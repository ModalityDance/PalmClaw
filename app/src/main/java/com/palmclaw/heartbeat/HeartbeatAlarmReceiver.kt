package com.palmclaw.heartbeat

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class HeartbeatAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != HeartbeatService.ACTION_ALARM_WAKE) return
        Log.d(TAG, "Alarm received, enqueue heartbeat tick")
        HeartbeatDispatchWorker.enqueueProcessDue(context.applicationContext)
    }

    companion object {
        private const val TAG = "HeartbeatAlarmReceiver"
    }
}

