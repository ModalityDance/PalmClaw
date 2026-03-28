package com.palmclaw.channels

data class SlackGatewaySnapshot(
    val running: Boolean = false,
    val connected: Boolean = false,
    val ready: Boolean = false,
    val botUserId: String = "",
    val inboundSeen: Long = 0L,
    val inboundForwarded: Long = 0L,
    val outboundSent: Long = 0L,
    val lastInboundChannelId: String = "",
    val lastError: String = "",
    val lastEnvelopeType: String = ""
)

object SlackGatewayDiagnostics {
    private val store = AdapterScopedSnapshotStore(::SlackGatewaySnapshot)

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

    fun markReady(adapterKey: String, botUserId: String?) {
        store.update(adapterKey) {
            it.copy(
                connected = true,
                ready = true,
                botUserId = botUserId.orEmpty()
            )
        }
    }

    fun markInboundSeen(adapterKey: String, channelId: String) {
        store.update(adapterKey) {
            it.copy(
                inboundSeen = it.inboundSeen + 1L,
                lastInboundChannelId = channelId
            )
        }
    }

    fun markInboundForwarded(adapterKey: String, channelId: String) {
        store.update(adapterKey) {
            it.copy(
                inboundForwarded = it.inboundForwarded + 1L,
                lastInboundChannelId = channelId
            )
        }
    }

    fun markOutboundSent(adapterKey: String) {
        store.update(adapterKey) { it.copy(outboundSent = it.outboundSent + 1L) }
    }

    fun markError(adapterKey: String, message: String) {
        store.update(adapterKey) { it.copy(lastError = message) }
    }

    fun markEnvelopeType(adapterKey: String, type: String) {
        store.update(adapterKey) { it.copy(lastEnvelopeType = type.take(64)) }
    }

    fun getSnapshot(adapterKey: String? = null): SlackGatewaySnapshot = store.getSnapshot(adapterKey)

    fun getSnapshots(): Map<String, SlackGatewaySnapshot> = store.getSnapshots()
}
