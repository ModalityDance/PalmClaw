package com.palmclaw.runtime

import android.app.Application
import android.content.Context
import com.palmclaw.bus.OutboundMessage
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.system.measureTimeMillis

class GatewayRuntimeSupervisorTest {
    @After
    fun tearDown() {
        GatewayRuntimeSupervisor.resetForTest()
    }

    @Test
    fun `ensureStarted creates only one runtime for repeated callers`() {
        val factory = FakeGatewayRuntimeFactory()
        GatewayRuntimeSupervisor.installFactoryForTest(factory)
        val app = TestApplication()

        GatewayRuntimeSupervisor.ensureStarted(app)
        runBlocking { GatewayRuntimeSupervisor.awaitIdleForTest() }
        val first = GatewayRuntimeSupervisor.currentRuntimeForTest()
        GatewayRuntimeSupervisor.ensureStarted(app)
        runBlocking { GatewayRuntimeSupervisor.awaitIdleForTest() }
        val second = GatewayRuntimeSupervisor.currentRuntimeForTest()

        assertSame(first, second)
        assertEquals(1, factory.created.size)
        assertEquals(1, factory.created.single().startCount)
    }

    @Test
    fun `operations are forwarded to the same runtime instance`() = runBlocking {
        val factory = FakeGatewayRuntimeFactory()
        GatewayRuntimeSupervisor.installFactoryForTest(factory)
        val app = TestApplication()

        GatewayRuntimeSupervisor.ensureStarted(app)
        GatewayRuntimeSupervisor.awaitIdleForTest()
        GatewayRuntimeSupervisor.reloadGateway(app)
        GatewayRuntimeSupervisor.reloadAutomation(app)
        GatewayRuntimeSupervisor.reloadMcp(app)
        GatewayRuntimeSupervisor.reloadAll(app)
        GatewayRuntimeSupervisor.awaitIdleForTest()
        GatewayRuntimeSupervisor.publishOutbound(
            app,
            OutboundMessage(channel = "telegram", chatId = "1", content = "hello")
        )
        GatewayRuntimeSupervisor.runUserMessage(app, "session:1", "Session", "hello")
        GatewayRuntimeSupervisor.triggerHeartbeatNow(app)
        GatewayRuntimeSupervisor.processHeartbeatTick(app)
        GatewayRuntimeSupervisor.processDueCronJobs(app, resync = true)
        GatewayRuntimeSupervisor.awaitIdleForTest()

        val runtime = factory.created.single()
        assertEquals(1, runtime.reloadGatewayCount)
        assertEquals(1, runtime.reloadAutomationCount)
        assertEquals(1, runtime.reloadMcpCount)
        assertEquals(1, runtime.reloadAllCount)
        assertEquals(1, runtime.publishOutboundCount)
        assertEquals(1, runtime.runUserMessageCount)
        assertEquals(1, runtime.triggerHeartbeatCount)
        assertEquals(1, runtime.processHeartbeatCount)
        assertEquals(1, runtime.processCronCount)
        assertEquals(1, factory.created.size)
    }

    @Test
    fun `service shell stop leaves supervisor runtime running`() {
        val factory = FakeGatewayRuntimeFactory()
        GatewayRuntimeSupervisor.installFactoryForTest(factory)
        val app = TestApplication()

        GatewayRuntimeSupervisor.ensureStarted(app)
        runBlocking { GatewayRuntimeSupervisor.awaitIdleForTest() }
        AlwaysOnModeController.updateServiceState(running = false, notificationActive = false)

        assertEquals(1, factory.created.size)
        assertEquals(0, factory.created.single().shutdownCount)
        assertEquals(true, GatewayRuntimeSupervisor.status.value.running)
    }

    @Test
    fun `ensureStarted returns before runtime start completes`() {
        val startGate = CountDownLatch(1)
        val factory = FakeGatewayRuntimeFactory(startGate = startGate)
        GatewayRuntimeSupervisor.installFactoryForTest(factory)
        val app = TestApplication()

        val elapsedMs = measureTimeMillis {
            GatewayRuntimeSupervisor.ensureStarted(app)
        }

        assertTrue("ensureStarted should not block caller thread", elapsedMs < 200L)
        assertEquals(null, GatewayRuntimeSupervisor.currentRuntimeForTest())

        startGate.countDown()
        runBlocking { GatewayRuntimeSupervisor.awaitIdleForTest() }

        assertEquals(1, factory.created.size)
        assertEquals(1, factory.created.single().startCount)
    }

    private class FakeGatewayRuntimeFactory : GatewayRuntimeFactory {
        constructor(startGate: CountDownLatch? = null) {
            this.startGate = startGate
        }

        private val startGate: CountDownLatch?
        val created: MutableList<FakeGatewayRuntimeHandle> = Collections.synchronizedList(mutableListOf())

        override fun create(
            app: Application,
            onStateChanged: (GatewayRuntimeState) -> Unit
        ): GatewayRuntimeHandle {
            return FakeGatewayRuntimeHandle(onStateChanged, startGate).also(created::add)
        }
    }

    private class FakeGatewayRuntimeHandle(
        private val onStateChanged: (GatewayRuntimeState) -> Unit,
        private val startGate: CountDownLatch? = null
    ) : GatewayRuntimeHandle {
        private val starts = AtomicInteger()
        private val reloadGateways = AtomicInteger()
        private val reloadAutomations = AtomicInteger()
        private val reloadMcps = AtomicInteger()
        private val reloadAlls = AtomicInteger()
        private val publishOutbounds = AtomicInteger()
        private val runUserMessages = AtomicInteger()
        private val triggerHeartbeats = AtomicInteger()
        private val processHeartbeats = AtomicInteger()
        private val processCrons = AtomicInteger()
        private val shutdowns = AtomicInteger()

        val startCount: Int get() = starts.get()
        val reloadGatewayCount: Int get() = reloadGateways.get()
        val reloadAutomationCount: Int get() = reloadAutomations.get()
        val reloadMcpCount: Int get() = reloadMcps.get()
        val reloadAllCount: Int get() = reloadAlls.get()
        val publishOutboundCount: Int get() = publishOutbounds.get()
        val runUserMessageCount: Int get() = runUserMessages.get()
        val triggerHeartbeatCount: Int get() = triggerHeartbeats.get()
        val processHeartbeatCount: Int get() = processHeartbeats.get()
        val processCronCount: Int get() = processCrons.get()
        val shutdownCount: Int get() = shutdowns.get()

        override fun start() {
            startGate?.await(2, TimeUnit.SECONDS)
            starts.incrementAndGet()
            onStateChanged(GatewayRuntimeState(gatewayRunning = true, activeAdapterCount = 1))
        }

        override fun reloadGatewayFromStoredConfig() {
            reloadGateways.incrementAndGet()
        }

        override fun reloadAutomationFromStoredConfig() {
            reloadAutomations.incrementAndGet()
        }

        override fun reloadMcpFromStoredConfig() {
            reloadMcps.incrementAndGet()
        }

        override fun reloadAllFromStoredConfig() {
            reloadAlls.incrementAndGet()
        }

        override suspend fun deliverOutboundViaOwnedGateway(outbound: OutboundMessage) {
            publishOutbounds.incrementAndGet()
        }

        override suspend fun runUserMessage(
            sessionId: String,
            sessionTitle: String,
            text: String,
            attachments: List<com.palmclaw.bus.MessageAttachment>
        ) {
            runUserMessages.incrementAndGet()
        }

        override suspend fun triggerHeartbeatNow(): String {
            triggerHeartbeats.incrementAndGet()
            return "heartbeat"
        }

        override suspend fun processHeartbeatTick(): String? {
            processHeartbeats.incrementAndGet()
            return "processed"
        }

        override suspend fun processDueCronJobs(resync: Boolean) {
            processCrons.incrementAndGet()
        }

        override fun shutdownRuntime() {
            shutdowns.incrementAndGet()
        }
    }

    private class TestApplication : Application() {
        override fun getApplicationContext(): Context = this
    }
}
