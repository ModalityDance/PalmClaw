package com.palmclaw.bus

import kotlinx.coroutines.channels.Channel

class MessageBus {
    private val inbound = Channel<InboundMessage>(capacity = Channel.UNLIMITED)
    private val outbound = Channel<OutboundMessage>(capacity = Channel.UNLIMITED)

    suspend fun publishInbound(msg: InboundMessage) {
        inbound.send(msg)
    }

    suspend fun consumeInbound(): InboundMessage {
        return inbound.receive()
    }

    suspend fun publishOutbound(msg: OutboundMessage) {
        outbound.send(msg)
    }

    suspend fun consumeOutbound(): OutboundMessage {
        return outbound.receive()
    }

    fun close() {
        inbound.close()
        outbound.close()
    }
}
