package com.palmclaw.runtime

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.palmclaw.runtime.alwayson.AlwaysOnRuntimeAccess
import com.palmclaw.runtime.alwayson.AlwaysOnTrigger
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlinx.coroutines.CancellationException

class AlwaysOnHealthCheckWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val trigger = inputData.getString(KEY_TRIGGER)
            ?.let { raw -> runCatching { AlwaysOnTrigger.valueOf(raw) }.getOrNull() }
            ?: AlwaysOnTrigger.WATCHDOG
        val isTransientRecovery = trigger == AlwaysOnTrigger.RECOVERY_DUE
        if (isTransientRecovery) {
            setTransientRecoveryScheduled(applicationContext, false)
        }
        return try {
            if (AlwaysOnRuntimeAccess.reconcile(trigger)) {
                Result.success()
            } else {
                if (isTransientRecovery) {
                    setTransientRecoveryScheduled(applicationContext, true)
                }
                Log.w(TAG, "Always-on coordinator is not installed; retrying")
                Result.retry()
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            if (isTransientRecovery) {
                setTransientRecoveryScheduled(applicationContext, true)
            }
            Log.e(TAG, "Always-on health check failed", error)
            Result.retry()
        }
    }

    companion object {
        private const val TAG = "AlwaysOnHealthWorker"
        private const val UNIQUE_WORK_NAME = "palmclaw_always_on_health_check"
        private const val UNIQUE_RECOVERY_WORK_NAME = "palmclaw_always_on_recovery"
        private const val STATE_PREFERENCES = "palmclaw_always_on_work_state"
        private const val KEY_TRANSIENT_RECOVERY_SCHEDULED = "transient_recovery_scheduled"
        private const val KEY_WATCHDOG_SCHEDULED = "watchdog_scheduled"
        private const val KEY_TRIGGER = "trigger"

        fun ensureScheduled(context: Context) {
            val request = PeriodicWorkRequestBuilder<AlwaysOnHealthCheckWorker>(
                15,
                TimeUnit.MINUTES
            )
                .setInputData(triggerData(AlwaysOnTrigger.WATCHDOG))
                .build()

            WorkManager.getInstance(context.applicationContext).enqueueUniquePeriodicWork(
                UNIQUE_WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request
            )
            setWatchdogScheduled(context, true)
        }

        fun scheduleRecovery(context: Context, delayMillis: Long) {
            val request = OneTimeWorkRequestBuilder<AlwaysOnHealthCheckWorker>()
                .setInitialDelay(max(0L, delayMillis), TimeUnit.MILLISECONDS)
                .setInputData(triggerData(AlwaysOnTrigger.RECOVERY_DUE))
                .build()
            WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
                UNIQUE_RECOVERY_WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                request
            )
            setTransientRecoveryScheduled(context, true)
        }

        fun cancelRecovery(context: Context) {
            WorkManager.getInstance(context.applicationContext)
                .cancelUniqueWork(UNIQUE_RECOVERY_WORK_NAME)
            setTransientRecoveryScheduled(context, false)
        }

        fun cancelWatchdog(context: Context) {
            WorkManager.getInstance(context.applicationContext).cancelUniqueWork(UNIQUE_WORK_NAME)
            setWatchdogScheduled(context, false)
        }

        fun cancel(context: Context) {
            cancelRecovery(context)
            cancelWatchdog(context)
        }

        fun isTransientRecoveryScheduled(context: Context): Boolean =
            statePreferences(context).getBoolean(KEY_TRANSIENT_RECOVERY_SCHEDULED, false)

        fun isWatchdogScheduled(context: Context): Boolean =
            statePreferences(context).getBoolean(KEY_WATCHDOG_SCHEDULED, false)

        private fun triggerData(trigger: AlwaysOnTrigger): Data =
            Data.Builder()
                .putString(KEY_TRIGGER, trigger.name)
                .build()

        private fun setTransientRecoveryScheduled(context: Context, scheduled: Boolean) {
            statePreferences(context)
                .edit()
                .putBoolean(KEY_TRANSIENT_RECOVERY_SCHEDULED, scheduled)
                .apply()
        }

        private fun setWatchdogScheduled(context: Context, scheduled: Boolean) {
            statePreferences(context)
                .edit()
                .putBoolean(KEY_WATCHDOG_SCHEDULED, scheduled)
                .apply()
        }

        private fun statePreferences(context: Context) =
            context.applicationContext.getSharedPreferences(
                STATE_PREFERENCES,
                Context.MODE_PRIVATE
            )
    }
}
