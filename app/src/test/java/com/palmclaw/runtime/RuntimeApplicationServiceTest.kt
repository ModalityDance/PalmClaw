package com.palmclaw.runtime

import com.palmclaw.bus.MessageAttachment
import com.palmclaw.bus.OutboundMessage
import com.palmclaw.config.AlwaysOnConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeApplicationServiceTest {

    @Test
    fun `startGatewayIfEnabled switches to always-on chain when enabled`() {
        val configGateway = FakeRuntimeModeConfigGateway(AlwaysOnConfig(enabled = true))
        val normal = FakeNormalRuntimeGateway()
        val alwaysOn = FakeAlwaysOnRuntimeGateway()
        val health = FakeAlwaysOnHealthCheckScheduler()
        val service = RuntimeApplicationService(
            appProvider = { throw IllegalStateException("unused in fake test") },
            modeConfigGateway = configGateway,
            normalRuntimeGateway = normal,
            alwaysOnRuntimeGateway = alwaysOn,
            healthCheckScheduler = health
        )

        service.startGatewayIfEnabled()

        assertEquals(1, health.ensureScheduledCount)
        assertEquals(1, normal.stopCount)
        assertEquals(1, alwaysOn.startServiceCount)
        assertEquals(1, alwaysOn.reloadAllCount)
        assertEquals(0, normal.startCount)
    }

    @Test
    fun `applyAlwaysOnConfig disables always-on and reloads normal runtime`() {
        val configGateway = FakeRuntimeModeConfigGateway(AlwaysOnConfig(enabled = true))
        val normal = FakeNormalRuntimeGateway()
        val alwaysOn = FakeAlwaysOnRuntimeGateway()
        val health = FakeAlwaysOnHealthCheckScheduler()
        val service = RuntimeApplicationService(
            appProvider = { throw IllegalStateException("unused in fake test") },
            modeConfigGateway = configGateway,
            normalRuntimeGateway = normal,
            alwaysOnRuntimeGateway = alwaysOn,
            healthCheckScheduler = health
        )

        service.applyAlwaysOnConfig(AlwaysOnConfig(enabled = false, keepScreenAwake = true))

        assertEquals(false, configGateway.configState.enabled)
        assertEquals(true, configGateway.configState.keepScreenAwake)
        assertEquals(1, health.cancelCount)
        assertEquals(1, alwaysOn.stopServiceCount)
        assertEquals(1, normal.startCount)
        assertEquals(1, normal.reloadAllCount)
    }

    @Test
    fun `refreshGatewayRuntimeConfig reloads gateway when normal runtime already running`() {
        val configGateway = FakeRuntimeModeConfigGateway(AlwaysOnConfig(enabled = false))
        val normal = FakeNormalRuntimeGateway().apply {
            statusFlow.value = RuntimeControllerStatus(running = true)
        }
        val alwaysOn = FakeAlwaysOnRuntimeGateway()
        val service = RuntimeApplicationService(
            appProvider = { throw IllegalStateException("unused in fake test") },
            modeConfigGateway = configGateway,
            normalRuntimeGateway = normal,
            alwaysOnRuntimeGateway = alwaysOn
        )

        service.refreshGatewayRuntimeConfig()

        assertEquals(1, alwaysOn.stopServiceCount)
        assertEquals(1, normal.reloadGatewayCount)
        assertEquals(0, normal.startCount)
    }

    @Test
    fun `refreshGatewayRuntimeConfig starts always on service when always on is enabled`() {
        val configGateway = FakeRuntimeModeConfigGateway(AlwaysOnConfig(enabled = true))
        val normal = FakeNormalRuntimeGateway()
        val alwaysOn = FakeAlwaysOnRuntimeGateway()
        val service = RuntimeApplicationService(
            appProvider = { throw IllegalStateException("unused in fake test") },
            modeConfigGateway = configGateway,
            normalRuntimeGateway = normal,
            alwaysOnRuntimeGateway = alwaysOn
        )

        service.refreshGatewayRuntimeConfig()

        assertEquals(1, normal.stopCount)
        assertEquals(1, alwaysOn.startServiceCount)
        assertEquals(1, alwaysOn.reloadGatewayCount)
        assertEquals(0, normal.reloadGatewayCount)
    }

    @Test
    fun `publishOutbound and runUserMessage route by mode`() = runBlocking {
        val configGateway = FakeRuntimeModeConfigGateway(AlwaysOnConfig(enabled = false))
        val normal = FakeNormalRuntimeGateway()
        val alwaysOn = FakeAlwaysOnRuntimeGateway()
        val service = RuntimeApplicationService(
            appProvider = { throw IllegalStateException("unused in fake test") },
            modeConfigGateway = configGateway,
            normalRuntimeGateway = normal,
            alwaysOnRuntimeGateway = alwaysOn
        )

        service.publishOutbound(
            OutboundMessage(
                channel = "telegram",
                chatId = "1",
                content = "hello"
            )
        )
        service.runUserMessage("session:1", "Session", "normal")

        configGateway.configState = AlwaysOnConfig(enabled = true)
        service.publishOutbound(
            OutboundMessage(
                channel = "telegram",
                chatId = "1",
                content = "always-on"
            )
        )
        service.runUserMessage("session:2", "Session 2", "always")

        assertEquals(1, normal.publishOutboundCount)
        assertEquals(1, normal.runUserMessageCount)
        assertEquals(1, alwaysOn.publishOutboundCount)
        assertEquals(1, alwaysOn.runUserMessageCount)
        assertTrue(normal.lastMessageText == "normal")
        assertTrue(alwaysOn.lastMessageText == "always")
    }

    private class FakeRuntimeModeConfigGateway(
        var configState: AlwaysOnConfig
    ) : RuntimeModeConfigGateway {
        override fun getAlwaysOnConfig(): AlwaysOnConfig = configState

        override fun saveAlwaysOnConfig(config: AlwaysOnConfig) {
            configState = config
        }
    }

    private class FakeNormalRuntimeGateway : NormalRuntimeGateway {
        val statusFlow = MutableStateFlow(RuntimeControllerStatus())
        override val status: StateFlow<RuntimeControllerStatus>
            get() = statusFlow

        var startCount = 0
        var stopCount = 0
        var reloadGatewayCount = 0
        var reloadAutomationCount = 0
        var reloadMcpCount = 0
        var reloadAllCount = 0
        var publishOutboundCount = 0
        var runUserMessageCount = 0
        var triggerHeartbeatCount = 0
        var lastMessageText: String = ""

        override fun start() {
            startCount += 1
            statusFlow.value = statusFlow.value.copy(running = true)
        }

        override fun stop() {
            stopCount += 1
            statusFlow.value = RuntimeControllerStatus()
        }

        override fun reloadGateway() {
            reloadGatewayCount += 1
        }

        override fun reloadAutomation() {
            reloadAutomationCount += 1
        }

        override fun reloadMcp() {
            reloadMcpCount += 1
        }

        override fun reloadAll() {
            reloadAllCount += 1
        }

        override suspend fun publishOutbound(outbound: OutboundMessage) {
            publishOutboundCount += 1
        }

        override suspend fun runUserMessage(
            sessionId: String,
            sessionTitle: String,
            text: String,
            attachments: List<MessageAttachment>
        ) {
            runUserMessageCount += 1
            lastMessageText = text
        }

        override suspend fun triggerHeartbeatNow(): String {
            triggerHeartbeatCount += 1
            return "normal-heartbeat"
        }
    }

    private class FakeAlwaysOnRuntimeGateway : AlwaysOnRuntimeGateway {
        val statusFlow = MutableStateFlow(AlwaysOnRuntimeStatus())
        override val status: StateFlow<AlwaysOnRuntimeStatus>
            get() = statusFlow

        var startServiceCount = 0
        var stopServiceCount = 0
        var reloadGatewayCount = 0
        var reloadAutomationCount = 0
        var reloadMcpCount = 0
        var reloadAllCount = 0
        var publishOutboundCount = 0
        var runUserMessageCount = 0
        var triggerHeartbeatCount = 0
        var lastMessageText: String = ""

        override fun startService() {
            startServiceCount += 1
        }

        override fun stopService() {
            stopServiceCount += 1
        }

        override fun reloadGateway() {
            reloadGatewayCount += 1
        }

        override fun reloadAutomation() {
            reloadAutomationCount += 1
        }

        override fun reloadMcp() {
            reloadMcpCount += 1
        }

        override fun reloadAll() {
            reloadAllCount += 1
        }

        override suspend fun publishOutbound(outbound: OutboundMessage) {
            publishOutboundCount += 1
        }

        override suspend fun runUserMessage(
            sessionId: String,
            sessionTitle: String,
            text: String,
            attachments: List<MessageAttachment>
        ) {
            runUserMessageCount += 1
            lastMessageText = text
        }

        override suspend fun triggerHeartbeatNow(): String {
            triggerHeartbeatCount += 1
            return "always-heartbeat"
        }
    }

    private class FakeAlwaysOnHealthCheckScheduler : AlwaysOnHealthCheckScheduler {
        var ensureScheduledCount = 0
        var cancelCount = 0

        override fun ensureScheduled() {
            ensureScheduledCount += 1
        }

        override fun cancel() {
            cancelCount += 1
        }
    }
}
