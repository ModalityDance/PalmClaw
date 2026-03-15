package com.palmclaw.tools

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

class RuntimeGetTool(
    private var getCallback: (suspend () -> Snapshot)? = null
) : Tool {
    private val json = Json { prettyPrint = true }

    override val name: String = "runtime_get"

    override val description: String =
        "Get the current adjustable runtime settings for tool rounds, timeouts, memory window, context size, and tool preview limits."

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
            content = "runtime_get failed: runtime settings access is not configured",
            isError = true
        )
        return try {
            val snapshot = callback()
            ToolResult(
                toolCallId = "",
                content = json.encodeToString(JsonObject.serializer(), snapshot.toJson()),
                isError = false,
                metadata = buildJsonObject {
                    put("setting_count", 9)
                }
            )
        } catch (t: Throwable) {
            ToolResult(
                toolCallId = "",
                content = "runtime_get failed: ${t.message ?: t.javaClass.simpleName}",
                isError = true
            )
        }
    }

    data class Snapshot(
        val maxToolRounds: Int,
        val toolResultMaxChars: Int,
        val memoryConsolidationWindow: Int,
        val llmCallTimeoutSeconds: Int,
        val llmConnectTimeoutSeconds: Int,
        val llmReadTimeoutSeconds: Int,
        val defaultToolTimeoutSeconds: Int,
        val contextMessages: Int,
        val toolArgsPreviewMaxChars: Int
    ) {
        fun toJson(): JsonObject = buildJsonObject {
            put("max_tool_rounds", maxToolRounds)
            put("tool_result_max_chars", toolResultMaxChars)
            put("memory_consolidation_window", memoryConsolidationWindow)
            put("llm_call_timeout_seconds", llmCallTimeoutSeconds)
            put("llm_connect_timeout_seconds", llmConnectTimeoutSeconds)
            put("llm_read_timeout_seconds", llmReadTimeoutSeconds)
            put("default_tool_timeout_seconds", defaultToolTimeoutSeconds)
            put("context_messages", contextMessages)
            put("tool_args_preview_max_chars", toolArgsPreviewMaxChars)
        }
    }
}

class RuntimeSetTool(
    private var setCallback: (suspend (Request) -> RuntimeGetTool.Snapshot)? = null
) : Tool {
    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
    }

    override val name: String = "runtime_set"

    override val description: String =
        "Persistently update adjustable runtime settings such as tool rounds, timeouts, memory window, context size, and tool preview limits."

    override val jsonSchema: JsonObject = buildJsonObject {
        put("type", "object")
        put("additionalProperties", false)
        put(
            "properties",
            Json.parseToJsonElement(
                """
                {
                  "max_tool_rounds": {"type":"integer"},
                  "tool_result_max_chars": {"type":"integer"},
                  "memory_consolidation_window": {"type":"integer"},
                  "llm_call_timeout_seconds": {"type":"integer"},
                  "llm_connect_timeout_seconds": {"type":"integer"},
                  "llm_read_timeout_seconds": {"type":"integer"},
                  "default_tool_timeout_seconds": {"type":"integer"},
                  "context_messages": {"type":"integer"},
                  "tool_args_preview_max_chars": {"type":"integer"}
                }
                """.trimIndent()
            )
        )
    }

    fun setSetCallback(callback: suspend (Request) -> RuntimeGetTool.Snapshot) {
        setCallback = callback
    }

    fun clearSetCallback() {
        setCallback = null
    }

    override suspend fun run(argumentsJson: String): ToolResult {
        val callback = setCallback ?: return ToolResult(
            toolCallId = "",
            content = "runtime_set failed: runtime settings update is not configured",
            isError = true
        )
        val request = try {
            parseArgs(argumentsJson)
        } catch (t: Throwable) {
            return ToolResult(
                toolCallId = "",
                content = "runtime_set failed: invalid arguments JSON (${t.message})",
                isError = true
            )
        }
        if (!request.hasAnyChange()) {
            return ToolResult(
                toolCallId = "",
                content = "runtime_set failed: at least one runtime setting is required",
                isError = true
            )
        }
        return try {
            val snapshot = callback(request)
            ToolResult(
                toolCallId = "",
                content = buildString {
                    append("Runtime settings updated.\n")
                    append(json.encodeToString(JsonObject.serializer(), snapshot.toJson()))
                },
                isError = false,
                metadata = buildJsonObject {
                    put("updated", true)
                }
            )
        } catch (t: Throwable) {
            ToolResult(
                toolCallId = "",
                content = "runtime_set failed: ${t.message ?: t.javaClass.simpleName}",
                isError = true
            )
        }
    }

    data class Request(
        val maxToolRounds: Int? = null,
        val toolResultMaxChars: Int? = null,
        val memoryConsolidationWindow: Int? = null,
        val llmCallTimeoutSeconds: Int? = null,
        val llmConnectTimeoutSeconds: Int? = null,
        val llmReadTimeoutSeconds: Int? = null,
        val defaultToolTimeoutSeconds: Int? = null,
        val contextMessages: Int? = null,
        val toolArgsPreviewMaxChars: Int? = null
    ) {
        fun hasAnyChange(): Boolean {
            return maxToolRounds != null ||
                toolResultMaxChars != null ||
                memoryConsolidationWindow != null ||
                llmCallTimeoutSeconds != null ||
                llmConnectTimeoutSeconds != null ||
                llmReadTimeoutSeconds != null ||
                defaultToolTimeoutSeconds != null ||
                contextMessages != null ||
                toolArgsPreviewMaxChars != null
        }
    }

    private fun parseArgs(raw: String): Request {
        val obj = json.parseToJsonElement(raw) as? JsonObject
            ?: throw IllegalArgumentException("JSON object expected")
        return Request(
            maxToolRounds = obj.int("max_tool_rounds"),
            toolResultMaxChars = obj.int("tool_result_max_chars"),
            memoryConsolidationWindow = obj.int("memory_consolidation_window"),
            llmCallTimeoutSeconds = obj.int("llm_call_timeout_seconds"),
            llmConnectTimeoutSeconds = obj.int("llm_connect_timeout_seconds"),
            llmReadTimeoutSeconds = obj.int("llm_read_timeout_seconds"),
            defaultToolTimeoutSeconds = obj.int("default_tool_timeout_seconds"),
            contextMessages = obj.int("context_messages"),
            toolArgsPreviewMaxChars = obj.int("tool_args_preview_max_chars")
        )
    }

    private fun JsonObject.int(key: String): Int? {
        return (this[key] as? JsonPrimitive)?.contentOrNull?.toIntOrNull()
    }

    @Suppress("unused")
    private fun JsonObject.boolean(key: String): Boolean? {
        return (this[key] as? JsonPrimitive)?.booleanOrNull
    }
}
