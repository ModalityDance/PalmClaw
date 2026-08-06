package com.palmclaw.runtime.control

import com.palmclaw.bus.MessageAttachment
import com.palmclaw.bus.OutboundMessage
import com.palmclaw.channels.ChannelRuntimeSnapshot
import com.palmclaw.channels.ChannelRuntimeSnapshotSource
import com.palmclaw.config.ChannelsConfig
import com.palmclaw.config.HeartbeatConfig
import com.palmclaw.config.SessionChannelBinding
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeToolIntegrationTest {

    @Test
    fun `integration exposes all ten runtime tools when automation is enabled`() {
        val integration = integration(includeHeartbeat = true)

        assertEquals(
            listOf(
                "runtime_get",
                "runtime_set",
                "heartbeat_get",
                "heartbeat_set",
                "heartbeat_trigger",
                "session_status",
                "session_set",
                "sessions_list",
                "sessions_send",
                "mcp_status"
            ),
            integration.tools.map { it.name }
        )
    }

    @Test
    fun `integration omits heartbeat tools when automation is disabled`() {
        val integration = integration(includeHeartbeat = false)

        assertTrue(integration.tools.none { it.name.startsWith("heartbeat_") })
        assertEquals(7, integration.tools.size)
    }

    @Test
    fun `runtime tool delegates through domain operations and close clears callback`() = runBlocking {
        val operations = FakeOperations()
        val integration = integration(operations = operations)
        val tool = integration.tools.first { it.name == "runtime_set" }

        val success = tool.run("""{"max_tool_rounds":42}""")

        assertEquals(42, operations.lastRuntimeUpdate?.maxToolRounds)
        assertTrue(!success.isError)
        integration.close()
        assertTrue(integration.isClosed)
        val closed = tool.run("""{"max_tool_rounds":43}""")
        assertTrue(closed.isError)
        assertTrue(closed.content.contains("not configured"))
    }

    @Test
    fun `close is idempotent and detaches every tool callback`() = runBlocking {
        val integration = integration()

        integration.close()
        integration.close()

        assertTrue(integration.isClosed)
        integration.tools.forEach { tool ->
            val result = tool.run(toolArguments.getValue(tool.name))
            assertTrue("${tool.name} should be inert after close", result.isError)
        }
    }

    @Test
    fun `all runtime tools delegate through matching domain operation once`() = runBlocking {
        val operations = FakeOperations()
        val integration = integration(operations = operations)
        integration.tools.forEach { tool ->
            val result = tool.run(toolArguments.getValue(tool.name))
            assertTrue("${tool.name} should succeed: ${result.content}", !result.isError)
        }

        assertEquals(
            listOf(
                "runtime_get",
                "runtime_set:41",
                "heartbeat_get",
                "heartbeat_set:true:600",
                "heartbeat_trigger",
                "session_status",
                "session_set:target:true",
                "sessions_list:local",
                "sessions_send:target:hello:true",
                "mcp_status"
            ),
            operations.calls
        )
        assertEquals(
            RuntimeSettingsUpdate(
                maxToolRounds = 41,
                toolResultMaxChars = 6_000,
                memoryConsolidationWindow = 60,
                llmCallTimeoutSeconds = 130,
                llmConnectTimeoutSeconds = 30,
                llmReadTimeoutSeconds = 140,
                defaultToolTimeoutSeconds = 70,
                contextMessages = 75,
                toolArgsPreviewMaxChars = 4_500
            ),
            operations.lastRuntimeUpdate
        )
        assertEquals(
            HeartbeatUpdate(
                enabled = true,
                intervalSeconds = 600,
                documentContent = "tasks",
                nextTriggerAtMs = 1234
            ),
            operations.lastHeartbeatUpdate
        )
        assertEquals(
            ChannelBindingUpdate(sessionId = "target", enabled = true),
            operations.lastChannelUpdate
        )
        assertEquals(listOf("legacy-path"), operations.lastSessionCommand?.media)
    }

    private fun integration(
        operations: RuntimeControlOperations = FakeOperations(),
        includeHeartbeat: Boolean = true
    ) = RuntimeToolIntegration(
        operations = operations,
        includeHeartbeat = includeHeartbeat,
        refreshPort = NoOpRefreshPort,
        heartbeatPort = NoOpHeartbeatPort,
        activeSessionSource = ActiveSessionSource { "local" },
        sessionDeliveryPort = NoOpSessionDeliveryPort,
        channelSnapshotSource = NoOpChannelSnapshotSource,
        mcpStatusSource = McpRuntimeStatusSource { emptyMap() }
    )

    private class FakeOperations : RuntimeControlOperations {
        var lastRuntimeUpdate: RuntimeSettingsUpdate? = null
        var lastHeartbeatUpdate: HeartbeatUpdate? = null
        var lastChannelUpdate: ChannelBindingUpdate? = null
        var lastSessionCommand: SessionDeliveryCommand? = null
        val calls = mutableListOf<String>()

        override fun getRuntimeSettings(): RuntimeSettingsSnapshot {
            calls += "runtime_get"
            return runtimeSnapshot()
        }

        override fun updateRuntimeSettings(update: RuntimeSettingsUpdate): RuntimeSettingsSnapshot {
            lastRuntimeUpdate = update
            calls += "runtime_set:${update.maxToolRounds}"
            return runtimeSnapshot().copy(maxToolRounds = update.maxToolRounds ?: 20)
        }

        override suspend fun getHeartbeat(): HeartbeatSnapshot {
            calls += "heartbeat_get"
            return HeartbeatSnapshot(false, 300, "", 0, 0)
        }

        override suspend fun updateHeartbeat(
            update: HeartbeatUpdate,
            refreshPort: RuntimeRefreshPort,
            heartbeatPort: HeartbeatRuntimePort
        ): HeartbeatSnapshot {
            lastHeartbeatUpdate = update
            calls += "heartbeat_set:${update.enabled}:${update.intervalSeconds}"
            return HeartbeatSnapshot(update.enabled ?: false, update.intervalSeconds ?: 300, "", 0, 0)
        }

        override suspend fun triggerHeartbeat(heartbeatPort: HeartbeatRuntimePort): String {
            calls += "heartbeat_trigger"
            return "triggered"
        }

        override suspend fun listSessions(activeSessionSource: ActiveSessionSource): SessionsSnapshot {
            val activeSessionId = activeSessionSource.currentSessionId().orEmpty()
            calls += "sessions_list:$activeSessionId"
            return SessionsSnapshot(activeSessionId, emptyList())
        }

        override suspend fun sendToSession(
            command: SessionDeliveryCommand,
            deliveryPort: SessionDeliveryPort
        ): SessionDeliveryResult {
            lastSessionCommand = command
            calls += "sessions_send:${command.sessionId}:${command.content}:${command.deliverRemote}"
            return SessionDeliveryResult(command.sessionId.orEmpty(), "Target", false)
        }

        override suspend fun getChannelBindings(
            snapshotSource: ChannelRuntimeSnapshotSource
        ): ChannelBindingsSnapshot {
            calls += "session_status"
            return ChannelBindingsSnapshot(false, emptyList())
        }

        override suspend fun setChannelEnabled(
            update: ChannelBindingUpdate,
            refreshPort: RuntimeRefreshPort,
            snapshotSource: ChannelRuntimeSnapshotSource
        ): ChannelBindingResult {
            lastChannelUpdate = update
            calls += "session_set:${update.sessionId}:${update.enabled}"
            return ChannelBindingResult(update.sessionId.orEmpty(), "Session", update.enabled, "Configured")
        }

        override fun getMcpStatus(statusSource: McpRuntimeStatusSource): McpStatusSnapshot {
            calls += "mcp_status"
            return McpStatusSnapshot(false, 0, 0, emptyList())
        }
    }

    private companion object {
        val toolArguments = mapOf(
            "runtime_get" to "{}",
            "runtime_set" to """{"max_tool_rounds":41,"tool_result_max_chars":6000,"memory_consolidation_window":60,"llm_call_timeout_seconds":130,"llm_connect_timeout_seconds":30,"llm_read_timeout_seconds":140,"default_tool_timeout_seconds":70,"context_messages":75,"tool_args_preview_max_chars":4500}""",
            "heartbeat_get" to "{}",
            "heartbeat_set" to """{"enabled":true,"interval_seconds":600,"document_content":"tasks","next_trigger_at_ms":1234}""",
            "heartbeat_trigger" to "{}",
            "session_status" to "{}",
            "session_set" to """{"session_id":"target","enabled":true}""",
            "sessions_list" to "{}",
            "sessions_send" to """{"session_id":"target","content":"hello","media":["legacy-path"]}""",
            "mcp_status" to "{}"
        )
        val NoOpRefreshPort = object : RuntimeRefreshPort {
            override fun applyHeartbeatConfig(config: HeartbeatConfig) = Unit
            override fun applyChannelsConfig(config: ChannelsConfig) = Unit
        }
        val NoOpHeartbeatPort = object : HeartbeatRuntimePort {
            override fun armNextAlarm(config: HeartbeatConfig, timestampMs: Long) = Unit
            override suspend fun triggerNow(): String = "triggered"
        }
        val NoOpSessionDeliveryPort = object : SessionDeliveryPort {
            override suspend fun prepareAttachments(
                sessionId: String,
                sessionTitle: String,
                messageId: Long,
                attachments: List<MessageAttachment>
            ): List<MessageAttachment> = attachments

            override fun resolveActiveBinding(sessionId: String): SessionChannelBinding? = null
            override fun supportsRemoteDelivery(outbound: OutboundMessage): Boolean = true
            override suspend fun deliver(outbound: OutboundMessage) = Unit
            override fun markRemoteDeliverySent() = Unit
            override fun adapterMetadata(binding: SessionChannelBinding): Map<String, String> = emptyMap()
        }
        val NoOpChannelSnapshotSource = ChannelRuntimeSnapshotSource { _, _ ->
            ChannelRuntimeSnapshot()
        }

        fun runtimeSnapshot() = RuntimeSettingsSnapshot(
            maxToolRounds = 20,
            toolResultMaxChars = 5_000,
            memoryConsolidationWindow = 50,
            llmCallTimeoutSeconds = 120,
            llmConnectTimeoutSeconds = 20,
            llmReadTimeoutSeconds = 120,
            defaultToolTimeoutSeconds = 60,
            contextMessages = 50,
            toolArgsPreviewMaxChars = 4_000
        )
    }
}
