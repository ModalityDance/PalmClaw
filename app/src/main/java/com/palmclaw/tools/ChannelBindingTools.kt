package com.palmclaw.tools

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

class ChannelsGetTool(
    private var getCallback: (suspend () -> Snapshot)? = null
) : Tool {
    private val json = Json { prettyPrint = true }

    override val name: String = "session_status"

    override val description: String =
        "Get status for sessions, including channel binding state and route information. Use session_id when you want one exact session."

    override val jsonSchema: JsonObject = buildJsonObject {
        put("type", "object")
        put("additionalProperties", false)
        put(
            "properties",
            Json.parseToJsonElement(
                """
                {
                  "session_id": {"type":"string","description":"Exact local session id; canonical unique identifier"},
                  "session_title": {"type":"string","description":"Local session title; only use when it uniquely identifies one session"}
                }
                """.trimIndent()
            )
        )
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
            content = "session_status failed: session status access is not configured",
            isError = true
        )
        return try {
            val selector = parseSelector(argumentsJson)
            val snapshot = callback()
            val filtered = selector.filter(snapshot)
            ToolResult(
                toolCallId = "",
                content = json.encodeToString(JsonObject.serializer(), filtered.toJson()),
                isError = false,
                metadata = buildJsonObject {
                    put("session_count", filtered.sessions.size)
                    put("gateway_enabled", filtered.gatewayEnabled)
                }
            )
        } catch (t: Throwable) {
            ToolResult(
                toolCallId = "",
                content = "session_status failed: ${t.message ?: t.javaClass.simpleName}",
                isError = true
            )
        }
    }

    private fun parseSelector(raw: String): Selector {
        if (raw.isBlank()) return Selector(null, null)
        val obj = json.parseToJsonElement(raw) as? JsonObject
            ?: throw IllegalArgumentException("JSON object expected")
        return Selector(
            sessionId = obj.string("session_id"),
            sessionTitle = obj.string("session_title")
        )
    }

    data class Snapshot(
        val gatewayEnabled: Boolean,
        val sessions: List<Entry>
    ) {
        fun toJson(): JsonObject = buildJsonObject {
            put("gateway_enabled", gatewayEnabled)
            put(
                "sessions",
                buildJsonArray {
                    sessions.forEach { add(it.toJson()) }
                }
            )
        }
    }

    data class Entry(
        val sessionId: String,
        val title: String,
        val bindingEnabled: Boolean,
        val channel: String,
        val target: String,
        val status: String
    ) {
        fun toJson(): JsonObject = buildJsonObject {
            put("session_id", sessionId)
            put("title", title)
            put("binding_enabled", bindingEnabled)
            put("channel", channel)
            put("target", target)
            put("status", status)
        }
    }

    private data class Selector(
        val sessionId: String?,
        val sessionTitle: String?
    ) {
        fun filter(snapshot: Snapshot): Snapshot {
            val id = sessionId?.trim().orEmpty()
            if (id.isNotBlank()) {
                val match = snapshot.sessions.filter { it.sessionId.equals(id, ignoreCase = true) }
                if (match.isEmpty()) throw IllegalArgumentException("session_id not found")
                return snapshot.copy(sessions = match)
            }
            val title = sessionTitle?.trim().orEmpty()
            if (title.isBlank()) return snapshot
            val exact = snapshot.sessions.filter { it.title.equals(title, ignoreCase = true) }
            if (exact.size > 1) throw IllegalArgumentException("session_title matches multiple sessions; use session_id")
            if (exact.size == 1) return snapshot.copy(sessions = exact)
            val partial = snapshot.sessions.filter { it.title.contains(title, ignoreCase = true) }
            return when {
                partial.isEmpty() -> throw IllegalArgumentException("session_title not found")
                partial.size > 1 -> throw IllegalArgumentException("session_title is ambiguous; use session_id")
                else -> snapshot.copy(sessions = partial)
            }
        }
    }

    private fun JsonObject.string(key: String): String? {
        return (this[key] as? JsonPrimitive)?.contentOrNull
    }
}

class ChannelsSetTool(
    private var setCallback: (suspend (Request) -> Result)? = null
) : Tool {
    private val json = Json { ignoreUnknownKeys = true }

    override val name: String = "session_set"

    override val description: String =
        "Update a session's binding switch. Prefer session_id because it is the unique local session identifier."

    override val jsonSchema: JsonObject = buildJsonObject {
        put("type", "object")
        put("additionalProperties", false)
        put("required", Json.parseToJsonElement("[\"enabled\"]"))
        put(
            "properties",
            Json.parseToJsonElement(
                """
                {
                  "session_id": {"type":"string","description":"Exact local session id; canonical unique identifier"},
                  "session_title": {"type":"string","description":"Local session title; only use when it uniquely identifies one session"},
                  "enabled": {"type":"boolean","description":"Whether this session's channel binding should be enabled"}
                }
                """.trimIndent()
            )
        )
    }

    fun setSetCallback(callback: suspend (Request) -> Result) {
        setCallback = callback
    }

    fun clearSetCallback() {
        setCallback = null
    }

    override suspend fun run(argumentsJson: String): ToolResult {
        val callback = setCallback ?: return ToolResult(
            toolCallId = "",
            content = "session_set failed: session update is not configured",
            isError = true
        )
        val request = try {
            parseArgs(argumentsJson)
        } catch (t: Throwable) {
            return ToolResult(
                toolCallId = "",
                content = "session_set failed: invalid arguments JSON (${t.message})",
                isError = true
            )
        }
        if (!request.hasSessionSelector()) {
            return ToolResult(
                toolCallId = "",
                content = "session_set failed: session_id or session_title is required",
                isError = true
            )
        }
        return try {
            val result = callback(request)
            ToolResult(
                toolCallId = "",
                content = "Channel binding for session ${result.sessionId} (${result.sessionTitle}) is now ${if (result.enabled) "enabled" else "disabled"}. Status: ${result.status}.",
                isError = false,
                metadata = buildJsonObject {
                    put("session_id", result.sessionId)
                    put("session_title", result.sessionTitle)
                    put("enabled", result.enabled)
                    put("status", result.status)
                }
            )
        } catch (t: Throwable) {
            ToolResult(
                toolCallId = "",
                content = "session_set failed: ${t.message ?: t.javaClass.simpleName}",
                isError = true
            )
        }
    }

    data class Request(
        val sessionId: String?,
        val sessionTitle: String?,
        val enabled: Boolean
    ) {
        fun hasSessionSelector(): Boolean {
            return !sessionId.isNullOrBlank() || !sessionTitle.isNullOrBlank()
        }
    }

    data class Result(
        val sessionId: String,
        val sessionTitle: String,
        val enabled: Boolean,
        val status: String
    )

    private fun parseArgs(raw: String): Request {
        val obj = json.parseToJsonElement(raw) as? JsonObject
            ?: throw IllegalArgumentException("JSON object expected")
        val enabled = obj.boolean("enabled")
            ?: throw IllegalArgumentException("enabled must be a boolean")
        return Request(
            sessionId = obj.string("session_id"),
            sessionTitle = obj.string("session_title"),
            enabled = enabled
        )
    }

    private fun JsonObject.string(key: String): String? {
        return (this[key] as? JsonPrimitive)?.contentOrNull
    }

    private fun JsonObject.boolean(key: String): Boolean? {
        return (this[key] as? JsonPrimitive)?.booleanOrNull
    }
}
