package com.palmclaw.runtime

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import com.palmclaw.runtime.alwayson.AlwaysOnPhase
import com.palmclaw.runtime.alwayson.AlwaysOnServiceLifecycle
import com.palmclaw.runtime.alwayson.AlwaysOnStatus
import com.palmclaw.runtime.alwayson.AlwaysOnRuntimeAccess
import com.palmclaw.runtime.alwayson.AlwaysOnTrigger
import com.palmclaw.runtime.alwayson.AndroidProcessingPowerLease
import com.palmclaw.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class AlwaysOnGatewayService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var processingPowerLease: AndroidProcessingPowerLease
    private var unregisterRuntimeListener: (() -> Unit)? = null
    private val shellLifecycle = AlwaysOnServiceLifecycle()
    private var statusJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureNotificationChannel()
        startSpecialUseForeground(buildNotification("Starting remote gateway..."))
        processingPowerLease = AndroidProcessingPowerLease(applicationContext)
        shellLifecycle.markRunning()
        unregisterRuntimeListener = GatewayRuntimeSupervisor.addStatusListener { runtimeStatus ->
            processingPowerLease.update(runtimeStatus.processingSessionIds.isNotEmpty())
            AlwaysOnRuntimeAccess.requestReconcile(AlwaysOnTrigger.GATEWAY_STATE_CHANGED)
        }
        statusJob = AlwaysOnRuntimeAccess.statusOrNull()?.let { statuses ->
            serviceScope.launch { statuses.collectLatest(::refreshNotification) }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action.orEmpty()
        if (ACTION_STOP == action) {
            AlwaysOnRuntimeAccess.requestSetEnabled(enabled = false) {
                stopSelfResult(startId)
            }
            return START_NOT_STICKY
        }
        shellLifecycle.markRunning()
        AlwaysOnRuntimeAccess.requestReconcile(AlwaysOnTrigger.SERVICE_STATE_CHANGED)
        return START_STICKY
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        shellLifecycle.markTimedOut()
        stopSelf(startId)
    }

    override fun onDestroy() {
        unregisterRuntimeListener?.invoke()
        unregisterRuntimeListener = null
        statusJob?.cancel()
        statusJob = null
        if (::processingPowerLease.isInitialized) {
            processingPowerLease.close()
        }
        val recoveryTrigger = shellLifecycle.markDestroyed()
        if (recoveryTrigger != null) {
            AlwaysOnRuntimeAccess.requestReconcile(recoveryTrigger)
        }
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun startSpecialUseForeground(notification: Notification) {
        val foregroundServiceType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification,
            foregroundServiceType
        )
    }

    private fun refreshNotification(status: AlwaysOnStatus) {
        val detail = when (status.phase) {
            AlwaysOnPhase.ONLINE -> "Online with ${status.channels.ready} channel(s)"
            AlwaysOnPhase.DEGRADED ->
                "Online on ${status.channels.ready} of ${status.channels.configured} channels"
            AlwaysOnPhase.STARTING -> "Starting remote channels..."
            AlwaysOnPhase.RECOVERING -> "Reconnecting remote channels..."
            AlwaysOnPhase.ACTION_REQUIRED -> "Open PalmClaw to restore remote access"
            AlwaysOnPhase.DISABLED -> "Stopping Always-on mode..."
        }
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification(detail))
    }

    private fun buildNotification(detail: String): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            REQUEST_OPEN,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            PendingIntent.getForegroundService(
                this,
                REQUEST_STOP,
                createStopIntent(this),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        } else {
            PendingIntent.getService(
                this,
                REQUEST_STOP,
                createStopIntent(this),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Always-on Mode")
            .setContentText(detail)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(openIntent)
            .addAction(android.R.drawable.ic_menu_view, "Open", openIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopIntent)
            .build()
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Always-on Mode",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Keeps remote channels connected for background replies."
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    companion object {
        private const val CHANNEL_ID = "always_on_mode"
        private const val NOTIFICATION_ID = 10041
        private const val REQUEST_OPEN = 10042
        private const val REQUEST_STOP = 10043
        private const val ACTION_START = "com.palmclaw.action.ALWAYS_ON_START"
        private const val ACTION_STOP = "com.palmclaw.action.ALWAYS_ON_STOP"

        fun createStartIntent(context: Context): Intent {
            return Intent(context, AlwaysOnGatewayService::class.java).apply {
                action = ACTION_START
            }
        }

        fun createStopIntent(context: Context): Intent {
            return Intent(context, AlwaysOnGatewayService::class.java).apply {
                action = ACTION_STOP
            }
        }

        internal fun canShowForegroundNotification(context: Context): Boolean {
            if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) {
                return false
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val manager = context.getSystemService(Context.NOTIFICATION_SERVICE)
                    as? NotificationManager ?: return false
                val channel = manager.getNotificationChannel(CHANNEL_ID)
                if (channel?.importance == NotificationManager.IMPORTANCE_NONE) {
                    return false
                }
            }
            return true
        }
    }
}
