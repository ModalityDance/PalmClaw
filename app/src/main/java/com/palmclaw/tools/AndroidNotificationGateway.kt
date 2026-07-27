package com.palmclaw.tools

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.palmclaw.ui.MainActivity

internal class AndroidNotificationGateway(
    context: Context
) : NotificationGateway {
    private val appContext = context.applicationContext
    private val manager =
        appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private val mutationLock = Any()

    override fun status(): NotificationGatewayResult<AgentNotificationStatus> =
        platformCall("status") {
            val channel = notificationChannel()
            val activeCount = readActive().mapNotNull(::toDomain).size
            AgentNotificationStatus(
                permissionGranted = hasPostPermission(),
                notificationsEnabled = NotificationManagerCompat.from(appContext)
                    .areNotificationsEnabled(),
                channelExists = Build.VERSION.SDK_INT < Build.VERSION_CODES.O || channel != null,
                channelEnabled = Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
                    channel?.importance?.let { it != NotificationManager.IMPORTANCE_NONE } == true,
                activeCount = activeCount
            )
        }

    override fun listActive(
        limit: Int
    ): NotificationGatewayResult<List<ActiveAgentNotification>> =
        platformCall("list_active") {
            readActive()
                .mapNotNull(::toDomain)
                .sortedByDescending(ActiveAgentNotification::postedAtMs)
                .take(limit.coerceIn(1, MAX_LIST_RESULTS))
        }

    override fun publish(
        mode: NotificationPublishMode,
        spec: AgentNotificationSpec
    ): NotificationGatewayResult<ActiveAgentNotification> = synchronized(mutationLock) {
        if (!hasPostPermission()) {
            return@synchronized NotificationGatewayResult.Failure(
                code = "permission_required",
                message = "Notification permission is required.",
                nextStep = "Grant notification permission and retry."
            )
        }
        if (!NotificationManagerCompat.from(appContext).areNotificationsEnabled()) {
            return@synchronized NotificationGatewayResult.Failure(
                code = "notifications_disabled",
                message = "PalmClaw notifications are disabled.",
                nextStep = "Open notification settings and enable notifications."
            )
        }
        ensureChannel()
        if (!isChannelEnabled()) {
            return@synchronized NotificationGatewayResult.Failure(
                code = "channel_disabled",
                message = "The PalmClaw notification channel is disabled.",
                nextStep = "Open notification settings and enable the PalmClaw channel."
            )
        }

        val existing = findActive(spec.key)
        if (mode == NotificationPublishMode.CREATE && existing != null) {
            return@synchronized NotificationGatewayResult.Failure(
                code = "notification_exists",
                message = "An active notification already uses key '${spec.key}'.",
                nextStep = "Use update with the same key or choose a new notification_key."
            )
        }
        if (mode == NotificationPublishMode.UPDATE && existing == null) {
            return@synchronized NotificationGatewayResult.Failure(
                code = "notification_not_found",
                message = "No active notification uses key '${spec.key}'.",
                nextStep = "Post a new notification if the user still wants one."
            )
        }

        val notification = buildNotification(spec)
        return@synchronized try {
            manager.notify(
                NotificationKeyCodec.toTag(spec.key),
                NotificationKeyCodec.ANDROID_NOTIFICATION_ID,
                notification
            )
            findActive(spec.key)?.let { NotificationGatewayResult.Success(it) }
                ?: NotificationGatewayResult.Failure(
                    code = "verification_failed",
                    message = "Android did not report the notification as active after posting.",
                    nextStep = "Inspect notification settings and retry."
                )
        } catch (_: SecurityException) {
            NotificationGatewayResult.Failure(
                code = "permission_required",
                message = "Notification permission is required.",
                nextStep = "Grant notification permission and retry."
            )
        } catch (failure: Throwable) {
            platformFailure("publish", failure)
        }
    }

    override fun cancel(key: String): NotificationGatewayResult<Boolean> =
        synchronized(mutationLock) {
            if (findActive(key) == null) {
                return@synchronized NotificationGatewayResult.Failure(
                    code = "notification_not_found",
                    message = "No active notification uses key '$key'.",
                    nextStep = "List active notifications and choose a returned notification_key."
                )
            }
            return@synchronized try {
                manager.cancel(
                    NotificationKeyCodec.toTag(key),
                    NotificationKeyCodec.ANDROID_NOTIFICATION_ID
                )
                if (findActive(key) == null) {
                    NotificationGatewayResult.Success(true)
                } else {
                    NotificationGatewayResult.Failure(
                        code = "verification_failed",
                        message = "The notification remained active after cancellation.",
                        nextStep = "Open notification settings and dismiss it manually."
                    )
                }
            } catch (failure: Throwable) {
                platformFailure("cancel", failure)
            }
        }

    private fun buildNotification(spec: AgentNotificationSpec): Notification {
        val openIntent = Intent(appContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val contentIntent = PendingIntent.getActivity(
            appContext,
            spec.key.hashCode(),
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(spec.title)
            .setContentText(spec.text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(spec.text))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(contentIntent)
            .apply {
                spec.timeoutAfterMs?.let { setTimeoutAfter(it) }
            }
            .build()
    }

    private fun findActive(key: String): ActiveAgentNotification? =
        readActive()
            .firstOrNull {
                NotificationKeyCodec.fromAndroidIdentity(it.tag, it.id) == key
            }
            ?.let(::toDomain)

    private fun readActive(): List<android.service.notification.StatusBarNotification> =
        manager.activeNotifications.orEmpty().toList()

    private fun toDomain(
        value: android.service.notification.StatusBarNotification
    ): ActiveAgentNotification? {
        val key = NotificationKeyCodec.fromAndroidIdentity(value.tag, value.id) ?: return null
        val notification = value.notification
        val title = notification.extras
            .getCharSequence(Notification.EXTRA_TITLE)
            ?.toString()
            .orEmpty()
        val text = (
            notification.extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
                ?: notification.extras.getCharSequence(Notification.EXTRA_TEXT)
            )
            ?.toString()
            .orEmpty()
        val timeoutAfterMs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            notification.timeoutAfter.takeIf { it > 0L }
        } else {
            null
        }
        val channelId = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            notification.channelId ?: CHANNEL_ID
        } else {
            CHANNEL_ID
        }
        return ActiveAgentNotification(
            key = key,
            title = title,
            text = text,
            postedAtMs = value.postTime,
            timeoutAfterMs = timeoutAfterMs,
            channelId = channelId,
            tapAction = "open_app"
        )
    }

    private fun hasPostPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            hasPermission(appContext, Manifest.permission.POST_NOTIFICATIONS)

    private fun notificationChannel(): NotificationChannel? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.getNotificationChannel(CHANNEL_ID)
        } else {
            null
        }

    private fun isChannelEnabled(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
            notificationChannel()
                ?.importance
                ?.let { it != NotificationManager.IMPORTANCE_NONE } == true

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        if (notificationChannel() != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Notifications created by the PalmClaw agent."
            }
        )
    }

    private fun <T> platformCall(
        action: String,
        block: () -> T
    ): NotificationGatewayResult<T> =
        try {
            NotificationGatewayResult.Success(block())
        } catch (failure: Throwable) {
            platformFailure(action, failure)
        }

    private fun platformFailure(
        action: String,
        failure: Throwable
    ): NotificationGatewayResult.Failure =
        NotificationGatewayResult.Failure(
            code = "notification_error",
            message = failure.message ?: "Android notification operation failed during $action.",
            nextStep = "Inspect notification status and retry."
        )

    internal companion object {
        const val CHANNEL_ID = "palmclaw_default"
        const val CHANNEL_NAME = "PalmClaw"
        const val MAX_LIST_RESULTS = 50
    }
}
