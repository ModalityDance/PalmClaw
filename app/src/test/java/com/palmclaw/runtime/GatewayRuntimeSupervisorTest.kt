package com.palmclaw.runtime

import android.app.Application
import android.content.Context
import com.palmclaw.bus.OutboundMessage
import com.palmclaw.runtime.alwayson.AlwaysOnShellRegistry
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
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
    fun `callers share one in-flight runtime start`() {
        val startGate = CountDownLatch(1)
        val factory = FakeGatewayRuntimeFactory(startGate = startGate)
        GatewayRuntimeSupervisor.installFactoryForTest(factory)
        val app = TestApplication()

        GatewayRuntimeSupervisor.ensureStarted(app)
        assertTrue(factory.awaitCreated())
        val created = factory.created.single()
        GatewayRuntimeSupervisor.ensureStarted(app)

        startGate.countDown()
        runBlocking { GatewayRuntimeSupervisor.awaitIdleForTest() }

        assertSame(created, GatewayRuntimeSupervisor.currentRuntimeForTest())
        assertEquals(1, factory.created.size)
        assertEquals(1, created.startCount)
    }

    @Test
    fun `process shutdown wins over a runtime start that completes late`() {
        val startGate = CountDownLatch(1)
        val factory = FakeGatewayRuntimeFactory(startGate = startGate)
        GatewayRuntimeSupervisor.installFactoryForTest(factory)
        val app = TestApplication()

        GatewayRuntimeSupervisor.ensureStarted(app)
        assertTrue(factory.awaitCreated())
        val created = factory.created.single()

        GatewayRuntimeSupervisor.shutdownForProcessExit()
        startGate.countDown()

        assertTrue(created.awaitShutdown())
        assertEquals(null, GatewayRuntimeSupervisor.currentRuntimeForTest())
        assertEquals(false, GatewayRuntimeSupervisor.status.value.running)
    }

    @Test
    fun `cancelled reload does not become a runtime error`() = runBlocking {
        val factory = FakeGatewayRuntimeFactory(
            reloadGatewayFailure = CancellationException("cancelled")
        )
        GatewayRuntimeSupervisor.installFactoryForTest(factory)
        val app = TestApplication()

        GatewayRuntimeSupervisor.ensureStarted(app)
        GatewayRuntimeSupervisor.awaitIdleForTest()
        GatewayRuntimeSupervisor.reloadGateway(app)
        GatewayRuntimeSupervisor.awaitIdleForTest()

        assertEquals("", GatewayRuntimeSupervisor.status.value.lastError)
    }

    @Test
    fun `starting the shared runtime does not open an inbound gateway`() = runBlocking {
        val factory = FakeGatewayRuntimeFactory()
        GatewayRuntimeSupervisor.installFactoryForTest(factory)
        val app = TestApplication()

        GatewayRuntimeSupervisor.ensureStarted(app)
        GatewayRuntimeSupervisor.awaitIdleForTest()

        val runtime = factory.created.single()
        assertEquals(1, runtime.startCount)
        assertEquals(0, runtime.reloadGatewayCount)
        assertEquals(false, GatewayRuntimeSupervisor.status.value.gatewayRunning)
    }

    @Test
    fun `operations are forwarded to the same runtime instance`() = runBlocking {
        val factory = FakeGatewayRuntimeFactory()
        GatewayRuntimeSupervisor.installFactoryForTest(factory)
        val app = TestApplication()

        GatewayRuntimeSupervisor.ensureStarted(app)
        GatewayRuntimeSupervisor.awaitIdleForTest()
        GatewayRuntimeSupervisor.acquireGateway(app, GatewayRuntimeOwner.NORMAL_PROCESS)
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
        GatewayRuntimeSupervisor.releaseGateway(GatewayRuntimeOwner.NORMAL_PROCESS)

        val runtime = factory.created.single()
        assertEquals(2, runtime.reloadGatewayCount)
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
    fun `cold cron execution releases its temporary inbound gateway`() = runBlocking {
        val factory = FakeGatewayRuntimeFactory()
        GatewayRuntimeSupervisor.installFactoryForTest(factory)
        val app = TestApplication()

        GatewayRuntimeSupervisor.processDueCronJobs(app, resync = true)

        val runtime = factory.created.single()
        assertEquals(1, runtime.processCronCount)
        assertEquals(1, runtime.reloadGatewayCount)
        assertEquals(1, runtime.stopGatewayCount)
        assertEquals(false, GatewayRuntimeSupervisor.status.value.gatewayRunning)
    }

    @Test
    fun `automation release preserves an existing foreground gateway`() = runBlocking {
        val factory = FakeGatewayRuntimeFactory()
        GatewayRuntimeSupervisor.installFactoryForTest(factory)
        val app = TestApplication()

        GatewayRuntimeSupervisor.acquireGateway(app, GatewayRuntimeOwner.NORMAL_PROCESS)
        GatewayRuntimeSupervisor.processHeartbeatTick(app)

        val runtime = factory.created.single()
        assertEquals(1, runtime.processHeartbeatCount)
        assertEquals(0, runtime.stopGatewayCount)
        assertEquals(true, GatewayRuntimeSupervisor.status.value.gatewayRunning)

        GatewayRuntimeSupervisor.releaseGateway(GatewayRuntimeOwner.NORMAL_PROCESS)

        assertEquals(1, runtime.stopGatewayCount)
        assertEquals(false, GatewayRuntimeSupervisor.status.value.gatewayRunning)
    }

    @Test
    fun `overlapping automation scopes keep the gateway until both finish`() = runBlocking {
        val entered = CountDownLatch(2)
        val gate = CountDownLatch(1)
        val factory = FakeGatewayRuntimeFactory(
            automationEntered = entered,
            automationGate = gate
        )
        GatewayRuntimeSupervisor.installFactoryForTest(factory)
        val app = TestApplication()

        val operations = listOf(
            async(Dispatchers.Default) {
                GatewayRuntimeSupervisor.processHeartbeatTick(app)
            },
            async(Dispatchers.Default) {
                GatewayRuntimeSupervisor.processDueCronJobs(app, resync = false)
            }
        )
        assertTrue(entered.await(2, TimeUnit.SECONDS))
        gate.countDown()
        operations.awaitAll()

        val runtime = factory.created.single()
        assertEquals(1, runtime.stopGatewayCount)
        assertEquals(false, GatewayRuntimeSupervisor.status.value.gatewayRunning)
    }

    @Test
    fun `automation cancellation propagates after gateway ownership is released`() = runBlocking {
        val cancellation = CancellationException("cancelled automation")
        val factory = FakeGatewayRuntimeFactory(processHeartbeatFailure = cancellation)
        GatewayRuntimeSupervisor.installFactoryForTest(factory)
        val app = TestApplication()

        val failure = runCatching {
            GatewayRuntimeSupervisor.processHeartbeatTick(app)
        }.exceptionOrNull()

        assertSame(cancellation, failure)
        assertEquals(1, factory.created.single().stopGatewayCount)
        assertEquals(false, GatewayRuntimeSupervisor.status.value.gatewayRunning)
    }

    @Test
    fun `failed scoped automation stop is consumed so retry owns a fresh lease`() = runBlocking {
        val factory = FakeGatewayRuntimeFactory(stopGatewayFailures = 1)
        GatewayRuntimeSupervisor.installFactoryForTest(factory)
        val app = TestApplication()

        val firstFailure = runCatching {
            GatewayRuntimeSupervisor.processDueCronJobs(app, resync = false)
        }.exceptionOrNull()

        val runtime = factory.created.single()
        assertTrue(firstFailure is IllegalStateException)
        assertEquals(1, runtime.processCronCount)
        assertEquals(1, runtime.reloadGatewayCount)
        assertEquals(1, runtime.stopGatewayCount)

        GatewayRuntimeSupervisor.reloadGateway(app)
        GatewayRuntimeSupervisor.reloadAll(app)
        GatewayRuntimeSupervisor.awaitIdleForTest()
        assertEquals(1, runtime.reloadGatewayCount)
        assertEquals(0, runtime.reloadAllCount)

        GatewayRuntimeSupervisor.processDueCronJobs(app, resync = true)

        assertEquals(2, runtime.processCronCount)
        assertEquals(2, runtime.reloadGatewayCount)
        assertEquals(2, runtime.stopGatewayCount)
        assertEquals(false, GatewayRuntimeSupervisor.status.value.gatewayRunning)

        GatewayRuntimeSupervisor.reloadGateway(app)
        GatewayRuntimeSupervisor.reloadAll(app)
        GatewayRuntimeSupervisor.awaitIdleForTest()
        assertEquals(2, runtime.reloadGatewayCount)
        assertEquals(0, runtime.reloadAllCount)
        assertEquals(false, GatewayRuntimeSupervisor.status.value.gatewayRunning)
    }

    @Test
    fun `late reload after foreground release cannot reopen inbound channels`() = runBlocking {
        val factory = FakeGatewayRuntimeFactory()
        GatewayRuntimeSupervisor.installFactoryForTest(factory)
        val app = TestApplication()

        GatewayRuntimeSupervisor.acquireGateway(app, GatewayRuntimeOwner.NORMAL_PROCESS)
        GatewayRuntimeSupervisor.releaseGateway(GatewayRuntimeOwner.NORMAL_PROCESS)
        GatewayRuntimeSupervisor.reloadGateway(app)
        GatewayRuntimeSupervisor.reloadAll(app)
        GatewayRuntimeSupervisor.awaitIdleForTest()

        val runtime = factory.created.single()
        assertEquals(1, runtime.reloadGatewayCount)
        assertEquals(0, runtime.reloadAllCount)
        assertEquals(1, runtime.reloadAutomationCount)
        assertEquals(1, runtime.reloadMcpCount)
        assertEquals(false, GatewayRuntimeSupervisor.status.value.gatewayRunning)
    }

    @Test
    fun `releasing always-on ownership preserves the normal process gateway`() = runBlocking {
        val factory = FakeGatewayRuntimeFactory()
        GatewayRuntimeSupervisor.installFactoryForTest(factory)
        val app = TestApplication()

        GatewayRuntimeSupervisor.acquireGateway(app, GatewayRuntimeOwner.NORMAL_PROCESS)
        GatewayRuntimeSupervisor.acquireGateway(app, GatewayRuntimeOwner.ALWAYS_ON)
        GatewayRuntimeSupervisor.releaseGateway(GatewayRuntimeOwner.ALWAYS_ON)

        val runtime = factory.created.single()
        assertEquals(0, runtime.stopGatewayCount)
        assertEquals(0, runtime.shutdownCount)
        assertEquals(true, GatewayRuntimeSupervisor.status.value.running)
        assertEquals(true, GatewayRuntimeSupervisor.status.value.gatewayRunning)

        GatewayRuntimeSupervisor.releaseGateway(GatewayRuntimeOwner.NORMAL_PROCESS)

        assertEquals(1, runtime.stopGatewayCount)
        assertEquals(0, runtime.shutdownCount)
        assertEquals(true, GatewayRuntimeSupervisor.status.value.running)
        assertEquals(false, GatewayRuntimeSupervisor.status.value.gatewayRunning)
    }

    @Test
    fun `releasing an ownership that was never acquired does not stop an unowned runtime`() = runBlocking {
        val factory = FakeGatewayRuntimeFactory()
        GatewayRuntimeSupervisor.installFactoryForTest(factory)
        val app = TestApplication()

        GatewayRuntimeSupervisor.ensureStarted(app)
        GatewayRuntimeSupervisor.awaitIdleForTest()
        GatewayRuntimeSupervisor.releaseGateway(GatewayRuntimeOwner.ALWAYS_ON)

        assertEquals(0, factory.created.single().stopGatewayCount)
        assertEquals(false, GatewayRuntimeSupervisor.status.value.gatewayRunning)
    }

    @Test
    fun `failed persistent ownership release can be retried`() = runBlocking {
        listOf(
            GatewayRuntimeOwner.NORMAL_PROCESS,
            GatewayRuntimeOwner.ALWAYS_ON
        ).forEach { owner ->
            val factory = FakeGatewayRuntimeFactory(stopGatewayFailures = 1)
            GatewayRuntimeSupervisor.installFactoryForTest(factory)
            val app = TestApplication()

            GatewayRuntimeSupervisor.acquireGateway(app, owner)
            val firstFailure = runCatching {
                GatewayRuntimeSupervisor.releaseGateway(owner)
            }.exceptionOrNull()

            assertTrue(firstFailure is IllegalStateException)
            assertEquals(1, factory.created.single().stopGatewayCount)

            GatewayRuntimeSupervisor.releaseGateway(owner)

            assertEquals(2, factory.created.single().stopGatewayCount)
            assertEquals(false, GatewayRuntimeSupervisor.status.value.gatewayRunning)
        }
    }

    @Test
    fun `a new owner reasserts gateway config after a deferred stop`() = runBlocking {
        val factory = FakeGatewayRuntimeFactory(deferGatewayStop = true)
        GatewayRuntimeSupervisor.installFactoryForTest(factory)
        val app = TestApplication()

        GatewayRuntimeSupervisor.acquireGateway(app, GatewayRuntimeOwner.ALWAYS_ON)
        GatewayRuntimeSupervisor.releaseGateway(GatewayRuntimeOwner.ALWAYS_ON)

        val runtime = factory.created.single()
        assertEquals(true, GatewayRuntimeSupervisor.status.value.gatewayRunning)
        assertEquals(1, runtime.stopGatewayCount)

        GatewayRuntimeSupervisor.acquireGateway(app, GatewayRuntimeOwner.NORMAL_PROCESS)

        assertEquals(2, runtime.reloadGatewayCount)
        assertEquals(true, GatewayRuntimeSupervisor.status.value.gatewayRunning)
    }

    @Test
    fun `service shell stop leaves supervisor runtime running`() {
        val factory = FakeGatewayRuntimeFactory()
        GatewayRuntimeSupervisor.installFactoryForTest(factory)
        val app = TestApplication()

        GatewayRuntimeSupervisor.ensureStarted(app)
        runBlocking { GatewayRuntimeSupervisor.awaitIdleForTest() }
        AlwaysOnShellRegistry.markRunning()
        AlwaysOnShellRegistry.expectStop()
        AlwaysOnShellRegistry.markStopped()

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
        constructor(
            startGate: CountDownLatch? = null,
            stopGatewayFailures: Int = 0,
            deferGatewayStop: Boolean = false,
            reloadGatewayFailure: Throwable? = null,
            processHeartbeatFailure: Throwable? = null,
            automationEntered: CountDownLatch? = null,
            automationGate: CountDownLatch? = null
        ) {
            this.startGate = startGate
            this.stopGatewayFailures = stopGatewayFailures
            this.deferGatewayStop = deferGatewayStop
            this.reloadGatewayFailure = reloadGatewayFailure
            this.processHeartbeatFailure = processHeartbeatFailure
            this.automationEntered = automationEntered
            this.automationGate = automationGate
        }

        private val startGate: CountDownLatch?
        private val stopGatewayFailures: Int
        private val deferGatewayStop: Boolean
        private val reloadGatewayFailure: Throwable?
        private val processHeartbeatFailure: Throwable?
        private val automationEntered: CountDownLatch?
        private val automationGate: CountDownLatch?
        private val createdSignal = CountDownLatch(1)
        val created: MutableList<FakeGatewayRuntimeHandle> = Collections.synchronizedList(mutableListOf())

        fun awaitCreated(): Boolean = createdSignal.await(2, TimeUnit.SECONDS)

        override fun create(
            app: Application,
            onStateChanged: (GatewayRuntimeState) -> Unit
        ): GatewayRuntimeHandle {
            return FakeGatewayRuntimeHandle(
                onStateChanged = onStateChanged,
                startGate = startGate,
                stopGatewayFailures = stopGatewayFailures,
                deferGatewayStop = deferGatewayStop,
                reloadGatewayFailure = reloadGatewayFailure,
                processHeartbeatFailure = processHeartbeatFailure,
                automationEntered = automationEntered,
                automationGate = automationGate
            ).also { handle ->
                created += handle
                createdSignal.countDown()
            }
        }
    }

    private class FakeGatewayRuntimeHandle(
        private val onStateChanged: (GatewayRuntimeState) -> Unit,
        private val startGate: CountDownLatch? = null,
        stopGatewayFailures: Int = 0,
        private val deferGatewayStop: Boolean = false,
        private val reloadGatewayFailure: Throwable? = null,
        private val processHeartbeatFailure: Throwable? = null,
        private val automationEntered: CountDownLatch? = null,
        private val automationGate: CountDownLatch? = null
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
        private val stopGateways = AtomicInteger()
        private val shutdowns = AtomicInteger()
        private val remainingStopGatewayFailures = AtomicInteger(stopGatewayFailures)
        private val shutdownSignal = CountDownLatch(1)

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
        val stopGatewayCount: Int get() = stopGateways.get()
        val shutdownCount: Int get() = shutdowns.get()
        fun awaitShutdown(): Boolean = shutdownSignal.await(2, TimeUnit.SECONDS)


        override fun start() {
            startGate?.await(2, TimeUnit.SECONDS)
            starts.incrementAndGet()
            onStateChanged(GatewayRuntimeState(gatewayRunning = false))
        }

        override fun reloadGatewayFromStoredConfig() {
            reloadGateways.incrementAndGet()
            reloadGatewayFailure?.let { throw it }
            onStateChanged(GatewayRuntimeState(gatewayRunning = true, activeAdapterCount = 1))
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
            processHeartbeatFailure?.let { throw it }
            automationEntered?.countDown()
            automationGate?.await(2, TimeUnit.SECONDS)
            return "processed"
        }

        override suspend fun processDueCronJobs(resync: Boolean) {
            processCrons.incrementAndGet()
            automationEntered?.countDown()
            automationGate?.await(2, TimeUnit.SECONDS)
        }

        override fun stopGateway() {
            stopGateways.incrementAndGet()
            if (remainingStopGatewayFailures.getAndDecrement() > 0) {
                throw IllegalStateException("simulated gateway stop failure")
            }
            if (!deferGatewayStop) {
                onStateChanged(GatewayRuntimeState(gatewayRunning = false))
            }
        }

        override fun shutdownRuntime() {
            shutdowns.incrementAndGet()
            shutdownSignal.countDown()
        }
    }

    private class TestApplication : Application() {
        override fun getApplicationContext(): Context = this
    }
}
