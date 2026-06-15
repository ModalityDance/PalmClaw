package com.palmclaw.tools

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class McpHttpEndpointPolicyTest {

    @Test
    fun `https endpoints are allowed for mcp`() {
        assertTrue(McpHttpEndpointPolicy.isAllowed("https://mcp.example.com/rpc"))
    }

    @Test
    fun `local and private http endpoints are allowed for mcp`() {
        assertTrue(McpHttpEndpointPolicy.isAllowed("http://localhost:3000/mcp"))
        assertTrue(McpHttpEndpointPolicy.isAllowed("http://127.0.0.1:3000/mcp"))
        assertTrue(McpHttpEndpointPolicy.isAllowed("http://10.0.2.2:3000/mcp"))
        assertTrue(McpHttpEndpointPolicy.isAllowed("http://10.1.2.3:3000/mcp"))
        assertTrue(McpHttpEndpointPolicy.isAllowed("http://172.16.1.2:3000/mcp"))
        assertTrue(McpHttpEndpointPolicy.isAllowed("http://172.31.1.2:3000/mcp"))
        assertTrue(McpHttpEndpointPolicy.isAllowed("http://192.168.1.2:3000/mcp"))
    }

    @Test
    fun `public http endpoints are rejected for mcp`() {
        assertFalse(McpHttpEndpointPolicy.isAllowed("http://example.com/mcp"))
        assertFalse(McpHttpEndpointPolicy.isAllowed("http://172.32.1.2:3000/mcp"))
        assertFalse(McpHttpEndpointPolicy.isAllowed("ftp://localhost/mcp"))
    }
}
