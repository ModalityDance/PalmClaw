package com.palmclaw.ui

import com.palmclaw.config.AppSession
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GatewayProcessingCoordinatorTest {

    @Test
    fun `requestGatewayRefresh returns true when no sessions are processing`() {
        val coordinator = GatewayProcessingCoordinator()

        assertTrue(coordinator.requestGatewayRefresh())
    }

    @Test
    fun `requestGatewayRefresh is deferred until all processing sessions finish`() {
        val coordinator = GatewayProcessingCoordinator()

        val startResult = coordinator.updateLocalProcessingSession("session-a", processing = true)
        assertFalse(startResult.shouldRefreshGateway)

        assertFalse(coordinator.requestGatewayRefresh())
        assertFalse(
            coordinator.updateRuntimeProcessingSessions(listOf("session-a")).shouldRefreshGateway
        )

        val finishLocal = coordinator.updateLocalProcessingSession("session-a", processing = false)
        assertFalse(finishLocal.shouldRefreshGateway)

        val finishRuntime = coordinator.updateRuntimeProcessingSessions(emptyList())
        assertTrue(finishRuntime.shouldRefreshGateway)
    }

    @Test
    fun `processing state merges local runtime and always on sessions`() {
        val coordinator = GatewayProcessingCoordinator()

        coordinator.updateLocalProcessingSession("session-local", processing = true)
        coordinator.updateRuntimeProcessingSessions(listOf("session-runtime"))
        coordinator.updateAlwaysOnProcessingSessions(listOf("session-always-on"))

        assertTrue(coordinator.isSessionProcessing("session-local"))
        assertTrue(coordinator.isSessionProcessing("session-runtime"))
        assertTrue(coordinator.isSessionProcessing("session-always-on"))
        assertFalse(coordinator.isSessionProcessing("session-missing"))
    }

    @Test
    fun `blank session ids are ignored`() {
        val coordinator = GatewayProcessingCoordinator()

        coordinator.updateRuntimeProcessingSessions(listOf(" ", "\t", "session-a"))
        coordinator.updateAlwaysOnProcessingSessions(listOf(""))
        coordinator.updateLocalProcessingSession("   ", processing = true)

        assertTrue(coordinator.isSessionProcessing("session-a"))
        assertFalse(coordinator.isSessionProcessing("   "))
    }

    @Test
    fun `blank session query falls back to local session`() {
        val coordinator = GatewayProcessingCoordinator()

        coordinator.updateLocalProcessingSession(AppSession.LOCAL_SESSION_ID, processing = true)

        assertTrue(coordinator.isSessionProcessing(""))
    }
}
