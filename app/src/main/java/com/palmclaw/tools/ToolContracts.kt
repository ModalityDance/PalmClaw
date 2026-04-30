package com.palmclaw.tools

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

interface Tool {
    val name: String
    val description: String
    val jsonSchema: JsonObject

    suspend fun run(argumentsJson: String): ToolResult
}

interface TimedTool {
    val timeoutMs: Long
}

@Serializable
data class ToolResult(
    val toolCallId: String,
    val content: String,
    val isError: Boolean,
    val metadata: JsonObject? = null
)

object ToolResultMetadataKeys {
    const val HIDE_UI_RESULT = "hide_ui_result"
    const val STOP_AGENT_LOOP = "stop_agent_loop"
    const val DELIVERY_TOOL = "delivery_tool"
}
