package com.palmclaw.cron

sealed interface CronFieldUpdate<out T> {
    data object Unchanged : CronFieldUpdate<Nothing>
    data class Set<T>(val value: T) : CronFieldUpdate<T>
}

data class CronJobUpdate(
    val name: CronFieldUpdate<String> = CronFieldUpdate.Unchanged,
    val enabled: CronFieldUpdate<Boolean> = CronFieldUpdate.Unchanged,
    val schedule: CronFieldUpdate<CronSchedule> = CronFieldUpdate.Unchanged,
    val message: CronFieldUpdate<String> = CronFieldUpdate.Unchanged,
    val deliver: CronFieldUpdate<Boolean> = CronFieldUpdate.Unchanged,
    val channel: CronFieldUpdate<String?> = CronFieldUpdate.Unchanged,
    val to: CronFieldUpdate<String?> = CronFieldUpdate.Unchanged,
    val sessionId: CronFieldUpdate<String?> = CronFieldUpdate.Unchanged,
    val deleteAfterRun: CronFieldUpdate<Boolean> = CronFieldUpdate.Unchanged
) {
    fun hasChanges(): Boolean = listOf(
        name,
        enabled,
        schedule,
        message,
        deliver,
        channel,
        to,
        sessionId,
        deleteAfterRun
    ).any { it !== CronFieldUpdate.Unchanged }
}

internal object CronJobUpdatePlanner {
    fun apply(
        existing: CronJob,
        update: CronJobUpdate,
        nowMs: Long,
        validateSchedule: (CronSchedule) -> Unit,
        nextRunAtMs: (CronSchedule) -> Long?
    ): CronJob {
        require(update.hasChanges()) { "At least one job field is required." }

        val name = update.name.resolveRequiredText(existing.name, "name")
        val message = update.message.resolveRequiredText(existing.payload.message, "message")

        val schedule = update.schedule.resolve(existing.schedule)
        val scheduleChanged = update.schedule !== CronFieldUpdate.Unchanged
        if (scheduleChanged) {
            validateSchedule(schedule)
        }

        val enabled = update.enabled.resolve(existing.enabled)
        val shouldRecomputeNextRun = scheduleChanged ||
            (!existing.enabled && enabled) ||
            (enabled && existing.state.nextRunAtMs == null)
        val nextRun = when {
            !enabled -> null
            shouldRecomputeNextRun -> requireNotNull(nextRunAtMs(schedule)) {
                "schedule does not produce a future run."
            }
            else -> existing.state.nextRunAtMs
        }

        return existing.copy(
            name = name,
            enabled = enabled,
            schedule = schedule,
            payload = existing.payload.copy(
                message = message,
                deliver = update.deliver.resolve(existing.payload.deliver),
                channel = update.channel.resolve(existing.payload.channel),
                to = update.to.resolve(existing.payload.to),
                sessionId = update.sessionId.resolve(existing.payload.sessionId)
            ),
            state = existing.state.copy(nextRunAtMs = nextRun),
            updatedAtMs = nowMs,
            deleteAfterRun = update.deleteAfterRun.resolve(existing.deleteAfterRun)
        )
    }

    private fun <T> CronFieldUpdate<T>.resolve(existing: T): T = when (this) {
        CronFieldUpdate.Unchanged -> existing
        is CronFieldUpdate.Set -> value
    }

    private fun CronFieldUpdate<String>.resolveRequiredText(
        existing: String,
        field: String
    ): String = when (this) {
        CronFieldUpdate.Unchanged -> existing
        is CronFieldUpdate.Set -> value.trim().also {
            require(it.isNotBlank()) { "$field must not be blank." }
        }
    }
}
