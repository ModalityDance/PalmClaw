package com.palmclaw.runtime.automation

import com.palmclaw.config.CronConfig
import com.palmclaw.config.HeartbeatConfig
import com.palmclaw.cron.CronJob
import com.palmclaw.cron.CronKinds
import com.palmclaw.cron.CronPayload
import com.palmclaw.cron.CronSchedule
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class AutomationRuntimeLifecycleTest {
    @Test
    fun `start registers callbacks before cron and heartbeat configuration`() {
        val events = mutableListOf<String>()
        val fixture = fixture(events)

        fixture.lifecycle.start(enabledCron(), enabledHeartbeat())

        assertEquals(
            listOf(
                "cron:job:set",
                "cron:log:set",
                "cron:update:60000:50:true",
                "cron:start",
                "heartbeat:update:true:900",
                "heartbeat:start"
            ),
            events
        )
    }

    @Test
    fun `disabled config stops both schedulers in stable order`() {
        val events = mutableListOf<String>()
        val fixture = fixture(events)

        fixture.lifecycle.start(disabledCron(), disabledHeartbeat())

        assertEquals(
            listOf(
                "cron:job:set",
                "cron:log:set",
                "cron:update:120000:25:false",
                "cron:stop",
                "heartbeat:update:false:1800",
                "heartbeat:stop"
            ),
            events
        )
    }

    @Test
    fun `reload reapplies configs without registering callbacks again`() {
        val events = mutableListOf<String>()
        val fixture = fixture(events)
        fixture.lifecycle.start(enabledCron(), enabledHeartbeat())
        events.clear()

        fixture.lifecycle.reload(disabledCron(), disabledHeartbeat())

        assertEquals(
            listOf(
                "cron:update:120000:25:false",
                "cron:stop",
                "heartbeat:update:false:1800",
                "heartbeat:stop"
            ),
            events
        )
    }

    @Test
    fun `individual config operations preserve service call order`() {
        val events = mutableListOf<String>()
        val fixture = fixture(events)
        fixture.lifecycle.start(enabledCron(), enabledHeartbeat())
        events.clear()

        fixture.lifecycle.applyCronConfig(disabledCron())
        fixture.lifecycle.applyHeartbeatConfig(disabledHeartbeat())

        assertEquals(
            listOf(
                "cron:update:120000:25:false",
                "cron:stop",
                "heartbeat:update:false:1800",
                "heartbeat:stop"
            ),
            events
        )
    }

    @Test
    fun `due processing selects normal or resync operation and forwards heartbeat alarm`() = runBlocking {
        val events = mutableListOf<String>()
        val fixture = fixture(events)
        fixture.lifecycle.start(enabledCron(), enabledHeartbeat())
        events.clear()

        fixture.lifecycle.processDueCronJobs(resync = false)
        fixture.lifecycle.processDueCronJobs(resync = true)
        fixture.lifecycle.armNextHeartbeatAlarm(123456L)

        assertEquals(
            listOf("cron:due", "cron:resync", "heartbeat:arm:123456"),
            events
        )
    }

    @Test
    fun `cron callbacks forward to runtime handlers`() = runBlocking {
        val handledJobs = mutableListOf<String>()
        val handledLogs = mutableListOf<String>()
        val cron = FakeCronRuntimeScheduler(mutableListOf())
        val heartbeat = FakeHeartbeatRuntimeScheduler(mutableListOf())
        val lifecycle = AutomationRuntimeLifecycle(
            cronScheduler = cron,
            heartbeatScheduler = heartbeat,
            onCronJob = { job ->
                handledJobs += job.id
                "handled:${job.id}"
            },
            onCronLog = { line -> handledLogs += line }
        )
        lifecycle.start(enabledCron(), enabledHeartbeat())

        val result = cron.onJob?.invoke(job("job-1"))
        cron.onLog?.invoke("line-1")

        assertEquals("handled:job-1", result)
        assertEquals(listOf("job-1"), handledJobs)
        assertEquals(listOf("line-1"), handledLogs)
    }

    @Test
    fun `close clears owned callbacks once without stopping schedulers`() {
        val events = mutableListOf<String>()
        val fixture = fixture(events)
        fixture.lifecycle.start(enabledCron(), enabledHeartbeat())
        events.clear()

        fixture.lifecycle.close()
        fixture.lifecycle.close()

        assertEquals(listOf("cron:job:clear", "cron:log:clear"), events)
        assertEquals(null, fixture.cron.onJob)
        assertEquals(null, fixture.cron.onLog)
        assertTrue(events.none { it == "cron:stop" || it == "heartbeat:stop" })
    }

    @Test
    fun `old lifecycle close does not clear newer lifecycle callbacks`() = runBlocking {
        val events = mutableListOf<String>()
        val cron = FakeCronRuntimeScheduler(events)
        val heartbeat = FakeHeartbeatRuntimeScheduler(events)
        val oldCalls = mutableListOf<String>()
        val newCalls = mutableListOf<String>()
        val oldLifecycle = AutomationRuntimeLifecycle(
            cronScheduler = cron,
            heartbeatScheduler = heartbeat,
            onCronJob = { job -> oldCalls += job.id; "old" },
            onCronLog = {}
        )
        val newLifecycle = AutomationRuntimeLifecycle(
            cronScheduler = cron,
            heartbeatScheduler = heartbeat,
            onCronJob = { job -> newCalls += job.id; "new" },
            onCronLog = {}
        )
        oldLifecycle.start(enabledCron(), enabledHeartbeat())
        newLifecycle.start(enabledCron(), enabledHeartbeat())
        val currentJobCallback = cron.onJob
        val currentLogCallback = cron.onLog

        oldLifecycle.close()

        assertSame(currentJobCallback, cron.onJob)
        assertSame(currentLogCallback, cron.onLog)
        assertEquals("new", cron.onJob?.invoke(job("latest")))
        assertTrue(oldCalls.isEmpty())
        assertEquals(listOf("latest"), newCalls)
    }

    private fun fixture(events: MutableList<String>): Fixture {
        val cron = FakeCronRuntimeScheduler(events)
        val heartbeat = FakeHeartbeatRuntimeScheduler(events)
        return Fixture(
            lifecycle = AutomationRuntimeLifecycle(
                cronScheduler = cron,
                heartbeatScheduler = heartbeat,
                onCronJob = { job -> "handled:${job.id}" },
                onCronLog = {}
            ),
            cron = cron
        )
    }

    private fun enabledCron() = CronConfig(enabled = true, minEveryMs = 60_000L, maxJobs = 50)
    private fun disabledCron() = CronConfig(enabled = false, minEveryMs = 120_000L, maxJobs = 25)
    private fun enabledHeartbeat() = HeartbeatConfig(enabled = true, intervalSeconds = 900L)
    private fun disabledHeartbeat() = HeartbeatConfig(enabled = false, intervalSeconds = 1_800L)

    private fun job(id: String) = CronJob(
        id = id,
        name = id,
        schedule = CronSchedule(kind = CronKinds.EVERY, everyMs = 60_000L),
        payload = CronPayload(message = "run"),
        createdAtMs = 1L,
        updatedAtMs = 1L
    )

    private data class Fixture(
        val lifecycle: AutomationRuntimeLifecycle,
        val cron: FakeCronRuntimeScheduler
    )

    private class FakeCronRuntimeScheduler(
        private val events: MutableList<String>
    ) : CronRuntimeScheduler {
        override var onJob: (suspend (CronJob) -> String?)? = null
            set(value) {
                field = value
                events += "cron:job:${if (value == null) "clear" else "set"}"
            }

        override var onLog: ((String) -> Unit)? = null
            set(value) {
                field = value
                events += "cron:log:${if (value == null) "clear" else "set"}"
            }

        override fun updatePolicy(minEveryMs: Long, maxJobs: Int, logEnabled: Boolean) {
            events += "cron:update:$minEveryMs:$maxJobs:$logEnabled"
        }

        override fun start() {
            events += "cron:start"
        }

        override fun stop() {
            events += "cron:stop"
        }

        override suspend fun processDueJobs() {
            events += "cron:due"
        }

        override suspend fun onSystemResync() {
            events += "cron:resync"
        }
    }

    private class FakeHeartbeatRuntimeScheduler(
        private val events: MutableList<String>
    ) : HeartbeatRuntimeScheduler {
        override fun updateConfig(enabled: Boolean, intervalSeconds: Long) {
            events += "heartbeat:update:$enabled:$intervalSeconds"
        }

        override fun start() {
            events += "heartbeat:start"
        }

        override fun stop() {
            events += "heartbeat:stop"
        }

        override fun armNextAlarm(timestampMs: Long) {
            events += "heartbeat:arm:$timestampMs"
        }
    }
}
