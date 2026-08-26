package com.palmclaw.mcp

import com.palmclaw.config.McpHttpConfig
import com.palmclaw.config.McpHttpServerConfig
import com.palmclaw.mcp.transport.McpClientSession
import com.palmclaw.mcp.transport.McpCompletionRequest
import com.palmclaw.mcp.transport.McpCompletionResult
import com.palmclaw.mcp.transport.McpImplementationInfo
import com.palmclaw.mcp.transport.McpNegotiatedInfo
import com.palmclaw.mcp.transport.McpPage
import com.palmclaw.mcp.transport.McpPromptResult
import com.palmclaw.mcp.transport.McpReadResourceResult
import com.palmclaw.mcp.transport.McpRemotePrompt
import com.palmclaw.mcp.transport.McpRemoteResource
import com.palmclaw.mcp.transport.McpRemoteResourceTemplate
import com.palmclaw.mcp.transport.McpRemoteTool
import com.palmclaw.mcp.transport.McpServerCapabilities
import com.palmclaw.mcp.transport.McpServerEvent
import com.palmclaw.mcp.transport.McpToolCallResult
import com.palmclaw.mcp.transport.McpTransportClientFactory
import com.palmclaw.mcp.transport.McpTransportConnectRequest
import com.palmclaw.mcp.transport.McpTransportErrorCode
import com.palmclaw.mcp.transport.McpTransportException
import com.palmclaw.mcp.transport.McpTransportKind
import com.palmclaw.tools.ToolRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class McpConnectionRetryTest {
    @Test
    fun `retry policy permits only transient connection failures and is bounded`() {
        val policy = McpConnectionRetryPolicy(
            maxAttempts = 3,
            baseDelayMillis = 100L,
            maxDelayMillis = 1_000L
        )
        val retryable = listOf(
            McpTransportErrorCode.NETWORK,
            McpTransportErrorCode.TIMEOUT,
            McpTransportErrorCode.RATE_LIMITED,
            McpTransportErrorCode.SERVER_ERROR
        )

        retryable.forEach { code ->
            assertEquals(100L, policy.delayBeforeNextAttempt(1, transportFailure(code), jitterUnit = 0.0))
            assertEquals(200L, policy.delayBeforeNextAttempt(2, transportFailure(code), jitterUnit = 0.0))
            assertEquals(null, policy.delayBeforeNextAttempt(3, transportFailure(code), jitterUnit = 0.0))
        }
        assertEquals(
            null,
            policy.delayBeforeNextAttempt(
                1,
                McpTransportException(
                    code = McpTransportErrorCode.NETWORK,
                    message = "not recoverable",
                    recoverable = false
                ),
                jitterUnit = 0.0
            )
        )
        listOf(
            McpTransportErrorCode.AUTHENTICATION_REQUIRED,
            McpTransportErrorCode.PERMISSION_DENIED,
            McpTransportErrorCode.NOT_FOUND,
            McpTransportErrorCode.PROTOCOL,
            McpTransportErrorCode.RESPONSE_TOO_LARGE,
            McpTransportErrorCode.UNSUPPORTED
        ).forEach { code ->
            assertEquals(null, policy.delayBeforeNextAttempt(1, transportFailure(code), jitterUnit = 0.0))
        }
        assertEquals(null, policy.delayBeforeNextAttempt(1, IllegalStateException("unknown"), jitterUnit = 0.0))

        val recovery = McpBackgroundRecoveryPolicy(
            baseDelayMillis = 100L,
            maxDelayMillis = 500L,
            jitterRatio = 0.0
        )
        assertEquals(100L, recovery.delayBeforeAttempt(1, 0.0))
        assertEquals(200L, recovery.delayBeforeAttempt(2, 0.0))
        assertEquals(400L, recovery.delayBeforeAttempt(3, 0.0))
        assertEquals(500L, recovery.delayBeforeAttempt(4, 0.0))
        assertEquals(500L, recovery.delayBeforeAttempt(100, 0.0))
    }

    @Test
    fun `lifecycle retries connect only and stops after third transient failure`(): Unit = runBlocking {
        val attempts = mutableListOf<String>()
        val waits = mutableListOf<Long>()
        val fixture = fixture(
            connect = { request ->
                attempts += request.serverId
                throw transportFailure(McpTransportErrorCode.NETWORK)
            },
            retryDelay = McpRetryDelay { waits += it },
            retryJitter = McpRetryJitter { 0.0 }
        )

        val result = fixture.lifecycle.reconcile(config(server("alpha")))

        assertEquals(listOf("alpha", "alpha", "alpha"), attempts)
        assertEquals(listOf(500L, 1_000L), waits)
        assertEquals(listOf("alpha"), result.failedServerIds)
        fixture.close()
    }

    @Test
    fun `authentication failure is attempted once and tool calls are never replayed`(): Unit = runBlocking {
        var connectAttempts = 0
        var toolCalls = 0
        val authFixture = fixture(connect = {
            connectAttempts += 1
            throw transportFailure(McpTransportErrorCode.AUTHENTICATION_REQUIRED)
        })

        authFixture.lifecycle.reconcile(config(server("auth")))
        assertEquals(1, connectAttempts)
        authFixture.close()

        val toolSession = FakeSession(tool = remoteTool("mutate")).apply {
            callBlock = {
                toolCalls += 1
                throw transportFailure(McpTransportErrorCode.NETWORK, outcomeUnknown = true)
            }
        }
        val toolFixture = fixture(connect = { toolSession })
        toolFixture.lifecycle.reconcile(config(server("tool")))

        val result = toolFixture.registry.get("mcp_tool_mutate")!!.run("{}")

        assertEquals(1, toolCalls)
        assertTrue(result.isError)
        assertTrue(result.metadata!!["outcome_unknown"]!!.jsonPrimitive.content.toBoolean())
        toolFixture.close()
    }

    @Test
    fun `offline external server returns immediately and reconnects when network is available`(): Unit = runBlocking {
        val online = MutableStateFlow(false)
        var connectAttempts = 0
        val fixture = fixture(
            connect = {
                connectAttempts += 1
                FakeSession()
            },
            networkAvailability = StateFlowMcpNetworkAvailability(online)
        )

        val initial = fixture.lifecycle.reconcile(config(server("alpha")))

        assertEquals(0, connectAttempts)
        assertEquals(false, initial.applied)
        assertEquals(McpServerPhase.CONNECTING, initial.snapshot.servers.single().phase)
        assertEquals("Waiting for network", initial.snapshot.servers.single().detail)

        online.value = true
        withTimeout(2_000L) {
            while (connectAttempts != 1 ||
                fixture.lifecycle.snapshot.value.servers.single().phase != McpServerPhase.READY
            ) {
                yield()
            }
        }
        fixture.close()
    }

    @Test
    fun `offline external server does not block a later loopback server`(): Unit = runBlocking {
        val online = MutableStateFlow(false)
        val connected = mutableListOf<String>()
        val fixture = fixture(
            connect = { request ->
                connected += request.serverId
                FakeSession(tool = remoteTool(request.serverId))
            },
            networkAvailability = StateFlowMcpNetworkAvailability(online)
        )

        val result = fixture.lifecycle.reconcile(
            McpHttpConfig(
                enabled = true,
                servers = listOf(
                    server("external"),
                    server("local").copy(serverUrl = "http://127.0.0.1:3000/mcp")
                )
            )
        )

        assertEquals(listOf("local"), connected)
        assertEquals(McpServerPhase.CONNECTING, result.snapshot.servers.first { it.serverId == "external" }.phase)
        assertEquals(McpServerPhase.READY, result.snapshot.servers.first { it.serverId == "local" }.phase)
        assertTrue(fixture.registry.has("mcp_local_local"))
        fixture.close()
    }

    @Test
    fun `close cancels an offline network recovery wait`(): Unit = runBlocking {
        val online = MutableStateFlow(false)
        var connectAttempts = 0
        val fixture = fixture(
            connect = {
                connectAttempts += 1
                FakeSession()
            },
            networkAvailability = StateFlowMcpNetworkAvailability(online)
        )

        fixture.lifecycle.reconcile(config(server("alpha")))
        fixture.lifecycle.close()
        online.value = true
        repeat(20) { yield() }

        assertEquals(0, connectAttempts)
        fixture.scope.cancel()
    }

    @Test
    fun `offline internet signal does not block loopback or private network endpoints`(): Unit = runBlocking {
        val online = MutableStateFlow(false)
        val connected = mutableListOf<String>()
        val fixture = fixture(
            connect = { request ->
                connected += request.endpoint
                FakeSession()
            },
            networkAvailability = StateFlowMcpNetworkAvailability(online)
        )

        val result = fixture.lifecycle.reconcile(
            McpHttpConfig(
                enabled = true,
                servers = listOf(
                    server("local").copy(serverUrl = "http://127.0.0.1:3000/mcp"),
                    server("lan").copy(
                        serverUrl = "http://192.168.1.9:3000/mcp",
                        insecureHttpAllowedOrigin = "http://192.168.1.9:3000"
                    )
                )
            )
        )

        assertTrue(result.applied)
        assertEquals(2, connected.size)
        fixture.close()
    }

    @Test(expected = CancellationException::class)
    fun `cancellation during retry wait propagates and makes no extra attempt`(): Unit = runBlocking {
        var attempts = 0
        val fixture = fixture(
            connect = {
                attempts += 1
                throw transportFailure(McpTransportErrorCode.TIMEOUT)
            },
            retryDelay = McpRetryDelay { throw CancellationException("stop") }
        )

        try {
            fixture.lifecycle.reconcile(config(server("alpha")))
        } finally {
            assertEquals(1, attempts)
            fixture.close()
        }
    }

    @Test
    fun `unexpected session close revokes stale tools and reconnects once`(): Unit = runBlocking {
        val first = FakeSession(tool = remoteTool("old"))
        val second = FakeSession(tool = remoteTool("new"))
        var attempts = 0
        val reconnected = kotlinx.coroutines.CompletableDeferred<Unit>()
        val fixture = fixture(
            connect = {
                attempts += 1
                if (attempts == 1) first else second.also { reconnected.complete(Unit) }
            }
        )
        fixture.lifecycle.reconcile(config(server("alpha")))
        assertTrue(fixture.registry.has("mcp_alpha_old"))
        withTimeout(2_000L) {
            while (first.eventSubscriberCount != 1) yield()
        }

        first.emit(McpServerEvent.Disconnected)
        first.emit(McpServerEvent.ToolsChanged)
        withTimeout(2_000L) { reconnected.await() }
        withTimeout(2_000L) {
            while (first.eventSubscriberCount != 0 || !fixture.registry.has("mcp_alpha_new")) yield()
        }

        assertEquals(2, attempts)
        assertTrue(!fixture.registry.has("mcp_alpha_old"))
        assertTrue(fixture.registry.has("mcp_alpha_new"))
        assertEquals(0, first.eventSubscriberCount)
        fixture.close()
    }

    @Test
    fun `persistent background recovery advances generations until a later success`(): Unit = runBlocking {
        val recoveryGate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val recovered = kotlinx.coroutines.CompletableDeferred<Unit>()
        var attempts = 0
        var recoveryWaits = 0
        val fixture = fixture(
            connect = {
                attempts += 1
                if (attempts <= 9) {
                    throw transportFailure(McpTransportErrorCode.NETWORK)
                }
                FakeSession(tool = remoteTool("recovered")).also { recovered.complete(Unit) }
            },
            retryDelay = McpRetryDelay { },
            recoveryDelay = McpRetryDelay {
                recoveryWaits += 1
                if (recoveryWaits == 1) recoveryGate.await()
            }
        )

        val initial = fixture.lifecycle.reconcile(config(server("alpha")))

        assertEquals(listOf("alpha"), initial.failedServerIds)
        assertEquals(McpServerPhase.ERROR, initial.snapshot.servers.single().phase)
        assertEquals(3, attempts)
        recoveryGate.complete(Unit)
        withTimeout(2_000L) { recovered.await() }
        withTimeout(2_000L) {
            while (!fixture.registry.has("mcp_alpha_recovered")) yield()
        }
        assertEquals(10, attempts)
        assertTrue(fixture.lifecycle.snapshot.value.generation > initial.snapshot.generation + 1)
        assertEquals(McpServerPhase.READY, fixture.lifecycle.snapshot.value.servers.single().phase)
        fixture.close()
    }

    @Test
    fun `background recovery retries only servers with typed transient failures`(): Unit = runBlocking {
        val recoveryGate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val recovered = kotlinx.coroutines.CompletableDeferred<Unit>()
        var authAttempts = 0
        var transientAttempts = 0
        val fixture = fixture(
            connect = { request ->
                when (request.serverId) {
                    "auth" -> {
                        authAttempts += 1
                        throw transportFailure(McpTransportErrorCode.AUTHENTICATION_REQUIRED)
                    }
                    else -> {
                        transientAttempts += 1
                        if (transientAttempts <= 3) {
                            throw transportFailure(McpTransportErrorCode.NETWORK)
                        }
                        FakeSession(tool = remoteTool("recovered")).also { recovered.complete(Unit) }
                    }
                }
            },
            retryDelay = McpRetryDelay { },
            recoveryDelay = McpRetryDelay { recoveryGate.await() }
        )

        fixture.lifecycle.reconcile(
            McpHttpConfig(enabled = true, servers = listOf(server("auth"), server("transient")))
        )
        assertEquals(1, authAttempts)
        assertEquals(3, transientAttempts)

        recoveryGate.complete(Unit)
        withTimeout(2_000L) { recovered.await() }
        withTimeout(2_000L) {
            while (fixture.lifecycle.snapshot.value.servers
                    .first { it.serverId == "transient" }
                    .phase != McpServerPhase.READY
            ) {
                yield()
            }
        }

        assertEquals(1, authAttempts)
        assertEquals(4, transientAttempts)
        assertEquals(McpServerPhase.ERROR, fixture.lifecycle.snapshot.value.servers.first { it.serverId == "auth" }.phase)
        assertEquals(McpServerPhase.READY, fixture.lifecycle.snapshot.value.servers.first { it.serverId == "transient" }.phase)
        fixture.close()
    }

    @Test
    fun `explicit reconcile supersedes pending recovery without duplicate connect`(): Unit = runBlocking {
        val recoveryDelayStarted = kotlinx.coroutines.CompletableDeferred<Unit>()
        val neverReleaseRecovery = kotlinx.coroutines.CompletableDeferred<Unit>()
        var attempts = 0
        val fixture = fixture(
            connect = {
                attempts += 1
                if (attempts <= 3) throw transportFailure(McpTransportErrorCode.NETWORK)
                FakeSession(tool = remoteTool("manual"))
            },
            retryDelay = McpRetryDelay { },
            recoveryDelay = McpRetryDelay {
                recoveryDelayStarted.complete(Unit)
                neverReleaseRecovery.await()
            }
        )
        val desired = config(server("alpha"))
        fixture.lifecycle.reconcile(desired)
        withTimeout(2_000L) { recoveryDelayStarted.await() }

        val manual = fixture.lifecycle.reconcile(desired)
        repeat(20) { yield() }

        assertTrue(manual.applied)
        assertEquals(4, attempts)
        assertTrue(fixture.registry.has("mcp_alpha_manual"))
        fixture.close()
    }

    @Test
    fun `disable cancels pending background recovery`(): Unit = runBlocking {
        val recoveryDelayStarted = kotlinx.coroutines.CompletableDeferred<Unit>()
        val neverReleaseRecovery = kotlinx.coroutines.CompletableDeferred<Unit>()
        var attempts = 0
        val fixture = fixture(
            connect = {
                attempts += 1
                throw transportFailure(McpTransportErrorCode.NETWORK)
            },
            retryDelay = McpRetryDelay { },
            recoveryDelay = McpRetryDelay {
                recoveryDelayStarted.complete(Unit)
                neverReleaseRecovery.await()
            }
        )
        fixture.lifecycle.reconcile(config(server("alpha")))
        withTimeout(2_000L) { recoveryDelayStarted.await() }

        fixture.lifecycle.reconcile(McpHttpConfig(enabled = false))
        neverReleaseRecovery.complete(Unit)
        repeat(20) { yield() }

        assertEquals(3, attempts)
        assertEquals(false, fixture.lifecycle.snapshot.value.enabled)
        fixture.close()
    }

    private fun fixture(
        connect: suspend (McpTransportConnectRequest) -> McpClientSession,
        networkAvailability: McpNetworkAvailability = AlwaysOnlineMcpNetworkAvailability,
        retryDelay: McpRetryDelay = McpRetryDelay { },
        retryJitter: McpRetryJitter = McpRetryJitter { 0.0 },
        recoveryDelay: McpRetryDelay = McpRetryDelay { awaitCancellation() }
    ): Fixture {
        val scope = CoroutineScope(Dispatchers.Default)
        val registry = ToolRegistry(emptyMap(), debugLog = {})
        return Fixture(
            lifecycle = DefaultMcpRuntimeLifecycle(
                transportFactory = object : McpTransportClientFactory {
                    override suspend fun connect(request: McpTransportConnectRequest): McpClientSession = connect(request)
                },
                toolRegistry = registry,
                parentScope = scope,
                networkAvailability = networkAvailability,
                retryDelay = retryDelay,
                retryJitter = retryJitter,
                recoveryDelay = recoveryDelay
            ),
            registry = registry,
            scope = scope
        )
    }

    private fun transportFailure(
        code: McpTransportErrorCode,
        outcomeUnknown: Boolean = false
    ) = McpTransportException(
        code = code,
        message = code.name,
        recoverable = true,
        outcomeUnknown = outcomeUnknown
    )

    private fun config(server: McpHttpServerConfig) = McpHttpConfig(enabled = true, servers = listOf(server))

    private fun server(id: String) = McpHttpServerConfig(
        id = id,
        serverName = id,
        serverUrl = "https://$id.example/mcp"
    )

    private fun remoteTool(name: String) = McpRemoteTool(
        name = name,
        inputSchema = buildJsonObject { put("type", "object") }
    )

    private data class Fixture(
        val lifecycle: DefaultMcpRuntimeLifecycle,
        val registry: ToolRegistry,
        val scope: CoroutineScope
    ) {
        fun close() {
            lifecycle.close()
            scope.cancel()
        }
    }

    private class FakeSession(
        private val tool: McpRemoteTool? = null
    ) : McpClientSession {
        override val negotiated = McpNegotiatedInfo(
            McpTransportKind.STREAMABLE_HTTP,
            "2025-06-18",
            McpImplementationInfo("fake", "1")
        )
        override val capabilities = McpServerCapabilities(tools = tool != null)
        private val mutableEvents = MutableSharedFlow<McpServerEvent>(replay = 1, extraBufferCapacity = 8)
        override val events: Flow<McpServerEvent> = mutableEvents
        val eventSubscriberCount: Int
            get() = mutableEvents.subscriptionCount.value
        var callBlock: suspend () -> McpToolCallResult = { McpToolCallResult(emptyList()) }

        fun emit(event: McpServerEvent) {
            mutableEvents.tryEmit(event)
        }

        override suspend fun listTools(cursor: String?) = McpPage(listOfNotNull(tool))
        override suspend fun callTool(name: String, arguments: JsonObject) = callBlock()
        override suspend fun listResources(cursor: String?) = McpPage<McpRemoteResource>(emptyList())
        override suspend fun listResourceTemplates(cursor: String?) = McpPage<McpRemoteResourceTemplate>(emptyList())
        override suspend fun readResource(uri: String) = McpReadResourceResult(emptyList())
        override suspend fun listPrompts(cursor: String?) = McpPage<McpRemotePrompt>(emptyList())
        override suspend fun getPrompt(name: String, arguments: Map<String, String>) = McpPromptResult(messages = emptyList())
        override suspend fun complete(request: McpCompletionRequest) = McpCompletionResult(emptyList())
        override suspend fun close() = Unit
    }
}
