package com.palmclaw.channels

internal enum class DiscordGatewayCloseDisposition {
    RETRY_RESUME,
    RETRY_IDENTIFY,
    BLOCK_AUTHENTICATION,
    BLOCK_CONFIGURATION
}

internal fun discordGatewayCloseDisposition(code: Int): DiscordGatewayCloseDisposition = when (code) {
    4004 -> DiscordGatewayCloseDisposition.BLOCK_AUTHENTICATION
    4010, 4011, 4012, 4013, 4014 -> DiscordGatewayCloseDisposition.BLOCK_CONFIGURATION
    1000, 1001, 4003, 4005, 4007, 4009 -> DiscordGatewayCloseDisposition.RETRY_IDENTIFY
    else -> DiscordGatewayCloseDisposition.RETRY_RESUME
}

internal sealed interface DiscordGatewayHandshake {
    object Identify : DiscordGatewayHandshake

    data class Resume(
        val gatewayUrl: String,
        val sessionId: String,
        val sequence: Long
    ) : DiscordGatewayHandshake
}

/**
 * Owns Discord session recovery state independently from the websocket lifecycle.
 * A session is resumable only after READY supplied a session id and resume URL and
 * at least one sequence number has been observed.
 */
internal class DiscordGatewayRecovery(
    private val defaultGatewayUrl: String
) {
    private val lock = Any()
    private var sequence: Long? = null
    private var sessionId: String? = null
    private var resumeGatewayUrl: String? = null

    fun recordSequence(value: Long) = synchronized(lock) {
        sequence = value
    }

    fun recordReady(sessionId: String?, resumeGatewayUrl: String?) = synchronized(lock) {
        this.sessionId = sessionId?.trim()?.ifBlank { null }
        this.resumeGatewayUrl = resumeGatewayUrl?.trim()?.ifBlank { null }
        if (this.sessionId == null || this.resumeGatewayUrl == null) {
            invalidateLocked()
        }
    }

    fun nextGatewayUrl(): String = synchronized(lock) {
        if (canResumeLocked()) {
            normalizeGatewayUrl(checkNotNull(resumeGatewayUrl))
        } else {
            defaultGatewayUrl
        }
    }

    fun nextHandshake(): DiscordGatewayHandshake = synchronized(lock) {
        if (!canResumeLocked()) {
            DiscordGatewayHandshake.Identify
        } else {
            DiscordGatewayHandshake.Resume(
                gatewayUrl = normalizeGatewayUrl(checkNotNull(resumeGatewayUrl)),
                sessionId = checkNotNull(sessionId),
                sequence = checkNotNull(sequence)
            )
        }
    }

    fun onClose(code: Int): DiscordGatewayCloseDisposition = synchronized(lock) {
        discordGatewayCloseDisposition(code).also { disposition ->
            if (disposition != DiscordGatewayCloseDisposition.RETRY_RESUME) {
                invalidateLocked()
            }
        }
    }

    fun onInvalidSession(canResume: Boolean) = synchronized(lock) {
        if (!canResume) invalidateLocked()
    }

    fun sequence(): Long? = synchronized(lock) { sequence }

    fun clear() = synchronized(lock) {
        invalidateLocked()
    }

    private fun canResumeLocked(): Boolean =
        sequence != null && sessionId != null && resumeGatewayUrl != null

    private fun invalidateLocked() {
        sequence = null
        sessionId = null
        resumeGatewayUrl = null
    }

    private fun normalizeGatewayUrl(raw: String): String {
        val url = raw.trim().trimEnd('?', '&')
        val additions = buildList {
            if (!url.contains(Regex("(?:[?&])v="))) add("v=10")
            if (!url.contains(Regex("(?:[?&])encoding="))) add("encoding=json")
        }
        if (additions.isEmpty()) return url
        val separator = if ('?' in url) '&' else '?'
        return url + separator + additions.joinToString("&")
    }
}

internal class DiscordHeartbeatWatchdog {
    private val lock = Any()
    private var awaitingAcknowledgement = false

    fun beginHeartbeat(): Boolean = synchronized(lock) {
        if (awaitingAcknowledgement) {
            false
        } else {
            awaitingAcknowledgement = true
            true
        }
    }

    fun acknowledge() = synchronized(lock) {
        awaitingAcknowledgement = false
    }

    fun awaitingAck(): Boolean = synchronized(lock) { awaitingAcknowledgement }

    fun reset() = synchronized(lock) {
        awaitingAcknowledgement = false
    }
}
