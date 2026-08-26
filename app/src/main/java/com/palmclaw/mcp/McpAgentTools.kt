package com.palmclaw.mcp

import com.palmclaw.mcp.transport.McpAnnotations
import com.palmclaw.mcp.transport.McpClientSession
import com.palmclaw.mcp.transport.McpCompletionReference
import com.palmclaw.mcp.transport.McpCompletionRequest
import com.palmclaw.mcp.transport.McpCompletionResult
import com.palmclaw.mcp.transport.McpContentBlock
import com.palmclaw.mcp.transport.McpPromptMessage
import com.palmclaw.mcp.transport.McpPromptResult
import com.palmclaw.mcp.transport.McpReadResourceResult
import com.palmclaw.mcp.transport.McpRemotePrompt
import com.palmclaw.mcp.transport.McpRemoteResource
import com.palmclaw.mcp.transport.McpRemoteResourceTemplate
import com.palmclaw.mcp.transport.McpRemoteTool
import com.palmclaw.mcp.transport.McpResourceContents
import com.palmclaw.mcp.transport.McpToolAnnotations
import com.palmclaw.mcp.transport.McpToolCallResult
import com.palmclaw.mcp.transport.McpTransportErrorCode
import com.palmclaw.mcp.transport.McpTransportException
import com.palmclaw.tools.TimedTool
import com.palmclaw.tools.Tool
import com.palmclaw.tools.ToolResult
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.security.MessageDigest
import java.util.Locale

internal data class McpContentTarget(
    val serverId: String,
    val serverName: String,
    val session: McpClientSession
)

internal class McpRemoteToolAdapter(
    private val serverId: String,
    private val serverName: String,
    private val remote: McpRemoteTool,
    private val session: McpClientSession,
    timeoutSeconds: Int
) : Tool, TimedTool {
    override val name: String = McpPublishedToolName.canonicalize(serverName, remote.name)
    override val description: String = buildString {
        append(remote.description?.takeIf { it.isNotBlank() } ?: remote.title ?: remote.name)
        remote.annotations?.safetyDescription()?.let { append(" Remote MCP hints: ").append(it) }
    }
    override val jsonSchema: JsonObject = remote.inputSchema
    override val timeoutMs: Long = timeoutSeconds.coerceIn(5, 300) * 1_000L

    override suspend fun run(argumentsJson: String): ToolResult {
        val arguments = parseObject(argumentsJson)
            ?: return toolError(
                serverId = serverId,
                serverName = serverName,
                remoteName = remote.name,
                code = "invalid_arguments",
                message = "Arguments must be a JSON object",
                recoverable = true,
                annotations = remote.annotations
            )
        return try {
            session.callTool(remote.name, arguments).toToolResult(
                serverId = serverId,
                serverName = serverName,
                remoteName = remote.name,
                annotations = remote.annotations
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            failure.toToolError(serverId, serverName, remote.name, remote.annotations)
        }
    }
}

/** Canonicalizes provider-controlled names to the common LLM function-name boundary. */
internal object McpPublishedToolName {
    private const val MAX_LENGTH = 64
    private const val HASH_HEX_LENGTH = 12
    private const val HEX = "0123456789abcdef"

    fun canonicalize(serverName: String, remoteToolName: String): String {
        val normalizedServer = normalize(serverName, "default")
        val normalizedTool = normalize(remoteToolName, "tool")
        val candidate = "mcp_${normalizedServer}_$normalizedTool"
        if (candidate.length <= MAX_LENGTH) return candidate

        val hash = stableHash("$serverName\u0000$remoteToolName")
        val prefixLength = MAX_LENGTH - HASH_HEX_LENGTH - 1
        val prefix = candidate.take(prefixLength).trimEnd('_', '-')
        return "${prefix}_$hash"
    }

    private fun normalize(input: String, fallback: String): String = input.trim()
        .lowercase(Locale.US)
        .replace(Regex("[^a-z0-9_\\-]+"), "_")
        .trim('_')
        .ifBlank { fallback }

    private fun stableHash(value: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        return buildString(HASH_HEX_LENGTH) {
            repeat(HASH_HEX_LENGTH / 2) { index ->
                val unsigned = bytes[index].toInt() and 0xff
                append(HEX[unsigned ushr 4])
                append(HEX[unsigned and 0x0f])
            }
        }
    }
}

internal class McpContentTool(
    private val targets: () -> List<McpContentTarget>
) : Tool {
    override val name: String = "mcp_content"
    override val description: String =
        "Use resources, resource templates, prompts, and completion exposed by connected MCP servers."
    override val jsonSchema: JsonObject = Json.parseToJsonElement(
        """
        {
          "type":"object",
          "additionalProperties":false,
          "required":["action"],
          "properties":{
            "action":{"type":"string","enum":["list_resources","list_resource_templates","read_resource","list_prompts","get_prompt","complete"]},
            "server_id":{"type":"string"},
            "server_name":{"type":"string"},
            "cursor":{"type":"string"},
            "uri":{"type":"string"},
            "name":{"type":"string"},
            "arguments":{"type":"object","additionalProperties":{"type":"string"}},
            "reference_type":{"type":"string","enum":["prompt","resource_template"]},
            "argument_name":{"type":"string"},
            "argument_value":{"type":"string"},
            "context_arguments":{"type":"object","additionalProperties":{"type":"string"}}
          }
        }
        """.trimIndent()
    ).jsonObject

    override suspend fun run(argumentsJson: String): ToolResult {
        val arguments = parseObject(argumentsJson)
            ?: return contentError("invalid_arguments", "Arguments must be a JSON object")
        val target = when (val selected = selectTarget(arguments, targets())) {
            is TargetSelection.Found -> selected.target
            is TargetSelection.Failed -> return contentError(selected.code, selected.message)
        }
        val action = arguments.string("action")
            ?: return contentError("action_required", "action is required", target)
        return try {
            val payload = when (action) {
                "list_resources" -> {
                    requireCapability(target.session.capabilities.resources, "resources")
                    pageJson(
                        page = target.session.listResources(arguments.string("cursor")),
                        field = "resources",
                        itemJson = ::resourceJson
                    )
                }
                "list_resource_templates" -> {
                    requireCapability(target.session.capabilities.resources, "resources")
                    pageJson(
                        page = target.session.listResourceTemplates(arguments.string("cursor")),
                        field = "resource_templates",
                        itemJson = ::resourceTemplateJson
                    )
                }
                "read_resource" -> {
                    requireCapability(target.session.capabilities.resources, "resources")
                    val uri = arguments.requiredString("uri")
                    readResourceJson(target.session.readResource(uri))
                }
                "list_prompts" -> {
                    requireCapability(target.session.capabilities.prompts, "prompts")
                    pageJson(
                        page = target.session.listPrompts(arguments.string("cursor")),
                        field = "prompts",
                        itemJson = ::promptJson
                    )
                }
                "get_prompt" -> {
                    requireCapability(target.session.capabilities.prompts, "prompts")
                    promptResultJson(
                        target.session.getPrompt(
                            name = arguments.requiredString("name"),
                            arguments = arguments.stringMap("arguments")
                        )
                    )
                }
                "complete" -> {
                    requireCapability(target.session.capabilities.completions, "completion")
                    completionJson(target.session.complete(arguments.completionRequest()))
                }
                else -> return contentError("unsupported_action", "Unsupported mcp_content action", target)
            }
            ToolResult(
                toolCallId = "",
                content = payload.toString(),
                isError = false,
                metadata = buildJsonObject {
                    put("status", "ok")
                    put("mcp_server_id", target.serverId)
                    put("mcp_server", target.serverName)
                    put("action", action)
                    put("mcp_content_chars", payload.toString().length)
                }
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (invalid: IllegalArgumentException) {
            contentError("invalid_arguments", invalid.message ?: "Invalid arguments", target)
        } catch (failure: Throwable) {
            failure.toToolError(target.serverId, target.serverName, action, null)
        }
    }

    private sealed interface TargetSelection {
        data class Found(val target: McpContentTarget) : TargetSelection
        data class Failed(val code: String, val message: String) : TargetSelection
    }

    private fun selectTarget(arguments: JsonObject, available: List<McpContentTarget>): TargetSelection {
        val requestedId = arguments.string("server_id")
        val requestedName = arguments.string("server_name")?.let(DefaultMcpRuntimeLifecycle::normalizedServerName)
        if (requestedId == null && requestedName == null) {
            return TargetSelection.Failed("server_required", "Pass server_id or server_name")
        }
        val matches = available.filter { target ->
            (requestedId == null || target.serverId == requestedId) &&
                (requestedName == null || target.serverName == requestedName)
        }
        return when (matches.size) {
            1 -> TargetSelection.Found(matches.single())
            0 -> TargetSelection.Failed("server_not_found", "No connected MCP server matches the selector")
            else -> TargetSelection.Failed("server_ambiguous", "MCP server selector must identify one server")
        }
    }
}

private fun McpToolCallResult.toToolResult(
    serverId: String,
    serverName: String,
    remoteName: String,
    annotations: McpToolAnnotations?
): ToolResult = ToolResult(
    toolCallId = "",
    content = buildString {
        append(content.toDisplayText())
        structuredContent?.let { structured ->
            if (isNotEmpty()) append('\n')
            append("Structured content: ").append(structured)
        }
        meta?.let { remoteMeta ->
            if (isNotEmpty()) append('\n')
            append("MCP meta: ").append(remoteMeta)
        }
    }.ifBlank { "(no output)" },
    isError = isError,
    metadata = buildJsonObject {
        put("status", if (isError) "error" else "ok")
        put("mcp_server_id", serverId)
        put("mcp_server", serverName)
        put("mcp_tool", remoteName)
        put("mcp_is_error", isError)
        put("mcp_content_types", JsonArray(content.map { JsonPrimitive(it.typeName()) }.distinct()))
        put("mcp_content_count", content.size)
        structuredContent?.let { put("mcp_has_structured_content", true) }
        meta?.let { put("mcp_has_meta", true) }
        annotations?.let { put("mcp_tool_annotations", toolAnnotationsJson(it)) }
    }
)

private fun Throwable.toToolError(
    serverId: String,
    serverName: String,
    remoteName: String,
    annotations: McpToolAnnotations?
): ToolResult {
    val transport = this as? McpTransportException
    val code = transport?.code?.wireValue ?: "mcp_call_failed"
    val recoverable = transport?.recoverable ?: false
    val outcomeUnknown = transport?.outcomeUnknown ?: false
    val message = when (transport?.code) {
        McpTransportErrorCode.AUTHENTICATION_REQUIRED -> "MCP authentication was rejected"
        McpTransportErrorCode.PERMISSION_DENIED -> "MCP access was denied"
        McpTransportErrorCode.NOT_FOUND -> "MCP method or item was not found"
        McpTransportErrorCode.RATE_LIMITED -> "MCP server rate limited the request"
        McpTransportErrorCode.SERVER_ERROR -> "MCP server failed the request"
        McpTransportErrorCode.TIMEOUT -> if (outcomeUnknown) "MCP request timed out; its outcome is unknown" else "MCP request timed out"
        McpTransportErrorCode.NETWORK -> if (outcomeUnknown) "MCP connection failed; the request outcome is unknown" else "MCP network request failed"
        McpTransportErrorCode.PROTOCOL -> "MCP protocol response was invalid"
        McpTransportErrorCode.RESPONSE_TOO_LARGE -> "MCP response exceeded the configured limit"
        McpTransportErrorCode.UNSUPPORTED -> "MCP capability is not supported"
        null -> "MCP call failed (${javaClass.simpleName})"
    }
    return toolError(serverId, serverName, remoteName, code, message, recoverable, outcomeUnknown, annotations)
}

private fun toolError(
    serverId: String,
    serverName: String,
    remoteName: String,
    code: String,
    message: String,
    recoverable: Boolean,
    outcomeUnknown: Boolean = false,
    annotations: McpToolAnnotations?
) = ToolResult(
    toolCallId = "",
    content = message,
    isError = true,
    metadata = buildJsonObject {
        put("status", "error")
        put("error", code)
        put("recoverable", recoverable)
        put("outcome_unknown", outcomeUnknown)
        put("mcp_server_id", serverId)
        put("mcp_server", serverName)
        put("mcp_tool", remoteName)
        annotations?.let { put("mcp_tool_annotations", toolAnnotationsJson(it)) }
    }
)

private fun contentError(
    code: String,
    message: String,
    target: McpContentTarget? = null
) = ToolResult(
    toolCallId = "",
    content = message,
    isError = true,
    metadata = buildJsonObject {
        put("status", "error")
        put("error", code)
        target?.let {
            put("mcp_server_id", it.serverId)
            put("mcp_server", it.serverName)
        }
    }
)

private val McpTransportErrorCode.wireValue: String
    get() = name.lowercase(Locale.US)

private fun parseObject(raw: String): JsonObject? = try {
    Json.parseToJsonElement(raw).jsonObject
} catch (_: Throwable) {
    null
}

private fun JsonObject.string(name: String): String? =
    (get(name) as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }

private fun JsonObject.requiredString(name: String): String =
    string(name) ?: throw IllegalArgumentException("$name is required")

private fun JsonObject.stringMap(name: String): Map<String, String> =
    (get(name) as? JsonObject).orEmpty().mapValues { (key, value) ->
        (value as? JsonPrimitive)?.contentOrNull
            ?: throw IllegalArgumentException("$name.$key must be a string")
    }

private fun JsonObject.completionRequest(): McpCompletionRequest {
    val reference = when (requiredString("reference_type")) {
        "prompt" -> McpCompletionReference.Prompt(requiredString("name"))
        "resource_template" -> McpCompletionReference.ResourceTemplate(requiredString("uri"))
        else -> throw IllegalArgumentException("reference_type must be prompt or resource_template")
    }
    return McpCompletionRequest(
        reference = reference,
        argumentName = requiredString("argument_name"),
        argumentValue = requiredString("argument_value"),
        contextArguments = stringMap("context_arguments")
    )
}

private fun requireCapability(supported: Boolean, capability: String) {
    if (!supported) throw McpTransportException(
        code = McpTransportErrorCode.UNSUPPORTED,
        message = "Server does not expose $capability",
        recoverable = false
    )
}

private fun List<McpContentBlock>.toDisplayText(): String {
    if (isEmpty()) return ""
    return joinToString("\n") { block ->
        when (block) {
            is McpContentBlock.Text -> block.text
            is McpContentBlock.Image -> "[image mime_type=${block.mimeType} base64_chars=${block.data.length}]"
            is McpContentBlock.Audio -> "[audio mime_type=${block.mimeType} base64_chars=${block.data.length}]"
            is McpContentBlock.ResourceLink -> "[resource ${block.name}: ${block.uri}]"
            is McpContentBlock.EmbeddedResource -> when (val resource = block.resource) {
                is McpResourceContents.Text -> resource.text
                is McpResourceContents.Blob -> "[embedded resource ${resource.uri} base64_chars=${resource.data.length}]"
                is McpResourceContents.Unknown -> "[embedded resource ${resource.uri}]"
            }
        }
    }
}

private fun contentBlockJson(block: McpContentBlock): JsonObject = buildJsonObject {
    when (block) {
        is McpContentBlock.Text -> {
            put("type", "text")
            put("text", block.text)
        }
        is McpContentBlock.Image -> {
            put("type", "image")
            put("data", block.data)
            put("mime_type", block.mimeType)
        }
        is McpContentBlock.Audio -> {
            put("type", "audio")
            put("data", block.data)
            put("mime_type", block.mimeType)
        }
        is McpContentBlock.ResourceLink -> {
            put("type", "resource_link")
            put("uri", block.uri)
            put("name", block.name)
            block.title?.let { put("title", it) }
            block.description?.let { put("description", it) }
            block.mimeType?.let { put("mime_type", it) }
            block.size?.let { put("size", it) }
        }
        is McpContentBlock.EmbeddedResource -> {
            put("type", "embedded_resource")
            put("resource", resourceContentsJson(block.resource))
        }
    }
    block.annotations?.let { put("annotations", annotationsJson(it)) }
    block.meta?.let { put("meta", it) }
}

private fun McpContentBlock.typeName(): String = when (this) {
    is McpContentBlock.Text -> "text"
    is McpContentBlock.Image -> "image"
    is McpContentBlock.Audio -> "audio"
    is McpContentBlock.ResourceLink -> "resource_link"
    is McpContentBlock.EmbeddedResource -> "embedded_resource"
}

private fun <T> pageJson(
    page: com.palmclaw.mcp.transport.McpPage<T>,
    field: String,
    itemJson: (T) -> JsonObject
): JsonObject = buildJsonObject {
    put(field, JsonArray(page.items.map(itemJson)))
    page.nextCursor?.let { put("next_cursor", it) }
    page.meta?.let { put("meta", it) }
}

private fun resourceJson(resource: McpRemoteResource): JsonObject = buildJsonObject {
    put("uri", resource.uri)
    put("name", resource.name)
    resource.title?.let { put("title", it) }
    resource.description?.let { put("description", it) }
    resource.mimeType?.let { put("mime_type", it) }
    resource.size?.let { put("size", it) }
    resource.annotations?.let { put("annotations", annotationsJson(it)) }
    resource.meta?.let { put("meta", it) }
}

private fun resourceTemplateJson(resource: McpRemoteResourceTemplate): JsonObject = buildJsonObject {
    put("uri_template", resource.uriTemplate)
    put("name", resource.name)
    resource.title?.let { put("title", it) }
    resource.description?.let { put("description", it) }
    resource.mimeType?.let { put("mime_type", it) }
    resource.annotations?.let { put("annotations", annotationsJson(it)) }
    resource.meta?.let { put("meta", it) }
}

private fun readResourceJson(result: McpReadResourceResult): JsonObject = buildJsonObject {
    put("contents", JsonArray(result.contents.map(::resourceContentsJson)))
    result.meta?.let { put("meta", it) }
}

private fun resourceContentsJson(resource: McpResourceContents): JsonObject = buildJsonObject {
    put("uri", resource.uri)
    resource.mimeType?.let { put("mime_type", it) }
    when (resource) {
        is McpResourceContents.Text -> {
            put("type", "text")
            put("text", resource.text)
        }
        is McpResourceContents.Blob -> {
            put("type", "blob")
            put("data", resource.data)
        }
        is McpResourceContents.Unknown -> put("type", "unknown")
    }
    resource.meta?.let { put("meta", it) }
}

private fun promptJson(prompt: McpRemotePrompt): JsonObject = buildJsonObject {
    put("name", prompt.name)
    prompt.title?.let { put("title", it) }
    prompt.description?.let { put("description", it) }
    put("arguments", buildJsonArray {
        prompt.arguments.forEach { argument ->
            add(buildJsonObject {
                put("name", argument.name)
                put("required", argument.required)
                argument.title?.let { put("title", it) }
                argument.description?.let { put("description", it) }
            })
        }
    })
    prompt.meta?.let { put("meta", it) }
}

private fun promptResultJson(result: McpPromptResult): JsonObject = buildJsonObject {
    result.description?.let { put("description", it) }
    put("messages", JsonArray(result.messages.map(::promptMessageJson)))
    result.meta?.let { put("meta", it) }
}

private fun promptMessageJson(message: McpPromptMessage): JsonObject = buildJsonObject {
    put("role", message.role.name.lowercase(Locale.US))
    put("content", contentBlockJson(message.content))
}

private fun completionJson(result: McpCompletionResult): JsonObject = buildJsonObject {
    put("values", JsonArray(result.values.map(::JsonPrimitive)))
    result.total?.let { put("total", it) }
    result.hasMore?.let { put("has_more", it) }
    result.meta?.let { put("meta", it) }
}

private fun annotationsJson(annotations: McpAnnotations): JsonObject = buildJsonObject {
    annotations.audience?.let { roles ->
        put("audience", JsonArray(roles.map { JsonPrimitive(it.name.lowercase(Locale.US)) }))
    }
    annotations.priority?.let { put("priority", it) }
    annotations.lastModified?.let { put("last_modified", it) }
}

private fun toolAnnotationsJson(annotations: McpToolAnnotations): JsonObject = buildJsonObject {
    annotations.title?.let { put("title", it) }
    annotations.readOnlyHint?.let { put("read_only", it) }
    annotations.destructiveHint?.let { put("destructive", it) }
    annotations.idempotentHint?.let { put("idempotent", it) }
    annotations.openWorldHint?.let { put("open_world", it) }
}

private fun McpToolAnnotations.safetyDescription(): String? {
    val hints = buildList {
        if (readOnlyHint == true) add("read-only")
        if (destructiveHint == true) add("destructive")
        if (idempotentHint == true) add("idempotent")
        if (openWorldHint == true) add("open-world")
    }
    return hints.takeIf { it.isNotEmpty() }?.joinToString()
}
