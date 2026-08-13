package com.palmclaw.channels

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChannelGatewayDiagnosticsSourceTest {
    @Test
    fun `process source copies runtime and gateway diagnostics for every supported status family`() {
        val snapshot = ProcessChannelGatewayDiagnosticsSource.snapshot()

        listOf("telegram", "discord", "slack", "feishu", "email", "wecom").forEach { channel ->
            assertTrue(snapshot.runtimeSnapshotsByChannel.containsKey(channel))
            assertEquals(
                ChannelRuntimeDiagnostics.getSnapshots(channel).values.toList(),
                snapshot.runtimeSnapshots(channel)
            )
        }
        assertEquals(DiscordGatewayDiagnostics.getSnapshots().values.toList(), snapshot.discordSnapshots)
        assertEquals(SlackGatewayDiagnostics.getSnapshots().values.toList(), snapshot.slackSnapshots)
        assertEquals(FeishuGatewayDiagnostics.getSnapshots().values.toList(), snapshot.feishuSnapshots)
        assertEquals(EmailGatewayDiagnostics.getSnapshots().values.toList(), snapshot.emailSnapshots)
        assertEquals(WeComGatewayDiagnostics.getSnapshots().values.toList(), snapshot.weComSnapshots)
    }

    @Test
    fun `snapshot normalizes channel names without exposing a mutable fallback`() {
        val runtimeSnapshot = ChannelRuntimeSnapshot(running = true)
        val snapshot = ChannelGatewayDiagnosticsSnapshot(
            runtimeSnapshotsByChannel = mapOf("discord" to listOf(runtimeSnapshot)),
            discordSnapshots = emptyList(),
            slackSnapshots = emptyList(),
            feishuSnapshots = emptyList(),
            emailSnapshots = emptyList(),
            weComSnapshots = emptyList()
        )

        assertEquals(listOf(runtimeSnapshot), snapshot.runtimeSnapshots(" DISCORD "))
        assertTrue(snapshot.runtimeSnapshots("unknown").isEmpty())
    }
}
