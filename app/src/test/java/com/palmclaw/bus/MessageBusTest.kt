package com.palmclaw.bus

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageBusTest {

    @Test
    fun `try publish reports backpressure when inbound buffer is full`() {
        val bus = MessageBus(inboundCapacity = 1, outboundCapacity = 1)

        assertTrue(bus.tryPublishInbound(inbound("first")))
        assertFalse(bus.tryPublishInbound(inbound("second")))

        bus.close()
    }

    @Test
    fun `try publish reports backpressure when outbound buffer is full`() {
        val bus = MessageBus(inboundCapacity = 1, outboundCapacity = 1)

        assertTrue(bus.tryPublishOutbound(OutboundMessage(channel = "telegram", chatId = "chat", content = "first")))
        assertFalse(bus.tryPublishOutbound(OutboundMessage(channel = "telegram", chatId = "chat", content = "second")))

        bus.close()
    }

    private fun inbound(content: String): InboundMessage {
        return InboundMessage(
            channel = "telegram",
            senderId = "sender",
            chatId = "chat",
            content = content
        )
    }
}
