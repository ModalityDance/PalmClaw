package com.palmclaw.channels

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DiscordGatewayRecoveryTest {
    @Test
    fun `non reconnectable close codes become stable blocked states`() {
        assertEquals(
            DiscordGatewayCloseDisposition.BLOCK_AUTHENTICATION,
            discordGatewayCloseDisposition(4004)
        )
        listOf(4010, 4011, 4012, 4013, 4014).forEach { closeCode ->
            assertEquals(
                DiscordGatewayCloseDisposition.BLOCK_CONFIGURATION,
                discordGatewayCloseDisposition(closeCode)
            )
        }
    }

    @Test
    fun `invalid sequence and expired session retry with identify`() {
        assertEquals(
            DiscordGatewayCloseDisposition.RETRY_IDENTIFY,
            discordGatewayCloseDisposition(4007)
        )
        assertEquals(
            DiscordGatewayCloseDisposition.RETRY_IDENTIFY,
            discordGatewayCloseDisposition(4009)
        )
    }

    @Test
    fun `resumable close retains session and selects resume gateway`() {
        val recovery = DiscordGatewayRecovery(DEFAULT_GATEWAY)
        recovery.recordSequence(42L)
        recovery.recordReady(
            sessionId = "session-id",
            resumeGatewayUrl = "wss://resume.discord.gg"
        )

        recovery.onClose(4000)

        val handshake = recovery.nextHandshake()
        assertTrue(handshake is DiscordGatewayHandshake.Resume)
        handshake as DiscordGatewayHandshake.Resume
        assertEquals("session-id", handshake.sessionId)
        assertEquals(42L, handshake.sequence)
        assertEquals(
            "wss://resume.discord.gg?v=10&encoding=json",
            handshake.gatewayUrl
        )
    }

    @Test
    fun `non resumable recovery discards session and selects identify`() {
        val recovery = DiscordGatewayRecovery(DEFAULT_GATEWAY)
        recovery.recordSequence(42L)
        recovery.recordReady("session-id", "wss://resume.discord.gg")

        recovery.onClose(4007)

        assertEquals(DiscordGatewayHandshake.Identify, recovery.nextHandshake())
        assertEquals(DEFAULT_GATEWAY, recovery.nextGatewayUrl())
        assertNull(recovery.sequence())
    }

    @Test
    fun `invalid session only retains resume state when gateway permits it`() {
        val recovery = DiscordGatewayRecovery(DEFAULT_GATEWAY)
        recovery.recordSequence(7L)
        recovery.recordReady("session-id", "wss://resume.discord.gg")

        recovery.onInvalidSession(canResume = true)
        assertTrue(recovery.nextHandshake() is DiscordGatewayHandshake.Resume)

        recovery.onInvalidSession(canResume = false)
        assertEquals(DiscordGatewayHandshake.Identify, recovery.nextHandshake())
    }

    @Test
    fun `heartbeat watchdog rejects another heartbeat until ack arrives`() {
        val watchdog = DiscordHeartbeatWatchdog()

        assertTrue(watchdog.beginHeartbeat())
        assertFalse(watchdog.beginHeartbeat())
        assertTrue(watchdog.awaitingAck())

        watchdog.acknowledge()

        assertFalse(watchdog.awaitingAck())
        assertTrue(watchdog.beginHeartbeat())
        watchdog.reset()
        assertFalse(watchdog.awaitingAck())
    }

    private companion object {
        const val DEFAULT_GATEWAY = "wss://gateway.discord.gg/?v=10&encoding=json"
    }
}
