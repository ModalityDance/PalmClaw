package com.palmclaw.tools

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/**
 * Minimal JSON-schema validator for tool arguments.
 *
 * We intentionally support only the subset needed by this app's tool catalog
 * so validation remains predictable and easy to test.
 */
class ToolArgumentsValidator(
    private val json: Json = Json { ignoreUnknownKeys = true }
) {
    fun parseArgumentsObject(raw: String): JsonObject? {
        val firstPass = runCatching { json.parseToJsonElement(raw) }.getOrNull() ?: return null
        if (firstPass is JsonObject) return firstPass
        if (firstPass is JsonPrimitive && firstPass.isString) {
            val secondPass = runCatching { json.parseToJsonElement(firstPass.content) }.getOrNull()
            if (secondPass is JsonObject) return secondPass
        }
        return null
    }

    fun validate(schema: JsonObject, arguments: JsonObject): List<String> {
        val errors = mutableListOf<String>()
        validateObjectAgainstSchema(
            path = "arguments",
            obj = arguments,
            schema = schema,
            errors = errors
        )
        return errors
    }

    private fun validateObjectAgainstSchema(
        path: String,
        obj: JsonObject,
        schema: JsonObject,
        errors: MutableList<String>
    ) {
        val allowedTypes = extractTypeSet(schema["type"])
        if (allowedTypes.isNotEmpty() && "object" !in allowedTypes) {
            return
        }

        val properties = schema["properties"] as? JsonObject ?: JsonObject(emptyMap())
        val requiredFields = (schema["required"] as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank) }
            .orEmpty()

        for (requiredField in requiredFields) {
            if (!obj.containsKey(requiredField) || obj[requiredField] is JsonNull) {
                errors += "$path.$requiredField is required"
            }
        }

        val additionalAllowed = (schema["additionalProperties"] as? JsonPrimitive)?.booleanOrNull ?: true
        if (!additionalAllowed) {
            for (key in obj.keys) {
                if (!properties.containsKey(key)) {
                    errors += "$path.$key is not allowed"
                }
            }
        }

        for ((key, value) in obj) {
            val propertySchema = properties[key] as? JsonObject ?: continue
            validateValue(
                path = "$path.$key",
                value = value,
                schema = propertySchema,
                errors = errors
            )
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
                value.forEachIndexed { index, item ->
                    validateValue("$path[$index]", item, itemSchema, errors)
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
}
