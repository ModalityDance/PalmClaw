package com.palmclaw.agent

import com.palmclaw.providers.ProtocolMetadata
import com.palmclaw.providers.ToolCall
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
internal data class StoredAssistantTrace(
    val toolCalls: List<ToolCall> = emptyList(),
    val reasoningContent: String? = null,
    val protocolMetadata: ProtocolMetadata? = null,
    val content: String? = null
)

/** One storage contract for both writing and rebuilding assistant history. */
internal object AssistantTraceCodec {
    private val json = Json { ignoreUnknownKeys = true }

    fun encode(trace: StoredAssistantTrace): String? =
        if (trace.toolCalls.isEmpty() && trace.reasoningContent.isNullOrBlank() && trace.protocolMetadata == null) {
            null
        } else {
            json.encodeToString(trace)
        }

    fun decode(raw: String?): StoredAssistantTrace {
        if (raw.isNullOrBlank()) return StoredAssistantTrace()
        return if (raw.trimStart().startsWith("[")) {
            StoredAssistantTrace(toolCalls = json.decodeFromString<List<ToolCall>>(raw))
        } else {
            json.decodeFromString<StoredAssistantTrace>(raw)
        }
    }
}
