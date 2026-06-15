package com.palmclaw.agent

import com.palmclaw.providers.AssistantMessage
import com.palmclaw.providers.LlmResponse
import com.palmclaw.providers.ToolCall
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolCallParserTest {

    private val parser = ToolCallParser()

    @Test
    fun `parse prefers structured tool calls from provider response`() {
        val structured = listOf(
            ToolCall(id = "call-1", name = "weather", argumentsJson = """{"city":"HK"}""")
        )

        val parsed = parser.parse(
            LlmResponse(
                assistant = AssistantMessage(
                    content = """{"tool_calls": []}""",
                    toolCalls = structured
                )
            )
        )

        assertEquals(structured, parsed)
    }

    @Test
    fun `parse extracts tool calls from fenced json object`() {
        val parsed = parser.parse(
            LlmResponse(
                assistant = AssistantMessage(
                    content = """
                        ```json
                        {
                          "tool_calls": [
                            {
                              "id": "call-1",
                              "function": {
                                "name": "weather",
                                "arguments": "{\"city\":\"HK\"}"
                              }
                            }
                          ]
                        }
                        ```
                    """.trimIndent()
                )
            )
        )

        assertEquals(1, parsed.size)
        assertEquals("call-1", parsed.single().id)
        assertEquals("weather", parsed.single().name)
        assertEquals("""{"city":"HK"}""", parsed.single().argumentsJson)
    }

    @Test
    fun `parse extracts tool calls from bare array with top-level fields`() {
        val parsed = parser.parse(
            LlmResponse(
                assistant = AssistantMessage(
                    content = """
                        [
                          {
                            "id": "call-2",
                            "name": "search",
                            "arguments": { "q": "palmclaw" }
                          }
                        ]
                    """.trimIndent()
                )
            )
        )

        assertEquals(1, parsed.size)
        assertEquals("call-2", parsed.single().id)
        assertEquals("search", parsed.single().name)
        assertTrue(parsed.single().argumentsJson.contains("palmclaw"))
    }

    @Test
    fun `parse returns empty list for invalid content`() {
        val parsed = parser.parse(
            LlmResponse(
                assistant = AssistantMessage(content = "not a tool call")
            )
        )

        assertTrue(parsed.isEmpty())
    }
}
