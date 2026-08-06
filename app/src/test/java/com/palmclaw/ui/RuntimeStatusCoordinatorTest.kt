package com.palmclaw.ui

import com.palmclaw.runtime.AlwaysOnRuntimeStatus
import com.palmclaw.runtime.RuntimeControllerStatus
import com.palmclaw.ui.domain.RuntimeRefreshGateway
import com.palmclaw.ui.domain.RuntimeStatusSource
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
        statusSource.alwaysOn.value = AlwaysOnRuntimeStatus(
            serviceRunning = true,
            notificationActive = true,
            gatewayRunning = true,
            activeAdapterCount = 2,
            startedAtMs = 42L,
            lastError = "status error",
            processingSessionIds = setOf("always-on")
        )
        yield()

        val alwaysOn = stateStore.alwaysOnSettingsState.value
        assertTrue(alwaysOn.serviceRunning)
        assertTrue(alwaysOn.notificationActive)
        assertTrue(alwaysOn.gatewayRunning)
        assertEquals(2, alwaysOn.activeAdapterCount)
        assertEquals(42L, alwaysOn.startedAtMs)
        assertEquals("status error", alwaysOn.lastError)
        assertTrue(processing.isSessionProcessing("foreground"))
        assertTrue(processing.isSessionProcessing("always-on"))
        assertEquals(changesAfterStart + 2, processingChanges)
        assertEquals(0, refresh.gatewayRefreshes)

        scope.cancel()
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
        val alwaysOn = MutableStateFlow(AlwaysOnRuntimeStatus())

        override val runtimeStatus = runtime
        override val alwaysOnStatus = alwaysOn

        override fun currentAlwaysOnStatus(): AlwaysOnRuntimeStatus = alwaysOn.value
    }

    private class FakeRuntimeRefreshGateway : RuntimeRefreshGateway {
        var gatewayRefreshes = 0

        override fun refreshGatewayRuntimeConfig() {
            gatewayRefreshes += 1
        }

        override fun refreshToolRuntimeConfig() = Unit

        override fun reloadAutomation() = Unit

        override fun reloadMcp() = Unit

        override fun reloadAll() = Unit
    }
}
