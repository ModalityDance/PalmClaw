package com.palmclaw.channels

import android.util.Log
import com.palmclaw.bus.InboundMessage
import com.palmclaw.bus.OutboundMessage
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
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
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
    private var sequence: Long? = null
    @Volatile
    private var botUserId: String? = null
    private val typingTasks = mutableMapOf<String, Job>()
    private val typingLock = Any()
    private val frameLock = Mutex()
    @Volatile
    private var expectedSocketClose: Boolean = false
    @Volatile
    private var identifyUseDollarKeys: Boolean = false

    override fun start(scope: CoroutineScope, publishInbound: suspend (InboundMessage) -> Unit) {
        if (token.isBlank() || workerJob != null) return
        ChannelRuntimeDiagnostics.reset(channelName, adapterKey)
        ChannelRuntimeDiagnostics.markRunning(channelName, adapterKey, true)
        DiscordGatewayDiagnostics.reset(adapterKey)
        DiscordGatewayDiagnostics.markRunning(adapterKey, true)
        expectedSocketClose = false
        runtimeScope = scope
        workerJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                try {
                    runGatewaySession(publishInbound)
                } catch (t: Throwable) {
                    Log.e(TAG, "Discord gateway loop failed", t)
                    ChannelRuntimeDiagnostics.markError(channelName, adapterKey, t.message ?: t.javaClass.simpleName)
                    DiscordGatewayDiagnostics.markError(adapterKey, t.message ?: t.javaClass.simpleName)
                }
                if (isActive) {
                    delay(RECONNECT_DELAY_MS)
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
            val mediaNote = if (message.media.isNotEmpty()) {
                "\n" + message.media.joinToString("\n") { ref -> "[attachment: $ref]" }
            } else {
                ""
            }
            val text = (baseText + mediaNote).trim()
            if (text.isBlank()) return@withContext
            val chunks = splitMessage(text, MAX_MESSAGE_CHARS)
            val replyTo = message.replyTo ?: message.metadata["reply_to"]
            chunks.forEachIndexed { index, chunk ->
                sendTextMessage(
                    chatId = message.chatId,
                    text = chunk,
                    replyTo = if (index == 0) replyTo else null
                )
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
        expectedSocketClose = true
        workerJob?.cancel()
        workerJob = null
        heartbeatJob?.cancel()
        heartbeatJob = null
        webSocket?.cancel()
        webSocket = null
        runtimeScope = null
        stopAllTyping()
        ChannelRuntimeDiagnostics.markRunning(channelName, adapterKey, false)
        ChannelRuntimeDiagnostics.markConnected(channelName, adapterKey, false)
        DiscordGatewayDiagnostics.markRunning(adapterKey, false)
        DiscordGatewayDiagnostics.markConnected(adapterKey, false)
    }

    private suspend fun runGatewaySession(
        publishInbound: suspend (InboundMessage) -> Unit
    ) {
        expectedSocketClose = false
        sequence = null
        botUserId = null
        val endSignal = CompletableDeferred<Unit>()
        val request = Request.Builder().url(gatewayUrl).build()
        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d(TAG, "Discord gateway connected")
                ChannelRuntimeDiagnostics.markConnected(channelName, adapterKey, true)
                DiscordGatewayDiagnostics.markConnected(adapterKey, true)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val scope = runtimeScope ?: return
                scope.launch(Dispatchers.IO) {
                    frameLock.withLock {
                        handleGatewayFrame(webSocket, text, publishInbound, endSignal)
                    }
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.w(TAG, "Discord websocket closed code=$code reason=$reason")
                ChannelRuntimeDiagnostics.markConnected(channelName, adapterKey, false)
                DiscordGatewayDiagnostics.markConnected(adapterKey, false)
                maybeSwitchIdentifyMode(code, reason)
                if (!expectedSocketClose) {
                    ChannelRuntimeDiagnostics.markError(channelName, adapterKey, "Gateway closed: code=$code reason=${reason.ifBlank { "n/a" }}")
                    DiscordGatewayDiagnostics.markError(adapterKey, "Gateway closed: code=$code reason=${reason.ifBlank { "n/a" }}")
                }
                if (!endSignal.isCompleted) {
                    endSignal.complete(Unit)
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Log.w(TAG, "Discord websocket closing code=$code reason=$reason")
                ChannelRuntimeDiagnostics.markConnected(channelName, adapterKey, false)
                DiscordGatewayDiagnostics.markConnected(adapterKey, false)
                maybeSwitchIdentifyMode(code, reason)
                if (!expectedSocketClose) {
                    ChannelRuntimeDiagnostics.markError(channelName, adapterKey, "Gateway closing: code=$code reason=${reason.ifBlank { "n/a" }}")
                    DiscordGatewayDiagnostics.markError(adapterKey, "Gateway closing: code=$code reason=${reason.ifBlank { "n/a" }}")
                }
                webSocket.close(code, reason)
                if (!endSignal.isCompleted) {
                    endSignal.complete(Unit)
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.w(TAG, "Discord websocket failure: ${t.message}")
                ChannelRuntimeDiagnostics.markConnected(channelName, adapterKey, false)
                DiscordGatewayDiagnostics.markConnected(adapterKey, false)
                val msg = t.message ?: t.javaClass.simpleName
                if (!(expectedSocketClose && msg.equals("Socket closed", ignoreCase = true))) {
                    ChannelRuntimeDiagnostics.markError(channelName, adapterKey, "Gateway failure: $msg")
                    DiscordGatewayDiagnostics.markError(adapterKey, "Gateway failure: $msg")
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

    private suspend fun handleGatewayFrame(
        socket: WebSocket,
        raw: String,
        publishInbound: suspend (InboundMessage) -> Unit,
        endSignal: CompletableDeferred<Unit>
    ) {
        val payload = runCatching { JSONObject(raw) }.getOrElse {
            Log.w(TAG, "Discord gateway non-json frame ignored")
            return
        }
        if (!payload.isNull("s")) {
            sequence = payload.optLong("s")
        }
        when (payload.optInt("op", -1)) {
            OP_HELLO -> {
                val heartbeatIntervalMs = payload.optJSONObject("d")
                    ?.optLong("heartbeat_interval", DEFAULT_HEARTBEAT_INTERVAL_MS)
                    ?: DEFAULT_HEARTBEAT_INTERVAL_MS
                sendIdentify(socket)
                startHeartbeat(socket, heartbeatIntervalMs)
            }

            OP_DISPATCH -> {
                when (payload.optString("t")) {
                    "READY" -> {
                        val user = payload.optJSONObject("d")?.optJSONObject("user")
                        botUserId = user?.optString("id")?.trim().orEmpty().ifBlank { null }
                        Log.d(TAG, "Discord READY as bot=$botUserId")
                        ChannelRuntimeDiagnostics.markReady(channelName, adapterKey)
                        DiscordGatewayDiagnostics.markReady(adapterKey, botUserId)
                    }

                    "MESSAGE_CREATE" -> {
                        handleMessageCreate(payload.optJSONObject("d"), publishInbound)
                    }
                }
            }

            OP_HEARTBEAT -> {
                sendHeartbeat(socket)
            }

            OP_RECONNECT, OP_INVALID_SESSION -> {
                expectedSocketClose = true
                if (!endSignal.isCompleted) {
                    endSignal.complete(Unit)
                }
                socket.close(4000, "reconnect")
            }
        }
    }

    private fun startHeartbeat(socket: WebSocket, intervalMs: Long) {
        heartbeatJob?.cancel()
        val scope = runtimeScope ?: return
        heartbeatJob = scope.launch(Dispatchers.IO) {
            val safeInterval = intervalMs.coerceAtLeast(1_000L)
            delay(Random.nextLong(0L, safeInterval))
            while (isActive) {
                sendHeartbeat(socket)
                delay(safeInterval)
            }
        }
    }

    private fun sendHeartbeat(socket: WebSocket) {
        val data = JSONObject()
            .put("op", OP_HEARTBEAT)
            .put("d", sequence)
        sendGatewayPayload(socket, data, "heartbeat")
    }

    private fun sendIdentify(socket: WebSocket) {
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
        sendGatewayPayload(socket, identify, "identify")
    }

    private fun sendGatewayPayload(socket: WebSocket, payload: JSONObject, tag: String) {
        val raw = payload.toString()
        val safeRaw = raw.replace(Regex("\"token\"\\s*:\\s*\"[^\"]+\""), "\"token\":\"***\"")
        DiscordGatewayDiagnostics.markGatewayPayload(adapterKey, "$tag: $safeRaw")
        val ok = socket.send(raw)
        if (!ok) {
            DiscordGatewayDiagnostics.markError(adapterKey, "Gateway send failed: $tag")
        }
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
                    parts += "[attachment: $name | $url]"
                } else {
                    parts += "[attachment: $name]"
                }
            }
        }
        val normalized = parts.joinToString("\n").trim().ifBlank { "[empty message]" }
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
                val raw = response.body?.string().orEmpty()
                Log.w(TAG, "Discord typing failed HTTP ${response.code}: ${raw.take(200)}")
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

        private const val RECONNECT_DELAY_MS = 5_000L
        private const val TYPING_INTERVAL_MS = 8_000L
        private const val MAX_TYPING_DURATION_MS = 120_000L
        private const val MAX_MESSAGE_CHARS = 1800
        private const val MAX_SEND_ATTEMPTS = 3

        private const val OP_DISPATCH = 0
        private const val OP_HEARTBEAT = 1
        private const val OP_IDENTIFY = 2
        private const val OP_RECONNECT = 7
        private const val OP_INVALID_SESSION = 9
        private const val OP_HELLO = 10
    }

    private fun normalizeResponseMode(raw: String): String {
        return when (raw.trim().lowercase(Locale.US)) {
            "open" -> "open"
            else -> "mention"
        }
    }
}
