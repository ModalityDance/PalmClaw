package com.palmclaw.mcp.transport

import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.url
import io.ktor.http.HttpHeaders
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import io.modelcontextprotocol.kotlin.sdk.client.StreamableHttpError
import io.modelcontextprotocol.kotlin.sdk.shared.Transport
import io.modelcontextprotocol.kotlin.sdk.shared.TransportSendOptions
import io.modelcontextprotocol.kotlin.sdk.types.JSONRPCMessage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import okio.Buffer

class McpTransportContractsTest {

    @Test
    fun `fake factory preserves exact connection request and session`(): Unit = runBlocking {
        val expectedSession = StubSession()
        var observed: McpTransportConnectRequest? = null
        val factory = FakeMcpTransportClientFactory { request ->
            observed = request
            expectedSession
        }
        val request = McpTransportConnectRequest(
            serverId = "server-1",
            endpoint = "https://mcp.example.test/rpc",
            bearerToken = "secret",
            requestTimeoutMillis = 12_000,
            transportPreference = McpTransportPreference.AUTO,
        )

        val session = factory.connect(request)

        assertEquals(request, observed)
        assertSame(expectedSession, session)
    }

    @Test
    fun `tool result retains structured and non-text content`() {
        val structured = buildJsonObject { put("answer", 42) }
        val result = McpToolCallResult(
            content = listOf(
                McpContentBlock.Text("done"),
                McpContentBlock.Image(data = "YWJj", mimeType = "image/png"),
                McpContentBlock.Audio(data = "ZGVm", mimeType = "audio/mpeg"),
                McpContentBlock.ResourceLink(uri = "demo://item/1", name = "item"),
                McpContentBlock.EmbeddedResource(
                    McpResourceContents.Text(
                        uri = "demo://item/1",
                        mimeType = "text/plain",
                        text = "embedded",
                    ),
                ),
            ),
            structuredContent = structured,
            isError = false,
        )

        assertEquals(5, result.content.size)
        assertEquals(structured, result.structuredContent)
        assertFalse(result.isError)
    }

    @Test
    fun `official tool schema mapping preserves its complete wire model`() {
        val properties = buildJsonObject {
            put("path", buildJsonObject { put("type", "string") })
        }
        val definitions = buildJsonObject {
            put("location", buildJsonObject { put("type", "object") })
        }

        val encoded = ToolSchema(
            properties = properties,
            required = listOf("path"),
            defs = definitions,
        ).toTransportJsonObject()

        assertEquals("object", encoded["type"]?.toString()?.trim('"'))
        assertEquals(properties, encoded["properties"])
        assertEquals("[\"path\"]", encoded["required"].toString())
        assertEquals(definitions, encoded["\$defs"])
        assertEquals(setOf("type", "properties", "required", "\$defs"), encoded.keys)
    }

    @Test
    fun `response limit is a stable non-recoverable transport error`() {
        val failure = McpResponseLimitException("too large")

        assertEquals(McpTransportErrorCode.RESPONSE_TOO_LARGE, failure.code)
        assertFalse(failure.recoverable)
        assertFalse(failure.outcomeUnknown)
    }

    @Test
    fun `raw response limiter rejects declared and streamed oversized bodies`() {
        val declared = runCatching { validateMcpContentLength(contentLength = 9, maxResponseBytes = 8) }
            .exceptionOrNull()
        val chunked = limitMcpResponseSource(
            source = Buffer().writeUtf8("123456789"),
            maxResponseBytes = 8,
            eventStream = false,
        )
        val streamed = runCatching { chunked.readUtf8() }.exceptionOrNull()

        assertTrue(declared is McpResponseTooLargeIOException)
        assertTrue(streamed is McpResponseTooLargeIOException)
        val mapped = streamed!!.toMcpTransportException(outcomeUnknown = false)
        val wrapped = IllegalStateException("SDK wrapper", streamed)
            .toMcpTransportException(outcomeUnknown = false)
        assertEquals(McpTransportErrorCode.RESPONSE_TOO_LARGE, mapped.code)
        assertEquals(McpTransportErrorCode.RESPONSE_TOO_LARGE, wrapped.code)
        assertFalse(mapped.recoverable)
        assertTrue(streamed.toMcpTransportException(outcomeUnknown = true).outcomeUnknown)
        assertTrue(McpResponseLimitException("decoded limit").toMcpTransportException(true).outcomeUnknown)
    }

    @Test
    fun `SSE limiter bounds each event without capping the session total`() {
        val validLongStream = limitMcpResponseSource(
            source = Buffer().writeUtf8("data: 1\n\ndata: 2\n\ndata: 3\n\n"),
            maxResponseBytes = 10,
            eventStream = true,
        )
        val oversizedEvent = limitMcpResponseSource(
            source = Buffer().writeUtf8("data: 12345\n\n"),
            maxResponseBytes = 10,
            eventStream = true,
        )
        val validCrLfStream = limitMcpResponseSource(
            source = Buffer().writeUtf8("data: 12\r\n\r\ndata: 34\r\n\r\n"),
            // Each event is exactly nine counted bytes. If LF after CR were charged to the
            // following event, the second event would incorrectly exceed this boundary.
            maxResponseBytes = 9,
            eventStream = true,
        )

        assertEquals("data: 1\n\ndata: 2\n\ndata: 3\n\n", validLongStream.readUtf8())
        assertEquals("data: 12\r\n\r\ndata: 34\r\n\r\n", validCrLfStream.readUtf8())
        assertTrue(runCatching { oversizedEvent.readUtf8() }.exceptionOrNull() is McpResponseTooLargeIOException)
    }

    @Test
    fun `legacy fallback accepts only deterministic compatibility failures`() {
        assertTrue(McpLegacyFallbackPolicy.shouldFallback(protocolFailure(400)))
        assertTrue(
            McpLegacyFallbackPolicy.shouldFallback(
                StreamableHttpError(404, "not found").toMcpTransportException(outcomeUnknown = false),
            ),
        )
        assertTrue(
            McpLegacyFallbackPolicy.shouldFallback(
                IllegalStateException(
                    "official SDK connect wrapper",
                    StreamableHttpError(404, "not found")
                        .toMcpTransportException(outcomeUnknown = false),
                ),
            ),
        )
        assertTrue(McpLegacyFallbackPolicy.shouldFallback(protocolFailure(405)))
        assertTrue(
            McpLegacyFallbackPolicy.shouldFallback(
                McpTransportException(
                    code = McpTransportErrorCode.UNSUPPORTED,
                    message = "unsupported transport",
                    recoverable = false,
                ),
            ),
        )

        assertFalse(McpLegacyFallbackPolicy.shouldFallback(protocolFailure(401)))
        assertFalse(McpLegacyFallbackPolicy.shouldFallback(protocolFailure(403)))
        assertFalse(McpLegacyFallbackPolicy.shouldFallback(protocolFailure(429)))
        assertFalse(McpLegacyFallbackPolicy.shouldFallback(protocolFailure(500)))
        assertFalse(
            McpLegacyFallbackPolicy.shouldFallback(
                McpTransportException(
                    code = McpTransportErrorCode.NOT_FOUND,
                    message = "logical resource not found",
                    recoverable = false,
                ),
            ),
        )
        assertFalse(
            McpLegacyFallbackPolicy.shouldFallback(
                McpTransportException(
                    code = McpTransportErrorCode.PROTOCOL,
                    message = "protocol error without HTTP status",
                    recoverable = false,
                ),
            ),
        )
        assertFalse(
            McpLegacyFallbackPolicy.shouldFallback(
                McpTransportException(
                    code = McpTransportErrorCode.NETWORK,
                    message = "offline",
                    recoverable = true,
                ),
            ),
        )
    }

    @Test
    fun `official streamable HTTP error retains status for fallback decision`() {
        val mapped = StreamableHttpError(405, "method not allowed")
            .toMcpTransportException(outcomeUnknown = false)

        assertEquals(McpTransportErrorCode.PROTOCOL, mapped.code)
        assertEquals(405, mapped.httpStatus)
        assertTrue(McpLegacyFallbackPolicy.shouldFallback(mapped))
    }

    @Test
    fun `official streamable HTTP 404 maps to not found and enables compatibility fallback`() {
        val mapped = StreamableHttpError(404, "not found")
            .toMcpTransportException(outcomeUnknown = false)

        assertEquals(McpTransportErrorCode.NOT_FOUND, mapped.code)
        assertEquals(404, mapped.httpStatus)
        assertTrue(McpLegacyFallbackPolicy.shouldFallback(mapped))
    }

    @Test
    fun `transport close before session observer is replayed exactly once`() {
        val delegate = ControllableTransport()
        val transport = NegotiatedVersionTransport(delegate, CompletableDeferred())
        transport.onClose { }
        delegate.signalClose()
        var disconnects = 0

        transport.observeClose { disconnects += 1 }
        delegate.signalClose()

        assertEquals(1, disconnects)
    }

    private fun protocolFailure(status: Int) = McpTransportException(
        code = when (status) {
            401 -> McpTransportErrorCode.AUTHENTICATION_REQUIRED
            403 -> McpTransportErrorCode.PERMISSION_DENIED
            429 -> McpTransportErrorCode.RATE_LIMITED
            in 500..599 -> McpTransportErrorCode.SERVER_ERROR
            else -> McpTransportErrorCode.PROTOCOL
        },
        message = "HTTP $status",
        recoverable = status == 429 || status >= 500,
        httpStatus = status,
    )

    @Test
    fun `session call contract does not swallow cancellation`(): Unit = runBlocking {
        val session = object : StubSession() {
            override suspend fun callTool(
                name: String,
                arguments: kotlinx.serialization.json.JsonObject,
            ): McpToolCallResult = throw CancellationException("cancelled")
        }

        val failure = runCatching { session.callTool("write", buildJsonObject {}) }.exceptionOrNull()

        assertTrue(failure is CancellationException)
    }

    @Test
    fun `internal request timeout is structured but outer timeout remains cancellation`(): Unit = runBlocking {
        val timeout = runCatching {
            withTimeout(1L) { awaitCancellation() }
        }.exceptionOrNull() as TimeoutCancellationException

        val mapped = timeout.toMcpCancellationFailure(
            outcomeUnknown = true,
            currentContextActive = true,
        )
        val propagated = timeout.toMcpCancellationFailure(
            outcomeUnknown = true,
            currentContextActive = false,
        )

        assertTrue(mapped is McpTransportException)
        mapped as McpTransportException
        assertEquals(McpTransportErrorCode.TIMEOUT, mapped.code)
        assertTrue(mapped.outcomeUnknown)
        assertSame(timeout, propagated)
    }

    @Test
    fun `connect handshake timeout covers transport negotiation`(): Unit = runBlocking {
        val timeout = runCatching {
            withMcpHandshakeTimeout(timeoutMillis = 1L) {
                awaitCancellation()
            }
        }.exceptionOrNull()

        assertTrue(timeout is TimeoutCancellationException)
        val mapped = (timeout as TimeoutCancellationException).toMcpCancellationFailure(
            outcomeUnknown = false,
            currentContextActive = true,
        )
        assertTrue(mapped is McpTransportException)
        assertEquals(McpTransportErrorCode.TIMEOUT, (mapped as McpTransportException).code)
        assertFalse(mapped.outcomeUnknown)
    }

    @Test
    fun `request security attaches credentials only after same origin validation`() {
        val guard = McpRequestOriginGuard("https://MCP.example.test:443/sse")
        val allowed = HttpRequestBuilder().apply { url("https://mcp.example.test/messages?session=one") }

        allowed.applyMcpRequestSecurity(guard, "secret")

        assertEquals("Bearer secret", allowed.headers[HttpHeaders.Authorization])

        listOf(
            "http://mcp.example.test/messages",
            "https://other.example.test/messages",
            "https://mcp.example.test:444/messages",
        ).forEach { target ->
            val blocked = HttpRequestBuilder().apply { url(target) }
            val failure = runCatching {
                blocked.applyMcpRequestSecurity(guard, "must-not-leak")
            }.exceptionOrNull()
            assertTrue("Expected target to be rejected: $target", failure is McpRequestOriginViolationException)
            assertNull(blocked.headers[HttpHeaders.Authorization])
        }
        assertTrue(
            runCatching { guard.requireSameOrigin("not-an-absolute-url") }.exceptionOrNull()
                is McpRequestOriginViolationException,
        )

        val mapped = McpRequestOriginViolationException("blocked")
            .toMcpTransportException(outcomeUnknown = true)
        assertEquals(McpTransportErrorCode.PROTOCOL, mapped.code)
        assertFalse(mapped.recoverable)
        assertFalse(mapped.outcomeUnknown)
    }

    private open class StubSession : McpClientSession {
        override val negotiated = McpNegotiatedInfo(
            transport = McpTransportKind.STREAMABLE_HTTP,
            protocolVersion = "2025-11-25",
            server = McpImplementationInfo(name = "stub", version = "1"),
        )
        override val capabilities = McpServerCapabilities(tools = true)
        override val events = emptyFlow<McpServerEvent>()

        override suspend fun listTools(cursor: String?) = McpPage<McpRemoteTool>(emptyList())
        override suspend fun callTool(name: String, arguments: kotlinx.serialization.json.JsonObject) =
            McpToolCallResult(emptyList())
        override suspend fun listResources(cursor: String?) = McpPage<McpRemoteResource>(emptyList())
        override suspend fun listResourceTemplates(cursor: String?) = McpPage<McpRemoteResourceTemplate>(emptyList())
        override suspend fun readResource(uri: String) = McpReadResourceResult(emptyList())
        override suspend fun listPrompts(cursor: String?) = McpPage<McpRemotePrompt>(emptyList())
        override suspend fun getPrompt(name: String, arguments: Map<String, String>) = McpPromptResult(messages = emptyList())
        override suspend fun complete(request: McpCompletionRequest) = McpCompletionResult(emptyList())
        override suspend fun close() = Unit
    }

    private class ControllableTransport : Transport {
        private var closeCallback: () -> Unit = {}

        override suspend fun start() = Unit
        override suspend fun send(message: JSONRPCMessage, options: TransportSendOptions?) = Unit
        override suspend fun close() = signalClose()
        override fun onClose(block: () -> Unit) {
            closeCallback = block
        }
        override fun onError(block: (Throwable) -> Unit) = Unit
        override fun onMessage(block: suspend (JSONRPCMessage) -> Unit) = Unit

        fun signalClose() = closeCallback()
    }
}
