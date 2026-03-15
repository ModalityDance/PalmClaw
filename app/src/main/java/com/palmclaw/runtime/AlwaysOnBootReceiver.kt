package com.palmclaw.runtime

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.palmclaw.config.ConfigStore

class AlwaysOnBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action.orEmpty()
        if (
            action != Intent.ACTION_BOOT_COMPLETED &&
            action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            return
        }
        if (!ConfigStore(context.applicationContext).getAlwaysOnConfig().enabled) {
            return
        }
        AlwaysOnModeController.startService(context.applicationContext)
    }
}

