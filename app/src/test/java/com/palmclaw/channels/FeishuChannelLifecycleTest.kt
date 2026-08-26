package com.palmclaw.channels

import com.palmclaw.bus.InboundMessage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class FeishuChannelLifecycleTest {
    @Test
    fun `authenticated client remains starting until an SDK inbound callback`() = runBlocking<Unit> {
        val adapterKey = "feishu-auth-only"
        val factory = FakeClientFactory()
        val adapter = adapter(
            adapterKey = adapterKey,
            probe = FeishuAuthenticationProbe {
                FeishuAuthenticationProbeResult.Authenticated
            },
            factory = factory
        )

        adapter.start(this) { _: InboundMessage -> }
        factory.started.await()
        awaitCondition {
            runtimeSnapshot(adapterKey).lastSuccessfulOperation?.operation ==
                ChannelOperation.AUTHENTICATION
        }

        runtimeSnapshot(adapterKey).also { snapshot ->
            assertEquals(ChannelBindingHealthState.STARTING, snapshot.state)
            assertFalse(snapshot.ready)
            assertFalse(snapshot.connected)
        }
        FeishuGatewayDiagnostics.getSnapshot(adapterKey).also { snapshot ->
            assertFalse(snapshot.ready)
            assertFalse(snapshot.connected)
        }

        adapter.stop()
    }

    @Test
    fun `SDK inbound callback establishes readiness before routing`() = runBlocking<Unit> {
        val adapterKey = "feishu-inbound-ready"
        val factory = FakeClientFactory()
        val adapter = adapter(
            adapterKey = adapterKey,
            probe = FeishuAuthenticationProbe {
                FeishuAuthenticationProbeResult.Authenticated
            },
            factory = factory
        )

        adapter.start(this) { _: InboundMessage -> }
        factory.started.await()
        awaitCondition {
            runtimeSnapshot(adapterKey).lastSuccessfulOperation?.operation ==
                ChannelOperation.AUTHENTICATION
        }

        factory.signalInbound()

        runtimeSnapshot(adapterKey).also { snapshot ->
            assertEquals(ChannelBindingHealthState.READY, snapshot.state)
            assertTrue(snapshot.ready)
            assertTrue(snapshot.connected)
            assertEquals(ChannelOperation.INBOUND, snapshot.lastSuccessfulOperation?.operation)
        }
        FeishuGatewayDiagnostics.getSnapshot(adapterKey).also { snapshot ->
            assertTrue(snapshot.ready)
            assertTrue(snapshot.connected)
        }

        adapter.stop()
    }

    @Test
    fun `stop during authentication probe never creates or starts a client`() = runBlocking<Unit> {
        val adapterKey = "feishu-stop-probe"
        val probeEntered = CompletableDeferred<Unit>()
        val probeRelease = CompletableDeferred<Unit>()
        val factory = FakeClientFactory()
        val adapter = adapter(
            adapterKey = adapterKey,
            probe = FeishuAuthenticationProbe {
                probeEntered.complete(Unit)
                probeRelease.await()
                FeishuAuthenticationProbeResult.Authenticated
            },
            factory = factory
        )

        adapter.start(this) { _: InboundMessage -> }
        probeEntered.await()
        adapter.stop()
        probeRelease.complete(Unit)
        yield()

        assertEquals(0, factory.createdCount.get())
        assertEquals(0, factory.startedCount.get())
        assertEquals(0, factory.closedCount.get())
        runtimeSnapshot(adapterKey).also { snapshot ->
            assertEquals(ChannelBindingHealthState.STOPPED, snapshot.state)
            assertFalse(snapshot.ready)
            assertFalse(snapshot.connected)
        }
    }

    @Test
    fun `stop closes the owned client once and stale callback cannot revive readiness`() = runBlocking<Unit> {
        val adapterKey = "feishu-stop-client"
        val factory = FakeClientFactory()
        val adapter = adapter(
            adapterKey = adapterKey,
            probe = FeishuAuthenticationProbe {
                FeishuAuthenticationProbeResult.Authenticated
            },
            factory = factory
        )

        adapter.start(this) { _: InboundMessage -> }
        factory.started.await()
        adapter.stop()
        factory.signalInbound()
        yield()

        assertEquals(1, factory.closedCount.get())
        runtimeSnapshot(adapterKey).also { snapshot ->
            assertEquals(ChannelBindingHealthState.STOPPED, snapshot.state)
            assertFalse(snapshot.ready)
            assertFalse(snapshot.connected)
        }
    }

    @Test
    fun `stop can close a client while SDK start is blocked`() = runBlocking<Unit> {
        val adapterKey = "feishu-blocking-start"
        val factory = BlockingClientFactory()
        val adapter = adapter(
            adapterKey = adapterKey,
            probe = FeishuAuthenticationProbe {
                FeishuAuthenticationProbeResult.Authenticated
            },
            factory = factory
        )

        adapter.start(this) { _: InboundMessage -> }
        factory.startEntered.await()

        val stop = async(Dispatchers.Default) { adapter.stop() }
        withTimeout(1_000L) { stop.await() }
        withTimeout(1_000L) { factory.startReturned.await() }
        awaitCondition {
            runtimeSnapshot(adapterKey).state == ChannelBindingHealthState.STOPPED
        }

        assertEquals(1, factory.startedCount.get())
        assertTrue(factory.closedCount.get() >= 1)
        runtimeSnapshot(adapterKey).also { snapshot ->
            assertEquals(ChannelBindingHealthState.STOPPED, snapshot.state)
            assertFalse(snapshot.ready)
            assertFalse(snapshot.connected)
        }
    }

    @Test
    fun `late exit from an old instance cannot stop a ready replacement`() = runBlocking<Unit> {
        val adapterKey = "feishu-replacement"
        val oldFactory = BlockingClientFactory(releaseOnClose = false)
        val oldAdapter = adapter(
            adapterKey = adapterKey,
            probe = FeishuAuthenticationProbe {
                FeishuAuthenticationProbeResult.Authenticated
            },
            factory = oldFactory
        )
        val oldParent = Job()
        val oldScope = CoroutineScope(Dispatchers.IO + oldParent)

        oldAdapter.start(oldScope) { _: InboundMessage -> }
        oldFactory.startEntered.await()
        val oldWorker = oldParent.children.single()
        oldAdapter.stop()

        val replacementFactory = FakeClientFactory()
        val replacement = adapter(
            adapterKey = adapterKey,
            probe = FeishuAuthenticationProbe {
                FeishuAuthenticationProbeResult.Authenticated
            },
            factory = replacementFactory
        )
        replacement.start(this) { _: InboundMessage -> }
        replacementFactory.started.await()
        replacementFactory.signalInbound()
        awaitCondition {
            runtimeSnapshot(adapterKey).state == ChannelBindingHealthState.READY
        }

        oldFactory.releaseStart()
        oldWorker.join()

        runtimeSnapshot(adapterKey).also { snapshot ->
            assertEquals(ChannelBindingHealthState.READY, snapshot.state)
            assertTrue(snapshot.ready)
            assertTrue(snapshot.connected)
        }

        replacement.stop()
        oldParent.cancel()
    }

    private fun adapter(
        adapterKey: String,
        probe: FeishuAuthenticationProbe,
        factory: FeishuLongConnectionClientFactory
    ): FeishuChannelAdapter = FeishuChannelAdapter(
        adapterKey = adapterKey,
        appId = "app-id",
        appSecret = "app-secret",
        authenticationProbe = probe,
        clientFactory = factory
    )

    private fun runtimeSnapshot(adapterKey: String): ChannelRuntimeSnapshot =
        ChannelRuntimeDiagnostics.getSnapshot("feishu", adapterKey)

    private suspend fun awaitCondition(condition: () -> Boolean) {
        repeat(100) {
            if (condition()) return
            yield()
        }
        assertTrue("Condition was not reached", condition())
    }

    private class BlockingClientFactory(
        private val releaseOnClose: Boolean = true
    ) : FeishuLongConnectionClientFactory {
        val startedCount = AtomicInteger()
        val closedCount = AtomicInteger()
        val startEntered = CompletableDeferred<Unit>()
        val startReturned = CompletableDeferred<Unit>()
        private val startRelease = CountDownLatch(1)

        override fun create(
            callbacks: FeishuLongConnectionCallbacks
        ): FeishuLongConnectionClient = object : FeishuLongConnectionClient {
            override fun start() {
                startedCount.incrementAndGet()
                startEntered.complete(Unit)
                try {
                    startRelease.await(5, TimeUnit.SECONDS)
                } finally {
                    startReturned.complete(Unit)
                }
            }

            override fun close() {
                closedCount.incrementAndGet()
                if (releaseOnClose) releaseStart()
            }
        }

        fun releaseStart() {
            startRelease.countDown()
        }
    }

    private class FakeClientFactory : FeishuLongConnectionClientFactory {
        val createdCount = AtomicInteger()
        val startedCount = AtomicInteger()
        val closedCount = AtomicInteger()
        val started = CompletableDeferred<Unit>()
        private var callbacks: FeishuLongConnectionCallbacks? = null

        override fun create(
            callbacks: FeishuLongConnectionCallbacks
        ): FeishuLongConnectionClient {
            createdCount.incrementAndGet()
            this.callbacks = callbacks
            return object : FeishuLongConnectionClient {
                override fun start() {
                    startedCount.incrementAndGet()
                    started.complete(Unit)
                }

                override fun close() {
                    closedCount.incrementAndGet()
                }
            }
        }

        fun signalInbound() {
            callbacks?.onInboundSignal?.invoke()
        }
    }
}
