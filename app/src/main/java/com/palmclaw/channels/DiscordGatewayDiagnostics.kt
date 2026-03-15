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
    @Volatile
    private var snapshot = DiscordGatewaySnapshot()

    fun reset() {
        snapshot = DiscordGatewaySnapshot()
    }

    fun markRunning(running: Boolean) {
        val current = snapshot
        snapshot = current.copy(running = running)
    }

    fun markConnected(connected: Boolean) {
        val current = snapshot
        snapshot = current.copy(connected = connected, ready = if (!connected) false else current.ready)
    }

    fun markReady(botUserId: String?) {
        val current = snapshot
        snapshot = current.copy(
            connected = true,
            ready = true,
            botUserId = botUserId.orEmpty()
        )
    }

    fun markInboundSeen(channelId: String) {
        val current = snapshot
        snapshot = current.copy(
            inboundSeen = current.inboundSeen + 1L,
            lastInboundChannelId = channelId
        )
    }

    fun markInboundForwarded(channelId: String) {
        val current = snapshot
        snapshot = current.copy(
            inboundForwarded = current.inboundForwarded + 1L,
            lastInboundChannelId = channelId
        )
    }

    fun markOutboundSent() {
        val current = snapshot
        snapshot = current.copy(outboundSent = current.outboundSent + 1L)
    }

    fun markError(message: String) {
        val current = snapshot
        snapshot = current.copy(lastError = message)
    }

    fun markGatewayPayload(payload: String) {
        val current = snapshot
        snapshot = current.copy(lastGatewayPayload = payload.take(300))
    }

    fun getSnapshot(): DiscordGatewaySnapshot = snapshot
}
