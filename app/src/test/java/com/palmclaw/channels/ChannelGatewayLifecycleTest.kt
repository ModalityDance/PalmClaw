package com.palmclaw.channels

import com.palmclaw.bus.InboundMessage
import com.palmclaw.bus.OutboundMessage
import com.palmclaw.config.SessionChannelBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ChannelGatewayLifecycleTest {
    @Test
    fun `disabled apply stops current gateway and clears error`() {
        val fixture = fixture(listOf(FakeAdapter("adapter:one")))
        fixture.lifecycle.apply(enabled = true, bindings = listOf(validBinding()))

        val result = fixture.lifecycle.apply(enabled = false, bindings = listOf(validBinding()))

        assertEquals(ChannelGatewayLifecycleSnapshot(), result)
        assertEquals(1, fixture.created.single().stopCount)
        assertEquals(result, fixture.snapshots.last())
    }

    @Test
    fun `enabled apply with no adapters reports existing compatibility error`() {
        val fixture = fixture(emptyList())

        val result = fixture.lifecycle.apply(enabled = true, bindings = listOf(validBinding()))

        assertFalse(result.running)
        assertEquals(0, result.adapterCount)
        assertEquals(NO_ACTIVE_ADAPTER_ERROR, result.lastError)
        assertTrue(fixture.created.isEmpty())
    }

    @Test
    fun `enabled apply with no configured binding stops without error`() {
        val fixture = fixture(emptyList())

        val result = fixture.lifecycle.apply(enabled = true, bindings = emptyList())

        assertEquals(ChannelGatewayLifecycleSnapshot(), result)
    }

    @Test
    fun `first enabled apply creates and starts one gateway`() {
        val adapters = listOf(FakeAdapter("adapter:one"), FakeAdapter("adapter:two"))
        val fixture = fixture(adapters)

        val result = fixture.lifecycle.apply(enabled = true, bindings = listOf(validBinding()))

        assertTrue(result.running)
        assertEquals(2, result.adapterCount)
        assertEquals("", result.lastError)
        assertEquals(1, fixture.created.size)
        assertEquals(1, fixture.created.single().startCount)
        assertEquals(adapters, fixture.created.single().initialAdapters)
    }

    @Test
    fun `subsequent enabled apply reconfigures existing gateway`() {
        val firstAdapters = listOf(FakeAdapter("adapter:one"))
        val secondAdapters = listOf(FakeAdapter("adapter:two"), FakeAdapter("adapter:three"))
        val fixture = fixture(firstAdapters)
        fixture.lifecycle.apply(enabled = true, bindings = listOf(validBinding()))
        fixture.adapterFactory.resultProvider = { secondAdapters }

        val result = fixture.lifecycle.apply(enabled = true, bindings = listOf(validBinding()))

        assertEquals(1, fixture.created.size)
        assertEquals(listOf(secondAdapters), fixture.created.single().reconfigurations)
        assertEquals(2, result.adapterCount)
        assertTrue(result.running)
    }

    @Test
    fun `stop is idempotent and clears owned gateway`() = runBlocking {
        val fixture = fixture(listOf(FakeAdapter("adapter:one")))
        fixture.lifecycle.apply(enabled = true, bindings = listOf(validBinding()))

        fixture.lifecycle.stop()
        fixture.lifecycle.stop()

        assertEquals(1, fixture.created.single().stopCount)
        val failure = runCatching {
            fixture.lifecycle.deliverOutbound(outbound())
        }.exceptionOrNull()
        assertEquals(GATEWAY_NOT_RUNNING_ERROR, failure?.message)
    }

    @Test
    fun `delivery without gateway keeps existing exception text`() = runBlocking {
        val fixture = fixture(emptyList())

        val failure = runCatching {
            fixture.lifecycle.deliverOutbound(outbound())
        }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        assertEquals(GATEWAY_NOT_RUNNING_ERROR, failure?.message)
        assertTrue(fixture.snapshots.isEmpty())
    }

    @Test
    fun `successful delivery publishes running snapshot with cleared error`() = runBlocking {
        val fixture = fixture(listOf(FakeAdapter("adapter:one")))
        fixture.lifecycle.apply(enabled = true, bindings = listOf(validBinding()))
        val orchestrator = fixture.created.single()
        val expectedFailure = IllegalStateException("delivery failed")
        orchestrator.deliveryFailure = expectedFailure
        runCatching { fixture.lifecycle.deliverOutbound(outbound()) }
        orchestrator.deliveryFailure = null

        fixture.lifecycle.deliverOutbound(outbound())

        assertEquals(1, orchestrator.delivered.size)
        assertEquals(ChannelGatewayLifecycleSnapshot(true, 1, ""), fixture.snapshots.last())
    }

    @Test
    fun `failed delivery publishes error and rethrows`() = runBlocking {
        val fixture = fixture(listOf(FakeAdapter("adapter:one")))
        fixture.lifecycle.apply(enabled = true, bindings = listOf(validBinding()))
        val expected = IllegalArgumentException("send failed")
        fixture.created.single().deliveryFailure = expected

        val actual = runCatching {
            fixture.lifecycle.deliverOutbound(outbound())
        }.exceptionOrNull()

        assertSame(expected, actual)
        assertEquals(ChannelGatewayLifecycleSnapshot(true, 1, "send failed"), fixture.snapshots.last())
    }

    @Test
    fun `attachment capability delegates without changing state`() {
        val fixture = fixture(listOf(FakeAdapter("adapter:one")))
        fixture.lifecycle.apply(enabled = true, bindings = listOf(validBinding()))
        val capability = ChannelAttachmentCapability(
            supportsInboundFiles = true,
            supportsOutboundFiles = true,
            requiresAuthenticatedDownload = false
        )
        fixture.created.single().capability = capability
        val snapshotCount = fixture.snapshots.size

        val result = fixture.lifecycle.resolveOutboundAttachmentCapability(outbound())

        assertEquals(capability, result)
        assertEquals(snapshotCount, fixture.snapshots.size)
        fixture.lifecycle.stop()
        assertNull(fixture.lifecycle.resolveOutboundAttachmentCapability(outbound()))
    }

    private fun fixture(initialAdapters: List<ChannelAdapter>): Fixture {
        val adapterFactory = RecordingAdapterFactory { initialAdapters }
        val created = mutableListOf<FakeOrchestrator>()
        val snapshots = mutableListOf<ChannelGatewayLifecycleSnapshot>()
        val lifecycle = ChannelGatewayLifecycle(
            adapterFactory = adapterFactory,
            orchestratorFactory = GatewayOrchestratorFactory { adapters ->
                FakeOrchestrator(adapters).also(created::add)
            },
            onStateChanged = snapshots::add
        )
        return Fixture(lifecycle, adapterFactory, created, snapshots)
    }

    private fun validBinding() = SessionChannelBinding(
        sessionId = "session",
        channel = "telegram",
        chatId = "100",
        telegramBotToken = "token"
    )

    private fun outbound() = OutboundMessage(
        channel = "telegram",
        chatId = "100",
        content = "message"
    )

    private data class Fixture(
        val lifecycle: ChannelGatewayLifecycle,
        val adapterFactory: RecordingAdapterFactory,
        val created: MutableList<FakeOrchestrator>,
        val snapshots: MutableList<ChannelGatewayLifecycleSnapshot>
    )

    private class RecordingAdapterFactory(
        var resultProvider: () -> List<ChannelAdapter>
    ) : ChannelAdapterFactory {
        override fun create(bindings: List<SessionChannelBinding>): List<ChannelAdapter> =
            resultProvider()
    }

    private class FakeOrchestrator(
        val initialAdapters: List<ChannelAdapter>
    ) : GatewayOrchestratorControl {
        override var adapterCount: Int = initialAdapters.size
        var startCount = 0
        var stopCount = 0
        val reconfigurations = mutableListOf<List<ChannelAdapter>>()
        val delivered = mutableListOf<OutboundMessage>()
        var deliveryFailure: Throwable? = null
        var capability: ChannelAttachmentCapability? = null

        override fun start() {
            startCount += 1
        }

        override fun reconfigure(adapters: List<ChannelAdapter>) {
            reconfigurations += adapters
            adapterCount = adapters.size
        }

        override fun stop() {
            stopCount += 1
        }

        override suspend fun deliverOutboundNow(outbound: OutboundMessage) {
            deliveryFailure?.let { throw it }
            delivered += outbound
        }

        override fun resolveOutboundAttachmentCapability(
            outbound: OutboundMessage
        ): ChannelAttachmentCapability? = capability
    }

    private class FakeAdapter(
        override val adapterKey: String
    ) : ChannelAdapter {
        override val channelName: String = "telegram"

        override fun start(
            scope: CoroutineScope,
            publishInbound: suspend (InboundMessage) -> Unit
        ) = Unit

        override fun canHandleOutbound(message: OutboundMessage): Boolean = true

        override suspend fun send(message: OutboundMessage) = Unit

        override fun stop() = Unit
    }

    private companion object {
        const val NO_ACTIVE_ADAPTER_ERROR =
            "No active adapter could start. Check credentials and target IDs."
        const val GATEWAY_NOT_RUNNING_ERROR =
            "Gateway is not running; cannot deliver outbound message"
    }
}
