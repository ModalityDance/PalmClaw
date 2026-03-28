package com.palmclaw.channels

data class DiscordGatewaySnapshot(
    val running: Boolean = false,
    val connected: Boolean = false,
    val ready: Boolean = false,
    val botUserId: String = "",
    val inboundSeen: Long = 0L,
    val inboundForwarded: Long = 0L,
    val outboundSent: Long = 0L,
    val lastInboundChannelId: String = "",
    val lastError: String = "",
    val lastGatewayPayload: String = ""
)

object DiscordGatewayDiagnostics {
    private val store = AdapterScopedSnapshotStore(::DiscordGatewaySnapshot)

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

    fun markGatewayPayload(adapterKey: String, payload: String) {
        store.update(adapterKey) { it.copy(lastGatewayPayload = payload.take(300)) }
    }

    fun getSnapshot(adapterKey: String? = null): DiscordGatewaySnapshot = store.getSnapshot(adapterKey)

    fun getSnapshots(): Map<String, DiscordGatewaySnapshot> = store.getSnapshots()
}
