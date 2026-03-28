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
    private val store = AdapterScopedSnapshotStore(::EmailGatewaySnapshot)

    fun reset(adapterKey: String) {
        store.reset(adapterKey)
    }

    fun markRunning(adapterKey: String, value: Boolean) {
        store.update(adapterKey) { it.copy(running = value) }
    }

    fun markConnected(adapterKey: String, value: Boolean) {
        store.update(adapterKey) { it.copy(connected = value) }
    }

    fun markReady(adapterKey: String) {
        store.update(adapterKey) { it.copy(connected = true, ready = true, lastError = "") }
    }

    fun markInboundSeen(adapterKey: String, senderEmail: String, subject: String) {
        store.update(adapterKey) {
            it.copy(
                inboundSeen = it.inboundSeen + 1,
                lastSenderEmail = senderEmail,
                lastSubject = subject
            )
        }
    }

    fun markInboundForwarded(adapterKey: String) {
        store.update(adapterKey) { it.copy(inboundForwarded = it.inboundForwarded + 1) }
    }

    fun markOutboundSent(adapterKey: String) {
        store.update(adapterKey) { it.copy(outboundSent = it.outboundSent + 1) }
    }

    fun markError(adapterKey: String, message: String) {
        store.update(adapterKey) { it.copy(lastError = message) }
    }

    fun recordSender(adapterKey: String, candidate: EmailSenderCandidate) {
        store.update(adapterKey) { current ->
            val deduped = linkedMapOf<String, EmailSenderCandidate>()
            deduped[candidate.email] = candidate
            current.recentSenders.forEach { existing ->
                if (existing.email != candidate.email) {
                    deduped[existing.email] = existing
                }
            }
            current.copy(recentSenders = deduped.values.take(20))
        }
    }

    fun getSnapshot(adapterKey: String? = null): EmailGatewaySnapshot = store.getSnapshot(adapterKey)

    fun getSnapshots(): Map<String, EmailGatewaySnapshot> = store.getSnapshots()
}
