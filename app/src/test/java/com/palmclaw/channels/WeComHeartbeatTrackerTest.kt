package com.palmclaw.channels

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WeComHeartbeatTrackerTest {
    @Test
    fun `heartbeat acknowledgements are atomic and never become negative`() {
        val tracker = WeComHeartbeatTracker()

        assertEquals(1, tracker.recordSent())
        assertEquals(2, tracker.recordSent())
        assertTrue(tracker.hasTimedOut(maxPending = 1))

        assertEquals(1, tracker.acknowledge())
        assertFalse(tracker.hasTimedOut(maxPending = 1))
        assertEquals(0, tracker.acknowledge())
        assertEquals(0, tracker.acknowledge())
        assertEquals(0, tracker.pendingCount())
    }

    @Test
    fun `reset clears pending heartbeat state between websocket sessions`() {
        val tracker = WeComHeartbeatTracker()
        repeat(3) { tracker.recordSent() }

        tracker.reset()

        assertEquals(0, tracker.pendingCount())
        assertFalse(tracker.hasTimedOut(maxPending = 0))
    }
}
