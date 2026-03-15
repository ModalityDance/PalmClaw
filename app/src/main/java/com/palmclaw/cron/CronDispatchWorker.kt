package com.palmclaw.cron

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.palmclaw.config.ConfigStore
import com.palmclaw.runtime.AlwaysOnModeController
import com.palmclaw.runtime.RuntimeController

class CronDispatchWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val mode = inputData.getString(KEY_MODE) ?: MODE_PROCESS_DUE
        return runCatching {
            val appContext = applicationContext
            if (ConfigStore(appContext).getAlwaysOnConfig().enabled) {
                AlwaysOnModeController.startService(appContext)
                AlwaysOnModeController.processDueCronJobs(resync = mode == MODE_RESYNC)
            } else {
                RuntimeController.processDueCronJobs(appContext, resync = mode == MODE_RESYNC)
            }
            Result.success()
        }.getOrElse { t ->
            Log.e(TAG, "Cron worker failed mode=$mode", t)
            Result.retry()
        }
    }

    companion object {
        private const val TAG = "CronDispatchWorker"
        private const val KEY_MODE = "mode"
        private const val MODE_PROCESS_DUE = "process_due"
        private const val MODE_RESYNC = "resync"

        fun enqueueProcessDue(context: Context) {
            enqueue(context, MODE_PROCESS_DUE)
        }

        fun enqueueResync(context: Context) {
            enqueue(context, MODE_RESYNC)
        }

        private fun enqueue(context: Context, mode: String) {
            val request = OneTimeWorkRequestBuilder<CronDispatchWorker>()
                .setInputData(workDataOf(KEY_MODE to mode))
                .build()
            WorkManager.getInstance(context.applicationContext).enqueue(request)
        }
    }
}
