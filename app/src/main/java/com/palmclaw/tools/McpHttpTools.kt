package com.palmclaw.tools

import android.util.Log
import com.palmclaw.config.McpHttpConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

class McpHttpRuntime private constructor(
    private val client: McpHttpClient,
    val serverName: String,
    val registeredToolNames: List<String>
) {
    fun close() {
        client.close()
    }

    companion object {
        suspend fun connect(config: McpHttpConfig, registry: ToolRegistry): McpHttpRuntime {
            require(config.serverUrl.trim().isNotBlank()) {
                "MCP server URL is required when MCP is enabled"
            }
            val normalizedServerName = normalizeName(config.serverName).ifBlank { "default" }
            val toolTimeoutSeconds = config.toolTimeoutSeconds.coerceIn(5, 300)
            val requestTimeoutSeconds = (toolTimeoutSeconds + 10).coerceAtMost(360)
            val client = McpHttpClient(
                serverName = normalizedServerName,
                serverUrl = config.serverUrl,
                authToken = config.authToken,
                requestTimeoutSeconds = requestTimeoutSeconds
            )
            return try {
                val discovered = client.listTools()
                val wrappers = discovered.map { tool ->
                    McpToolWrapper(
                        client = client,
                        serverName = normalizedServerName,
                        toolDef = tool,
                        toolTimeoutSeconds = toolTimeoutSeconds
                    )
                }
                registry.registerAll(wrappers)
                Log.i(TAG, "MCP connected: server=$normalizedServerName tools=${wrappers.size}")
                McpHttpRuntime(client, normalizedServerName, wrappers.map { it.name })
            } catch (t: Throwable) {
                client.close()
                throw IllegalStateException(classifyConnectError(t), t)
            }
        }

        private fun normalizeName(input: String): String {
            return input.trim().lowercase(Locale.US)
                .replace(Regex("[^a-z0-9_\\-]+"), "_")
                .trim('_')
                .take(40)
        }

        private fun classifyConnectError(t: Throwable): String {
            val cause = (t as? IllegalStateException)?.cause ?: t
            return when (cause) {
                is McpHttpStatusException -> when (cause.statusCode) {
                    401, 403 -> "auth rejected. Check token/ACL and retry."
                    404 -> "endpoint not found. Check MCP URL path and retry."
                    429 -> "rate limited. Wait a moment and retry."
                    in 500..599 -> "server unavailable (${cause.statusCode}). Retry later."
                    else -> "HTTP ${cause.statusCode}. ${cause.message.orEmpty()}".trim()
                }

                is McpRpcException -> "RPC error ${cause.rpcCode}: ${cause.rpcMessage}"
                is SocketTimeoutException -> "request timed out. Check network/VPN and retry."
                is IOException -> "network error. ${cause.message.orEmpty()}".trim()
                else -> cause.message ?: cause.javaClass.simpleName
            }
        }

        private const val TAG = "McpHttpRuntime"
    }
}

private data class McpToolDef(
    val name: String,
    val description: String,
    val inputSchema: JsonObject
)

private class McpToolWrapper(
    private val client: McpHttpClient,
    serverName: String,
    private val toolDef: McpToolDef,
    toolTimeoutSeconds: Int
) : Tool, TimedTool {
    private val json = Json { ignoreUnknownKeys = true }
    private val wrappedToolName = toolDef.name.lowercase(Locale.US)
        .replace(Regex("[^a-z0-9_\\-]+"), "_")
        .trim('_')
        .ifBlank { "tool" }

    override val name: String = "mcp_${serverName}_${wrappedToolName}"
    override val description: String = toolDef.description.ifBlank { toolDef.name }
    override val jsonSchema: JsonObject = toolDef.inputSchema
    override val timeoutMs: Long = toolTimeoutSeconds.coerceIn(5, 300) * 1000L

    override suspend fun run(argumentsJson: String): ToolResult {
        val argsObj = runCatching { json.parseToJsonElement(argumentsJson).jsonObject }
            .getOrElse {
                return ToolResult(
                    toolCallId = "",
                    content = "MCP tool '$name' failed: arguments must be a JSON object",
                    isError = true,
                    metadata = buildJsonObject {
                        put("mcp_server", client.serverName)
                        put("mcp_tool", toolDef.name)
                        put("status", "error")
                        put("error", "invalid_arguments")
                        put("recoverable", true)
                        put("next_step", "Pass a valid JSON object as arguments and retry.")
                    }
                )
            }

        return runCatching {
            val result = client.callTool(toolDef.name, argsObj)
            ToolResult(
                toolCallId = "",
                content = result.content,
                isError = result.isError,
                metadata = buildJsonObject {
                    put("mcp_server", client.serverName)
                    put("mcp_tool", toolDef.name)
                    put("mcp_is_error", result.isError)
                }
            )
        }.getOrElse { t ->
            val mapped = mapToolError(t)
            ToolResult(
                toolCallId = "",
                content = buildString {
                    append("MCP tool '$name' failed: ")
                    append(mapped.message)
                    if (mapped.nextStep.isNotBlank()) {
                        append(" Next: ")
                        append(mapped.nextStep)
                    }
                },
                isError = true,
                metadata = buildJsonObject {
                    put("mcp_server", client.serverName)
                    put("mcp_tool", toolDef.name)
                    put("status", "error")
                    put("error", mapped.code)
                    put("recoverable", mapped.recoverable)
                    if (mapped.nextStep.isNotBlank()) {
                        put("next_step", mapped.nextStep)
                    }
                }
            )
        }
    }

    private fun mapToolError(t: Throwable): ToolError {
        return when (t) {
            is McpHttpStatusException -> when (t.statusCode) {
                401, 403 -> ToolError(
                    code = "auth_rejected",
                    message = "auth rejected by MCP server.",
                    nextStep = "Check token/ACL, save settings, then retry.",
                    recoverable = true
                )

                404 -> ToolError(
                    code = "endpoint_not_found",
                    message = "MCP endpoint not found.",
                    nextStep = "Check MCP URL path and retry.",
                    recoverable = true
                )

                429 -> ToolError(
                    code = "rate_limited",
                    message = "rate limited by MCP server.",
                    nextStep = "Wait a moment and retry.",
                    recoverable = true
                )

                in 500..599 -> ToolError(
                    code = "server_unavailable",
                    message = "MCP server unavailable (HTTP ${t.statusCode}).",
                    nextStep = "Retry later.",
                    recoverable = true
                )

                else -> ToolError(
                    code = "http_error",
                    message = t.message ?: "HTTP ${t.statusCode}",
                    nextStep = "Check MCP endpoint and retry.",
                    recoverable = true
                )
            }

            is McpRpcException -> ToolError(
                code = "rpc_error",
                message = "RPC error ${t.rpcCode}: ${t.rpcMessage}",
                nextStep = "Check remote MCP server logs and retry.",
                recoverable = true
            )

            is SocketTimeoutException -> ToolError(
                code = "timeout",
                message = "request timed out.",
                nextStep = "Check network and retry.",
                recoverable = true
            )

            is IOException -> ToolError(
                code = "network_error",
                message = t.message ?: "network I/O error.",
                nextStep = "Check network/VPN and retry.",
                recoverable = true
            )

            else -> ToolError(
                code = "unknown_error",
                message = t.message ?: t.javaClass.simpleName,
                nextStep = "Retry; if it persists, reconnect MCP server.",
                recoverable = true
            )
        }
    }

    private data class ToolError(
        val code: String,
        val message: String,
        val nextStep: String,
        val recoverable: Boolean
    )
}

private class McpHttpClient(
    val serverName: String,
    serverUrl: String,
    private val authToken: String,
    requestTimeoutSeconds: Int
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val rpcId = AtomicLong(1L)
    private val initMutex = Mutex()
    private val timeoutSeconds = requestTimeoutSeconds.coerceIn(10, 360)
    private val client = OkHttpClient.Builder()
        .callTimeout(timeoutSeconds.toLong(), TimeUnit.SECONDS)
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(timeoutSeconds.toLong(), TimeUnit.SECONDS)
        .writeTimeout(timeoutSeconds.toLong(), TimeUnit.SECONDS)
        .build()
    private val serverUrl = serverUrl.trim().removeSuffix("/")

    @Volatile
    private var initialized = false

    @Volatile
    private var sessionId: String? = null

    init {
        require(
            this.serverUrl.startsWith("http://", ignoreCase = true) ||
                this.serverUrl.startsWith("https://", ignoreCase = true)
        ) {
            "MCP server URL must start with http:// or https://"
        }
        if (this.serverUrl.startsWith("http://", ignoreCase = true) &&
            !isLocalAddress(this.serverUrl)
        ) {
            throw IllegalArgumentException("Use HTTPS for non-local MCP endpoints.")
        }
    }

    suspend fun listTools(): List<McpToolDef> {
        ensureInitialized()
        val result = rpc("tools/list", buildJsonObject {})
        val tools = result.jsonObject["tools"]?.jsonArray.orEmpty()
        return tools.mapNotNull { item ->
            val obj = item as? JsonObject ?: return@mapNotNull null
            val name = obj["name"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            if (name.isBlank()) return@mapNotNull null
            val description = obj["description"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val inputSchema = (obj["inputSchema"] as? JsonObject) ?: buildJsonObject {
                put("type", "object")
                put("properties", buildJsonObject {})
            }
            McpToolDef(name = name, description = description, inputSchema = inputSchema)
        }
    }

    suspend fun callTool(toolName: String, arguments: JsonObject): ToolCallResult {
        ensureInitialized()
        val params = buildJsonObject {
            put("name", toolName)
            put("arguments", arguments)
        }
        val result = rpc("tools/call", params).jsonObject
        val isError = result["isError"]?.jsonPrimitive?.booleanOrNull ?: false
        val content = extractContent(result["content"])
        return ToolCallResult(
            content = content.ifBlank { "(no output)" },
            isError = isError
        )
    }

    fun close() {
        client.dispatcher.cancelAll()
        client.connectionPool.evictAll()
    }

    private suspend fun ensureInitialized() {
        if (initialized) return
        initMutex.withLock {
            if (initialized) return
            val initParams = buildJsonObject {
                put("protocolVersion", "2024-11-05")
                put("capabilities", buildJsonObject {})
                put(
                    "clientInfo",
                    buildJsonObject {
                        put("name", "palmclaw")
                        put("version", "1.0")
                    }
                )
            }
            rpc("initialize", initParams)
            runCatching { rpcNotification("notifications/initialized", buildJsonObject {}) }
            initialized = true
        }
    }

    private suspend fun rpc(method: String, params: JsonElement?): JsonElement = withContext(Dispatchers.IO) {
        var lastError: Throwable? = null
        for (attempt in 1..MAX_RPC_ATTEMPTS) {
            try {
                val requestBodyJson = buildJsonObject {
                    put("jsonrpc", "2.0")
                    put("id", rpcId.getAndIncrement())
                    put("method", method)
                    if (params != null) put("params", params)
                }
                val request = requestBuilder()
                    .post(json.encodeToString(requestBodyJson).toRequestBody(JSON_MEDIA_TYPE))
                    .build()
                return@withContext executeRpcRequest(request)
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                lastError = t
                if (!shouldRetryRpc(t, attempt)) throw t
                delay(retryDelayMs(attempt))
            }
        }
        throw lastError ?: IOException("MCP RPC failed")
    }

    private suspend fun rpcNotification(method: String, params: JsonElement?) = withContext(Dispatchers.IO) {
        val requestBodyJson = buildJsonObject {
            put("jsonrpc", "2.0")
            put("method", method)
            if (params != null) put("params", params)
        }
        val request = requestBuilder()
            .post(json.encodeToString(requestBodyJson).toRequestBody(JSON_MEDIA_TYPE))
            .build()
        client.newCall(request).execute().use { response ->
            sessionId = response.header(HEADER_SESSION_ID) ?: sessionId
            if (!response.isSuccessful) {
                throw McpHttpStatusException(response.code, "MCP notification HTTP ${response.code}")
            }
        }
    }

    private fun executeRpcRequest(request: Request): JsonElement {
        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            sessionId = response.header(HEADER_SESSION_ID) ?: sessionId
            if (!response.isSuccessful) {
                throw McpHttpStatusException(
                    statusCode = response.code,
                    message = "MCP HTTP ${response.code}: ${body.take(MAX_ERROR_CHARS)}"
                )
            }
            val root = runCatching { json.parseToJsonElement(body).jsonObject }
                .getOrElse { throw IOException("MCP invalid JSON response: ${it.message}") }
            val error = root["error"]?.jsonObject
            if (error != null) {
                val code = error["code"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val message = error["message"]?.jsonPrimitive?.contentOrNull.orEmpty()
                throw McpRpcException(code, message)
            }
            return root["result"] ?: buildJsonObject {}
        }
    }

    private fun shouldRetryRpc(t: Throwable, attempt: Int): Boolean {
        if (attempt >= MAX_RPC_ATTEMPTS) return false
        return when (t) {
            is McpHttpStatusException -> t.statusCode in RETRYABLE_HTTP_CODES
            is SocketTimeoutException -> true
            is IOException -> {
                val message = t.message.orEmpty().lowercase(Locale.US)
                message.contains("timeout") ||
                    message.contains("connection reset") ||
                    message.contains("failed to connect") ||
                    message.contains("unexpected end of stream")
            }

            else -> false
        }
    }

    private fun retryDelayMs(attempt: Int): Long {
        val idx = (attempt - 1).coerceAtLeast(0)
        return (RETRY_BASE_DELAY_MS * (1L shl idx)).coerceAtMost(MAX_RETRY_DELAY_MS)
    }

    private fun requestBuilder(): Request.Builder {
        val builder = Request.Builder()
            .url(serverUrl)
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
        if (authToken.isNotBlank()) {
            builder.header("Authorization", "Bearer $authToken")
        }
        val sid = sessionId
        if (!sid.isNullOrBlank()) {
            builder.header(HEADER_SESSION_ID, sid)
        }
        return builder
    }

    private fun extractContent(contentElement: JsonElement?): String {
        if (contentElement == null) return ""
        return when (contentElement) {
            is JsonPrimitive -> contentElement.contentOrNull ?: contentElement.toString()
            is JsonArray -> {
                contentElement.mapNotNull { item ->
                    val obj = item as? JsonObject ?: return@mapNotNull item.toString()
                    val type = obj["type"]?.jsonPrimitive?.contentOrNull.orEmpty()
                    when (type) {
                        "text" -> obj["text"]?.jsonPrimitive?.contentOrNull
                        else -> obj["text"]?.jsonPrimitive?.contentOrNull ?: obj.toString()
                    }
                }.joinToString("\n")
            }

            else -> contentElement.toString()
        }
    }

    data class ToolCallResult(
        val content: String,
        val isError: Boolean
    )

    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private const val HEADER_SESSION_ID = "Mcp-Session-Id"
        private const val MAX_ERROR_CHARS = 2000
        private const val MAX_RPC_ATTEMPTS = 3
        private const val RETRY_BASE_DELAY_MS = 300L
        private const val MAX_RETRY_DELAY_MS = 1200L
        private val RETRYABLE_HTTP_CODES = setOf(408, 425, 429, 500, 502, 503, 504)

        private fun isLocalAddress(url: String): Boolean {
            val host = runCatching { url.toHttpUrlOrNull()?.host }.getOrNull().orEmpty()
            if (host.equals("localhost", ignoreCase = true)) return true
            if (host == "127.0.0.1") return true
            if (host.startsWith("10.")) return true
            if (host.startsWith("192.168.")) return true
            if (host.startsWith("172.")) {
                val second = host.split(".").getOrNull(1)?.toIntOrNull()
                if (second != null && second in 16..31) return true
            }
            return false
        }
    }
}

private class McpHttpStatusException(
    val statusCode: Int,
    override val message: String
) : IOException(message)

private class McpRpcException(
    val rpcCode: String,
    val rpcMessage: String
) : IOException("MCP RPC error $rpcCode: $rpcMessage")
