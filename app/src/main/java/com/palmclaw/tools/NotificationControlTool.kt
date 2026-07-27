package com.palmclaw.tools

import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal interface NotificationUserInteraction {
    suspend fun ensurePostPermission(action: String): NotificationGatewayResult<Unit>

    suspend fun openSettings(): Boolean?
}

internal class NotificationControlTool(
    private val gateway: NotificationGateway,
    private val userInteraction: NotificationUserInteraction,
    private val keyGenerator: () -> String = { UUID.randomUUID().toString() }
) : Tool, TimedTool {
    override val name: String = "notification"
    override val description: String =
        "Manage PalmClaw agent notifications. " +
            "Use action=status|list_active|post|update|cancel|open_settings. " +
            "Use cron instead when a notification must be delivered in the future."
    override val timeoutMs: Long = 300_000L
    override val jsonSchema: JsonObject = notificationToolSchema()

    override suspend fun run(argumentsJson: String): ToolResult = withContext(Dispatchers.IO) {
        val args = try {
            NOTIFICATION_JSON.decodeFromString<Args>(argumentsJson)
        } catch (failure: Throwable) {
            if (failure is CancellationException) throw failure
            return@withContext error(
                action = "unknown",
                code = "invalid_arguments",
                message = failure.message ?: "Invalid notification arguments.",
                nextStep = "Use the fields declared in the notification tool schema."
            )
        }
        val action = args.action.trim().lowercase(Locale.US)
        try {
            when (action) {
                "status" -> status(action)
                "list_active" -> listActive(action, args)
                "post" -> publish(action, args, NotificationPublishMode.CREATE)
                "update" -> publish(action, args, NotificationPublishMode.UPDATE)
                "cancel" -> cancel(action, args)
                "open_settings" -> openSettings(action)
                else -> error(
                    action,
                    "unsupported_action",
                    "Unsupported notification action '${args.action}'.",
                    "Use one of the actions declared in the notification tool schema."
                )
            }
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: Throwable) {
            error(
                action,
                "notification_error",
                failure.message ?: failure.javaClass.simpleName,
                "Inspect notification status and retry."
            )
        }
    }

    private fun status(action: String): ToolResult =
        when (val result = gateway.status()) {
            is NotificationGatewayResult.Failure -> gatewayError(action, result)
            is NotificationGatewayResult.Success -> ok(action, "Notification status loaded.") {
                put("permission_granted", result.value.permissionGranted)
                put("notifications_enabled", result.value.notificationsEnabled)
                put("channel_exists", result.value.channelExists)
                put("channel_enabled", result.value.channelEnabled)
                put("active_count", result.value.activeCount)
            }
        }

    private fun listActive(action: String, args: Args): ToolResult {
        val limit = args.maxResults ?: DEFAULT_LIST_LIMIT
        if (limit !in 1..MAX_LIST_LIMIT) {
            return error(
                action,
                "invalid_arguments",
                "max_results must be between 1 and $MAX_LIST_LIMIT.",
                "Choose a bounded result count."
            )
        }
        return when (val result = gateway.listActive(limit)) {
            is NotificationGatewayResult.Failure -> gatewayError(action, result)
            is NotificationGatewayResult.Success -> ok(action, "Active notifications loaded.") {
                put("count", result.value.size)
                put(
                    "notifications",
                    buildJsonArray { result.value.forEach { add(it.toJson()) } }
                )
            }
        }
    }

    private suspend fun publish(
        action: String,
        args: Args,
        mode: NotificationPublishMode
    ): ToolResult {
        val key = when (mode) {
            NotificationPublishMode.CREATE -> {
                if (args.notificationKey == null) {
                    NotificationKeyCodec.normalize(keyGenerator())
                        ?: return error(
                            action,
                            "notification_error",
                            "PalmClaw could not generate a valid notification key.",
                            "Retry the notification."
                        )
                } else {
                    parseKey(args.notificationKey) ?: return invalidKey(action)
                }
            }
            NotificationPublishMode.UPDATE ->
                parseKey(args.notificationKey) ?: return invalidKey(action)
        }
        val title = args.title?.trim().orEmpty()
        if (title.isBlank() || title.length > MAX_TITLE_CHARS) {
            return error(
                action,
                "invalid_arguments",
                "title must contain 1 to $MAX_TITLE_CHARS characters.",
                "Provide a concise notification title."
            )
        }
        val text = args.text?.trim().orEmpty()
        if (text.isBlank() || text.length > MAX_TEXT_CHARS) {
            return error(
                action,
                "invalid_arguments",
                "text must contain 1 to $MAX_TEXT_CHARS characters.",
                "Provide non-empty notification text."
            )
        }
        val timeoutAfterMs = args.timeoutSec?.let { seconds ->
            if (seconds !in MIN_TIMEOUT_SECONDS..MAX_TIMEOUT_SECONDS) {
                return error(
                    action,
                    "invalid_arguments",
                    "timeout_sec must be between $MIN_TIMEOUT_SECONDS and $MAX_TIMEOUT_SECONDS.",
                    "Omit timeout_sec to keep the notification until dismissal."
                )
            }
            seconds * 1_000L
        }
        when (val permission = userInteraction.ensurePostPermission(action)) {
            is NotificationGatewayResult.Failure -> return gatewayError(action, permission)
            is NotificationGatewayResult.Success -> Unit
        }
        return when (
            val result = gateway.publish(
                mode,
                AgentNotificationSpec(
                    key = key,
                    title = title,
                    text = text,
                    timeoutAfterMs = timeoutAfterMs
                )
            )
        ) {
            is NotificationGatewayResult.Failure -> gatewayError(action, result)
            is NotificationGatewayResult.Success -> ok(
                action,
                if (mode == NotificationPublishMode.CREATE) {
                    "Notification posted."
                } else {
                    "Notification updated."
                }
            ) {
                put("notification", result.value.toJson())
            }
        }
    }

    private fun cancel(action: String, args: Args): ToolResult {
        val key = parseKey(args.notificationKey) ?: return invalidKey(action)
        return when (val result = gateway.cancel(key)) {
            is NotificationGatewayResult.Failure -> gatewayError(action, result)
            is NotificationGatewayResult.Success -> ok(action, "Notification cancelled.") {
                put("notification_key", key)
                put("cancelled", result.value)
            }
        }
    }

    private suspend fun openSettings(action: String): ToolResult =
        if (userInteraction.openSettings() == true) {
            ok(action, "Notification settings opened.") { put("opened", true) }
        } else {
            error(
                action,
                "settings_unavailable",
                "Notification settings could not be opened.",
                "Open PalmClaw notification settings manually."
            )
        }

    private fun parseKey(raw: String?): String? =
        raw?.let(NotificationKeyCodec::normalize)

    private fun invalidKey(action: String): ToolResult =
        error(
            action,
            "invalid_notification_key",
            "$action requires a valid notification_key.",
            "Use 1 to 64 letters, numbers, dots, underscores, or hyphens, starting with a letter or number."
        )

    private fun gatewayError(
        action: String,
        failure: NotificationGatewayResult.Failure
    ): ToolResult =
        error(action, failure.code, failure.message, failure.nextStep)

    private fun ok(
        action: String,
        message: String,
        details: JsonObjectBuilder.() -> Unit = {}
    ): ToolResult {
        val body = buildJsonObject {
            put("status", "ok")
            put("tool", name)
            put("action", action)
            put("message", message)
            details()
        }
        return ToolResult(
            toolCallId = "",
            content = body.toString(),
            isError = false,
            metadata = body
        )
    }

    private fun error(
        action: String,
        code: String,
        message: String,
        nextStep: String? = null
    ): ToolResult {
        val body = buildJsonObject {
            put("status", "error")
            put("tool", name)
            put("action", action)
            put("code", code)
            put("message", message)
            nextStep?.let { put("next_step", it) }
        }
        return ToolResult(
            toolCallId = "",
            content = body.toString(),
            isError = true,
            metadata = JsonObject(body + mapOf("error" to JsonPrimitive(code)))
        )
    }

    private fun ActiveAgentNotification.toJson(): JsonObject = buildJsonObject {
        put("notification_key", key)
        put("title", title)
        put("text", text)
        put("posted_at_ms", postedAtMs)
        timeoutAfterMs?.let { put("timeout_sec", it / 1_000L) }
        put("channel_id", channelId)
        put("tap_action", tapAction)
        put("active", true)
    }

    @Serializable
    private data class Args(
        val action: String,
        @SerialName("notification_key")
        val notificationKey: String? = null,
        val title: String? = null,
        val text: String? = null,
        @SerialName("timeout_sec")
        val timeoutSec: Long? = null,
        @SerialName("max_results")
        val maxResults: Int? = null
    )

    private companion object {
        const val DEFAULT_LIST_LIMIT = 20
        const val MAX_LIST_LIMIT = 50
        const val MAX_TITLE_CHARS = 120
        const val MAX_TEXT_CHARS = 4_000
        const val MIN_TIMEOUT_SECONDS = 5L
        const val MAX_TIMEOUT_SECONDS = 604_800L
    }
}

internal fun notificationToolSchema(): JsonObject = buildJsonObject {
    put("type", "object")
    put("additionalProperties", false)
    put("required", buildJsonArray { add("action") })
    put(
        "properties",
        NOTIFICATION_JSON.parseToJsonElement(
            """
            {
              "action":{"type":"string","enum":["status","list_active","post","update","cancel","open_settings"]},
              "notification_key":{"type":"string","minLength":1,"maxLength":64,"pattern":"^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$"},
              "title":{"type":"string","minLength":1,"maxLength":120},
              "text":{"type":"string","minLength":1,"maxLength":4000},
              "timeout_sec":{"type":"integer","minimum":5,"maximum":604800},
              "max_results":{"type":"integer","minimum":1,"maximum":50}
            }
            """.trimIndent()
        )
    )
}

private val NOTIFICATION_JSON = Json {
    ignoreUnknownKeys = false
    explicitNulls = false
}
