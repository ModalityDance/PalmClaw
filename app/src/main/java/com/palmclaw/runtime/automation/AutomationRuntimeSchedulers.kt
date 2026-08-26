package com.palmclaw.runtime.automation

import com.palmclaw.cron.CronJob
import com.palmclaw.cron.CronService
import com.palmclaw.heartbeat.HeartbeatService

internal interface CronRuntimeScheduler {
    var onJob: (suspend (CronJob) -> String?)?
    var onLog: ((String) -> Unit)?

    fun updatePolicy(minEveryMs: Long, maxJobs: Int, logEnabled: Boolean)
    fun start()
    fun stop()
    suspend fun processDueJobs()
    suspend fun onSystemResync()
}

internal interface HeartbeatRuntimeScheduler {
    fun updateConfig(enabled: Boolean, intervalSeconds: Long)
    fun start()
    fun stop()
    fun armNextAlarm(timestampMs: Long)
}

internal class CronServiceRuntimeScheduler(
    private val service: CronService
) : CronRuntimeScheduler {
    override var onJob: (suspend (CronJob) -> String?)?
        get() = service.onJob
        set(value) {
            service.onJob = value
        }

    override var onLog: ((String) -> Unit)?
        get() = service.onLog
        set(value) {
            service.onLog = value
        }

    override fun updatePolicy(minEveryMs: Long, maxJobs: Int, logEnabled: Boolean) {
        service.updatePolicy(minEveryMs, maxJobs, logEnabled)
    }

    override fun start() = service.start()
    override fun stop() = service.stop()
    override suspend fun processDueJobs() = service.processDueJobs()
    override suspend fun onSystemResync() = service.onSystemResync()
}

internal class HeartbeatServiceRuntimeScheduler(
    private val service: HeartbeatService
) : HeartbeatRuntimeScheduler {
    override fun updateConfig(enabled: Boolean, intervalSeconds: Long) {
        service.updateConfig(enabled, intervalSeconds)
    }

    override fun start() = service.start()
    override fun stop() = service.stop()
    override fun armNextAlarm(timestampMs: Long) = service.armNextAlarm(timestampMs)
}
