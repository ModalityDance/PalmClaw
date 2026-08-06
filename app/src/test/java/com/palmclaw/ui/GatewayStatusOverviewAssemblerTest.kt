package com.palmclaw.ui

import com.palmclaw.channels.ChannelGatewayDiagnosticsSnapshot
import com.palmclaw.channels.ChannelGatewayDiagnosticsSource
import com.palmclaw.channels.ChannelRuntimeSnapshot
import com.palmclaw.channels.DiscordGatewaySnapshot
import com.palmclaw.channels.EmailGatewaySnapshot
import com.palmclaw.channels.FeishuGatewaySnapshot
import com.palmclaw.channels.SlackGatewaySnapshot
import com.palmclaw.channels.WeComGatewaySnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GatewayStatusOverviewAssemblerTest {
    @Test
    fun `build reads one snapshot and formats all gateway status families`() {
        var snapshotCalls = 0
        val runtime = ChannelRuntimeSnapshot(running = true, connected = true, ready = true)
        val source = ChannelGatewayDiagnosticsSource {
            snapshotCalls += 1
            ChannelGatewayDiagnosticsSnapshot(
                runtimeSnapshotsByChannel = mapOf(
                    "discord" to listOf(runtime),
                    "slack" to listOf(runtime),
                    "feishu" to listOf(runtime),
                    "email" to listOf(runtime),
                    "wecom" to listOf(runtime)
                ),
                discordSnapshots = listOf(DiscordGatewaySnapshot(inboundSeen = 1)),
                slackSnapshots = listOf(SlackGatewaySnapshot(inboundSeen = 1)),
                feishuSnapshots = listOf(
                    FeishuGatewaySnapshot(
                        inboundSeen = 2,
                        inboundForwarded = 1,
                        outboundSent = 3
                    )
                ),
                emailSnapshots = listOf(EmailGatewaySnapshot(inboundSeen = 1)),
                weComSnapshots = listOf(WeComGatewaySnapshot(inboundSeen = 1))
            )
        }

        val result = GatewayStatusOverviewAssembler(source).build()

        assertEquals(1, snapshotCalls)
        assertEquals(
            "Adapters: 1\nRunning: 1\nConnected: 1\nReady: 1\n" +
                "Inbound seen: 2\nInbound forwarded: 1\nOutbound sent: 3",
            result.feishu
        )
        assertTrue(result.discord.contains("Inbound seen: 1"))
        assertTrue(result.slack.contains("Inbound seen: 1"))
        assertTrue(result.email.contains("Inbound seen: 1"))
        assertTrue(result.wecom.contains("Inbound seen: 1"))
    }
}
