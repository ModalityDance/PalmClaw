package com.palmclaw.runtime.control

import com.palmclaw.channels.ChannelRuntimeSnapshotSource
import com.palmclaw.tools.ChannelsGetTool
import com.palmclaw.tools.ChannelsSetTool
import com.palmclaw.tools.HeartbeatGetTool
import com.palmclaw.tools.HeartbeatSetTool
import com.palmclaw.tools.HeartbeatTriggerTool
import com.palmclaw.tools.McpStatusTool
import com.palmclaw.tools.RuntimeGetTool
import com.palmclaw.tools.RuntimeSetTool
import com.palmclaw.tools.SessionsListTool
import com.palmclaw.tools.SessionsSendTool
import com.palmclaw.tools.Tool

internal class RuntimeToolIntegration(
    operations: RuntimeControlOperations,
    includeHeartbeat: Boolean,
    refreshPort: RuntimeRefreshPort,
    heartbeatPort: HeartbeatRuntimePort,
    activeSessionSource: ActiveSessionSource,
    sessionDeliveryPort: SessionDeliveryPort,
    channelSnapshotSource: ChannelRuntimeSnapshotSource,
    mcpStatusSource: McpRuntimeStatusSource
) : AutoCloseable {
    private var bindings: Bindings? = Bindings(
        operations = operations,
        refreshPort = refreshPort,
        heartbeatPort = heartbeatPort,
        activeSessionSource = activeSessionSource,
        sessionDeliveryPort = sessionDeliveryPort,
        channelSnapshotSource = channelSnapshotSource,
        mcpStatusSource = mcpStatusSource
    )
    internal val isClosed: Boolean
        get() = bindings == null

    private val runtimeGetTool = RuntimeGetTool {
        requireBindings().operations.getRuntimeSettings().toToolSnapshot()
    }
    private val runtimeSetTool = RuntimeSetTool { request ->
        requireBindings().operations.updateRuntimeSettings(request.toDomain()).toToolSnapshot()
    }
    private val heartbeatGetTool = HeartbeatGetTool {
        requireBindings().operations.getHeartbeat().toToolSnapshot()
    }
    private val heartbeatSetTool = HeartbeatSetTool { request ->
        val active = requireBindings()
        active.operations.updateHeartbeat(
            update = request.toDomain(),
            refreshPort = active.refreshPort,
            heartbeatPort = active.heartbeatPort
        ).toToolSnapshot()
    }
    private val heartbeatTriggerTool = HeartbeatTriggerTool {
        val active = requireBindings()
        active.operations.triggerHeartbeat(active.heartbeatPort)
    }
    private val channelsGetTool = ChannelsGetTool {
        val active = requireBindings()
        active.operations.getChannelBindings(active.channelSnapshotSource).toToolSnapshot()
    }
    private val channelsSetTool = ChannelsSetTool { request ->
        val active = requireBindings()
        active.operations.setChannelEnabled(
            update = ChannelBindingUpdate(request.sessionId, request.sessionTitle, request.enabled),
            refreshPort = active.refreshPort,
            snapshotSource = active.channelSnapshotSource
        ).toToolResult()
    }
    private val sessionsListTool = SessionsListTool {
        val active = requireBindings()
        active.operations.listSessions(active.activeSessionSource).toToolSnapshot()
    }
    private val sessionsSendTool = SessionsSendTool { request ->
        val active = requireBindings()
        active.operations.sendToSession(request.toDomain(), active.sessionDeliveryPort).toToolResult()
    }
    private val mcpStatusTool = McpStatusTool {
        val active = requireBindings()
        active.operations.getMcpStatus(active.mcpStatusSource).toToolSnapshot()
    }

    val tools: List<Tool> = buildList {
        add(runtimeGetTool)
        add(runtimeSetTool)
        if (includeHeartbeat) {
            add(heartbeatGetTool)
            add(heartbeatSetTool)
            add(heartbeatTriggerTool)
        }
        add(channelsGetTool)
        add(channelsSetTool)
        add(sessionsListTool)
        add(sessionsSendTool)
        add(mcpStatusTool)
    }

    override fun close() {
        runtimeGetTool.clearGetCallback()
        runtimeSetTool.clearSetCallback()
        heartbeatGetTool.clearGetCallback()
        heartbeatSetTool.clearSetCallback()
        heartbeatTriggerTool.clearTriggerCallback()
        channelsGetTool.clearGetCallback()
        channelsSetTool.clearSetCallback()
        sessionsListTool.clearListCallback()
        sessionsSendTool.clearSendCallback()
        mcpStatusTool.clearGetCallback()
        bindings = null
    }

    private fun requireBindings(): Bindings =
        checkNotNull(bindings) { "Runtime tool integration is closed" }

    private data class Bindings(
        val operations: RuntimeControlOperations,
        val refreshPort: RuntimeRefreshPort,
        val heartbeatPort: HeartbeatRuntimePort,
        val activeSessionSource: ActiveSessionSource,
        val sessionDeliveryPort: SessionDeliveryPort,
        val channelSnapshotSource: ChannelRuntimeSnapshotSource,
        val mcpStatusSource: McpRuntimeStatusSource
    )

    private fun RuntimeSetTool.Request.toDomain() = RuntimeSettingsUpdate(
        maxToolRounds = maxToolRounds,
        toolResultMaxChars = toolResultMaxChars,
        memoryConsolidationWindow = memoryConsolidationWindow,
        llmCallTimeoutSeconds = llmCallTimeoutSeconds,
        llmConnectTimeoutSeconds = llmConnectTimeoutSeconds,
        llmReadTimeoutSeconds = llmReadTimeoutSeconds,
        defaultToolTimeoutSeconds = defaultToolTimeoutSeconds,
        contextMessages = contextMessages,
        toolArgsPreviewMaxChars = toolArgsPreviewMaxChars
    )

    private fun RuntimeSettingsSnapshot.toToolSnapshot() = RuntimeGetTool.Snapshot(
        maxToolRounds = maxToolRounds,
        toolResultMaxChars = toolResultMaxChars,
        memoryConsolidationWindow = memoryConsolidationWindow,
        llmCallTimeoutSeconds = llmCallTimeoutSeconds,
        llmConnectTimeoutSeconds = llmConnectTimeoutSeconds,
        llmReadTimeoutSeconds = llmReadTimeoutSeconds,
        defaultToolTimeoutSeconds = defaultToolTimeoutSeconds,
        contextMessages = contextMessages,
        toolArgsPreviewMaxChars = toolArgsPreviewMaxChars
    )

    private fun HeartbeatSetTool.Request.toDomain() = HeartbeatUpdate(
        enabled = enabled,
        intervalSeconds = intervalSeconds,
        documentContent = documentContent,
        nextTriggerAtMs = nextTriggerAtMs
    )

    private fun HeartbeatSnapshot.toToolSnapshot() = HeartbeatGetTool.Snapshot(
        enabled = enabled,
        intervalSeconds = intervalSeconds,
        documentContent = documentContent,
        lastTriggeredAtMs = lastTriggeredAtMs,
        nextTriggerAtMs = nextTriggerAtMs
    )

    private fun SessionsSnapshot.toToolSnapshot() = SessionsListTool.Snapshot(
        currentSessionId = currentSessionId,
        sessions = sessions.map { entry ->
            SessionsListTool.Entry(
                sessionId = entry.sessionId,
                title = entry.title,
                status = entry.status,
                isCurrent = entry.isCurrent,
                isLocal = entry.isLocal,
                channelEnabled = entry.channelEnabled,
                boundChannel = entry.boundChannel,
                boundTarget = entry.boundTarget
            )
        }
    )

    private fun SessionsSendTool.Request.toDomain() = SessionDeliveryCommand(
        content = content,
        sessionId = sessionId,
        sessionTitle = sessionTitle,
        deliverRemote = deliverRemote,
        attachments = attachments,
        media = media
    )

    private fun SessionDeliveryResult.toToolResult() = SessionsSendTool.DeliveryResult(
        sessionId = sessionId,
        sessionTitle = sessionTitle,
        remoteDelivered = remoteDelivered,
        note = note
    )

    private fun ChannelBindingsSnapshot.toToolSnapshot() = ChannelsGetTool.Snapshot(
        gatewayEnabled = gatewayEnabled,
        sessions = sessions.map { entry ->
            ChannelsGetTool.Entry(
                sessionId = entry.sessionId,
                title = entry.title,
                bindingEnabled = entry.bindingEnabled,
                channel = entry.channel,
                target = entry.target,
                status = entry.status
            )
        }
    )

    private fun ChannelBindingResult.toToolResult() = ChannelsSetTool.Result(
        sessionId = sessionId,
        sessionTitle = sessionTitle,
        enabled = enabled,
        status = status
    )

    private fun McpStatusSnapshot.toToolSnapshot() = McpStatusTool.Snapshot(
        enabled = enabled,
        generation = generation,
        connectedServerCount = connectedServerCount,
        registeredToolCount = registeredToolCount,
        availableResourceCount = availableResourceCount,
        availableResourceTemplateCount = availableResourceTemplateCount,
        availablePromptCount = availablePromptCount,
        issues = issues.map { issue ->
            McpStatusTool.Issue(
                code = issue.code,
                detail = issue.detail
            )
        },
        servers = servers.map { entry ->
            McpStatusTool.Entry(
                serverId = entry.id,
                serverName = entry.serverName,
                serverUrl = entry.serverUrl,
                phase = entry.phase,
                status = entry.status,
                usable = entry.usable,
                detail = entry.detail,
                toolCount = entry.toolCount,
                resourceCount = entry.resourceCount,
                resourceTemplateCount = entry.resourceTemplateCount,
                promptCount = entry.promptCount,
                completionSupported = entry.completionSupported,
                toolNames = entry.toolNames,
                transport = entry.transport,
                protocolVersion = entry.protocolVersion,
                endpointSecurity = entry.endpointSecurity,
                insecureWarning = entry.insecureWarning
            )
        }
    )
}
