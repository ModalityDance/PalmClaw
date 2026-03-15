package com.palmclaw.cron

data class CronSchedule(
    val kind: String,
    val atMs: Long? = null,
    val everyMs: Long? = null,
    val expr: String? = null,
    val tz: String? = null
)

data class CronPayload(
    val kind: String = "agent_turn",
    val message: String = "",
    val deliver: Boolean = false,
    val channel: String? = null,
    val to: String? = null,
    val sessionId: String? = null
)

data class CronJobState(
    val nextRunAtMs: Long? = null,
    val lastRunAtMs: Long? = null,
    val lastStatus: String? = null,
    val lastError: String? = null
)

data class CronJob(
    val id: String,
    val name: String,
    val enabled: Boolean = true,
    val schedule: CronSchedule,
    val payload: CronPayload = CronPayload(),
    val state: CronJobState = CronJobState(),
    val createdAtMs: Long,
    val updatedAtMs: Long,
    val deleteAfterRun: Boolean = false
)

object CronKinds {
    const val AT = "at"
    const val EVERY = "every"
    const val CRON = "cron"
}

object CronStatus {
    const val OK = "ok"
    const val ERROR = "error"
}
