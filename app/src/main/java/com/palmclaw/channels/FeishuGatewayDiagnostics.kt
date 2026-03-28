package com.palmclaw.channels

data class FeishuChatCandidate(
    val chatId: String,
    val title: String,
    val kind: String,
    val note: String = ""
)

data class FeishuGatewaySnapshot(
    val running: Boolean = false,
    val connected: Boolean = false,
    val ready: Boolean = false,
    val inboundSeen: Long = 0L,
    val inboundForwarded: Long = 0L,
    val outboundSent: Long = 0L,
    val lastInboundChatId: String = "",
    val lastSenderOpenId: String = "",
    val lastEventType: String = "",
    val lastError: String = "",
    val recentChats: List<FeishuChatCandidate> = emptyList()
)

object FeishuGatewayDiagnostics {
    private val store = AdapterScopedSnapshotStore(::FeishuGatewaySnapshot)

    fun prepareForStart(adapterKey: String) {
        store.update(adapterKey) { current ->
            current.copy(
                running = false,
                connected = false,
                ready = false,
                inboundForwarded = 0L,
                outboundSent = 0L,
                lastEventType = "",
                lastError = ""
            )
        }
    }

    fun reset(adapterKey: String) {
        store.reset(adapterKey)
    }

    fun markRunning(adapterKey: String, running: Boolean) {
        store.update(adapterKey) { it.copy(running = running) }
    }

    fun markConnected(adapterKey: String, connected: Boolean) {
        store.update(adapterKey) { current ->
            current.copy(connected = connected, ready = if (!connected) false else current.ready)
        }
    }

    fun markReady(adapterKey: String) {
        store.update(adapterKey) { it.copy(connected = true, ready = true) }
    }

    fun markInboundSeen(adapterKey: String, chatId: String, senderOpenId: String) {
        store.update(adapterKey) {
            it.copy(
                inboundSeen = it.inboundSeen + 1L,
                lastInboundChatId = chatId,
                lastSenderOpenId = senderOpenId
            )
        }
    }

    fun markInboundForwarded(adapterKey: String, chatId: String) {
        store.update(adapterKey) {
            it.copy(
                inboundForwarded = it.inboundForwarded + 1L,
                lastInboundChatId = chatId
            )
        }
    }

    fun markOutboundSent(adapterKey: String) {
        store.update(adapterKey) { it.copy(outboundSent = it.outboundSent + 1L) }
    }

    fun markEventType(adapterKey: String, type: String) {
        store.update(adapterKey) { it.copy(lastEventType = type.take(80)) }
    }

    fun markError(adapterKey: String, message: String) {
        store.update(adapterKey) { it.copy(lastError = message) }
    }

    fun recordCandidate(adapterKey: String, candidate: FeishuChatCandidate) {
        if (candidate.chatId.isBlank()) return
        store.update(adapterKey) { current ->
            val next = buildList {
                add(candidate)
                current.recentChats
                    .asSequence()
                    .filterNot { it.chatId == candidate.chatId }
                    .take(MAX_RECENT_CHATS - 1)
                    .forEach { add(it) }
            }
            current.copy(recentChats = next)
        }
    }

    fun getSnapshot(adapterKey: String? = null): FeishuGatewaySnapshot = store.getSnapshot(adapterKey)

    fun getSnapshots(): Map<String, FeishuGatewaySnapshot> = store.getSnapshots()

    private const val MAX_RECENT_CHATS = 20
}
