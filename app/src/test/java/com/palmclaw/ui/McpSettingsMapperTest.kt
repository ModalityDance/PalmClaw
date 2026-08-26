package com.palmclaw.ui

import com.palmclaw.config.McpHttpConfig
import com.palmclaw.config.McpHttpServerConfig
import com.palmclaw.mcp.McpEndpointDisposition
import com.palmclaw.mcp.McpEndpointIssue
import com.palmclaw.mcp.McpEndpointPolicy
import com.palmclaw.mcp.McpRuntimeIssue
import com.palmclaw.mcp.McpRuntimeSnapshot
import com.palmclaw.mcp.McpServerPhase
import com.palmclaw.mcp.McpServerSnapshot
import com.palmclaw.mcp.transport.McpTransportKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class McpSettingsMapperTest {
    @Test
    fun `runtime snapshot keeps degraded capability state distinct from disconnected defaults`() {
        val statuses = McpSettingsMapper.runtimeStatuses(
            McpRuntimeSnapshot(
                enabled = true,
                generation = 4,
                servers = listOf(
                    McpServerSnapshot(
                        serverId = "stable-id",
                        serverName = "Alpha",
                        endpoint = "http://127.0.0.1:8080/mcp",
                        phase = McpServerPhase.DEGRADED,
                        usable = true,
                        detail = "Prompt refresh failed",
                        toolNames = listOf("mcp_alpha_read"),
                        resourceCount = 2,
                        promptCount = 1,
                        transport = McpTransportKind.STREAMABLE_HTTP,
                        protocolVersion = "2025-11-25",
                        endpointSecurity = com.palmclaw.mcp.McpEndpointSecurity.LOOPBACK_HTTP,
                        insecureWarning = "Local traffic is not encrypted"
                    )
                )
            )
        )

        val status = statuses.getValue("stable-id")
        assertEquals("Degraded", status.status)
        assertEquals("degraded", status.phase)
        assertEquals(true, status.usable)
        assertEquals(1, status.toolCount)
        assertEquals(2, status.resourceCount)
        assertEquals("streamable_http", status.transport)
        assertEquals("loopback_http", status.endpointSecurity)
    }

    @Test
    fun `runtime snapshot projects enabled generation and issues for settings ui`() {
        val projected = McpSettingsMapper.runtimeSnapshot(
            McpRuntimeSnapshot(
                enabled = true,
                generation = 13,
                issues = listOf(
                    McpRuntimeIssue(
                        code = "content_tool_name_conflict",
                        detail = "Could not publish mcp_content"
                    )
                )
            )
        )

        assertEquals(true, projected.enabled)
        assertEquals(13, projected.generation)
        assertEquals("content_tool_name_conflict", projected.issues.single().code)
        assertEquals("Could not publish mcp_content", projected.issues.single().detail)
    }

    @Test
    fun `runtime status merge isolates an unsaved server from stale runtime fields`() {
        val draft = UiMcpServerConfig(
            id = "stable-id",
            serverName = "Unsaved name",
            serverUrl = "https://draft.example/mcp",
            authToken = "unsaved-token",
            toolTimeoutSeconds = "91",
            dirty = true,
            phase = "unsaved",
            status = "Unsaved changes"
        )
        val merged = McpSettingsMapper.applyRuntimeStatuses(
            servers = listOf(draft),
            enabled = true,
            runtimeStatuses = mapOf(
                "stable-id" to UiMcpServerRuntimeStatus(
                    phase = "ready",
                    status = "Connected",
                    usable = true,
                    toolCount = 2,
                    protocolVersion = "2025-11-25"
                )
            )
        ).single()

        assertEquals("Unsaved name", merged.serverName)
        assertEquals("https://draft.example/mcp", merged.serverUrl)
        assertEquals("unsaved-token", merged.authToken)
        assertEquals("91", merged.toolTimeoutSeconds)
        assertEquals("unsaved", merged.phase)
        assertEquals("Unsaved changes", merged.status)
        assertEquals(false, merged.usable)
        assertEquals(0, merged.toolCount)
        assertEquals(0, merged.resourceCount)
        assertEquals(0, merged.resourceTemplateCount)
        assertEquals(0, merged.promptCount)
        assertEquals(false, merged.completionSupported)
        assertEquals(emptyList<String>(), merged.toolNames)
        assertNull(merged.transport)
        assertNull(merged.protocolVersion)
        assertNull(merged.endpointSecurity)
        assertNull(merged.insecureWarning)
    }

    @Test
    fun `runtime status merge rejects a snapshot for the previous endpoint`() {
        val merged = McpSettingsMapper.applyRuntimeStatuses(
            servers = listOf(
                UiMcpServerConfig(
                    id = "stable-id",
                    serverName = "Alpha",
                    serverUrl = "https://new.example/mcp"
                )
            ),
            enabled = true,
            runtimeStatuses = mapOf(
                "stable-id" to UiMcpServerRuntimeStatus(
                    serverName = "Alpha",
                    endpoint = "https://old.example/mcp",
                    phase = "ready",
                    status = "Connected",
                    usable = true,
                    toolCount = 2
                )
            )
        ).single()

        assertEquals("Not connected", merged.status)
        assertEquals("connecting", merged.phase)
        assertEquals(false, merged.usable)
        assertEquals(0, merged.toolCount)
    }

    @Test
    fun `lan endpoint remains configured while approval is pending`() {
        val config = McpSettingsMapper.buildConfig(
            McpSettingsState(
                enabled = true,
                servers = listOf(
                    UiMcpServerConfig(
                        id = "lan",
                        serverName = "Lab",
                        serverUrl = "http://192.168.0.4:8080/mcp"
                    )
                )
            )
        )

        val server = config.servers.single()
        assertEquals("http://192.168.0.4:8080/mcp", server.serverUrl)
        assertNull(server.insecureHttpAllowedOrigin)
        assertEquals(
            McpEndpointDisposition.ACTION_REQUIRED,
            McpEndpointPolicy.evaluate(
                server.serverUrl,
                server.authToken,
                server.insecureHttpAllowedOrigin
            ).disposition
        )
    }

    @Test
    fun `confirmed lan origin round trips through config and ui`() {
        val origin = "http://10.20.30.40:9000"
        val config = McpSettingsMapper.buildConfig(
            McpSettingsState(
                enabled = true,
                servers = listOf(
                    UiMcpServerConfig(
                        id = "lan",
                        serverName = "Lab",
                        serverUrl = "$origin/mcp",
                        insecureHttpAllowedOrigin = origin
                    )
                )
            )
        )

        assertEquals(origin, config.servers.single().insecureHttpAllowedOrigin)
        assertEquals(
            origin,
            McpSettingsMapper.buildUiServers(config, emptyMap()).single().insecureHttpAllowedOrigin
        )
    }

    @Test
    fun `lan bearer config is retained but cannot connect`() {
        val origin = "http://192.168.2.8"
        val config = McpSettingsMapper.buildConfig(
            McpSettingsState(
                enabled = true,
                servers = listOf(
                    UiMcpServerConfig(
                        id = "lan",
                        serverName = "Lab",
                        serverUrl = "$origin/mcp",
                        authToken = "secret",
                        insecureHttpAllowedOrigin = origin
                    )
                )
            )
        )

        val server = config.servers.single()
        val decision = McpEndpointPolicy.evaluate(
            server.serverUrl,
            server.authToken,
            server.insecureHttpAllowedOrigin
        )
        assertEquals(McpEndpointDisposition.ACTION_REQUIRED, decision.disposition)
        assertEquals(McpEndpointIssue.AUTH_REQUIRES_HTTPS, decision.issue)
        assertEquals("secret", server.authToken)
    }

    @Test
    fun `stale lan confirmation is not applied to a different origin`() {
        val config = McpSettingsMapper.buildConfig(
            McpSettingsState(
                enabled = true,
                servers = listOf(
                    UiMcpServerConfig(
                        id = "lan",
                        serverName = "Lab",
                        serverUrl = "http://192.168.0.5/mcp",
                        insecureHttpAllowedOrigin = "http://192.168.0.4"
                    )
                )
            )
        )

        assertNull(config.servers.single().insecureHttpAllowedOrigin)
    }

    @Test
    fun `equivalent lan origin is canonicalized before it is compared`() {
        val config = McpSettingsMapper.buildConfig(
            McpSettingsState(
                enabled = true,
                servers = listOf(
                    UiMcpServerConfig(
                        id = "lan",
                        serverName = "Lab",
                        serverUrl = "http://192.168.0.5:80/mcp",
                        insecureHttpAllowedOrigin = "HTTP://192.168.0.5:80"
                    )
                )
            )
        )

        assertEquals("http://192.168.0.5", config.servers.single().insecureHttpAllowedOrigin)
    }

    @Test
    fun `public http and credential-bearing urls cannot be saved`() {
        fun stateFor(url: String) = McpSettingsState(
            enabled = true,
            servers = listOf(
                UiMcpServerConfig(
                    id = "server",
                    serverUrl = url
                )
            )
        )

        assertThrows(IllegalArgumentException::class.java) {
            McpSettingsMapper.buildConfig(stateFor("http://example.com/mcp"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            McpSettingsMapper.buildConfig(stateFor("https://user:pass@example.com/mcp"))
        }
    }

    @Test
    fun `server names that normalize to one tool namespace are rejected`() {
        val state = McpSettingsState(
            enabled = true,
            servers = listOf(
                UiMcpServerConfig(
                    id = "first",
                    serverName = "Research Server",
                    serverUrl = "https://first.example/mcp"
                ),
                UiMcpServerConfig(
                    id = "second",
                    serverName = "research_server",
                    serverUrl = "https://second.example/mcp"
                )
            )
        )

        assertThrows(IllegalArgumentException::class.java) {
            McpSettingsMapper.buildConfig(state)
        }
    }

    @Test
    fun `normalizer preserves an existing exact lan confirmation`() {
        val origin = "http://192.168.10.4:8080"
        val uiServers = McpSettingsMapper.buildUiServers(
            config = McpHttpConfig(
                enabled = true,
                servers = listOf(
                    McpHttpServerConfig(
                        id = "lan",
                        serverUrl = "$origin/mcp",
                        insecureHttpAllowedOrigin = origin
                    )
                )
            ),
            runtimeStatuses = emptyMap()
        )

        assertEquals(origin, uiServers.single().insecureHttpAllowedOrigin)
    }

    @Test
    fun `runtime status projects by stable server id without exposing credentials`() {
        val server = McpSettingsMapper.buildUiServers(
            config = McpHttpConfig(
                enabled = true,
                servers = listOf(
                    McpHttpServerConfig(
                        id = "stable-id",
                        serverName = "Renamed Server",
                        serverUrl = "https://example.com/mcp",
                        authToken = "stored-secret"
                    )
                )
            ),
            runtimeStatuses = mapOf(
                "stable-id" to UiMcpServerRuntimeStatus(
                    phase = "ready",
                    status = "Connected",
                    usable = true,
                    detail = "Ready",
                    toolCount = 2,
                    resourceCount = 3,
                    resourceTemplateCount = 4,
                    promptCount = 5,
                    completionSupported = true,
                    transport = "streamable_http",
                    protocolVersion = "2025-11-25",
                    endpointSecurity = "https"
                )
            )
        ).single()

        assertEquals("ready", server.phase)
        assertEquals(2, server.toolCount)
        assertEquals(3, server.resourceCount)
        assertEquals(4, server.resourceTemplateCount)
        assertEquals(5, server.promptCount)
        assertEquals("streamable_http", server.transport)
        assertEquals("2025-11-25", server.protocolVersion)
        assertEquals("https", server.endpointSecurity)
    }
}
