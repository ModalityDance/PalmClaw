package com.palmclaw.tools

import com.palmclaw.bus.MessageAttachment
import com.palmclaw.bus.OutboundMessage
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class MessageTool(
    private var sendCallback: (suspend (OutboundMessage) -> Unit)? = null
) : Tool {
    private val json = Json { ignoreUnknownKeys = true }
    private val contextMutex = Mutex()
    private val turnStates = mutableMapOf<Job, TurnState>()

    @Volatile
    private var defaultChannel: String = ""

    @Volatile
    private var defaultChatId: String = ""

    @Volatile
    private var defaultMessageId: String? = null

    @Volatile
    private var defaultAdapterKey: String? = null

    override val name: String = "message"

    override val description: String =
        "Send a message or attachments to the current target chat or local session. " +
            "Use this to deliver files directly to the user when the result should appear in the conversation."

    override val jsonSchema: JsonObject = buildJsonObject {
        put("type", "object")
        put("additionalProperties", false)
        put(
            "properties",
            Json.parseToJsonElement(
                """
                {
                  "content": {"type":"string","description":"Message content to send"},
                  "channel": {"type":"string","description":"Optional target channel"},
                  "chat_id": {"type":"string","description":"Optional target chat id"},
                  "message_id": {"type":"string","description":"Optional reply/message reference"},
                  "attachments": {
                    "type":"array",
                    "items": {
                      "type":"object",
                      "additionalProperties": false,
                      "required": ["reference"],
                      "properties": {
                        "kind": {"type":"string","enum":["image","video","audio","file"]},
                        "reference": {"type":"string"},
                        "label": {"type":"string"},
                        "mimeType": {"type":"string"},
                        "sizeBytes": {"type":"integer"},
                        "source": {"type":"string","enum":["local","remote","unknown"]},
                        "metadata": {
                          "type":"object",
                          "additionalProperties":{"type":"string"}
                        }
                      }
                    },
                    "description":"Optional structured attachments"
                  },
                  "media": {
                    "type":"array",
                    "items":{"type":"string"},
                    "description":"Legacy attachment paths/urls"
                  }
                }
                """.trimIndent()
            )
        )
    }

    fun setContext(channel: String, chatId: String, messageId: String? = null, adapterKey: String? = null) {
        defaultChannel = channel
        defaultChatId = chatId
        defaultMessageId = messageId
        defaultAdapterKey = adapterKey?.trim()?.ifBlank { null }
    }

    fun clearContext() {
        defaultChannel = ""
        defaultChatId = ""
        defaultMessageId = null
        defaultAdapterKey = null
    }

    fun setSendCallback(callback: suspend (OutboundMessage) -> Unit) {
        sendCallback = callback
    }

    fun clearSendCallback() {
        sendCallback = null
    }

    suspend fun startTurn() {
        val job = requireCurrentJob()
        contextMutex.withLock {
            turnStates[job] = TurnState(
                channel = defaultChannel,
                chatId = defaultChatId,
                messageId = defaultMessageId,
                adapterKey = defaultAdapterKey,
                sentInTurn = false
            )
        }
    }

    suspend fun finishTurn() {
        val job = requireCurrentJob()
        contextMutex.withLock {
            turnStates.remove(job)
        }
    }

    suspend fun wasSentInCurrentTurn(): Boolean {
        val job = requireCurrentJob()
        return contextMutex.withLock {
            turnStates[job]?.sentInTurn ?: false
        }
    }

    override suspend fun run(argumentsJson: String): ToolResult {
        val args = runCatching { json.decodeFromString<Args>(argumentsJson) }
            .getOrElse {
                return ToolResult(
                    toolCallId = "",
                    content = "Error: invalid arguments JSON for message tool: ${it.message}",
                    isError = true
                )
            }
        val content = args.content.trim()
        val normalizedAttachments = args.attachments.orEmpty()
        val normalizedMedia = args.media.orEmpty()
        if (content.isBlank() && normalizedAttachments.isEmpty() && normalizedMedia.isEmpty()) {
            return ToolResult(
                toolCallId = "",
                content = "Error: content or attachments are required",
                isError = true
            )
        }

        val currentJob = currentCoroutineContext()[Job]
        val turnState = contextMutex.withLock {
            if (currentJob != null) turnStates[currentJob] else null
        }
        val fallbackChannel = turnState?.channel ?: defaultChannel
        val fallbackChatId = turnState?.chatId ?: defaultChatId
        val fallbackMessageId = turnState?.messageId
        val fallbackAdapterKey = turnState?.adapterKey ?: defaultAdapterKey
        val channel = args.channel?.trim().orEmpty().ifBlank { fallbackChannel }
        val chatId = args.chat_id?.trim().orEmpty().ifBlank { fallbackChatId }
        val messageId = args.message_id?.trim().orEmpty().ifBlank { fallbackMessageId.orEmpty() }
            .ifBlank { null }
        val adapterKey = fallbackAdapterKey
            ?.takeIf { channel == fallbackChannel && chatId == fallbackChatId }
        if (channel.isBlank() || chatId.isBlank()) {
            return ToolResult(
                toolCallId = "",
                content = "Error: no target channel/chat specified",
                isError = true
            )
        }

        val callback = sendCallback
            ?: return ToolResult(
                toolCallId = "",
                content = "Error: message sending is not configured",
                isError = true
            )

        return runCatching {
            callback(
                OutboundMessage(
                    channel = channel,
                    chatId = chatId,
                    content = content,
                    attachments = normalizedAttachments,
                    media = normalizedMedia,
                    metadata = buildMap {
                        if (messageId != null) put("message_id", messageId)
                        if (!adapterKey.isNullOrBlank()) put("adapter_key", adapterKey)
                    }
                )
            )
            contextMutex.withLock {
                if (currentJob != null) {
                    val state = turnStates[currentJob]
                    if (state != null && channel == state.channel && chatId == state.chatId) {
                        turnStates[currentJob] = state.copy(sentInTurn = true)
                    }
                }
            }
            ToolResult(
                toolCallId = "",
                content = if (args.attachments.isNullOrEmpty() && args.media.isNullOrEmpty()) {
                    "Message sent to $channel:$chatId"
                } else {
                    val count = normalizedAttachments.size.takeIf { it > 0 } ?: normalizedMedia.size
                    "Message sent to $channel:$chatId with $count attachments"
                },
                isError = false,
                metadata = buildJsonObject {
                    put(ToolResultMetadataKeys.DELIVERY_TOOL, true)
                    put(ToolResultMetadataKeys.HIDE_UI_RESULT, true)
                    put(ToolResultMetadataKeys.STOP_AGENT_LOOP, true)
                }
            )
        }.getOrElse { t ->
            ToolResult(
                toolCallId = "",
                content = "Error sending message: ${t.message ?: t.javaClass.simpleName}",
                isError = true
            )
        }
    }

    @Serializable
    private data class Args(
        val content: String = "",
        val channel: String? = null,
        val chat_id: String? = null,
        val message_id: String? = null,
        val attachments: List<MessageAttachment>? = null,
        val media: List<String>? = null
    )

    private suspend fun requireCurrentJob(): Job {
        return currentCoroutineContext()[Job]
            ?: throw IllegalStateException("message tool requires an active coroutine job")
    }

    private data class TurnState(
        val channel: String,
        val chatId: String,
        val messageId: String?,
        val adapterKey: String?,
        val sentInTurn: Boolean
    )
}


