package com.palmclaw.runtime

import java.util.Collections
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SessionTurnCoordinatorTest {

    @Test
    fun `turns for different sessions can run concurrently`() = runBlocking {
        val coordinator = SessionTurnCoordinator(maxConcurrentTurns = 2)
        val releaseA = CompletableDeferred<Unit>()
        val enteredA = CompletableDeferred<Unit>()
        val enteredB = CompletableDeferred<Unit>()

        val jobA = launch {
            coordinator.withSessionTurn("session-a") {
                enteredA.complete(Unit)
                releaseA.await()
            }
        }
        enteredA.await()

        val jobB = launch {
            coordinator.withSessionTurn("session-b") {
                enteredB.complete(Unit)
            }
        }

        withTimeout(500) { enteredB.await() }
        releaseA.complete(Unit)
        jobA.join()
        jobB.join()
    }

    @Test
    fun `turns for same session are serialized`() = runBlocking {
        val coordinator = SessionTurnCoordinator(maxConcurrentTurns = 2)
        val releaseFirst = CompletableDeferred<Unit>()
        val enteredFirst = CompletableDeferred<Unit>()
        val enteredSecond = CompletableDeferred<Unit>()

        val first = launch {
            coordinator.withSessionTurn("session-a") {
                enteredFirst.complete(Unit)
                releaseFirst.await()
            }
        }
        enteredFirst.await()

        val second = async {
            coordinator.withSessionTurn("session-a") {
                enteredSecond.complete(Unit)
            }
        }

        assertNull(withTimeoutOrNull(120) { enteredSecond.await() })
        releaseFirst.complete(Unit)
        withTimeout(500) { enteredSecond.await() }
        first.join()
        second.await()
    }

    @Test
    fun `global limit caps cross session turns`() = runBlocking {
        val coordinator = SessionTurnCoordinator(maxConcurrentTurns = 2)
        val active = Collections.synchronizedList(mutableListOf<String>())
        val release = CompletableDeferred<Unit>()

        val jobs = listOf("a", "b", "c").map { sessionId ->
            launch {
                coordinator.withSessionTurn(sessionId) {
                    active += sessionId
                    release.await()
                }
            }
        }

        withTimeout(500) {
            while (active.size < 2) {
                delay(10)
            }
        }
        assertEquals(2, active.size)
        delay(120)
        assertEquals(2, active.size)
        release.complete(Unit)
        jobs.forEach { it.join() }
        assertEquals(3, active.size)
    }
}
