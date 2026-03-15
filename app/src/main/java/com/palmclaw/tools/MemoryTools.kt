package com.palmclaw.tools

import com.palmclaw.config.AppSession
import com.palmclaw.memory.MemoryStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.Locale

fun createMemoryToolSet(
    memoryStore: MemoryStore,
    currentSessionIdProvider: () -> String
): List<Tool> {
    val engine = MemoryOpsTool(memoryStore, currentSessionIdProvider)
    return listOf(
        MemoryActionTool(
            name = "memory_get",
            description = "Read global long-term memory.",
            action = "read",
            schema = buildJsonObject {
                put("type", "object")
                put("additionalProperties", false)
                put("properties", Json.parseToJsonElement("{}"))
            },
            engine = engine
        ),
        MemoryActionTool(
            name = "memory_set",
            description = "Write global long-term memory. mode=replace|append.",
            action = "write",
            schema = buildJsonObject {
                put("type", "object")
                put("additionalProperties", false)
                put("required", Json.parseToJsonElement("[\"text\"]"))
                put(
                    "properties",
                    Json.parseToJsonElement(
                        """
                        {
                          "text":{"type":"string"},
                          "mode":{"type":"string","enum":["replace","append"]}
                        }
                        """.trimIndent()
                    )
                )
            },
            engine = engine
        ),
        MemoryActionTool(
            name = "memory_history",
            description = "Read recent session history lines. If session_id is omitted, the current session is used.",
            action = "read_history",
            schema = buildJsonObject {
                put("type", "object")
                put("additionalProperties", false)
                put(
                    "properties",
                    Json.parseToJsonElement(
                        """
                        {
                          "session_id":{"type":"string"},
                          "max_lines":{"type":"integer","minimum":1},
                          "maxLines":{"type":"integer","minimum":1}
                        }
                        """.trimIndent()
                    )
                )
            },
            engine = engine
        ),
        MemoryActionTool(
            name = "memory_search",
            description = "Search session history with substring or regex. If session_id is omitted, the current session is used.",
            action = "search_history",
            schema = buildJsonObject {
                put("type", "object")
                put("additionalProperties", false)
                put("required", Json.parseToJsonElement("[\"query\"]"))
                put(
                    "properties",
                    Json.parseToJsonElement(
                        """
                        {
                          "session_id":{"type":"string"},
                          "query":{"type":"string"},
                          "ignore_case":{"type":"boolean"},
                          "ignoreCase":{"type":"boolean"},
                          "regex":{"type":"boolean"},
                          "limit":{"type":"integer","minimum":1}
                        }
                        """.trimIndent()
                    )
                )
            },
            engine = engine
        )
    )
}

private class MemoryOpsTool(
    private val memoryStore: MemoryStore,
    private val currentSessionIdProvider: () -> String
) : Tool {
    override val name: String = "__memory_engine"
    override val description: String =
        "Unified memory tool. action=read|write|read_history|search_history."
    override val jsonSchema: JsonObject = buildJsonObject {
        put("type", "object")
        put("additionalProperties", false)
        put("required", Json.parseToJsonElement("[\"action\"]"))
        put(
            "properties",
            Json.parseToJsonElement(
                """
                {
                  "action":{"type":"string","enum":["read","write","read_history","search_history"]},
                  "text":{"type":"string"},
                  "mode":{"type":"string","enum":["replace","append"]},
                  "session_id":{"type":"string"},
                  "max_lines":{"type":"integer","minimum":1},
                  "maxLines":{"type":"integer","minimum":1},
                  "query":{"type":"string"},
                  "ignore_case":{"type":"boolean"},
                  "ignoreCase":{"type":"boolean"},
                  "regex":{"type":"boolean"},
                  "limit":{"type":"integer","minimum":1}
                }
                """.trimIndent()
            )
        )
    }

    override suspend fun run(argumentsJson: String): ToolResult = withContext(Dispatchers.IO) {
        val args = Json.decodeFromString<Args>(argumentsJson)
        val action = args.action?.trim().orEmpty().lowercase(Locale.US)
        return@withContext dispatch(action, args)
    }

    suspend fun runWithAction(action: String, argumentsJson: String): ToolResult = withContext(Dispatchers.IO) {
        val args = Json.decodeFromString<Args>(argumentsJson)
        return@withContext dispatch(action.trim().lowercase(Locale.US), args)
    }

    private fun dispatch(action: String, args: Args): ToolResult {
        val rawAction = args.action?.takeIf { it.isNotBlank() } ?: action
        return when (action) {
            "read" -> actionRead()
            "write" -> actionWrite(args)
            "read_history" -> actionReadHistory(args)
            "search_history" -> actionSearchHistory(args)
            else -> errorResult(
                action = rawAction,
                code = "unsupported_action",
                message = "Unsupported action '$rawAction'.",
                nextStep = "Use action=read|write|read_history|search_history."
            )
        }
    }

    private fun actionRead(): ToolResult {
        val content = memoryStore.readLongTerm()
        val output = content.ifBlank { "(empty)" }
        return okResult(
            action = "read",
            message = output
        ) {
            put("chars", content.length)
            put("empty", content.isBlank())
        }
    }

    private fun actionWrite(args: Args): ToolResult {
        val text = args.text?.trim().orEmpty()
        if (text.isBlank()) {
            return errorResult(
                action = "write",
                code = "empty_text",
                message = "text is empty.",
                nextStep = "Provide non-empty text."
            )
        }
        val mode = (args.mode ?: "replace").trim().lowercase(Locale.US)
        if (mode != "replace" && mode != "append") {
            return errorResult(
                action = "write",
                code = "invalid_mode",
                message = "mode must be replace or append.",
                nextStep = "Set mode=replace or mode=append."
            )
        }

        val updated = if (mode == "append") {
            val existing = memoryStore.readLongTerm().trimEnd()
            if (existing.isBlank()) text else "$existing\n$text"
        } else {
            text
        }
        if (updated.length > MAX_MEMORY_CHARS) {
            return errorResult(
                action = "write",
                code = "content_too_large",
                message = "content too large (max=$MAX_MEMORY_CHARS chars).",
                nextStep = "Trim text or use shorter updates."
            )
        }

        runCatching { memoryStore.writeLongTerm(updated) }
            .onFailure { t ->
                return errorResult(
                    action = "write",
                    code = "write_failed",
                    message = t.message ?: t.javaClass.simpleName,
                    nextStep = "Retry. If this persists, check storage availability."
                )
            }

        return okResult(
            action = "write",
            message = "write ok (mode=$mode, chars=${updated.length})"
        ) {
            put("mode", mode)
            put("chars", updated.length)
        }
    }

    private fun actionReadHistory(args: Args): ToolResult {
        val maxLines = (args.maxLines ?: args.maxLinesCamel ?: DEFAULT_HISTORY_READ_LINES)
            .coerceIn(1, MAX_HISTORY_READ_LINES)
        val sessionId = resolveSessionId(args.sessionId)
        val text = runCatching { memoryStore.readHistory(sessionId = sessionId, maxLines = maxLines) }
            .getOrElse { t ->
                return errorResult(
                    action = "read_history",
                    code = "read_failed",
                    message = t.message ?: t.javaClass.simpleName,
                    nextStep = "Retry. If this persists, check storage availability."
                )
            }
        return okResult(
            action = "read_history",
            message = if (text.isBlank()) "History is empty" else text
        ) {
            put("session_id", sessionId)
            put("max_lines", maxLines)
            put("lines_returned", if (text.isBlank()) 0 else text.lines().size)
            put("empty", text.isBlank())
        }
    }

    private fun actionSearchHistory(args: Args): ToolResult {
        val query = args.query?.trim().orEmpty()
        if (query.isBlank()) {
            return errorResult(
                action = "search_history",
                code = "empty_query",
                message = "query is empty.",
                nextStep = "Provide query text."
            )
        }
        val limit = (args.limit ?: DEFAULT_HISTORY_SEARCH_LIMIT).coerceIn(1, MAX_HISTORY_SEARCH_LIMIT)
        val ignoreCase = args.ignoreCase ?: args.ignoreCaseCamel ?: true
        val regex = args.regex ?: false
        val sessionId = resolveSessionId(args.sessionId)
        val results = runCatching {
            memoryStore.searchHistory(
                sessionId = sessionId,
                query = query,
                ignoreCase = ignoreCase,
                regex = regex,
                limit = limit
            )
        }.getOrElse { t ->
            val code = if (regex) "invalid_regex" else "search_failed"
            val next = if (regex) "Fix regex syntax and retry." else "Retry with adjusted query."
            return errorResult(
                action = "search_history",
                code = code,
                message = t.message ?: t.javaClass.simpleName,
                nextStep = next
            )
        }

        return okResult(
            action = "search_history",
            message = if (results.isEmpty()) "(no matches)" else results.joinToString("\n")
        ) {
            put("session_id", sessionId)
            put("count", results.size)
            put("limit", limit)
            put("query", query)
            put("regex", regex)
            put("ignore_case", ignoreCase)
        }
    }

    private fun okResult(
        action: String,
        message: String,
        extra: (JsonObjectBuilder.() -> Unit)? = null
    ): ToolResult {
        return ToolResult(
            toolCallId = "",
            content = message,
            isError = false,
            metadata = buildJsonObject {
                put("tool", name)
                put("action", action)
                put("status", "ok")
                extra?.invoke(this)
            }
        )
    }

    private fun resolveSessionId(raw: String?): String {
        val explicit = raw?.trim().orEmpty()
        if (explicit.isNotBlank()) return explicit
        return currentSessionIdProvider().trim().ifBlank { AppSession.LOCAL_SESSION_ID }
    }

    private fun errorResult(
        action: String,
        code: String,
        message: String,
        nextStep: String? = null
    ): ToolResult {
        val text = buildString {
            append("$name/$action failed: $message")
            if (!nextStep.isNullOrBlank()) {
                append(" Next: ")
                append(nextStep)
            }
        }
        return ToolResult(
            toolCallId = "",
            content = text,
            isError = true,
            metadata = buildJsonObject {
                put("tool", name)
                put("action", action)
                put("status", "error")
                put("error", code)
                put("recoverable", !nextStep.isNullOrBlank())
                if (!nextStep.isNullOrBlank()) {
                    put("next_step", nextStep)
                }
            }
        )
    }

    @Serializable
    private data class Args(
        val action: String? = null,
        val text: String? = null,
        val mode: String? = null,
        @SerialName("session_id")
        val sessionId: String? = null,
        @SerialName("max_lines")
        val maxLines: Int? = null,
        @SerialName("maxLines")
        val maxLinesCamel: Int? = null,
        val query: String? = null,
        @SerialName("ignore_case")
        val ignoreCase: Boolean? = null,
        @SerialName("ignoreCase")
        val ignoreCaseCamel: Boolean? = null,
        val regex: Boolean? = null,
        val limit: Int? = null
    )
}

private class MemoryActionTool(
    override val name: String,
    override val description: String,
    private val action: String,
    private val schema: JsonObject,
    private val engine: MemoryOpsTool
) : Tool {
    override val jsonSchema: JsonObject = schema
    override suspend fun run(argumentsJson: String): ToolResult {
        return engine.runWithAction(action, argumentsJson)
    }
}

private const val DEFAULT_HISTORY_READ_LINES = 200
private const val MAX_HISTORY_READ_LINES = 2000
private const val DEFAULT_HISTORY_SEARCH_LIMIT = 50
private const val MAX_HISTORY_SEARCH_LIMIT = 500
private const val MAX_MEMORY_CHARS = 120_000
