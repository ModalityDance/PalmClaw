package com.palmclaw.cron

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class CronAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != CronService.ACTION_ALARM_WAKE) return
        Log.d(TAG, "Alarm received, enqueue cron processing")
        CronDispatchWorker.enqueueProcessDue(context.applicationContext)
    }

    companion object {
        private const val TAG = "CronAlarmReceiver"
    }
}
