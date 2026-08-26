package com.palmclaw.ui

import com.palmclaw.mcp.McpEndpointSecurity
import com.palmclaw.mcp.McpRuntimeSnapshot
import com.palmclaw.mcp.McpServerPhase
import com.palmclaw.mcp.McpServerSnapshot
import com.palmclaw.mcp.transport.McpTransportKind
import com.palmclaw.runtime.RuntimeControllerStatus
import com.palmclaw.runtime.alwayson.AlwaysOnActionRequired
import com.palmclaw.runtime.alwayson.AlwaysOnActionRequiredReason
import com.palmclaw.runtime.alwayson.AlwaysOnChannelCounts
import com.palmclaw.runtime.alwayson.AlwaysOnGatewayState
import com.palmclaw.runtime.alwayson.AlwaysOnNetworkState
import com.palmclaw.runtime.alwayson.AlwaysOnPhase
import com.palmclaw.runtime.alwayson.AlwaysOnRuntimeState
import com.palmclaw.runtime.alwayson.AlwaysOnShellState
import com.palmclaw.runtime.alwayson.AlwaysOnStatus
import com.palmclaw.runtime.alwayson.AlwaysOnWaitingReason
import com.palmclaw.ui.domain.AlwaysOnUiStatus
import com.palmclaw.ui.domain.RuntimeRefreshGateway
import com.palmclaw.ui.domain.RuntimeStatusSource
import com.palmclaw.ui.domain.toUiStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeStatusCoordinatorTest {
    @Test
    fun `status observation maps always on state and merges processing sessions`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val statusSource = FakeRuntimeStatusSource()
        val stateStore = ChatStateStore(ChatUiState())
        val processing = GatewayProcessingCoordinator()
        val refresh = FakeRuntimeRefreshGateway()
        var processingChanges = 0
        val coordinator = RuntimeStatusCoordinator(
            scope = scope,
            stateStore = stateStore,
            statusSource = statusSource,
            gatewayProcessingCoordinator = processing,
            refreshGateway = refresh,
            onProcessingChanged = { processingChanges += 1 }
        )

        coordinator.start()
        coordinator.start()
        val changesAfterStart = processingChanges
        statusSource.runtime.value = RuntimeControllerStatus(
            processingSessionIds = setOf("foreground")
        )
        statusSource.alwaysOn.value = AlwaysOnUiStatus(
            desired = true,
            phase = AlwaysOnUiStatus.Phase.DEGRADED,
            shell = AlwaysOnUiStatus.LifecycleState.RUNNING,
            notificationVisible = true,
            runtime = AlwaysOnUiStatus.LifecycleState.RUNNING,
            gateway = AlwaysOnUiStatus.LifecycleState.RUNNING,
            network = AlwaysOnUiStatus.NetworkState.ONLINE,
            channels = AlwaysOnUiStatus.ChannelCounts(
                configured = 3,
                ready = 2,
                reconnecting = 1
            ),
            waitingFor = AlwaysOnUiStatus.WaitingReason.CHANNELS,
            updatedAtEpochMillis = 42L,
            lastError = "status error",
            processingSessionIds = setOf("always-on")
        )
        yield()

        val runtimeStatus = stateStore.alwaysOnSettingsState.value.runtimeStatus
        assertTrue(runtimeStatus.desired)
        assertEquals(AlwaysOnUiStatus.Phase.DEGRADED, runtimeStatus.phase)
        assertEquals(AlwaysOnUiStatus.LifecycleState.RUNNING, runtimeStatus.shell)
        assertTrue(runtimeStatus.notificationVisible)
        assertEquals(AlwaysOnUiStatus.LifecycleState.RUNNING, runtimeStatus.runtime)
        assertEquals(AlwaysOnUiStatus.LifecycleState.RUNNING, runtimeStatus.gateway)
        assertEquals(AlwaysOnUiStatus.NetworkState.ONLINE, runtimeStatus.network)
        assertEquals(3, runtimeStatus.channels.configured)
        assertEquals(2, runtimeStatus.channels.ready)
        assertEquals(1, runtimeStatus.channels.reconnecting)
        assertEquals(AlwaysOnUiStatus.WaitingReason.CHANNELS, runtimeStatus.waitingFor)
        assertEquals(42L, runtimeStatus.updatedAtEpochMillis)
        assertEquals("status error", runtimeStatus.lastError)
        assertEquals(setOf("always-on"), runtimeStatus.processingSessionIds)
        assertTrue(processing.isSessionProcessing("foreground"))
        assertTrue(processing.isSessionProcessing("always-on"))
        assertEquals(changesAfterStart + 2, processingChanges)
        assertEquals(0, refresh.gatewayRefreshes)

        scope.cancel()
    }

    @Test
    fun `runtime observation forwards each typed mcp snapshot once`(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val statusSource = FakeRuntimeStatusSource()
        val observed = mutableListOf<McpRuntimeSnapshot>()
        val coordinator = RuntimeStatusCoordinator(
            scope = scope,
            stateStore = ChatStateStore(ChatUiState()),
            statusSource = statusSource,
            gatewayProcessingCoordinator = GatewayProcessingCoordinator(),
            refreshGateway = FakeRuntimeRefreshGateway(),
            onProcessingChanged = {},
            onMcpSnapshotChanged = observed::add
        )
        coordinator.start()
        val snapshot = McpRuntimeSnapshot(
            enabled = true,
            generation = 9,
            servers = listOf(
                McpServerSnapshot(
                    serverId = "alpha",
                    serverName = "Alpha",
                    endpoint = "https://example.com/mcp",
                    phase = McpServerPhase.READY,
                    usable = true,
                    toolNames = listOf("mcp_alpha_read"),
                    resourceCount = 2,
                    resourceTemplateCount = 1,
                    promptCount = 3,
                    completionSupported = true,
                    transport = McpTransportKind.STREAMABLE_HTTP,
                    protocolVersion = "2025-11-25",
                    endpointSecurity = McpEndpointSecurity.HTTPS,
                    generation = 9
                )
            )
        )

        statusSource.runtime.value = RuntimeControllerStatus(mcpSnapshot = snapshot)
        yield()
        statusSource.runtime.value = RuntimeControllerStatus(
            processingSessionIds = setOf("turn"),
            mcpSnapshot = snapshot
        )
        yield()

        assertEquals(snapshot, observed.last())
        assertEquals(1, observed.count { it == snapshot })
        scope.cancel()
    }

    @Test
    fun `core always on status projects every independent availability dimension`() {
        val projected = AlwaysOnStatus(
            desired = true,
            phase = AlwaysOnPhase.ACTION_REQUIRED,
            shell = AlwaysOnShellState.RUNNING,
            runtime = AlwaysOnRuntimeState.RUNNING,
            gateway = AlwaysOnGatewayState.STARTING,
            network = AlwaysOnNetworkState.OFFLINE,
            notificationVisible = true,
            channels = AlwaysOnChannelCounts(
                configured = 3,
                ready = 1,
                reconnecting = 1,
                blocked = 1
            ),
            waitingFor = AlwaysOnWaitingReason.GATEWAY,
            actionRequired = AlwaysOnActionRequired(
                reason = AlwaysOnActionRequiredReason.GATEWAY_BLOCKED,
                message = "gateway needs attention"
            ),
            lastError = "last failure",
            updatedAtEpochMillis = 42L
        ).toUiStatus(processingSessionIds = setOf("turn"))

        assertTrue(projected.desired)
        assertEquals(AlwaysOnUiStatus.Phase.ACTION_REQUIRED, projected.phase)
        assertEquals(AlwaysOnUiStatus.LifecycleState.RUNNING, projected.shell)
        assertEquals(AlwaysOnUiStatus.LifecycleState.RUNNING, projected.runtime)
        assertEquals(AlwaysOnUiStatus.LifecycleState.STARTING, projected.gateway)
        assertEquals(AlwaysOnUiStatus.NetworkState.OFFLINE, projected.network)
        assertTrue(projected.notificationVisible)
        assertEquals(3, projected.channels.configured)
        assertEquals(1, projected.channels.ready)
        assertEquals(1, projected.channels.reconnecting)
        assertEquals(1, projected.channels.blocked)
        assertEquals(AlwaysOnUiStatus.WaitingReason.GATEWAY, projected.waitingFor)
        assertEquals(
            AlwaysOnUiStatus.ActionRequiredReason.GATEWAY_BLOCKED,
            projected.actionRequired?.reason
        )
        assertEquals("gateway needs attention", projected.actionRequired?.message)
        assertEquals("last failure", projected.lastError)
        assertEquals(42L, projected.updatedAtEpochMillis)
        assertEquals(setOf("turn"), projected.processingSessionIds)
    }

    @Test
    fun `deferred refresh fires once when observed processing becomes idle and stops after cancellation`() =
        runBlocking {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            val statusSource = FakeRuntimeStatusSource()
            val processing = GatewayProcessingCoordinator()
            val refresh = FakeRuntimeRefreshGateway()
            var processingChanges = 0
            val coordinator = RuntimeStatusCoordinator(
                scope = scope,
                stateStore = ChatStateStore(ChatUiState()),
                statusSource = statusSource,
                gatewayProcessingCoordinator = processing,
                refreshGateway = refresh,
                onProcessingChanged = { processingChanges += 1 }
            )
            coordinator.start()
            statusSource.runtime.value = RuntimeControllerStatus(
                processingSessionIds = setOf("foreground")
            )
            yield()
            assertFalse(processing.requestGatewayRefresh())

            statusSource.runtime.value = RuntimeControllerStatus()
            yield()

            assertEquals(1, refresh.gatewayRefreshes)
            val changesBeforeCancellation = processingChanges
            scope.cancel()
            statusSource.runtime.value = RuntimeControllerStatus(
                processingSessionIds = setOf("after-cancel")
            )
            yield()
            assertEquals(changesBeforeCancellation, processingChanges)
            assertFalse(processing.isSessionProcessing("after-cancel"))
        }

    private class FakeRuntimeStatusSource : RuntimeStatusSource {
        val runtime = MutableStateFlow(RuntimeControllerStatus())
        val alwaysOn = MutableStateFlow(AlwaysOnUiStatus())

        override val runtimeStatus = runtime
        override val alwaysOnStatus = alwaysOn

        override fun currentAlwaysOnStatus(): AlwaysOnUiStatus = alwaysOn.value
    }

    private class FakeRuntimeRefreshGateway : RuntimeRefreshGateway {
        var gatewayRefreshes = 0

        override suspend fun refreshGatewayRuntimeConfig() {
            gatewayRefreshes += 1
        }

        override suspend fun refreshToolRuntimeConfig() = Unit

        override fun reloadAutomation() = Unit

        override fun reloadMcp() = Unit

        override fun reloadAll() = Unit
    }
}
