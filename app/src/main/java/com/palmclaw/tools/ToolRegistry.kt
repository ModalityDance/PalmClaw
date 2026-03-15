package com.palmclaw.tools

import android.util.Log
import com.palmclaw.providers.ToolCall
import com.palmclaw.providers.ToolSpec
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.util.concurrent.ConcurrentHashMap

class ToolRegistry(
    initialTools: Map<String, Tool>,
    private val timeoutMsProvider: () -> Long = { 60_000L }
) {
    private val tools = ConcurrentHashMap(initialTools)
    private val json = Json { ignoreUnknownKeys = true }
    private val errorHint = "\n\n[Analyze the error above and try a different approach.]"

    fun toToolSpecList(): List<ToolSpec> {
        return tools.values.map {
            ToolSpec(name = it.name, description = it.description, parameters = it.jsonSchema)
        }
    }

    fun register(tool: Tool) {
        tools[tool.name] = tool
    }

    fun unregister(name: String) {
        tools.remove(name)
    }

    fun registerAll(list: List<Tool>) {
        list.forEach { register(it) }
    }

    fun unregisterByPrefix(prefix: String): Int {
        val keys = tools.keys.filter { it.startsWith(prefix) }
        keys.forEach { tools.remove(it) }
        return keys.size
    }

    fun get(name: String): Tool? = tools[name]

    fun has(name: String): Boolean = tools.containsKey(name)

    fun toolNames(): List<String> = tools.keys().toList().sorted()

    val size: Int
        get() = tools.size

    operator fun contains(name: String): Boolean = has(name)

    suspend fun execute(call: ToolCall): ToolResult {
        val defaultTimeoutMs = timeoutMsProvider().coerceAtLeast(1_000L)
        var effectiveTimeoutMs = defaultTimeoutMs
        val tool = tools[call.name]
        if (tool == null) {
            return ToolResult(
                toolCallId = call.id,
                content = buildString {
                    append("Tool not found: ${call.name}.")
                    val available = toolNames()
                    if (available.isNotEmpty()) {
                        append(" Available: ")
                        append(available.joinToString(", "))
                    }
                    append(errorHint)
                },
                isError = true,
                metadata = buildJsonObject { put("error", "not_found") }
            )
        }

        return try {
            Log.d(TAG, "Executing tool ${tool.name}, callId=${call.id}")
            val parsedArgs = parseArgumentsObject(call.argumentsJson)
            if (parsedArgs == null) {
                return ToolResult(
                    toolCallId = call.id,
                    content = "Invalid arguments for ${call.name}: JSON object expected$errorHint",
                    isError = true,
                    metadata = buildJsonObject { put("error", "invalid_arguments") }
                )
            }

            val validationErrors = validateArgs(tool.jsonSchema, parsedArgs)
            if (validationErrors.isNotEmpty()) {
                return ToolResult(
                    toolCallId = call.id,
                    content = "Invalid parameters for ${call.name}: ${validationErrors.joinToString("; ")}$errorHint",
                    isError = true,
                    metadata = buildJsonObject {
                        put("error", "invalid_parameters")
                        put("error_count", validationErrors.size)
                    }
                )
            }

            effectiveTimeoutMs = (tool as? TimedTool)?.timeoutMs?.takeIf { it > 0 } ?: defaultTimeoutMs
            // Use normalized JSON object string to avoid provider quirks where arguments is a JSON string.
            val raw = withTimeout(effectiveTimeoutMs) { tool.run(parsedArgs.toString()) }
            if (raw.isError && !raw.content.contains(errorHint)) {
                raw.copy(toolCallId = call.id, content = raw.content + errorHint)
            } else {
                raw.copy(toolCallId = call.id)
            }
        } catch (_: TimeoutCancellationException) {
            ToolResult(
                toolCallId = call.id,
                content = "Tool execution timed out for ${call.name} after ${effectiveTimeoutMs}ms$errorHint",
                isError = true,
                metadata = buildJsonObject {
                    put("error", "timeout")
                    put("timeout_ms", effectiveTimeoutMs)
                }
            )
        } catch (t: Throwable) {
            ToolResult(
                toolCallId = call.id,
                content = "Tool execution failed for ${call.name}: ${t.message}$errorHint",
                isError = true,
                metadata = buildJsonObject {
                    put("error", t.javaClass.simpleName)
                }
            )
        }
    }

    private fun parseArgumentsObject(raw: String): JsonObject? {
        val first = runCatching { json.parseToJsonElement(raw) }.getOrNull() ?: return null
        if (first is JsonObject) return first
        if (first is JsonPrimitive && first.isString) {
            val second = runCatching { json.parseToJsonElement(first.content) }.getOrNull()
            if (second is JsonObject) return second
        }
        return null
    }

    private fun validateArgs(schema: JsonObject, args: JsonObject): List<String> {
        val errors = mutableListOf<String>()
        validateObjectAgainstSchema(path = "arguments", obj = args, schema = schema, errors = errors)
        return errors
    }

    private fun validateObjectAgainstSchema(
        path: String,
        obj: JsonObject,
        schema: JsonObject,
        errors: MutableList<String>
    ) {
        val allowedTypes = extractTypeSet(schema["type"])
        if (allowedTypes.isNotEmpty() && !allowedTypes.contains("object")) {
            return
        }

        val properties = schema["properties"] as? JsonObject ?: JsonObject(emptyMap())
        val requiredFields = (schema["required"] as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank) }
            .orEmpty()

        for (required in requiredFields) {
            if (!obj.containsKey(required) || obj[required] is JsonNull) {
                errors += "${path}.${required} is required"
            }
        }

        val additionalAllowed = (schema["additionalProperties"] as? JsonPrimitive)?.booleanOrNull ?: true
        if (!additionalAllowed) {
            for (key in obj.keys) {
                if (!properties.containsKey(key)) {
                    errors += "${path}.${key} is not allowed"
                }
            }
        }

        for ((key, value) in obj) {
            val propSchema = properties[key] as? JsonObject ?: continue
            validateValue(path = "$path.$key", value = value, schema = propSchema, errors = errors)
        }
    }

    private fun validateValue(
        path: String,
        value: JsonElement,
        schema: JsonObject,
        errors: MutableList<String>
    ) {
        val allowedTypes = extractTypeSet(schema["type"])
        if (allowedTypes.isNotEmpty() && !matchesAnyType(value, allowedTypes)) {
            errors += "$path has invalid type (expected ${allowedTypes.joinToString("|")})"
            return
        }

        val enumValues = schema["enum"] as? JsonArray
        if (enumValues != null && enumValues.none { it == value }) {
            errors += "$path must be one of ${enumValues.joinToString(", ") { it.toString() }}"
            return
        }

        if (value is JsonPrimitive && value.isString) {
            val text = value.content
            val minLength = schemaInt(schema, "minLength")
            val maxLength = schemaInt(schema, "maxLength")
            if (minLength != null && text.length < minLength) {
                errors += "$path length must be >= $minLength"
            }
            if (maxLength != null && text.length > maxLength) {
                errors += "$path length must be <= $maxLength"
            }
        }

        val numericValue = asDouble(value)
        if (numericValue != null) {
            val min = schemaDouble(schema, "minimum")
            val max = schemaDouble(schema, "maximum")
            if (min != null && numericValue < min) {
                errors += "$path must be >= $min"
            }
            if (max != null && numericValue > max) {
                errors += "$path must be <= $max"
            }
        }

        if (value is JsonArray) {
            val minItems = schemaInt(schema, "minItems")
            val maxItems = schemaInt(schema, "maxItems")
            if (minItems != null && value.size < minItems) {
                errors += "$path must contain at least $minItems items"
            }
            if (maxItems != null && value.size > maxItems) {
                errors += "$path must contain at most $maxItems items"
            }
            val itemSchema = schema["items"] as? JsonObject
            if (itemSchema != null) {
                value.forEachIndexed { idx, item ->
                    validateValue("$path[$idx]", item, itemSchema, errors)
                }
            }
        }

        if (value is JsonObject) {
            validateObjectAgainstSchema(path, value, schema, errors)
        }
    }

    private fun extractTypeSet(typeElement: JsonElement?): Set<String> {
        return when (typeElement) {
            is JsonPrimitive -> typeElement.contentOrNull?.let { setOf(it.lowercase()) } ?: emptySet()
            is JsonArray -> typeElement.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.lowercase() }.toSet()
            else -> emptySet()
        }
    }

    private fun matchesAnyType(value: JsonElement, allowedTypes: Set<String>): Boolean {
        return allowedTypes.any { matchesType(value, it) }
    }

    private fun matchesType(value: JsonElement, type: String): Boolean {
        return when (type) {
            "string" -> value is JsonPrimitive && value.isString
            "integer" -> value is JsonPrimitive && !value.isString && value.longOrNull != null
            "number" -> value is JsonPrimitive && !value.isString && value.doubleOrNull != null
            "boolean" -> value is JsonPrimitive && !value.isString && value.booleanOrNull != null
            "object" -> value is JsonObject
            "array" -> value is JsonArray
            "null" -> value is JsonNull
            else -> true
        }
    }

    private fun schemaInt(schema: JsonObject, key: String): Int? {
        return (schema[key] as? JsonPrimitive)
            ?.takeIf { !it.isString }
            ?.content
            ?.toIntOrNull()
    }

    private fun schemaDouble(schema: JsonObject, key: String): Double? {
        return (schema[key] as? JsonPrimitive)
            ?.takeIf { !it.isString }
            ?.doubleOrNull
    }

    private fun asDouble(value: JsonElement): Double? {
        return (value as? JsonPrimitive)
            ?.takeIf { !it.isString }
            ?.doubleOrNull
    }

    companion object {
        private const val TAG = "ToolRegistry"
    }
}

