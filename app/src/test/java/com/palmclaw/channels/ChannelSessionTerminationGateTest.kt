package com.palmclaw.channels

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChannelSessionTerminationGateTest {
    @Test
    fun `only the first terminal path owns a websocket session`() {
        val gate = ChannelSessionTerminationGate()

        assertTrue(gate.claim())
        assertFalse(gate.claim())
        assertTrue(gate.isClaimed())
    }

    @Test
    fun `late callback from an ended session cannot claim a newer session`() {
        val endedSession = ChannelSessionTerminationGate()
        val newSession = ChannelSessionTerminationGate()

        assertTrue(endedSession.claim())

        assertFalse(endedSession.claim())
        assertFalse(newSession.isClaimed())
        assertTrue(newSession.claim())
    }
}
