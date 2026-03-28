package com.palmclaw.channels

import android.content.Context
import android.util.Base64
import android.util.Log
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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
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

class WeComChannelAdapter(
    context: Context,
    override val adapterKey: String,
    botId: String,
    secret: String,
    allowedChatTargets: Set<String> = emptySet(),
    routeRules: Map<String, WeComRouteRule> = emptyMap()
) : ChannelAdapter {
    override val channelName: String = "wecom"

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
    private var expectedSocketClose: Boolean = false
    @Volatile
    private var authenticated: Boolean = false
    @Volatile
    private var pendingAcks: Int = 0

    private val processedMessageIdsLock = Any()
    private val processedMessageIds = linkedMapOf<String, Long>()
    private val replyContextsLock = Any()
    private val replyContexts = linkedMapOf<String, WeComReplyContext>()

    override fun start(scope: CoroutineScope, publishInbound: suspend (InboundMessage) -> Unit) {
        if (botId.isBlank() || secret.isBlank() || workerJob != null) return
        ChannelRuntimeDiagnostics.reset(channelName, adapterKey)
        ChannelRuntimeDiagnostics.markRunning(channelName, adapterKey, true)
        WeComGatewayDiagnostics.prepareForStart(adapterKey)
        WeComGatewayDiagnostics.markRunning(adapterKey, true)
        synchronized(processedMessageIdsLock) { processedMessageIds.clear() }
        synchronized(replyContextsLock) { replyContexts.clear() }
        pendingAcks = 0
        authenticated = false
        runtimeScope = scope
        workerJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                try {
                    runSocketSession(publishInbound)
                } catch (t: Throwable) {
                    Log.e(TAG, "WeCom websocket loop failed", t)
                    ChannelRuntimeDiagnostics.markError(channelName, adapterKey, t.message ?: t.javaClass.simpleName)
                    WeComGatewayDiagnostics.markError(adapterKey, t.message ?: t.javaClass.simpleName)
                }
                if (isActive) {
                    delay(RECONNECT_DELAY_MS)
                }
            }
        }
    }

    override suspend fun send(message: OutboundMessage) {
        if (botId.isBlank() || secret.isBlank()) return
        withContext(Dispatchers.IO) {
            val isProgress = message.metadata["_progress"]?.equals("true", ignoreCase = true) == true
            if (isProgress) return@withContext
            val targetId = normalizeTargetId(message.chatId)
            if (targetId.isBlank()) return@withContext
            val baseText = message.content.trim()
            val mediaNote = if (message.media.isNotEmpty()) {
                "\n" + message.media.joinToString("\n") { ref -> "[attachment: $ref]" }
            } else {
                ""
            }
            val text = (baseText + mediaNote).trim()
            if (text.isBlank()) return@withContext
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
        expectedSocketClose = true
        heartbeatJob?.cancel()
        heartbeatJob = null
        workerJob?.cancel()
        workerJob = null
        webSocket?.cancel()
        webSocket = null
        runtimeScope = null
        authenticated = false
        pendingAcks = 0
        ChannelRuntimeDiagnostics.markRunning(channelName, adapterKey, false)
        ChannelRuntimeDiagnostics.markConnected(channelName, adapterKey, false)
        WeComGatewayDiagnostics.markRunning(adapterKey, false)
        WeComGatewayDiagnostics.markConnected(adapterKey, false)
    }

    private suspend fun runSocketSession(
        publishInbound: suspend (InboundMessage) -> Unit
    ) {
        expectedSocketClose = false
        authenticated = false
        pendingAcks = 0
        val endSignal = CompletableDeferred<Unit>()
        val request = Request.Builder()
            .url(DEFAULT_WS_URL)
            .get()
            .build()
        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d(TAG, "WeCom websocket connected")
                ChannelRuntimeDiagnostics.markConnected(channelName, adapterKey, true)
                WeComGatewayDiagnostics.markConnected(adapterKey, true)
                val scope = runtimeScope ?: return
                scope.launch(Dispatchers.IO) {
                    runCatching { sendAuthFrame() }
                        .onFailure {
                            ChannelRuntimeDiagnostics.markError(
                                channelName,
                                adapterKey,
                                "Auth send failed: ${it.message ?: it.javaClass.simpleName}"
                            )
                            ChannelRuntimeDiagnostics.markConnected(channelName, adapterKey, false)
                            WeComGatewayDiagnostics.markError(adapterKey, "Auth send failed: ${it.message ?: it.javaClass.simpleName}")
                            endSignal.complete(Unit)
                        }
                }
                startHeartbeatLoop(webSocket, endSignal)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val scope = runtimeScope ?: return
                scope.launch(Dispatchers.IO) {
                    handleIncomingFrame(text, publishInbound, endSignal)
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                ChannelRuntimeDiagnostics.markConnected(channelName, adapterKey, false)
                WeComGatewayDiagnostics.markConnected(adapterKey, false)
                if (!expectedSocketClose) {
                    ChannelRuntimeDiagnostics.markError(channelName, adapterKey, "Socket closed: code=$code reason=${reason.ifBlank { "n/a" }}")
                    WeComGatewayDiagnostics.markError(adapterKey, "Socket closed: code=$code reason=${reason.ifBlank { "n/a" }}")
                }
                if (!endSignal.isCompleted) {
                    endSignal.complete(Unit)
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                ChannelRuntimeDiagnostics.markConnected(channelName, adapterKey, false)
                WeComGatewayDiagnostics.markConnected(adapterKey, false)
                webSocket.close(code, reason)
                if (!expectedSocketClose) {
                    ChannelRuntimeDiagnostics.markError(channelName, adapterKey, "Socket closing: code=$code reason=${reason.ifBlank { "n/a" }}")
                    WeComGatewayDiagnostics.markError(adapterKey, "Socket closing: code=$code reason=${reason.ifBlank { "n/a" }}")
                }
                if (!endSignal.isCompleted) {
                    endSignal.complete(Unit)
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                ChannelRuntimeDiagnostics.markConnected(channelName, adapterKey, false)
                WeComGatewayDiagnostics.markConnected(adapterKey, false)
                val msg = t.message ?: t.javaClass.simpleName
                if (!(expectedSocketClose && msg.equals("Socket closed", ignoreCase = true))) {
                    ChannelRuntimeDiagnostics.markError(channelName, adapterKey, "Socket failure: $msg")
                    WeComGatewayDiagnostics.markError(adapterKey, "Socket failure: $msg")
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
            heartbeatJob?.cancel()
            heartbeatJob = null
            expectedSocketClose = true
            runCatching { socket.close(1000, "session_end") }
            if (webSocket === socket) {
                webSocket = null
            }
        }
    }

    private fun startHeartbeatLoop(socket: WebSocket, endSignal: CompletableDeferred<Unit>) {
        heartbeatJob?.cancel()
        val scope = runtimeScope ?: return
        heartbeatJob = scope.launch(Dispatchers.IO) {
            while (isActive && webSocket === socket) {
                delay(HEARTBEAT_INTERVAL_MS)
                if (!authenticated) continue
                if (pendingAcks > MAX_PENDING_ACKS) {
                    WeComGatewayDiagnostics.markError(adapterKey, "Heartbeat timeout")
                    expectedSocketClose = true
                    runCatching { socket.close(4000, "heartbeat_timeout") }
                    if (!endSignal.isCompleted) {
                        endSignal.complete(Unit)
                    }
                    break
                }
                val payload = JSONObject()
                    .put("cmd", "ping")
                    .put("headers", JSONObject().put("req_id", "ping_${System.currentTimeMillis()}"))
                if (socket.send(payload.toString())) {
                    pendingAcks += 1
                } else {
                    WeComGatewayDiagnostics.markError(adapterKey, "Heartbeat send failed")
                }
            }
        }
    }

    private fun sendAuthFrame() {
        val socket = webSocket ?: error("WeCom websocket not connected")
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
        raw: String,
        publishInbound: suspend (InboundMessage) -> Unit,
        endSignal: CompletableDeferred<Unit>
    ) {
        val payload = runCatching { JSONObject(raw) }.getOrElse {
            WeComGatewayDiagnostics.markError(adapterKey, "Invalid JSON frame")
            return
        }
        val cmd = payload.optString("cmd").trim()
        if (cmd.equals("ack", ignoreCase = true)) {
            pendingAcks = (pendingAcks - 1).coerceAtLeast(0)
            return
        }

        val headers = payload.optJSONObject("headers")
        val reqId = headers?.optString("req_id")?.trim().orEmpty()
        val errCode = payload.optInt("errcode", 0)
        val errMsg = payload.optString("errmsg").trim()

        when {
            reqId.startsWith("aibot_subscribe") -> {
                if (errCode == 0) {
                    authenticated = true
                    ChannelRuntimeDiagnostics.markReady(channelName, adapterKey)
                    WeComGatewayDiagnostics.markReady(adapterKey)
                } else {
                    ChannelRuntimeDiagnostics.markConnected(channelName, adapterKey, false)
                    ChannelRuntimeDiagnostics.markError(
                        channelName,
                        adapterKey,
                        "Auth failed: ${errMsg.ifBlank { errCode.toString() }}"
                    )
                    WeComGatewayDiagnostics.markError(adapterKey, "Auth failed: ${errMsg.ifBlank { errCode.toString() }}")
                    expectedSocketClose = true
                    webSocket?.close(4001, "auth_failed")
                    if (!endSignal.isCompleted) {
                        endSignal.complete(Unit)
                    }
                }
                return
            }

            reqId.startsWith("ping") -> {
                pendingAcks = (pendingAcks - 1).coerceAtLeast(0)
                return
            }
        }

        if (errCode != 0) {
            val label = reqId.ifBlank { cmd.ifBlank { "frame" } }
            WeComGatewayDiagnostics.markError(
                adapterKey,
                "WeCom $label failed: ${errMsg.ifBlank { errCode.toString() }}"
            )
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

        val routeRule = routeRulesByTarget[chatId] ?: WeComRouteRule()
        val allowAll = "*" in routeRule.allowedUserIds
        if (routeRule.allowedUserIds.isNotEmpty() && !allowAll && senderUserId !in routeRule.allowedUserIds) {
            return
        }
        if (allowedTargets.isNotEmpty() && chatId !in allowedTargets) {
            return
        }

        val msgType = body.optString("msgtype").trim().lowercase()
        val content = buildInboundText(msgType, body)
        if (content.isBlank()) return

        publishInbound(
            InboundMessage(
                channel = channelName,
                senderId = senderUserId,
                chatId = chatId,
                content = content,
                metadata = buildMap {
                    put(GatewayOrchestrator.KEY_ADAPTER_KEY, adapterKey)
                    put("message_id", messageId)
                    put("msg_type", msgType)
                    put("chat_type", chatType)
                    put("sender_user_id", senderUserId)
                }
            )
        )
        WeComGatewayDiagnostics.markInboundForwarded(adapterKey, chatId)
    }

    private suspend fun buildInboundText(msgType: String, body: JSONObject): String {
        return when (msgType) {
            "text" -> body.optJSONObject("text")?.optString("content").orEmpty().trim()
            "voice" -> body.optJSONObject("voice")?.optString("content").orEmpty().trim().ifBlank { "[voice]" }
            "image" -> {
                val image = body.optJSONObject("image")
                val url = image?.optString("url").orEmpty().trim()
                val aesKey = image?.optString("aeskey").orEmpty().trim()
                val saved = downloadAndSaveMedia(url, aesKey, "image")
                if (saved != null) "[image: ${saved.name}]\n[Image: source: ${saved.absolutePath}]" else "[image]"
            }
            "file" -> {
                val file = body.optJSONObject("file")
                val url = file?.optString("url").orEmpty().trim()
                val aesKey = file?.optString("aeskey").orEmpty().trim()
                val fileName = file?.optString("name").orEmpty().trim()
                val saved = downloadAndSaveMedia(url, aesKey, "file", fileName)
                if (saved != null) "[file: ${saved.name}]\n[File: source: ${saved.absolutePath}]" else {
                    "[file: ${fileName.ifBlank { "download failed" }}]"
                }
            }
            "mixed" -> {
                val mixed = body.optJSONObject("mixed")?.optJSONArray("item")
                buildList {
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
            }
            else -> "[${msgType.ifBlank { "message" }}]"
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
        }.onFailure {
            WeComGatewayDiagnostics.markError(adapterKey, "Media download failed: ${it.message ?: it.javaClass.simpleName}")
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
        private const val RECONNECT_DELAY_MS = 5_000L
        private const val REPLY_CONTEXT_TTL_MS = 7L * 24L * 60L * 60L * 1000L
        private const val DEDUP_TTL_MS = 10 * 60 * 1000L
        private const val MAX_DEDUP_IDS = 2_000
        private const val MAX_REPLY_CONTEXTS = 100
        private const val MAX_PENDING_ACKS = 3
        private const val MAX_TEXT_CHARS = 3_000
    }
}
