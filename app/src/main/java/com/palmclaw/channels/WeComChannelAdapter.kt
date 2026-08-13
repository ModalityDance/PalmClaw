package com.palmclaw.channels

import android.content.Context
import android.util.Base64
import android.util.Log
import com.palmclaw.bus.MessageAttachment
import com.palmclaw.bus.MessageAttachmentKind
import com.palmclaw.bus.MessageAttachmentSource
import com.palmclaw.bus.InboundMessage
import com.palmclaw.bus.OutboundMessage
import com.palmclaw.config.AppStoragePaths
import java.io.File
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
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
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject

data class WeComRouteRule(
    val allowedUserIds: Set<String> = emptySet()
)

private data class WeComReplyContext(
    val reqId: String,
    val chatId: String,
    val senderUserId: String,
    val messageId: String,
    val updatedAtMs: Long
)

private data class WeComInboundContent(
    val text: String,
    val attachments: List<MessageAttachment> = emptyList()
)

class WeComChannelAdapter(
    context: Context,
    override val adapterKey: String,
    botId: String,
    secret: String,
    allowedChatTargets: Set<String> = emptySet(),
    routeRules: Map<String, WeComRouteRule> = emptyMap(),
    private val captureOnly: Boolean = false
) : ChannelAdapter {
    override val channelName: String = "wecom"
    override val attachmentCapability: ChannelAttachmentCapability = ChannelAttachmentCapability(
        supportsInboundFiles = true,
        supportsOutboundFiles = false,
        requiresAuthenticatedDownload = true
    )

    private val appContext = context.applicationContext
    private val botId = botId.trim()
    private val secret = secret.trim()
    private val routeRulesByTarget: Map<String, WeComRouteRule> = routeRules
        .mapNotNull { (rawTarget, rawRule) ->
            val target = normalizeTargetId(rawTarget)
            if (target.isBlank()) return@mapNotNull null
            target to WeComRouteRule(
                allowedUserIds = rawRule.allowedUserIds
                    .asSequence()
                    .map { it.trim() }
                    .filter { it.isNotBlank() }
                    .toSet()
            )
        }
        .toMap()
    private val allowedTargets = (if (routeRulesByTarget.isNotEmpty()) {
        routeRulesByTarget.keys
    } else {
        allowedChatTargets
    })
        .map { normalizeTargetId(it) }
        .filter { it.isNotBlank() }
        .toSet()

    private val httpClient = OkHttpClient.Builder()
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
    private var activeSessionGate: ChannelSessionTerminationGate? = null
    @Volatile
    private var authenticated: Boolean = false
    private val heartbeatTracker = WeComHeartbeatTracker()

    private val processedMessageIdsLock = Any()
    private val processedMessageIds = linkedMapOf<String, Long>()
    private val replyContextsLock = Any()
    private val replyContexts = linkedMapOf<String, WeComReplyContext>()
    private val runtimeHealth = ChannelAdapterRuntimeHealth(channelName, adapterKey)

    override fun start(scope: CoroutineScope, publishInbound: suspend (InboundMessage) -> Unit) {
        if (workerJob != null) return
        if (botId.isBlank() || secret.isBlank()) {
            runtimeHealth.starting()
            runtimeHealth.blocked(ChannelRuntimeErrorCode.CONFIGURATION_INVALID)
            return
        }
        runtimeHealth.starting()
        WeComGatewayDiagnostics.prepareForStart(adapterKey)
        WeComGatewayDiagnostics.markRunning(adapterKey, true)
        synchronized(processedMessageIdsLock) { processedMessageIds.clear() }
        synchronized(replyContextsLock) { replyContexts.clear() }
        heartbeatTracker.reset()
        authenticated = false
        runtimeScope = scope
        workerJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                try {
                    runSocketSession(publishInbound)
                } catch (t: Throwable) {
                    if (t is CancellationException) throw t
                    val safeError = safeChannelErrorSummary(t)
                    Log.w(TAG, "WeCom websocket loop failed: $safeError")
                    runtimeHealth.failure(t)
                    WeComGatewayDiagnostics.markError(adapterKey, safeError)
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
        if (botId.isBlank() || secret.isBlank()) return
        withContext(Dispatchers.IO) {
            val isProgress = message.metadata["_progress"]?.equals("true", ignoreCase = true) == true
            if (isProgress) return@withContext
            val attachments = message.normalizedAttachments
            val targetId = normalizeTargetId(message.chatId)
            if (targetId.isBlank()) return@withContext
            val text = message.content.trim()
            if (text.isBlank() && attachments.isEmpty()) return@withContext
            runtimeHealth.runOperation(ChannelOperation.OUTBOUND) {
                if (attachments.isNotEmpty()) {
                    val errorMessage =
                        "WeCom long-connection mode currently supports real inbound file download, but outbound file replies are not available in this protocol yet."
                    WeComGatewayDiagnostics.markError(adapterKey, errorMessage)
                    throw UnsupportedOperationException(errorMessage)
                }
                val chunks = splitMessage(text, MAX_TEXT_CHARS)
                val replyContext = findReplyContext(targetId)
                if (replyContext == null) {
                    WeComGatewayDiagnostics.markError(
                        adapterKey,
                        "WeCom proactive send is not supported in current mobile mode. Send a message from WeCom first, then reply while the cached context is still available."
                    )
                    error("WeCom reply context missing")
                }
                val streamId = generateReqId("stream")
                chunks.forEachIndexed { index, chunk ->
                    sendReplyStream(
                        reqId = replyContext.reqId,
                        streamId = streamId,
                        content = chunk,
                        finish = index == chunks.lastIndex
                    )
                }
                WeComGatewayDiagnostics.markOutboundSent(adapterKey)
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
        val target = normalizeTargetId(message.chatId)
        return target.isNotBlank() && (allowedTargets.isEmpty() || target in allowedTargets)
    }

    override fun stop() {
        activeSessionGate?.claim()
        activeSessionGate = null
        heartbeatJob?.cancel()
        heartbeatJob = null
        workerJob?.cancel()
        workerJob = null
        webSocket?.cancel()
        webSocket = null
        runtimeScope = null
        authenticated = false
        heartbeatTracker.reset()
        runtimeHealth.stopped()
        WeComGatewayDiagnostics.markRunning(adapterKey, false)
        WeComGatewayDiagnostics.markConnected(adapterKey, false)
    }

    private suspend fun runSocketSession(
        publishInbound: suspend (InboundMessage) -> Unit
    ) {
        authenticated = false
        heartbeatTracker.reset()
        val endSignal = CompletableDeferred<Unit>()
        currentCoroutineContext().ensureActive()
        val terminationGate = ChannelSessionTerminationGate()
        activeSessionGate = terminationGate
        val request = Request.Builder()
            .url(DEFAULT_WS_URL)
            .get()
            .build()
        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (terminationGate.isClaimed()) return
                Log.d(TAG, "WeCom websocket connected")
                runtimeHealth.connected()
                WeComGatewayDiagnostics.markConnected(adapterKey, true)
                val scope = runtimeScope ?: return
                scope.launch(Dispatchers.IO) {
                    runCatching { sendAuthFrame(webSocket) }
                        .onFailure {
                            terminateSocketSession(webSocket, endSignal, terminationGate, "Authentication frame send failed")
                        }
                }
                startHeartbeatLoop(webSocket, endSignal, terminationGate)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (terminationGate.isClaimed()) return
                val scope = runtimeScope ?: return
                scope.launch(Dispatchers.IO) {
                    handleIncomingFrame(webSocket, text, publishInbound, endSignal, terminationGate)
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (!terminationGate.claim()) return
                ChannelRuntimeDiagnostics.markConnected(channelName, adapterKey, false)
                WeComGatewayDiagnostics.markConnected(adapterKey, false)
                runtimeHealth.failure("Socket closed")
                WeComGatewayDiagnostics.markError(adapterKey, "Connection interrupted")
                endSignal.complete(Unit)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                if (terminationGate.claim()) {
                    ChannelRuntimeDiagnostics.markConnected(channelName, adapterKey, false)
                    WeComGatewayDiagnostics.markConnected(adapterKey, false)
                    runtimeHealth.failure("Socket closing")
                    WeComGatewayDiagnostics.markError(adapterKey, "Connection interrupted")
                    endSignal.complete(Unit)
                }
                webSocket.close(code, reason)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (!terminationGate.claim()) return
                val safeError = safeChannelErrorSummary(t)
                ChannelRuntimeDiagnostics.markConnected(channelName, adapterKey, false)
                WeComGatewayDiagnostics.markConnected(adapterKey, false)
                runtimeHealth.failure(t)
                WeComGatewayDiagnostics.markError(adapterKey, safeError)
                endSignal.complete(Unit)
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
                activeSessionGate = null
            }
            runCatching { socket.close(1000, "session_end") }
            if (webSocket === socket) {
                webSocket = null
            }
        }
    }

    private fun startHeartbeatLoop(
        socket: WebSocket,
        endSignal: CompletableDeferred<Unit>,
        terminationGate: ChannelSessionTerminationGate
    ) {
        heartbeatJob?.cancel()
        val scope = runtimeScope ?: return
        heartbeatJob = scope.launch(Dispatchers.IO) {
            while (isActive && webSocket === socket && !terminationGate.isClaimed()) {
                delay(HEARTBEAT_INTERVAL_MS)
                if (!authenticated) continue
                if (heartbeatTracker.hasTimedOut(MAX_PENDING_ACKS)) {
                    terminateSocketSession(socket, endSignal, terminationGate, "Heartbeat timeout")
                    break
                }
                val payload = JSONObject()
                    .put("cmd", "ping")
                    .put("headers", JSONObject().put("req_id", "ping_${System.currentTimeMillis()}"))
                if (socket.send(payload.toString())) {
                    heartbeatTracker.recordSent()
                } else {
                    runtimeHealth.warning(ChannelOperation.HEARTBEAT, "Heartbeat send failed")
                    Log.w(TAG, "WeCom heartbeat send failed")
                }
            }
        }
    }

    private fun sendAuthFrame(socket: WebSocket) {
        val payload = JSONObject()
            .put("cmd", "aibot_subscribe")
            .put("headers", JSONObject().put("req_id", "aibot_subscribe_${System.currentTimeMillis()}"))
            .put(
                "body",
                JSONObject()
                    .put("bot_id", botId)
                    .put("secret", secret)
            )
        if (!socket.send(payload.toString())) {
            error("Failed to send WeCom auth frame")
        }
    }

    private suspend fun handleIncomingFrame(
        socket: WebSocket,
        raw: String,
        publishInbound: suspend (InboundMessage) -> Unit,
        endSignal: CompletableDeferred<Unit>,
        terminationGate: ChannelSessionTerminationGate
    ) {
        if (terminationGate.isClaimed()) return
        val payload = runCatching { JSONObject(raw) }.getOrElse {
            runtimeHealth.warning(ChannelOperation.INBOUND, "Invalid JSON frame")
            Log.w(TAG, "WeCom invalid JSON frame ignored")
            return
        }
        val cmd = payload.optString("cmd").trim()
        if (cmd.equals("ack", ignoreCase = true)) {
            heartbeatTracker.acknowledge()
            runtimeHealth.succeeded(ChannelOperation.HEARTBEAT)
            return
        }

        val headers = payload.optJSONObject("headers")
        val reqId = headers?.optString("req_id")?.trim().orEmpty()
        val errCode = payload.optInt("errcode", 0)
        when {
            reqId.startsWith("aibot_subscribe") -> {
                if (errCode == 0) {
                    authenticated = true
                    runtimeHealth.succeeded(ChannelOperation.AUTHENTICATION)
                    WeComGatewayDiagnostics.markReady(adapterKey)
                } else {
                    blockSocketSession(socket, endSignal, terminationGate)
                }
                return
            }

            reqId.startsWith("ping") -> {
                heartbeatTracker.acknowledge()
                runtimeHealth.succeeded(ChannelOperation.HEARTBEAT)
                return
            }
        }

        if (errCode != 0) {
            runtimeHealth.warning(ChannelOperation.INBOUND, "Protocol error")
            Log.w(TAG, "WeCom provider returned a protocol error")
            return
        }

        val body = payload.optJSONObject("body") ?: return
        val msgType = body.optString("msgtype").trim().lowercase()
        if (msgType.isBlank()) return
        WeComGatewayDiagnostics.markEventType(adapterKey, msgType)

        if (msgType == "event") {
            handleEventFrame(headers, body)
            return
        }

        handleMessageFrame(headers, body, publishInbound)
    }

    private fun terminateSocketSession(
        socket: WebSocket,
        endSignal: CompletableDeferred<Unit>,
        terminationGate: ChannelSessionTerminationGate,
        message: String
    ) {
        if (!terminationGate.claim()) return
        ChannelRuntimeDiagnostics.markConnected(channelName, adapterKey, false)
        WeComGatewayDiagnostics.markConnected(adapterKey, false)
        runtimeHealth.failure(message)
        WeComGatewayDiagnostics.markError(adapterKey, safeChannelErrorSummary(message))
        endSignal.complete(Unit)
        socket.close(4000, "reconnect")
    }

    private fun blockSocketSession(
        socket: WebSocket,
        endSignal: CompletableDeferred<Unit>,
        terminationGate: ChannelSessionTerminationGate
    ) {
        if (!terminationGate.claim()) return
        ChannelRuntimeDiagnostics.markConnected(channelName, adapterKey, false)
        WeComGatewayDiagnostics.markConnected(adapterKey, false)
        runtimeHealth.blocked(ChannelRuntimeErrorCode.AUTHENTICATION_FAILED)
        WeComGatewayDiagnostics.markError(adapterKey, "Authentication required")
        endSignal.complete(Unit)
        socket.close(4001, "auth_failed")
    }

    private fun handleEventFrame(headers: JSONObject?, body: JSONObject) {
        val event = body.optJSONObject("event") ?: return
        val eventType = event.optString("event_type").trim()
        WeComGatewayDiagnostics.markEventType(adapterKey, "event.$eventType")
        if (!eventType.equals("enter_chat", ignoreCase = true)) return
        val senderId = body.optString("from_userid").trim()
        val chatId = normalizeTargetId(body.optString("chatid").trim().ifBlank { senderId })
        if (chatId.isBlank()) return
        rememberReplyContext(
            chatId = chatId,
            reqId = headers?.optString("req_id")?.trim().orEmpty(),
            senderUserId = senderId,
            messageId = body.optString("msgid").trim()
        )
        WeComGatewayDiagnostics.recordCandidate(
            adapterKey,
            WeComChatCandidate(
                chatId = chatId,
                title = if (body.optString("chattype").trim().equals("group", ignoreCase = true)) {
                    "WeCom group"
                } else {
                    senderId.ifBlank { "WeCom user" }
                },
                kind = body.optString("chattype").trim().ifBlank { "single" },
                note = if (senderId.isNotBlank()) "userId: $senderId" else ""
            )
        )
    }

    private suspend fun handleMessageFrame(
        headers: JSONObject?,
        body: JSONObject,
        publishInbound: suspend (InboundMessage) -> Unit
    ) {
        val messageId = body.optString("msgid").trim()
            .ifBlank { "${body.optString("chatid").trim()}_${body.optLong("create_time", 0L)}" }
        if (messageId.isBlank() || isDuplicateMessage(messageId)) return

        val from = body.optJSONObject("from")
        val senderUserId = from?.optString("userid")?.trim().orEmpty()
            .ifBlank { body.optString("from_userid").trim() }
        val chatType = body.optString("chattype").trim().ifBlank { "single" }
        val chatId = normalizeTargetId(body.optString("chatid").trim().ifBlank { senderUserId })
        if (chatId.isBlank() || senderUserId.isBlank()) return

        rememberReplyContext(
            chatId = chatId,
            reqId = headers?.optString("req_id")?.trim().orEmpty(),
            senderUserId = senderUserId,
            messageId = messageId
        )

        WeComGatewayDiagnostics.markInboundSeen(adapterKey, chatId, senderUserId)
        WeComGatewayDiagnostics.recordCandidate(
            adapterKey,
            WeComChatCandidate(
                chatId = chatId,
                title = if (chatType.equals("group", ignoreCase = true)) {
                    "WeCom group"
                } else {
                    senderUserId
                },
                kind = chatType,
                note = "userId: $senderUserId"
            )
        )
        if (captureOnly) return

        val routeRule = routeRulesByTarget[chatId] ?: WeComRouteRule()
        val allowAll = "*" in routeRule.allowedUserIds
        if (routeRule.allowedUserIds.isNotEmpty() && !allowAll && senderUserId !in routeRule.allowedUserIds) {
            return
        }
        if (allowedTargets.isNotEmpty() && chatId !in allowedTargets) {
            return
        }

        val msgType = body.optString("msgtype").trim().lowercase()
        val inboundContent = buildInboundContent(msgType, body)
        if (inboundContent.text.isBlank()) return

        publishInbound(
            InboundMessage(
                channel = channelName,
                senderId = senderUserId,
                chatId = chatId,
                content = inboundContent.text,
                attachments = inboundContent.attachments,
                metadata = buildMap {
                    put(GatewayOrchestrator.KEY_ADAPTER_KEY, adapterKey)
                    put("message_id", messageId)
                    put("msg_type", msgType)
                    put("chat_type", chatType)
                    put("sender_user_id", senderUserId)
                }
            )
        )
        runtimeHealth.succeeded(ChannelOperation.INBOUND)
        WeComGatewayDiagnostics.markInboundForwarded(adapterKey, chatId)
    }

    private suspend fun buildInboundContent(msgType: String, body: JSONObject): WeComInboundContent {
        return when (msgType) {
            "text" -> WeComInboundContent(
                text = body.optJSONObject("text")?.optString("content").orEmpty().trim()
            )
            "voice" -> WeComInboundContent(
                text = body.optJSONObject("voice")?.optString("content").orEmpty().trim().ifBlank { "[voice]" }
            )
            "image" -> {
                val image = body.optJSONObject("image")
                val url = image?.optString("url").orEmpty().trim()
                val aesKey = image?.optString("aeskey").orEmpty().trim()
                val saved = downloadAndSaveMedia(url, aesKey, "image")
                if (saved != null) {
                    WeComInboundContent(
                        text = "Sent 1 attachment.",
                        attachments = listOf(
                            MessageAttachment(
                                kind = MessageAttachmentKind.Image,
                                reference = saved.absolutePath,
                                label = saved.name,
                                source = MessageAttachmentSource.Local,
                                metadata = mapOf("source_channel" to channelName)
                            )
                        )
                    )
                } else {
                    WeComInboundContent(text = "[image]")
                }
            }
            "file" -> {
                val file = body.optJSONObject("file")
                val url = file?.optString("url").orEmpty().trim()
                val aesKey = file?.optString("aeskey").orEmpty().trim()
                val fileName = file?.optString("name").orEmpty().trim()
                val saved = downloadAndSaveMedia(url, aesKey, "file", fileName)
                if (saved != null) {
                    WeComInboundContent(
                        text = "Sent 1 attachment.",
                        attachments = listOf(
                            MessageAttachment(
                                kind = MessageAttachmentKind.File,
                                reference = saved.absolutePath,
                                label = saved.name,
                                source = MessageAttachmentSource.Local,
                                metadata = mapOf("source_channel" to channelName)
                            )
                        )
                    )
                } else {
                    WeComInboundContent(text = "[file: ${fileName.ifBlank { "download failed" }}]")
                }
            }
            "mixed" -> {
                val mixed = body.optJSONObject("mixed")?.optJSONArray("item")
                val lines = buildList {
                    if (mixed != null) {
                        for (i in 0 until mixed.length()) {
                            val item = mixed.optJSONObject(i) ?: continue
                            when (item.optString("type").trim().lowercase()) {
                                "text" -> {
                                    val text = item.optJSONObject("text")?.optString("content").orEmpty().trim()
                                    if (text.isNotBlank()) add(text)
                                }
                                "image" -> add("[image]")
                                else -> add("[${item.optString("type").trim().ifBlank { "item" }}]")
                            }
                        }
                    }
                }.joinToString("\n").trim().ifBlank { "[mixed]" }
                WeComInboundContent(text = lines)
            }
            else -> WeComInboundContent(text = "[${msgType.ifBlank { "message" }}]")
        }
    }

    private suspend fun downloadAndSaveMedia(
        url: String,
        aesKey: String,
        kind: String,
        filenameHint: String = ""
    ): File? {
        if (url.isBlank()) return null
        return runCatching {
            val request = Request.Builder().url(url).get().build()
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    error("HTTP ${response.code}")
                }
                val encrypted = response.body?.bytes() ?: error("Empty body")
                val filename = extractFilename(response.header("Content-Disposition").orEmpty())
                    .ifBlank { filenameHint.ifBlank { "${kind}_${System.currentTimeMillis()}" } }
                val bytes = if (aesKey.isBlank()) encrypted else decryptFile(encrypted, aesKey)
                val dir = File(AppStoragePaths.storageRoot(appContext), "media/wecom").apply { mkdirs() }
                val safeName = sanitizeFilename(filename)
                val out = uniqueFile(dir, safeName)
                out.writeBytes(bytes)
                out
            }
        }.onFailure { failure ->
            val safeError = safeChannelErrorSummary(failure)
            runtimeHealth.warning(ChannelOperation.INBOUND, failure)
            Log.w(TAG, "WeCom media download failed: $safeError")
        }.getOrNull()
    }

    private fun decryptFile(encryptedData: ByteArray, aesKey: String): ByteArray {
        require(encryptedData.isNotEmpty()) { "Encrypted data is empty" }
        val normalizedKey = normalizeBase64(aesKey)
        val keyBytes = Base64.decode(normalizedKey, Base64.DEFAULT)
        require(keyBytes.isNotEmpty()) { "AES key decode failed" }
        val iv = keyBytes.copyOfRange(0, 16)
        val cipher = Cipher.getInstance("AES/CBC/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(keyBytes, "AES"), IvParameterSpec(iv))
        val decrypted = cipher.doFinal(encryptedData)
        val padLen = decrypted.last().toInt() and 0xFF
        require(padLen in 1..32 && padLen <= decrypted.size) { "Invalid PKCS#7 padding" }
        for (i in decrypted.size - padLen until decrypted.size) {
            require((decrypted[i].toInt() and 0xFF) == padLen) { "Invalid PKCS#7 padding bytes" }
        }
        return decrypted.copyOf(decrypted.size - padLen)
    }

    private fun normalizeBase64(value: String): String {
        val padding = (4 - value.length % 4) % 4
        return value + "=".repeat(padding)
    }

    private fun sendReplyStream(reqId: String, streamId: String, content: String, finish: Boolean) {
        require(reqId.isNotBlank()) { "WeCom req_id missing for reply" }
        val socket = webSocket ?: error("WeCom websocket not connected")
        val payload = JSONObject()
            .put("cmd", "aibot_respond_msg")
            .put("headers", JSONObject().put("req_id", reqId))
            .put(
                "body",
                JSONObject()
                    .put("msgtype", "stream")
                    .put(
                        "stream",
                        JSONObject()
                            .put("id", streamId)
                            .put("content", content)
                            .put("finish", finish)
                    )
            )
        if (!socket.send(payload.toString())) {
            error("WeCom reply send failed")
        }
    }

    private fun rememberReplyContext(
        chatId: String,
        reqId: String,
        senderUserId: String,
        messageId: String
    ) {
        if (chatId.isBlank() || reqId.isBlank()) return
        val now = System.currentTimeMillis()
        synchronized(replyContextsLock) {
            cleanupReplyContexts(now)
            replyContexts[chatId] = WeComReplyContext(
                reqId = reqId,
                chatId = chatId,
                senderUserId = senderUserId,
                messageId = messageId,
                updatedAtMs = now
            )
            while (replyContexts.size > MAX_REPLY_CONTEXTS) {
                val firstKey = replyContexts.entries.firstOrNull()?.key ?: break
                replyContexts.remove(firstKey)
            }
        }
    }

    private fun findReplyContext(chatId: String): WeComReplyContext? {
        val normalized = normalizeTargetId(chatId)
        if (normalized.isBlank()) return null
        val now = System.currentTimeMillis()
        synchronized(replyContextsLock) {
            cleanupReplyContexts(now)
            return replyContexts[normalized]
        }
    }

    private fun cleanupReplyContexts(now: Long) {
        val iterator = replyContexts.entries.iterator()
        while (iterator.hasNext()) {
            if (now - iterator.next().value.updatedAtMs > REPLY_CONTEXT_TTL_MS) {
                iterator.remove()
            }
        }
    }

    private fun isDuplicateMessage(messageId: String): Boolean {
        val now = System.currentTimeMillis()
        synchronized(processedMessageIdsLock) {
            val cutoff = now - DEDUP_TTL_MS
            val iterator = processedMessageIds.entries.iterator()
            while (iterator.hasNext()) {
                if (iterator.next().value < cutoff) {
                    iterator.remove()
                }
            }
            if (processedMessageIds.containsKey(messageId)) {
                return true
            }
            processedMessageIds[messageId] = now
            while (processedMessageIds.size > MAX_DEDUP_IDS) {
                val firstKey = processedMessageIds.entries.firstOrNull()?.key ?: break
                processedMessageIds.remove(firstKey)
            }
            return false
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

    private fun extractFilename(contentDisposition: String): String {
        if (contentDisposition.isBlank()) return ""
        val utf8 = Regex("filename\\*=UTF-8''([^;\\s]+)", RegexOption.IGNORE_CASE)
            .find(contentDisposition)
            ?.groupValues
            ?.getOrNull(1)
        if (!utf8.isNullOrBlank()) {
            return URLDecoder.decode(utf8, StandardCharsets.UTF_8.name())
        }
        val normal = Regex("filename=\"?([^\";\\s]+)\"?", RegexOption.IGNORE_CASE)
            .find(contentDisposition)
            ?.groupValues
            ?.getOrNull(1)
        return if (normal.isNullOrBlank()) "" else URLDecoder.decode(normal, StandardCharsets.UTF_8.name())
    }

    private fun sanitizeFilename(name: String): String {
        return name.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "file.bin" }
    }

    private fun uniqueFile(dir: File, preferredName: String): File {
        var candidate = File(dir, preferredName)
        if (!candidate.exists()) return candidate
        val base = preferredName.substringBeforeLast('.', preferredName)
        val ext = preferredName.substringAfterLast('.', "")
        var index = 1
        while (candidate.exists()) {
            val nextName = if (ext.isBlank()) "${base}_$index" else "${base}_$index.$ext"
            candidate = File(dir, nextName)
            index += 1
        }
        return candidate
    }

    private fun normalizeTargetId(raw: String): String = raw.trim()

    private fun generateReqId(prefix: String): String {
        return "${prefix}_${UUID.randomUUID().toString().replace("-", "").take(16)}"
    }

    companion object {
        private const val TAG = "WeComAdapter"
        private const val DEFAULT_WS_URL = "wss://openws.work.weixin.qq.com"
        private const val HEARTBEAT_INTERVAL_MS = 30_000L
        private const val REPLY_CONTEXT_TTL_MS = 7L * 24L * 60L * 60L * 1000L
        private const val DEDUP_TTL_MS = 10 * 60 * 1000L
        private const val MAX_DEDUP_IDS = 2_000
        private const val MAX_REPLY_CONTEXTS = 100
        private const val MAX_PENDING_ACKS = 3
        private const val MAX_TEXT_CHARS = 3_000
    }
}
