package com.palmclaw.tools

import com.palmclaw.bus.MessageAttachment
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.put

class SessionsSendTool(
    private var sendCallback: (suspend (Request) -> DeliveryResult)? = null
) : Tool {
    private val json = Json { ignoreUnknownKeys = true }

    override val name: String = "sessions_send"

    override val description: String =
        "Send an assistant-authored message into a specific local session. Prefer session_id because it is the unique identifier; " +
            "session_title should only be used when you know it uniquely identifies one session. " +
            "Use this for proactive cross-session delivery. Note: WeCom remote delivery is reply-context based " +
            "and is not a fully proactive push channel."

    override val jsonSchema: JsonObject = buildJsonObject {
        put("type", "object")
        put("additionalProperties", false)
        put(
            "properties",
            Json.parseToJsonElement(
                """
                {
                  "content": {"type":"string","description":"Message content to deliver into the target session"},
                  "session_id": {"type":"string","description":"Exact local session id; this is the canonical unique identifier"},
                  "session_title": {"type":"string","description":"Local session title; only use when it uniquely identifies one session"},
                  "deliver_remote": {"type":"boolean","description":"If the target session is bound to a remote channel, mirror the message there too"},
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
                    }
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

    fun setSendCallback(callback: suspend (Request) -> DeliveryResult) {
        sendCallback = callback
    }

    fun clearSendCallback() {
        sendCallback = null
    }

    override suspend fun run(argumentsJson: String): ToolResult {
        val args = try {
            parseArgs(argumentsJson)
        } catch (t: Throwable) {
            return ToolResult(
                toolCallId = "",
                content = "sessions_send failed: invalid arguments JSON (${t.message})",
                isError = true
            )
        }

        val content = args.content.trim()
        val attachments = args.attachments.orEmpty()
        val media = args.media.orEmpty()
        if (content.isBlank() && attachments.isEmpty() && media.isEmpty()) {
            return ToolResult(
                toolCallId = "",
                content = "sessions_send failed: content or attachments are required",
                isError = true
            )
        }

        val sessionId = args.sessionId?.trim().orEmpty()
        val sessionTitle = args.sessionTitle?.trim().orEmpty()
        if (sessionId.isBlank() && sessionTitle.isBlank()) {
            return ToolResult(
                toolCallId = "",
                content = "sessions_send failed: session_id or session_title is required",
                isError = true
            )
        }

        val callback = sendCallback
            ?: return ToolResult(
                toolCallId = "",
                content = "sessions_send failed: session delivery is not configured",
                isError = true
            )

        return try {
            val result = callback(
                Request(
                    content = content,
                    sessionId = sessionId.ifBlank { null },
                    sessionTitle = sessionTitle.ifBlank { null },
                    deliverRemote = args.deliverRemote,
                    attachments = attachments,
                    media = media
                )
            )
            ToolResult(
                toolCallId = "",
                content = buildString {
                    append("Message sent to session ")
                    append(result.sessionId)
                    if (result.sessionTitle.isNotBlank()) {
                        append(" (")
                        append(result.sessionTitle)
                        append(")")
                    }
                    append(".")
                    append(if (result.remoteDelivered) " Remote delivery mirrored." else " Local session only.")
                    result.note?.takeIf { it.isNotBlank() }?.let {
                        append(" ")
                        append(it)
                    }
                },
                isError = false,
                metadata = buildJsonObject {
                    put("session_id", result.sessionId)
                    put("session_title", result.sessionTitle)
                    put("remote_delivered", result.remoteDelivered)
                    put(ToolResultMetadataKeys.DELIVERY_TOOL, true)
                    put(ToolResultMetadataKeys.HIDE_UI_RESULT, true)
                    put(ToolResultMetadataKeys.STOP_AGENT_LOOP, true)
                    result.note?.takeIf { it.isNotBlank() }?.let { put("note", it) }
                }
            )
        } catch (t: Throwable) {
            ToolResult(
                toolCallId = "",
                content = "sessions_send failed: ${t.message ?: t.javaClass.simpleName}",
                isError = true
            )
        }
    }

    data class Request(
        val content: String,
        val sessionId: String?,
        val sessionTitle: String?,
        val deliverRemote: Boolean,
        val attachments: List<MessageAttachment> = emptyList(),
        val media: List<String> = emptyList()
    )

    data class DeliveryResult(
        val sessionId: String,
        val sessionTitle: String,
        val remoteDelivered: Boolean,
        val note: String? = null
    )

    private data class Args(
        val content: String,
        val sessionId: String? = null,
        val sessionTitle: String? = null,
        val deliverRemote: Boolean = true,
        val attachments: List<MessageAttachment>? = null,
        val media: List<String>? = null
    )

    private fun parseArgs(raw: String): Args {
        val obj = json.parseToJsonElement(raw) as? JsonObject
            ?: throw IllegalArgumentException("JSON object expected")
        return Args(
            content = obj.string("content").orEmpty(),
            sessionId = obj.string("session_id"),
            sessionTitle = obj.string("session_title"),
            deliverRemote = obj.boolean("deliver_remote") ?: true,
            attachments = runCatching {
                json.decodeFromJsonElement<List<MessageAttachment>>(obj["attachments"] ?: Json.parseToJsonElement("[]"))
            }.getOrDefault(emptyList()),
            media = runCatching {
                json.decodeFromJsonElement<List<String>>(obj["media"] ?: Json.parseToJsonElement("[]"))
            }.getOrDefault(emptyList())
        )
    }

    private fun JsonObject.string(key: String): String? {
        return (this[key] as? JsonPrimitive)?.contentOrNull
    }

    private fun JsonObject.boolean(key: String): Boolean? {
        return (this[key] as? JsonPrimitive)?.booleanOrNull
    }
}
