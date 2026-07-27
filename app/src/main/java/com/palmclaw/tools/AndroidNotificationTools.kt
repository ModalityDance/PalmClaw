package com.palmclaw.tools

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings

fun createAndroidNotificationToolSet(context: Context): List<Tool> {
    val appContext = context.applicationContext
    return listOf(
        NotificationControlTool(
            gateway = AndroidNotificationRuntime.gateway(appContext),
            userInteraction = AndroidNotificationUserInteraction(appContext)
        )
    )
}

private object AndroidNotificationRuntime {
    @Volatile
    private var sharedGateway: NotificationGateway? = null

    fun gateway(context: Context): NotificationGateway =
        sharedGateway ?: synchronized(this) {
            sharedGateway ?: AndroidNotificationGateway(context).also { sharedGateway = it }
        }
}

private class AndroidNotificationUserInteraction(
    private val context: Context
) : NotificationUserInteraction {
    override suspend fun ensurePostPermission(
        action: String
    ): NotificationGatewayResult<Unit> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return NotificationGatewayResult.Success(Unit)
        }
        if (hasPermission(context, Manifest.permission.POST_NOTIFICATIONS)) {
            return NotificationGatewayResult.Success(Unit)
        }
        if (AndroidUserActionBridge.requestPermissions(
                listOf(Manifest.permission.POST_NOTIFICATIONS)
            ) == true &&
            hasPermission(context, Manifest.permission.POST_NOTIFICATIONS)
        ) {
            return NotificationGatewayResult.Success(Unit)
        }

        if (!openAppNotificationSettings()) {
            return NotificationGatewayResult.Failure(
                code = "settings_unavailable",
                message = "Notification permission is missing and settings could not be opened.",
                nextStep = "Open PalmClaw notification settings manually."
            )
        }
        val confirmed = AndroidUserActionBridge.requestUserConfirmation(
            title = "Notification Permission",
            message = "Allow PalmClaw notifications, then return and tap Continue.",
            confirmLabel = "Continue",
            cancelLabel = "Cancel"
        )
        if (confirmed != true) {
            return NotificationGatewayResult.Failure(
                code = if (confirmed == false) "user_cancelled" else "confirmation_unavailable",
                message = if (confirmed == false) {
                    "Notification permission setup was cancelled."
                } else {
                    "Notification permission confirmation is unavailable."
                },
                nextStep = "Enable PalmClaw notifications in system settings and retry."
            )
        }
        return if (hasPermission(context, Manifest.permission.POST_NOTIFICATIONS)) {
            NotificationGatewayResult.Success(Unit)
        } else {
            NotificationGatewayResult.Failure(
                code = "permission_required",
                message = "Notification permission is still required for $action.",
                nextStep = "Enable PalmClaw notifications in system settings and retry."
            )
        }
    }

    override suspend fun openSettings(): Boolean? = openChannelNotificationSettings()

    private fun openChannelNotificationSettings(): Boolean {
        val manager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            manager.getNotificationChannel(AndroidNotificationGateway.CHANNEL_ID) != null
        ) {
            val channelResult = launchIntent(
                context,
                Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).apply {
                    putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                    putExtra(
                        Settings.EXTRA_CHANNEL_ID,
                        AndroidNotificationGateway.CHANNEL_ID
                    )
                }
            )
            if (!channelResult.isError) return true
        }
        return openAppNotificationSettings()
    }

    private fun openAppNotificationSettings(): Boolean {
        val primary = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            }
        } else {
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:${context.packageName}")
            }
        }
        val result = launchIntent(context, primary)
        if (!result.isError) return true
        val fallback = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.parse("package:${context.packageName}")
        }
        return !launchIntent(context, fallback).isError
    }
}
