package com.palmclaw.runtime

import android.content.Context
import com.palmclaw.bus.MessageAttachment
import com.palmclaw.bus.OutboundMessage
import kotlinx.coroutines.flow.StateFlow

data class RuntimeControllerStatus(
    val running: Boolean = false,
    val gatewayRunning: Boolean = false,
    val activeAdapterCount: Int = 0,
    val lastError: String = "",
    val processingSessionIds: Set<String> = emptySet()
)

object RuntimeController {
    val status: StateFlow<RuntimeControllerStatus> = GatewayRuntimeSupervisor.status

    fun start(context: Context) {
        GatewayRuntimeSupervisor.ensureStarted(context)
    }

    fun reloadGateway(context: Context) {
        GatewayRuntimeSupervisor.reloadGateway(context)
    }

    fun reloadAutomation(context: Context) {
        GatewayRuntimeSupervisor.reloadAutomation(context)
    }

    fun reloadMcp(context: Context) {
        GatewayRuntimeSupervisor.reloadMcp(context)
    }

    fun reloadAll(context: Context) {
        GatewayRuntimeSupervisor.reloadAll(context)
    }

    suspend fun publishOutbound(context: Context, outbound: OutboundMessage) {
        GatewayRuntimeSupervisor.publishOutbound(context, outbound)
    }

    suspend fun runUserMessage(
        context: Context,
        sessionId: String,
        sessionTitle: String,
        text: String,
        attachments: List<MessageAttachment> = emptyList()
    ) {
        GatewayRuntimeSupervisor.runUserMessage(
            context = context,
            sessionId = sessionId,
            sessionTitle = sessionTitle,
            text = text,
            attachments = attachments
        )
    }

    suspend fun triggerHeartbeatNow(context: Context): String {
        return GatewayRuntimeSupervisor.triggerHeartbeatNow(context)
    }

    suspend fun processHeartbeatTick(context: Context): String? {
        return GatewayRuntimeSupervisor.processHeartbeatTick(context)
    }

    suspend fun processDueCronJobs(context: Context, resync: Boolean) {
        GatewayRuntimeSupervisor.processDueCronJobs(context, resync = resync)
    }

    fun stop() {
        // Runtime lifetime is process-scoped. Stopping a mode or service shell must not
        // tear down the single in-process GatewayRuntime.
    }
}
