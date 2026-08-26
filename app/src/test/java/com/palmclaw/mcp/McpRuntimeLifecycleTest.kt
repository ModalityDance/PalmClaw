package com.palmclaw.mcp

import com.palmclaw.config.McpHttpConfig
import com.palmclaw.config.McpHttpServerConfig
import com.palmclaw.mcp.transport.McpClientSession
import com.palmclaw.mcp.transport.McpCompletionRequest
import com.palmclaw.mcp.transport.McpCompletionResult
import com.palmclaw.mcp.transport.McpContentBlock
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
import com.palmclaw.mcp.transport.McpTransportConnectRequest
import com.palmclaw.mcp.transport.McpTransportKind
import com.palmclaw.mcp.transport.McpTransportClientFactory
import com.palmclaw.tools.ToolRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class McpRuntimeLifecycleTest {
    @Test
    fun `reconcile publishes remote tools and complete typed status`(): Unit = runBlocking {
        val session = FakeSession(
            capabilities = McpServerCapabilities(
                tools = true,
                resources = true,
                prompts = true,
                completions = true
            ),
            tools = listOf(remoteTool("Search Web")),
            resources = listOf(McpRemoteResource(uri = "docs://one", name = "one")),
            templates = listOf(McpRemoteResourceTemplate(uriTemplate = "docs://{id}", name = "docs")),
            prompts = listOf(McpRemotePrompt(name = "summarize"))
        )
        val fixture = fixture { session }

        val result = fixture.lifecycle.reconcile(config(server("alpha", "Alpha Server")))

        assertTrue(result.applied)
        assertEquals(listOf("mcp_alpha_server_search_web"), fixture.registry.toolNames().filter { it.startsWith("mcp_alpha") })
        assertTrue(fixture.registry.has("mcp_content"))
        val status = result.snapshot.servers.single()
        assertEquals(McpServerPhase.READY, status.phase)
        assertTrue(status.usable)
        assertEquals(1, status.toolCount)
        assertEquals(1, status.resourceCount)
        assertEquals(1, status.resourceTemplateCount)
        assertEquals(1, status.promptCount)
        assertTrue(status.completionSupported)
        assertEquals(McpTransportKind.STREAMABLE_HTTP, status.transport)
        assertEquals("2025-06-18", status.protocolVersion)
        assertEquals(McpEndpointSecurity.HTTPS, status.endpointSecurity)
        assertNull(status.insecureWarning)
        fixture.close()
    }

    @Test
    fun `connected server without agent capabilities is ready but not usable`(): Unit = runBlocking {
        val fixture = fixture {
            FakeSession(capabilities = McpServerCapabilities())
        }

        val status = fixture.lifecycle
            .reconcile(config(server("empty", "empty")))
            .snapshot
            .servers
            .single()

        assertEquals(McpServerPhase.READY, status.phase)
        assertFalse(status.usable)
        assertEquals(0, status.toolCount)
        assertFalse(fixture.registry.has("mcp_content"))
        fixture.close()
    }

    @Test
    fun `long remote tool names are bounded and retain deterministic identity`(): Unit = runBlocking {
        val commonPrefix = "very_long_remote_operation_".repeat(4)
        val session = FakeSession(
            tools = listOf(
                remoteTool(commonPrefix + "alpha"),
                remoteTool(commonPrefix + "beta")
            )
        )
        val fixture = fixture { session }

        val result = fixture.lifecycle.reconcile(
            config(server("long-server", "long_server_name_".repeat(3)))
        )
        val published = result.snapshot.servers.single().toolNames

        assertTrue(result.applied)
        assertEquals(2, published.distinct().size)
        assertTrue(published.all { it.length <= 64 })
        val normalizedServer = DefaultMcpRuntimeLifecycle.normalizedServerName("long_server_name_".repeat(3))
        val expectedAlpha = McpPublishedToolName.canonicalize(normalizedServer, commonPrefix + "alpha")
        assertTrue(expectedAlpha in published)
        assertTrue(fixture.registry.has(expectedAlpha))
        fixture.close()
    }

    @Test
    fun `normalized remote tool name collision fails atomically`(): Unit = runBlocking {
        val session = FakeSession(
            tools = listOf(remoteTool("read value"), remoteTool("read@value"))
        )
        val fixture = fixture { session }

        val result = fixture.lifecycle.reconcile(config(server("alpha", "alpha")))

        assertFalse(result.applied)
        assertEquals(McpServerPhase.ERROR, result.snapshot.servers.single().phase)
        assertTrue(result.snapshot.servers.single().detail.orEmpty().contains("conflict", ignoreCase = true))
        assertEquals("remote_tool_name_conflict", result.snapshot.issues.single().code)
        assertFalse(fixture.registry.has("mcp_alpha_read_value"))
        assertTrue(session.closed)
        fixture.close()
    }

    @Test
    fun `unchanged server reuses its connected session`(): Unit = runBlocking {
        val session = FakeSession(tools = listOf(remoteTool("ping")))
        var connects = 0
        val fixture = fixture {
            connects += 1
            session
        }
        val desired = config(server("alpha", "alpha"))

        fixture.lifecycle.reconcile(desired)
        val second = fixture.lifecycle.reconcile(desired)

        assertEquals(1, connects)
        assertEquals(listOf("alpha"), second.reusedServerIds)
        assertFalse(session.closed)
        fixture.close()
    }

    @Test
    fun `query only endpoint change replaces the session and status identity`(): Unit = runBlocking {
        val firstSession = FakeSession(tools = listOf(remoteTool("first")))
        val secondSession = FakeSession(tools = listOf(remoteTool("second")))
        var connects = 0
        val fixture = fixture {
            connects += 1
            if (connects == 1) firstSession else secondSession
        }

        val first = fixture.lifecycle.reconcile(
            config(server("alpha", "alpha", "https://mcp.example/rpc?tenant=one"))
        )
        val second = fixture.lifecycle.reconcile(
            config(server("alpha", "alpha", "https://mcp.example/rpc?tenant=two"))
        )

        assertEquals(2, connects)
        assertTrue(firstSession.closed)
        assertEquals(emptyList<String>(), second.reusedServerIds)
        assertEquals("https://mcp.example/rpc", first.snapshot.servers.single().endpoint)
        assertEquals("https://mcp.example/rpc", second.snapshot.servers.single().endpoint)
        assertNotEquals(
            first.snapshot.servers.single().configFingerprint,
            second.snapshot.servers.single().configFingerprint
        )
        fixture.close()
    }

    @Test
    fun `changed server failure removes tools granted by the previous config`(): Unit = runBlocking {
        val oldSession = FakeSession(tools = listOf(remoteTool("write")))
        var connectIndex = 0
        val fixture = fixture {
            connectIndex += 1
            if (connectIndex == 1) oldSession else error("cannot connect")
        }
        fixture.lifecycle.reconcile(config(server("alpha", "alpha", url = "https://old.example/mcp")))
        assertTrue(fixture.registry.has("mcp_alpha_write"))

        val result = fixture.lifecycle.reconcile(
            config(server("alpha", "alpha", url = "https://new.example/mcp"))
        )

        assertFalse(fixture.registry.has("mcp_alpha_write"))
        assertTrue(oldSession.closed)
        assertEquals(McpServerPhase.ERROR, result.snapshot.servers.single().phase)
        assertFalse(result.snapshot.servers.single().usable)
        fixture.close()
    }

    @Test
    fun `servers fail independently and successful server remains usable`(): Unit = runBlocking {
        val good = FakeSession(tools = listOf(remoteTool("read")))
        val fixture = fixture { request ->
            if (request.serverId == "good") good else error("offline")
        }

        val result = fixture.lifecycle.reconcile(
            config(
                server("good", "good", "https://good.example/mcp"),
                server("bad", "bad", "https://bad.example/mcp")
            )
        )

        assertTrue(fixture.registry.has("mcp_good_read"))
        assertEquals(McpServerPhase.READY, result.snapshot.servers.first { it.serverId == "good" }.phase)
        assertEquals(McpServerPhase.ERROR, result.snapshot.servers.first { it.serverId == "bad" }.phase)
        fixture.close()
    }

    @Test
    fun `endpoint requiring action never reaches the transport`(): Unit = runBlocking {
        var connects = 0
        val fixture = fixture {
            connects += 1
            FakeSession()
        }

        val result = fixture.lifecycle.reconcile(
            config(server("lan", "lan", "http://192.168.1.20:8080/mcp"))
        )

        assertEquals(0, connects)
        val status = result.snapshot.servers.single()
        assertEquals(McpServerPhase.ACTION_REQUIRED, status.phase)
        assertEquals(McpEndpointSecurity.PRIVATE_LAN_HTTP, status.endpointSecurity)
        assertFalse(status.usable)
        fixture.close()
    }

    @Test
    fun `disabled and rejected endpoint status never exposes URL credentials`(): Unit = runBlocking {
        var connects = 0
        val fixture = fixture {
            connects += 1
            FakeSession()
        }
        val secretUrl = "https://user:password@example.com/mcp?access_token=secret#private"
        val configured = server("secret", "secret", secretUrl)

        val disabled = fixture.lifecycle.reconcile(
            McpHttpConfig(enabled = false, servers = listOf(configured))
        ).snapshot.servers.single()
        val rejected = fixture.lifecycle.reconcile(config(configured)).snapshot.servers.single()

        assertEquals("https://example.com/mcp", disabled.endpoint)
        assertEquals("https://example.com/mcp", rejected.endpoint)
        listOf("user", "password", "access_token", "secret", "private").forEach { credential ->
            assertFalse(disabled.endpoint.contains(credential))
            assertFalse(rejected.endpoint.contains(credential))
        }
        assertEquals(McpServerPhase.ERROR, rejected.phase)
        assertEquals(0, connects)
        fixture.close()
    }

    @Test
    fun `remote tool preserves content structured result and annotations`(): Unit = runBlocking {
        val tool = remoteTool("mutate").copy(
            annotations = com.palmclaw.mcp.transport.McpToolAnnotations(
                readOnlyHint = false,
                destructiveHint = true,
                idempotentHint = false,
                openWorldHint = true
            )
        )
        val session = FakeSession(tools = listOf(tool)).apply {
            callResult = McpToolCallResult(
                content = listOf(
                    McpContentBlock.Text("done"),
                    McpContentBlock.ResourceLink(uri = "docs://changed", name = "changed"),
                    McpContentBlock.Image(data = "SECRET_BASE64", mimeType = "image/png")
                ),
                structuredContent = buildJsonObject { put("changed", true) },
                meta = buildJsonObject { put("trace", "safe") }
            )
        }
        val fixture = fixture { session }
        fixture.lifecycle.reconcile(config(server("alpha", "alpha")))

        val result = fixture.registry.get("mcp_alpha_mutate")!!.run("{\"id\":1}")

        assertFalse(result.isError)
        assertTrue(result.content.contains("done"))
        assertTrue(result.content.contains("Structured content: {\"changed\":true}"))
        assertEquals(true, result.metadata!!["mcp_has_structured_content"]!!.jsonPrimitive.content.toBoolean())
        assertEquals(
            "resource_link",
            result.metadata!!["mcp_content_types"]!!.jsonArray[1].jsonPrimitive.content
        )
        val annotations = result.metadata!!["mcp_tool_annotations"]!!.jsonObject
        assertEquals(true, annotations["destructive"]!!.jsonPrimitive.content.toBoolean())
        assertEquals(false, annotations["read_only"]!!.jsonPrimitive.content.toBoolean())
        assertFalse(result.content.contains("SECRET_BASE64"))
        assertTrue(result.content.contains("MCP meta: {\"trace\":\"safe\"}"))
        assertFalse(result.metadata.toString().contains("SECRET_BASE64"))
        assertFalse(result.metadata.toString().contains("safe"))
        fixture.close()
    }

    @Test(expected = CancellationException::class)
    fun `remote tool propagates cancellation`(): Unit = runBlocking {
        val session = FakeSession(tools = listOf(remoteTool("wait"))).apply {
            callFailure = CancellationException("cancelled")
        }
        val fixture = fixture { session }
        fixture.lifecycle.reconcile(config(server("alpha", "alpha")))

        try {
            fixture.registry.get("mcp_alpha_wait")!!.run("{}")
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `mcp content selects a unique server and exposes resources`(): Unit = runBlocking {
        val first = FakeSession(
            capabilities = McpServerCapabilities(resources = true),
            resources = listOf(McpRemoteResource(uri = "docs://a", name = "A"))
        )
        val second = FakeSession(
            capabilities = McpServerCapabilities(resources = true),
            resources = listOf(McpRemoteResource(uri = "docs://b", name = "B"))
        )
        val fixture = fixture { request -> if (request.serverId == "a") first else second }
        fixture.lifecycle.reconcile(
            config(
                server("a", "shared", "https://a.example/mcp"),
                server("b", "other", "https://b.example/mcp")
            )
        )

        val byId = fixture.registry.get("mcp_content")!!.run(
            "{\"action\":\"list_resources\",\"server_id\":\"a\"}"
        )
        val missingSelector = fixture.registry.get("mcp_content")!!.run(
            "{\"action\":\"list_resources\"}"
        )

        assertFalse(byId.isError)
        assertTrue(byId.content.contains("docs://a"))
        assertFalse(byId.metadata.toString().contains("docs://a"))
        assertTrue(missingSelector.isError)
        assertEquals("server_required", missingSelector.metadata!!["error"]!!.jsonPrimitive.content)
        fixture.close()
    }

    @Test
    fun `mcp content list actions forward cursor and return one page`(): Unit = runBlocking {
        val session = FakeSession(
            capabilities = McpServerCapabilities(resources = true, prompts = true),
            resources = listOf(McpRemoteResource(uri = "docs://next", name = "next")),
            templates = listOf(McpRemoteResourceTemplate(uriTemplate = "docs://{id}", name = "docs")),
            prompts = listOf(McpRemotePrompt(name = "next_prompt"))
        ).apply {
            pageNextCursor = "page-3"
        }
        val fixture = fixture { session }
        fixture.lifecycle.reconcile(config(server("alpha", "alpha")))
        session.receivedCursors.clear()
        session.listResourcesCalls = 0
        session.listTemplateCalls = 0
        session.listPromptCalls = 0

        val resources = fixture.registry.get("mcp_content")!!.run(
            "{\"action\":\"list_resources\",\"server_id\":\"alpha\",\"cursor\":\"page-2\"}"
        )
        val templates = fixture.registry.get("mcp_content")!!.run(
            "{\"action\":\"list_resource_templates\",\"server_id\":\"alpha\",\"cursor\":\"template-2\"}"
        )
        val prompts = fixture.registry.get("mcp_content")!!.run(
            "{\"action\":\"list_prompts\",\"server_id\":\"alpha\",\"cursor\":\"prompt-2\"}"
        )

        assertEquals(
            listOf("resources:page-2", "templates:template-2", "prompts:prompt-2"),
            session.receivedCursors
        )
        assertTrue(resources.content.contains("\"next_cursor\":\"page-3\""))
        assertTrue(templates.content.contains("\"next_cursor\":\"page-3\""))
        assertTrue(prompts.content.contains("\"next_cursor\":\"page-3\""))
        assertEquals(1, session.listResourcesCalls)
        assertEquals(1, session.listTemplateCalls)
        assertEquals(1, session.listPromptCalls)
        fixture.close()
    }

    @Test
    fun `disabled reconcile and close remove only lifecycle owned tools`(): Unit = runBlocking {
        val fixture = fixture { FakeSession(tools = listOf(remoteTool("ping"))) }
        val unrelated = object : com.palmclaw.tools.Tool {
            override val name = "mcp_user_defined"
            override val description = "unrelated"
            override val jsonSchema = buildJsonObject { put("type", "object") }
            override suspend fun run(argumentsJson: String) = com.palmclaw.tools.ToolResult("", "ok", false)
        }
        fixture.registry.register(unrelated)
        fixture.lifecycle.reconcile(config(server("alpha", "alpha")))

        fixture.lifecycle.reconcile(McpHttpConfig(enabled = false))

        assertTrue(fixture.registry.has("mcp_user_defined"))
        assertFalse(fixture.registry.has("mcp_alpha_ping"))
        assertFalse(fixture.registry.has("mcp_content"))
        fixture.lifecycle.close()
        assertTrue(fixture.registry.has("mcp_user_defined"))
        fixture.scope.cancel()
    }

    @Test
    fun `cancelled multi server reconcile revokes new sessions but preserves reused sessions`(): Unit = runBlocking {
        val reused = FakeSession(tools = listOf(remoteTool("stable")))
        val newSession = FakeSession(tools = listOf(remoteTool("new")))
        val blocked = CompletableDeferred<Unit>()
        var initial = true
        val fixture = fixture { request ->
            if (initial) {
                reused
            } else when (request.serverId) {
                "new" -> newSession
                else -> {
                    blocked.await()
                    error("unreachable")
                }
            }
        }
        fixture.lifecycle.reconcile(config(server("stable", "stable")))
        initial = false

        val pending = async {
            fixture.lifecycle.reconcile(
                config(
                    server("stable", "stable"),
                    server("new", "new"),
                    server("blocked", "blocked")
                )
            )
        }
        while (!fixture.registry.has("mcp_new_new")) {
            kotlinx.coroutines.yield()
        }
        pending.cancel()
        runCatching { pending.await() }

        assertTrue(newSession.closed)
        assertFalse(fixture.registry.has("mcp_new_new"))
        assertFalse(reused.closed)
        assertTrue(fixture.registry.has("mcp_stable_stable"))
        fixture.close()
    }

    @Test
    fun `close remains the final snapshot when an in flight reconcile completes late`(): Unit = runBlocking {
        val connectStarted = CompletableDeferred<Unit>()
        val releaseConnect = CompletableDeferred<Unit>()
        val lateSession = FakeSession(tools = listOf(remoteTool("late")))
        val fixture = fixture {
            connectStarted.complete(Unit)
            releaseConnect.await()
            lateSession
        }

        val pending = async {
            runCatching { fixture.lifecycle.reconcile(config(server("late", "late"))) }
        }
        connectStarted.await()

        fixture.lifecycle.close()
        releaseConnect.complete(Unit)
        pending.await()

        assertFalse(fixture.lifecycle.snapshot.value.enabled)
        assertFalse(fixture.lifecycle.snapshot.value.servers.any { it.phase == McpServerPhase.CONNECTING })
        assertFalse(fixture.registry.has("mcp_late_late"))
        assertTrue(lateSession.closed)
        fixture.scope.cancel()
    }

    @Test
    fun `close wins over a capability refresh that completes late`(): Unit = runBlocking {
        val refreshStarted = CompletableDeferred<Unit>()
        val releaseRefresh = CompletableDeferred<Unit>()
        val session = FakeSession(tools = listOf(remoteTool("old"))).apply {
            listToolsBlock = {
                if (listToolsCalls == 1) {
                    McpPage(listOf(remoteTool("old")))
                } else {
                    refreshStarted.complete(Unit)
                    withContext(NonCancellable) { releaseRefresh.await() }
                    McpPage(listOf(remoteTool("late")))
                }
            }
        }
        val fixture = fixture { session }
        fixture.lifecycle.reconcile(config(server("alpha", "alpha")))
        withTimeout(2_000L) {
            while (session.eventSubscriberCount != 1) yield()
        }

        session.emit(McpServerEvent.ToolsChanged)
        withTimeout(2_000L) { refreshStarted.await() }
        fixture.lifecycle.close()
        releaseRefresh.complete(Unit)
        repeat(20) { kotlinx.coroutines.yield() }

        assertFalse(fixture.registry.has("mcp_alpha_old"))
        assertFalse(fixture.registry.has("mcp_alpha_late"))
        assertFalse(fixture.lifecycle.snapshot.value.enabled)
        fixture.scope.cancel()
    }

    @Test
    fun `close prevents a delayed disconnect from republishing content tools`(): Unit = runBlocking {
        val disconnectedSession = FakeSession(
            capabilities = McpServerCapabilities(tools = true, resources = true),
            tools = listOf(remoteTool("old"))
        )
        val remainingSession = FakeSession(
            capabilities = McpServerCapabilities(tools = true, resources = true),
            tools = listOf(remoteTool("remaining"))
        )
        val fixture = fixture { request ->
            if (request.serverId == "old") disconnectedSession else remainingSession
        }
        fixture.lifecycle.reconcile(
            config(server("old", "old"), server("remaining", "remaining"))
        )
        withTimeout(2_000L) {
            while (disconnectedSession.eventSubscriberCount != 1) yield()
        }

        val contentPublicationStarted = CountDownLatch(1)
        val releaseContentPublication = CountDownLatch(1)
        remainingSession.capabilitiesReadBlock = {
            contentPublicationStarted.countDown()
            check(releaseContentPublication.await(2, TimeUnit.SECONDS))
        }

        disconnectedSession.emit(McpServerEvent.Disconnected)
        assertTrue(contentPublicationStarted.await(2, TimeUnit.SECONDS))
        val closing = async(Dispatchers.Default) { fixture.lifecycle.close() }
        runCatching { withTimeout(250L) { closing.join() } }
        releaseContentPublication.countDown()
        closing.await()
        repeat(20) { yield() }

        assertFalse(fixture.registry.has("mcp_content"))
        assertFalse(fixture.registry.toolNames().any { it.startsWith("mcp_old_") })
        assertFalse(fixture.registry.toolNames().any { it.startsWith("mcp_remaining_") })
        val finalSnapshot = fixture.lifecycle.snapshot.value
        assertFalse(finalSnapshot.enabled)
        assertEquals(listOf(McpServerPhase.DISABLED), finalSnapshot.servers.map { it.phase })
        fixture.scope.cancel()
    }

    @Test
    fun `late disconnect recovery cannot restore an older explicit configuration`(): Unit = runBlocking {
        val oldCloseStarted = CompletableDeferred<Unit>()
        val releaseOldClose = CompletableDeferred<Unit>()
        val oldSession = FakeSession(tools = listOf(remoteTool("old"))).apply {
            closeBlock = {
                oldCloseStarted.complete(Unit)
                releaseOldClose.await()
            }
        }
        val newSession = FakeSession(tools = listOf(remoteTool("new")))
        val endpoints = mutableListOf<String>()
        val fixture = fixture { request ->
            endpoints += request.endpoint
            if (request.endpoint.contains("old.example")) oldSession else newSession
        }
        fixture.lifecycle.reconcile(
            config(server("alpha", "alpha", "https://old.example/mcp"))
        )
        withTimeout(2_000L) {
            while (oldSession.eventSubscriberCount != 1) yield()
        }

        oldSession.emit(McpServerEvent.Disconnected)
        withTimeout(2_000L) { oldCloseStarted.await() }
        val replacement = fixture.lifecycle.reconcile(
            config(server("alpha", "alpha", "https://new.example/mcp"))
        )
        releaseOldClose.complete(Unit)
        repeat(20) { yield() }

        assertTrue(replacement.applied)
        assertEquals(listOf("https://old.example/mcp", "https://new.example/mcp"), endpoints)
        assertEquals("https://new.example/mcp", fixture.lifecycle.snapshot.value.servers.single().endpoint)
        assertFalse(fixture.registry.has("mcp_alpha_old"))
        assertTrue(fixture.registry.has("mcp_alpha_new"))
        fixture.close()
    }

    private fun fixture(connect: suspend (McpTransportConnectRequest) -> McpClientSession): Fixture {
        val scope = CoroutineScope(Dispatchers.Default)
        val registry = ToolRegistry(emptyMap(), debugLog = {})
        return Fixture(
            lifecycle = DefaultMcpRuntimeLifecycle(
                transportFactory = object : McpTransportClientFactory {
                    override suspend fun connect(request: McpTransportConnectRequest): McpClientSession = connect(request)
                },
                toolRegistry = registry,
                parentScope = scope
            ),
            registry = registry,
            scope = scope
        )
    }

    private fun config(vararg servers: McpHttpServerConfig) = McpHttpConfig(
        enabled = true,
        servers = servers.toList()
    )

    private fun server(
        id: String,
        name: String,
        url: String = "https://mcp.example/$id"
    ) = McpHttpServerConfig(id = id, serverName = name, serverUrl = url)

    private fun remoteTool(name: String) = McpRemoteTool(
        name = name,
        description = "Remote $name",
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
        capabilities: McpServerCapabilities = McpServerCapabilities(tools = true),
        private val tools: List<McpRemoteTool> = emptyList(),
        private val resources: List<McpRemoteResource> = emptyList(),
        private val templates: List<McpRemoteResourceTemplate> = emptyList(),
        private val prompts: List<McpRemotePrompt> = emptyList()
    ) : McpClientSession {
        private val configuredCapabilities = capabilities

        override val capabilities: McpServerCapabilities
            get() {
                capabilitiesReadBlock?.invoke()
                return configuredCapabilities
            }

        override val negotiated = McpNegotiatedInfo(
            transport = McpTransportKind.STREAMABLE_HTTP,
            protocolVersion = "2025-06-18",
            server = McpImplementationInfo("fake", "1")
        )
        private val eventFlow = MutableSharedFlow<McpServerEvent>(extraBufferCapacity = 8)
        override val events: Flow<McpServerEvent> = eventFlow
        val eventSubscriberCount: Int
            get() = eventFlow.subscriptionCount.value
        var callResult = McpToolCallResult(listOf(McpContentBlock.Text("ok")))
        var callFailure: Throwable? = null
        var closed = false
        var pageNextCursor: String? = null
        var listResourcesCalls = 0
        var listTemplateCalls = 0
        var listPromptCalls = 0
        var listToolsCalls = 0
        var listToolsBlock: (suspend () -> McpPage<McpRemoteTool>)? = null
        var closeBlock: suspend () -> Unit = {}
        var capabilitiesReadBlock: (() -> Unit)? = null
        val receivedCursors = mutableListOf<String>()

        fun emit(event: McpServerEvent) {
            eventFlow.tryEmit(event)
        }

        override suspend fun listTools(cursor: String?): McpPage<McpRemoteTool> {
            listToolsCalls += 1
            return listToolsBlock?.invoke() ?: McpPage(tools)
        }
        override suspend fun callTool(name: String, arguments: kotlinx.serialization.json.JsonObject): McpToolCallResult {
            callFailure?.let { throw it }
            return callResult
        }
        override suspend fun listResources(cursor: String?): McpPage<McpRemoteResource> {
            listResourcesCalls += 1
            if (cursor != null) receivedCursors += "resources:$cursor"
            return McpPage(resources, nextCursor = if (cursor == null) null else pageNextCursor)
        }
        override suspend fun listResourceTemplates(cursor: String?): McpPage<McpRemoteResourceTemplate> {
            listTemplateCalls += 1
            if (cursor != null) receivedCursors += "templates:$cursor"
            return McpPage(templates, nextCursor = if (cursor == null) null else pageNextCursor)
        }
        override suspend fun readResource(uri: String) = McpReadResourceResult(emptyList())
        override suspend fun listPrompts(cursor: String?): McpPage<McpRemotePrompt> {
            listPromptCalls += 1
            if (cursor != null) receivedCursors += "prompts:$cursor"
            return McpPage(prompts, nextCursor = if (cursor == null) null else pageNextCursor)
        }
        override suspend fun getPrompt(name: String, arguments: Map<String, String>) = McpPromptResult(messages = emptyList())
        override suspend fun complete(request: McpCompletionRequest) = McpCompletionResult(emptyList())
        override suspend fun close() {
            closeBlock()
            closed = true
        }
    }
}
