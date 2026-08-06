package com.palmclaw.runtime.control

import com.palmclaw.bus.MessageAttachmentTransferState
import com.palmclaw.bus.OutboundMessage
import com.palmclaw.bus.normalizeMessageAttachments
import com.palmclaw.channels.ChannelBindingRuntimeProjector
import com.palmclaw.channels.ChannelRuntimeSnapshotSource
import com.palmclaw.config.AppLimits
import com.palmclaw.config.AppSession
import com.palmclaw.config.HeartbeatConfig
import com.palmclaw.config.McpHttpServerConfig
import com.palmclaw.storage.entities.SessionEntity
import java.util.Locale

internal interface RuntimeControlOperations {
    fun getRuntimeSettings(): RuntimeSettingsSnapshot
    fun updateRuntimeSettings(update: RuntimeSettingsUpdate): RuntimeSettingsSnapshot
    suspend fun getHeartbeat(): HeartbeatSnapshot
    suspend fun updateHeartbeat(
        update: HeartbeatUpdate,
        refreshPort: RuntimeRefreshPort,
        heartbeatPort: HeartbeatRuntimePort
    ): HeartbeatSnapshot
    suspend fun triggerHeartbeat(heartbeatPort: HeartbeatRuntimePort): String
    suspend fun listSessions(activeSessionSource: ActiveSessionSource): SessionsSnapshot
    suspend fun sendToSession(
        command: SessionDeliveryCommand,
        deliveryPort: SessionDeliveryPort
    ): SessionDeliveryResult
    suspend fun getChannelBindings(snapshotSource: ChannelRuntimeSnapshotSource): ChannelBindingsSnapshot
    suspend fun setChannelEnabled(
        update: ChannelBindingUpdate,
        refreshPort: RuntimeRefreshPort,
        snapshotSource: ChannelRuntimeSnapshotSource
    ): ChannelBindingResult
    fun getMcpStatus(statusSource: McpRuntimeStatusSource): McpStatusSnapshot
}

internal class RuntimeControlService(
    private val persistence: RuntimeControlPersistence,
    private val channelProjector: ChannelBindingRuntimeProjector
) : RuntimeControlOperations {
    override fun getRuntimeSettings(): RuntimeSettingsSnapshot =
        persistence.getAppConfig().let(::runtimeSettingsSnapshot)

    override fun updateRuntimeSettings(update: RuntimeSettingsUpdate): RuntimeSettingsSnapshot {
        val current = persistence.getAppConfig()
        val updated = current.copy(
            maxToolRounds = update.maxToolRounds
                ?.let { validateInt("Max tool rounds", it, AppLimits.MIN_MAX_TOOL_ROUNDS, AppLimits.MAX_MAX_TOOL_ROUNDS) }
                ?: current.maxToolRounds,
            toolResultMaxChars = update.toolResultMaxChars
                ?.let { validateInt("Tool result max chars", it, AppLimits.MIN_TOOL_RESULT_MAX_CHARS, AppLimits.MAX_TOOL_RESULT_MAX_CHARS) }
                ?: current.toolResultMaxChars,
            memoryConsolidationWindow = update.memoryConsolidationWindow
                ?.let {
                    validateInt(
                        "Memory consolidation window",
                        it,
                        AppLimits.MIN_MEMORY_CONSOLIDATION_WINDOW,
                        AppLimits.MAX_MEMORY_CONSOLIDATION_WINDOW
                    )
                }
                ?: current.memoryConsolidationWindow,
            llmCallTimeoutSeconds = update.llmCallTimeoutSeconds
                ?.let {
                    validateInt(
                        "LLM call timeout seconds",
                        it,
                        AppLimits.MIN_LLM_CALL_TIMEOUT_SECONDS,
                        AppLimits.MAX_LLM_CALL_TIMEOUT_SECONDS
                    )
                }
                ?: current.llmCallTimeoutSeconds,
            llmConnectTimeoutSeconds = update.llmConnectTimeoutSeconds
                ?.let {
                    validateInt(
                        "LLM connect timeout seconds",
                        it,
                        AppLimits.MIN_LLM_CONNECT_TIMEOUT_SECONDS,
                        AppLimits.MAX_LLM_CONNECT_TIMEOUT_SECONDS
                    )
                }
                ?: current.llmConnectTimeoutSeconds,
            llmReadTimeoutSeconds = update.llmReadTimeoutSeconds
                ?.let {
                    validateInt(
                        "LLM read timeout seconds",
                        it,
                        AppLimits.MIN_LLM_READ_TIMEOUT_SECONDS,
                        AppLimits.MAX_LLM_READ_TIMEOUT_SECONDS
                    )
                }
                ?: current.llmReadTimeoutSeconds,
            defaultToolTimeoutSeconds = update.defaultToolTimeoutSeconds
                ?.let {
                    validateInt(
                        "Default tool timeout seconds",
                        it,
                        AppLimits.MIN_TOOL_TIMEOUT_SECONDS,
                        AppLimits.MAX_TOOL_TIMEOUT_SECONDS
                    )
                }
                ?: current.defaultToolTimeoutSeconds,
            contextMessages = update.contextMessages
                ?.let { validateInt("Context messages", it, AppLimits.MIN_CONTEXT_MESSAGES, AppLimits.MAX_CONTEXT_MESSAGES) }
                ?: current.contextMessages,
            toolArgsPreviewMaxChars = update.toolArgsPreviewMaxChars
                ?.let {
                    validateInt(
                        "Tool args preview max chars",
                        it,
                        AppLimits.MIN_TOOL_ARGS_PREVIEW_MAX_CHARS,
                        AppLimits.MAX_TOOL_ARGS_PREVIEW_MAX_CHARS
                    )
                }
                ?: current.toolArgsPreviewMaxChars
        )
        persistence.saveAppConfig(updated)
        return runtimeSettingsSnapshot(updated)
    }

    override suspend fun getHeartbeat(): HeartbeatSnapshot = heartbeatSnapshot(persistence.getHeartbeatConfig())

    override suspend fun updateHeartbeat(
        update: HeartbeatUpdate,
        refreshPort: RuntimeRefreshPort,
        heartbeatPort: HeartbeatRuntimePort
    ): HeartbeatSnapshot {
        val current = persistence.getHeartbeatConfig()
        val intervalSeconds = update.intervalSeconds
            ?.also {
                if (it !in AppLimits.MIN_HEARTBEAT_INTERVAL_SECONDS..AppLimits.MAX_HEARTBEAT_INTERVAL_SECONDS) {
                    throw IllegalArgumentException(
                        "Heartbeat interval seconds must be between ${AppLimits.MIN_HEARTBEAT_INTERVAL_SECONDS} and ${AppLimits.MAX_HEARTBEAT_INTERVAL_SECONDS}"
                    )
                }
            }
            ?: current.intervalSeconds
        val updated = HeartbeatConfig(
            enabled = update.enabled ?: current.enabled,
            intervalSeconds = intervalSeconds
        )
        persistence.saveHeartbeatConfig(updated)
        update.documentContent?.let { persistence.writeHeartbeatDocument(it) }
        refreshPort.applyHeartbeatConfig(updated)
        update.nextTriggerAtMs?.let { requested ->
            if (!updated.enabled) {
                throw IllegalStateException("Cannot set next heartbeat trigger while heartbeat is disabled")
            }
            heartbeatPort.armNextAlarm(updated, requested)
        }
        return heartbeatSnapshot(updated)
    }

    override suspend fun triggerHeartbeat(heartbeatPort: HeartbeatRuntimePort): String {
        if (!persistence.getHeartbeatConfig().enabled) {
            throw IllegalStateException("Heartbeat is disabled")
        }
        return heartbeatPort.triggerNow()
    }

    override suspend fun listSessions(activeSessionSource: ActiveSessionSource): SessionsSnapshot {
        val bindings = persistence.getSessionChannelBindings().associateBy { it.sessionId.trim() }
        val sessions = sessionsWithLocalFallback()
        val activeId = activeSessionSource.currentSessionId()
            ?.trim()
            ?.ifBlank { null }
            ?: AppSession.LOCAL_SESSION_ID
        val entries = sessions.map { session ->
            val binding = bindings[session.id]
            val channelEnabled = binding?.enabled ?: true
            val isCurrent = session.id == activeId
            SessionSnapshotEntry(
                sessionId = session.id,
                title = session.title,
                status = when {
                    isCurrent -> "current"
                    !channelEnabled -> "off"
                    else -> "active"
                },
                isCurrent = isCurrent,
                isLocal = session.id == AppSession.LOCAL_SESSION_ID,
                channelEnabled = channelEnabled,
                boundChannel = binding?.channel?.trim().orEmpty(),
                boundTarget = binding?.chatId?.trim().orEmpty()
            )
        }
        return SessionsSnapshot(currentSessionId = activeId, sessions = entries)
    }

    override suspend fun sendToSession(
        command: SessionDeliveryCommand,
        deliveryPort: SessionDeliveryPort
    ): SessionDeliveryResult {
        val target = resolveSession(SessionSelector(command.sessionId, command.sessionTitle))
            ?: throw IllegalArgumentException("target session not found")
        val normalizedAttachments = normalizeMessageAttachments(command.attachments, command.media)
        val preparedAttachments = deliveryPort.prepareAttachments(
            sessionId = target.id,
            sessionTitle = target.title,
            messageId = System.currentTimeMillis(),
            attachments = normalizedAttachments
        )
        preparedAttachments.firstOrNull { it.transferState == MessageAttachmentTransferState.Failed }
            ?.let { failed ->
                throw IllegalStateException(
                    failed.failureMessage ?: "Attachment prepare failed: ${failed.label}"
                )
            }
        if (normalizedAttachments.isNotEmpty() && preparedAttachments.isEmpty()) {
            throw IllegalStateException("Attachment prepare failed: no readable attachments")
        }
        persistence.appendAssistantMessage(target.id, command.content, preparedAttachments)
        persistence.touchSession(target.id)

        var remoteDelivered = false
        val rawBinding = if (command.deliverRemote) {
            persistence.getSessionChannelBindings()
                .firstOrNull { it.sessionId.trim() == target.id.trim() && it.enabled }
        } else {
            null
        }
        val binding = if (command.deliverRemote) deliveryPort.resolveActiveBinding(target.id) else null
        if (command.deliverRemote && rawBinding != null && binding == null) {
            throw IllegalStateException("target session remote channel is configured but inactive or incomplete")
        }
        var note: String? = null
        if (binding != null) {
            val outbound = OutboundMessage(
                channel = binding.channel,
                chatId = binding.chatId,
                content = command.content,
                attachments = preparedAttachments,
                media = command.media,
                metadata = deliveryPort.adapterMetadata(binding)
            )
            if (deliveryPort.supportsRemoteDelivery(outbound)) {
                deliveryPort.deliver(outbound)
                remoteDelivered = true
                deliveryPort.markRemoteDeliverySent()
            } else {
                note =
                    "${binding.channel} remote attachment delivery is not supported in the current adapter mode. The local session message was kept."
            }
        }
        if (
            note == null &&
            command.deliverRemote &&
            rawBinding?.channel?.trim()?.equals("wecom", ignoreCase = true) == true
        ) {
            note =
                "WeCom remote delivery is reply-context based. It only works after that WeCom chat has sent a recent inbound message; local context is kept until app restart and up to 7 days."
        }
        return SessionDeliveryResult(target.id, target.title, remoteDelivered, note)
    }

    override suspend fun getChannelBindings(snapshotSource: ChannelRuntimeSnapshotSource): ChannelBindingsSnapshot {
        val gatewayEnabled = persistence.getChannelsConfig().enabled
        val bindings = persistence.getSessionChannelBindings().associateBy { it.sessionId.trim() }
        val entries = sessionsWithLocalFallback().map { session ->
            val binding = bindings[session.id]
            val projection = channelProjector.project(binding, gatewayEnabled, snapshotSource)
            ChannelBindingSnapshotEntry(
                sessionId = session.id,
                title = session.title,
                bindingEnabled = binding?.enabled ?: false,
                channel = projection.channel,
                target = projection.target,
                status = projection.status
            )
        }
        return ChannelBindingsSnapshot(gatewayEnabled, entries)
    }

    override suspend fun setChannelEnabled(
        update: ChannelBindingUpdate,
        refreshPort: RuntimeRefreshPort,
        snapshotSource: ChannelRuntimeSnapshotSource
    ): ChannelBindingResult {
        val target = resolveSession(SessionSelector(update.sessionId, update.sessionTitle))
            ?: throw IllegalArgumentException("target session not found")
        val binding = persistence.getSessionChannelBindings()
            .firstOrNull { it.sessionId.trim() == target.id.trim() }
            ?: throw IllegalArgumentException("target session has no channel binding")
        if (binding.channel.trim().isBlank()) {
            throw IllegalArgumentException("target session has no configured channel binding")
        }
        persistence.saveSessionChannelBinding(binding.copy(enabled = update.enabled))
        val current = persistence.getChannelsConfig()
        val shouldEnableGateway = persistence.getSessionChannelBindings()
            .any(channelProjector::canStartAdapter)
        val runtimeConfig = if (current.enabled == shouldEnableGateway) {
            current
        } else {
            current.copy(enabled = shouldEnableGateway).also(persistence::saveChannelsConfig)
        }
        refreshPort.applyChannelsConfig(runtimeConfig)
        val status = getChannelBindings(snapshotSource).sessions
            .firstOrNull { it.sessionId == target.id }
            ?.status
            ?: if (update.enabled) "Configured" else "Disabled"
        return ChannelBindingResult(target.id, target.title, update.enabled, status)
    }

    override fun getMcpStatus(statusSource: McpRuntimeStatusSource): McpStatusSnapshot {
        val config = persistence.getMcpHttpConfig()
        val servers = config.servers.ifEmpty {
            if (config.serverUrl.isNotBlank()) {
                listOf(
                    McpHttpServerConfig(
                        id = "mcp_1",
                        serverName = config.serverName,
                        serverUrl = config.serverUrl,
                        authToken = config.authToken,
                        toolTimeoutSeconds = config.toolTimeoutSeconds
                    )
                )
            } else {
                emptyList()
            }
        }
        val statuses = statusSource.currentStatuses()
        val entries = servers.map { server ->
            val normalizedName = normalizeMcpRuntimeServerName(server.serverName)
            val status = statuses[normalizedName] ?: if (config.enabled) {
                McpRuntimeStatus(status = "Not connected")
            } else {
                McpRuntimeStatus(status = "Disabled")
            }
            McpStatusEntry(
                id = server.id.ifBlank { normalizedName.ifBlank { "mcp" } },
                serverName = server.serverName,
                serverUrl = server.serverUrl,
                status = status.status,
                usable = status.usable,
                detail = status.detail,
                toolCount = status.toolCount,
                toolNames = status.toolNames
            )
        }
        return McpStatusSnapshot(
            enabled = config.enabled,
            connectedServerCount = entries.count { it.status.equals("Connected", ignoreCase = true) },
            registeredToolCount = entries.sumOf { it.toolCount },
            servers = entries
        )
    }

    private suspend fun heartbeatSnapshot(config: HeartbeatConfig): HeartbeatSnapshot =
        HeartbeatSnapshot(
            enabled = config.enabled,
            intervalSeconds = config.intervalSeconds,
            documentContent = persistence.readHeartbeatDocument(),
            lastTriggeredAtMs = persistence.getHeartbeatLastTriggeredAtMs(),
            nextTriggerAtMs = persistence.getHeartbeatNextTriggerAtMs()
        )

    private suspend fun sessionsWithLocalFallback(): List<SessionEntity> {
        val sessions = persistence.listSessions().toMutableList()
        if (sessions.none { it.id == AppSession.LOCAL_SESSION_ID }) {
            sessions += SessionEntity(
                id = AppSession.LOCAL_SESSION_ID,
                title = AppSession.LOCAL_SESSION_TITLE,
                createdAt = 0L,
                updatedAt = 0L
            )
        }
        return sessions.sortedWith(
            compareBy<SessionEntity> { it.id != AppSession.LOCAL_SESSION_ID }
                .thenByDescending { it.updatedAt }
                .thenBy { it.createdAt }
        )
    }

    private suspend fun resolveSession(selector: SessionSelector): SessionTarget? {
        val sessions = persistence.listSessions().map { SessionTarget(it.id, it.title) }
        val requestedId = selector.sessionId?.trim().orEmpty()
        if (requestedId.isNotBlank()) {
            return sessions.firstOrNull { it.id.equals(requestedId, ignoreCase = true) }
        }
        val requestedTitle = selector.sessionTitle?.trim().orEmpty()
        if (requestedTitle.isBlank()) return null
        val exact = sessions.filter { it.title.equals(requestedTitle, ignoreCase = true) }
        if (exact.size > 1) {
            throw IllegalArgumentException("session_title matches multiple sessions; use session_id")
        }
        exact.singleOrNull()?.let { return it }
        val partial = sessions.filter { it.title.contains(requestedTitle, ignoreCase = true) }
        return when {
            partial.isEmpty() -> null
            partial.size == 1 -> partial.first()
            else -> throw IllegalArgumentException("session_title is ambiguous; use session_id")
        }
    }

    private fun runtimeSettingsSnapshot(config: com.palmclaw.config.AppConfig) =
        RuntimeSettingsSnapshot(
            maxToolRounds = config.maxToolRounds,
            toolResultMaxChars = config.toolResultMaxChars,
            memoryConsolidationWindow = config.memoryConsolidationWindow,
            llmCallTimeoutSeconds = config.llmCallTimeoutSeconds,
            llmConnectTimeoutSeconds = config.llmConnectTimeoutSeconds,
            llmReadTimeoutSeconds = config.llmReadTimeoutSeconds,
            defaultToolTimeoutSeconds = config.defaultToolTimeoutSeconds,
            contextMessages = config.contextMessages,
            toolArgsPreviewMaxChars = config.toolArgsPreviewMaxChars
        )

    private fun validateInt(label: String, value: Int, min: Int, max: Int): Int {
        if (value !in min..max) {
            throw IllegalArgumentException("$label must be between $min and $max")
        }
        return value
    }

    private fun normalizeMcpRuntimeServerName(input: String): String =
        input.trim().lowercase(Locale.US)
            .replace(Regex("[^a-z0-9_\\-]+"), "_")
            .trim('_')
            .take(40)
            .ifBlank { AppLimits.DEFAULT_MCP_HTTP_SERVER_NAME }

    private data class SessionTarget(val id: String, val title: String)
}
