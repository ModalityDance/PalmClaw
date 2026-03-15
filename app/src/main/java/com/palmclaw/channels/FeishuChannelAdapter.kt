package com.palmclaw.channels

import android.util.Log
import com.lark.oapi.core.utils.Jsons
import com.lark.oapi.event.EventDispatcher
import com.lark.oapi.service.im.ImService
import com.lark.oapi.service.im.v1.model.P2MessageReceiveV1
import com.lark.oapi.ws.Client
import com.palmclaw.bus.InboundMessage
import com.palmclaw.bus.OutboundMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit

data class FeishuRouteRule(
    val allowedOpenIds: Set<String> = emptySet()
)

class FeishuChannelAdapter(
    override val adapterKey: String,
    appId: String,
    appSecret: String,
    encryptKey: String = "",
    verificationToken: String = "",
    allowedChatTargets: Set<String> = emptySet(),
    routeRules: Map<String, FeishuRouteRule> = emptyMap()
) : ChannelAdapter {
    override val channelName: String = "feishu"

    private val appId = appId.trim()
    private val appSecret = appSecret.trim()
    private val encryptKey = encryptKey.trim()
    private val verificationToken = verificationToken.trim()
    private val routeRulesByTarget: Map<String, FeishuRouteRule> = routeRules
        .mapNotNull { (rawTarget, rawRule) ->
            val target = normalizeTargetId(rawTarget)
            if (target.isBlank()) return@mapNotNull null
            target to FeishuRouteRule(
                allowedOpenIds = rawRule.allowedOpenIds
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

    @Volatile
    private var runtimeScope: CoroutineScope? = null
    private var workerJob: Job? = null
    @Volatile
    private var sdkClient: Client? = null
    private val accessTokenLock = Mutex()
    @Volatile
    private var accessToken: String? = null
    @Volatile
    private var accessTokenExpiryMs: Long = 0L
    private val processedMessageIdsLock = Any()
    private val processedMessageIds = linkedMapOf<String, Long>()

    override fun start(scope: CoroutineScope, publishInbound: suspend (InboundMessage) -> Unit) {
        if (appId.isBlank() || appSecret.isBlank() || workerJob != null) return
        ChannelRuntimeDiagnostics.reset(channelName, adapterKey)
        ChannelRuntimeDiagnostics.markRunning(channelName, adapterKey, true)
        FeishuGatewayDiagnostics.reset()
        FeishuGatewayDiagnostics.markRunning(true)
        synchronized(processedMessageIdsLock) { processedMessageIds.clear() }
        runtimeScope = scope
        workerJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                runCatching {
                    val eventHandler = EventDispatcher.newBuilder(encryptKey, verificationToken)
                        .onP2MessageReceiveV1(object : ImService.P2MessageReceiveV1Handler() {
                            override fun handle(event: P2MessageReceiveV1) {
                                val currentScope = runtimeScope ?: return
                                FeishuGatewayDiagnostics.markEventType("im.message.receive_v1")
                                currentScope.launch(Dispatchers.IO) {
                                    handleIncomingEvent(event, publishInbound)
                                }
                            }
                        })
                        .build()
                    val client = Client.Builder(appId, appSecret)
                        .eventHandler(eventHandler)
                        .build()
                    sdkClient = client
                    client.start()
                    ChannelRuntimeDiagnostics.markConnected(channelName, adapterKey, true)
                    ChannelRuntimeDiagnostics.markReady(channelName, adapterKey)
                    FeishuGatewayDiagnostics.markConnected(true)
                    FeishuGatewayDiagnostics.markReady()
                    Log.d(TAG, "Feishu long connection started")
                    while (isActive && sdkClient === client) {
                        delay(1_000L)
                    }
                }.onFailure { t ->
                    Log.e(TAG, "Feishu long connection failed", t)
                    stopSdkClient()
                    ChannelRuntimeDiagnostics.markConnected(channelName, adapterKey, false)
                    ChannelRuntimeDiagnostics.markError(channelName, adapterKey, t.message ?: t.javaClass.simpleName)
                    FeishuGatewayDiagnostics.markConnected(false)
                    FeishuGatewayDiagnostics.markError(t.message ?: t.javaClass.simpleName)
                }
                if (isActive) {
                    delay(RECONNECT_DELAY_MS)
                }
            }
        }
    }

    override suspend fun send(message: OutboundMessage) {
        if (appId.isBlank() || appSecret.isBlank()) return
        withContext(Dispatchers.IO) {
            val isProgress = message.metadata["_progress"]?.equals("true", ignoreCase = true) == true
            if (isProgress) return@withContext
            val baseText = message.content.trim()
            val mediaNote = if (message.media.isNotEmpty()) {
                "\n" + message.media.joinToString("\n") { ref -> "[attachment: $ref]" }
            } else {
                ""
            }
            val text = (baseText + mediaNote).trim()
            if (text.isBlank()) return@withContext
            val receiveId = normalizeTargetId(message.chatId)
            if (receiveId.isBlank()) return@withContext
            val receiveIdType = if (receiveId.startsWith("oc_")) "chat_id" else "open_id"
            splitMessage(text, MAX_TEXT_CHARS).forEach { chunk ->
                sendTextMessage(receiveIdType = receiveIdType, receiveId = receiveId, text = chunk)
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
        workerJob?.cancel()
        workerJob = null
        runtimeScope = null
        stopSdkClient()
        ChannelRuntimeDiagnostics.markRunning(channelName, adapterKey, false)
        ChannelRuntimeDiagnostics.markConnected(channelName, adapterKey, false)
        FeishuGatewayDiagnostics.markRunning(false)
        FeishuGatewayDiagnostics.markConnected(false)
    }

    private suspend fun handleIncomingEvent(
        event: P2MessageReceiveV1,
        publishInbound: suspend (InboundMessage) -> Unit
    ) {
        val raw = runCatching { Jsons.DEFAULT.toJson(event.event) }
            .getOrElse {
                ChannelRuntimeDiagnostics.markError(
                    channelName,
                    adapterKey,
                    "Event parse failed: ${it.message ?: it.javaClass.simpleName}"
                )
                FeishuGatewayDiagnostics.markError("Event parse failed: ${it.message ?: it.javaClass.simpleName}")
                return
            }
        val root = runCatching { JSONObject(raw) }.getOrElse {
            ChannelRuntimeDiagnostics.markError(channelName, adapterKey, "Event json invalid")
            FeishuGatewayDiagnostics.markError("Event json invalid")
            return
        }
        val message = root.optJSONObject("message") ?: return
        val sender = root.optJSONObject("sender") ?: return
        val senderId = sender.optJSONObject("sender_id")
        val senderOpenId = optString(senderId, "open_id", "openId")
        if (senderOpenId.isBlank()) return
        val senderType = optString(sender, "sender_type", "senderType")
        if (senderType.equals("bot", ignoreCase = true)) return
        val messageId = optString(message, "message_id", "messageId")
        if (messageId.isBlank() || isDuplicateMessage(messageId)) return
        val chatId = optString(message, "chat_id", "chatId")
        val chatType = optString(message, "chat_type", "chatType").ifBlank { "p2p" }
        val messageType = optString(message, "message_type", "messageType").ifBlank { "unknown" }
        val routeTargetId = if (chatType.equals("p2p", ignoreCase = true)) senderOpenId else chatId
        if (routeTargetId.isBlank()) return

        FeishuGatewayDiagnostics.markInboundSeen(routeTargetId, senderOpenId)

        val senderName = buildSenderDisplayName(sender)
        FeishuGatewayDiagnostics.recordCandidate(
            FeishuChatCandidate(
                chatId = routeTargetId,
                title = buildCandidateTitle(chatType, senderName, message),
                kind = if (chatType.equals("p2p", ignoreCase = true)) "p2p" else "group",
                note = if (chatType.equals("p2p", ignoreCase = true)) {
                    "open_id: $senderOpenId"
                } else {
                    "chat_id: ${chatId.ifBlank { routeTargetId }}"
                }
            )
        )

        val routeRule = routeRulesByTarget[routeTargetId] ?: FeishuRouteRule()
        val allowAll = "*" in routeRule.allowedOpenIds
        if (routeRule.allowedOpenIds.isNotEmpty() && !allowAll && senderOpenId !in routeRule.allowedOpenIds) {
            return
        }
        if (allowedTargets.isEmpty() || routeTargetId !in allowedTargets) {
            return
        }

        val contentText = extractContentText(messageType, message)
            .ifBlank { defaultContentForType(messageType) }
        publishInbound(
            InboundMessage(
                channel = channelName,
                senderId = senderOpenId,
                chatId = routeTargetId,
                content = contentText,
                metadata = buildMap {
                    put(GatewayOrchestrator.KEY_ADAPTER_KEY, adapterKey)
                    put("message_id", messageId)
                    put("chat_type", chatType)
                    put("msg_type", messageType)
                    put("sender_open_id", senderOpenId)
                    if (chatId.isNotBlank()) put("source_chat_id", chatId)
                }
            )
        )
        FeishuGatewayDiagnostics.markInboundForwarded(routeTargetId)
    }

    private suspend fun sendTextMessage(receiveIdType: String, receiveId: String, text: String) {
        val tenantToken = getTenantAccessToken()
        val payload = JSONObject()
            .put("receive_id", receiveId)
            .put("msg_type", "text")
            .put("content", JSONObject().put("text", text).toString())
        val request = Request.Builder()
            .url("$FEISHU_API_BASE/open-apis/im/v1/messages?receive_id_type=$receiveIdType")
            .header("Authorization", "Bearer $tenantToken")
            .header("Content-Type", "application/json; charset=utf-8")
            .post(payload.toString().toRequestBody(JSON_MEDIA))
            .build()
        httpClient.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IllegalStateException("Feishu send HTTP ${response.code}: ${body.take(300)}")
            }
            val root = runCatching { JSONObject(body) }.getOrNull()
            val code = root?.optInt("code", -1) ?: -1
            if (code != 0) {
                val msg = root?.optString("msg").orEmpty().ifBlank { "Feishu API error" }
                throw IllegalStateException("Feishu send failed: $msg")
            }
            FeishuGatewayDiagnostics.markOutboundSent()
        }
    }

    private suspend fun getTenantAccessToken(): String {
        val now = System.currentTimeMillis()
        val cached = accessToken
        if (!cached.isNullOrBlank() && now < accessTokenExpiryMs) {
            return cached
        }
        return accessTokenLock.withLock {
            val lockedNow = System.currentTimeMillis()
            val lockedCached = accessToken
            if (!lockedCached.isNullOrBlank() && lockedNow < accessTokenExpiryMs) {
                return@withLock lockedCached
            }
            val payload = JSONObject()
                .put("app_id", appId)
                .put("app_secret", appSecret)
            val request = Request.Builder()
                .url("$FEISHU_API_BASE/open-apis/auth/v3/tenant_access_token/internal")
                .header("Content-Type", "application/json; charset=utf-8")
                .post(payload.toString().toRequestBody(JSON_MEDIA))
                .build()
            httpClient.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    throw IllegalStateException("Feishu auth HTTP ${response.code}: ${body.take(300)}")
                }
                val root = JSONObject(body)
                val code = root.optInt("code", -1)
                if (code != 0) {
                    val msg = root.optString("msg").ifBlank { "Feishu auth failed" }
                    throw IllegalStateException(msg)
                }
                val token = root.optString("tenant_access_token").trim()
                val expireSeconds = root.optLong("expire", 7_200L)
                require(token.isNotBlank()) { "Feishu tenant access token is empty" }
                accessToken = token
                accessTokenExpiryMs = System.currentTimeMillis() +
                    (expireSeconds.coerceAtLeast(300L) - 60L) * 1_000L
                return@withLock token
            }
        }
    }

    private fun extractContentText(messageType: String, message: JSONObject): String {
        val rawContent = optString(message, "content")
        val contentJson = runCatching { JSONObject(rawContent) }.getOrNull()
        return when (messageType.lowercase()) {
            "text" -> contentJson?.optString("text").orEmpty()
            "post", "interactive", "share_chat", "share_user", "merge_forward", "system" -> {
                collectText(contentJson ?: rawContent)
            }
            "image" -> "[image]"
            "audio" -> "[audio]"
            "media", "file" -> "[file]"
            else -> collectText(contentJson ?: rawContent)
        }.trim()
    }

    private fun collectText(value: Any?, depth: Int = 0): String {
        if (value == null || depth > 8) return ""
        return when (value) {
            is String -> value
            is JSONObject -> {
                val pieces = mutableListOf<String>()
                listOf("text", "title", "content", "user_name", "name").forEach { key ->
                    val item = value.opt(key)
                    if (item != null && item !is JSONObject && item !is JSONArray) {
                        val text = item.toString().trim()
                        if (text.isNotBlank()) {
                            pieces += text
                        }
                    }
                }
                val keys = value.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val nested = value.opt(key)
                    if (nested is JSONObject || nested is JSONArray) {
                        val text = collectText(nested, depth + 1)
                        if (text.isNotBlank()) {
                            pieces += text
                        }
                    }
                }
                pieces.joinToString("\n")
            }
            is JSONArray -> {
                val pieces = mutableListOf<String>()
                for (i in 0 until value.length()) {
                    val text = collectText(value.opt(i), depth + 1)
                    if (text.isNotBlank()) {
                        pieces += text
                    }
                }
                pieces.joinToString("\n")
            }
            else -> value.toString()
        }
    }

    private fun defaultContentForType(messageType: String): String {
        return when (messageType.lowercase()) {
            "image" -> "[image]"
            "audio" -> "[audio]"
            "media", "file" -> "[file]"
            else -> "[${messageType.ifBlank { "message" }}]"
        }
    }

    private fun buildSenderDisplayName(sender: JSONObject): String {
        val senderId = sender.optJSONObject("sender_id")
        val openId = optString(senderId, "open_id", "openId")
        val tenantKey = optString(senderId, "tenant_key", "tenantKey")
        return optString(sender, "name")
            .ifBlank { openId }
            .ifBlank { tenantKey }
            .ifBlank { "Feishu user" }
    }

    private fun buildCandidateTitle(chatType: String, senderName: String, message: JSONObject): String {
        if (chatType.equals("p2p", ignoreCase = true)) {
            return senderName
        }
        return optString(message, "chat_name", "chatName")
            .ifBlank { "Feishu group chat" }
    }

    private fun isDuplicateMessage(messageId: String): Boolean {
        val now = System.currentTimeMillis()
        synchronized(processedMessageIdsLock) {
            val cutoff = now - DEDUP_TTL_MS
            if (processedMessageIds.isNotEmpty()) {
                val iter = processedMessageIds.entries.iterator()
                while (iter.hasNext()) {
                    if (iter.next().value < cutoff) {
                        iter.remove()
                    }
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

    private fun stopSdkClient() {
        val client = sdkClient ?: return
        sdkClient = null
        runCatching {
            val autoReconnectField = client.javaClass.getDeclaredField("autoReconnect")
            autoReconnectField.isAccessible = true
            autoReconnectField.setBoolean(client, false)
        }
        runCatching {
            val disconnectMethod = client.javaClass.getDeclaredMethod("disconnect")
            disconnectMethod.isAccessible = true
            disconnectMethod.invoke(client)
        }
        runCatching {
            val executorField = client.javaClass.getDeclaredField("executor")
            executorField.isAccessible = true
            (executorField.get(client) as? ExecutorService)?.shutdownNow()
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

    private fun optString(obj: JSONObject?, vararg keys: String): String {
        if (obj == null) return ""
        keys.forEach { key ->
            val value = obj.optString(key).trim()
            if (value.isNotBlank()) {
                return value
            }
        }
        return ""
    }

    private fun normalizeTargetId(raw: String): String = raw.trim()

    companion object {
        private const val TAG = "FeishuAdapter"
        private const val FEISHU_API_BASE = "https://open.feishu.cn"
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
        private const val MAX_TEXT_CHARS = 3000
        private const val DEDUP_TTL_MS = 10 * 60 * 1000L
        private const val MAX_DEDUP_IDS = 2_000
        private const val RECONNECT_DELAY_MS = 5_000L
    }
}
