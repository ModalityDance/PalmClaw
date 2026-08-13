package com.palmclaw.channels

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChannelGatewayDiagnosticsStopTest {
    @Test
    fun `stopping clears stale errors from protocol diagnostics`() {
        val adapterKey = "stop-test"

        DiscordGatewayDiagnostics.reset(adapterKey)
        DiscordGatewayDiagnostics.markReady(adapterKey, "bot")
        DiscordGatewayDiagnostics.markError(adapterKey, "old error")
        DiscordGatewayDiagnostics.markRunning(adapterKey, false)
        DiscordGatewayDiagnostics.getSnapshot(adapterKey).also {
            assertStopped(it.running, it.connected, it.ready, it.lastError)
        }

        SlackGatewayDiagnostics.reset(adapterKey)
        SlackGatewayDiagnostics.markReady(adapterKey, "bot")
        SlackGatewayDiagnostics.markError(adapterKey, "old error")
        SlackGatewayDiagnostics.markRunning(adapterKey, false)
        SlackGatewayDiagnostics.getSnapshot(adapterKey).also {
            assertStopped(it.running, it.connected, it.ready, it.lastError)
        }

        FeishuGatewayDiagnostics.reset(adapterKey)
        FeishuGatewayDiagnostics.markReady(adapterKey)
        FeishuGatewayDiagnostics.markError(adapterKey, "old error")
        FeishuGatewayDiagnostics.markRunning(adapterKey, false)
        FeishuGatewayDiagnostics.getSnapshot(adapterKey).also {
            assertStopped(it.running, it.connected, it.ready, it.lastError)
        }

        WeComGatewayDiagnostics.reset(adapterKey)
        WeComGatewayDiagnostics.markReady(adapterKey)
        WeComGatewayDiagnostics.markError(adapterKey, "old error")
        WeComGatewayDiagnostics.markRunning(adapterKey, false)
        WeComGatewayDiagnostics.getSnapshot(adapterKey).also {
            assertStopped(it.running, it.connected, it.ready, it.lastError)
        }

        EmailGatewayDiagnostics.reset(adapterKey)
        EmailGatewayDiagnostics.markReady(adapterKey)
        EmailGatewayDiagnostics.markError(adapterKey, "old error")
        EmailGatewayDiagnostics.markRunning(adapterKey, false)
        EmailGatewayDiagnostics.getSnapshot(adapterKey).also {
            assertStopped(it.running, it.connected, it.ready, it.lastError)
        }
    }

    @Test
    fun `Feishu authentication success remains unready until inbound evidence`() {
        val adapterKey = "feishu-authenticated"

        FeishuGatewayDiagnostics.reset(adapterKey)
        FeishuGatewayDiagnostics.markRunning(adapterKey, true)
        FeishuGatewayDiagnostics.markAuthenticated(adapterKey)

        FeishuGatewayDiagnostics.getSnapshot(adapterKey).also {
            assertFalse(it.ready)
            assertFalse(it.connected)
        }

        FeishuGatewayDiagnostics.markReady(adapterKey)
        assertTrue(FeishuGatewayDiagnostics.getSnapshot(adapterKey).ready)
    }

    private fun assertStopped(
        running: Boolean,
        connected: Boolean,
        ready: Boolean,
        lastError: String
    ) {
        assertFalse(running)
        assertFalse(connected)
        assertFalse(ready)
        assertEquals("", lastError)
    }
}
