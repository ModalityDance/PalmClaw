package com.palmclaw.runtime

import android.content.Context
import androidx.core.content.ContextCompat
import com.palmclaw.bus.MessageAttachment
import com.palmclaw.bus.OutboundMessage
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class AlwaysOnRuntimeStatus(
    val serviceRunning: Boolean = false,
    val notificationActive: Boolean = false,
    val gatewayRunning: Boolean = false,
    val activeAdapterCount: Int = 0,
    val startedAtMs: Long = 0L,
    val lastError: String = "",
    val processingSessionIds: Set<String> = emptySet()
)

object AlwaysOnModeController {
    private val _status = MutableStateFlow(AlwaysOnRuntimeStatus())
    val status: StateFlow<AlwaysOnRuntimeStatus> = _status.asStateFlow()

    @Volatile
    private var runtime: GatewayRuntime? = null

    private suspend fun requireRuntime(): GatewayRuntime {
        runtime?.let { return it }
        repeat(30) {
            delay(100)
            runtime?.let { return it }
        }
        throw IllegalStateException("Always-on service is not running")
    }

    fun startService(context: Context) {
        val intent = AlwaysOnGatewayService.createStartIntent(context.applicationContext)
        ContextCompat.startForegroundService(context.applicationContext, intent)
    }

    fun stopService(context: Context) {
        if (_status.value.serviceRunning) {
            context.applicationContext.startService(
                AlwaysOnGatewayService.createStopIntent(context.applicationContext)
            )
        }
        updateServiceState(running = false, notificationActive = false)
    }

    fun attachRuntime(runtime: GatewayRuntime) {
        this.runtime = runtime
        _status.update {
            it.copy(
                serviceRunning = true,
                notificationActive = true,
                startedAtMs = if (it.startedAtMs > 0L) it.startedAtMs else System.currentTimeMillis()
            )
        }
    }

    fun detachRuntime(runtime: GatewayRuntime? = null) {
        if (runtime != null && this.runtime !== runtime) return
        this.runtime = null
        _status.update {
            AlwaysOnRuntimeStatus(
                serviceRunning = false,
                notificationActive = false,
                gatewayRunning = false,
                activeAdapterCount = 0,
                startedAtMs = 0L,
                lastError = it.lastError,
                processingSessionIds = emptySet()
            )
        }
    }

    suspend fun publishOutbound(outbound: OutboundMessage) {
        val current = requireRuntime()
        current.deliverOutboundViaOwnedGateway(outbound)
    }

    suspend fun runUserMessage(
        sessionId: String,
        sessionTitle: String,
        text: String,
        attachments: List<MessageAttachment> = emptyList()
    ) {
        val current = requireRuntime()
        current.runUserMessage(
            sessionId = sessionId,
            sessionTitle = sessionTitle,
            text = text,
            attachments = attachments
        )
    }

    suspend fun triggerHeartbeatNow(): String {
        val current = requireRuntime()
        return current.triggerHeartbeatNow()
    }

    suspend fun processHeartbeatTick(): String? {
        val current = requireRuntime()
        return current.processHeartbeatTick()
    }

    suspend fun processDueCronJobs(resync: Boolean) {
        val current = requireRuntime()
        current.processDueCronJobs(resync = resync)
    }

    fun reloadGateway() {
        runtime?.reloadGatewayFromStoredConfig()
    }

    fun reloadAutomation() {
        runtime?.reloadAutomationFromStoredConfig()
    }

    fun reloadMcp() {
        runtime?.reloadMcpFromStoredConfig()
    }

    fun reloadAll() {
        runtime?.reloadAllFromStoredConfig()
    }

    fun updateRuntimeState(
        gatewayRunning: Boolean,
        activeAdapterCount: Int,
        lastError: String = "",
        processingSessionIds: Set<String> = emptySet()
    ) {
        _status.update {
            it.copy(
                serviceRunning = true,
                notificationActive = true,
                gatewayRunning = gatewayRunning,
                activeAdapterCount = activeAdapterCount,
                lastError = lastError,
                processingSessionIds = processingSessionIds
            )
        }
    }

    fun updateServiceState(
        running: Boolean,
        notificationActive: Boolean,
        lastError: String = ""
    ) {
        _status.update {
            if (!running) {
                AlwaysOnRuntimeStatus(
                    serviceRunning = false,
                    notificationActive = false,
                    gatewayRunning = false,
                    activeAdapterCount = 0,
                    startedAtMs = 0L,
                    lastError = lastError.ifBlank { it.lastError },
                    processingSessionIds = emptySet()
                )
            } else {
                it.copy(
                    serviceRunning = true,
                    notificationActive = notificationActive,
                    startedAtMs = if (it.startedAtMs > 0L) it.startedAtMs else System.currentTimeMillis(),
                    lastError = lastError.ifBlank { it.lastError }
                )
            }
        }
    }
}
