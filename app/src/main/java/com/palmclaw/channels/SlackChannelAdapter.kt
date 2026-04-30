package com.palmclaw.channels

import com.palmclaw.attachments.AttachmentRecordRepository
import com.palmclaw.attachments.AttachmentRemoteLocator
import com.palmclaw.attachments.AttachmentRemoteLocatorJsonCodec
import android.util.Log
import com.palmclaw.bus.MessageAttachment
import com.palmclaw.bus.MessageAttachmentSource
import com.palmclaw.bus.InboundMessage
import com.palmclaw.bus.OutboundMessage
import com.palmclaw.bus.inferMessageAttachmentKind
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale
import java.util.concurrent.TimeUnit

data class SlackRouteRule(
    val responseMode: String = "mention",
    val allowedUserIds: Set<String> = emptySet()
)

class SlackChannelAdapter(
    override val adapterKey: String,
    botToken: String,
    appToken: String,
    allowedChannelIds: Set<String> = emptySet(),
    routeRules: Map<String, SlackRouteRule> = emptyMap()
) : ChannelAdapter {
    override val channelName: String = "slack"
    override val attachmentCapability: ChannelAttachmentCapability = ChannelAttachmentCapability(
        supportsInboundFiles = true,
        supportsOutboundFiles = true,
        requiresAuthenticatedDownload = true
    )

    private val botToken = botToken.trim()
    private val appToken = appToken.trim()

    private val routeRulesByChannel: Map<String, SlackRouteRule> = routeRules
        .mapNotNull { (rawChatId, rawRule) ->
            val chatId = rawChatId.trim().uppercase(Locale.US)
            if (chatId.isBlank()) return@mapNotNull null
            val rule = SlackRouteRule(
                responseMode = normalizeResponseMode(rawRule.responseMode),
                allowedUserIds = rawRule.allowedUserIds
                    .asSequence()
                    .map { it.trim() }
                    .filter { it.isNotBlank() }
                    .toSet()
            )
            chatId to rule
        }
        .toMap()
    private val defaultRouteRule = SlackRouteRule(
        responseMode = DEFAULT_RESPONSE_MODE,
        allowedUserIds = emptySet()
    )
    private val allowedChannels = (if (routeRulesByChannel.isNotEmpty()) {
        routeRulesByChannel.keys
    } else {
        allowedChannelIds
    })
        .map { it.trim().uppercase(Locale.US) }
        .filter { it.isNotBlank() }
        .toSet()

    private val restClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(35, TimeUnit.SECONDS)
        .build()

    private val wsClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    @Volatile
    private var runtimeScope: CoroutineScope? = null
    private var workerJob: Job? = null
    @Volatile
    private var webSocket: WebSocket? = null
    private val frameLock = Mutex()
    @Volatile
    private var expectedSocketClose: Boolean = false
    @Volatile
    private var botUserId: String? = null
    private val inboundDedupLock = Any()
    private val recentInboundKeys = linkedMapOf<String, Long>()

    override fun start(scope: CoroutineScope, publishInbound: suspend (InboundMessage) -> Unit) {
        if (botToken.isBlank() || appToken.isBlank() || workerJob != null) return
        ChannelRuntimeDiagnostics.reset(channelName, adapterKey)
        ChannelRuntimeDiagnostics.markRunning(channelName, adapterKey, true)
        SlackGatewayDiagnostics.reset(adapterKey)
        SlackGatewayDiagnostics.markRunning(adapterKey, true)
        synchronized(inboundDedupLock) { recentInboundKeys.clear() }
        expectedSocketClose = false
        runtimeScope = scope
        workerJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                try {
                    runSocketSession(publishInbound)
                } catch (t: Throwable) {
                    Log.e(TAG, "Slack socket mode loop failed", t)
                    ChannelRuntimeDiagnostics.markError(channelName, adapterKey, t.message ?: t.javaClass.simpleName)
                    SlackGatewayDiagnostics.markError(adapterKey, t.message ?: t.javaClass.simpleName)
                }
                if (isActive) {
                    delay(RECONNECT_DELAY_MS)
                }
            }
        }
    }

    override suspend fun send(message: OutboundMessage) {
        if (botToken.isBlank()) return
        withContext(Dispatchers.IO) {
            val isProgress = message.metadata["_progress"]?.equals("true", ignoreCase = true) == true
            if (isProgress) return@withContext

            val baseText = message.content.trim()
            val attachments = message.normalizedAttachments
            if (baseText.isBlank() && attachments.isEmpty()) return@withContext
            val chunks = splitMessage(baseText, MAX_MESSAGE_CHARS)
            val threadTs = resolveThreadTs(message)
            val chatId = message.chatId.trim().uppercase(Locale.US)
            if (attachments.isNotEmpty()) {
                sendAttachmentsMessage(
                    chatId = chatId,
                    text = chunks.firstOrNull().orEmpty(),
                    threadTs = threadTs,
                    attachments = attachments
                )
                chunks.drop(1).forEach { chunk ->
                    sendTextMessage(
                        chatId = chatId,
                        text = chunk,
                        threadTs = threadTs
                    )
                }
            } else {
                chunks.forEach { chunk ->
                    sendTextMessage(
                        chatId = chatId,
                        text = chunk,
                        threadTs = threadTs
                    )
                }
            }
        }
    }

    override fun canHandleOutbound(message: OutboundMessage): Boolean {
        val requestedKey = message.metadata[GatewayOrchestrator.KEY_ADAPTER_KEY]
            ?.trim()
            ?.ifBlank { null }
        if (requestedKey != null) {
            return requestedKey == adapterKey
        }
        val chatId = message.chatId.trim().uppercase(Locale.US)
        return chatId.isNotBlank() && (allowedChannels.isEmpty() || chatId in allowedChannels)
    }

    override fun stop() {
        expectedSocketClose = true
        workerJob?.cancel()
        workerJob = null
        webSocket?.cancel()
        webSocket = null
        runtimeScope = null
        ChannelRuntimeDiagnostics.markRunning(channelName, adapterKey, false)
        ChannelRuntimeDiagnostics.markConnected(channelName, adapterKey, false)
        SlackGatewayDiagnostics.markRunning(adapterKey, false)
        SlackGatewayDiagnostics.markConnected(adapterKey, false)
    }

    private suspend fun runSocketSession(
        publishInbound: suspend (InboundMessage) -> Unit
    ) {
        expectedSocketClose = false
        botUserId = runCatching { resolveBotUserId() }
            .onFailure { t -> Log.w(TAG, "Slack auth.test failed: ${t.message}") }
            .getOrNull()

        val socketUrl = openSocketUrl()
        val endSignal = CompletableDeferred<Unit>()
        val request = Request.Builder().url(socketUrl).build()
        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d(TAG, "Slack socket connected")
                ChannelRuntimeDiagnostics.markConnected(channelName, adapterKey, true)
                SlackGatewayDiagnostics.markConnected(adapterKey, true)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val scope = runtimeScope ?: return
                scope.launch(Dispatchers.IO) {
                    frameLock.withLock {
                        handleSocketFrame(webSocket, text, publishInbound, endSignal)
                    }
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.w(TAG, "Slack websocket closed code=$code reason=$reason")
                ChannelRuntimeDiagnostics.markConnected(channelName, adapterKey, false)
                SlackGatewayDiagnostics.markConnected(adapterKey, false)
                if (!expectedSocketClose) {
                    ChannelRuntimeDiagnostics.markError(channelName, adapterKey, "Socket closed: code=$code reason=${reason.ifBlank { "n/a" }}")
                    SlackGatewayDiagnostics.markError(adapterKey, "Socket closed: code=$code reason=${reason.ifBlank { "n/a" }}")
                }
                if (!endSignal.isCompleted) {
                    endSignal.complete(Unit)
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Log.w(TAG, "Slack websocket closing code=$code reason=$reason")
                ChannelRuntimeDiagnostics.markConnected(channelName, adapterKey, false)
                SlackGatewayDiagnostics.markConnected(adapterKey, false)
                if (!expectedSocketClose) {
                    ChannelRuntimeDiagnostics.markError(channelName, adapterKey, "Socket closing: code=$code reason=${reason.ifBlank { "n/a" }}")
                    SlackGatewayDiagnostics.markError(adapterKey, "Socket closing: code=$code reason=${reason.ifBlank { "n/a" }}")
                }
                webSocket.close(code, reason)
                if (!endSignal.isCompleted) {
                    endSignal.complete(Unit)
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.w(TAG, "Slack websocket failure: ${t.message}")
                ChannelRuntimeDiagnostics.markConnected(channelName, adapterKey, false)
                SlackGatewayDiagnostics.markConnected(adapterKey, false)
                val msg = t.message ?: t.javaClass.simpleName
                if (!(expectedSocketClose && msg.equals("Socket closed", ignoreCase = true))) {
                    ChannelRuntimeDiagnostics.markError(channelName, adapterKey, "Socket failure: $msg")
                    SlackGatewayDiagnostics.markError(adapterKey, "Socket failure: $msg")
                }
                if (!endSignal.isCompleted) {
                    endSignal.complete(Unit)
                }
            }
        }

        val socket = wsClient.newWebSocket(request, listener)
        webSocket = socket
        try {
            endSignal.await()
        } finally {
            expectedSocketClose = true
            runCatching { socket.close(1000, "session_end") }
            if (webSocket === socket) {
                webSocket = null
            }
        }
    }

    private suspend fun handleSocketFrame(
        socket: WebSocket,
        raw: String,
        publishInbound: suspend (InboundMessage) -> Unit,
        endSignal: CompletableDeferred<Unit>
    ) {
        val payload = runCatching { JSONObject(raw) }.getOrElse {
            Log.w(TAG, "Slack socket non-json frame ignored")
            return
        }
        val type = payload.optString("type").trim()
        if (type.isNotBlank()) {
            SlackGatewayDiagnostics.markEnvelopeType(adapterKey, type)
        }
        val envelopeId = payload.optString("envelope_id").trim()
        if (envelopeId.isNotBlank()) {
            sendEnvelopeAck(socket, envelopeId)
        }
        when (type) {
            "hello" -> {
                ChannelRuntimeDiagnostics.markReady(channelName, adapterKey)
                SlackGatewayDiagnostics.markReady(adapterKey, botUserId)
            }

            "events_api" -> {
                handleEventsApiPayload(payload.optJSONObject("payload"), publishInbound)
            }

            "disconnect" -> {
                val reason = payload.optString("reason").ifBlank {
                    payload.optJSONObject("debug_info")?.toString().orEmpty()
                }.ifBlank { "disconnect requested" }
                SlackGatewayDiagnostics.markError(adapterKey, "Socket disconnect: $reason")
                expectedSocketClose = true
                if (!endSignal.isCompleted) {
                    endSignal.complete(Unit)
                }
                socket.close(4000, reason.take(120))
            }
        }
    }

    private suspend fun handleEventsApiPayload(
        payload: JSONObject?,
        publishInbound: suspend (InboundMessage) -> Unit
    ) {
        if (payload == null) return
        val event = payload.optJSONObject("event") ?: return
        val eventType = event.optString("type").trim()
        if (eventType != "message" && eventType != "app_mention") return
        if (event.optString("subtype").trim().isNotBlank()) return
        val senderId = event.optString("user").trim()
        val channelId = event.optString("channel").trim().uppercase(Locale.US)
        if (senderId.isBlank() || channelId.isBlank()) return
        if (botUserId != null && senderId == botUserId) return
        SlackGatewayDiagnostics.markInboundSeen(adapterKey, channelId)

        val boundRouteChatId = when {
            channelId in allowedChannels -> channelId
            allowedChannels.isEmpty() -> channelId
            else -> return
        }

        val routeRule = routeRulesByChannel[boundRouteChatId] ?: defaultRouteRule
        if (routeRule.allowedUserIds.isNotEmpty() && senderId !in routeRule.allowedUserIds) {
            return
        }
        val channelType = event.optString("channel_type").trim().lowercase(Locale.US)
        val text = event.optString("text").orEmpty()
        val botId = botUserId
        // Slack can emit both `message` and `app_mention` for a single mention message.
        // Prefer `app_mention` to avoid duplicate inbound processing.
        if (eventType == "message" && botId != null && text.contains("<@$botId>")) {
            return
        }
        if (channelType != "im" && routeRule.responseMode == "mention" && !isBotMentioned(eventType, text)) {
            return
        }
        val parts = mutableListOf<String>()
        val inboundAttachments = mutableListOf<MessageAttachment>()
        val strippedText = stripBotMention(text)
        if (strippedText.isNotBlank()) {
            parts += strippedText
        }
        val files = event.optJSONArray("files")
        if (files != null) {
            for (i in 0 until files.length()) {
                val item = files.optJSONObject(i) ?: continue
                buildInboundAttachment(item)?.let { inboundAttachments += it }
            }
        }
        val normalizedText = when {
            parts.isNotEmpty() -> parts.joinToString("\n").trim()
            inboundAttachments.isNotEmpty() -> "Sent ${inboundAttachments.size} attachment(s)."
            else -> "[empty message]"
        }
        val messageTs = event.optString("ts").trim()
        val threadTs = event.optString("thread_ts").trim().ifBlank { null }
        val eventId = payload.optString("event_id").trim()
        val dedupKeys = buildList {
            if (eventId.isNotBlank()) add("event:$eventId")
            if (messageTs.isNotBlank()) add("msg:${boundRouteChatId}:${senderId}:$messageTs")
            val clientMsgId = event.optString("client_msg_id").trim()
            if (clientMsgId.isNotBlank()) add("client:${boundRouteChatId}:${senderId}:$clientMsgId")
        }
        if (shouldSkipInboundAsDuplicate(dedupKeys)) {
            Log.d(TAG, "Slack inbound dedup hit channel=$boundRouteChatId sender=$senderId ts=$messageTs")
            return
        }

        if (messageTs.isNotBlank()) {
            runCatching {
                addProcessingReaction(channelId, messageTs)
            }.onFailure { t ->
                Log.d(TAG, "Slack reaction add failed: ${t.message}")
            }
        }

        publishInbound(
            InboundMessage(
                channel = channelName,
                senderId = senderId,
                chatId = boundRouteChatId,
                content = normalizedText,
                attachments = inboundAttachments,
                metadata = buildMap {
                    put(GatewayOrchestrator.KEY_ADAPTER_KEY, adapterKey)
                    val replyTs = threadTs ?: messageTs
                    if (replyTs.isNotBlank()) put("message_id", replyTs)
                    if (messageTs.isNotBlank()) put("source_message_ts", messageTs)
                    if (threadTs != null) put("thread_ts", threadTs)
                    if (channelType.isNotBlank()) put("slack_channel_type", channelType)
                }
            )
        )
        SlackGatewayDiagnostics.markInboundForwarded(adapterKey, boundRouteChatId)
    }

    private fun shouldSkipInboundAsDuplicate(keys: List<String>): Boolean {
        if (keys.isEmpty()) return false
        val now = System.currentTimeMillis()
        synchronized(inboundDedupLock) {
            val cutoff = now - DEDUP_TTL_MS
            if (recentInboundKeys.isNotEmpty()) {
                val iter = recentInboundKeys.entries.iterator()
                while (iter.hasNext()) {
                    if (iter.next().value < cutoff) {
                        iter.remove()
                    }
                }
            }
            if (keys.any { recentInboundKeys.containsKey(it) }) {
                return true
            }
            keys.forEach { key ->
                recentInboundKeys[key] = now
            }
            while (recentInboundKeys.size > MAX_DEDUP_KEYS) {
                val firstKey = recentInboundKeys.entries.firstOrNull()?.key ?: break
                recentInboundKeys.remove(firstKey)
            }
            return false
        }
    }

    private fun sendEnvelopeAck(socket: WebSocket, envelopeId: String) {
        val ack = JSONObject().put("envelope_id", envelopeId).toString()
        if (!socket.send(ack)) {
            SlackGatewayDiagnostics.markError(adapterKey, "Envelope ack failed")
        }
    }

    private suspend fun openSocketUrl(): String {
        val response = postSlackApiJson(
            method = "apps.connections.open",
            token = appToken,
            payload = JSONObject()
        )
        val url = response.optString("url").trim()
        if (url.isBlank()) {
            throw IllegalStateException("Slack apps.connections.open returned empty URL")
        }
        return url
    }

    private suspend fun resolveBotUserId(): String {
        val response = postSlackApiJson(
            method = "auth.test",
            token = botToken,
            payload = JSONObject()
        )
        return response.optString("user_id").trim()
    }

    private suspend fun sendTextMessage(chatId: String, text: String, threadTs: String?) {
        val payload = JSONObject()
            .put("channel", chatId)
            .put("text", text)
        if (!threadTs.isNullOrBlank() && isThreadCapableChannel(chatId)) {
            payload.put("thread_ts", threadTs)
        }
        postSlackApiJson(
            method = "chat.postMessage",
            token = botToken,
            payload = payload
        )
        SlackGatewayDiagnostics.markOutboundSent(adapterKey)
    }

    private suspend fun sendAttachmentsMessage(
        chatId: String,
        text: String,
        threadTs: String?,
        attachments: List<MessageAttachment>
    ) {
        attachments.forEachIndexed { index, attachment ->
            sendSingleAttachment(
                chatId = chatId,
                attachment = attachment,
                threadTs = threadTs,
                initialComment = if (index == 0) text else ""
            )
        }
    }

    private suspend fun sendSingleAttachment(
        chatId: String,
        attachment: MessageAttachment,
        threadTs: String?,
        initialComment: String
    ) {
        val reference = attachment.localWorkspacePath?.takeIf { it.isNotBlank() } ?: attachment.reference
        val file = File(reference)
        require(file.exists()) { "Attachment file not found: $reference" }
        val upload = requestExternalUploadUrl(file, attachment)
        uploadExternalBinary(upload.uploadUrl, file, attachment)
        completeExternalUpload(
            fileId = upload.fileId,
            title = attachment.label.ifBlank { file.name },
            chatId = chatId,
            threadTs = threadTs,
            initialComment = initialComment
        )
        SlackGatewayDiagnostics.markOutboundSent(adapterKey)
    }

    private suspend fun requestExternalUploadUrl(
        file: File,
        attachment: MessageAttachment
    ): SlackExternalUpload {
        val response = postSlackApiJson(
            method = "files.getUploadURLExternal",
            token = botToken,
            payload = JSONObject()
                .put("filename", attachment.label.ifBlank { file.name })
                .put("length", file.length().toString())
        )
        val uploadUrl = response.optString("upload_url").trim()
        val fileId = response.optString("file_id").trim()
        require(uploadUrl.isNotBlank()) { "Slack upload URL is empty" }
        require(fileId.isNotBlank()) { "Slack file ID is empty" }
        return SlackExternalUpload(
            uploadUrl = uploadUrl,
            fileId = fileId
        )
    }

    private fun uploadExternalBinary(
        uploadUrl: String,
        file: File,
        attachment: MessageAttachment
    ) {
        val request = Request.Builder()
            .url(uploadUrl)
            .post(file.asRequestBody((attachment.mimeType ?: "application/octet-stream").toMediaType()))
            .build()
        restClient.newCall(request).execute().use { response ->
            val raw = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IllegalStateException("Slack external upload HTTP ${response.code}: ${raw.take(300)}")
            }
        }
    }

    private suspend fun completeExternalUpload(
        fileId: String,
        title: String,
        chatId: String,
        threadTs: String?,
        initialComment: String
    ) {
        val payload = JSONObject()
            .put(
                "files",
                JSONArray().put(
                    JSONObject()
                        .put("id", fileId)
                        .put("title", title)
                )
            )
            .put("channel_id", chatId)
        if (!threadTs.isNullOrBlank() && isThreadCapableChannel(chatId)) {
            payload.put("thread_ts", threadTs)
        }
        if (initialComment.isNotBlank()) {
            payload.put("initial_comment", initialComment)
        }
        postSlackApiJson(
            method = "files.completeUploadExternal",
            token = botToken,
            payload = payload
        )
    }

    private suspend fun addProcessingReaction(channelId: String, messageTs: String) {
        postSlackApiJson(
            method = "reactions.add",
            token = botToken,
            payload = JSONObject()
                .put("channel", channelId)
                .put("name", DEFAULT_REACT_EMOJI)
                .put("timestamp", messageTs),
            ignoredSlackErrors = setOf("already_reacted", "missing_scope", "not_reactable")
        )
    }

    private suspend fun postSlackApiJson(
        method: String,
        token: String,
        payload: JSONObject,
        ignoredSlackErrors: Set<String> = emptySet()
    ): JSONObject {
        val url = "$SLACK_API_BASE/$method"
        var lastError: String? = null
        repeat(MAX_SEND_ATTEMPTS) { attempt ->
            var retryDelayMs: Long? = null
            try {
                val form = FormBody.Builder().apply {
                    val keys = payload.keys()
                    while (keys.hasNext()) {
                        val key = keys.next()
                        val value = payload.opt(key)?.toString().orEmpty()
                        add(key, value)
                    }
                }.build()
                val request = Request.Builder()
                    .url(url)
                    .header("Authorization", "Bearer $token")
                    .post(form)
                    .build()
                restClient.newCall(request).execute().use { response ->
                    val raw = response.body?.string().orEmpty()
                    if (response.code == 429) {
                        val retryHeader = response.header("Retry-After")
                            ?.trim()
                            ?.toDoubleOrNull()
                        retryDelayMs = ((retryHeader ?: 1.0) * 1000.0).toLong().coerceAtLeast(500L)
                        return@use
                    }
                    if (!response.isSuccessful) {
                        throw IllegalStateException("Slack $method HTTP ${response.code}: ${raw.take(300)}")
                    }
                    val json = runCatching { JSONObject(raw) }
                        .getOrElse {
                            throw IllegalStateException("Slack $method returned non-JSON response")
                        }
                    val ok = json.optBoolean("ok", false)
                    if (!ok) {
                        val error = json.optString("error").ifBlank { "unknown_error" }
                        if (error in ignoredSlackErrors) {
                            return json
                        }
                        if (error.equals("ratelimited", ignoreCase = true)) {
                            retryDelayMs = 1_000L
                            return@use
                        }
                        throw IllegalStateException("Slack $method error: $error")
                    }
                    return json
                }
                if (retryDelayMs != null) {
                    lastError = "Slack $method retry requested"
                    if (attempt < MAX_SEND_ATTEMPTS - 1) {
                        delay(retryDelayMs ?: 1_000L)
                    }
                }
            } catch (t: Throwable) {
                lastError = t.message ?: t.javaClass.simpleName
                if (attempt >= MAX_SEND_ATTEMPTS - 1) throw t
                delay(1_000L)
            }
        }
        throw IllegalStateException(lastError ?: "Slack $method failed after retries")
    }

    private fun resolveThreadTs(message: OutboundMessage): String? {
        val candidate = message.replyTo
            ?: message.metadata["thread_ts"]
            ?: message.metadata["message_id"]
        return candidate?.trim()?.ifBlank { null }
    }

    private fun isThreadCapableChannel(chatId: String): Boolean {
        val id = chatId.trim().uppercase(Locale.US)
        return id.startsWith("C") || id.startsWith("G")
    }

    private fun isBotMentioned(eventType: String, text: String): Boolean {
        if (eventType == "app_mention") return true
        val botId = botUserId ?: return false
        return text.contains("<@$botId>")
    }

    private fun stripBotMention(text: String): String {
        val botId = botUserId ?: return text.trim()
        return text.replace(Regex("<@${Regex.escape(botId)}>\\s*"), "").trim()
    }

    private fun buildInboundAttachment(item: JSONObject): MessageAttachment? {
        val reference = item.optString("url_private_download").trim().ifBlank {
            item.optString("url_private").trim()
        }
        if (reference.isBlank()) return null
        val locator = AttachmentRemoteLocator.BearerUrl(
            url = reference,
            bearerToken = botToken
        )
        return MessageAttachment(
            kind = inferMessageAttachmentKind(
                reference = reference,
                explicitMimeType = item.optString("mimetype").trim().ifBlank { null }
            ),
            reference = reference,
            label = item.optString("name").trim().ifBlank { "file" },
            mimeType = item.optString("mimetype").trim().ifBlank { null },
            sizeBytes = item.optLong("size").takeIf { it > 0 },
            source = MessageAttachmentSource.Remote,
            isRemoteBacked = true,
            metadata = buildMap {
                put("source_channel", channelName)
                AttachmentRemoteLocatorJsonCodec.encode(locator)?.let {
                    put(AttachmentRecordRepository.KEY_REMOTE_LOCATOR, it)
                }
            }
        )
    }

    private fun splitMessage(text: String, maxChars: Int): List<String> {
        if (text.length <= maxChars) return listOf(text)
        val chunks = mutableListOf<String>()
        var start = 0
        while (start < text.length) {
            val remaining = text.length - start
            if (remaining <= maxChars) {
                chunks += text.substring(start)
                break
            }
            val end = start + maxChars
            val newline = text.lastIndexOf('\n', end).takeIf { it > start + maxChars / 2 } ?: -1
            val splitAt = if (newline > 0) newline else end
            chunks += text.substring(start, splitAt).trimEnd()
            start = splitAt
            while (start < text.length && text[start] == '\n') {
                start += 1
            }
        }
        return chunks.filter { it.isNotBlank() }
    }

    companion object {
        private const val TAG = "SlackAdapter"
        private const val SLACK_API_BASE = "https://slack.com/api"
        private const val RECONNECT_DELAY_MS = 5_000L
        private const val MAX_MESSAGE_CHARS = 3500
        private const val MAX_SEND_ATTEMPTS = 3
        private const val DEFAULT_RESPONSE_MODE = "mention"
        private const val DEFAULT_REACT_EMOJI = "eyes"
        private const val DEDUP_TTL_MS = 10 * 60 * 1000L
        private const val MAX_DEDUP_KEYS = 2_000
    }

    private data class SlackExternalUpload(
        val uploadUrl: String,
        val fileId: String
    )

    private fun normalizeResponseMode(raw: String): String {
        return when (raw.trim().lowercase(Locale.US)) {
            "open" -> "open"
            else -> DEFAULT_RESPONSE_MODE
        }
    }
}
