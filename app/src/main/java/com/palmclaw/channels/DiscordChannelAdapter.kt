package com.palmclaw.channels

import android.util.Log
import com.palmclaw.bus.MessageAttachment
import com.palmclaw.bus.MessageAttachmentSource
import com.palmclaw.bus.InboundMessage
import com.palmclaw.bus.OutboundMessage
import com.palmclaw.bus.inferMessageAttachmentKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.io.File
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.random.Random

data class DiscordRouteRule(
    val responseMode: String = "mention",
    val allowedUserIds: Set<String> = emptySet()
)

class DiscordChannelAdapter(
    override val adapterKey: String,
    botToken: String,
    allowedChannelIds: Set<String> = emptySet(),
    private val groupPolicy: String = DEFAULT_GROUP_POLICY,
    private val gatewayUrl: String = DEFAULT_GATEWAY_URL,
    routeRules: Map<String, DiscordRouteRule> = emptyMap()
) : ChannelAdapter {
    override val channelName: String = "discord"
    override val attachmentCapability: ChannelAttachmentCapability = ChannelAttachmentCapability(
        supportsInboundFiles = true,
        supportsOutboundFiles = true,
        requiresAuthenticatedDownload = false
    )

    private val token = botToken.trim().removePrefix("Bot ").removePrefix("bot ").trim()

    private val routeRulesByChannel: Map<String, DiscordRouteRule> = routeRules
        .mapNotNull { (rawChatId, rawRule) ->
            val chatId = rawChatId.trim()
            if (chatId.isBlank()) return@mapNotNull null
            val rule = DiscordRouteRule(
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
    private val defaultRouteRule = DiscordRouteRule(
        responseMode = normalizeResponseMode(groupPolicy),
        allowedUserIds = emptySet()
    )
    private val allowedChannels = (if (routeRulesByChannel.isNotEmpty()) {
        routeRulesByChannel.keys
    } else {
        allowedChannelIds
    })
        .map { it.trim() }
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
    private var heartbeatJob: Job? = null
    @Volatile
    private var webSocket: WebSocket? = null
    @Volatile
    private var botUserId: String? = null
    private val typingTasks = mutableMapOf<String, Job>()
    private val typingLock = Any()
    private val frameLock = Mutex()
    @Volatile
    private var activeSessionGate: ChannelSessionTerminationGate? = null
    @Volatile
    private var identifyUseDollarKeys: Boolean = false
    private val runtimeHealth = ChannelAdapterRuntimeHealth(channelName, adapterKey)
    private val gatewayRecovery = DiscordGatewayRecovery(gatewayUrl)
    private val heartbeatWatchdog = DiscordHeartbeatWatchdog()

    override fun start(scope: CoroutineScope, publishInbound: suspend (InboundMessage) -> Unit) {
        if (workerJob != null) return
        if (token.isBlank()) {
            runtimeHealth.starting()
            runtimeHealth.blocked(ChannelRuntimeErrorCode.CONFIGURATION_INVALID)
            return
        }
        runtimeHealth.starting()
        gatewayRecovery.clear()
        heartbeatWatchdog.reset()
        botUserId = null
        DiscordGatewayDiagnostics.reset(adapterKey)
        DiscordGatewayDiagnostics.markRunning(adapterKey, true)
        runtimeScope = scope
        workerJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                try {
                    runGatewaySession(publishInbound)
                } catch (t: Throwable) {
                    if (t is CancellationException) throw t
                    val safeError = safeChannelErrorSummary(t)
                    Log.w(TAG, "Discord gateway loop failed: $safeError")
                    runtimeHealth.failure(t)
                    DiscordGatewayDiagnostics.markError(adapterKey, safeError)
                }
                if (isActive) {
                    if (!runtimeHealth.awaitReconnect()) {
                        workerJob = null
                        break
                    }
                }
            }
        }
    }

    override suspend fun send(message: OutboundMessage) {
        if (token.isBlank()) return
        withContext(Dispatchers.IO) {
            val isProgress = message.metadata["_progress"]?.equals("true", ignoreCase = true) == true
            if (!isProgress) {
                stopTyping(message.chatId)
            } else {
                return@withContext
            }

            val baseText = message.content.trim()
            val attachments = message.normalizedAttachments
            val text = baseText
            if (text.isBlank() && attachments.isEmpty()) return@withContext
            runtimeHealth.runOperation(ChannelOperation.OUTBOUND) {
                val chunks = splitMessage(text, MAX_MESSAGE_CHARS)
                val replyTo = message.replyTo ?: message.metadata["reply_to"]
                if (attachments.isNotEmpty()) {
                    sendMessageWithAttachments(
                        chatId = message.chatId,
                        text = chunks.firstOrNull().orEmpty(),
                        replyTo = replyTo,
                        attachments = attachments
                    )
                    chunks.drop(1).forEach { chunk ->
                        sendTextMessage(chatId = message.chatId, text = chunk, replyTo = null)
                    }
                } else {
                    chunks.forEachIndexed { index, chunk ->
                        sendTextMessage(
                            chatId = message.chatId,
                            text = chunk,
                            replyTo = if (index == 0) replyTo else null
                        )
                    }
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
        val chatId = message.chatId.trim()
        return chatId.isNotBlank() && (allowedChannels.isEmpty() || chatId in allowedChannels)
    }

    override fun stop() {
        activeSessionGate?.claim()
        activeSessionGate = null
        workerJob?.cancel()
        workerJob = null
        heartbeatJob?.cancel()
        heartbeatJob = null
        webSocket?.cancel()
        webSocket = null
        runtimeScope = null
        gatewayRecovery.clear()
        heartbeatWatchdog.reset()
        stopAllTyping()
        runtimeHealth.stopped()
        DiscordGatewayDiagnostics.markRunning(adapterKey, false)
        DiscordGatewayDiagnostics.markConnected(adapterKey, false)
    }

    private suspend fun runGatewaySession(
        publishInbound: suspend (InboundMessage) -> Unit
    ) {
        val endSignal = CompletableDeferred<Unit>()
        currentCoroutineContext().ensureActive()
        val terminationGate = ChannelSessionTerminationGate()
        activeSessionGate = terminationGate
        val request = Request.Builder().url(gatewayRecovery.nextGatewayUrl()).build()
        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (terminationGate.isClaimed()) return
                Log.d(TAG, "Discord gateway connected")
                runtimeHealth.connected()
                DiscordGatewayDiagnostics.markConnected(adapterKey, true)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val scope = runtimeScope ?: return
                if (terminationGate.isClaimed()) return
                scope.launch(Dispatchers.IO) {
                    frameLock.withLock {
                        handleGatewayFrame(webSocket, text, publishInbound, endSignal, terminationGate)
                    }
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (terminationGate.claim()) {
                    Log.w(TAG, "Discord websocket closed code=$code")
                    handleGatewayClose(code, reason, "Gateway closed")
                    endSignal.complete(Unit)
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                if (terminationGate.claim()) {
                    Log.w(TAG, "Discord websocket closing code=$code")
                    handleGatewayClose(code, reason, "Gateway closing")
                    endSignal.complete(Unit)
                }
                webSocket.close(code, reason)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (terminationGate.claim()) {
                    val safeError = safeChannelErrorSummary(t)
                    Log.w(TAG, "Discord websocket failure: $safeError")
                    ChannelRuntimeDiagnostics.markConnected(channelName, adapterKey, false)
                    DiscordGatewayDiagnostics.markConnected(adapterKey, false)
                    runtimeHealth.failure(t)
                    DiscordGatewayDiagnostics.markError(adapterKey, safeError)
                    endSignal.complete(Unit)
                }
            }
        }

        val socket = wsClient.newWebSocket(request, listener)
        webSocket = socket
        try {
            endSignal.await()
        } finally {
            terminationGate.claim()
            if (activeSessionGate === terminationGate) {
                heartbeatJob?.cancel()
                heartbeatJob = null
                heartbeatWatchdog.reset()
                activeSessionGate = null
            }
            runCatching { socket.close(1000, "session_end") }
            if (webSocket === socket) {
                webSocket = null
            }
        }
    }

    private fun handleGatewayClose(code: Int, reason: String, transientMessage: String) {
        ChannelRuntimeDiagnostics.markConnected(channelName, adapterKey, false)
        DiscordGatewayDiagnostics.markConnected(adapterKey, false)
        maybeSwitchIdentifyMode(code, reason)
        val disposition = gatewayRecovery.onClose(code)

        when (disposition) {
            DiscordGatewayCloseDisposition.BLOCK_AUTHENTICATION -> {
                runtimeHealth.blocked(ChannelRuntimeErrorCode.AUTHENTICATION_FAILED)
                DiscordGatewayDiagnostics.markError(adapterKey, "Authentication required")
            }

            DiscordGatewayCloseDisposition.BLOCK_CONFIGURATION -> {
                runtimeHealth.blocked(ChannelRuntimeErrorCode.CONFIGURATION_INVALID)
                DiscordGatewayDiagnostics.markError(adapterKey, "Configuration required")
            }

            DiscordGatewayCloseDisposition.RETRY_IDENTIFY,
            DiscordGatewayCloseDisposition.RETRY_RESUME -> {
                runtimeHealth.failure(transientMessage)
                DiscordGatewayDiagnostics.markError(adapterKey, "Connection interrupted")
            }
        }
    }

    private suspend fun handleGatewayFrame(
        socket: WebSocket,
        raw: String,
        publishInbound: suspend (InboundMessage) -> Unit,
        endSignal: CompletableDeferred<Unit>,
        terminationGate: ChannelSessionTerminationGate
    ) {
        if (terminationGate.isClaimed()) return
        val payload = runCatching { JSONObject(raw) }.getOrElse {
            Log.w(TAG, "Discord gateway non-json frame ignored")
            return
        }
        if (!payload.isNull("s")) {
            gatewayRecovery.recordSequence(payload.optLong("s"))
        }
        when (payload.optInt("op", -1)) {
            OP_HELLO -> {
                val heartbeatIntervalMs = payload.optJSONObject("d")
                    ?.optLong("heartbeat_interval", DEFAULT_HEARTBEAT_INTERVAL_MS)
                    ?: DEFAULT_HEARTBEAT_INTERVAL_MS
                if (!sendHandshake(socket)) {
                    terminateGatewaySession(socket, endSignal, terminationGate, "Gateway handshake send failed")
                    return
                }
                heartbeatWatchdog.reset()
                startHeartbeat(socket, heartbeatIntervalMs, endSignal, terminationGate)
            }

            OP_DISPATCH -> {
                when (payload.optString("t")) {
                    "READY" -> {
                        val ready = payload.optJSONObject("d")
                        gatewayRecovery.recordReady(
                            sessionId = ready?.optString("session_id"),
                            resumeGatewayUrl = ready?.optString("resume_gateway_url")
                        )
                        val user = ready?.optJSONObject("user")
                        botUserId = user?.optString("id")?.trim().orEmpty().ifBlank { null }
                        Log.d(TAG, "Discord READY as bot=$botUserId")
                        runtimeHealth.succeeded(ChannelOperation.AUTHENTICATION)
                        DiscordGatewayDiagnostics.markReady(adapterKey, botUserId)
                    }

                    "RESUMED" -> {
                        runtimeHealth.succeeded(ChannelOperation.AUTHENTICATION)
                        DiscordGatewayDiagnostics.markReady(adapterKey, botUserId)
                    }

                    "MESSAGE_CREATE" -> {
                        handleMessageCreate(payload.optJSONObject("d"), publishInbound)
                    }
                }
            }

            OP_HEARTBEAT -> {
                sendHeartbeat(socket, endSignal, terminationGate)
            }

            OP_HEARTBEAT_ACK -> {
                heartbeatWatchdog.acknowledge()
                runtimeHealth.succeeded(ChannelOperation.HEARTBEAT)
            }

            OP_RECONNECT -> {
                terminateGatewaySession(socket, endSignal, terminationGate, "Gateway reconnect requested")
            }

            OP_INVALID_SESSION -> {
                gatewayRecovery.onInvalidSession(payload.optBoolean("d", false))
                terminateGatewaySession(socket, endSignal, terminationGate, "Gateway session invalid")
            }
        }
    }

    private fun startHeartbeat(
        socket: WebSocket,
        intervalMs: Long,
        endSignal: CompletableDeferred<Unit>,
        terminationGate: ChannelSessionTerminationGate
    ) {
        heartbeatJob?.cancel()
        val scope = runtimeScope ?: return
        heartbeatJob = scope.launch(Dispatchers.IO) {
            val safeInterval = intervalMs.coerceAtLeast(1_000L)
            delay(Random.nextLong(0L, safeInterval))
            while (isActive) {
                if (!sendHeartbeat(socket, endSignal, terminationGate)) break
                delay(safeInterval)
            }
        }
    }

    private fun sendHeartbeat(
        socket: WebSocket,
        endSignal: CompletableDeferred<Unit>,
        terminationGate: ChannelSessionTerminationGate
    ): Boolean {
        if (!heartbeatWatchdog.beginHeartbeat()) {
            terminateGatewaySession(socket, endSignal, terminationGate, "Heartbeat ACK timeout")
            return false
        }
        val data = JSONObject()
            .put("op", OP_HEARTBEAT)
            .put("d", gatewayRecovery.sequence())
        if (!sendGatewayPayload(socket, data, "heartbeat")) {
            heartbeatWatchdog.reset()
            terminateGatewaySession(socket, endSignal, terminationGate, "Heartbeat send failed")
            return false
        }
        return true
    }

    private fun sendHandshake(socket: WebSocket): Boolean =
        when (val handshake = gatewayRecovery.nextHandshake()) {
            DiscordGatewayHandshake.Identify -> sendIdentify(socket)
            is DiscordGatewayHandshake.Resume -> sendResume(socket, handshake)
        }

    private fun sendIdentify(socket: WebSocket): Boolean {
        val properties = if (identifyUseDollarKeys) {
            JSONObject()
                .put("\$os", "android")
                .put("\$browser", "palmclaw")
                .put("\$device", "palmclaw")
        } else {
            JSONObject()
                .put("os", "android")
                .put("browser", "palmclaw")
                .put("device", "palmclaw")
        }
        val identifyData = JSONObject()
            .put("token", token)
            .put("intents", DEFAULT_INTENTS)
            .put("properties", properties)
            .put("compress", false)
        val identify = JSONObject()
            .put("op", OP_IDENTIFY)
            .put("d", identifyData)
        return sendGatewayPayload(socket, identify, "identify")
    }

    private fun sendResume(
        socket: WebSocket,
        handshake: DiscordGatewayHandshake.Resume
    ): Boolean {
        val resume = JSONObject()
            .put("op", OP_RESUME)
            .put(
                "d",
                JSONObject()
                    .put("token", token)
                    .put("session_id", handshake.sessionId)
                    .put("seq", handshake.sequence)
            )
        return sendGatewayPayload(socket, resume, "resume")
    }

    private fun sendGatewayPayload(socket: WebSocket, payload: JSONObject, tag: String): Boolean {
        val raw = payload.toString()
        DiscordGatewayDiagnostics.markGatewayPayload(adapterKey, "$tag sent")
        val ok = socket.send(raw)
        if (!ok) {
            DiscordGatewayDiagnostics.markError(adapterKey, "Gateway send failed: $tag")
        }
        return ok
    }

    private fun terminateGatewaySession(
        socket: WebSocket,
        endSignal: CompletableDeferred<Unit>,
        terminationGate: ChannelSessionTerminationGate,
        message: String
    ) {
        if (!terminationGate.claim()) return
        ChannelRuntimeDiagnostics.markConnected(channelName, adapterKey, false)
        DiscordGatewayDiagnostics.markConnected(adapterKey, false)
        runtimeHealth.failure(message)
        DiscordGatewayDiagnostics.markError(adapterKey, safeChannelErrorSummary(message))
        endSignal.complete(Unit)
        socket.close(4000, "reconnect")
    }

    private fun maybeSwitchIdentifyMode(code: Int, reason: String?) {
        if (code != 4002) return
        if (!reason.orEmpty().contains("decoding payload", ignoreCase = true)) return
        identifyUseDollarKeys = !identifyUseDollarKeys
    }

    private suspend fun handleMessageCreate(
        payload: JSONObject?,
        publishInbound: suspend (InboundMessage) -> Unit
    ) {
        if (payload == null) return
        val author = payload.optJSONObject("author")
        if (author?.optBoolean("bot", false) == true) return
        val senderId = author?.optString("id").orEmpty().trim()
        val channelId = payload.optString("channel_id").trim()
        if (senderId.isBlank() || channelId.isBlank()) return
        DiscordGatewayDiagnostics.markInboundSeen(adapterKey, channelId)
        val parentId = payload.optString("parent_id").trim().ifBlank { null }
        val boundRouteChatId = when {
            channelId in allowedChannels -> channelId
            parentId != null && parentId in allowedChannels -> parentId
            allowedChannels.isEmpty() -> channelId
            else -> return
        }

        val guildId = payload.optString("guild_id").trim().ifBlank { null }
        val content = payload.optString("content").orEmpty()
        val routeRule = routeRulesByChannel[boundRouteChatId] ?: defaultRouteRule
        if (routeRule.allowedUserIds.isNotEmpty() && senderId !in routeRule.allowedUserIds) {
            return
        }
        if (guildId != null && routeRule.responseMode == "mention" && !isBotMentioned(payload, content)) {
            return
        }

        val parts = mutableListOf<String>()
        val inboundAttachments = mutableListOf<MessageAttachment>()
        if (content.isNotBlank()) {
            parts += content
        }
        val attachments = payload.optJSONArray("attachments")
        if (attachments != null) {
            for (i in 0 until attachments.length()) {
                val item = attachments.optJSONObject(i) ?: continue
                val name = item.optString("filename").ifBlank { "attachment" }
                val url = item.optString("url").ifBlank { "" }
                if (url.isNotBlank()) {
                    inboundAttachments += MessageAttachment(
                        kind = inferMessageAttachmentKind(
                            reference = url,
                            explicitMimeType = item.optString("content_type").trim().ifBlank { null }
                        ),
                        reference = url,
                        label = name,
                        mimeType = item.optString("content_type").trim().ifBlank { null },
                        source = MessageAttachmentSource.Remote,
                        isRemoteBacked = true,
                        metadata = mapOf("source_channel" to channelName)
                    )
                }
            }
        }
        val normalized = when {
            parts.isNotEmpty() -> parts.joinToString("\n").trim()
            inboundAttachments.isNotEmpty() -> "Sent ${inboundAttachments.size} attachment(s)."
            else -> "[empty message]"
        }
        val messageId = payload.optString("id").trim()
        val replyTo = payload.optJSONObject("referenced_message")
            ?.optString("id")
            ?.trim()
            ?.ifBlank { null }
        startTyping(channelId)
        publishInbound(
            InboundMessage(
                channel = channelName,
                senderId = senderId,
                chatId = boundRouteChatId,
                content = normalized,
                attachments = inboundAttachments,
                metadata = buildMap {
                    put(GatewayOrchestrator.KEY_ADAPTER_KEY, adapterKey)
                    if (messageId.isNotBlank()) put("message_id", messageId)
                    if (!guildId.isNullOrBlank()) put("guild_id", guildId)
                    if (!replyTo.isNullOrBlank()) put("reply_to", replyTo)
                    if (!parentId.isNullOrBlank()) put("parent_id", parentId)
                    if (boundRouteChatId != channelId) {
                        put("source_channel_id", channelId)
                    }
                }
            )
        )
        runtimeHealth.succeeded(ChannelOperation.INBOUND)
        DiscordGatewayDiagnostics.markInboundForwarded(adapterKey, boundRouteChatId)
    }

    private fun isBotMentioned(payload: JSONObject, content: String): Boolean {
        val botId = botUserId ?: return false
        val mentions = payload.optJSONArray("mentions")
        if (mentions != null) {
            for (i in 0 until mentions.length()) {
                val mention = mentions.optJSONObject(i) ?: continue
                if (mention.optString("id").trim() == botId) return true
            }
        }
        return content.contains("<@$botId>") || content.contains("<@!$botId>")
    }

    private suspend fun sendTextMessage(chatId: String, text: String, replyTo: String?) {
        val payload = JSONObject()
            .put("content", text)
        if (!replyTo.isNullOrBlank()) {
            payload.put("message_reference", JSONObject().put("message_id", replyTo))
            payload.put("allowed_mentions", JSONObject().put("replied_user", false))
        }
        postJsonWithRetry(
            url = "$DISCORD_API_BASE/channels/$chatId/messages",
            payload = payload
        )
    }

    private suspend fun sendMessageWithAttachments(
        chatId: String,
        text: String,
        replyTo: String?,
        attachments: List<MessageAttachment>
    ) {
        val payload = JSONObject()
        if (text.isNotBlank()) {
            payload.put("content", text)
        }
        if (!replyTo.isNullOrBlank()) {
            payload.put(
                "message_reference",
                JSONObject().put("message_id", replyTo)
            )
        }
        val multipart = MultipartBody.Builder().setType(MultipartBody.FORM).apply {
            addFormDataPart("payload_json", payload.toString())
            attachments.forEachIndexed { index, attachment ->
                val reference = attachment.localWorkspacePath?.takeIf { it.isNotBlank() } ?: attachment.reference
                val file = File(reference)
                require(file.exists()) { "Discord attachment file not found: $reference" }
                val mediaType = (attachment.mimeType ?: "application/octet-stream").toMediaType()
                addFormDataPart(
                    "files[$index]",
                    attachment.label.ifBlank { file.name },
                    file.asRequestBody(mediaType)
                )
            }
        }.build()
        val request = Request.Builder()
            .url("https://discord.com/api/v10/channels/${chatId.trim()}/messages")
            .header("Authorization", "Bot $token")
            .post(multipart)
            .build()
        restClient.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IllegalStateException("Discord file send HTTP ${response.code}: ${body.take(300)}")
            }
        }
    }

    private suspend fun postJsonWithRetry(url: String, payload: JSONObject) {
        val bodyMedia = "application/json; charset=utf-8".toMediaType()
        var delivered = false
        repeat(MAX_SEND_ATTEMPTS) { attempt ->
            var retryDelayMs: Long? = null
            val request = Request.Builder()
                .url(url)
                .header("Authorization", "Bot $token")
                .header("Content-Type", "application/json")
                .post(payload.toString().toRequestBody(bodyMedia))
                .build()
            val retried = runCatching {
                restClient.newCall(request).execute().use { response ->
                    val raw = response.body?.string().orEmpty()
                    if (response.code == 429) {
                        val retrySeconds = runCatching {
                            JSONObject(raw).optDouble("retry_after", 1.0)
                        }.getOrDefault(1.0)
                        retryDelayMs = (retrySeconds * 1000.0).toLong().coerceAtLeast(500L)
                        return@use true
                    }
                    if (!response.isSuccessful) {
                        throw IllegalStateException("Discord HTTP ${response.code}: ${raw.take(300)}")
                    }
                    delivered = true
                    DiscordGatewayDiagnostics.markOutboundSent(adapterKey)
                    return@use false
                }
            }.getOrElse { t ->
                if (attempt >= MAX_SEND_ATTEMPTS - 1) throw t
                false
            }
            if (delivered) return
            if (retried) {
                delay(retryDelayMs ?: 1_000L)
                return@repeat
            }
            if (attempt < MAX_SEND_ATTEMPTS - 1) {
                delay(1_000L)
            }
        }
        if (!delivered) {
            throw IllegalStateException("Discord send failed after retries")
        }
    }

    private fun startTyping(channelId: String) {
        val scope = runtimeScope ?: return
        stopTyping(channelId)
        val job = scope.launch(Dispatchers.IO) {
            var elapsed = 0L
            while (isActive && elapsed < MAX_TYPING_DURATION_MS) {
                runCatching { sendTyping(channelId) }
                delay(TYPING_INTERVAL_MS)
                elapsed += TYPING_INTERVAL_MS
            }
        }
        synchronized(typingLock) {
            typingTasks[channelId] = job
        }
    }

    private fun stopTyping(channelId: String) {
        val task = synchronized(typingLock) { typingTasks.remove(channelId) }
        task?.cancel()
    }

    private fun stopAllTyping() {
        val tasks = synchronized(typingLock) {
            val all = typingTasks.values.toList()
            typingTasks.clear()
            all
        }
        tasks.forEach { it.cancel() }
    }

    private fun sendTyping(channelId: String) {
        val request = Request.Builder()
            .url("$DISCORD_API_BASE/channels/$channelId/typing")
            .header("Authorization", "Bot $token")
            .post("{}".toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()
        restClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                Log.w(TAG, "Discord typing failed HTTP ${response.code}")
            }
        }
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
        private const val TAG = "DiscordAdapter"
        private const val DISCORD_API_BASE = "https://discord.com/api/v10"
        private const val DEFAULT_GATEWAY_URL = "wss://gateway.discord.gg/?v=10&encoding=json"
        private const val DEFAULT_GROUP_POLICY = "mention"
        // GUILDS(1) + GUILD_MESSAGES(512) + DIRECT_MESSAGES(4096) + MESSAGE_CONTENT(32768)
        // Do not request GUILD_MEMBERS by default (can cause 4014 if not enabled).
        private const val DEFAULT_INTENTS = 37377
        private const val DEFAULT_HEARTBEAT_INTERVAL_MS = 45_000L

        private const val TYPING_INTERVAL_MS = 8_000L
        private const val MAX_TYPING_DURATION_MS = 120_000L
        private const val MAX_MESSAGE_CHARS = 1800
        private const val MAX_SEND_ATTEMPTS = 3

        private const val OP_DISPATCH = 0
        private const val OP_HEARTBEAT = 1
        private const val OP_IDENTIFY = 2
        private const val OP_RESUME = 6
        private const val OP_RECONNECT = 7
        private const val OP_INVALID_SESSION = 9
        private const val OP_HELLO = 10
        private const val OP_HEARTBEAT_ACK = 11
    }

    private fun normalizeResponseMode(raw: String): String {
        return when (raw.trim().lowercase(Locale.US)) {
            "open" -> "open"
            else -> "mention"
        }
    }
}
