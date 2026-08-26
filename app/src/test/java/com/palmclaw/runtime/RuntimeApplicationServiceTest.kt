package com.palmclaw.runtime

import com.palmclaw.bus.MessageAttachment
import com.palmclaw.bus.OutboundMessage
import com.palmclaw.config.AlwaysOnConfig
import com.palmclaw.runtime.alwayson.AlwaysOnControl
import com.palmclaw.runtime.alwayson.AlwaysOnPhase
import com.palmclaw.runtime.alwayson.AlwaysOnStatus
import com.palmclaw.runtime.alwayson.AlwaysOnTrigger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class RuntimeApplicationServiceTest {

    @Test
    fun `startup refresh does not claim foreground gateway ownership`() = runBlocking {
        val events = mutableListOf<String>()
        val service = createService(events = events)

        service.startGatewayIfEnabled()

        assertEquals(
            listOf(
                "always:reconcile:APP_FOREGROUND",
                "normal:reload_all"
            ),
            events
        )
    }

    @Test
    fun `foreground lifecycle releases and reacquires normal gateway ownership`() = runBlocking {
        val events = mutableListOf<String>()
        val service = createService(events = events)

        service.onAppForegrounded()
        service.onAppBackgrounded()
        service.onAppForegrounded()

        assertEquals(
            listOf(
                "normal:acquire_gateway",
                "always:reconcile:APP_FOREGROUND",
                "normal:reload_all",
                "normal:release_gateway",
                "normal:acquire_gateway",
                "always:reconcile:APP_FOREGROUND",
                "normal:reload_all"
            ),
            events
        )
    }

    @Test
    fun `reconcile failure rolls back a partially entered foreground lease`() = runBlocking {
        val events = mutableListOf<String>()
        val expected = IllegalStateException("reconcile failed")
        val service = createService(
            events = events,
            alwaysOn = FakeAlwaysOnControl(
                events = events,
                reconcileFailure = expected
            )
        )

        val actual = runCatching {
            service.onAppForegrounded()
        }.exceptionOrNull()

        assertSame(expected, actual)
        assertEquals(
            listOf(
                "normal:acquire_gateway",
                "always:reconcile:APP_FOREGROUND",
                "normal:release_gateway"
            ),
            events
        )
    }

    @Test
    fun `reload failure preserves the original error and suppresses rollback failure`() = runBlocking {
        val events = mutableListOf<String>()
        val reloadFailure = IllegalStateException("reload failed")
        val releaseFailure = IllegalStateException("release failed")
        val service = createService(
            events = events,
            normal = FakeNormalRuntimeGateway(
                events = events,
                releaseGatewayFailure = releaseFailure,
                reloadAllFailure = reloadFailure
            )
        )

        val actual = runCatching {
            service.onAppForegrounded()
        }.exceptionOrNull()

        assertSame(reloadFailure, actual)
        val suppressed = actual?.suppressed.orEmpty()
        assertEquals(1, suppressed.size)
        assertEquals(releaseFailure.javaClass, suppressed.single().javaClass)
        assertEquals(releaseFailure.message, suppressed.single().message)
        assertEquals(
            listOf(
                "normal:acquire_gateway",
                "always:reconcile:APP_FOREGROUND",
                "normal:reload_all",
                "normal:release_gateway"
            ),
            events
        )
    }

    @Test
    fun `cancelled foreground entry rolls back through a non cancellable release`() = runBlocking {
        val events = mutableListOf<String>()
        val reconcileStarted = CompletableDeferred<Unit>()
        val keepReconciling = CompletableDeferred<Unit>()
        val service = createService(
            events = events,
            normal = FakeNormalRuntimeGateway(
                events = events,
                releaseChecksCancellation = true
            ),
            alwaysOn = FakeAlwaysOnControl(
                events = events,
                reconcileAction = {
                    reconcileStarted.complete(Unit)
                    keepReconciling.await()
                }
            )
        )
        val entering = launch {
            service.onAppForegrounded()
        }
        reconcileStarted.await()

        entering.cancelAndJoin()

        assertEquals(
            listOf(
                "normal:acquire_gateway",
                "always:reconcile:APP_FOREGROUND",
                "normal:release_gateway"
            ),
            events
        )
    }

    @Test
    fun `applying enabled config saves the complete config before coordinator and reload`() = runBlocking {
        val events = mutableListOf<String>()
        val config = FakeRuntimeModeConfigGateway(
            configState = AlwaysOnConfig(enabled = false),
            events = events
        )
        val service = createService(events = events, config = config)

        service.applyAlwaysOnConfig(
            AlwaysOnConfig(enabled = true, keepScreenAwake = true)
        )

        assertEquals(
            listOf(
                "config:save:true:true",
                "always:set:true",
                "normal:reload_all"
            ),
            events
        )
        assertEquals(AlwaysOnConfig(enabled = true, keepScreenAwake = true), config.configState)
    }

    @Test
    fun `applying disabled config keeps the normal runtime available`() = runBlocking {
        val events = mutableListOf<String>()
        val config = FakeRuntimeModeConfigGateway(
            configState = AlwaysOnConfig(enabled = true, keepScreenAwake = true),
            events = events
        )
        val service = createService(events = events, config = config)
        service.onAppForegrounded()
        events.clear()


        service.applyAlwaysOnConfig(
            AlwaysOnConfig(enabled = false, keepScreenAwake = true)
        )

        assertEquals(
            listOf(
                "config:save:false:true",
                "always:set:false",
                "normal:reload_all"
            ),
            events
        )
        service.onAppBackgrounded()
        assertEquals("normal:release_gateway", events.last())
        assertEquals(AlwaysOnConfig(enabled = false, keepScreenAwake = true), config.configState)
    }

    @Test
    fun `gateway config refresh reconciles gateway state before reload`() = runBlocking {
        val events = mutableListOf<String>()
        val service = createService(events = events)

        service.refreshGatewayRuntimeConfig()

        assertEquals(
            listOf(
                "always:reconcile:GATEWAY_STATE_CHANGED",
                "normal:reload_gateway"
            ),
            events
        )
    }

    @Test
    fun `tool config refresh reconciles foreground state before reload`() = runBlocking {
        val events = mutableListOf<String>()
        val service = createService(events = events)

        service.refreshToolRuntimeConfig()

        assertEquals(
            listOf(
                "always:reconcile:APP_FOREGROUND",
                "normal:reload_all"
            ),
            events
        )
    }

    @Test
    fun `always-on status is the coordinator status flow`() {
        val events = mutableListOf<String>()
        val alwaysOn = FakeAlwaysOnControl(events).apply {
            statusFlow.value = AlwaysOnStatus(
                desired = true,
                phase = AlwaysOnPhase.ONLINE
            )
        }
        val service = createService(events = events, alwaysOn = alwaysOn)

        assertEquals(alwaysOn.status, service.alwaysOnStatus)
        assertEquals(alwaysOn.status.value, service.currentAlwaysOnStatus())
    }

    @Test
    fun `publish and user messages always use the single normal runtime`() = runBlocking {
        val events = mutableListOf<String>()
        val normal = FakeNormalRuntimeGateway(events)
        val service = createService(events = events, normal = normal)

        service.publishOutbound(
            OutboundMessage(
                channel = "telegram",
                chatId = "1",
                content = "hello"
            )
        )
        service.runUserMessage("session:1", "Session", "normal")

        assertEquals(1, normal.publishOutboundCount)
        assertEquals(1, normal.runUserMessageCount)
        assertEquals("normal", normal.lastMessageText)
    }

    @Test
    fun `heartbeat always uses the single normal runtime`() = runBlocking {
        val events = mutableListOf<String>()
        val normal = FakeNormalRuntimeGateway(events)
        val service = createService(events = events, normal = normal)

        val result = service.triggerHeartbeatNow()

        assertEquals("normal-heartbeat", result)
        assertEquals(1, normal.triggerHeartbeatCount)
    }

    private fun createService(
        events: MutableList<String>,
        config: FakeRuntimeModeConfigGateway = FakeRuntimeModeConfigGateway(
            configState = AlwaysOnConfig(enabled = true),
            events = events
        ),
        normal: FakeNormalRuntimeGateway = FakeNormalRuntimeGateway(events),
        alwaysOn: FakeAlwaysOnControl = FakeAlwaysOnControl(events)
    ): RuntimeApplicationService {
        return RuntimeApplicationService(
            appProvider = { throw IllegalStateException("unused in fake test") },
            modeConfigGateway = config,
            alwaysOnControl = alwaysOn,
            normalRuntimeGateway = normal
        )
    }

    private class FakeRuntimeModeConfigGateway(
        var configState: AlwaysOnConfig,
        private val events: MutableList<String>
    ) : RuntimeModeConfigGateway {
        override fun getAlwaysOnConfig(): AlwaysOnConfig = configState

        override fun saveAlwaysOnConfig(config: AlwaysOnConfig) {
            events += "config:save:${config.enabled}:${config.keepScreenAwake}"
            configState = config
        }
    }

    private class FakeAlwaysOnControl(
        private val events: MutableList<String>,
        private val reconcileFailure: Throwable? = null,
        private val reconcileAction: suspend () -> Unit = {}
    ) : AlwaysOnControl {
        val statusFlow = MutableStateFlow(AlwaysOnStatus())
        override val status: StateFlow<AlwaysOnStatus>
            get() = statusFlow

        override suspend fun setEnabled(enabled: Boolean) {
            events += "always:set:$enabled"
            statusFlow.value = statusFlow.value.copy(desired = enabled)
        }

        override suspend fun reconcile(trigger: AlwaysOnTrigger) {
            events += "always:reconcile:${trigger.name}"
            reconcileAction()
            reconcileFailure?.let { throw it }
        }
    }

    private class FakeNormalRuntimeGateway(
        private val events: MutableList<String>,
        private val releaseGatewayFailure: Throwable? = null,
        private val reloadAllFailure: Throwable? = null,
        private val releaseChecksCancellation: Boolean = false
    ) : NormalRuntimeGateway {
        private val statusFlow = MutableStateFlow(RuntimeControllerStatus())
        override val status: StateFlow<RuntimeControllerStatus>
            get() = statusFlow

        var publishOutboundCount = 0
        var runUserMessageCount = 0
        var triggerHeartbeatCount = 0
        var lastMessageText = ""

        override suspend fun acquireGatewayOwnership() {
            events += "normal:acquire_gateway"
        }

        override suspend fun releaseGatewayOwnership() {
            if (releaseChecksCancellation) {
                yield()
            }
            events += "normal:release_gateway"
            releaseGatewayFailure?.let { throw it }
        }

        override fun reloadGateway() {
            events += "normal:reload_gateway"
        }

        override fun reloadAutomation() {
            events += "normal:reload_automation"
        }

        override fun reloadMcp() {
            events += "normal:reload_mcp"
        }

        override fun reloadAll() {
            events += "normal:reload_all"
            reloadAllFailure?.let { throw it }
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
}
