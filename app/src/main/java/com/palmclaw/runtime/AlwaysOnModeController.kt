package com.palmclaw.runtime

import android.content.Context
import androidx.core.content.ContextCompat
import com.palmclaw.bus.MessageAttachment
import com.palmclaw.bus.OutboundMessage
import java.util.Locale
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

    private suspend fun requireRuntime(): GatewayRuntimeHandle {
        GatewayRuntimeSupervisor.currentRuntimeOrNull()?.let { return it }
        repeat(30) {
            delay(100)
            GatewayRuntimeSupervisor.currentRuntimeOrNull()?.let { return it }
        }
        throw IllegalStateException("Gateway runtime is not running")
    }

    fun startService(context: Context): Boolean {
        val intent = AlwaysOnGatewayService.createStartIntent(context.applicationContext)
        return runCatching {
            ContextCompat.startForegroundService(context.applicationContext, intent)
            true
        }.getOrElse { t ->
            if (!AlwaysOnForegroundServiceStartPolicy.isForegroundServiceStartDenied(t)) {
                throw t
            }
            updateServiceState(
                running = false,
                notificationActive = false,
                lastError = t.message ?: t.javaClass.simpleName
            )
            false
        }
    }

    fun stopService(context: Context) {
        context.applicationContext.startService(
            AlwaysOnGatewayService.createStopIntent(context.applicationContext)
        )
        updateServiceState(running = false, notificationActive = false)
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
        GatewayRuntimeSupervisor.currentRuntimeOrNull()?.reloadGatewayFromStoredConfig()
    }

    fun reloadAutomation() {
        GatewayRuntimeSupervisor.currentRuntimeOrNull()?.reloadAutomationFromStoredConfig()
    }

    fun reloadMcp() {
        GatewayRuntimeSupervisor.currentRuntimeOrNull()?.reloadMcpFromStoredConfig()
    }

    fun reloadAll() {
        GatewayRuntimeSupervisor.currentRuntimeOrNull()?.reloadAllFromStoredConfig()
    }

    fun updateRuntimeState(
        gatewayRunning: Boolean,
        activeAdapterCount: Int,
        lastError: String = "",
        processingSessionIds: Set<String> = emptySet()
    ) {
        _status.update {
            it.copy(
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
                it.copy(
                    serviceRunning = false,
                    notificationActive = false,
                    startedAtMs = 0L,
                    lastError = lastError.ifBlank { it.lastError },
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

    fun clearRuntimeState() {
        _status.update {
            it.copy(
                gatewayRunning = false,
                activeAdapterCount = 0,
                lastError = "",
                processingSessionIds = emptySet()
            )
        }
    }
}

internal object AlwaysOnForegroundServiceStartPolicy {
    fun isForegroundServiceStartDenied(t: Throwable): Boolean {
        return isForegroundServiceStartDenied(
            className = t.javaClass.name,
            message = t.message.orEmpty()
        )
    }

    fun isForegroundServiceStartDenied(className: String, message: String): Boolean {
        val normalized = message.lowercase(Locale.US)
        return className == "android.app.ForegroundServiceStartNotAllowedException" ||
            (
                className.endsWith("IllegalStateException") &&
                    normalized.contains("not allowed") &&
                    normalized.contains("start") &&
                    normalized.contains("service")
                )
    }
}
