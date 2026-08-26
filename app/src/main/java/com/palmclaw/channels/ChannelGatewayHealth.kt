package com.palmclaw.channels

import java.util.Locale

enum class ChannelBindingHealthState {
    STARTING,
    READY,
    RECONNECTING,
    BLOCKED,
    STOPPED
}

enum class ChannelOperation {
    CONNECTION,
    AUTHENTICATION,
    POLL,
    INBOUND,
    OUTBOUND,
    HEARTBEAT
}

data class ChannelSuccessfulOperation(
    val operation: ChannelOperation,
    val atEpochMillis: Long
) {
    init {
        require(atEpochMillis >= 0L) { "atEpochMillis must not be negative" }
    }
}

data class ChannelOperationWarning(
    val operation: ChannelOperation,
    val error: NormalizedChannelError,
    val atEpochMillis: Long
) {
    init {
        require(atEpochMillis >= 0L) { "atEpochMillis must not be negative" }
    }
}

enum class ChannelRuntimeErrorCode(
    val summary: String,
    val retryable: Boolean
) {
    AUTHENTICATION_FAILED("Authentication required", false),
    CONFIGURATION_INVALID("Configuration required", false),
    RATE_LIMITED("Rate limited", true),
    NETWORK_UNAVAILABLE("Network unavailable", true),
    CONNECTION_CLOSED("Connection interrupted", true),
    PROTOCOL_ERROR("Protocol error", true),
    UNKNOWN("Temporary channel error", true)
}

data class NormalizedChannelError(
    val code: ChannelRuntimeErrorCode
) {
    val summary: String
        get() = code.summary

    val retryable: Boolean
        get() = code.retryable
}

internal object ChannelRuntimeErrorNormalizer {
    fun normalize(message: String): NormalizedChannelError {
        val value = message.trim().lowercase(Locale.US)
        val code = when {
            value.containsAny(
                "missing token",
                "missing credential",
                "missing app",
                "missing bot",
                "account is incomplete",
                "not configured",
                "consent is not granted",
                "consent required"
            ) -> ChannelRuntimeErrorCode.CONFIGURATION_INVALID

            value.containsAny(
                "http 401",
                "http 403",
                "unauthorized",
                "forbidden",
                "invalid token",
                "invalid app token",
                "invalid credentials",
                "authentication failed",
                "auth failed",
                "authenticationfailedexception",
                "invalid_auth",
                "not_authed",
                "token_revoked",
                "account_inactive",
                "login failed",
                "bad credentials",
                "authentication required",
                "username and password not accepted",
                "invalid password",
                "getupdates http 404"
            ) -> ChannelRuntimeErrorCode.AUTHENTICATION_FAILED

            value.containsAny("rate limit", "too many requests", "http 429") ->
                ChannelRuntimeErrorCode.RATE_LIMITED

            value.containsAny(
                "network",
                "unknown host",
                "host unreachable",
                "connection reset",
                "connection refused",
                "socket failure",
                "timed out",
                "timeout"
            ) -> ChannelRuntimeErrorCode.NETWORK_UNAVAILABLE

            value.containsAny(
                "socket closed",
                "socket closing",
                "gateway closed",
                "gateway closing",
                "socket disconnect",
                "connection closed"
            ) -> ChannelRuntimeErrorCode.CONNECTION_CLOSED

            value.containsAny(
                "invalid json",
                "json invalid",
                "parse failed",
                "protocol"
            ) -> ChannelRuntimeErrorCode.PROTOCOL_ERROR

            else -> ChannelRuntimeErrorCode.UNKNOWN
        }
        return NormalizedChannelError(code)
    }

    private fun String.containsAny(vararg candidates: String): Boolean =
        candidates.any { candidate -> contains(candidate) }
}

internal fun deriveChannelBindingHealthState(
    running: Boolean,
    connected: Boolean,
    ready: Boolean,
    lastError: String
): ChannelBindingHealthState = when {
    ready -> ChannelBindingHealthState.READY
    lastError.isNotBlank() -> ChannelBindingHealthState.BLOCKED
    running || connected -> ChannelBindingHealthState.STARTING
    else -> ChannelBindingHealthState.STOPPED
}
