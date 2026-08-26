package com.palmclaw.runtime.automation

import com.palmclaw.config.CronConfig
import com.palmclaw.config.HeartbeatConfig
import com.palmclaw.cron.CronJob
import com.palmclaw.cron.CronService
import com.palmclaw.heartbeat.HeartbeatService

internal class AutomationRuntimeLifecycle(
    private val cronScheduler: CronRuntimeScheduler,
    private val heartbeatScheduler: HeartbeatRuntimeScheduler,
    onCronJob: suspend (CronJob) -> String?,
    onCronLog: (String) -> Unit
) {
    constructor(
        cronService: CronService,
        heartbeatService: HeartbeatService,
        onCronJob: suspend (CronJob) -> String?,
        onCronLog: (String) -> Unit
    ) : this(
        cronScheduler = CronServiceRuntimeScheduler(cronService),
        heartbeatScheduler = HeartbeatServiceRuntimeScheduler(heartbeatService),
        onCronJob = onCronJob,
        onCronLog = onCronLog
    )

    private val ownedCronJobCallback: suspend (CronJob) -> String? = { job -> onCronJob(job) }
    private val ownedCronLogCallback: (String) -> Unit = { line -> onCronLog(line) }
    private var started = false
    private var closed = false

    fun start(cronConfig: CronConfig, heartbeatConfig: HeartbeatConfig) {
        check(!closed) { "Automation runtime lifecycle is closed" }
        check(!started) { "Automation runtime lifecycle is already started" }
        cronScheduler.onJob = ownedCronJobCallback
        cronScheduler.onLog = ownedCronLogCallback
        started = true
        reload(cronConfig, heartbeatConfig)
    }

    fun reload(cronConfig: CronConfig, heartbeatConfig: HeartbeatConfig) {
        requireActive()
        applyCronConfig(cronConfig)
        applyHeartbeatConfig(heartbeatConfig)
    }

    fun applyCronConfig(config: CronConfig) {
        requireActive()
        cronScheduler.updatePolicy(
            minEveryMs = config.minEveryMs,
            maxJobs = config.maxJobs,
            logEnabled = config.enabled
        )
        if (config.enabled) cronScheduler.start() else cronScheduler.stop()
    }

    fun applyHeartbeatConfig(config: HeartbeatConfig) {
        requireActive()
        heartbeatScheduler.updateConfig(
            enabled = config.enabled,
            intervalSeconds = config.intervalSeconds
        )
        if (config.enabled) heartbeatScheduler.start() else heartbeatScheduler.stop()
    }

    fun armNextHeartbeatAlarm(timestampMs: Long) {
        requireActive()
        heartbeatScheduler.armNextAlarm(timestampMs)
    }

    suspend fun processDueCronJobs(resync: Boolean) {
        requireActive()
        if (resync) {
            cronScheduler.onSystemResync()
        } else {
            cronScheduler.processDueJobs()
        }
    }

    fun close() {
        if (closed) return
        closed = true
        if (cronScheduler.onJob === ownedCronJobCallback) {
            cronScheduler.onJob = null
        }
        if (cronScheduler.onLog === ownedCronLogCallback) {
            cronScheduler.onLog = null
        }
        started = false
    }

    private fun requireActive() {
        check(started && !closed) { "Automation runtime lifecycle is not active" }
    }
}
