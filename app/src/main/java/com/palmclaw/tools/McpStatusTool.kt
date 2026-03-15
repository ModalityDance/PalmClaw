package com.palmclaw.tools

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class McpStatusTool(
    private var getCallback: (suspend () -> Snapshot)? = null
) : Tool {
    private val json = Json { prettyPrint = true }

    override val name: String = "mcp_status"

    override val description: String =
        "Get MCP runtime status, including whether MCP is enabled, which servers are configured, which are connected, and which MCP tools are available."

    override val jsonSchema: JsonObject = buildJsonObject {
        put("type", "object")
        put("additionalProperties", false)
        put("properties", buildJsonObject {})
    }

    fun setGetCallback(callback: suspend () -> Snapshot) {
        getCallback = callback
    }

    fun clearGetCallback() {
        getCallback = null
    }

    override suspend fun run(argumentsJson: String): ToolResult {
        val callback = getCallback ?: return ToolResult(
            toolCallId = "",
            content = "mcp_status failed: MCP status access is not configured",
            isError = true
        )
        return try {
            val snapshot = callback()
            ToolResult(
                toolCallId = "",
                content = json.encodeToString(JsonObject.serializer(), snapshot.toJson()),
                isError = false,
                metadata = buildJsonObject {
                    put("enabled", snapshot.enabled)
                    put("server_count", snapshot.servers.size)
                    put("connected_server_count", snapshot.connectedServerCount)
                    put("registered_tool_count", snapshot.registeredToolCount)
                }
            )
        } catch (t: Throwable) {
            ToolResult(
                toolCallId = "",
                content = "mcp_status failed: ${t.message ?: t.javaClass.simpleName}",
                isError = true
            )
        }
    }

    data class Snapshot(
        val enabled: Boolean,
        val connectedServerCount: Int,
        val registeredToolCount: Int,
        val servers: List<Entry>
    ) {
        fun toJson(): JsonObject = buildJsonObject {
            put("enabled", enabled)
            put("connected_server_count", connectedServerCount)
            put("registered_tool_count", registeredToolCount)
            put(
                "servers",
                buildJsonArray {
                    servers.forEach { add(it.toJson()) }
                }
            )
        }
    }

    data class Entry(
        val id: String,
        val serverName: String,
        val serverUrl: String,
        val status: String,
        val usable: Boolean,
        val detail: String,
        val toolCount: Int,
        val toolNames: List<String>
    ) {
        fun toJson(): JsonObject = buildJsonObject {
            put("id", id)
            put("server_name", serverName)
            put("server_url", serverUrl)
            put("status", status)
            put("usable", usable)
            put("detail", detail)
            put("tool_count", toolCount)
            put(
                "tool_names",
                buildJsonArray {
                    toolNames.forEach { add(JsonPrimitive(it)) }
                }
            )
        }
    }
}
