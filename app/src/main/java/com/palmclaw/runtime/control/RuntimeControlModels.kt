package com.palmclaw.runtime.control

import com.palmclaw.bus.MessageAttachment

internal data class RuntimeSettingsUpdate(
    val maxToolRounds: Int? = null,
    val toolResultMaxChars: Int? = null,
    val memoryConsolidationWindow: Int? = null,
    val llmCallTimeoutSeconds: Int? = null,
    val llmConnectTimeoutSeconds: Int? = null,
    val llmReadTimeoutSeconds: Int? = null,
    val defaultToolTimeoutSeconds: Int? = null,
    val contextMessages: Int? = null,
    val toolArgsPreviewMaxChars: Int? = null
)

internal data class RuntimeSettingsSnapshot(
    val maxToolRounds: Int,
    val toolResultMaxChars: Int,
    val memoryConsolidationWindow: Int,
    val llmCallTimeoutSeconds: Int,
    val llmConnectTimeoutSeconds: Int,
    val llmReadTimeoutSeconds: Int,
    val defaultToolTimeoutSeconds: Int,
    val contextMessages: Int,
    val toolArgsPreviewMaxChars: Int
)

internal data class HeartbeatUpdate(
    val enabled: Boolean? = null,
    val intervalSeconds: Long? = null,
    val documentContent: String? = null,
    val nextTriggerAtMs: Long? = null
)

internal data class HeartbeatSnapshot(
    val enabled: Boolean,
    val intervalSeconds: Long,
    val documentContent: String,
    val lastTriggeredAtMs: Long,
    val nextTriggerAtMs: Long
)

internal data class SessionSelector(
    val sessionId: String? = null,
    val sessionTitle: String? = null
)

internal data class SessionDeliveryCommand(
    val content: String,
    val sessionId: String? = null,
    val sessionTitle: String? = null,
    val deliverRemote: Boolean = true,
    val attachments: List<MessageAttachment> = emptyList(),
    val media: List<String> = emptyList()
)

internal data class SessionDeliveryResult(
    val sessionId: String,
    val sessionTitle: String,
    val remoteDelivered: Boolean,
    val note: String? = null
)

internal data class SessionsSnapshot(
    val currentSessionId: String,
    val sessions: List<SessionSnapshotEntry>
)

internal data class SessionSnapshotEntry(
    val sessionId: String,
    val title: String,
    val status: String,
    val isCurrent: Boolean,
    val isLocal: Boolean,
    val channelEnabled: Boolean,
    val boundChannel: String,
    val boundTarget: String
)

internal data class ChannelBindingUpdate(
    val sessionId: String? = null,
    val sessionTitle: String? = null,
    val enabled: Boolean
)

internal data class ChannelBindingResult(
    val sessionId: String,
    val sessionTitle: String,
    val enabled: Boolean,
    val status: String
)

internal data class ChannelBindingsSnapshot(
    val gatewayEnabled: Boolean,
    val sessions: List<ChannelBindingSnapshotEntry>
)

internal data class ChannelBindingSnapshotEntry(
    val sessionId: String,
    val title: String,
    val bindingEnabled: Boolean,
    val channel: String,
    val target: String,
    val status: String
)

internal data class ChannelProjection(
    val target: String,
    val status: String
)

internal data class McpRuntimeStatus(
    val status: String,
    val usable: Boolean = status.equals("Connected", ignoreCase = true),
    val detail: String = "",
    val toolCount: Int = 0,
    val toolNames: List<String> = emptyList()
)

internal data class McpStatusSnapshot(
    val enabled: Boolean,
    val connectedServerCount: Int,
    val registeredToolCount: Int,
    val servers: List<McpStatusEntry>
)

internal data class McpStatusEntry(
    val id: String,
    val serverName: String,
    val serverUrl: String,
    val status: String,
    val usable: Boolean,
    val detail: String,
    val toolCount: Int,
    val toolNames: List<String>
)
