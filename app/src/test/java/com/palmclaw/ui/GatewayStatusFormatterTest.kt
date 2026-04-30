package com.palmclaw.ui

import com.palmclaw.channels.ChannelRuntimeSnapshot
import com.palmclaw.channels.DiscordGatewaySnapshot
import com.palmclaw.channels.EmailGatewaySnapshot
import com.palmclaw.channels.EmailSenderCandidate
import com.palmclaw.channels.WeComChatCandidate
import com.palmclaw.channels.WeComGatewaySnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GatewayStatusFormatterTest {

    @Test
    fun `buildDiscordStatus includes runtime summary and optional snapshot fields`() {
        val status = GatewayStatusFormatter.buildDiscordStatus(
            runtimeSnapshots = listOf(
                ChannelRuntimeSnapshot(
                    running = true,
                    connected = true,
                    ready = false,
                    lastError = "gateway closed"
                ),
                ChannelRuntimeSnapshot(
                    running = true,
                    connected = true,
                    ready = true
                )
            ),
            gatewaySnapshots = listOf(
                DiscordGatewaySnapshot(
                    botUserId = "bot-1",
                    inboundSeen = 3L,
                    inboundForwarded = 2L,
                    outboundSent = 1L,
                    lastInboundChannelId = "channel-42",
                    lastGatewayPayload = "{\"op\":0}"
                )
            )
        )

        assertTrue(status.contains("Adapters: 2"))
        assertTrue(status.contains("Running: 2"))
        assertTrue(status.contains("Ready: 1"))
        assertTrue(status.contains("Runtime error: gateway closed"))
        assertTrue(status.contains("Bot User ID: bot-1"))
        assertTrue(status.contains("Last inbound channel: channel-42"))
        assertTrue(status.contains("Last payload: {\"op\":0}"))
    }

    @Test
    fun `buildEmailStatus sums sender stats and detected senders`() {
        val status = GatewayStatusFormatter.buildEmailStatus(
            runtimeSnapshots = emptyList(),
            gatewaySnapshots = listOf(
                EmailGatewaySnapshot(
                    inboundSeen = 2,
                    inboundForwarded = 1,
                    outboundSent = 4,
                    lastSenderEmail = "alpha@example.com",
                    lastSubject = "Quarterly update",
                    recentSenders = listOf(
                        EmailSenderCandidate(email = "alpha@example.com", subject = "Quarterly update"),
                        EmailSenderCandidate(email = "beta@example.com", subject = "Reminder")
                    )
                )
            )
        )

        assertTrue(status.contains("Adapters: 0"))
        assertTrue(status.contains("Inbound seen: 2"))
        assertTrue(status.contains("Outbound sent: 4"))
        assertTrue(status.contains("Last sender: alpha@example.com"))
        assertTrue(status.contains("Last subject: Quarterly update"))
        assertTrue(status.contains("Detected senders: 2"))
    }

    @Test
    fun `buildWeComStatus omits detected chats when none exist`() {
        val status = GatewayStatusFormatter.buildWeComStatus(
            runtimeSnapshots = listOf(ChannelRuntimeSnapshot(running = true)),
            gatewaySnapshots = listOf(
                WeComGatewaySnapshot(
                    inboundSeen = 1L,
                    inboundForwarded = 1L,
                    outboundSent = 0L,
                    lastInboundChatId = "chat-1",
                    lastSenderUserId = "user-9",
                    lastEventType = "message"
                )
            )
        )

        assertTrue(status.contains("Last inbound target: chat-1"))
        assertTrue(status.contains("Last sender user ID: user-9"))
        assertTrue(status.contains("Last event: message"))
        assertEquals(false, status.contains("Detected chats:"))
    }

    @Test
    fun `buildWeComStatus includes detected chats when present`() {
        val status = GatewayStatusFormatter.buildWeComStatus(
            runtimeSnapshots = emptyList(),
            gatewaySnapshots = listOf(
                WeComGatewaySnapshot(
                    recentChats = listOf(
                        WeComChatCandidate(chatId = "chat-1", title = "Ops", kind = "group")
                    )
                )
            )
        )

        assertTrue(status.contains("Detected chats: 1"))
    }
}
