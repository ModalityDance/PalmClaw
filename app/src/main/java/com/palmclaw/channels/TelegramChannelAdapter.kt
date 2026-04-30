package com.palmclaw.channels

import android.util.Log
import com.palmclaw.bus.MessageAttachment
import com.palmclaw.bus.MessageAttachmentKind
import com.palmclaw.bus.MessageAttachmentSource
import com.palmclaw.bus.InboundMessage
import com.palmclaw.bus.OutboundMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.MultipartBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

class TelegramChannelAdapter(
    override val adapterKey: String,
    private val botToken: String,
    private val allowedChatIds: Set<String> = emptySet()
) : ChannelAdapter {
    override val channelName: String = "telegram"
    override val attachmentCapability: ChannelAttachmentCapability = ChannelAttachmentCapability(
        supportsInboundFiles = true,
        supportsOutboundFiles = true,
        requiresAuthenticatedDownload = true
    )
    private val allowedChats = allowedChatIds
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .toSet()

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(35, TimeUnit.SECONDS)
        .callTimeout(40, TimeUnit.SECONDS)
        .build()

    private var pollingJob: Job? = null
    private var updateOffset: Long = 0L
    @Volatile
    private var runtimeScope: CoroutineScope? = null
    private val typingTasks = mutableMapOf<String, Job>()
    private val typingLock = Any()

    override fun start(
        scope: CoroutineScope,
        publishInbound: suspend (InboundMessage) -> Unit
    ) {
        if (botToken.isBlank() || pollingJob != null) return
        ChannelRuntimeDiagnostics.reset(channelName, adapterKey)
        ChannelRuntimeDiagnostics.markRunning(channelName, adapterKey, true)
        runtimeScope = scope
        pollingJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                try {
                    pollUpdates(publishInbound)
                } catch (t: Throwable) {
                    Log.e(TAG, "Telegram polling failed", t)
                    ChannelRuntimeDiagnostics.markConnected(channelName, adapterKey, false)
                    ChannelRuntimeDiagnostics.markError(
                        channelName,
                        adapterKey,
                        t.message ?: t.javaClass.simpleName
                    )
                    delay(2_000)
                }
            }
        }
    }

    override suspend fun send(message: OutboundMessage) {
        if (botToken.isBlank()) return
        val isProgress = message.metadata["_progress"]?.equals("true", ignoreCase = true) == true
        if (isProgress) return
        stopTyping(message.chatId)
        val text = message.content.trim()
        val attachments = message.normalizedAttachments
        val bodyText = if (text.length > MAX_MESSAGE_CHARS) {
            text.take(MAX_MESSAGE_CHARS) + "\n...[truncated]"
        } else {
            text
        }
        if (bodyText.isBlank() && attachments.isEmpty()) return
        if (attachments.isNotEmpty()) {
            attachments.forEachIndexed { index, attachment ->
                sendAttachmentMessage(
                    chatId = message.chatId,
                    attachment = attachment,
                    caption = if (index == 0) bodyText else "",
                    replyTo = message.replyTo ?: message.metadata["reply_to"]
                )
            }
            return
        }
        val payload = JSONObject()
            .put("chat_id", message.chatId)
            .put("text", bodyText)
            .put("disable_web_page_preview", true)

        val request = Request.Builder()
            .url("$BASE_URL/bot$botToken/sendMessage")
            .header("Content-Type", "application/json")
            .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                val body = response.body?.string().orEmpty()
                throw IllegalStateException("Telegram sendMessage HTTP ${response.code}: ${body.take(300)}")
            }
            Log.d(TAG, "Telegram outbound sent to chatId=${message.chatId}")
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
        return chatId.isNotBlank() && (allowedChats.isEmpty() || chatId in allowedChats)
    }

    override fun stop() {
        pollingJob?.cancel()
        pollingJob = null
        runtimeScope = null
        stopAllTyping()
        ChannelRuntimeDiagnostics.markRunning(channelName, adapterKey, false)
        ChannelRuntimeDiagnostics.markConnected(channelName, adapterKey, false)
    }

    private suspend fun pollUpdates(publishInbound: suspend (InboundMessage) -> Unit) {
        val url = "$BASE_URL/bot$botToken/getUpdates?timeout=25&offset=$updateOffset"
        val request = Request.Builder().url(url).get().build()
        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IllegalStateException("Telegram getUpdates HTTP ${response.code}: ${body.take(300)}")
            }
            val json = JSONObject(body)
            if (!json.optBoolean("ok")) return
            ChannelRuntimeDiagnostics.markConnected(channelName, adapterKey, true)
            ChannelRuntimeDiagnostics.markReady(channelName, adapterKey)
            val result = json.optJSONArray("result") ?: return
            for (i in 0 until result.length()) {
                val update = result.optJSONObject(i) ?: continue
                val updateId = update.optLong("update_id")
                updateOffset = maxOf(updateOffset, updateId + 1)
                val message = update.optJSONObject("message") ?: continue
                val chat = message.optJSONObject("chat") ?: continue
                val from = message.optJSONObject("from")
                val chatId = chat.optLong("id").toString()
                if (allowedChats.isNotEmpty() && chatId !in allowedChats) continue
                val text = message.optString("text")
                val caption = message.optString("caption")
                val attachments = buildInboundAttachments(message)
                if (text.isBlank() && caption.isBlank() && attachments.isEmpty()) continue
                val senderId = from?.optLong("id")?.toString().orEmpty()
                startTyping(chatId)
                publishInbound(
                    InboundMessage(
                        channel = channelName,
                        senderId = senderId,
                        chatId = chatId,
                        content = text.ifBlank { caption },
                        attachments = attachments,
                        metadata = mapOf(
                            "update_id" to updateId.toString(),
                            "message_id" to message.optLong("message_id").toString(),
                            GatewayOrchestrator.KEY_ADAPTER_KEY to adapterKey
                        )
                    )
                )
                Log.d(TAG, "Telegram inbound received chatId=$chatId, updateId=$updateId")
            }
        }
    }

    private fun startTyping(chatId: String) {
        val scope = runtimeScope ?: return
        stopTyping(chatId)
        val job = scope.launch(Dispatchers.IO) {
            var elapsed = 0L
            while (isActive && elapsed < MAX_TYPING_DURATION_MS) {
                runCatching { sendTypingAction(chatId) }
                delay(TYPING_INTERVAL_MS)
                elapsed += TYPING_INTERVAL_MS
            }
        }
        synchronized(typingLock) {
            typingTasks[chatId] = job
        }
    }

    private fun stopTyping(chatId: String) {
        val task = synchronized(typingLock) { typingTasks.remove(chatId) }
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

    private fun sendTypingAction(chatId: String) {
        val payload = JSONObject()
            .put("chat_id", chatId)
            .put("action", "typing")
        val request = Request.Builder()
            .url("$BASE_URL/bot$botToken/sendChatAction")
            .header("Content-Type", "application/json")
            .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                val body = response.body?.string().orEmpty()
                Log.w(TAG, "sendChatAction failed HTTP ${response.code}: ${body.take(200)}")
            }
        }
    }

    private fun buildInboundAttachments(message: JSONObject): List<MessageAttachment> {
        val attachments = mutableListOf<MessageAttachment>()
        message.optJSONObject("document")?.let { document ->
            val fileId = document.optString("file_id").orEmpty()
            val fileName = document.optString("file_name").orEmpty().ifBlank { "document" }
            resolveTelegramFileUrl(fileId)?.let { url ->
                attachments += MessageAttachment(
                    kind = MessageAttachmentKind.File,
                    reference = url,
                    label = fileName,
                    mimeType = document.optString("mime_type").takeIf { it.isNotBlank() },
                    sizeBytes = document.optLong("file_size").takeIf { it > 0 },
                    source = MessageAttachmentSource.Remote,
                    isRemoteBacked = true,
                    metadata = mapOf("source_channel" to channelName)
                )
            }
        }
        message.optJSONObject("video")?.let { video ->
            val fileId = video.optString("file_id").orEmpty()
            resolveTelegramFileUrl(fileId)?.let { url ->
                attachments += MessageAttachment(
                    kind = MessageAttachmentKind.Video,
                    reference = url,
                    label = video.optString("file_name").ifBlank { "video.mp4" },
                    mimeType = video.optString("mime_type").takeIf { it.isNotBlank() },
                    sizeBytes = video.optLong("file_size").takeIf { it > 0 },
                    source = MessageAttachmentSource.Remote,
                    isRemoteBacked = true,
                    metadata = mapOf("source_channel" to channelName)
                )
            }
        }
        message.optJSONObject("audio")?.let { audio ->
            val fileId = audio.optString("file_id").orEmpty()
            resolveTelegramFileUrl(fileId)?.let { url ->
                attachments += MessageAttachment(
                    kind = MessageAttachmentKind.Audio,
                    reference = url,
                    label = audio.optString("file_name").ifBlank { "audio.mp3" },
                    mimeType = audio.optString("mime_type").takeIf { it.isNotBlank() },
                    sizeBytes = audio.optLong("file_size").takeIf { it > 0 },
                    source = MessageAttachmentSource.Remote,
                    isRemoteBacked = true,
                    metadata = mapOf("source_channel" to channelName)
                )
            }
        }
        message.optJSONObject("voice")?.let { voice ->
            val fileId = voice.optString("file_id").orEmpty()
            resolveTelegramFileUrl(fileId)?.let { url ->
                attachments += MessageAttachment(
                    kind = MessageAttachmentKind.Audio,
                    reference = url,
                    label = "voice.ogg",
                    mimeType = voice.optString("mime_type").takeIf { it.isNotBlank() },
                    sizeBytes = voice.optLong("file_size").takeIf { it > 0 },
                    source = MessageAttachmentSource.Remote,
                    isRemoteBacked = true,
                    metadata = mapOf("source_channel" to channelName)
                )
            }
        }
        val photos = message.optJSONArray("photo")
        if (photos != null && photos.length() > 0) {
            val photo = photos.optJSONObject(photos.length() - 1)
            val fileId = photo?.optString("file_id").orEmpty()
            resolveTelegramFileUrl(fileId)?.let { url ->
                attachments += MessageAttachment(
                    kind = MessageAttachmentKind.Image,
                    reference = url,
                    label = "photo.jpg",
                    mimeType = "image/jpeg",
                    sizeBytes = photo?.optLong("file_size")?.takeIf { it > 0 },
                    source = MessageAttachmentSource.Remote,
                    isRemoteBacked = true,
                    metadata = mapOf("source_channel" to channelName)
                )
            }
        }
        return attachments
    }

    private fun resolveTelegramFileUrl(fileId: String): String? {
        if (fileId.isBlank()) return null
        val request = Request.Builder()
            .url("$BASE_URL/bot$botToken/getFile?file_id=$fileId")
            .get()
            .build()
        return runCatching {
            client.newCall(request).execute().use { response ->
                require(response.isSuccessful) { "HTTP ${response.code}" }
                val body = response.body?.string().orEmpty()
                val json = JSONObject(body)
                if (!json.optBoolean("ok")) return@use null
                val filePath = json.optJSONObject("result")?.optString("file_path").orEmpty()
                filePath.takeIf { it.isNotBlank() }?.let { "$BASE_URL/file/bot$botToken/$it" }
            }
        }.getOrNull()
    }

    private fun sendAttachmentMessage(
        chatId: String,
        attachment: MessageAttachment,
        caption: String,
        replyTo: String?
    ) {
        val reference = attachment.localWorkspacePath?.takeIf { it.isNotBlank() } ?: attachment.reference
        val file = File(reference)
        if (!file.exists()) {
            return
        }
        val endpoint = when (attachment.kind) {
            MessageAttachmentKind.Image -> "sendPhoto"
            MessageAttachmentKind.Video -> "sendVideo"
            MessageAttachmentKind.Audio -> "sendAudio"
            MessageAttachmentKind.File -> "sendDocument"
        }
        val fieldName = when (attachment.kind) {
            MessageAttachmentKind.Image -> "photo"
            MessageAttachmentKind.Video -> "video"
            MessageAttachmentKind.Audio -> "audio"
            MessageAttachmentKind.File -> "document"
        }
        val multipart = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("chat_id", chatId)
            .apply {
                if (caption.isNotBlank()) addFormDataPart("caption", caption)
                replyTo?.takeIf { it.isNotBlank() }?.let { addFormDataPart("reply_to_message_id", it) }
                addFormDataPart(
                    fieldName,
                    attachment.label.ifBlank { file.name },
                    file.asRequestBody((attachment.mimeType ?: "application/octet-stream").toMediaType())
                )
            }
            .build()
        val request = Request.Builder()
            .url("$BASE_URL/bot$botToken/$endpoint")
            .post(multipart)
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                val body = response.body?.string().orEmpty()
                throw IllegalStateException("Telegram $endpoint HTTP ${response.code}: ${body.take(300)}")
            }
        }
    }

    companion object {
        private const val TAG = "TelegramAdapter"
        private const val BASE_URL = "https://api.telegram.org"
        private const val MAX_MESSAGE_CHARS = 3500
        private const val TYPING_INTERVAL_MS = 4000L
        private const val MAX_TYPING_DURATION_MS = 120_000L
    }
}

