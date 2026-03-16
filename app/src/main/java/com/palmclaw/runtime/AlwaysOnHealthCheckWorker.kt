package com.palmclaw.runtime

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.palmclaw.config.ConfigStore
import java.util.concurrent.TimeUnit

class AlwaysOnHealthCheckWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val appContext = applicationContext
        return runCatching {
            val enabled = ConfigStore(appContext).getAlwaysOnConfig().enabled
            if (!enabled) {
                cancel(appContext)
                Log.d(TAG, "Always-on health check skipped: disabled")
                return Result.success()
            }

            // Second recovery chain: try reviving foreground service every 15 minutes.
            AlwaysOnModeController.startService(appContext)
            Result.success()
        }.getOrElse { t ->
            Log.e(TAG, "Always-on health check failed", t)
            Result.retry()
        }
    }

    companion object {
        private const val TAG = "AlwaysOnHealthWorker"
        private const val UNIQUE_WORK_NAME = "palmclaw_always_on_health_check"

        fun ensureScheduled(context: Context) {
            val request = PeriodicWorkRequestBuilder<AlwaysOnHealthCheckWorker>(
                15,
                TimeUnit.MINUTES
            ).build()

            WorkManager.getInstance(context.applicationContext).enqueueUniquePeriodicWork(
                UNIQUE_WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request
            )
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context.applicationContext).cancelUniqueWork(UNIQUE_WORK_NAME)
        }
    }
}
