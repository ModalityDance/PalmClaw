package com.palmclaw.mcp

import com.palmclaw.config.McpHttpServerConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class McpEndpointPolicyTest {
    @Test
    fun `configuration fingerprint includes full endpoint and hides identity material`() {
        val server = McpHttpServerConfig(
            id = "alpha",
            serverName = "Alpha Server",
            serverUrl = "https://mcp.example/rpc",
            authToken = "private-bearer-token",
            toolTimeoutSeconds = 30
        )
        val first = McpEndpointPolicy.configurationFingerprint(
            server = server,
            canonicalUrl = "https://mcp.example/rpc?tenant=one"
        )
        val second = McpEndpointPolicy.configurationFingerprint(
            server = server,
            canonicalUrl = "https://mcp.example/rpc?tenant=two"
        )

        assertNotEquals(first, second)
        assertTrue(first.orEmpty().startsWith("mcp-config-v1:"))
        assertFalse(first.orEmpty().contains("tenant"))
        assertFalse(first.orEmpty().contains("one"))
        assertFalse(first.orEmpty().contains("private-bearer-token"))
    }

    @Test
    fun `https is allowed and exposes a canonical origin`() {
        val decision = McpEndpointPolicy.evaluate(
            rawUrl = "HTTPS://MCP.Example.COM:443/rpc?client=palmclaw",
            authToken = "secret",
            insecureHttpAllowedOrigin = null
        )

        assertEquals(McpEndpointDisposition.ALLOWED, decision.disposition)
        assertEquals(McpEndpointSecurity.HTTPS, decision.security)
        assertEquals(McpEndpointNetworkScope.EXTERNAL, decision.networkScope)
        assertEquals("https://mcp.example.com", decision.canonicalOrigin)
        assertEquals("https://mcp.example.com/rpc?client=palmclaw", decision.canonicalUrl)
        assertNull(decision.issue)
    }

    @Test
    fun `loopback and emulator http stay available without confirmation`() {
        listOf(
            "http://localhost:3000/mcp",
            "http://127.0.0.42:3000/mcp",
            "http://[::1]:3000/mcp",
            "http://10.0.2.2:3000/mcp",
            "http://10.0.3.2:3000/mcp"
        ).forEach { url ->
            val decision = McpEndpointPolicy.evaluate(
                rawUrl = url,
                authToken = "local-token",
                insecureHttpAllowedOrigin = null
            )

            assertEquals("Expected $url to remain usable", McpEndpointDisposition.ALLOWED, decision.disposition)
            assertTrue(decision.isInsecureHttp)
            assertTrue(decision.warning != null)
        }
    }

    @Test
    fun `network scope is independent from transport security`() {
        assertEquals(
            McpEndpointNetworkScope.LOOPBACK,
            McpEndpointPolicy.evaluate("https://localhost/mcp", "", null).networkScope
        )
        assertEquals(
            McpEndpointNetworkScope.PRIVATE_NETWORK,
            McpEndpointPolicy.evaluate("https://192.168.1.9/mcp", "", null).networkScope
        )
        assertEquals(
            McpEndpointNetworkScope.PRIVATE_NETWORK,
            McpEndpointPolicy.evaluate("https://agent.local/mcp", "", null).networkScope
        )
    }

    @Test
    fun `private lan http requires confirmation for its exact canonical origin`() {
        val url = "http://192.168.1.20:8080/mcp"
        val unconfirmed = McpEndpointPolicy.evaluate(url, "", null)

        assertEquals(McpEndpointDisposition.ACTION_REQUIRED, unconfirmed.disposition)
        assertEquals(McpEndpointSecurity.PRIVATE_LAN_HTTP, unconfirmed.security)
        assertEquals("http://192.168.1.20:8080", unconfirmed.canonicalOrigin)
        assertEquals(McpEndpointIssue.INSECURE_HTTP_CONFIRMATION_REQUIRED, unconfirmed.issue)

        val confirmed = McpEndpointPolicy.evaluate(
            rawUrl = url,
            authToken = "",
            insecureHttpAllowedOrigin = "http://192.168.1.20:8080"
        )
        assertEquals(McpEndpointDisposition.ALLOWED, confirmed.disposition)

        val differentPort = McpEndpointPolicy.evaluate(
            rawUrl = url,
            authToken = "",
            insecureHttpAllowedOrigin = "http://192.168.1.20:8081"
        )
        assertEquals(McpEndpointDisposition.ACTION_REQUIRED, differentPort.disposition)

        val ipv6 = McpEndpointPolicy.evaluate("http://[fd00::1]:8080/mcp", "", null)
        assertEquals(McpEndpointDisposition.ACTION_REQUIRED, ipv6.disposition)
        assertEquals("http://[fd00::1]:8080", ipv6.canonicalOrigin)
        assertEquals(McpEndpointNetworkScope.PRIVATE_NETWORK, ipv6.networkScope)
    }

    @Test
    fun `bearer token is never sent over private lan http`() {
        val decision = McpEndpointPolicy.evaluate(
            rawUrl = "http://10.1.2.3/mcp",
            authToken = "secret",
            insecureHttpAllowedOrigin = "http://10.1.2.3"
        )

        assertEquals(McpEndpointDisposition.ACTION_REQUIRED, decision.disposition)
        assertEquals(McpEndpointIssue.AUTH_REQUIRES_HTTPS, decision.issue)
        assertFalse(decision.canConnect)
    }

    @Test
    fun `public and ambiguous http endpoints are rejected`() {
        listOf(
            "http://example.com/mcp",
            "http://printer.local/mcp",
            "http://172.32.1.2/mcp",
            "http://169.254.1.2/mcp",
            "http://224.0.0.1/mcp",
            "http://[2001:db8::1]/mcp"
        ).forEach { url ->
            val decision = McpEndpointPolicy.evaluate(url, "", null)
            assertEquals("Expected $url to be rejected", McpEndpointDisposition.REJECTED, decision.disposition)
            assertEquals(McpEndpointIssue.PUBLIC_HTTP_NOT_ALLOWED, decision.issue)
        }
    }

    @Test
    fun `userinfo fragments and unsupported schemes are rejected`() {
        assertEquals(
            McpEndpointIssue.USERINFO_NOT_ALLOWED,
            McpEndpointPolicy.evaluate("https://user:pass@mcp.example.com/rpc", "", null).issue
        )
        assertEquals(
            McpEndpointIssue.USERINFO_NOT_ALLOWED,
            McpEndpointPolicy.evaluate("https://@mcp.example.com/rpc", "", null).issue
        )
        assertEquals(
            McpEndpointIssue.FRAGMENT_NOT_ALLOWED,
            McpEndpointPolicy.evaluate("https://mcp.example.com/rpc#secret", "", null).issue
        )
        assertEquals(
            McpEndpointIssue.QUERY_CREDENTIAL_NOT_ALLOWED,
            McpEndpointPolicy.evaluate(
                "https://mcp.example.com/rpc?access_token=secret",
                "",
                null
            ).issue
        )
        assertEquals(
            McpEndpointIssue.UNSUPPORTED_SCHEME,
            McpEndpointPolicy.evaluate("ftp://localhost/mcp", "", null).issue
        )
    }
}
