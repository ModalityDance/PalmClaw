package com.palmclaw.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class McpHttpConfigNormalizerTest {
    @Test
    fun `restore falls back to legacy server when new list is empty`() {
        val restored = McpHttpConfigNormalizer.restore(
            legacy = McpHttpConfigNormalizer.LegacySettings(
                enabled = true,
                serverName = "  ",
                serverUrl = "https://mcp.example.com",
                authToken = "secret",
                toolTimeoutSeconds = 999
            ),
            storedServers = emptyList()
        )

        assertTrue(restored.enabled)
        assertEquals(1, restored.servers.size)
        assertEquals("mcp_1", restored.servers.first().id)
        assertEquals(AppLimits.DEFAULT_MCP_HTTP_SERVER_NAME, restored.servers.first().serverName)
        assertEquals(AppLimits.MAX_MCP_HTTP_TOOL_TIMEOUT_SECONDS, restored.servers.first().toolTimeoutSeconds)
    }

    @Test
    fun `prepareForSave normalizes server ids names and timeout bounds`() {
        val persisted = McpHttpConfigNormalizer.prepareForSave(
            McpHttpConfig(
                enabled = true,
                serverName = "legacy-name",
                serverUrl = "legacy-url",
                authToken = "legacy-token",
                toolTimeoutSeconds = 1,
                servers = listOf(
                    McpHttpServerConfig(
                        id = "",
                        serverName = "  ",
                        serverUrl = "https://one.example.com",
                        authToken = "one",
                        toolTimeoutSeconds = 1
                    )
                )
            )
        )

        assertEquals(1, persisted.servers.size)
        assertEquals("mcp_1", persisted.servers.first().id)
        assertEquals(AppLimits.DEFAULT_MCP_HTTP_SERVER_NAME, persisted.servers.first().serverName)
        assertEquals(AppLimits.MIN_MCP_HTTP_TOOL_TIMEOUT_SECONDS, persisted.servers.first().toolTimeoutSeconds)
        assertEquals("https://one.example.com", persisted.serverUrl)
        assertEquals("one", persisted.authToken)
    }
}
