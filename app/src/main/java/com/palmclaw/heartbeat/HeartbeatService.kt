package com.palmclaw.heartbeat

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.palmclaw.config.AppLimits

class HeartbeatService(
    context: Context
) {
    private val appContext = context.applicationContext
    private val alarmManager = appContext.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    private var enabled: Boolean = false
    private var intervalMs: Long = AppLimits.DEFAULT_HEARTBEAT_INTERVAL_SECONDS * 1_000L

    fun updateConfig(enabled: Boolean, intervalSeconds: Long) {
        this.enabled = enabled
        val safeSeconds = intervalSeconds.coerceIn(
            AppLimits.MIN_HEARTBEAT_INTERVAL_SECONDS,
            AppLimits.MAX_HEARTBEAT_INTERVAL_SECONDS
        )
        this.intervalMs = safeSeconds * 1_000L
    }

    fun start() {
        if (!enabled) {
            cancelNextAlarm()
            Log.d(TAG, "Heartbeat disabled")
            return
        }
        armNextAlarm(System.currentTimeMillis() + intervalMs)
        Log.d(TAG, "Heartbeat scheduled intervalMs=$intervalMs")
    }

    fun stop() {
        cancelNextAlarm()
        Log.d(TAG, "Heartbeat stopped")
    }

    fun close() {
        stop()
    }

    fun triggerNow() {
        if (!enabled) return
        HeartbeatDispatchWorker.enqueueProcessDue(appContext)
        Log.d(TAG, "Heartbeat trigger-now enqueued")
    }

    internal fun armNextAlarm(triggerAtMs: Long) {
        val pi = alarmPendingIntent()
        alarmManager.cancel(pi)
        try {
            when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.M -> {
                    alarmManager.setExactAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP,
                        triggerAtMs,
                        pi
                    )
                }

                Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT -> {
                    alarmManager.setExact(
                        AlarmManager.RTC_WAKEUP,
                        triggerAtMs,
                        pi
                    )
                }

                else -> {
                    alarmManager.set(
                        AlarmManager.RTC_WAKEUP,
                        triggerAtMs,
                        pi
                    )
                }
            }
        } catch (se: SecurityException) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarmManager.setAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    triggerAtMs,
                    pi
                )
                Log.w(TAG, "Exact alarm denied; fallback to inexact heartbeat alarm")
            } else {
                throw se
            }
        }
    }

    internal fun cancelNextAlarm() {
        alarmManager.cancel(alarmPendingIntent())
    }

    private fun alarmPendingIntent(): PendingIntent {
        val intent = Intent(appContext, HeartbeatAlarmReceiver::class.java).apply {
            action = ACTION_ALARM_WAKE
            setPackage(appContext.packageName)
        }
        return PendingIntent.getBroadcast(
            appContext,
            ALARM_REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    companion object {
        private const val TAG = "HeartbeatService"
        const val ACTION_ALARM_WAKE = "com.palmclaw.action.HEARTBEAT_ALARM_WAKE"
        private const val ALARM_REQUEST_CODE = 92001
    }
}

