package com.palmclaw.runtime.alwayson

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class AlwaysOnCoordinatorTest {

    @Test
    fun `disable persists intent before cancelling recovery and stopping runtime shell`() = runBlocking {
        val events = mutableListOf<String>()
        val platform = FakeAlwaysOnPlatform(
            state = AlwaysOnPlatformSnapshot(
                shell = AlwaysOnShellState.RUNNING,
                network = AlwaysOnNetworkState.ONLINE,
                transientRecoveryScheduled = true,
                watchdogScheduled = true
            ),
            events = events
        )
        val gateway = FakeGatewayAvailability(
            state = GatewayAvailabilitySnapshot(
                runtime = AlwaysOnRuntimeState.RUNNING,
                gateway = AlwaysOnGatewayState.RUNNING,
                channels = AlwaysOnChannelCounts(configured = 1, ready = 1)
            ),
            events = events
        )
        val coordinator = AlwaysOnCoordinator(
            platform = platform,
            gateway = gateway,
            configStore = FakeAlwaysOnConfigStore(enabled = true, events = events),
            clock = AlwaysOnClock { 42L }
        )

        coordinator.setEnabled(false)

        assertEquals(
            listOf(
                "save:false",
                "cancel_recovery",
                "cancel_watchdog",
                "release_gateway_ownership",
                "stop_shell"
            ),
            events
        )
        assertEquals(
            AlwaysOnStatus(
                desired = false,
                phase = AlwaysOnPhase.DISABLED,
                shell = AlwaysOnShellState.STOPPED,
                runtime = AlwaysOnRuntimeState.RUNNING,
                network = AlwaysOnNetworkState.ONLINE,
                channels = AlwaysOnChannelCounts(configured = 1),
                lastTrigger = AlwaysOnTrigger.USER_DISABLED,
                updatedAtEpochMillis = 42L
            ),
            coordinator.status.value
        )
    }

    @Test
    fun `hidden notification does not block shell and runtime from becoming online`() = runBlocking {
        val events = mutableListOf<String>()
        val platform = FakeAlwaysOnPlatform(
            state = AlwaysOnPlatformSnapshot(
                shell = AlwaysOnShellState.STOPPED,
                network = AlwaysOnNetworkState.ONLINE,
                notificationVisible = false
            ),
            events = events
        )
        val gateway = FakeGatewayAvailability(
            state = GatewayAvailabilitySnapshot(
                runtime = AlwaysOnRuntimeState.STOPPED,
                channels = AlwaysOnChannelCounts(configured = 1)
            ),
            events = events
        )
        val coordinator = AlwaysOnCoordinator(
            platform = platform,
            gateway = gateway,
            configStore = FakeAlwaysOnConfigStore(enabled = false, events = events),
            clock = AlwaysOnClock { 43L }
        )

        coordinator.setEnabled(true)
        coordinator.reconcile(AlwaysOnTrigger.WATCHDOG)

        assertEquals(
            listOf("save:true", "ensure_watchdog", "start_shell", "ensure_gateway"),
            events
        )
        assertEquals(AlwaysOnPhase.ONLINE, coordinator.status.value.phase)
        assertEquals(true, coordinator.status.value.desired)
        assertEquals(false, coordinator.status.value.notificationVisible)
        assertEquals(AlwaysOnShellState.RUNNING, coordinator.status.value.shell)
        assertEquals(AlwaysOnRuntimeState.RUNNING, coordinator.status.value.runtime)
        assertEquals(AlwaysOnChannelCounts(configured = 1, ready = 1), coordinator.status.value.channels)
        assertEquals(AlwaysOnTrigger.WATCHDOG, coordinator.status.value.lastTrigger)
    }

    @Test
    fun `enabled mode without a configured channel releases persistent resources`() = runBlocking {
        val events = mutableListOf<String>()
        val coordinator = AlwaysOnCoordinator(
            platform = FakeAlwaysOnPlatform(
                state = AlwaysOnPlatformSnapshot(
                    shell = AlwaysOnShellState.RUNNING,
                    network = AlwaysOnNetworkState.ONLINE,
                    transientRecoveryScheduled = true,
                    watchdogScheduled = true
                ),
                events = events
            ),
            gateway = FakeGatewayAvailability(
                state = GatewayAvailabilitySnapshot(
                    runtime = AlwaysOnRuntimeState.RUNNING,
                    gateway = AlwaysOnGatewayState.RUNNING,
                    channels = AlwaysOnChannelCounts()
                ),
                events = events
            ),
            configStore = FakeAlwaysOnConfigStore(enabled = true, events = events)
        )

        coordinator.reconcile(AlwaysOnTrigger.INITIALIZE)

        assertEquals(
            listOf(
                "cancel_recovery",
                "cancel_watchdog",
                "release_gateway_ownership",
                "stop_shell"
            ),
            events
        )
        assertEquals(AlwaysOnPhase.ACTION_REQUIRED, coordinator.status.value.phase)
        assertEquals(
            AlwaysOnActionRequiredReason.NO_CHANNEL_CONFIGURED,
            coordinator.status.value.actionRequired?.reason
        )
        assertEquals(AlwaysOnShellState.STOPPED, coordinator.status.value.shell)
        assertEquals(AlwaysOnRuntimeState.RUNNING, coordinator.status.value.runtime)
        assertEquals(AlwaysOnGatewayState.STOPPED, coordinator.status.value.gateway)
    }

    @Test
    fun `offline reconciliation keeps the shell ready but freezes transient retries`() = runBlocking {
        val events = mutableListOf<String>()
        val coordinator = AlwaysOnCoordinator(
            platform = FakeAlwaysOnPlatform(
                state = AlwaysOnPlatformSnapshot(
                    shell = AlwaysOnShellState.STOPPED,
                    network = AlwaysOnNetworkState.OFFLINE
                ),
                events = events
            ),
            gateway = FakeGatewayAvailability(
                state = GatewayAvailabilitySnapshot(
                    runtime = AlwaysOnRuntimeState.STOPPED,
                    channels = AlwaysOnChannelCounts(configured = 1)
                ),
                events = events
            ),
            configStore = FakeAlwaysOnConfigStore(enabled = true, events = events)
        )

        coordinator.reconcile(AlwaysOnTrigger.NETWORK_CHANGED)

        assertEquals(
            listOf("ensure_watchdog", "start_shell", "ensure_gateway"),
            events
        )
        assertEquals(AlwaysOnPhase.RECOVERING, coordinator.status.value.phase)
        assertEquals(AlwaysOnWaitingReason.NETWORK, coordinator.status.value.waitingFor)
        assertEquals(AlwaysOnShellState.RUNNING, coordinator.status.value.shell)
        assertEquals(AlwaysOnRuntimeState.RUNNING, coordinator.status.value.runtime)
        assertEquals(AlwaysOnGatewayState.RUNNING, coordinator.status.value.gateway)
    }

    @Test
    fun `background start rejection waits for a foreground trigger`() = runBlocking {
        val events = mutableListOf<String>()
        val coordinator = AlwaysOnCoordinator(
            platform = FakeAlwaysOnPlatform(
                state = AlwaysOnPlatformSnapshot(
                    shell = AlwaysOnShellState.STOPPED,
                    network = AlwaysOnNetworkState.ONLINE
                ),
                events = events,
                startResult = ShellStartResult.Rejected(
                    reason = AlwaysOnActionRequiredReason.BACKGROUND_START_RESTRICTED,
                    message = "Open PalmClaw to resume"
                )
            ),
            gateway = FakeGatewayAvailability(
                state = GatewayAvailabilitySnapshot(
                    runtime = AlwaysOnRuntimeState.STOPPED,
                    channels = AlwaysOnChannelCounts(configured = 1)
                ),
                events = events
            ),
            configStore = FakeAlwaysOnConfigStore(enabled = true, events = events)
        )

        coordinator.reconcile(AlwaysOnTrigger.BOOT_COMPLETED)

        assertEquals(
            listOf(
                "ensure_watchdog",
                "start_shell",
                "cancel_watchdog",
                "release_gateway_ownership"
            ),
            events
        )
        assertEquals(AlwaysOnPhase.ACTION_REQUIRED, coordinator.status.value.phase)
        assertEquals(AlwaysOnWaitingReason.USER_FOREGROUND, coordinator.status.value.waitingFor)
        assertEquals(
            AlwaysOnActionRequired(
                reason = AlwaysOnActionRequiredReason.BACKGROUND_START_RESTRICTED,
                message = "Open PalmClaw to resume"
            ),
            coordinator.status.value.actionRequired
        )
        assertEquals(AlwaysOnRuntimeState.STOPPED, coordinator.status.value.runtime)
    }

    @Test
    fun `system restriction blocks startup before side effects`() = runBlocking {
        val events = mutableListOf<String>()
        val coordinator = AlwaysOnCoordinator(
            platform = FakeAlwaysOnPlatform(
                state = AlwaysOnPlatformSnapshot(
                    shell = AlwaysOnShellState.STOPPED,
                    network = AlwaysOnNetworkState.ONLINE,
                    startConstraint = AlwaysOnStartConstraint.SYSTEM_RESTRICTED
                ),
                events = events
            ),
            gateway = FakeGatewayAvailability(
                state = GatewayAvailabilitySnapshot(
                    runtime = AlwaysOnRuntimeState.STOPPED,
                    channels = AlwaysOnChannelCounts(configured = 1)
                ),
                events = events
            ),
            configStore = FakeAlwaysOnConfigStore(enabled = true, events = events)
        )

        coordinator.reconcile(AlwaysOnTrigger.INITIALIZE)

        assertEquals(listOf("release_gateway_ownership"), events)
        assertEquals(AlwaysOnPhase.ACTION_REQUIRED, coordinator.status.value.phase)
        assertEquals(
            AlwaysOnActionRequiredReason.SYSTEM_RESTRICTED,
            coordinator.status.value.actionRequired?.reason
        )
    }

    @Test
    fun `partially healthy channels report degraded without restarting healthy runtime`() = runBlocking {
        val events = mutableListOf<String>()
        val coordinator = AlwaysOnCoordinator(
            platform = FakeAlwaysOnPlatform(
                state = AlwaysOnPlatformSnapshot(
                    shell = AlwaysOnShellState.RUNNING,
                    network = AlwaysOnNetworkState.ONLINE
                ),
                events = events
            ),
            gateway = FakeGatewayAvailability(
                state = GatewayAvailabilitySnapshot(
                    runtime = AlwaysOnRuntimeState.RUNNING,
                    gateway = AlwaysOnGatewayState.RUNNING,
                    channels = AlwaysOnChannelCounts(
                        configured = 3,
                        ready = 1,
                        reconnecting = 1,
                        blocked = 1
                    )
                ),
                events = events
            ),
            configStore = FakeAlwaysOnConfigStore(enabled = true, events = events)
        )

        coordinator.reconcile(AlwaysOnTrigger.GATEWAY_STATE_CHANGED)

        assertEquals(listOf("ensure_watchdog"), events)
        assertEquals(AlwaysOnPhase.DEGRADED, coordinator.status.value.phase)
    }

    @Test
    fun `recoverable shell failure schedules one bounded retry`() = runBlocking {
        val events = mutableListOf<String>()
        val coordinator = AlwaysOnCoordinator(
            platform = FakeAlwaysOnPlatform(
                state = AlwaysOnPlatformSnapshot(
                    shell = AlwaysOnShellState.STOPPED,
                    network = AlwaysOnNetworkState.ONLINE
                ),
                events = events,
                startResult = ShellStartResult.Failed("temporary failure")
            ),
            gateway = FakeGatewayAvailability(
                state = GatewayAvailabilitySnapshot(
                    runtime = AlwaysOnRuntimeState.STOPPED,
                    channels = AlwaysOnChannelCounts(configured = 1)
                ),
                events = events
            ),
            configStore = FakeAlwaysOnConfigStore(enabled = true, events = events)
        )

        coordinator.reconcile(AlwaysOnTrigger.WATCHDOG)

        assertEquals(
            listOf("ensure_watchdog", "start_shell", "schedule_recovery:15000"),
            events
        )
        assertEquals(AlwaysOnPhase.RECOVERING, coordinator.status.value.phase)
        assertEquals(AlwaysOnWaitingReason.SHELL, coordinator.status.value.waitingFor)
        assertEquals("temporary failure", coordinator.status.value.lastError)
    }

    @Test
    fun `notification projection failure does not abort reconciliation`() = runBlocking {
        val events = mutableListOf<String>()
        val coordinator = AlwaysOnCoordinator(
            platform = FakeAlwaysOnPlatform(
                state = AlwaysOnPlatformSnapshot(
                    shell = AlwaysOnShellState.RUNNING,
                    network = AlwaysOnNetworkState.ONLINE,
                    watchdogScheduled = true
                ),
                events = events,
                failActionUpdate = true
            ),
            gateway = FakeGatewayAvailability(
                state = GatewayAvailabilitySnapshot(
                    runtime = AlwaysOnRuntimeState.RUNNING,
                    gateway = AlwaysOnGatewayState.RUNNING,
                    channels = AlwaysOnChannelCounts(configured = 1, ready = 1)
                ),
                events = events
            ),
            configStore = FakeAlwaysOnConfigStore(enabled = true, events = events)
        )

        coordinator.reconcile(AlwaysOnTrigger.GATEWAY_STATE_CHANGED)

        assertEquals(AlwaysOnPhase.ONLINE, coordinator.status.value.phase)
        assertEquals(AlwaysOnTrigger.GATEWAY_STATE_CHANGED, coordinator.status.value.lastTrigger)
    }

    @Test
    fun `failed disable cleanup stops the shell and schedules one cleanup retry`() = runBlocking {
        val events = mutableListOf<String>()
        val gateway = FakeGatewayAvailability(
            state = GatewayAvailabilitySnapshot(
                runtime = AlwaysOnRuntimeState.RUNNING,
                gateway = AlwaysOnGatewayState.RUNNING,
                channels = AlwaysOnChannelCounts(configured = 1, ready = 1)
            ),
            events = events,
            releaseResult = GatewayReleaseResult.Failed(
                "Gateway ownership could not be released"
            )
        )
        val coordinator = AlwaysOnCoordinator(
            platform = FakeAlwaysOnPlatform(
                state = AlwaysOnPlatformSnapshot(
                    shell = AlwaysOnShellState.RUNNING,
                    network = AlwaysOnNetworkState.ONLINE
                ),
                events = events
            ),
            gateway = gateway,
            configStore = FakeAlwaysOnConfigStore(enabled = true, events = events)
        )

        coordinator.setEnabled(false)

        assertEquals(
            listOf(
                "save:false",
                "release_gateway_ownership",
                "stop_shell",
                "schedule_recovery:15000"
            ),
            events
        )
        assertEquals(AlwaysOnPhase.DISABLED, coordinator.status.value.phase)
        assertEquals(AlwaysOnShellState.STOPPED, coordinator.status.value.shell)
        assertEquals("Gateway ownership could not be released", coordinator.status.value.lastError)

        gateway.releaseResult = GatewayReleaseResult.Released
        events.clear()
        coordinator.reconcile(AlwaysOnTrigger.RECOVERY_DUE)

        assertEquals(
            listOf("cancel_recovery", "release_gateway_ownership"),
            events
        )
        assertEquals(AlwaysOnPhase.DISABLED, coordinator.status.value.phase)
        assertEquals(AlwaysOnShellState.STOPPED, coordinator.status.value.shell)
    }

    private class FakeAlwaysOnConfigStore(
        private var enabled: Boolean,
        private val events: MutableList<String>
    ) : AlwaysOnConfigStore {
        override suspend fun isEnabled(): Boolean = enabled

        override suspend fun setEnabled(enabled: Boolean) {
            events += "save:$enabled"
            this.enabled = enabled
        }
    }

    private class FakeAlwaysOnPlatform(
        var state: AlwaysOnPlatformSnapshot,
        private val events: MutableList<String>,
        private val startResult: ShellStartResult = ShellStartResult.Started,
        private val failActionUpdate: Boolean = false
    ) : AlwaysOnPlatform {
        override suspend fun snapshot(): AlwaysOnPlatformSnapshot = state

        override suspend fun startShell(): ShellStartResult {
            events += "start_shell"
            if (startResult == ShellStartResult.Started ||
                startResult == ShellStartResult.AlreadyRunning
            ) {
                state = state.copy(shell = AlwaysOnShellState.RUNNING)
            }
            return startResult
        }

        override suspend fun stopShell() {
            events += "stop_shell"
            state = state.copy(shell = AlwaysOnShellState.STOPPED)
        }

        override suspend fun scheduleRecovery(delayMillis: Long) {
            events += "schedule_recovery:$delayMillis"
            state = state.copy(transientRecoveryScheduled = true)
        }

        override suspend fun cancelRecovery() {
            events += "cancel_recovery"
            state = state.copy(transientRecoveryScheduled = false)
        }

        override suspend fun ensureWatchdog() {
            events += "ensure_watchdog"
            state = state.copy(watchdogScheduled = true)
        }

        override suspend fun cancelWatchdog() {
            events += "cancel_watchdog"
            state = state.copy(watchdogScheduled = false)
        }

        override suspend fun updateActionRequired(action: AlwaysOnActionRequired?) {
            if (failActionUpdate) {
                throw IllegalStateException("notification unavailable")
            }
        }
    }

    private class FakeGatewayAvailability(
        var state: GatewayAvailabilitySnapshot,
        private val events: MutableList<String>,
        var releaseResult: GatewayReleaseResult = GatewayReleaseResult.Released
    ) : GatewayAvailability {
        override suspend fun snapshot(): GatewayAvailabilitySnapshot = state

        override suspend fun ensureAvailable(): GatewayEnsureResult {
            events += "ensure_gateway"
            state = state.copy(
                runtime = AlwaysOnRuntimeState.RUNNING,
                gateway = AlwaysOnGatewayState.RUNNING,
                channels = state.channels.copy(ready = state.channels.configured)
            )
            return GatewayEnsureResult.Available
        }

        override suspend fun releaseOwnership(): GatewayReleaseResult {
            events += "release_gateway_ownership"
            if (releaseResult == GatewayReleaseResult.Released) {
                state = state.copy(
                    gateway = AlwaysOnGatewayState.STOPPED,
                    channels = state.channels.copy(
                        ready = 0,
                        reconnecting = 0,
                        blocked = 0
                    )
                )
            }
            return releaseResult
        }
    }
}
