package com.palmclaw.ui

import com.palmclaw.config.AppLimits
import com.palmclaw.config.McpHttpConfig
import com.palmclaw.config.McpHttpServerConfig
import com.palmclaw.tools.McpStatusTool
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.util.Locale

internal object McpSettingsMapper {
    fun buildConfig(state: McpSettingsState): McpHttpConfig {
        val servers = buildNormalizedServers(state)
        val duplicateNames = servers
            .groupingBy { it.serverName.trim().lowercase(Locale.US) }
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
            servers = servers
        )
    }

    fun buildUiServers(
        config: McpHttpConfig,
        runtimeStatuses: Map<String, UiMcpServerRuntimeStatus>
    ): List<UiMcpServerConfig> {
        return normalizedServersFromConfig(config).map { server ->
            val runtimeName = normalizeRuntimeServerName(server.serverName)
            val status = runtimeStatuses[runtimeName] ?: defaultRuntimeStatus(config.enabled)
            UiMcpServerConfig(
                id = server.id.ifBlank { "mcp_${server.serverName}_${server.serverUrl.hashCode()}" },
                serverName = server.serverName,
                serverUrl = server.serverUrl,
                authToken = server.authToken,
                toolTimeoutSeconds = server.toolTimeoutSeconds.toString(),
                status = status.status,
                usable = status.usable,
                detail = status.detail,
                toolCount = status.toolCount
            )
        }
    }

    fun buildStatusSnapshot(
        config: McpHttpConfig,
        runtimeStatuses: Map<String, UiMcpServerRuntimeStatus>
    ): McpStatusTool.Snapshot {
        val entries = normalizedServersFromConfig(config).map { server ->
            val normalizedName = normalizeRuntimeServerName(server.serverName)
            val status = runtimeStatuses[normalizedName] ?: defaultRuntimeStatus(config.enabled)
            McpStatusTool.Entry(
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
        return McpStatusTool.Snapshot(
            enabled = config.enabled,
            connectedServerCount = entries.count { it.status.equals("Connected", ignoreCase = true) },
            registeredToolCount = entries.sumOf { it.toolCount },
            servers = entries
        )
    }

    fun normalizeRuntimeServerName(input: String): String {
        return input.trim().lowercase(Locale.US)
            .replace(Regex("[^a-z0-9_\\-]+"), "_")
            .trim('_')
            .take(40)
            .ifBlank { AppLimits.DEFAULT_MCP_HTTP_SERVER_NAME }
    }

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
            validateEndpointUrl(url)
            McpHttpServerConfig(
                id = item.id.ifBlank { "mcp_${index + 1}" },
                serverName = name,
                serverUrl = url,
                authToken = token,
                toolTimeoutSeconds = timeout
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
                        toolTimeoutSeconds = config.toolTimeoutSeconds
                    )
                )
            } else {
                emptyList()
            }
        }
    }

    private fun validateEndpointUrl(url: String) {
        if (url.isBlank()) {
            throw IllegalArgumentException("MCP server URL is required when MCP is enabled")
        }
        val parsed = url.toHttpUrlOrNull()
            ?: throw IllegalArgumentException("MCP server URL is invalid")
        val scheme = parsed.scheme.lowercase(Locale.US)
        if (scheme != "http" && scheme != "https") {
            throw IllegalArgumentException("MCP server URL must use http or https")
        }
        if (scheme == "http" && !isLocalHost(parsed.host)) {
            throw IllegalArgumentException("Use HTTPS for non-local MCP endpoints")
        }
    }

    private fun isLocalHost(host: String): Boolean {
        val normalized = host.trim().lowercase(Locale.US).trim('[', ']')
        return normalized == "localhost" ||
            normalized == "127.0.0.1" ||
            normalized == "::1" ||
            normalized == "10.0.2.2" ||
            normalized == "10.0.3.2"
    }

    private fun defaultRuntimeStatus(enabled: Boolean): UiMcpServerRuntimeStatus {
        return if (enabled) {
            UiMcpServerRuntimeStatus(status = "Not connected")
        } else {
            UiMcpServerRuntimeStatus(status = "Disabled")
        }
    }
}
