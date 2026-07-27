package com.palmclaw.tools

import java.util.Locale

internal object NotificationKeyCodec {
    private const val TAG_PREFIX = "palmclaw.agent."
    const val ANDROID_NOTIFICATION_ID = 1
    private val VALID_KEY = Regex("^[a-z0-9][a-z0-9._-]{0,63}$")

    fun normalize(raw: String?): String? {
        val normalized = raw?.trim()?.lowercase(Locale.US).orEmpty()
        return normalized.takeIf(VALID_KEY::matches)
    }

    fun toTag(key: String): String = TAG_PREFIX + key

    fun fromTag(tag: String?): String? {
        val raw = tag?.takeIf { it.startsWith(TAG_PREFIX) }
            ?.removePrefix(TAG_PREFIX)
            ?: return null
        return normalize(raw)?.takeIf { toTag(it) == tag }
    }

    fun fromAndroidIdentity(tag: String?, id: Int): String? =
        if (id == ANDROID_NOTIFICATION_ID) fromTag(tag) else null
}

internal enum class NotificationPublishMode {
    CREATE,
    UPDATE
}

internal data class AgentNotificationSpec(
    val key: String,
    val title: String,
    val text: String,
    val timeoutAfterMs: Long?
)

internal data class ActiveAgentNotification(
    val key: String,
    val title: String,
    val text: String,
    val postedAtMs: Long,
    val timeoutAfterMs: Long?,
    val channelId: String,
    val tapAction: String
)

internal data class AgentNotificationStatus(
    val permissionGranted: Boolean,
    val notificationsEnabled: Boolean,
    val channelExists: Boolean,
    val channelEnabled: Boolean,
    val activeCount: Int
)

internal sealed interface NotificationGatewayResult<out T> {
    data class Success<T>(val value: T) : NotificationGatewayResult<T>

    data class Failure(
        val code: String,
        val message: String,
        val nextStep: String? = null
    ) : NotificationGatewayResult<Nothing>
}

/**
 * Deep notification module interface shared by the Android adapter and an in-memory test adapter.
 *
 * The Android implementation owns notification identity, channel behavior, PendingIntents,
 * mutation serialization, namespace filtering, and post-mutation verification.
 */
internal interface NotificationGateway {
    fun status(): NotificationGatewayResult<AgentNotificationStatus>

    fun listActive(limit: Int): NotificationGatewayResult<List<ActiveAgentNotification>>

    fun publish(
        mode: NotificationPublishMode,
        spec: AgentNotificationSpec
    ): NotificationGatewayResult<ActiveAgentNotification>

    fun cancel(key: String): NotificationGatewayResult<Boolean>
}
