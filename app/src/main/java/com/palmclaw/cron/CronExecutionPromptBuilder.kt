package com.palmclaw.cron

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Builds the internal prompt sent to the agent when a cron job fires.
 *
 * Keeping this logic outside of Android runtime orchestration makes the prompt
 * wording easier to reason about and easy to test in isolation.
 */
internal object CronExecutionPromptBuilder {

    fun build(
        job: CronJob,
        nowMs: Long = System.currentTimeMillis(),
        timezone: TimeZone = TimeZone.getDefault()
    ): String {
        val originalMessage = job.payload.message.trim().ifBlank { job.name }
        return buildString {
            appendLine("[Cron Execution Context]")
            appendLine("You are executing a cron job now.")
            appendLine("This is not a new scheduling request.")
            appendLine("Do the requested reminder/action now if it is due.")
            appendLine("Do not reinterpret relative time phrases from the original request relative to the current moment.")
            appendLine("The schedule has already been resolved by the app.")
            appendLine()
            appendLine("job_id=${job.id}")
            appendLine("job_name=${job.name}")
            appendLine("schedule_kind=${job.schedule.kind}")
            appendLine("timezone_id=${timezone.id}")
            appendLine("job_created_at=${formatTimestamp(job.createdAtMs, timezone)}")
            job.schedule.atMs?.let { appendLine("scheduled_for_at=${formatTimestamp(it, timezone)}") }
            job.state.nextRunAtMs?.let { appendLine("scheduled_run_time=${formatTimestamp(it, timezone)}") }
            appendLine("execution_started_at=${formatTimestamp(nowMs, timezone)}")
            appendLine()
            appendLine("Original request:")
            appendLine(originalMessage)
        }.trim()
    }

    private fun formatTimestamp(timestampMs: Long, timezone: TimeZone): String {
        val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US)
        formatter.timeZone = timezone
        return formatter.format(Date(timestampMs))
    }
}
