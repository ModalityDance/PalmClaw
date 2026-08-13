package com.palmclaw.runtime

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class BackgroundWorkerCancellationGuardTest {
    @Test
    fun `cron and heartbeat workers preserve coroutine cancellation`() {
        assertDoWorkRethrowsCancellation(
            sourcePath = "app/src/main/java/com/palmclaw/cron/CronDispatchWorker.kt"
        )
        assertDoWorkRethrowsCancellation(
            sourcePath = "app/src/main/java/com/palmclaw/heartbeat/HeartbeatDispatchWorker.kt"
        )
    }

    private fun assertDoWorkRethrowsCancellation(sourcePath: String) {
        val source = resolveSource(sourcePath).readText()
        val doWorkBody = source
            .substringAfter("override suspend fun doWork(): Result")
            .substringBefore("companion object")
        val cancellationCatch = doWorkBody.indexOf("catch (cancelled: CancellationException)")
        val cancellationRethrow = doWorkBody.indexOf("throw cancelled", cancellationCatch)
        val retryableCatch = doWorkBody.indexOf("catch (error: Exception)")

        assertTrue("$sourcePath must catch cancellation explicitly", cancellationCatch >= 0)
        assertTrue("$sourcePath must rethrow cancellation", cancellationRethrow > cancellationCatch)
        assertTrue(
            "$sourcePath must classify ordinary failures only after cancellation",
            retryableCatch > cancellationRethrow
        )
    }

    private fun resolveSource(path: String): File = sequenceOf(
        File(path),
        File(path.removePrefix("app/"))
    ).first(File::exists)
}
