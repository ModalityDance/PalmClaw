package com.palmclaw.runtime

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.palmclaw.runtime.alwayson.AlwaysOnRuntimeAccess
import com.palmclaw.runtime.alwayson.AlwaysOnTrigger

class AlwaysOnBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action.orEmpty()
        if (
            action != Intent.ACTION_BOOT_COMPLETED &&
            action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            return
        }
        val trigger = when (action) {
            Intent.ACTION_BOOT_COMPLETED -> AlwaysOnTrigger.BOOT_COMPLETED
            Intent.ACTION_MY_PACKAGE_REPLACED -> AlwaysOnTrigger.PACKAGE_REPLACED
            else -> return
        }
        val pendingResult = goAsync()
        AlwaysOnRuntimeAccess.requestReconcile(trigger) {
            pendingResult.finish()
        }
    }
}

