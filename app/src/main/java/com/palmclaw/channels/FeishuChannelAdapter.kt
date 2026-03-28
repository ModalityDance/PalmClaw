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
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit

data class FeishuRouteRule(
    val responseMode: String = "mention",
    val allowedOpenIds: Set<String> = emptySet()
)

private data class FeishuSenderIdentity(
    val senderType: String,
    val openId: String,
    val tenantKey: String,
    val displayName: String
)

private data class FeishuInboundContext(
    val messageId: String,
    val senderId: String,
    val senderOpenId: String,
    val sourceChatId: String,
    val bindTargetId: String,
    val routeTargetId: String,
    val targetAliases: List<String>,
    val chatType: String,
    val messageType: String,
    val hasBotMention: Boolean,
    val contentText: String,
    val candidate: FeishuChatCandidate
)

private data class FeishuTextMention(
    val key: String,
    val label: String
)

private data class FeishuContentExtraction(
    val text: String,
    val hasBotMention: Boolean
)

private data class FeishuRoutingState(
    val routeRulesByTarget: Map<String, FeishuRouteRule>,
    val allowedTargets: Set<String>
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
    @Volatile
    private var routingState = buildRoutingState(
        allowedChatTargets = allowedChatTargets,
        routeRules = routeRules
    )
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
    @Volatile
    private var stopRequested = false

    override fun start(scope: CoroutineScope, publishInbound: suspend (InboundMessage) -> Unit) {
        if (appId.isBlank() || appSecret.isBlank() || workerJob != null) return
        stopRequested = false
        ChannelRuntimeDiagnostics.reset(channelName, adapterKey)
        ChannelRuntimeDiagnostics.markRunning(channelName, adapterKey, true)
        FeishuGatewayDiagnostics.prepareForStart(adapterKey)
        FeishuGatewayDiagnostics.markRunning(adapterKey, true)
        synchronized(processedMessageIdsLock) { processedMessageIds.clear() }
        runtimeScope = scope
        workerJob = scope.launch(Dispatchers.IO) {
            while (isActive && !stopRequested) {
                runCatching {
                    val eventHandler = EventDispatcher.newBuilder(encryptKey, verificationToken)
                        .onP2MessageReceiveV1(object : ImService.P2MessageReceiveV1Handler() {
                            override fun handle(event: P2MessageReceiveV1) {
                                val currentScope = runtimeScope ?: return
                                FeishuGatewayDiagnostics.markEventType(adapterKey, "im.message.receive_v1")
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
                    FeishuGatewayDiagnostics.markConnected(adapterKey, true)
                    FeishuGatewayDiagnostics.markReady(adapterKey)
                    Log.d(TAG, "Feishu long connection started")
                    while (isActive && !stopRequested && sdkClient === client) {
                        delay(1_000L)
                    }
                }.onFailure { t ->
                    if (stopRequested) {
                        return@onFailure
                    }
                    Log.e(TAG, "Feishu long connection failed", t)
                    stopSdkClient()
                    ChannelRuntimeDiagnostics.markConnected(channelName, adapterKey, false)
                    ChannelRuntimeDiagnostics.markError(channelName, adapterKey, t.message ?: t.javaClass.simpleName)
                    FeishuGatewayDiagnostics.markConnected(adapterKey, false)
                    FeishuGatewayDiagnostics.markError(adapterKey, t.message ?: t.javaClass.simpleName)
                }
                if (isActive && !stopRequested) {
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
            val receiveId = normalizeFeishuTargetId(message.chatId)
            if (receiveId.isBlank()) return@withContext
            val receiveIdType = if (receiveId.startsWith("oc_")) "chat_id" else "open_id"
            splitMessage(text, MAX_TEXT_CHARS).forEach { chunk ->
                sendTextMessage(receiveIdType = receiveIdType, receiveId = receiveId, text = chunk)
            }
        }
    }

    override suspend fun beginInboundProcessing(message: InboundMessage): String? {
        if (!message.channel.equals(channelName, ignoreCase = true)) return null
        val messageId = message.metadata["message_id"]?.trim().orEmpty()
        if (messageId.isBlank()) return null
        return runCatching { addMessageReaction(messageId, FEEDBACK_EMOJI_TYPING) }
            .onFailure { t ->
                Log.w(TAG, "Failed to add Feishu processing feedback for message=$messageId", t)
            }
            .getOrNull()
    }

    override suspend fun endInboundProcessing(message: InboundMessage, handle: String?) {
        val messageId = message.metadata["message_id"]?.trim().orEmpty()
        val reactionId = handle?.trim().orEmpty()
        if (messageId.isBlank() || reactionId.isBlank()) return
        runCatching { deleteMessageReaction(messageId, reactionId) }
            .onFailure { t ->
                Log.w(TAG, "Failed to clear Feishu processing feedback for message=$messageId", t)
            }
    }

    override fun canHandleOutbound(message: OutboundMessage): Boolean {
        val requestedKey = message.metadata[GatewayOrchestrator.KEY_ADAPTER_KEY]
            ?.trim()
            ?.ifBlank { null }
        if (requestedKey != null) {
            return requestedKey == adapterKey
        }
        val currentRouting = routingState
        val target = normalizeFeishuTargetId(message.chatId)
        return target.isNotBlank() &&
            (currentRouting.allowedTargets.isEmpty() || target in currentRouting.allowedTargets)
    }

    override fun reconfigureFrom(next: ChannelAdapter): Boolean {
        val updated = next as? FeishuChannelAdapter ?: return false
        if (
            updated.adapterKey != adapterKey ||
            updated.appId != appId ||
            updated.appSecret != appSecret ||
            updated.encryptKey != encryptKey ||
            updated.verificationToken != verificationToken
        ) {
            return false
        }
        routingState = updated.routingState
        return true
    }

    override fun stop() {
        stopRequested = true
        workerJob?.cancel()
        workerJob = null
        runtimeScope = null
        stopSdkClient()
        ChannelRuntimeDiagnostics.markRunning(channelName, adapterKey, false)
        ChannelRuntimeDiagnostics.markConnected(channelName, adapterKey, false)
        FeishuGatewayDiagnostics.markRunning(adapterKey, false)
        FeishuGatewayDiagnostics.markConnected(adapterKey, false)
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
                FeishuGatewayDiagnostics.markError(adapterKey, "Event parse failed: ${it.message ?: it.javaClass.simpleName}")
                return
            }
        val root = runCatching { JSONObject(raw) }.getOrElse {
            ChannelRuntimeDiagnostics.markError(channelName, adapterKey, "Event json invalid")
            FeishuGatewayDiagnostics.markError(adapterKey, "Event json invalid")
            return
        }
        val context = parseInboundContext(root) ?: return

        FeishuGatewayDiagnostics.markInboundSeen(
            adapterKey = adapterKey,
            chatId = context.bindTargetId,
            senderOpenId = context.senderOpenId.ifBlank { context.senderId }
        )
        FeishuGatewayDiagnostics.recordCandidate(adapterKey, context.candidate)

        val currentRouting = routingState
        val matchedTargetId = resolveConfiguredTargetId(context, currentRouting)
        val routeRule = matchedTargetId
            ?.let { currentRouting.routeRulesByTarget[it] }
            ?: defaultRouteRuleForChat(context.chatType)
        val allowAll = "*" in routeRule.allowedOpenIds
        if (
            routeRule.allowedOpenIds.isNotEmpty() &&
            !allowAll &&
            (
                context.senderOpenId.isBlank() ||
                    context.senderOpenId !in routeRule.allowedOpenIds
                )
        ) {
            return
        }
        if (currentRouting.allowedTargets.isEmpty()) {
            return
        }
        if (matchedTargetId == null) {
            return
        }
        if (shouldRequireBotMention(context.chatType, routeRule) && !context.hasBotMention) {
            return
        }

        val inboundContent = context.contentText.ifBlank {
            defaultContentForType(context.messageType)
                .takeUnless { context.messageType.equals("text", ignoreCase = true) }
                .orEmpty()
        }
        if (inboundContent.isBlank()) {
            return
        }

        publishInbound(
            InboundMessage(
                channel = channelName,
                senderId = context.senderId,
                chatId = matchedTargetId,
                content = inboundContent,
                metadata = buildMap {
                    put(GatewayOrchestrator.KEY_ADAPTER_KEY, adapterKey)
                    put("message_id", context.messageId)
                    put("chat_type", context.chatType)
                    put("msg_type", context.messageType)
                    if (context.senderOpenId.isNotBlank()) put("sender_open_id", context.senderOpenId)
                    if (context.sourceChatId.isNotBlank()) put("source_chat_id", context.sourceChatId)
                }
            )
        )
        FeishuGatewayDiagnostics.markInboundForwarded(adapterKey, matchedTargetId)
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
            FeishuGatewayDiagnostics.markOutboundSent(adapterKey)
        }
    }

    private suspend fun addMessageReaction(messageId: String, emojiType: String): String? {
        val tenantToken = getTenantAccessToken()
        val payload = JSONObject()
            .put("reaction_type", JSONObject().put("emoji_type", emojiType))
        val request = Request.Builder()
            .url(
                FEISHU_API_BASE
                    .toHttpUrl()
                    .newBuilder()
                    .addPathSegments("open-apis/im/v1/messages")
                    .addPathSegment(messageId)
                    .addPathSegment("reactions")
                    .build()
            )
            .header("Authorization", "Bearer $tenantToken")
            .header("Content-Type", "application/json; charset=utf-8")
            .post(payload.toString().toRequestBody(JSON_MEDIA))
            .build()
        httpClient.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IllegalStateException("Feishu reaction HTTP ${response.code}: ${body.take(300)}")
            }
            val root = JSONObject(body)
            val code = root.optInt("code", -1)
            if (code != 0) {
                val msg = root.optString("msg").ifBlank { "Feishu reaction failed" }
                throw IllegalStateException(msg)
            }
            return root.optJSONObject("data")
                ?.optString("reaction_id")
                ?.trim()
                ?.ifBlank { null }
        }
    }

    private suspend fun deleteMessageReaction(messageId: String, reactionId: String) {
        val tenantToken = getTenantAccessToken()
        val request = Request.Builder()
            .url(
                FEISHU_API_BASE
                    .toHttpUrl()
                    .newBuilder()
                    .addPathSegments("open-apis/im/v1/messages")
                    .addPathSegment(messageId)
                    .addPathSegment("reactions")
                    .addPathSegment(reactionId)
                    .build()
            )
            .header("Authorization", "Bearer $tenantToken")
            .delete()
            .build()
        httpClient.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IllegalStateException("Feishu reaction delete HTTP ${response.code}: ${body.take(300)}")
            }
            val root = JSONObject(body)
            val code = root.optInt("code", -1)
            if (code != 0) {
                val msg = root.optString("msg").ifBlank { "Feishu reaction delete failed" }
                throw IllegalStateException(msg)
            }
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

    private fun parseInboundContext(root: JSONObject): FeishuInboundContext? {
        val message = root.optJSONObject("message") ?: return null
        val sender = root.optJSONObject("sender") ?: return null
        val senderIdentity = parseSenderIdentity(sender)
        if (senderIdentity.senderType.equals("bot", ignoreCase = true)) return null

        val messageId = optString(message, "message_id", "messageId")
        if (messageId.isBlank() || isDuplicateMessage(messageId)) return null

        val sourceChatId = normalizeFeishuTargetId(optString(message, "chat_id", "chatId"))
        val chatType = optString(message, "chat_type", "chatType").ifBlank { "p2p" }
        val messageType = optString(message, "message_type", "messageType").ifBlank { "unknown" }
        val bindTargetId = resolveFeishuBindingTarget(
            chatType = chatType,
            sourceChatId = sourceChatId,
            senderOpenId = senderIdentity.openId
        )
        val targetAliases = buildFeishuTargetAliases(
            primaryTargetId = bindTargetId,
            sourceChatId = sourceChatId,
            senderOpenId = senderIdentity.openId
        )
        val routeTargetId = targetAliases.firstOrNull().orEmpty()
        if (routeTargetId.isBlank()) return null

        val senderId = senderIdentity.openId
            .ifBlank { senderIdentity.tenantKey }
            .ifBlank { routeTargetId }

        val content = extractContent(
            messageType = messageType,
            chatType = chatType,
            message = message
        )

        return FeishuInboundContext(
            messageId = messageId,
            senderId = senderId,
            senderOpenId = senderIdentity.openId,
            sourceChatId = sourceChatId,
            bindTargetId = bindTargetId,
            routeTargetId = routeTargetId,
            targetAliases = targetAliases,
            chatType = chatType,
            messageType = messageType,
            hasBotMention = content.hasBotMention,
            contentText = content.text,
            candidate = FeishuChatCandidate(
                chatId = bindTargetId,
                title = buildCandidateTitle(chatType, senderIdentity.displayName, message),
                kind = if (chatType.equals("p2p", ignoreCase = true)) "p2p" else "group",
                note = buildCandidateNote(
                    chatType = chatType,
                    senderOpenId = senderIdentity.openId,
                    sourceChatId = sourceChatId,
                    bindTargetId = bindTargetId
                )
            )
        )
    }

    private fun extractContent(
        messageType: String,
        chatType: String,
        message: JSONObject
    ): FeishuContentExtraction {
        val rawContent = optString(message, "content")
        val contentJson = runCatching { JSONObject(rawContent) }.getOrNull()
        return when (messageType.lowercase(Locale.US)) {
            "text" -> extractTextContent(
                chatType = chatType,
                rawText = contentJson?.optString("text").orEmpty(),
                contentJson = contentJson,
                message = message
            )
            "post", "interactive", "share_chat", "share_user", "merge_forward", "system" -> {
                FeishuContentExtraction(
                    text = collectText(contentJson ?: rawContent).trim(),
                    hasBotMention = hasBotMention(
                        chatType = chatType,
                        contentJson = contentJson,
                        mentions = extractMentions(contentJson, message),
                        rawText = rawContent
                    )
                )
            }
            "image" -> FeishuContentExtraction(text = "[image]", hasBotMention = false)
            "audio" -> FeishuContentExtraction(text = "[audio]", hasBotMention = false)
            "media", "file" -> FeishuContentExtraction(text = "[file]", hasBotMention = false)
            else -> FeishuContentExtraction(
                text = collectText(contentJson ?: rawContent).trim(),
                hasBotMention = hasBotMention(
                    chatType = chatType,
                    contentJson = contentJson,
                    mentions = extractMentions(contentJson, message),
                    rawText = rawContent
                )
            )
        }
    }

    private fun extractTextContent(
        chatType: String,
        rawText: String,
        contentJson: JSONObject?,
        message: JSONObject
    ): FeishuContentExtraction {
        val mentions = extractMentions(contentJson, message)
        val hasBotMention = hasBotMention(
            chatType = chatType,
            contentJson = contentJson,
            mentions = mentions,
            rawText = rawText
        )
        val preferredText = optString(contentJson, "text_without_at_bot", "textWithoutAtBot")
        val text = normalizeTextContent(
            rawText = rawText,
            preferredText = preferredText,
            mentions = mentions,
            removeMentions = hasBotMention
        )
        return FeishuContentExtraction(
            text = text,
            hasBotMention = hasBotMention
        )
    }

    private fun collectText(value: Any?, depth: Int = 0): String {
        if (value == null || depth > 8) return ""
        return when (value) {
            is String -> value
            is JSONObject -> {
                val tag = optString(value, "tag").lowercase()
                if (tag == "at") {
                    val label = optString(
                        value,
                        "text",
                        "user_name",
                        "userName",
                        "name",
                        "display_name",
                        "displayName"
                    ).removePrefix("@").trim()
                    return if (label.isBlank()) "" else "@$label"
                }
                val pieces = mutableListOf<String>()
                listOf("text", "title", "content").forEach { key ->
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

    private fun extractMentions(contentJson: JSONObject?, message: JSONObject): List<FeishuTextMention> {
        val mentions = mutableListOf<FeishuTextMention>()
        sequenceOf(contentJson?.optJSONArray("mentions"), message.optJSONArray("mentions")).forEach { array ->
            if (array == null) return@forEach
            for (index in 0 until array.length()) {
                val mention = array.optJSONObject(index) ?: continue
                val label = optString(
                    mention,
                    "name",
                    "display_name",
                    "displayName",
                    "user_name",
                    "userName",
                    "text"
                ).removePrefix("@").trim()
                val key = optString(mention, "key", "token", "placeholder")
                if (label.isBlank() && key.isBlank()) continue
                mentions += FeishuTextMention(key = key, label = label)
            }
        }
        return mentions
            .distinctBy { "${it.key}|${it.label}" }
    }

    private fun normalizeTextContent(
        rawText: String,
        preferredText: String,
        mentions: List<FeishuTextMention>,
        removeMentions: Boolean = false
    ): String {
        if (preferredText.isNotBlank()) {
            return cleanupText(preferredText)
        }
        var normalized = rawText.trim()
        mentions.forEach { mention ->
            val replacement = if (removeMentions) {
                ""
            } else {
                mention.label.takeIf { it.isNotBlank() }?.let { "@$it" }.orEmpty()
            }
            if (mention.key.isNotBlank()) {
                normalized = normalized.replace(mention.key, replacement)
                if (!mention.key.startsWith("@")) {
                    normalized = normalized.replace("@${mention.key}", replacement)
                }
            }
        }

        normalized = INLINE_AT_REGEX.replace(normalized) { match ->
            val inner = match.groupValues.getOrNull(1).orEmpty().removePrefix("@").trim()
            if (removeMentions || inner.isBlank()) "" else "@$inner"
        }
        normalized = UNRESOLVED_MENTION_REGEX.replace(normalized) { match ->
            match.groupValues.getOrNull(1).orEmpty()
        }
        if (removeMentions) {
            normalized = GENERIC_LEADING_AT_MENTION_REGEX.replace(normalized, "")
        }
        return cleanupText(normalized)
    }

    private fun cleanupText(value: String): String {
        return value
            .trim()
            .replace(Regex("[ \\t]{2,}"), " ")
            .replace(Regex("\\n{3,}"), "\n\n")
            .lines()
            .joinToString("\n") { it.trimEnd() }
            .trim()
    }

    private fun hasBotMention(
        chatType: String,
        contentJson: JSONObject?,
        mentions: List<FeishuTextMention>,
        rawText: String
    ): Boolean {
        if (contentJson?.has("text_without_at_bot") == true || contentJson?.has("textWithoutAtBot") == true) {
            return true
        }
        if (!chatType.equals("p2p", ignoreCase = true)) return false
        if (mentions.isNotEmpty()) return true
        val normalized = rawText.trim()
        if (normalized.isBlank()) return false
        if (INLINE_AT_REGEX.containsMatchIn(normalized)) return true
        if (UNRESOLVED_MENTION_REGEX.containsMatchIn(" $normalized")) return true
        return GENERIC_LEADING_AT_MENTION_REGEX.containsMatchIn(normalized)
    }

    private fun normalizeResponseMode(raw: String): String {
        return if (raw.trim().lowercase(Locale.US) == "open") "open" else "mention"
    }

    private fun defaultRouteRuleForChat(@Suppress("UNUSED_PARAMETER") chatType: String): FeishuRouteRule {
        return FeishuRouteRule(responseMode = DEFAULT_RESPONSE_MODE)
    }

    private fun shouldRequireBotMention(
        @Suppress("UNUSED_PARAMETER") chatType: String,
        @Suppress("UNUSED_PARAMETER") routeRule: FeishuRouteRule
    ): Boolean {
        return true
    }

    private fun defaultContentForType(messageType: String): String {
        return when (messageType.lowercase(Locale.US)) {
            "image" -> "[image]"
            "audio" -> "[audio]"
            "media", "file" -> "[file]"
            else -> "[${messageType.ifBlank { "message" }}]"
        }
    }

    private fun parseSenderIdentity(sender: JSONObject): FeishuSenderIdentity {
        val senderId = sender.optJSONObject("sender_id")
        val openId = normalizeFeishuTargetId(optString(senderId, "open_id", "openId"))
        val tenantKey = optString(senderId, "tenant_key", "tenantKey")
        return FeishuSenderIdentity(
            senderType = optString(sender, "sender_type", "senderType"),
            openId = openId,
            tenantKey = tenantKey,
            displayName = buildSenderDisplayName(sender)
        )
    }

    private fun buildCandidateNote(
        chatType: String,
        senderOpenId: String,
        sourceChatId: String,
        bindTargetId: String
    ): String {
        return if (chatType.equals("p2p", ignoreCase = true)) {
            buildString {
                append("chat_id: ${sourceChatId.ifBlank { bindTargetId }}")
                if (senderOpenId.isNotBlank()) {
                    append(" / open_id: $senderOpenId")
                }
            }
        } else {
            "chat_id: ${sourceChatId.ifBlank { bindTargetId }}"
        }
    }

    private fun resolveConfiguredTargetId(
        context: FeishuInboundContext,
        routing: FeishuRoutingState
    ): String? {
        return context.targetAliases.firstOrNull { alias ->
            alias in routing.routeRulesByTarget || alias in routing.allowedTargets
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
        listOf("stop", "close", "disconnect", "shutdown").forEach { methodName ->
            invokeNoArgMethodIfPresent(client, methodName)
        }
        runCatching {
            val executorField = client.javaClass.getDeclaredField("executor")
            executorField.isAccessible = true
            (executorField.get(client) as? ExecutorService)?.shutdownNow()
        }
    }

    private fun buildRoutingState(
        allowedChatTargets: Set<String>,
        routeRules: Map<String, FeishuRouteRule>
    ): FeishuRoutingState {
        val normalizedRouteRules = routeRules
            .mapNotNull { (rawTarget, rawRule) ->
                val target = normalizeFeishuTargetId(rawTarget)
                if (target.isBlank()) return@mapNotNull null
                target to FeishuRouteRule(
                    responseMode = normalizeResponseMode(rawRule.responseMode),
                    allowedOpenIds = rawRule.allowedOpenIds
                        .asSequence()
                        .map { it.trim() }
                        .filter { it.isNotBlank() }
                        .toSet()
                )
            }
            .toMap()
        val normalizedAllowedTargets = (if (normalizedRouteRules.isNotEmpty()) {
            normalizedRouteRules.keys
        } else {
            allowedChatTargets
        })
            .map { normalizeFeishuTargetId(it) }
            .filter { it.isNotBlank() }
            .toSet()
        return FeishuRoutingState(
            routeRulesByTarget = normalizedRouteRules,
            allowedTargets = normalizedAllowedTargets
        )
    }

    private fun invokeNoArgMethodIfPresent(target: Any, methodName: String) {
        runCatching {
            val method = target.javaClass.methods.firstOrNull {
                it.name == methodName && it.parameterCount == 0
            } ?: return@runCatching
            method.isAccessible = true
            method.invoke(target)
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

    companion object {
        private const val TAG = "FeishuAdapter"
        private const val FEISHU_API_BASE = "https://open.feishu.cn"
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
        private val INLINE_AT_REGEX = Regex("<at\\b[^>]*>(.*?)</at>", RegexOption.IGNORE_CASE)
        private val UNRESOLVED_MENTION_REGEX = Regex("(^|\\s)@?_?user_\\d+(?=\\s|$)")
        private val GENERIC_LEADING_AT_MENTION_REGEX = Regex("^(?:@[^\\s]+\\s+)+")
        private const val MAX_TEXT_CHARS = 3000
        private const val DEDUP_TTL_MS = 10 * 60 * 1000L
        private const val MAX_DEDUP_IDS = 2_000
        private const val RECONNECT_DELAY_MS = 5_000L
        private const val DEFAULT_RESPONSE_MODE = "mention"
        private const val FEEDBACK_EMOJI_TYPING = "Typing"
    }
}
