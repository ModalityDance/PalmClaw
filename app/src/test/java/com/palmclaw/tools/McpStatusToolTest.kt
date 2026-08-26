package com.palmclaw.tools

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class McpStatusToolTest {

    @Test
    fun `status reports negotiated capabilities and endpoint security without credentials`(): Unit = runBlocking {
        val tool = McpStatusTool {
            McpStatusTool.Snapshot(
                enabled = true,
                generation = 7,
                connectedServerCount = 1,
                registeredToolCount = 2,
                availableResourceCount = 3,
                availableResourceTemplateCount = 4,
                availablePromptCount = 5,
                issues = listOf(
                    McpStatusTool.Issue(
                        code = "content_tool_name_conflict",
                        detail = "Could not publish mcp_content"
                    )
                ),
                servers = listOf(
                    McpStatusTool.Entry(
                        serverId = "alpha",
                        serverName = "Alpha",
                        serverUrl = "https://user:secret@example.com/mcp?api_key=must-not-leak#private-fragment",
                        phase = "ready",
                        status = "Connected",
                        usable = true,
                        detail = "",
                        toolCount = 2,
                        resourceCount = 3,
                        resourceTemplateCount = 4,
                        promptCount = 5,
                        completionSupported = true,
                        toolNames = listOf("mcp_alpha_read", "mcp_alpha_write"),
                        transport = "streamable_http",
                        protocolVersion = "2025-11-25",
                        endpointSecurity = "private_lan_http",
                        insecureWarning = "Traffic is not encrypted"
                    )
                )
            )
        }

        val result = tool.run("{}")
        val json = Json.parseToJsonElement(result.content).jsonObject
        val server = json.getValue("servers").jsonArray.single().jsonObject

        assertFalse(result.isError)
        assertEquals("alpha", server.getValue("server_id").jsonPrimitive.content)
        assertEquals("ready", server.getValue("phase").jsonPrimitive.content)
        assertEquals("streamable_http", server.getValue("transport").jsonPrimitive.content)
        assertEquals(3, server.getValue("resource_count").jsonPrimitive.content.toInt())
        assertTrue(server.getValue("completion_supported").jsonPrimitive.content.toBoolean())
        assertEquals(7, json.getValue("generation").jsonPrimitive.content.toLong())
        assertEquals(
            "content_tool_name_conflict",
            json.getValue("issues").jsonArray.single().jsonObject
                .getValue("code").jsonPrimitive.content
        )
        assertEquals(
            1,
            result.metadata?.get("issue_count")?.jsonPrimitive?.content?.toInt()
        )
        assertEquals(
            "https://example.com/mcp",
            server.getValue("server_url").jsonPrimitive.content
        )
        assertFalse(result.content.contains("user"))
        assertFalse(result.content.contains("secret"))
        assertFalse(result.content.contains("must-not-leak"))
        assertFalse(result.content.contains("private-fragment"))
    }
}
