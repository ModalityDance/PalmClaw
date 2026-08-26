package com.palmclaw.runtime.alwayson

import com.palmclaw.channels.ChannelAdapterIdentity
import com.palmclaw.channels.ChannelBindingHealthState
import com.palmclaw.channels.ChannelBindingRuntimeProjector
import com.palmclaw.channels.ChannelRuntimeSnapshot
import com.palmclaw.channels.ChannelRuntimeSnapshotSource
import com.palmclaw.config.SessionChannelBinding
import com.palmclaw.runtime.RuntimeControllerStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class GatewayAvailabilityAdapterTest {
    @Test
    fun `snapshot counts each shared adapter once using real binding health`() = runBlocking {
        val telegramA = telegramBinding("session-a", "shared-token")
        val telegramB = telegramBinding("session-b", "shared-token")
        val slack = SessionChannelBinding(
            sessionId = "session-c",
            channel = "slack",
            chatId = "C12345678",
            slackBotToken = "bot-token",
            slackAppToken = "app-token"
        )
        val discord = SessionChannelBinding(
            sessionId = "session-d",
            channel = "discord",
            chatId = "123456789012345678",
            discordBotToken = "discord-token"
        )
        val snapshots = mapOf(
            identity(telegramA) to ChannelRuntimeSnapshot(
                running = true,
                connected = true,
                ready = true,
                state = ChannelBindingHealthState.READY
            ),
            identity(slack) to ChannelRuntimeSnapshot(
                running = true,
                state = ChannelBindingHealthState.RECONNECTING,
                retryAttempt = 2
            ),
            identity(discord) to ChannelRuntimeSnapshot(
                running = true,
                state = ChannelBindingHealthState.BLOCKED
            )
        )
        val runtime = FakeAlwaysOnGatewayRuntimePort(
            RuntimeControllerStatus(running = true, gatewayRunning = true)
        )
        val adapter = GatewayAvailabilityAdapter(
            bindingsProvider = { listOf(telegramA, telegramB, slack, discord) },
            bindingProjector = projector(),
            snapshotSource = ChannelRuntimeSnapshotSource { channel, adapterKey ->
                snapshots[channel to adapterKey] ?: ChannelRuntimeSnapshot()
            },
            runtime = runtime
        )

        assertEquals(
            GatewayAvailabilitySnapshot(
                runtime = AlwaysOnRuntimeState.RUNNING,
                gateway = AlwaysOnGatewayState.RUNNING,
                channels = AlwaysOnChannelCounts(
                    configured = 3,
                    ready = 1,
                    reconnecting = 1,
                    blocked = 1
                )
            ),
            adapter.snapshot()
        )
    }

    @Test
    fun `ensure and release delegate only always-on gateway ownership`() = runBlocking {
        val binding = telegramBinding("session-a", "token")
        val runtime = FakeAlwaysOnGatewayRuntimePort(RuntimeControllerStatus())
        val adapter = GatewayAvailabilityAdapter(
            bindingsProvider = { listOf(binding) },
            bindingProjector = projector(),
            snapshotSource = ChannelRuntimeSnapshotSource { _, _ -> ChannelRuntimeSnapshot() },
            runtime = runtime
        )

        assertEquals(GatewayEnsureResult.Available, adapter.ensureAvailable())
        assertEquals(
            GatewayReleaseResult.Released,
            adapter.releaseOwnership()
        )

        assertEquals(1, runtime.acquireCalls)
        assertEquals(1, runtime.releaseCalls)
        assertEquals(true, runtime.status.value.running)
        assertEquals(false, runtime.status.value.gatewayRunning)
    }

    @Test
    fun `ensure refuses to start without a valid configured adapter`() = runBlocking {
        val runtime = FakeAlwaysOnGatewayRuntimePort(RuntimeControllerStatus())
        val adapter = GatewayAvailabilityAdapter(
            bindingsProvider = {
                listOf(SessionChannelBinding(sessionId = "session-a", channel = "telegram"))
            },
            bindingProjector = projector(),
            snapshotSource = ChannelRuntimeSnapshotSource { _, _ -> ChannelRuntimeSnapshot() },
            runtime = runtime
        )

        assertEquals(
            GatewayEnsureResult.Blocked("Configure at least one remote channel"),
            adapter.ensureAvailable()
        )
        assertEquals(0, runtime.acquireCalls)
    }

    @Test
    fun `ensure preserves coroutine cancellation`() = runBlocking {
        val cancellation = CancellationException("cancelled")
        val runtime = FakeAlwaysOnGatewayRuntimePort(
            initial = RuntimeControllerStatus(),
            acquireFailure = cancellation
        )
        val adapter = GatewayAvailabilityAdapter(
            bindingsProvider = { listOf(telegramBinding("session-a", "token")) },
            bindingProjector = projector(),
            snapshotSource = ChannelRuntimeSnapshotSource { _, _ -> ChannelRuntimeSnapshot() },
            runtime = runtime
        )

        val failure = runCatching {
            adapter.ensureAvailable()
        }.exceptionOrNull()

        assertSame(cancellation, failure)
    }

    @Test
    fun `release failure returns a stable safe summary`() = runBlocking {
        val runtime = FakeAlwaysOnGatewayRuntimePort(
            initial = RuntimeControllerStatus(
                running = true,
                gatewayRunning = true
            ),
            releaseFailure = IllegalStateException("secret provider detail")
        )
        val adapter = GatewayAvailabilityAdapter(
            bindingsProvider = { emptyList() },
            bindingProjector = projector(),
            snapshotSource = ChannelRuntimeSnapshotSource { _, _ -> ChannelRuntimeSnapshot() },
            runtime = runtime
        )

        assertEquals(
            GatewayReleaseResult.Failed("Gateway ownership could not be released"),
            adapter.releaseOwnership()
        )
    }

    private fun telegramBinding(sessionId: String, token: String): SessionChannelBinding =
        SessionChannelBinding(
            sessionId = sessionId,
            channel = "telegram",
            chatId = "123",
            telegramBotToken = token
        )

    private fun identity(binding: SessionChannelBinding): Pair<String, String> =
        binding.channel to checkNotNull(ChannelAdapterIdentity.primaryKeyForBinding(binding))

    private fun projector(): ChannelBindingRuntimeProjector =
        ChannelBindingRuntimeProjector { value -> value.contains('@') }

    private class FakeAlwaysOnGatewayRuntimePort(
        initial: RuntimeControllerStatus,
        private val acquireFailure: Throwable? = null,
        private val releaseFailure: Throwable? = null
    ) : AlwaysOnGatewayRuntimePort {
        override val status = MutableStateFlow(initial)
        var acquireCalls = 0
        var releaseCalls = 0

        override suspend fun acquireOwnership() {
            acquireCalls += 1
            acquireFailure?.let { throw it }
            status.value = status.value.copy(
                running = true,
                gatewayRunning = true,
                activeAdapterCount = 1
            )
        }

        override suspend fun releaseOwnership() {
            releaseCalls += 1
            releaseFailure?.let { throw it }
            status.value = status.value.copy(
                gatewayRunning = false,
                activeAdapterCount = 0
            )
        }
    }
}
