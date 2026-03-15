package com.palmclaw.cron

import com.palmclaw.storage.dao.CronJobDao
import com.palmclaw.storage.entities.CronJobEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class CronRepository(
    private val dao: CronJobDao
) {
    suspend fun listJobs(): List<CronJob> = withContext(Dispatchers.IO) {
        dao.getAll().map { it.toModel() }
    }

    suspend fun getJob(jobId: String): CronJob? = withContext(Dispatchers.IO) {
        dao.getById(jobId)?.toModel()
    }

    suspend fun upsert(job: CronJob) = withContext(Dispatchers.IO) {
        dao.upsert(job.toEntity())
    }

    suspend fun remove(jobId: String) = withContext(Dispatchers.IO) {
        dao.deleteById(jobId)
    }
}

private fun CronJobEntity.toModel(): CronJob {
    return CronJob(
        id = id,
        name = name,
        enabled = enabled,
        schedule = CronSchedule(
            kind = scheduleKind,
            atMs = scheduleAtMs,
            everyMs = scheduleEveryMs,
            expr = scheduleExpr,
            tz = scheduleTz
        ),
        payload = CronPayload(
            kind = payloadKind,
            message = payloadMessage,
            deliver = payloadDeliver,
            channel = payloadChannel,
            to = payloadTo,
            sessionId = payloadSessionId
        ),
        state = CronJobState(
            nextRunAtMs = nextRunAtMs,
            lastRunAtMs = lastRunAtMs,
            lastStatus = lastStatus,
            lastError = lastError
        ),
        createdAtMs = createdAtMs,
        updatedAtMs = updatedAtMs,
        deleteAfterRun = deleteAfterRun
    )
}

private fun CronJob.toEntity(): CronJobEntity {
    return CronJobEntity(
        id = id,
        name = name,
        enabled = enabled,
        scheduleKind = schedule.kind,
        scheduleAtMs = schedule.atMs,
        scheduleEveryMs = schedule.everyMs,
        scheduleExpr = schedule.expr,
        scheduleTz = schedule.tz,
        payloadKind = payload.kind,
        payloadMessage = payload.message,
        payloadDeliver = payload.deliver,
        payloadChannel = payload.channel,
        payloadTo = payload.to,
        payloadSessionId = payload.sessionId,
        nextRunAtMs = state.nextRunAtMs,
        lastRunAtMs = state.lastRunAtMs,
        lastStatus = state.lastStatus,
        lastError = state.lastError,
        createdAtMs = createdAtMs,
        updatedAtMs = updatedAtMs,
        deleteAfterRun = deleteAfterRun
    )
}
