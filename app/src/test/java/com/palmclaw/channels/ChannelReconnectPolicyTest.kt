package com.palmclaw.channels

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChannelReconnectPolicyTest {
    @Test
    fun `failures back off from two seconds to five minutes`() {
        val policy = ChannelReconnectPolicy()
        var state = ChannelReconnectState()
        val delays = mutableListOf<Long>()

        repeat(10) { index ->
            val transition = policy.transition(
                state,
                ChannelReconnectEvent.Failure(
                    atEpochMillis = index * 1_000L,
                    networkAvailable = true
                )
            )
            state = transition.state
            delays += requireNotNull(transition.retry).delayMillis
        }

        assertEquals(
            listOf(
                2_000L,
                4_000L,
                8_000L,
                16_000L,
                32_000L,
                64_000L,
                128_000L,
                256_000L,
                300_000L,
                300_000L
            ),
            delays
        )
    }

    @Test
    fun `injected jitter is deterministic and remains bounded by the maximum`() {
        val policy = ChannelReconnectPolicy(
            jitter = ChannelRetryJitter { baseDelayMillis, _ ->
                baseDelayMillis + baseDelayMillis / 10
            }
        )

        val first = policy.transition(
            ChannelReconnectState(),
            ChannelReconnectEvent.Failure(atEpochMillis = 1_000L, networkAvailable = true)
        )
        val capped = policy.transition(
            ChannelReconnectState(attempt = 9),
            ChannelReconnectEvent.Failure(atEpochMillis = 2_000L, networkAvailable = true)
        )

        assertEquals(2_200L, first.retry?.delayMillis)
        assertEquals(300_000L, capped.retry?.delayMillis)
    }

    @Test
    fun `network loss freezes retries and network restoration retries immediately`() {
        val policy = ChannelReconnectPolicy()
        val offline = policy.transition(
            ChannelReconnectState(attempt = 2),
            ChannelReconnectEvent.Failure(atEpochMillis = 10_000L, networkAvailable = false)
        )

        assertNull(offline.retry)
        assertTrue(offline.state.waitingForNetwork)
        assertEquals(3, offline.state.attempt)

        val restored = policy.transition(
            offline.state,
            ChannelReconnectEvent.NetworkRestored(atEpochMillis = 20_000L)
        )

        assertEquals(3, restored.retry?.attempt)
        assertEquals(0L, restored.retry?.delayMillis)
        assertEquals(20_000L, restored.retry?.atEpochMillis)
        assertEquals(false, restored.state.waitingForNetwork)
    }

    @Test
    fun `network loss freezes an already scheduled attempt without incrementing it`() {
        val policy = ChannelReconnectPolicy()
        val scheduled = policy.transition(
            ChannelReconnectState(attempt = 2),
            ChannelReconnectEvent.Failure(atEpochMillis = 10_000L, networkAvailable = true)
        )

        val offline = policy.transition(
            scheduled.state,
            ChannelReconnectEvent.NetworkLost
        )
        val restored = policy.transition(
            offline.state,
            ChannelReconnectEvent.NetworkRestored(atEpochMillis = 20_000L)
        )

        assertNull(offline.retry)
        assertEquals(3, offline.state.attempt)
        assertTrue(offline.state.waitingForNetwork)
        assertEquals(3, restored.retry?.attempt)
        assertEquals(0L, restored.retry?.delayMillis)
    }

    @Test
    fun `five stable minutes reset the next failure to the first attempt`() {
        val policy = ChannelReconnectPolicy()
        val ready = policy.transition(
            ChannelReconnectState(attempt = 6),
            ChannelReconnectEvent.Ready(atEpochMillis = 1_000L)
        )
        val stillReady = policy.transition(
            ready.state,
            ChannelReconnectEvent.Ready(atEpochMillis = 200_000L)
        )

        val transition = policy.transition(
            stillReady.state,
            ChannelReconnectEvent.Failure(
                atEpochMillis = 301_000L,
                networkAvailable = true
            )
        )

        assertEquals(1_000L, stillReady.state.readySinceEpochMillis)
        assertEquals(1, transition.retry?.attempt)
        assertEquals(2_000L, transition.retry?.delayMillis)
    }

    @Test
    fun `stopping clears pending recovery history`() {
        val policy = ChannelReconnectPolicy()

        val stopped = policy.transition(
            ChannelReconnectState(
                attempt = 4,
                readySinceEpochMillis = 2_000L,
                waitingForNetwork = true
            ),
            ChannelReconnectEvent.Stopped
        )

        assertEquals(ChannelReconnectState(), stopped.state)
        assertNull(stopped.retry)
    }
}
