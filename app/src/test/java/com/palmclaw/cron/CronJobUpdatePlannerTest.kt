package com.palmclaw.cron

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class CronJobUpdatePlannerTest {
    @Test
    fun `payload update preserves schedule and execution state`() {
        val existing = job().copy(name = " Original ")

        val updated = CronJobUpdatePlanner.apply(
            existing = existing,
            update = CronJobUpdate(
                message = CronFieldUpdate.Set("updated message"),
                channel = CronFieldUpdate.Set(null)
            ),
            nowMs = 5_000L,
            validateSchedule = { error("schedule must not be revalidated") },
            nextRunAtMs = { error("next run must not be recomputed") }
        )

        assertEquals(existing.schedule, updated.schedule)
        assertEquals(existing.state, updated.state)
        assertEquals(" Original ", updated.name)
        assertEquals("updated message", updated.payload.message)
        assertNull(updated.payload.channel)
        assertEquals(5_000L, updated.updatedAtMs)
    }

    @Test
    fun `schedule update recomputes only next run and preserves last result`() {
        val schedule = CronSchedule(kind = CronKinds.EVERY, everyMs = 120_000L)

        val updated = CronJobUpdatePlanner.apply(
            existing = job(),
            update = CronJobUpdate(schedule = CronFieldUpdate.Set(schedule)),
            nowMs = 5_000L,
            validateSchedule = { assertEquals(schedule, it) },
            nextRunAtMs = { 125_000L }
        )

        assertEquals(schedule, updated.schedule)
        assertEquals(125_000L, updated.state.nextRunAtMs)
        assertEquals(1_000L, updated.state.lastRunAtMs)
        assertEquals(CronStatus.OK, updated.state.lastStatus)
        assertEquals("previous", updated.state.lastError)
    }

    @Test
    fun `disable clears next run and re-enable recomputes it`() {
        val disabled = CronJobUpdatePlanner.apply(
            existing = job(),
            update = CronJobUpdate(enabled = CronFieldUpdate.Set(false)),
            nowMs = 5_000L,
            validateSchedule = {},
            nextRunAtMs = { error("disable must not recompute") }
        )
        assertNull(disabled.state.nextRunAtMs)

        val enabled = CronJobUpdatePlanner.apply(
            existing = disabled,
            update = CronJobUpdate(enabled = CronFieldUpdate.Set(true)),
            nowMs = 6_000L,
            validateSchedule = {},
            nextRunAtMs = { 66_000L }
        )
        assertEquals(66_000L, enabled.state.nextRunAtMs)
    }

    @Test
    fun `updating an enabled job repairs a missing next run`() {
        val existing = job().copy(state = job().state.copy(nextRunAtMs = null))

        val updated = CronJobUpdatePlanner.apply(
            existing = existing,
            update = CronJobUpdate(message = CronFieldUpdate.Set("updated")),
            nowMs = 6_000L,
            validateSchedule = {},
            nextRunAtMs = { 66_000L }
        )

        assertEquals(66_000L, updated.state.nextRunAtMs)
    }

    @Test
    fun `empty update and blank managed text are rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            CronJobUpdatePlanner.apply(job(), CronJobUpdate(), 5_000L, {}, { 10_000L })
        }
        assertThrows(IllegalArgumentException::class.java) {
            CronJobUpdatePlanner.apply(
                job(),
                CronJobUpdate(name = CronFieldUpdate.Set("  ")),
                5_000L,
                {},
                { 10_000L }
            )
        }
    }

    private fun job() = CronJob(
        id = "job-1",
        name = "Original",
        enabled = true,
        schedule = CronSchedule(kind = CronKinds.EVERY, everyMs = 60_000L),
        payload = CronPayload(
            message = "original message",
            deliver = true,
            channel = "email",
            to = "person@example.com",
            sessionId = "session-1"
        ),
        state = CronJobState(
            nextRunAtMs = 60_000L,
            lastRunAtMs = 1_000L,
            lastStatus = CronStatus.OK,
            lastError = "previous"
        ),
        createdAtMs = 100L,
        updatedAtMs = 200L
    )
}
