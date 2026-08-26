package com.palmclaw.channels

import com.palmclaw.bus.OutboundMessage
import com.palmclaw.config.SessionChannelBinding

interface GatewayOrchestratorControl {
    val adapterCount: Int
    fun start()
    fun reconfigure(adapters: List<ChannelAdapter>)
    fun stop()
    suspend fun deliverOutboundNow(outbound: OutboundMessage)
    fun resolveOutboundAttachmentCapability(
        outbound: OutboundMessage
    ): ChannelAttachmentCapability?
}

internal fun interface GatewayOrchestratorFactory {
    fun create(adapters: List<ChannelAdapter>): GatewayOrchestratorControl
}

internal data class ChannelGatewayLifecycleSnapshot(
    val running: Boolean = false,
    val adapterCount: Int = 0,
    val lastError: String = ""
)

internal class ChannelGatewayLifecycle(
    private val adapterFactory: ChannelAdapterFactory,
    private val orchestratorFactory: GatewayOrchestratorFactory,
    private val onStateChanged: (ChannelGatewayLifecycleSnapshot) -> Unit = {}
) {
    private var orchestrator: GatewayOrchestratorControl? = null

    fun apply(
        enabled: Boolean,
        bindings: List<SessionChannelBinding>
    ): ChannelGatewayLifecycleSnapshot {
        if (!enabled) return stop()

        val adapters = adapterFactory.create(bindings)
        if (adapters.isEmpty()) {
            stopOwnedOrchestrator()
            val error = if (bindings.any { it.enabled && it.channel.trim().isNotBlank() }) {
                NO_ACTIVE_ADAPTER_ERROR
            } else {
                ""
            }
            return publish(running = false, adapterCount = 0, lastError = error)
        }

        val current = orchestrator
        if (current != null) {
            current.reconfigure(adapters)
            return publish(
                running = true,
                adapterCount = current.adapterCount,
                lastError = ""
            )
        }

        val created = orchestratorFactory.create(adapters)
        created.start()
        orchestrator = created
        return publish(
            running = true,
            adapterCount = created.adapterCount,
            lastError = ""
        )
    }

    fun stop(): ChannelGatewayLifecycleSnapshot {
        stopOwnedOrchestrator()
        return publish(running = false, adapterCount = 0, lastError = "")
    }

    suspend fun deliverOutbound(outbound: OutboundMessage) {
        val current = orchestrator
            ?: throw IllegalStateException(GATEWAY_NOT_RUNNING_ERROR)
        try {
            current.deliverOutboundNow(outbound)
            publish(
                running = true,
                adapterCount = current.adapterCount,
                lastError = ""
            )
        } catch (failure: Throwable) {
            publish(
                running = true,
                adapterCount = current.adapterCount,
                lastError = failure.message ?: failure.javaClass.simpleName
            )
            throw failure
        }
    }

    fun resolveOutboundAttachmentCapability(
        outbound: OutboundMessage
    ): ChannelAttachmentCapability? =
        orchestrator?.resolveOutboundAttachmentCapability(outbound)

    private fun stopOwnedOrchestrator() {
        orchestrator?.stop()
        orchestrator = null
    }

    private fun publish(
        running: Boolean,
        adapterCount: Int,
        lastError: String
    ): ChannelGatewayLifecycleSnapshot =
        ChannelGatewayLifecycleSnapshot(
            running = running,
            adapterCount = adapterCount,
            lastError = lastError
        ).also(onStateChanged)

    private companion object {
        const val NO_ACTIVE_ADAPTER_ERROR =
            "No active adapter could start. Check credentials and target IDs."
        const val GATEWAY_NOT_RUNNING_ERROR =
            "Gateway is not running; cannot deliver outbound message"
    }
}
