package com.palmclaw.cron

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class CronBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action.orEmpty()
        if (action !in RESYNC_ACTIONS) return
        Log.d(TAG, "System action '$action' received, enqueue cron resync")
        CronDispatchWorker.enqueueResync(context.applicationContext)
    }

    companion object {
        private const val TAG = "CronBootReceiver"
        private const val ACTION_TIME_SET = "android.intent.action.TIME_SET"
        private const val ACTION_TIMEZONE_CHANGED = "android.intent.action.TIMEZONE_CHANGED"
        private val RESYNC_ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            ACTION_TIME_SET,
            ACTION_TIMEZONE_CHANGED
        )
    }
}
