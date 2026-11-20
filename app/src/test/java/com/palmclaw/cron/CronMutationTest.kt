package com.palmclaw.cron

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class CronMutationTest {
    @Test
    fun `scheduler failure preserves the saved job identity`(): Unit = runBlocking {
        var writes = 0
        try {
            commitCronChange("saved-job", persist = { writes++; "saved" }, reconcile = { error("alarm failed") })
            fail("Expected follow-up failure")
        } catch (error: CronMutationException) {
            assertEquals("saved-job", error.jobId)
            assertTrue(error.committed)
            assertEquals(1, writes)
        }
    }

    @Test
    fun `a failed persistence call does not claim a committed write`(): Unit = runBlocking {
        try {
            commitCronChange("attempted-job", persist = { error("storage failure") }, reconcile = { fail("Must not reconcile") })
            fail("Expected persistence failure")
        } catch (error: CronMutationException) {
            assertFalse(error.committed)
            assertEquals("attempted-job", error.jobId)
        }
    }

    @Test(expected = CancellationException::class)
    fun `cancellation is not converted to a retryable failure`(): Unit = runBlocking {
        commitCronChange("saved-job", persist = {}, reconcile = { throw CancellationException() })
    }
}
