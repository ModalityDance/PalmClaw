package com.palmclaw.ui

import com.palmclaw.config.AppLimits
import com.palmclaw.config.McpHttpConfig
import com.palmclaw.config.McpHttpServerConfig
import com.palmclaw.mcp.McpEndpointDisposition
import com.palmclaw.mcp.McpEndpointPolicy
import com.palmclaw.mcp.McpEndpointSecurity
import com.palmclaw.mcp.McpRuntimeSnapshot
import java.util.Locale

internal object McpSettingsMapper {
    fun runtimeSnapshot(snapshot: McpRuntimeSnapshot): UiMcpRuntimeSnapshot =
        UiMcpRuntimeSnapshot(
            enabled = snapshot.enabled,
            generation = snapshot.generation,
            issues = snapshot.issues.map { issue ->
                UiMcpRuntimeIssue(
                    code = issue.code,
                    detail = issue.detail
                )
            }
        )

    fun runtimeStatuses(snapshot: McpRuntimeSnapshot): Map<String, UiMcpServerRuntimeStatus> =
        snapshot.servers.associate { server ->
            server.serverId to UiMcpServerRuntimeStatus(
                serverName = server.serverName,
                endpoint = server.endpoint,
                status = server.phase.toDisplayStatus(),
                phase = server.phase.name.lowercase(Locale.US),
                usable = server.usable,
                detail = server.detail.orEmpty(),
                toolCount = server.toolCount,
                toolNames = server.toolNames,
                resourceCount = server.resourceCount,
                resourceTemplateCount = server.resourceTemplateCount,
                promptCount = server.promptCount,
                completionSupported = server.completionSupported,
                transport = server.transport?.name?.lowercase(Locale.US),
                protocolVersion = server.protocolVersion,
                endpointSecurity = server.endpointSecurity?.name?.lowercase(Locale.US),
                insecureWarning = server.insecureWarning
            )
        }

    fun applyRuntimeSnapshot(
        state: McpSettingsState,
        snapshot: McpRuntimeSnapshot
    ): McpSettingsState = state.copy(
        runtimeSnapshot = runtimeSnapshot(snapshot),
        servers = applyRuntimeStatuses(
            servers = state.servers,
            enabled = snapshot.enabled,
            runtimeStatuses = runtimeStatuses(snapshot)
        )
    )

    fun buildConfig(state: McpSettingsState): McpHttpConfig {
        val servers = buildNormalizedServers(state)
        val duplicateNames = servers
            .groupingBy { normalizeRuntimeServerName(it.serverName) }
            .eachCount()
            .filterValues { it > 1 }
        if (duplicateNames.isNotEmpty()) {
            throw IllegalArgumentException("MCP server names must be unique.")
        }
        if (state.enabled && servers.isEmpty()) {
            throw IllegalArgumentException("Enable MCP requires at least one configured server.")
        }
        val first = servers.firstOrNull()
        return McpHttpConfig(
            enabled = state.enabled,
            serverName = first?.serverName ?: AppLimits.DEFAULT_MCP_HTTP_SERVER_NAME,
            serverUrl = first?.serverUrl.orEmpty(),
            authToken = first?.authToken.orEmpty(),
            toolTimeoutSeconds = first?.toolTimeoutSeconds
                ?: AppLimits.DEFAULT_MCP_HTTP_TOOL_TIMEOUT_SECONDS,
            insecureHttpAllowedOrigin = first?.insecureHttpAllowedOrigin,
            servers = servers
        )
    }

    fun buildUiServers(
        config: McpHttpConfig,
        runtimeStatuses: Map<String, UiMcpServerRuntimeStatus>
    ): List<UiMcpServerConfig> {
        val configured = normalizedServersFromConfig(config).map { server ->
            val serverId = server.id.ifBlank { "mcp_${server.serverName}_${server.serverUrl.hashCode()}" }
            UiMcpServerConfig(
                id = serverId,
                serverName = server.serverName,
                serverUrl = server.serverUrl,
                authToken = server.authToken,
                toolTimeoutSeconds = server.toolTimeoutSeconds.toString(),
                insecureHttpAllowedOrigin = server.insecureHttpAllowedOrigin
            )
        }
        return applyRuntimeStatuses(configured, config.enabled, runtimeStatuses)
    }

    /** Updates only runtime-derived fields so a status emission cannot discard unsaved edits. */
    fun applyRuntimeStatuses(
        servers: List<UiMcpServerConfig>,
        enabled: Boolean,
        runtimeStatuses: Map<String, UiMcpServerRuntimeStatus>
    ): List<UiMcpServerConfig> = servers.map { server ->
        if (server.dirty) return@map markServerDirty(server)
        val status = (
            runtimeStatuses[server.id]
                ?: runtimeStatuses[normalizeRuntimeServerName(server.serverName)]
            )
            ?.takeIf { it.matches(server) }
            ?: defaultRuntimeStatus(enabled, server.toServerConfig())
        server.copy(
            phase = status.phase,
            status = status.status,
            usable = status.usable,
            detail = status.detail,
            toolCount = status.toolCount,
            resourceCount = status.resourceCount,
            resourceTemplateCount = status.resourceTemplateCount,
            promptCount = status.promptCount,
            completionSupported = status.completionSupported,
            toolNames = status.toolNames,
            transport = status.transport,
            protocolVersion = status.protocolVersion,
            endpointSecurity = status.endpointSecurity,
            insecureWarning = status.insecureWarning
        )
    }

    /** Marks a persisted-server draft as unsaved and removes every runtime-derived value. */
    fun markServerDirty(server: UiMcpServerConfig): UiMcpServerConfig = server.copy(
        dirty = true,
        phase = "unsaved",
        status = "Unsaved changes",
        usable = false,
        detail = "",
        toolCount = 0,
        resourceCount = 0,
        resourceTemplateCount = 0,
        promptCount = 0,
        completionSupported = false,
        toolNames = emptyList(),
        transport = null,
        protocolVersion = null,
        endpointSecurity = null,
        insecureWarning = null
    )

    fun normalizeRuntimeServerName(input: String): String {
        return input.trim().lowercase(Locale.US)
            .replace(Regex("[^a-z0-9_\\-]+"), "_")
            .trim('_')
            .take(40)
            .ifBlank { AppLimits.DEFAULT_MCP_HTTP_SERVER_NAME }
    }

    private fun UiMcpServerRuntimeStatus.matches(server: UiMcpServerConfig): Boolean {
        val nameMatches = serverName.isBlank() ||
            normalizeRuntimeServerName(serverName) == normalizeRuntimeServerName(server.serverName)
        val endpointMatches = endpoint.isBlank() ||
            normalizedEndpoint(endpoint) == normalizedEndpoint(server.serverUrl)
        return nameMatches && endpointMatches
    }

    private fun normalizedEndpoint(value: String): String = value
        .trim()
        .substringBefore('?')
        .substringBefore('#')
        .trimEnd('/')

    private fun buildNormalizedServers(state: McpSettingsState): List<McpHttpServerConfig> {
        return state.servers.mapIndexedNotNull { index, item ->
            val name = item.serverName.trim().ifBlank { AppLimits.DEFAULT_MCP_HTTP_SERVER_NAME }
            val url = item.serverUrl.trim()
            val token = item.authToken.trim()
            val timeout = item.toolTimeoutSeconds.trim().toIntOrNull()
                ?: throw IllegalArgumentException("MCP server #${index + 1} timeout must be a number")
            if (timeout !in AppLimits.MIN_MCP_HTTP_TOOL_TIMEOUT_SECONDS..AppLimits.MAX_MCP_HTTP_TOOL_TIMEOUT_SECONDS) {
                throw IllegalArgumentException(
                    "MCP server #${index + 1} timeout must be between ${AppLimits.MIN_MCP_HTTP_TOOL_TIMEOUT_SECONDS} and ${AppLimits.MAX_MCP_HTTP_TOOL_TIMEOUT_SECONDS} seconds"
                )
            }
            val looksEmpty = url.isBlank() && token.isBlank() && item.serverName.trim().isBlank()
            if (looksEmpty) return@mapIndexedNotNull null
            if (url.isBlank()) {
                throw IllegalArgumentException("MCP server #${index + 1} URL is required")
            }
            val endpoint = McpEndpointPolicy.evaluate(
                rawUrl = url,
                authToken = token,
                insecureHttpAllowedOrigin = item.insecureHttpAllowedOrigin
            )
            if (endpoint.disposition == McpEndpointDisposition.REJECTED) {
                throw IllegalArgumentException(endpoint.message)
            }
            val allowedOrigin = normalizedAllowedOrigin(
                candidate = item.insecureHttpAllowedOrigin,
                endpointSecurity = endpoint.security,
                canonicalOrigin = endpoint.canonicalOrigin
            )
            McpHttpServerConfig(
                id = item.id.ifBlank { "mcp_${index + 1}" },
                serverName = name,
                serverUrl = endpoint.canonicalUrl ?: url,
                authToken = token,
                toolTimeoutSeconds = timeout,
                insecureHttpAllowedOrigin = allowedOrigin
            )
        }
    }

    private fun normalizedServersFromConfig(config: McpHttpConfig): List<McpHttpServerConfig> {
        return config.servers.ifEmpty {
            if (config.serverUrl.isNotBlank()) {
                listOf(
                    McpHttpServerConfig(
                        id = "mcp_1",
                        serverName = config.serverName,
                        serverUrl = config.serverUrl,
                        authToken = config.authToken,
                        toolTimeoutSeconds = config.toolTimeoutSeconds,
                        insecureHttpAllowedOrigin = config.insecureHttpAllowedOrigin
                    )
                )
            } else {
                emptyList()
            }
        }
    }

    private fun normalizedAllowedOrigin(
        candidate: String?,
        endpointSecurity: McpEndpointSecurity?,
        canonicalOrigin: String?
    ): String? {
        if (endpointSecurity != McpEndpointSecurity.PRIVATE_LAN_HTTP) return null
        val raw = candidate?.trim()?.ifBlank { null } ?: return null
        val canonicalCandidate = McpEndpointPolicy.evaluate(
            rawUrl = raw,
            authToken = "",
            insecureHttpAllowedOrigin = raw
        )
        return canonicalCandidate.canonicalOrigin
            ?.takeIf { it == canonicalOrigin }
    }

    private fun defaultRuntimeStatus(
        enabled: Boolean,
        server: McpHttpServerConfig
    ): UiMcpServerRuntimeStatus {
        if (server.serverUrl.isBlank()) {
            return UiMcpServerRuntimeStatus(
                status = if (enabled) "Not connected" else "Disabled",
                phase = if (enabled) "connecting" else "disabled"
            )
        }
        val endpoint = McpEndpointPolicy.evaluate(
            rawUrl = server.serverUrl,
            authToken = server.authToken,
            insecureHttpAllowedOrigin = server.insecureHttpAllowedOrigin
        )
        val security = endpoint.security?.name?.lowercase(Locale.US)
        return when {
            !enabled -> UiMcpServerRuntimeStatus(
                status = "Disabled",
                phase = "disabled",
                endpointSecurity = security,
                insecureWarning = endpoint.warning
            )
            endpoint.requiresAction -> UiMcpServerRuntimeStatus(
                status = "Action required",
                phase = "action_required",
                detail = endpoint.message,
                endpointSecurity = security,
                insecureWarning = endpoint.warning
            )
            !endpoint.canConnect -> UiMcpServerRuntimeStatus(
                status = "Error",
                phase = "error",
                detail = endpoint.message,
                endpointSecurity = security,
                insecureWarning = endpoint.warning
            )
            else -> UiMcpServerRuntimeStatus(
                status = "Not connected",
                phase = "connecting",
                endpointSecurity = security,
                insecureWarning = endpoint.warning
            )
        }
    }

    private fun UiMcpServerConfig.toServerConfig() = McpHttpServerConfig(
        id = id,
        serverName = serverName,
        serverUrl = serverUrl,
        authToken = authToken,
        toolTimeoutSeconds = toolTimeoutSeconds.toIntOrNull()
            ?: AppLimits.DEFAULT_MCP_HTTP_TOOL_TIMEOUT_SECONDS,
        insecureHttpAllowedOrigin = insecureHttpAllowedOrigin
    )

    private fun com.palmclaw.mcp.McpServerPhase.toDisplayStatus(): String = when (this) {
        com.palmclaw.mcp.McpServerPhase.DISABLED -> "Disabled"
        com.palmclaw.mcp.McpServerPhase.ACTION_REQUIRED -> "Action required"
        com.palmclaw.mcp.McpServerPhase.CONNECTING -> "Connecting"
        com.palmclaw.mcp.McpServerPhase.READY -> "Connected"
        com.palmclaw.mcp.McpServerPhase.DEGRADED -> "Degraded"
        com.palmclaw.mcp.McpServerPhase.ERROR -> "Error"
    }
}
