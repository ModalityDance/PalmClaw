package com.palmclaw.tools

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Owns the submit/observe distinction for both the Android adapter and fault-injection tests. */
internal class NotificationMutationExecutor(
    private val readActive: () -> List<ActiveAgentNotification>,
    private val post: (AgentNotificationSpec) -> Unit,
    private val cancel: (String) -> Unit
) {
    fun publish(mode: NotificationPublishMode, spec: AgentNotificationSpec): NotificationGatewayResult<ActiveAgentNotification> {
        val existing = try {
            readActive().firstOrNull { it.key == spec.key }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return preflightUnavailable()
        }
        if (mode == NotificationPublishMode.CREATE && existing != null) {
            return NotificationGatewayResult.Failure("notification_exists", "This notification key is already active.", "Use update with the same notification_key.")
        }
        if (mode == NotificationPublishMode.UPDATE && existing == null) {
            return NotificationGatewayResult.Failure("notification_not_found", "This notification key is not active.", "Use list_active to inspect current notifications.")
        }
        var submitted = false
        return try {
            post(spec)
            submitted = true
            val actual = readActive().firstOrNull { it.key == spec.key }
            when {
                actual == null -> uncertain(spec.key, true, VerificationState.UNAVAILABLE)
                actual.title != spec.title || actual.text != spec.text || actual.timeoutAfterMs != spec.timeoutAfterMs ->
                    uncertain(spec.key, true, VerificationState.MISMATCH)
                else -> NotificationGatewayResult.Success(actual)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            uncertain(spec.key, submitted, VerificationState.UNAVAILABLE)
        }
    }

    fun cancel(key: String): NotificationGatewayResult<Boolean> {
        val absent = try {
            readActive().none { it.key == key }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return preflightUnavailable()
        }
        if (absent) {
            return NotificationGatewayResult.Failure("notification_not_found", "This notification key is not active.", "No cancellation is needed for this key.")
        }
        var submitted = false
        return try {
            cancel.invoke(key)
            submitted = true
            if (readActive().none { it.key == key }) NotificationGatewayResult.Success(true)
            else uncertain(key, true, VerificationState.MISMATCH)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            uncertain(key, submitted, VerificationState.UNAVAILABLE)
        }
    }

    private fun uncertain(key: String, submitted: Boolean, verification: VerificationState) =
        NotificationGatewayResult.Failure(
            code = "notification_unverified",
            message = if (submitted) "Android accepted the notification request; its resulting state is not verified."
                else "The notification request returned no reliable completion receipt.",
            feedback = MutationFeedback(
                if (submitted) ExecutionState.SUBMITTED else ExecutionState.UNKNOWN,
                verification,
                buildJsonObject { put("notification_key", key) },
                "Use list_active to inspect this notification_key. Do not post a replacement with a new key."
            )
        )

    private fun preflightUnavailable() = NotificationGatewayResult.Failure(
        "notification_query_unavailable", "Active notifications could not be queried; no publish or cancel request was sent.",
        "Use list_active to inspect notifications before changing them."
    )
}
