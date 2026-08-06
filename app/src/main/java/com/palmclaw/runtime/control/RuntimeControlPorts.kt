package com.palmclaw.runtime.control

import com.palmclaw.bus.MessageAttachment
import com.palmclaw.bus.OutboundMessage
import com.palmclaw.config.ChannelsConfig
import com.palmclaw.config.HeartbeatConfig
import com.palmclaw.config.SessionChannelBinding

internal interface RuntimeRefreshPort {
    fun applyHeartbeatConfig(config: HeartbeatConfig)
    fun applyChannelsConfig(config: ChannelsConfig)
}

internal interface HeartbeatRuntimePort {
    fun armNextAlarm(config: HeartbeatConfig, timestampMs: Long)
    suspend fun triggerNow(): String
}

internal fun interface ActiveSessionSource {
    fun currentSessionId(): String?
}

internal interface SessionDeliveryPort {
    suspend fun prepareAttachments(
        sessionId: String,
        sessionTitle: String,
        messageId: Long,
        attachments: List<MessageAttachment>
    ): List<MessageAttachment>

    fun resolveActiveBinding(sessionId: String): SessionChannelBinding?
    fun supportsRemoteDelivery(outbound: OutboundMessage): Boolean
    suspend fun deliver(outbound: OutboundMessage)
    fun markRemoteDeliverySent()
    fun adapterMetadata(binding: SessionChannelBinding): Map<String, String>
}

internal fun interface McpRuntimeStatusSource {
    fun currentStatuses(): Map<String, McpRuntimeStatus>
}
