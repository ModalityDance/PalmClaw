package com.palmclaw.runtime

import android.app.Application
import android.content.Context
import com.palmclaw.bus.OutboundMessage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class RuntimeControllerStatus(
    val running: Boolean = false,
    val gatewayRunning: Boolean = false,
    val activeAdapterCount: Int = 0,
    val lastError: String = "",
    val processingSessionIds: Set<String> = emptySet()
)

object RuntimeController {
    private val _status = MutableStateFlow(RuntimeControllerStatus())
    val status: StateFlow<RuntimeControllerStatus> = _status.asStateFlow()

    @Volatile
    private var runtime: GatewayRuntime? = null

    private fun requireRuntime(context: Context): GatewayRuntime {
        val existing = runtime
        if (existing != null) return existing
        start(context)
        return runtime ?: throw IllegalStateException("Normal-mode runtime is not running")
    }

    fun start(context: Context) {
        val app = context.applicationContext as Application
        if (runtime == null) {
            runtime = GatewayRuntime(
                app = app,
                enableAutomation = true,
                enableMcp = true
            ) { state ->
                _status.update {
                    it.copy(
                        running = true,
                        gatewayRunning = state.gatewayRunning,
                        activeAdapterCount = state.activeAdapterCount,
                        lastError = state.lastError,
                        processingSessionIds = state.processingSessionIds
                    )
                }
            }.also {
                it.start()
                _status.update { status -> status.copy(running = true) }
            }
        } else {
            runtime?.reloadAllFromStoredConfig()
        }
    }

    fun reloadGateway(context: Context) {
        requireRuntime(context).reloadGatewayFromStoredConfig()
    }

    fun reloadAutomation(context: Context) {
        requireRuntime(context).reloadAutomationFromStoredConfig()
    }

    fun reloadMcp(context: Context) {
        requireRuntime(context).reloadMcpFromStoredConfig()
    }

    fun reloadAll(context: Context) {
        requireRuntime(context).reloadAllFromStoredConfig()
    }

    suspend fun publishOutbound(context: Context, outbound: OutboundMessage) {
        requireRuntime(context).deliverOutboundViaOwnedGateway(outbound)
    }

    suspend fun runUserMessage(
        context: Context,
        sessionId: String,
        sessionTitle: String,
        text: String
    ) {
        requireRuntime(context).runUserMessage(
            sessionId = sessionId,
            sessionTitle = sessionTitle,
            text = text
        )
    }

    suspend fun triggerHeartbeatNow(context: Context): String {
        return requireRuntime(context).triggerHeartbeatNow()
    }

    suspend fun processHeartbeatTick(context: Context): String? {
        return requireRuntime(context).processHeartbeatTick()
    }

    suspend fun processDueCronJobs(context: Context, resync: Boolean) {
        requireRuntime(context).processDueCronJobs(resync = resync)
    }

    fun stop() {
        runtime?.shutdownRuntime()
        runtime = null
        _status.value = RuntimeControllerStatus()
    }
}
