package com.palmclaw.tools

import com.palmclaw.mcp.McpEndpointPolicy
import kotlinx.coroutines.CancellationException
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
        "Get structured MCP runtime status, top-level runtime issues, negotiated transport and protocol, endpoint security, and available tool, resource, and prompt counts for every configured server."

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
                    put("generation", snapshot.generation)
                    put("server_count", snapshot.servers.size)
                    put("connected_server_count", snapshot.connectedServerCount)
                    put("registered_tool_count", snapshot.registeredToolCount)
                    put("available_resource_count", snapshot.availableResourceCount)
                    put("available_resource_template_count", snapshot.availableResourceTemplateCount)
                    put("available_prompt_count", snapshot.availablePromptCount)
                    put("issue_count", snapshot.issues.size)
                }
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
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
        val generation: Long,
        val connectedServerCount: Int,
        val registeredToolCount: Int,
        val availableResourceCount: Int,
        val availableResourceTemplateCount: Int,
        val availablePromptCount: Int,
        val issues: List<Issue> = emptyList(),
        val servers: List<Entry>
    ) {
        fun toJson(): JsonObject = buildJsonObject {
            put("enabled", enabled)
            put("generation", generation)
            put("connected_server_count", connectedServerCount)
            put("registered_tool_count", registeredToolCount)
            put("available_resource_count", availableResourceCount)
            put("available_resource_template_count", availableResourceTemplateCount)
            put("available_prompt_count", availablePromptCount)
            put(
                "issues",
                buildJsonArray {
                    issues.forEach { add(it.toJson()) }
                }
            )
            put(
                "servers",
                buildJsonArray {
                    servers.forEach { add(it.toJson()) }
                }
            )
        }
    }

    data class Issue(
        val code: String,
        val detail: String
    ) {
        fun toJson(): JsonObject = buildJsonObject {
            put("code", code)
            put("detail", detail)
        }
    }

    data class Entry(
        val serverId: String,
        val serverName: String,
        val serverUrl: String,
        val phase: String,
        val status: String,
        val usable: Boolean,
        val detail: String,
        val toolCount: Int,
        val resourceCount: Int,
        val resourceTemplateCount: Int,
        val promptCount: Int,
        val completionSupported: Boolean,
        val toolNames: List<String>,
        val transport: String?,
        val protocolVersion: String?,
        val endpointSecurity: String?,
        val insecureWarning: String?
    ) {
        fun toJson(): JsonObject = buildJsonObject {
            put("server_id", serverId)
            put("server_name", serverName)
            put("server_url", McpEndpointPolicy.safeDisplayUrl(serverUrl))
            put("phase", phase)
            put("status", status)
            put("usable", usable)
            put("detail", detail)
            put("tool_count", toolCount)
            put("resource_count", resourceCount)
            put("resource_template_count", resourceTemplateCount)
            put("prompt_count", promptCount)
            put("completion_supported", completionSupported)
            transport?.let { put("transport", it) }
            protocolVersion?.let { put("protocol_version", it) }
            endpointSecurity?.let { put("endpoint_security", it) }
            insecureWarning?.let { put("insecure_warning", it) }
            put(
                "tool_names",
                buildJsonArray {
                    toolNames.forEach { add(JsonPrimitive(it)) }
                }
            )
        }
    }
}
