package com.palmclaw.tools

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

class HeartbeatGetTool(
    private var getCallback: (suspend () -> Snapshot)? = null
) : Tool {
    private val json = Json { prettyPrint = true }

    override val name: String = "heartbeat_get"

    override val description: String =
        "Get persisted heartbeat settings, including enabled state, interval, and HEARTBEAT.md content."

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
            content = "heartbeat_get failed: heartbeat settings access is not configured",
            isError = true
        )
        return try {
            val snapshot = callback()
            ToolResult(
                toolCallId = "",
                content = json.encodeToString(JsonObject.serializer(), snapshot.toJson()),
                isError = false
            )
        } catch (t: Throwable) {
            ToolResult(
                toolCallId = "",
                content = "heartbeat_get failed: ${t.message ?: t.javaClass.simpleName}",
                isError = true
            )
        }
    }

    data class Snapshot(
        val enabled: Boolean,
        val intervalSeconds: Long,
        val documentContent: String
    ) {
        fun toJson(): JsonObject = buildJsonObject {
            put("enabled", enabled)
            put("interval_seconds", intervalSeconds)
            put("document_content", documentContent)
        }
    }
}

class HeartbeatSetTool(
    private var setCallback: (suspend (Request) -> HeartbeatGetTool.Snapshot)? = null
) : Tool {
    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
    }

    override val name: String = "heartbeat_set"

    override val description: String =
        "Persist heartbeat settings. You can update enabled, interval_seconds, and replace HEARTBEAT.md content."

    override val jsonSchema: JsonObject = buildJsonObject {
        put("type", "object")
        put("additionalProperties", false)
        put(
            "properties",
            Json.parseToJsonElement(
                """
                {
                  "enabled": {"type":"boolean"},
                  "interval_seconds": {"type":"integer"},
                  "document_content": {"type":"string"}
                }
                """.trimIndent()
            )
        )
    }

    fun setSetCallback(callback: suspend (Request) -> HeartbeatGetTool.Snapshot) {
        setCallback = callback
    }

    fun clearSetCallback() {
        setCallback = null
    }

    override suspend fun run(argumentsJson: String): ToolResult {
        val callback = setCallback ?: return ToolResult(
            toolCallId = "",
            content = "heartbeat_set failed: heartbeat settings update is not configured",
            isError = true
        )
        val request = try {
            parseArgs(argumentsJson)
        } catch (t: Throwable) {
            return ToolResult(
                toolCallId = "",
                content = "heartbeat_set failed: invalid arguments JSON (${t.message})",
                isError = true
            )
        }
        if (!request.hasAnyChange()) {
            return ToolResult(
                toolCallId = "",
                content = "heartbeat_set failed: at least one heartbeat setting is required",
                isError = true
            )
        }
        return try {
            val snapshot = callback(request)
            ToolResult(
                toolCallId = "",
                content = buildString {
                    append("Heartbeat settings updated.\n")
                    append(json.encodeToString(JsonObject.serializer(), snapshot.toJson()))
                },
                isError = false
            )
        } catch (t: Throwable) {
            ToolResult(
                toolCallId = "",
                content = "heartbeat_set failed: ${t.message ?: t.javaClass.simpleName}",
                isError = true
            )
        }
    }

    data class Request(
        val enabled: Boolean? = null,
        val intervalSeconds: Long? = null,
        val documentContent: String? = null
    ) {
        fun hasAnyChange(): Boolean {
            return enabled != null ||
                intervalSeconds != null ||
                documentContent != null
        }
    }

    private fun parseArgs(raw: String): Request {
        val obj = json.parseToJsonElement(raw) as? JsonObject
            ?: throw IllegalArgumentException("JSON object expected")
        return Request(
            enabled = obj.boolean("enabled"),
            intervalSeconds = obj.long("interval_seconds"),
            documentContent = obj.string("document_content")
        )
    }

    private fun JsonObject.string(key: String): String? {
        return (this[key] as? JsonPrimitive)?.contentOrNull
    }

    private fun JsonObject.long(key: String): Long? {
        return (this[key] as? JsonPrimitive)?.contentOrNull?.toLongOrNull()
    }

    private fun JsonObject.boolean(key: String): Boolean? {
        return ((this[key] as? JsonPrimitive)?.contentOrNull)?.toBooleanStrictOrNull()
    }
}

class HeartbeatTriggerTool(
    private var triggerCallback: (suspend () -> String)? = null
) : Tool {
    override val name: String = "heartbeat_trigger"

    override val description: String =
        "Trigger the heartbeat worker immediately if heartbeat is enabled."

    override val jsonSchema: JsonObject = buildJsonObject {
        put("type", "object")
        put("additionalProperties", false)
        put("properties", buildJsonObject {})
    }

    fun setTriggerCallback(callback: suspend () -> String) {
        triggerCallback = callback
    }

    fun clearTriggerCallback() {
        triggerCallback = null
    }

    override suspend fun run(argumentsJson: String): ToolResult {
        val callback = triggerCallback ?: return ToolResult(
            toolCallId = "",
            content = "heartbeat_trigger failed: heartbeat trigger is not configured",
            isError = true
        )
        return try {
            ToolResult(
                toolCallId = "",
                content = callback(),
                isError = false
            )
        } catch (t: Throwable) {
            ToolResult(
                toolCallId = "",
                content = "heartbeat_trigger failed: ${t.message ?: t.javaClass.simpleName}",
                isError = true
            )
        }
    }
}
