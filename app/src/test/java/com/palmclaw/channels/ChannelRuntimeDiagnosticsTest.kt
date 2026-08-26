package com.palmclaw.channels

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ChannelRuntimeDiagnosticsTest {
    private val channel = "test-channel"
    private val adapterKey = "binding-1"

    @Before
    fun resetDiagnostics() {
        ChannelRuntimeDiagnostics.reset(channel, adapterKey)
    }

    @Test
    fun `legacy lifecycle calls expose one consistent binding health state`() {
        assertEquals(ChannelBindingHealthState.STOPPED, snapshot().state)

        ChannelRuntimeDiagnostics.markRunning(channel, adapterKey, true)
        assertEquals(ChannelBindingHealthState.STARTING, snapshot().state)

        ChannelRuntimeDiagnostics.markConnected(channel, adapterKey, true)
        assertEquals(ChannelBindingHealthState.STARTING, snapshot().state)

        ChannelRuntimeDiagnostics.markReady(channel, adapterKey)
        assertEquals(ChannelBindingHealthState.READY, snapshot().state)

        ChannelRuntimeDiagnostics.markConnected(channel, adapterKey, false)
        assertEquals(ChannelBindingHealthState.RECONNECTING, snapshot().state)

        ChannelRuntimeDiagnostics.markRunning(channel, adapterKey, false)
        val stopped = snapshot()
        assertEquals(ChannelBindingHealthState.STOPPED, stopped.state)
        assertFalse(stopped.running)
        assertFalse(stopped.connected)
        assertFalse(stopped.ready)
    }

    @Test
    fun `authentication errors are blocked and do not expose credential material`() {
        ChannelRuntimeDiagnostics.markRunning(channel, adapterKey, true)
        ChannelRuntimeDiagnostics.markError(
            channel,
            adapterKey,
            "HTTP 401 invalid token sk-secret-value for user@example.com"
        )

        val blocked = snapshot()
        assertEquals(ChannelBindingHealthState.BLOCKED, blocked.state)
        assertEquals(ChannelRuntimeErrorCode.AUTHENTICATION_FAILED, blocked.error?.code)
        assertEquals("Authentication required", blocked.lastError)
        assertFalse(blocked.lastError.contains("sk-secret-value"))
        assertFalse(blocked.lastError.contains("user@example.com"))
    }

    @Test
    fun `reconnect metadata is exposed without changing legacy running flags`() {
        ChannelRuntimeDiagnostics.markReconnecting(
            channel = channel,
            adapterKey = adapterKey,
            attempt = 4,
            nextRetryAtEpochMillis = 25_000L,
            error = NormalizedChannelError(ChannelRuntimeErrorCode.NETWORK_UNAVAILABLE)
        )

        val reconnecting = snapshot()
        assertEquals(ChannelBindingHealthState.RECONNECTING, reconnecting.state)
        assertTrue(reconnecting.running)
        assertFalse(reconnecting.connected)
        assertFalse(reconnecting.ready)
        assertEquals(4, reconnecting.retryAttempt)
        assertEquals(25_000L, reconnecting.nextRetryAtEpochMillis)
        assertEquals(ChannelRuntimeErrorCode.NETWORK_UNAVAILABLE, reconnecting.error?.code)
    }

    @Test
    fun `outbound success records liveness without claiming a disconnected binding is ready`() {
        ChannelRuntimeDiagnostics.markReconnecting(
            channel = channel,
            adapterKey = adapterKey,
            attempt = 2,
            nextRetryAtEpochMillis = 9_000L,
            error = NormalizedChannelError(ChannelRuntimeErrorCode.NETWORK_UNAVAILABLE)
        )

        ChannelRuntimeDiagnostics.recordSuccessfulOperation(
            channel = channel,
            adapterKey = adapterKey,
            operation = ChannelOperation.OUTBOUND,
            atEpochMillis = 12_345L
        )

        val reconnecting = snapshot()
        assertEquals(ChannelBindingHealthState.RECONNECTING, reconnecting.state)
        assertFalse(reconnecting.connected)
        assertFalse(reconnecting.ready)
        assertEquals(
            ChannelSuccessfulOperation(ChannelOperation.OUTBOUND, 12_345L),
            reconnecting.lastSuccessfulOperation
        )
        assertEquals(2, reconnecting.retryAttempt)
        assertEquals(9_000L, reconnecting.nextRetryAtEpochMillis)
        assertEquals(ChannelRuntimeErrorCode.NETWORK_UNAVAILABLE, reconnecting.error?.code)
    }

    @Test
    fun `readiness success records liveness and clears retry state`() {
        ChannelRuntimeDiagnostics.markReconnecting(
            channel = channel,
            adapterKey = adapterKey,
            attempt = 2,
            nextRetryAtEpochMillis = 9_000L
        )

        ChannelRuntimeDiagnostics.markReadyAfterOperation(
            channel = channel,
            adapterKey = adapterKey,
            operation = ChannelOperation.INBOUND,
            atEpochMillis = 12_345L
        )

        val ready = snapshot()
        assertEquals(ChannelBindingHealthState.READY, ready.state)
        assertEquals(ChannelSuccessfulOperation(ChannelOperation.INBOUND, 12_345L), ready.lastSuccessfulOperation)
        assertEquals(0, ready.retryAttempt)
        assertNull(ready.nextRetryAtEpochMillis)
        assertNull(ready.error)
        assertEquals("", ready.lastError)
    }

    @Test
    fun `operation warning remains observational and does not start reconnecting`() {
        ChannelRuntimeDiagnostics.markReady(channel, adapterKey)

        ChannelRuntimeDiagnostics.markOperationWarning(
            channel = channel,
            adapterKey = adapterKey,
            operation = ChannelOperation.HEARTBEAT,
            error = NormalizedChannelError(ChannelRuntimeErrorCode.PROTOCOL_ERROR),
            atEpochMillis = 2_000L
        )

        val ready = snapshot()
        assertEquals(ChannelBindingHealthState.READY, ready.state)
        assertTrue(ready.connected)
        assertTrue(ready.ready)
        assertEquals("", ready.lastError)
        assertNull(ready.error)
        assertEquals(
            ChannelOperationWarning(
                operation = ChannelOperation.HEARTBEAT,
                error = NormalizedChannelError(ChannelRuntimeErrorCode.PROTOCOL_ERROR),
                atEpochMillis = 2_000L
            ),
            ready.lastOperationWarning
        )
    }

    @Test
    fun `stop clears connection errors and warnings from the inactive binding`() {
        ChannelRuntimeDiagnostics.markReconnecting(
            channel = channel,
            adapterKey = adapterKey,
            attempt = 1,
            nextRetryAtEpochMillis = 4_000L,
            error = NormalizedChannelError(ChannelRuntimeErrorCode.NETWORK_UNAVAILABLE)
        )
        ChannelRuntimeDiagnostics.markOperationWarning(
            channel = channel,
            adapterKey = adapterKey,
            operation = ChannelOperation.OUTBOUND,
            error = NormalizedChannelError(ChannelRuntimeErrorCode.UNKNOWN),
            atEpochMillis = 3_000L
        )

        ChannelRuntimeDiagnostics.markStopped(channel, adapterKey)

        val stopped = snapshot()
        assertEquals(ChannelBindingHealthState.STOPPED, stopped.state)
        assertEquals("", stopped.lastError)
        assertNull(stopped.error)
        assertNull(stopped.lastOperationWarning)
    }

    @Test
    fun `state flow publishes the latest immutable snapshot by binding identity`() {
        val flowChannel = "state-flow-channel"
        val flowAdapterKey = "binding-flow"
        ChannelRuntimeDiagnostics.reset(flowChannel, flowAdapterKey)
        val before = ChannelRuntimeDiagnostics.state.value

        ChannelRuntimeDiagnostics.markRunning(flowChannel, flowAdapterKey, true)

        val identity = ChannelRuntimeBindingId(flowChannel, flowAdapterKey)
        assertEquals(ChannelBindingHealthState.STARTING, ChannelRuntimeDiagnostics.state.value[identity]?.state)
        assertEquals(ChannelBindingHealthState.STOPPED, before[identity]?.state)
        assertTrue(before !== ChannelRuntimeDiagnostics.state.value)
    }

    private fun snapshot(): ChannelRuntimeSnapshot =
        ChannelRuntimeDiagnostics.getSnapshot(channel, adapterKey)
}
