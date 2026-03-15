package com.palmclaw.runtime

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.palmclaw.config.ConfigStore
import com.palmclaw.runtime.GatewayRuntime
import com.palmclaw.ui.MainActivity

class AlwaysOnGatewayService : Service() {
    private var runtime: GatewayRuntime? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var manualStopRequested: Boolean = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("Starting remote gateway..."))
        acquireWakeLock()
        acquireWifiLock()
        AlwaysOnModeController.updateServiceState(running = true, notificationActive = true)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action.orEmpty()
        if (ACTION_STOP == action) {
            manualStopRequested = true
            cancelRestart()
            stopSelf()
            return START_NOT_STICKY
        }
        manualStopRequested = false
        cancelRestart()

        val alwaysOnConfig = ConfigStore(applicationContext).getAlwaysOnConfig()
        if (!alwaysOnConfig.enabled) {
            stopSelf()
            return START_NOT_STICKY
        }

        if (runtime == null) {
            runtime = GatewayRuntime(application) { state ->
                AlwaysOnModeController.updateRuntimeState(
                    gatewayRunning = state.gatewayRunning,
                    activeAdapterCount = state.activeAdapterCount,
                    lastError = state.lastError
                )
                refreshNotification()
            }.also {
                AlwaysOnModeController.attachRuntime(it)
                it.start()
            }
        } else {
            runtime?.reloadGatewayFromStoredConfig()
        }
        runtime?.reloadMcpFromStoredConfig()
        refreshNotification()
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        scheduleRestartIfNeeded("task removed")
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        runtime?.shutdownRuntime()
        runtime = null
        releaseWakeLock()
        releaseWifiLock()
        AlwaysOnModeController.detachRuntime()
        AlwaysOnModeController.updateServiceState(running = false, notificationActive = false)
        scheduleRestartIfNeeded("service destroyed")
        super.onDestroy()
    }

    private fun refreshNotification() {
        val status = AlwaysOnModeController.status.value
        val detail = when {
            status.gatewayRunning -> "Gateway active with ${status.activeAdapterCount} adapter(s)"
            status.lastError.isNotBlank() -> status.lastError
            else -> "Waiting for active channel bindings"
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
        val stopIntent = PendingIntent.getService(
            this,
            REQUEST_STOP,
            createStopIntent(this),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
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

    private fun acquireWakeLock() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
        val lock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:always_on")
        lock.setReferenceCounted(false)
        runCatching { lock.acquire() }
        wakeLock = lock
    }

    private fun acquireWifiLock() {
        val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return
        val lock = wifiManager.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "$packageName:always_on_wifi")
        lock.setReferenceCounted(false)
        runCatching { lock.acquire() }
        wifiLock = lock
    }

    private fun releaseWakeLock() {
        val lock = wakeLock
        wakeLock = null
        if (lock != null && lock.isHeld) {
            runCatching { lock.release() }
        }
    }

    private fun releaseWifiLock() {
        val lock = wifiLock
        wifiLock = null
        if (lock != null && lock.isHeld) {
            runCatching { lock.release() }
        }
    }

    private fun scheduleRestartIfNeeded(reason: String) {
        if (manualStopRequested) return
        if (!ConfigStore(applicationContext).getAlwaysOnConfig().enabled) return
        val alarmManager = getSystemService(Context.ALARM_SERVICE) as? android.app.AlarmManager ?: return
        val restartIntent = PendingIntent.getService(
            this,
            REQUEST_RESTART,
            createStartIntent(this),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val triggerAtMs = System.currentTimeMillis() + RESTART_DELAY_MS
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarmManager.setExactAndAllowWhileIdle(
                    android.app.AlarmManager.RTC_WAKEUP,
                    triggerAtMs,
                    restartIntent
                )
            } else {
                alarmManager.setExact(android.app.AlarmManager.RTC_WAKEUP, triggerAtMs, restartIntent)
            }
            AlwaysOnModeController.updateServiceState(
                running = false,
                notificationActive = false,
                lastError = "Always-on restart scheduled: $reason"
            )
        }
    }

    private fun cancelRestart() {
        val alarmManager = getSystemService(Context.ALARM_SERVICE) as? android.app.AlarmManager ?: return
        val restartIntent = PendingIntent.getService(
            this,
            REQUEST_RESTART,
            createStartIntent(this),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        alarmManager.cancel(restartIntent)
    }

    companion object {
        private const val CHANNEL_ID = "always_on_mode"
        private const val NOTIFICATION_ID = 10041
        private const val REQUEST_OPEN = 10042
        private const val REQUEST_STOP = 10043
        private const val REQUEST_RESTART = 10044
        private const val RESTART_DELAY_MS = 2_000L
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
    }
}

