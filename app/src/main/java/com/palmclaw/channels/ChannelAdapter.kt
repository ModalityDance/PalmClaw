package com.palmclaw.channels

import com.palmclaw.bus.InboundMessage
import com.palmclaw.bus.OutboundMessage
import kotlinx.coroutines.CoroutineScope

interface ChannelAdapter {
    val channelName: String
    val adapterKey: String
    fun start(scope: CoroutineScope, publishInbound: suspend (InboundMessage) -> Unit)
    fun canHandleOutbound(message: OutboundMessage): Boolean
    suspend fun send(message: OutboundMessage)
    fun stop()
}

