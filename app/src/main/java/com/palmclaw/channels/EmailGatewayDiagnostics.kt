package com.palmclaw.channels

data class EmailSenderCandidate(
    val email: String,
    val subject: String,
    val note: String = ""
)

data class EmailGatewaySnapshot(
    val running: Boolean = false,
    val connected: Boolean = false,
    val ready: Boolean = false,
    val inboundSeen: Int = 0,
    val inboundForwarded: Int = 0,
    val outboundSent: Int = 0,
    val lastSenderEmail: String = "",
    val lastSubject: String = "",
    val lastError: String = "",
    val recentSenders: List<EmailSenderCandidate> = emptyList()
)

object EmailGatewayDiagnostics {
    private val lock = Any()
    private var snapshot = EmailGatewaySnapshot()

    fun reset() = synchronized(lock) {
        snapshot = EmailGatewaySnapshot()
    }

    fun markRunning(value: Boolean) = synchronized(lock) {
        snapshot = snapshot.copy(running = value)
    }

    fun markConnected(value: Boolean) = synchronized(lock) {
        snapshot = snapshot.copy(connected = value)
    }

    fun markReady() = synchronized(lock) {
        snapshot = snapshot.copy(connected = true, ready = true, lastError = "")
    }

    fun markInboundSeen(senderEmail: String, subject: String) = synchronized(lock) {
        snapshot = snapshot.copy(
            inboundSeen = snapshot.inboundSeen + 1,
            lastSenderEmail = senderEmail,
            lastSubject = subject
        )
    }

    fun markInboundForwarded() = synchronized(lock) {
        snapshot = snapshot.copy(inboundForwarded = snapshot.inboundForwarded + 1)
    }

    fun markOutboundSent() = synchronized(lock) {
        snapshot = snapshot.copy(outboundSent = snapshot.outboundSent + 1)
    }

    fun markError(message: String) = synchronized(lock) {
        snapshot = snapshot.copy(lastError = message)
    }

    fun recordSender(candidate: EmailSenderCandidate) = synchronized(lock) {
        val deduped = linkedMapOf<String, EmailSenderCandidate>()
        deduped[candidate.email] = candidate
        snapshot.recentSenders.forEach { existing ->
            if (existing.email != candidate.email) {
                deduped[existing.email] = existing
            }
        }
        snapshot = snapshot.copy(recentSenders = deduped.values.take(20))
    }

    fun getSnapshot(): EmailGatewaySnapshot = synchronized(lock) { snapshot }
}
