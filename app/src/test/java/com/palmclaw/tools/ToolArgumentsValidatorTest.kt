package com.palmclaw.tools

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolArgumentsValidatorTest {
    private val validator = ToolArgumentsValidator()

    @Test
    fun `parseArgumentsObject supports raw and stringified json objects`() {
        val rawObject = validator.parseArgumentsObject("""{"name":"PalmClaw"}""")
        val stringifiedObject = validator.parseArgumentsObject("\"{\\\"name\\\":\\\"PalmClaw\\\"}\"")

        assertNotNull(rawObject)
        assertNotNull(stringifiedObject)
        assertEquals(rawObject, stringifiedObject)
    }

    @Test
    fun `validate reports required additional and range errors`() {
        val schema = buildJsonObject {
            put("type", "object")
            put(
                "properties",
                buildJsonObject {
                    put(
                        "name",
                        buildJsonObject {
                            put("type", "string")
                            put("minLength", 3)
                        }
                    )
                    put(
                        "count",
                        buildJsonObject {
                            put("type", "integer")
                            put("minimum", 1)
                        }
                    )
                }
            )
            put("required", buildJsonArray { add(JsonPrimitive("name")); add(JsonPrimitive("count")) })
            put("additionalProperties", false)
        }
        val arguments = buildJsonObject {
            put("name", "hi")
            put("extra", true)
        }

        val errors = validator.validate(schema, arguments)

        assertEquals(3, errors.size)
        assertTrue(errors.contains("arguments.name length must be >= 3"))
        assertTrue(errors.contains("arguments.count is required"))
        assertTrue(errors.contains("arguments.extra is not allowed"))
    }
}
