package com.palmclaw.heartbeat

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.palmclaw.config.ConfigStore
import com.palmclaw.runtime.AlwaysOnModeController
import com.palmclaw.runtime.RuntimeController

class HeartbeatDispatchWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val appContext = applicationContext
        val mode = inputData.getString(KEY_MODE) ?: MODE_PROCESS_DUE
        val configStore = ConfigStore(appContext)
        val heartbeatConfig = configStore.getHeartbeatConfig()
        val heartbeatService = HeartbeatService(appContext).apply {
            updateConfig(enabled = heartbeatConfig.enabled, intervalSeconds = heartbeatConfig.intervalSeconds)
        }

        if (!heartbeatConfig.enabled) {
            heartbeatService.stop()
            Log.d(TAG, "Heartbeat worker mode=$mode ignored: disabled")
            return Result.success()
        }

        return runCatching {
            if (configStore.getAlwaysOnConfig().enabled) {
                AlwaysOnModeController.startService(appContext)
                AlwaysOnModeController.processHeartbeatTick()
            } else {
                RuntimeController.processHeartbeatTick(appContext)
            }

            val latestConfig = ConfigStore(appContext).getHeartbeatConfig()
            heartbeatService.updateConfig(
                enabled = latestConfig.enabled,
                intervalSeconds = latestConfig.intervalSeconds
            )
            if (latestConfig.enabled) {
                val nextAt = System.currentTimeMillis() + latestConfig.intervalSeconds * 1_000L
                heartbeatService.armNextAlarm(nextAt)
            } else {
                heartbeatService.stop()
            }
            Result.success()
        }.getOrElse { t ->
            Log.e(TAG, "Heartbeat worker failed mode=$mode", t)
            Result.retry()
        }
    }

    companion object {
        private const val TAG = "HeartbeatWorker"
        private const val KEY_MODE = "mode"
        private const val MODE_PROCESS_DUE = "process_due"
        private const val MODE_RESYNC = "resync"
        private const val UNIQUE_WORK_NAME = "palmclaw_heartbeat_dispatch"

        fun enqueueProcessDue(context: Context) {
            enqueue(context, MODE_PROCESS_DUE)
        }

        fun enqueueResync(context: Context) {
            enqueue(context, MODE_RESYNC)
        }

        private fun enqueue(context: Context, mode: String) {
            val request = OneTimeWorkRequestBuilder<HeartbeatDispatchWorker>()
                .setInputData(workDataOf(KEY_MODE to mode))
                .build()
            WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
                UNIQUE_WORK_NAME,
                ExistingWorkPolicy.KEEP,
                request
            )
        }
    }
}
