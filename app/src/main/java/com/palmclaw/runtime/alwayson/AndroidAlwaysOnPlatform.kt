package com.palmclaw.runtime.alwayson

import android.app.ActivityManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.palmclaw.channels.ProcessChannelNetworkAvailability
import com.palmclaw.runtime.AlwaysOnForegroundServiceStartPolicy
import com.palmclaw.runtime.AlwaysOnGatewayService
import com.palmclaw.runtime.AlwaysOnHealthCheckWorker
import com.palmclaw.ui.MainActivity
import kotlinx.coroutines.CancellationException

internal class AndroidAlwaysOnPlatform(
    context: Context
) : AlwaysOnPlatform {
    private val appContext = context.applicationContext
    private val connectivityManager =
        appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
    @Volatile
    private var lastNetworkAvailable: Boolean? = null

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            publishNetworkState()
        }

        override fun onLost(network: Network) {
            publishNetworkState()
        }

        override fun onCapabilitiesChanged(
            network: Network,
            networkCapabilities: NetworkCapabilities
        ) {
            publishNetworkState()
        }
    }

    init {
        publishNetworkState(requestReconcile = false)
        runCatching {
            connectivityManager?.registerDefaultNetworkCallback(networkCallback)
        }
    }

    override suspend fun snapshot(): AlwaysOnPlatformSnapshot {
        val shellSnapshot = AlwaysOnShellRegistry.state.value
        return AlwaysOnPlatformSnapshot(
            shell = if (shellSnapshot.running) {
                AlwaysOnShellState.RUNNING
            } else {
                AlwaysOnShellState.STOPPED
            },
            notificationVisible = shellSnapshot.running &&
                AlwaysOnGatewayService.canShowForegroundNotification(appContext),
            network = currentNetworkState(),
            startConstraint = currentStartConstraint(),
            transientRecoveryScheduled =
                AlwaysOnHealthCheckWorker.isTransientRecoveryScheduled(appContext),
            watchdogScheduled = AlwaysOnHealthCheckWorker.isWatchdogScheduled(appContext)
        )
    }

    override suspend fun startShell(): ShellStartResult {
        if (AlwaysOnShellRegistry.state.value.running) {
            return ShellStartResult.AlreadyRunning
        }
        return when (val constraint = currentStartConstraint()) {
            AlwaysOnStartConstraint.SYSTEM_RESTRICTED ->
                ShellStartResult.Rejected(
                    reason = AlwaysOnActionRequiredReason.SYSTEM_RESTRICTED
                )
            AlwaysOnStartConstraint.ALLOWED -> {
                try {
                    ContextCompat.startForegroundService(
                        appContext,
                        AlwaysOnGatewayService.createStartIntent(appContext)
                    )
                    ShellStartResult.Started
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    if (AlwaysOnForegroundServiceStartPolicy.isForegroundServiceStartDenied(error)) {
                        ShellStartResult.Rejected(
                            reason = AlwaysOnActionRequiredReason.BACKGROUND_START_RESTRICTED
                        )
                    } else {
                        ShellStartResult.Failed("Foreground service could not start")
                    }
                }
            }
        }
    }

    override suspend fun stopShell() {
        AlwaysOnShellRegistry.expectStop()
        appContext.stopService(AlwaysOnGatewayService.createStartIntent(appContext))
    }

    override suspend fun scheduleRecovery(delayMillis: Long) {
        AlwaysOnHealthCheckWorker.scheduleRecovery(appContext, delayMillis)
    }

    override suspend fun cancelRecovery() {
        AlwaysOnHealthCheckWorker.cancelRecovery(appContext)
    }

    override suspend fun ensureWatchdog() {
        AlwaysOnHealthCheckWorker.ensureScheduled(appContext)
    }

    override suspend fun cancelWatchdog() {
        AlwaysOnHealthCheckWorker.cancelWatchdog(appContext)
    }

    override suspend fun updateActionRequired(action: AlwaysOnActionRequired?) {
        try {
            updateActionRequiredNotification(action)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Android notification delivery is best effort.
        }
    }

    private fun updateActionRequiredNotification(action: AlwaysOnActionRequired?) {
        val manager = appContext.getSystemService(Context.NOTIFICATION_SERVICE)
            as? NotificationManager ?: return
        if (action == null || !canPostNotifications()) {
            manager.cancel(ACTION_REQUIRED_NOTIFICATION_ID)
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(
                    ACTION_REQUIRED_CHANNEL_ID,
                    "Always-on recovery",
                    NotificationManager.IMPORTANCE_DEFAULT
                ).apply {
                    description = "Prompts when Always-on mode needs user action."
                }
            )
        }
        val openIntent = PendingIntent.getActivity(
            appContext,
            ACTION_REQUIRED_REQUEST_CODE,
            Intent(appContext, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val detail = when (action.reason) {
            AlwaysOnActionRequiredReason.NO_CHANNEL_CONFIGURED ->
                "Configure a remote channel to start Always-on mode."
            AlwaysOnActionRequiredReason.SYSTEM_RESTRICTED ->
                "Allow PalmClaw to run in the background."
            AlwaysOnActionRequiredReason.BACKGROUND_START_RESTRICTED ->
                "Open PalmClaw to restore remote access."
            AlwaysOnActionRequiredReason.ALL_CHANNELS_BLOCKED ->
                "Review the credentials for your remote channels."
            AlwaysOnActionRequiredReason.GATEWAY_BLOCKED ->
                "Open PalmClaw to restore the remote gateway."
        }
        manager.notify(
            ACTION_REQUIRED_NOTIFICATION_ID,
            NotificationCompat.Builder(appContext, ACTION_REQUIRED_CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setContentTitle("Always-on mode needs attention")
                .setContentText(detail)
                .setContentIntent(openIntent)
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .build()
        )
    }

    private fun currentNetworkState(): AlwaysOnNetworkState {
        val manager = connectivityManager ?: return AlwaysOnNetworkState.UNKNOWN
        val activeNetwork = manager.activeNetwork
            ?: return AlwaysOnNetworkState.OFFLINE
        val capabilities = manager.getNetworkCapabilities(activeNetwork)
            ?: return AlwaysOnNetworkState.OFFLINE
        return if (
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        ) {
            AlwaysOnNetworkState.ONLINE
        } else {
            AlwaysOnNetworkState.OFFLINE
        }
    }

    private fun publishNetworkState(requestReconcile: Boolean = true) {
        val available = currentNetworkState() == AlwaysOnNetworkState.ONLINE
        ProcessChannelNetworkAvailability.update(available)
        if (lastNetworkAvailable == available) {
            return
        }
        lastNetworkAvailable = available
        if (requestReconcile) {
            AlwaysOnRuntimeAccess.requestReconcile(AlwaysOnTrigger.NETWORK_CHANGED)
        }
    }

    private fun canPostNotifications(): Boolean {
        return NotificationManagerCompat.from(appContext).areNotificationsEnabled()
    }

    private fun currentStartConstraint(): AlwaysOnStartConstraint {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val activityManager =
                appContext.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            if (activityManager?.isBackgroundRestricted == true) {
                return AlwaysOnStartConstraint.SYSTEM_RESTRICTED
            }
        }
        return AlwaysOnStartConstraint.ALLOWED
    }

    private companion object {
        const val ACTION_REQUIRED_CHANNEL_ID = "always_on_recovery"
        const val ACTION_REQUIRED_NOTIFICATION_ID = 10044
        const val ACTION_REQUIRED_REQUEST_CODE = 10045
    }
}
