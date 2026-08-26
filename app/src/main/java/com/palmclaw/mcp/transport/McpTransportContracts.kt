package com.palmclaw.mcp.transport

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.JsonObject

/**
 * Protocol-neutral boundary between PalmClaw's MCP lifecycle and a concrete MCP SDK.
 *
 * No official SDK type crosses this boundary. This keeps protocol/library upgrades local to
 * [OfficialKotlinMcpTransportFactory] and makes lifecycle tests independent from network I/O.
 */
interface McpTransportClientFactory {
    suspend fun connect(request: McpTransportConnectRequest): McpClientSession
}

data class McpTransportConnectRequest(
    val serverId: String,
    val endpoint: String,
    val bearerToken: String? = null,
    val requestTimeoutMillis: Long,
    val transportPreference: McpTransportPreference = McpTransportPreference.AUTO,
    val responseLimits: McpResponseLimits = McpResponseLimits(),
) {
    init {
        require(serverId.isNotBlank()) { "serverId must not be blank" }
        require(endpoint.isNotBlank()) { "endpoint must not be blank" }
        require(requestTimeoutMillis > 0) { "requestTimeoutMillis must be positive" }
    }
}

enum class McpTransportPreference {
    AUTO,
    STREAMABLE_HTTP,
    LEGACY_HTTP_SSE,
}

enum class McpTransportKind {
    STREAMABLE_HTTP,
    LEGACY_HTTP_SSE,
}

data class McpResponseLimits(
    /** Maximum decoded HTTP response bytes, or bytes in one SSE event for a long-lived stream. */
    val maxResponseBytes: Long = 16L * 1024L * 1024L,
    val maxItemsPerPage: Int = 1_000,
    val maxTextChars: Int = 2_000_000,
    val maxBase64Chars: Int = 8_000_000,
) {
    init {
        require(maxResponseBytes > 0) { "maxResponseBytes must be positive" }
        require(maxItemsPerPage > 0) { "maxItemsPerPage must be positive" }
        require(maxTextChars > 0) { "maxTextChars must be positive" }
        require(maxBase64Chars > 0) { "maxBase64Chars must be positive" }
    }
}

interface McpClientSession {
    val negotiated: McpNegotiatedInfo
    val capabilities: McpServerCapabilities
    /** Hot event stream. It stops emitting after [close]; collectors should be owned by the session lifecycle. */
    val events: Flow<McpServerEvent>

    suspend fun listTools(cursor: String? = null): McpPage<McpRemoteTool>

    /** A tool call is attempted once. The transport must never replay it automatically. */
    suspend fun callTool(name: String, arguments: JsonObject): McpToolCallResult

    suspend fun listResources(cursor: String? = null): McpPage<McpRemoteResource>
    suspend fun listResourceTemplates(cursor: String? = null): McpPage<McpRemoteResourceTemplate>
    suspend fun readResource(uri: String): McpReadResourceResult
    suspend fun listPrompts(cursor: String? = null): McpPage<McpRemotePrompt>
    suspend fun getPrompt(name: String, arguments: Map<String, String> = emptyMap()): McpPromptResult
    suspend fun complete(request: McpCompletionRequest): McpCompletionResult
    suspend fun close()
}

data class McpNegotiatedInfo(
    val transport: McpTransportKind,
    /** Exact value returned by the server during MCP initialization. */
    val protocolVersion: String,
    val server: McpImplementationInfo,
    val instructions: String? = null,
)

data class McpImplementationInfo(
    val name: String,
    val version: String,
    val title: String? = null,
    val websiteUrl: String? = null,
)

data class McpServerCapabilities(
    val tools: Boolean = false,
    val toolListChanged: Boolean = false,
    val resources: Boolean = false,
    val resourceListChanged: Boolean = false,
    val resourceSubscriptions: Boolean = false,
    val prompts: Boolean = false,
    val promptListChanged: Boolean = false,
    val completions: Boolean = false,
)

sealed interface McpServerEvent {
    data object ToolsChanged : McpServerEvent
    data object ResourcesChanged : McpServerEvent
    data object PromptsChanged : McpServerEvent
    data class ResourceUpdated(val uri: String) : McpServerEvent
    data object Disconnected : McpServerEvent
}

data class McpPage<T>(
    val items: List<T>,
    val nextCursor: String? = null,
    val meta: JsonObject? = null,
)

data class McpRemoteTool(
    val name: String,
    val description: String? = null,
    val title: String? = null,
    val inputSchema: JsonObject,
    val outputSchema: JsonObject? = null,
    val annotations: McpToolAnnotations? = null,
    val meta: JsonObject? = null,
)

data class McpToolAnnotations(
    val title: String? = null,
    val readOnlyHint: Boolean? = null,
    val destructiveHint: Boolean? = null,
    val idempotentHint: Boolean? = null,
    val openWorldHint: Boolean? = null,
)

data class McpToolCallResult(
    val content: List<McpContentBlock>,
    val structuredContent: JsonObject? = null,
    val isError: Boolean = false,
    val meta: JsonObject? = null,
)

sealed interface McpContentBlock {
    val annotations: McpAnnotations?
    val meta: JsonObject?

    data class Text(
        val text: String,
        override val annotations: McpAnnotations? = null,
        override val meta: JsonObject? = null,
    ) : McpContentBlock

    data class Image(
        val data: String,
        val mimeType: String,
        override val annotations: McpAnnotations? = null,
        override val meta: JsonObject? = null,
    ) : McpContentBlock

    data class Audio(
        val data: String,
        val mimeType: String,
        override val annotations: McpAnnotations? = null,
        override val meta: JsonObject? = null,
    ) : McpContentBlock

    data class ResourceLink(
        val uri: String,
        val name: String,
        val title: String? = null,
        val description: String? = null,
        val mimeType: String? = null,
        val size: Long? = null,
        override val annotations: McpAnnotations? = null,
        override val meta: JsonObject? = null,
    ) : McpContentBlock

    data class EmbeddedResource(
        val resource: McpResourceContents,
        override val annotations: McpAnnotations? = null,
        override val meta: JsonObject? = null,
    ) : McpContentBlock
}

data class McpAnnotations(
    val audience: List<McpRole>? = null,
    val priority: Double? = null,
    val lastModified: String? = null,
)

enum class McpRole {
    USER,
    ASSISTANT,
}

data class McpRemoteResource(
    val uri: String,
    val name: String,
    val title: String? = null,
    val description: String? = null,
    val mimeType: String? = null,
    val size: Long? = null,
    val annotations: McpAnnotations? = null,
    val meta: JsonObject? = null,
)

data class McpRemoteResourceTemplate(
    val uriTemplate: String,
    val name: String,
    val title: String? = null,
    val description: String? = null,
    val mimeType: String? = null,
    val annotations: McpAnnotations? = null,
    val meta: JsonObject? = null,
)

sealed interface McpResourceContents {
    val uri: String
    val mimeType: String?
    val meta: JsonObject?

    data class Text(
        override val uri: String,
        override val mimeType: String?,
        val text: String,
        override val meta: JsonObject? = null,
    ) : McpResourceContents

    data class Blob(
        override val uri: String,
        override val mimeType: String?,
        val data: String,
        override val meta: JsonObject? = null,
    ) : McpResourceContents

    data class Unknown(
        override val uri: String,
        override val mimeType: String?,
        override val meta: JsonObject? = null,
    ) : McpResourceContents
}

data class McpReadResourceResult(
    val contents: List<McpResourceContents>,
    val meta: JsonObject? = null,
)

data class McpRemotePrompt(
    val name: String,
    val description: String? = null,
    val title: String? = null,
    val arguments: List<McpPromptArgument> = emptyList(),
    val meta: JsonObject? = null,
)

data class McpPromptArgument(
    val name: String,
    val description: String? = null,
    val required: Boolean = false,
    val title: String? = null,
)

data class McpPromptResult(
    val description: String? = null,
    val messages: List<McpPromptMessage>,
    val meta: JsonObject? = null,
)

data class McpPromptMessage(
    val role: McpRole,
    val content: McpContentBlock,
)

sealed interface McpCompletionReference {
    data class Prompt(val name: String, val title: String? = null) : McpCompletionReference
    data class ResourceTemplate(val uri: String) : McpCompletionReference
}

data class McpCompletionRequest(
    val reference: McpCompletionReference,
    val argumentName: String,
    val argumentValue: String,
    val contextArguments: Map<String, String> = emptyMap(),
)

data class McpCompletionResult(
    val values: List<String>,
    val total: Int? = null,
    val hasMore: Boolean? = null,
    val meta: JsonObject? = null,
)

enum class McpTransportErrorCode {
    AUTHENTICATION_REQUIRED,
    PERMISSION_DENIED,
    NOT_FOUND,
    RATE_LIMITED,
    SERVER_ERROR,
    TIMEOUT,
    NETWORK,
    PROTOCOL,
    RESPONSE_TOO_LARGE,
    UNSUPPORTED,
}

open class McpTransportException(
    val code: McpTransportErrorCode,
    message: String,
    val recoverable: Boolean,
    /** True when the request may have reached the server before the failure was observed. */
    val outcomeUnknown: Boolean = false,
    val httpStatus: Int? = null,
    cause: Throwable? = null,
) : Exception(message, cause)

class McpResponseLimitException(
    message: String,
    cause: Throwable? = null,
    outcomeUnknown: Boolean = false,
) : McpTransportException(
    code = McpTransportErrorCode.RESPONSE_TOO_LARGE,
    message = message,
    recoverable = false,
    outcomeUnknown = outcomeUnknown,
    cause = cause,
)

/** Used by tests and lifecycle fakes without depending on an SDK implementation. */
class FakeMcpTransportClientFactory(
    private val connectBlock: suspend (McpTransportConnectRequest) -> McpClientSession,
) : McpTransportClientFactory {
    override suspend fun connect(request: McpTransportConnectRequest): McpClientSession = connectBlock(request)
}
