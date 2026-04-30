package com.palmclaw.cron

import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

class CronExecutionPromptBuilderTest {

    @Test
    fun `build includes absolute execution timestamps and guardrails for relative time phrasing`() {
        val prompt = CronExecutionPromptBuilder.build(
            job = CronJob(
                id = "job-1",
                name = "Drink water reminder",
                schedule = CronSchedule(kind = CronKinds.AT, atMs = 1_710_000_000_000),
                payload = CronPayload(message = "Remind me in 2 hours to drink water"),
                state = CronJobState(nextRunAtMs = 1_710_000_000_000),
                createdAtMs = 1_709_999_000_000,
                updatedAtMs = 1_709_999_000_000
            ),
            nowMs = 1_710_000_000_000,
            timezone = TimeZone.getTimeZone("UTC")
        )

        assertTrue(prompt.contains("This is not a new scheduling request."))
        assertTrue(prompt.contains("Do not reinterpret relative time phrases"))
        assertTrue(prompt.contains("timezone_id=UTC"))
        assertTrue(prompt.contains("job_created_at=2024-03-09 15:43:20 +0000"))
        assertTrue(prompt.contains("scheduled_for_at=2024-03-09 16:00:00 +0000"))
        assertTrue(prompt.contains("execution_started_at=2024-03-09 16:00:00 +0000"))
        assertTrue(prompt.contains("Original request:\nRemind me in 2 hours to drink water"))
    }
}
