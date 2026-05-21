package com.palmclaw.bus

import kotlinx.coroutines.channels.Channel

class MessageBus(
    inboundCapacity: Int = DEFAULT_INBOUND_CAPACITY,
    outboundCapacity: Int = DEFAULT_OUTBOUND_CAPACITY
) {
    private val inbound = Channel<InboundMessage>(capacity = inboundCapacity.coerceAtLeast(1))
    private val outbound = Channel<OutboundMessage>(capacity = outboundCapacity.coerceAtLeast(1))

    suspend fun publishInbound(msg: InboundMessage) {
        inbound.send(msg)
    }

    fun tryPublishInbound(msg: InboundMessage): Boolean {
        return inbound.trySend(msg).isSuccess
    }

    suspend fun consumeInbound(): InboundMessage {
        return inbound.receive()
    }

    suspend fun publishOutbound(msg: OutboundMessage) {
        outbound.send(msg)
    }

    fun tryPublishOutbound(msg: OutboundMessage): Boolean {
        return outbound.trySend(msg).isSuccess
    }

    suspend fun consumeOutbound(): OutboundMessage {
        return outbound.receive()
    }

    fun close() {
        inbound.close()
        outbound.close()
    }

    companion object {
        const val DEFAULT_INBOUND_CAPACITY = 256
        const val DEFAULT_OUTBOUND_CAPACITY = 256
    }
}
