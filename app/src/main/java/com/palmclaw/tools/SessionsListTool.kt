package com.palmclaw.tools

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class SessionsListTool(
    private var listCallback: (suspend () -> Snapshot)? = null
) : Tool {
    override val name: String = "sessions_list"

    override val description: String =
        "List available local sessions. Use sessionId as the canonical unique identifier; titles are not guaranteed to be unique."

    override val jsonSchema: JsonObject = buildJsonObject {
        put("type", "object")
        put("additionalProperties", false)
        put("properties", buildJsonObject {})
    }

    fun setListCallback(callback: suspend () -> Snapshot) {
        listCallback = callback
    }

    fun clearListCallback() {
        listCallback = null
    }

    override suspend fun run(argumentsJson: String): ToolResult {
        val callback = listCallback
            ?: return ToolResult(
                toolCallId = "",
                content = "sessions_list failed: session listing is not configured",
                isError = true
            )

        return try {
            val snapshot = callback()
            ToolResult(
                toolCallId = "",
                content = Json { prettyPrint = true }.encodeToString(snapshot),
                isError = false,
                metadata = buildJsonObject {
                    put("session_count", snapshot.sessions.size)
                    put("current_session_id", snapshot.currentSessionId)
                }
            )
        } catch (t: Throwable) {
            ToolResult(
                toolCallId = "",
                content = "sessions_list failed: ${t.message ?: t.javaClass.simpleName}",
                isError = true
            )
        }
    }

    @Serializable
    data class Snapshot(
        val currentSessionId: String,
        val sessions: List<Entry>
    )

    @Serializable
    data class Entry(
        val sessionId: String,
        val title: String,
        val status: String,
        val isCurrent: Boolean,
        val isLocal: Boolean,
        val channelEnabled: Boolean,
        val boundChannel: String = "",
        val boundTarget: String = ""
    )
}
