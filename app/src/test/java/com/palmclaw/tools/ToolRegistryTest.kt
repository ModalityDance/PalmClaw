package com.palmclaw.tools

import com.palmclaw.providers.ToolCall
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolRegistryTest {
    @Test
    fun `structured tool errors remain valid json when recovery guidance is added`() = runBlocking {
        val registry = registry(
            ToolResult(
                toolCallId = "",
                content = """{"status":"error","code":"invalid_arguments"}""",
                isError = true
            )
        )

        val result = registry.execute(call())
        val body = Json.parseToJsonElement(result.content).jsonObject

        assertEquals("call-1", result.toolCallId)
        assertEquals("invalid_arguments", body["code"]!!.jsonPrimitive.content)
        assertEquals(
            "Analyze the error above and try a different approach.",
            body["recovery_hint"]!!.jsonPrimitive.content
        )
    }

    @Test
    fun `plain text tool errors retain the legacy recovery suffix`() = runBlocking {
        val result = registry(
            ToolResult(toolCallId = "", content = "plain failure", isError = true)
        ).execute(call())

        assertTrue(result.content.startsWith("plain failure"))
        assertTrue(result.content.contains("[Analyze the error above and try a different approach.]"))
    }

    private fun registry(result: ToolResult): ToolRegistry {
        val tool = object : Tool {
            override val name: String = "test_tool"
            override val description: String = "Test tool"
            override val jsonSchema: JsonObject = buildJsonObject {
                put("type", "object")
                put("additionalProperties", false)
            }

            override suspend fun run(argumentsJson: String): ToolResult = result
        }
        return ToolRegistry(
            initialTools = mapOf(tool.name to tool),
            debugLog = {}
        )
    }

    private fun call() = ToolCall(
        id = "call-1",
        name = "test_tool",
        argumentsJson = "{}"
    )
}
