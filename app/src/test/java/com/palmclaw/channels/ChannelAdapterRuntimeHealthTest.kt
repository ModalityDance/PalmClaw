package com.palmclaw.channels

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChannelAdapterRuntimeHealthTest {
    @Test
    fun `transient failure schedules the shared first retry and exposes it`() = runBlocking {
        val delays = mutableListOf<Long>()
        val health = health(
            sleep = { delayMillis -> delays += delayMillis }
        )
        health.starting()
        health.failure("socket timeout while connecting to a remote endpoint")

        assertEquals(ChannelBindingHealthState.RECONNECTING, currentSnapshot().state)
        assertFalse(currentSnapshot().connected)
        assertFalse(currentSnapshot().ready)
        val retrying = health.awaitReconnect()

        assertTrue(retrying)
        assertEquals(listOf(2_000L), delays)
        val snapshot = ChannelRuntimeDiagnostics.getSnapshot(CHANNEL, ADAPTER_KEY)
        assertEquals(ChannelBindingHealthState.RECONNECTING, snapshot.state)
        assertEquals(1, snapshot.retryAttempt)
        assertEquals(12_000L, snapshot.nextRetryAtEpochMillis)
        assertEquals(ChannelRuntimeErrorCode.NETWORK_UNAVAILABLE, snapshot.error?.code)
    }

    @Test
    fun `offline recovery waits for the network seam and then retries immediately`() = runBlocking {
        val network = MutableChannelNetworkAvailability(initiallyAvailable = false)
        val delays = mutableListOf<Long>()
        val health = health(
            network = network,
            sleep = { delayMillis -> delays += delayMillis }
        )
        health.starting()
        health.failure("network unavailable")

        val recovery = async { health.awaitReconnect() }
        yield()
        assertTrue(delays.isEmpty())
        assertTrue(ChannelRuntimeDiagnostics.getSnapshot(CHANNEL, ADAPTER_KEY).nextRetryAtEpochMillis == null)

        network.update(true)

        assertTrue(recovery.await())
        assertEquals(listOf(0L), delays)
        assertFalse(ChannelRuntimeDiagnostics.getSnapshot(CHANNEL, ADAPTER_KEY).nextRetryAtEpochMillis == null)
    }

    @Test
    fun `network loss during backoff freezes and restoration retries immediately`() = runBlocking {
        val network = MutableChannelNetworkAvailability(initiallyAvailable = true)
        val retryDelayStarted = CompletableDeferred<Unit>()
        val retryDelay = CompletableDeferred<Unit>()
        val delays = mutableListOf<Long>()
        val health = health(
            network = network,
            sleep = { delayMillis ->
                delays += delayMillis
                if (delayMillis > 0L) {
                    retryDelayStarted.complete(Unit)
                    retryDelay.await()
                }
            }
        )
        health.starting()
        health.failure("connection reset")

        val recovery = async { health.awaitReconnect() }
        retryDelayStarted.await()
        network.update(false)
        yield()

        assertFalse(recovery.isCompleted)
        assertEquals(1, ChannelRuntimeDiagnostics.getSnapshot(CHANNEL, ADAPTER_KEY).retryAttempt)

        network.update(true)

        assertTrue(recovery.await())
        assertEquals(listOf(2_000L, 0L), delays)
        assertEquals(1, ChannelRuntimeDiagnostics.getSnapshot(CHANNEL, ADAPTER_KEY).retryAttempt)
    }

    @Test
    fun `configuration and authentication failures block instead of hot looping`() = runBlocking {
        val delays = mutableListOf<Long>()
        val health = health(
            sleep = { delayMillis -> delays += delayMillis }
        )
        health.starting()
        health.blocked(ChannelRuntimeErrorCode.AUTHENTICATION_FAILED)

        assertFalse(health.awaitReconnect())
        assertTrue(delays.isEmpty())
        val snapshot = ChannelRuntimeDiagnostics.getSnapshot(CHANNEL, ADAPTER_KEY)
        assertEquals(ChannelBindingHealthState.BLOCKED, snapshot.state)
        assertEquals("Authentication required", snapshot.lastError)
        assertFalse(snapshot.running)
    }

    @Test
    fun `authentication exception type blocks without retaining its sensitive message`() {
        val health = health()
        health.starting()

        health.failure(
            AuthenticationFailedException(
                "Login rejected for user@example.com with password=secret-value"
            )
        )

        val snapshot = ChannelRuntimeDiagnostics.getSnapshot(CHANNEL, ADAPTER_KEY)
        assertEquals(ChannelBindingHealthState.BLOCKED, snapshot.state)
        assertEquals(ChannelRuntimeErrorCode.AUTHENTICATION_FAILED, snapshot.error?.code)
        assertEquals("Authentication required", snapshot.lastError)
    }

    @Test
    fun `real network success records liveness and clears recovery metadata`() = runBlocking {
        val health = health()
        health.starting()
        health.failure("connection reset")
        health.awaitReconnect()

        health.succeeded(ChannelOperation.INBOUND)

        val snapshot = ChannelRuntimeDiagnostics.getSnapshot(CHANNEL, ADAPTER_KEY)
        assertEquals(ChannelBindingHealthState.READY, snapshot.state)
        assertEquals(ChannelSuccessfulOperation(ChannelOperation.INBOUND, 10_000L), snapshot.lastSuccessfulOperation)
        assertEquals(0, snapshot.retryAttempt)
        assertEquals(null, snapshot.nextRetryAtEpochMillis)
        assertEquals(null, snapshot.error)
    }

    @Test
    fun `authentication success alone remains starting without a transport connection`() {
        val health = health()
        health.starting()

        health.authenticationSucceeded()

        val authenticated = currentSnapshot()
        assertEquals(ChannelBindingHealthState.STARTING, authenticated.state)
        assertFalse(authenticated.ready)
        assertFalse(authenticated.connected)
        assertEquals(
            ChannelSuccessfulOperation(ChannelOperation.AUTHENTICATION, 10_000L),
            authenticated.lastSuccessfulOperation
        )
    }

    @Test
    fun `typed startup failure blocks after authentication success`() {
        val health = health()
        health.starting()
        health.authenticationSucceeded()

        health.failure(NormalizedChannelError(ChannelRuntimeErrorCode.AUTHENTICATION_FAILED))

        val failed = currentSnapshot()
        assertEquals(ChannelBindingHealthState.BLOCKED, failed.state)
        assertFalse(failed.ready)
        assertFalse(failed.connected)
        assertEquals(ChannelRuntimeErrorCode.AUTHENTICATION_FAILED, failed.error?.code)
    }

    @Test
    fun `outbound success does not turn a reconnecting inbound binding ready`() = runBlocking {
        val health = health()
        health.starting()
        health.failure("connection reset")
        health.awaitReconnect()

        health.succeeded(ChannelOperation.OUTBOUND)

        val snapshot = currentSnapshot()
        assertEquals(ChannelBindingHealthState.RECONNECTING, snapshot.state)
        assertFalse(snapshot.connected)
        assertFalse(snapshot.ready)
        assertEquals(ChannelSuccessfulOperation(ChannelOperation.OUTBOUND, 10_000L), snapshot.lastSuccessfulOperation)
        assertEquals(1, snapshot.retryAttempt)
    }

    @Test
    fun `operation warning does not convert a ready binding into reconnecting`() {
        val health = health()
        health.starting()
        health.succeeded(ChannelOperation.AUTHENTICATION)

        health.warning(ChannelOperation.HEARTBEAT, "protocol parse failed")

        val snapshot = currentSnapshot()
        assertEquals(ChannelBindingHealthState.READY, snapshot.state)
        assertEquals(ChannelRuntimeErrorCode.PROTOCOL_ERROR, snapshot.lastOperationWarning?.error?.code)
        assertEquals(ChannelOperation.HEARTBEAT, snapshot.lastOperationWarning?.operation)
    }

    @Test
    fun `inbound protocol warning keeps retry metadata empty`() {
        val health = health()
        health.starting()
        health.succeeded(ChannelOperation.AUTHENTICATION)

        health.warning(ChannelOperation.INBOUND, "invalid json frame")

        val snapshot = currentSnapshot()
        assertEquals(ChannelBindingHealthState.READY, snapshot.state)
        assertEquals(0, snapshot.retryAttempt)
        assertEquals(null, snapshot.nextRetryAtEpochMillis)
        assertEquals(ChannelOperation.INBOUND, snapshot.lastOperationWarning?.operation)
        assertEquals(ChannelRuntimeErrorCode.PROTOCOL_ERROR, snapshot.lastOperationWarning?.error?.code)
    }

    @Test
    fun `late readiness evidence cannot clear an authentication block`() {
        val health = health()
        health.starting()
        health.blocked(ChannelRuntimeErrorCode.AUTHENTICATION_FAILED)

        health.succeeded(ChannelOperation.POLL)

        assertEquals(ChannelBindingHealthState.BLOCKED, currentSnapshot().state)
        assertEquals(ChannelRuntimeErrorCode.AUTHENTICATION_FAILED, currentSnapshot().error?.code)
    }

    @Test
    fun `operation boundary blocks authentication and exposes only a safe exception`() = runBlocking {
        val health = health()
        health.starting()

        val thrown = runCatching {
            health.runOperation(ChannelOperation.OUTBOUND) {
                throw AuthenticationFailedException(
                    "Login rejected for user@example.com with password=secret-value"
                )
            }
        }.exceptionOrNull()

        assertTrue(thrown is ChannelOperationFailedException)
        assertEquals("Authentication required", thrown?.message)
        assertFalse(thrown?.message.orEmpty().contains("user@example.com"))
        assertFalse(thrown?.message.orEmpty().contains("secret-value"))
        assertEquals(ChannelBindingHealthState.BLOCKED, currentSnapshot().state)
    }

    @Test
    fun `operation boundary records transient failure as warning and preserves cancellation`() = runBlocking {
        val health = health()
        health.starting()
        health.succeeded(ChannelOperation.AUTHENTICATION)

        val transient = runCatching {
            health.runOperation(ChannelOperation.OUTBOUND) {
                error("network timeout with credential secret-value")
            }
        }.exceptionOrNull()
        assertTrue(transient is ChannelOperationFailedException)
        assertEquals("Network unavailable", transient?.message)
        assertEquals(ChannelBindingHealthState.READY, currentSnapshot().state)
        assertEquals(ChannelRuntimeErrorCode.NETWORK_UNAVAILABLE, currentSnapshot().lastOperationWarning?.error?.code)

        val cancellation = CancellationException("stop")
        val cancelled = runCatching {
            health.runOperation(ChannelOperation.OUTBOUND) { throw cancellation }
        }.exceptionOrNull()
        assertTrue(cancelled === cancellation)
    }

    private fun health(
        network: ChannelNetworkAvailability = MutableChannelNetworkAvailability(true),
        sleep: suspend (Long) -> Unit = { _ -> }
    ): ChannelAdapterRuntimeHealth = ChannelAdapterRuntimeHealth(
        channel = CHANNEL,
        adapterKey = ADAPTER_KEY,
        networkAvailability = network,
        reconnectPolicy = ChannelReconnectPolicy(),
        nowEpochMillis = { 10_000L },
        sleep = sleep
    )

    private fun currentSnapshot(): ChannelRuntimeSnapshot =
        ChannelRuntimeDiagnostics.getSnapshot(CHANNEL, ADAPTER_KEY)

    private class AuthenticationFailedException(
        message: String
    ) : RuntimeException(message)

    private companion object {
        const val CHANNEL = "test-runtime"
        const val ADAPTER_KEY = "test-adapter"
    }
}
