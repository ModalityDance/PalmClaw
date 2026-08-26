package com.palmclaw.runtime.control

import com.palmclaw.bus.MessageAttachment
import com.palmclaw.bus.OutboundMessage
import com.palmclaw.channels.ChannelBindingRuntimeProjector
import com.palmclaw.channels.ChannelRuntimeSnapshot
import com.palmclaw.channels.ChannelRuntimeSnapshotSource
import com.palmclaw.channels.EmailAddressValidator
import com.palmclaw.config.AppConfig
import com.palmclaw.config.AppLimits
import com.palmclaw.config.ChannelsConfig
import com.palmclaw.config.HeartbeatConfig
import com.palmclaw.config.McpHttpConfig
import com.palmclaw.config.McpHttpServerConfig
import com.palmclaw.config.SessionChannelBinding
import com.palmclaw.mcp.McpEndpointPolicy
import com.palmclaw.storage.entities.SessionEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeControlServiceTest {

    @Test
    fun `runtime update preserves omitted values and validates supplied values`() {
        val persistence = FakePersistence()
        val service = createService(persistence)

        val result = service.updateRuntimeSettings(
            RuntimeSettingsUpdate(maxToolRounds = 42, contextMessages = 80)
        )

        assertEquals(42, result.maxToolRounds)
        assertEquals(80, result.contextMessages)
        assertEquals(5_000, result.toolResultMaxChars)
        assertEquals(42, persistence.storedAppConfig.maxToolRounds)
        assertThrows(IllegalArgumentException::class.java) {
            service.updateRuntimeSettings(RuntimeSettingsUpdate(maxToolRounds = 0))
        }.also { error ->
            assertEquals("Max tool rounds must be between 1 and 100", error.message)
        }
    }

    @Test
    fun `runtime update accepts every numeric boundary and rejects values outside them`() {
        val cases = listOf(
            RuntimeBoundary(
                AppLimits.MIN_MAX_TOOL_ROUNDS,
                AppLimits.MAX_MAX_TOOL_ROUNDS,
                { RuntimeSettingsUpdate(maxToolRounds = it) },
                RuntimeSettingsSnapshot::maxToolRounds
            ),
            RuntimeBoundary(
                AppLimits.MIN_TOOL_RESULT_MAX_CHARS,
                AppLimits.MAX_TOOL_RESULT_MAX_CHARS,
                { RuntimeSettingsUpdate(toolResultMaxChars = it) },
                RuntimeSettingsSnapshot::toolResultMaxChars
            ),
            RuntimeBoundary(
                AppLimits.MIN_MEMORY_CONSOLIDATION_WINDOW,
                AppLimits.MAX_MEMORY_CONSOLIDATION_WINDOW,
                { RuntimeSettingsUpdate(memoryConsolidationWindow = it) },
                RuntimeSettingsSnapshot::memoryConsolidationWindow
            ),
            RuntimeBoundary(
                AppLimits.MIN_LLM_CALL_TIMEOUT_SECONDS,
                AppLimits.MAX_LLM_CALL_TIMEOUT_SECONDS,
                { RuntimeSettingsUpdate(llmCallTimeoutSeconds = it) },
                RuntimeSettingsSnapshot::llmCallTimeoutSeconds
            ),
            RuntimeBoundary(
                AppLimits.MIN_LLM_CONNECT_TIMEOUT_SECONDS,
                AppLimits.MAX_LLM_CONNECT_TIMEOUT_SECONDS,
                { RuntimeSettingsUpdate(llmConnectTimeoutSeconds = it) },
                RuntimeSettingsSnapshot::llmConnectTimeoutSeconds
            ),
            RuntimeBoundary(
                AppLimits.MIN_LLM_READ_TIMEOUT_SECONDS,
                AppLimits.MAX_LLM_READ_TIMEOUT_SECONDS,
                { RuntimeSettingsUpdate(llmReadTimeoutSeconds = it) },
                RuntimeSettingsSnapshot::llmReadTimeoutSeconds
            ),
            RuntimeBoundary(
                AppLimits.MIN_TOOL_TIMEOUT_SECONDS,
                AppLimits.MAX_TOOL_TIMEOUT_SECONDS,
                { RuntimeSettingsUpdate(defaultToolTimeoutSeconds = it) },
                RuntimeSettingsSnapshot::defaultToolTimeoutSeconds
            ),
            RuntimeBoundary(
                AppLimits.MIN_CONTEXT_MESSAGES,
                AppLimits.MAX_CONTEXT_MESSAGES,
                { RuntimeSettingsUpdate(contextMessages = it) },
                RuntimeSettingsSnapshot::contextMessages
            ),
            RuntimeBoundary(
                AppLimits.MIN_TOOL_ARGS_PREVIEW_MAX_CHARS,
                AppLimits.MAX_TOOL_ARGS_PREVIEW_MAX_CHARS,
                { RuntimeSettingsUpdate(toolArgsPreviewMaxChars = it) },
                RuntimeSettingsSnapshot::toolArgsPreviewMaxChars
            )
        )

        cases.forEach { boundary ->
            val service = createService(FakePersistence())
            assertEquals(boundary.min, boundary.read(service.updateRuntimeSettings(boundary.update(boundary.min))))
            assertEquals(boundary.max, boundary.read(service.updateRuntimeSettings(boundary.update(boundary.max))))
            assertThrows(IllegalArgumentException::class.java) {
                service.updateRuntimeSettings(boundary.update(boundary.min - 1))
            }
            assertThrows(IllegalArgumentException::class.java) {
                service.updateRuntimeSettings(boundary.update(boundary.max + 1))
            }
        }
    }

    @Test
    fun `heartbeat update persists then refreshes then arms requested alarm`() = runBlocking {
        val persistence = FakePersistence()
        val events = mutableListOf<String>()
        val service = createService(persistence)
        val refresh = object : RuntimeRefreshPort {
            override fun applyHeartbeatConfig(config: HeartbeatConfig) {
                events += "refresh:${config.enabled}:${config.intervalSeconds}"
            }

            override fun applyChannelsConfig(config: ChannelsConfig) = Unit
        }
        val heartbeat = object : HeartbeatRuntimePort {
            override fun armNextAlarm(config: HeartbeatConfig, timestampMs: Long) {
                events += "arm:$timestampMs"
            }

            override suspend fun triggerNow(): String = "triggered"
        }
        persistence.onSaveHeartbeat = { events += "save:${it.enabled}:${it.intervalSeconds}" }
        persistence.onWriteHeartbeatDocument = { events += "document:$it" }

        val result = service.updateHeartbeat(
            update = HeartbeatUpdate(
                enabled = true,
                intervalSeconds = 600,
                documentContent = "check tasks",
                nextTriggerAtMs = 1234L
            ),
            refreshPort = refresh,
            heartbeatPort = heartbeat
        )

        assertEquals(
            listOf("save:true:600", "document:check tasks", "refresh:true:600", "arm:1234"),
            events
        )
        assertTrue(result.enabled)
        assertEquals("check tasks", result.documentContent)
    }

    @Test
    fun `disabled heartbeat with next trigger keeps existing failure ordering`() = runBlocking {
        val persistence = FakePersistence().apply {
            storedHeartbeatConfig = HeartbeatConfig(enabled = true, intervalSeconds = 300)
        }
        val events = mutableListOf<String>()
        persistence.onSaveHeartbeat = { events += "save" }
        val service = createService(persistence)

        val error = runCatching {
            service.updateHeartbeat(
                update = HeartbeatUpdate(enabled = false, nextTriggerAtMs = 999L),
                refreshPort = object : RuntimeRefreshPort {
                    override fun applyHeartbeatConfig(config: HeartbeatConfig) {
                        events += "refresh"
                    }

                    override fun applyChannelsConfig(config: ChannelsConfig) = Unit
                },
                heartbeatPort = NoOpHeartbeatRuntimePort
            )
        }.exceptionOrNull() ?: error("Expected disabled heartbeat update to fail")

        assertTrue(error is IllegalStateException)
        assertEquals("Cannot set next heartbeat trigger while heartbeat is disabled", error.message)
        assertEquals(listOf("save", "refresh"), events)
        assertEquals(false, persistence.storedHeartbeatConfig.enabled)
    }

    @Test
    fun `heartbeat trigger requires enabled config before invoking runtime`() = runBlocking {
        val persistence = FakePersistence()
        var triggerCount = 0
        val port = object : HeartbeatRuntimePort {
            override fun armNextAlarm(config: HeartbeatConfig, timestampMs: Long) = Unit
            override suspend fun triggerNow(): String {
                triggerCount += 1
                return "triggered"
            }
        }
        val service = createService(persistence)

        val disabled = runCatching { service.triggerHeartbeat(port) }.exceptionOrNull()
        assertTrue(disabled is IllegalStateException)
        assertEquals(0, triggerCount)

        persistence.storedHeartbeatConfig = HeartbeatConfig(enabled = true, intervalSeconds = 300)
        assertEquals("triggered", service.triggerHeartbeat(port))
        assertEquals(1, triggerCount)
    }

    @Test
    fun `session listing inserts local session and rejects ambiguous titles`() = runBlocking {
        val persistence = FakePersistence().apply {
            sessions = mutableListOf(
                session("a", "Project Alpha", updatedAt = 20),
                session("b", "Project Beta", updatedAt = 10)
            )
        }
        val service = createService(persistence)

        val snapshot = service.listSessions { "a" }

        assertEquals("a", snapshot.currentSessionId)
        assertEquals("local", snapshot.sessions.first().sessionId)
        assertEquals("current", snapshot.sessions.first { it.sessionId == "a" }.status)
        val error = runCatching {
            service.sendToSession(
                SessionDeliveryCommand(content = "hello", sessionTitle = "Project"),
                FakeSessionDeliveryPort()
            )
        }.exceptionOrNull() ?: error("Expected ambiguous session title to fail")
        assertTrue(error is IllegalArgumentException)
        assertEquals("session_title is ambiguous; use session_id", error.message)
    }

    @Test
    fun `session delivery persists locally before remote delivery`() = runBlocking {
        val persistence = FakePersistence().apply {
            sessions = mutableListOf(session("target", "Target", updatedAt = 1))
            bindings = mutableListOf(
                SessionChannelBinding(
                    sessionId = "target",
                    enabled = true,
                    channel = "telegram",
                    chatId = "42",
                    telegramBotToken = "token"
                )
            )
        }
        val events = mutableListOf<String>()
        persistence.onAppendMessage = { events += "append" }
        persistence.onTouchSession = { events += "touch" }
        val delivery = FakeSessionDeliveryPort(events = events).apply {
            resolvedBinding = persistence.bindings.single()
        }

        val result = createService(persistence).sendToSession(
            SessionDeliveryCommand(content = "hello", sessionId = "target", deliverRemote = true),
            delivery
        )

        assertEquals(listOf("prepare", "append", "touch", "deliver", "mark"), events)
        assertTrue(result.remoteDelivered)
    }

    @Test
    fun `session selection supports exact and unique partial titles but rejects duplicate exact titles`() = runBlocking {
        val persistence = FakePersistence().apply {
            sessions = mutableListOf(
                session("alpha", "Project Alpha", updatedAt = 3),
                session("beta", "Project Beta", updatedAt = 2),
                session("duplicate", "Project Alpha", updatedAt = 1)
            )
        }
        val service = createService(persistence)

        val byId = service.sendToSession(
            SessionDeliveryCommand(content = "id", sessionId = "BETA", deliverRemote = false),
            FakeSessionDeliveryPort()
        )
        assertEquals("beta", byId.sessionId)

        val partial = service.sendToSession(
            SessionDeliveryCommand(content = "partial", sessionTitle = "Beta", deliverRemote = false),
            FakeSessionDeliveryPort()
        )
        assertEquals("beta", partial.sessionId)

        val duplicate = runCatching {
            service.sendToSession(
                SessionDeliveryCommand(content = "duplicate", sessionTitle = "Project Alpha"),
                FakeSessionDeliveryPort()
            )
        }.exceptionOrNull()
        assertTrue(duplicate is IllegalArgumentException)
        assertEquals("session_title matches multiple sessions; use session_id", duplicate?.message)
    }

    @Test
    fun `invalid remote binding fails only after local message persistence`() = runBlocking {
        val events = mutableListOf<String>()
        val persistence = FakePersistence().apply {
            sessions = mutableListOf(session("target", "Target", updatedAt = 1))
            bindings = mutableListOf(
                SessionChannelBinding(
                    sessionId = "target",
                    enabled = true,
                    channel = "telegram",
                    chatId = "42"
                )
            )
            onAppendMessage = { events += "append" }
            onTouchSession = { events += "touch" }
        }

        val failure = runCatching {
            createService(persistence).sendToSession(
                SessionDeliveryCommand(content = "hello", sessionId = "target"),
                FakeSessionDeliveryPort(events)
            )
        }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        assertEquals(listOf("prepare", "append", "touch"), events)
    }

    @Test
    fun `remote delivery failure preserves local message and unsupported delivery returns note`() = runBlocking {
        val binding = SessionChannelBinding(
            sessionId = "target",
            enabled = true,
            channel = "telegram",
            chatId = "42",
            telegramBotToken = "token"
        )
        val events = mutableListOf<String>()
        val persistence = FakePersistence().apply {
            sessions = mutableListOf(session("target", "Target", updatedAt = 1))
            bindings = mutableListOf(binding)
            onAppendMessage = { events += "append" }
            onTouchSession = { events += "touch" }
        }
        val failingDelivery = FakeSessionDeliveryPort(events).apply {
            resolvedBinding = binding
            deliverFailure = IllegalStateException("remote failed")
        }

        val failure = runCatching {
            createService(persistence).sendToSession(
                SessionDeliveryCommand(content = "hello", sessionId = "target"),
                failingDelivery
            )
        }.exceptionOrNull()
        assertEquals("remote failed", failure?.message)
        assertEquals(listOf("prepare", "append", "touch", "deliver"), events)

        val unsupported = FakeSessionDeliveryPort().apply {
            resolvedBinding = binding
            remoteDeliverySupported = false
        }
        val result = createService(persistence).sendToSession(
            SessionDeliveryCommand(content = "kept", sessionId = "target"),
            unsupported
        )
        assertEquals(false, result.remoteDelivered)
        assertTrue(result.note.orEmpty().contains("not supported"))
    }

    @Test
    fun `wecom remote delivery retains reply context note`() = runBlocking {
        val binding = SessionChannelBinding(
            sessionId = "target",
            enabled = true,
            channel = "wecom",
            chatId = "chat",
            wecomBotId = "bot",
            wecomSecret = "secret"
        )
        val persistence = FakePersistence().apply {
            sessions = mutableListOf(session("target", "Target", updatedAt = 1))
            bindings = mutableListOf(binding)
        }
        val delivery = FakeSessionDeliveryPort().apply { resolvedBinding = binding }

        val result = createService(persistence).sendToSession(
            SessionDeliveryCommand(content = "hello", sessionId = "target"),
            delivery
        )

        assertTrue(result.remoteDelivered)
        assertTrue(result.note.orEmpty().startsWith("WeCom remote delivery is reply-context based."))
    }

    @Test
    fun `channel update recalculates gateway state and returns projected status`() = runBlocking {
        val persistence = FakePersistence().apply {
            sessions = mutableListOf(session("target", "Target", updatedAt = 1))
            bindings = mutableListOf(
                SessionChannelBinding(
                    sessionId = "target",
                    enabled = false,
                    channel = "telegram",
                    chatId = "42",
                    telegramBotToken = "token"
                )
            )
            storedChannelsConfig = channelsConfig(enabled = false)
        }
        val refreshed = mutableListOf<ChannelsConfig>()
        val service = createService(persistence)

        val result = service.setChannelEnabled(
            update = ChannelBindingUpdate(sessionId = "target", enabled = true),
            refreshPort = object : RuntimeRefreshPort {
                override fun applyHeartbeatConfig(config: HeartbeatConfig) = Unit
                override fun applyChannelsConfig(config: ChannelsConfig) {
                    refreshed += config
                }
            },
            snapshotSource = ChannelRuntimeSnapshotSource { _, _ ->
                ChannelRuntimeSnapshot(ready = true)
            }
        )

        assertTrue(persistence.storedChannelsConfig.enabled)
        assertEquals(1, refreshed.size)
        assertEquals("Connected", result.status)
    }

    @Test
    fun `channel get uses shared normalized projection`() = runBlocking {
        val persistence = FakePersistence().apply {
            sessions = mutableListOf(session("target", "Target", updatedAt = 1))
            bindings = mutableListOf(
                SessionChannelBinding(
                    sessionId = "target",
                    channel = " Slack ",
                    chatId = " <#c12345678|general> ",
                    slackBotToken = "xoxb-bot",
                    slackAppToken = "xapp-app"
                )
            )
            storedChannelsConfig = channelsConfig(enabled = true)
        }

        val result = createService(persistence).getChannelBindings(
            ChannelRuntimeSnapshotSource { _, _ -> ChannelRuntimeSnapshot(connected = true) }
        )

        assertEquals("Unbound", result.sessions.first().status)
        assertEquals("slack", result.sessions.last().channel)
        assertEquals("C12345678", result.sessions.last().target)
        assertEquals("Connecting", result.sessions.last().status)
    }

    @Test
    fun `channel update saves binding and gateway before refresh and projection`() = runBlocking {
        val events = mutableListOf<String>()
        val persistence = FakePersistence().apply {
            sessions = mutableListOf(session("target", "Target", updatedAt = 1))
            bindings = mutableListOf(
                SessionChannelBinding(
                    sessionId = "target",
                    enabled = false,
                    channel = "telegram",
                    chatId = "42",
                    telegramBotToken = "token"
                )
            )
            storedChannelsConfig = channelsConfig(enabled = false)
            onSaveBinding = { events += "save_binding" }
            onSaveChannels = { events += "save_gateway" }
        }

        createService(persistence).setChannelEnabled(
            update = ChannelBindingUpdate(sessionId = "target", enabled = true),
            refreshPort = object : RuntimeRefreshPort {
                override fun applyHeartbeatConfig(config: HeartbeatConfig) = Unit
                override fun applyChannelsConfig(config: ChannelsConfig) {
                    events += "refresh"
                }
            },
            snapshotSource = ChannelRuntimeSnapshotSource { channel, _ ->
                events += "snapshot:$channel"
                ChannelRuntimeSnapshot(ready = true)
            }
        )

        assertEquals(
            listOf(
                "save_binding",
                "save_gateway",
                "refresh",
                "snapshot:telegram"
            ),
            events
        )
    }

    @Test
    fun `mcp status aggregates configured runtime entries`() {
        val alpha = McpHttpServerConfig(id = "one", serverName = "Alpha Server", serverUrl = "https://a")
        val beta = McpHttpServerConfig(id = "two", serverName = "Beta Server", serverUrl = "https://b")
        val persistence = FakePersistence().apply {
            mcpConfig = McpHttpConfig(
                enabled = true,
                servers = listOf(alpha, beta)
            )
        }
        val source = McpRuntimeStatusSource {
            mapOf(
                "one" to McpRuntimeStatus(
                    serverName = "alpha_server",
                    endpoint = "https://a/",
                    configFingerprint = fingerprint(alpha),
                    phase = "ready",
                    status = "Connected",
                    toolCount = 2,
                    toolNames = listOf("a", "b"),
                    resourceCount = 3,
                    resourceTemplateCount = 1,
                    promptCount = 4,
                    completionSupported = true,
                    transport = "streamable_http",
                    protocolVersion = "2025-11-25",
                    endpointSecurity = "https"
                )
            )
        }

        val result = createService(persistence).getMcpStatus(source)

        assertEquals(1, result.connectedServerCount)
        assertEquals(2, result.registeredToolCount)
        assertEquals(3, result.availableResourceCount)
        assertEquals(1, result.availableResourceTemplateCount)
        assertEquals(4, result.availablePromptCount)
        assertEquals(listOf("a", "b"), result.servers.first().toolNames)
        assertEquals("ready", result.servers.first().phase)
        assertEquals("2025-11-25", result.servers.first().protocolVersion)
        assertEquals("Not connected", result.servers.last().status)
    }

    @Test
    fun `mcp status preserves runtime enabled generation and issues`() {
        val resources = McpHttpServerConfig(
            id = "resources",
            serverName = "Resources",
            serverUrl = "https://resources.example/mcp"
        )
        val persistence = FakePersistence().apply {
            mcpConfig = McpHttpConfig(
                enabled = true,
                servers = listOf(resources)
            )
        }
        val source = object : McpRuntimeStatusSource {
            override fun currentStatuses(): Map<String, McpRuntimeStatus> = emptyMap()

            override fun currentSnapshot() = McpRuntimeStatusSnapshot(
                enabled = false,
                generation = 19,
                statuses = mapOf(
                    "resources" to McpRuntimeStatus(
                        serverName = "resources",
                        endpoint = "https://resources.example/mcp",
                        configFingerprint = fingerprint(resources),
                        status = "Degraded",
                        phase = "degraded",
                        usable = false,
                        resourceCount = 3,
                        generation = 19
                    )
                ),
                issues = listOf(
                    McpRuntimeStatusIssue(
                        code = "content_tool_name_conflict",
                        detail = "Could not publish mcp_content"
                    )
                )
            )
        }

        val result = createService(persistence).getMcpStatus(source)

        assertFalse(result.enabled)
        assertEquals(19, result.generation)
        assertEquals(3, result.availableResourceCount)
        assertEquals("content_tool_name_conflict", result.issues.single().code)
        assertEquals("Could not publish mcp_content", result.issues.single().detail)
    }

    @Test
    fun `mcp status does not attach a stale ready snapshot after the configured endpoint changes`() {
        val oldServer = McpHttpServerConfig(
            id = "stable-id",
            serverName = "Alpha",
            serverUrl = "https://old.example/mcp"
        )
        val persistence = FakePersistence().apply {
            mcpConfig = McpHttpConfig(
                enabled = true,
                servers = listOf(oldServer.copy(serverUrl = "https://new.example/mcp"))
            )
        }
        val source = McpRuntimeStatusSource {
            mapOf(
                "stable-id" to McpRuntimeStatus(
                    serverName = "alpha",
                    endpoint = "https://old.example/mcp",
                    configFingerprint = fingerprint(oldServer),
                    status = "Connected",
                    phase = "ready",
                    usable = true,
                    toolCount = 2,
                    toolNames = listOf("mcp_alpha_read", "mcp_alpha_write")
                )
            )
        }

        val result = createService(persistence).getMcpStatus(source)

        assertEquals("Not connected", result.servers.single().status)
        assertEquals("connecting", result.servers.single().phase)
        assertFalse(result.servers.single().usable)
        assertEquals(0, result.registeredToolCount)
        assertEquals(emptyList<String>(), result.servers.single().toolNames)
    }

    @Test
    fun `mcp status does not attach stale ready capabilities after only query changes`() {
        val oldServer = McpHttpServerConfig(
            id = "stable-id",
            serverName = "Alpha",
            serverUrl = "https://mcp.example/rpc?tenant=one"
        )
        val persistence = FakePersistence().apply {
            mcpConfig = McpHttpConfig(
                enabled = true,
                servers = listOf(oldServer)
            )
        }
        val source = McpRuntimeStatusSource {
            mapOf(
                "stable-id" to McpRuntimeStatus(
                    serverName = "alpha",
                    endpoint = "https://mcp.example/rpc",
                    configFingerprint = fingerprint(oldServer),
                    status = "Connected",
                    phase = "ready",
                    usable = true,
                    toolCount = 2,
                    toolNames = listOf("mcp_alpha_read", "mcp_alpha_write"),
                    resourceCount = 3
                )
            )
        }

        val service = createService(persistence)
        val matching = service.getMcpStatus(source)
        assertEquals("Connected", matching.servers.single().status)
        assertEquals(2, matching.registeredToolCount)
        assertEquals(3, matching.availableResourceCount)
        assertEquals("https://mcp.example/rpc", matching.servers.single().serverUrl)

        persistence.mcpConfig = persistence.mcpConfig.copy(
            servers = listOf(oldServer.copy(serverUrl = "https://mcp.example/rpc?tenant=two"))
        )
        val result = service.getMcpStatus(source)

        assertEquals("Not connected", result.servers.single().status)
        assertEquals("connecting", result.servers.single().phase)
        assertFalse(result.servers.single().usable)
        assertEquals(0, result.registeredToolCount)
        assertEquals(0, result.availableResourceCount)
        assertEquals(emptyList<String>(), result.servers.single().toolNames)
        assertFalse(result.servers.single().serverUrl.contains("tenant"))
    }

    @Test
    fun `mcp status DTO never contains endpoint credentials query or fragment`() {
        val persistence = FakePersistence().apply {
            mcpConfig = McpHttpConfig(
                enabled = true,
                servers = listOf(
                    McpHttpServerConfig(
                        id = "unsafe",
                        serverName = "Unsafe",
                        serverUrl = "https://user:secret@example.com/mcp?token=private#fragment"
                    )
                )
            )
        }

        val result = createService(persistence).getMcpStatus(McpRuntimeStatusSource { emptyMap() })

        assertEquals("https://example.com/mcp", result.servers.single().serverUrl)
        assertFalse(result.servers.single().serverUrl.contains("user"))
        assertFalse(result.servers.single().serverUrl.contains("secret"))
        assertFalse(result.servers.single().serverUrl.contains("private"))
        assertFalse(result.servers.single().serverUrl.contains("fragment"))
        assertFalse(result.toString().contains("user:secret"))
        assertFalse(result.toString().contains("token=private"))
        assertFalse(result.toString().contains("private#fragment"))
    }

    @Test
    fun `mcp status supports legacy fallback and enabled disconnected defaults`() {
        val persistence = FakePersistence().apply {
            mcpConfig = McpHttpConfig(
                enabled = false,
                serverName = "Legacy Server",
                serverUrl = "https://legacy"
            )
        }
        val service = createService(persistence)

        val disabled = service.getMcpStatus(McpRuntimeStatusSource { emptyMap() })
        assertEquals("mcp_1", disabled.servers.single().id)
        assertEquals("Disabled", disabled.servers.single().status)

        persistence.mcpConfig = persistence.mcpConfig.copy(enabled = true)
        val disconnected = service.getMcpStatus(McpRuntimeStatusSource { emptyMap() })
        assertEquals("Not connected", disconnected.servers.single().status)
        assertEquals(0, disconnected.connectedServerCount)
    }

    private fun fingerprint(server: McpHttpServerConfig): String {
        val endpoint = McpEndpointPolicy.evaluate(
            rawUrl = server.serverUrl,
            authToken = server.authToken,
            insecureHttpAllowedOrigin = server.insecureHttpAllowedOrigin
        )
        return checkNotNull(
            McpEndpointPolicy.configurationFingerprint(
                server = server,
                canonicalUrl = endpoint.canonicalUrl
            )
        )
    }

    private class FakePersistence : RuntimeControlPersistence {
        var storedAppConfig = appConfig()
        var storedHeartbeatConfig = HeartbeatConfig(enabled = false, intervalSeconds = 300)
        var heartbeatDocument = ""
        var storedChannelsConfig = channelsConfig(enabled = false)
        var bindings = mutableListOf<SessionChannelBinding>()
        var mcpConfig = McpHttpConfig()
        var sessions = mutableListOf<SessionEntity>()
        var onSaveHeartbeat: (HeartbeatConfig) -> Unit = {}
        var onWriteHeartbeatDocument: (String) -> Unit = {}
        var onAppendMessage: () -> Unit = {}
        var onTouchSession: () -> Unit = {}
        var onSaveBinding: (SessionChannelBinding) -> Unit = {}
        var onSaveChannels: (ChannelsConfig) -> Unit = {}

        override fun getAppConfig(): AppConfig = storedAppConfig
        override fun saveAppConfig(config: AppConfig) {
            storedAppConfig = config
        }

        override fun getHeartbeatConfig(): HeartbeatConfig = storedHeartbeatConfig
        override fun saveHeartbeatConfig(config: HeartbeatConfig) {
            storedHeartbeatConfig = config
            onSaveHeartbeat(config)
        }

        override fun getHeartbeatLastTriggeredAtMs(): Long = 10L
        override fun getHeartbeatNextTriggerAtMs(): Long = 20L
        override suspend fun readHeartbeatDocument(): String = heartbeatDocument
        override suspend fun writeHeartbeatDocument(content: String) {
            heartbeatDocument = content
            onWriteHeartbeatDocument(content)
        }

        override fun getChannelsConfig(): ChannelsConfig = storedChannelsConfig
        override fun saveChannelsConfig(config: ChannelsConfig) {
            storedChannelsConfig = config
            onSaveChannels(config)
        }

        override fun getSessionChannelBindings(): List<SessionChannelBinding> = bindings.toList()
        override fun saveSessionChannelBinding(binding: SessionChannelBinding) {
            bindings.removeAll { it.sessionId == binding.sessionId }
            bindings += binding
            onSaveBinding(binding)
        }

        override fun getMcpHttpConfig(): McpHttpConfig = mcpConfig
        override suspend fun listSessions(): List<SessionEntity> = sessions.toList()
        override suspend fun appendAssistantMessage(
            sessionId: String,
            content: String,
            attachments: List<MessageAttachment>
        ) {
            onAppendMessage()
        }

        override suspend fun touchSession(sessionId: String) {
            onTouchSession()
        }
    }

    private class FakeSessionDeliveryPort(
        private val events: MutableList<String> = mutableListOf()
    ) : SessionDeliveryPort {
        var resolvedBinding: SessionChannelBinding? = null
        var remoteDeliverySupported: Boolean = true
        var deliverFailure: Throwable? = null

        override suspend fun prepareAttachments(
            sessionId: String,
            sessionTitle: String,
            messageId: Long,
            attachments: List<MessageAttachment>
        ): List<MessageAttachment> {
            events += "prepare"
            return attachments
        }

        override fun resolveActiveBinding(sessionId: String): SessionChannelBinding? = resolvedBinding
        override fun supportsRemoteDelivery(outbound: OutboundMessage): Boolean = remoteDeliverySupported
        override suspend fun deliver(outbound: OutboundMessage) {
            events += "deliver"
            deliverFailure?.let { throw it }
        }

        override fun markRemoteDeliverySent() {
            events += "mark"
        }

        override fun adapterMetadata(binding: SessionChannelBinding): Map<String, String> = emptyMap()
    }

    private companion object {
        val Projector = ChannelBindingRuntimeProjector(
            EmailAddressValidator { value -> value.contains('@') }
        )

        fun createService(persistence: RuntimeControlPersistence) =
            RuntimeControlService(persistence, Projector)

        val NoOpHeartbeatRuntimePort = object : HeartbeatRuntimePort {
            override fun armNextAlarm(config: HeartbeatConfig, timestampMs: Long) = Unit
            override suspend fun triggerNow(): String = "triggered"
        }

        fun appConfig() = AppConfig(providerName = "openai", apiKey = "", model = "model")

        fun channelsConfig(enabled: Boolean) = ChannelsConfig(
            enabled = enabled,
            telegramBotToken = "",
            telegramAllowedChatId = null,
            discordWebhookUrl = ""
        )

        fun session(id: String, title: String, updatedAt: Long) = SessionEntity(
            id = id,
            title = title,
            createdAt = 1,
            updatedAt = updatedAt
        )
    }

    private data class RuntimeBoundary(
        val min: Int,
        val max: Int,
        val update: (Int) -> RuntimeSettingsUpdate,
        val read: (RuntimeSettingsSnapshot) -> Int
    )
}
