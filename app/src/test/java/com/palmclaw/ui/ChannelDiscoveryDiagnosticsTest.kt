package com.palmclaw.ui

import com.palmclaw.channels.FeishuChatCandidate
import com.palmclaw.channels.FeishuGatewaySnapshot
import com.palmclaw.channels.WeComGatewaySnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChannelDiscoveryDiagnosticsTest {

    @Test
    fun `collectFeishuDiscoveryResult falls back to single active external snapshot`() {
        val result = ChannelDiscoveryDiagnostics.collectFeishuDiscoveryResult(
            requestedAdapterKeys = listOf("requested"),
            currentBindingAdapterKeys = emptyList(),
            snapshotsByAdapterKey = mapOf(
                "external" to FeishuGatewaySnapshot(
                    running = true,
                    recentChats = listOf(
                        FeishuChatCandidate(
                            chatId = "chat-1",
                            title = "External Chat",
                            kind = "group"
                        )
                    )
                )
            )
        )

        assertEquals(1, result.candidates.size)
        assertEquals("chat-1", result.candidates.first().chatId)
        assertTrue(result.snapshots.containsKey("external"))
    }

    @Test
    fun `buildFeishuDiscoveryInfo detects running snapshot mismatch`() {
        val message = ChannelDiscoveryDiagnostics.buildFeishuDiscoveryInfo(
            requestedAdapterKeys = listOf("requested"),
            currentBindingAdapterKeys = listOf("current"),
            snapshots = mapOf(
                "requested" to FeishuGatewaySnapshot(),
                "current" to FeishuGatewaySnapshot(running = true)
            )
        )

        assertEquals(
            "These fields do not match the running Feishu connection. Save first, then detect again.",
            message
        )
    }

    @Test
    fun `buildWeComDiscoveryInfo explains inbound prerequisite`() {
        val message = ChannelDiscoveryDiagnostics.buildWeComDiscoveryInfo(
            WeComGatewaySnapshot(
                running = true,
                connected = true,
                ready = true,
                inboundSeen = 0L
            )
        )

        assertEquals(
            "WeCom connection is ready, but PalmClaw has not received any inbound message yet. Send one message from WeCom first.",
            message
        )
    }
}
