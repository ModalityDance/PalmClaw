package com.palmclaw.agent

import com.palmclaw.providers.LlmResponse
import com.palmclaw.providers.ToolCall
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

class ToolCallParser {
    private val json = Json { ignoreUnknownKeys = true }

    fun parse(response: LlmResponse): List<ToolCall> {
        if (response.assistant.toolCalls.isNotEmpty()) {
            return response.assistant.toolCalls
        }

        val content = response.assistant.content.trim()
        if (content.isBlank()) return emptyList()

        val cleaned = content
            .removePrefix("```json")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()

        return runCatching {
            val root = json.parseToJsonElement(cleaned)
            extractToolCalls(root)
        }.getOrElse { emptyList() }
    }

    private fun extractToolCalls(root: JsonElement): List<ToolCall> {
        return when (root) {
            is JsonObject -> {
                val arr = root["tool_calls"] as? JsonArray ?: return emptyList()
                parseArray(arr)
            }

            is JsonArray -> parseArray(root)
            else -> emptyList()
        }
    }

    private fun parseArray(array: JsonArray): List<ToolCall> {
        return array.mapNotNull { item ->
            val obj = item as? JsonObject ?: return@mapNotNull null
            val id = (obj["id"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null

            val functionObj = obj["function"] as? JsonObject
            val name = functionObj?.get("name")?.jsonPrimitive?.contentOrNull
                ?: (obj["name"] as? JsonPrimitive)?.contentOrNull
                ?: return@mapNotNull null

            val argsJson = functionObj?.get("arguments")?.jsonPrimitive?.contentOrNull
                ?: obj["arguments"]?.toString()
                ?: "{}"

            ToolCall(id = id, name = name, argumentsJson = argsJson)
        }
    }
}

