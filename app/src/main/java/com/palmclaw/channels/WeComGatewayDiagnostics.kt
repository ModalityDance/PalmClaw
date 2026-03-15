package com.palmclaw.channels

data class WeComChatCandidate(
    val chatId: String,
    val title: String,
    val kind: String,
    val note: String = ""
)

data class WeComGatewaySnapshot(
    val running: Boolean = false,
    val connected: Boolean = false,
    val ready: Boolean = false,
    val inboundSeen: Long = 0L,
    val inboundForwarded: Long = 0L,
    val outboundSent: Long = 0L,
    val lastInboundChatId: String = "",
    val lastSenderUserId: String = "",
    val lastEventType: String = "",
    val lastError: String = "",
    val recentChats: List<WeComChatCandidate> = emptyList()
)

object WeComGatewayDiagnostics {
    @Volatile
    private var snapshot = WeComGatewaySnapshot()

    fun reset() {
        snapshot = WeComGatewaySnapshot()
    }

    fun markRunning(running: Boolean) {
        val current = snapshot
        snapshot = current.copy(running = running)
    }

    fun markConnected(connected: Boolean) {
        val current = snapshot
        snapshot = current.copy(connected = connected, ready = if (!connected) false else current.ready)
    }

    fun markReady() {
        val current = snapshot
        snapshot = current.copy(connected = true, ready = true)
    }

    fun markInboundSeen(chatId: String, senderUserId: String) {
        val current = snapshot
        snapshot = current.copy(
            inboundSeen = current.inboundSeen + 1L,
            lastInboundChatId = chatId,
            lastSenderUserId = senderUserId
        )
    }

    fun markInboundForwarded(chatId: String) {
        val current = snapshot
        snapshot = current.copy(
            inboundForwarded = current.inboundForwarded + 1L,
            lastInboundChatId = chatId
        )
    }

    fun markOutboundSent() {
        val current = snapshot
        snapshot = current.copy(outboundSent = current.outboundSent + 1L)
    }

    fun markEventType(type: String) {
        val current = snapshot
        snapshot = current.copy(lastEventType = type.take(80))
    }

    fun markError(message: String) {
        val current = snapshot
        snapshot = current.copy(lastError = message)
    }

    fun recordCandidate(candidate: WeComChatCandidate) {
        if (candidate.chatId.isBlank()) return
        val current = snapshot
        val next = buildList {
            add(candidate)
            current.recentChats
                .asSequence()
                .filterNot { it.chatId == candidate.chatId }
                .take(MAX_RECENT_CHATS - 1)
                .forEach { add(it) }
        }
        snapshot = current.copy(recentChats = next)
    }

    fun getSnapshot(): WeComGatewaySnapshot = snapshot

    private const val MAX_RECENT_CHATS = 20
}
