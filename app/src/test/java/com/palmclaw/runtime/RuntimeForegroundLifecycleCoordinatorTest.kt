package com.palmclaw.runtime

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class RuntimeForegroundLifecycleCoordinatorTest {
    @Test
    fun `foreground lifecycle releases and reacquires through application scope`() = runBlocking {
        val events = mutableListOf<String>()
        val coordinator = RuntimeForegroundLifecycleCoordinator(
            scope = this,
            enterForeground = { events += "foreground" },
            leaveForeground = { events += "background" }
        )

        coordinator.requestForegrounded(true).join()
        coordinator.requestForegrounded(false).join()
        coordinator.requestForegrounded(true).join()

        assertEquals(
            listOf("foreground", "background", "foreground"),
            events
        )
    }

    @Test
    fun `latest desired foreground state wins while a transition is suspended`() = runBlocking {
        val events = mutableListOf<String>()
        val enterStarted = CompletableDeferred<Unit>()
        val allowEnter = CompletableDeferred<Unit>()
        val coordinator = RuntimeForegroundLifecycleCoordinator(
            scope = this,
            enterForeground = {
                events += "foreground"
                enterStarted.complete(Unit)
                allowEnter.await()
            },
            leaveForeground = { events += "background" }
        )

        val entering = coordinator.requestForegrounded(true)
        enterStarted.await()
        val staleBackground = coordinator.requestForegrounded(false)
        val latestForeground = coordinator.requestForegrounded(true)
        allowEnter.complete(Unit)
        joinAll(entering, staleBackground, latestForeground)

        assertEquals(listOf("foreground"), events)

        coordinator.requestForegrounded(false).join()
        assertEquals(listOf("foreground", "background"), events)
    }
}
