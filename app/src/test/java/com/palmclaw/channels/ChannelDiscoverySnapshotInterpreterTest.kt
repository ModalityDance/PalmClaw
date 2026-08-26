package com.palmclaw.channels

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ChannelDiscoverySnapshotInterpreterTest {
    @Test
    fun `feishu collection merges requested compatible keys and deduplicates candidates`() {
        val result = ChannelDiscoverySnapshotInterpreter.collectFeishu(
            requestedAdapterKeys = listOf("canonical", "legacy"),
            currentBindingAdapterKeys = listOf("canonical"),
            snapshotsByAdapterKey = mapOf(
                "canonical" to FeishuGatewaySnapshot(
                    running = true,
                    recentChats = listOf(FeishuChatCandidate("oc_one", "One", "group"))
                ),
                "legacy" to FeishuGatewaySnapshot(
                    recentChats = listOf(
                        FeishuChatCandidate("oc_one", "Duplicate", "group"),
                        FeishuChatCandidate("oc_two", "Two", "group")
                    )
                )
            )
        )

        assertEquals(listOf("canonical", "legacy"), result.snapshots.keys.toList())
        assertEquals(listOf("oc_one", "oc_two"), result.candidates.map { it.chatId })
    }

    @Test
    fun `feishu collection never borrows a different identity snapshot`() {
        val result = ChannelDiscoverySnapshotInterpreter.collectFeishu(
            requestedAdapterKeys = listOf("requested"),
            currentBindingAdapterKeys = emptyList(),
            snapshotsByAdapterKey = mapOf(
                "external" to FeishuGatewaySnapshot(
                    running = true,
                    recentChats = listOf(FeishuChatCandidate("oc_external", "External", "group"))
                )
            )
        )

        assertEquals(emptyList<FeishuChatCandidate>(), result.candidates)
        assertFalse(result.snapshots.containsKey("external"))
    }

    @Test
    fun `timeout guidance describes the active discovery window`() {
        val feishu = ChannelDiscoverySnapshotInterpreter.feishuInfo(
            FeishuDiscoverySnapshotResult(
                snapshots = mapOf(
                    "key" to FeishuGatewaySnapshot(running = true, connected = true, ready = true)
                ),
                candidates = emptyList()
            )
        )
        val weCom = ChannelDiscoverySnapshotInterpreter.weComInfo(
            WeComGatewaySnapshot(running = true, connected = true, ready = true)
        )

        assertEquals(
            "Feishu discovery is ready, but no inbound message arrived during the detection window. Send one @mention message and detect again.",
            feishu
        )
        assertEquals(
            "WeCom discovery is ready, but no inbound message arrived during the detection window. Send one message and detect again.",
            weCom
        )
    }
}
