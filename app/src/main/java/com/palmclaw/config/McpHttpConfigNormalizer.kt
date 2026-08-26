package com.palmclaw.config

/**
 * Encapsulates legacy migration and normalization for MCP HTTP server settings.
 */
object McpHttpConfigNormalizer {
    data class LegacySettings(
        val enabled: Boolean,
        val serverName: String,
        val serverUrl: String,
        val authToken: String,
        val toolTimeoutSeconds: Int,
        val insecureHttpAllowedOrigin: String? = null
    )

    data class PersistedConfig(
        val enabled: Boolean,
        val serverName: String,
        val serverUrl: String,
        val authToken: String,
        val toolTimeoutSeconds: Int,
        val insecureHttpAllowedOrigin: String?,
        val servers: List<McpHttpServerConfig>
    )

    fun restore(
        legacy: LegacySettings,
        storedServers: List<McpHttpServerConfig>
    ): McpHttpConfig {
        val normalizedServers = normalizeServers(storedServers)
            .ifEmpty {
                if (legacy.serverUrl.isNotBlank()) {
                    listOf(
                        McpHttpServerConfig(
                            id = "mcp_1",
                            serverName = legacy.serverName.trim()
                                .ifBlank { AppLimits.DEFAULT_MCP_HTTP_SERVER_NAME },
                            serverUrl = legacy.serverUrl,
                            authToken = legacy.authToken,
                            toolTimeoutSeconds = normalizeToolTimeout(legacy.toolTimeoutSeconds),
                            insecureHttpAllowedOrigin = normalizeAllowedOrigin(
                                legacy.insecureHttpAllowedOrigin
                            )
                        )
                    )
                } else {
                    emptyList()
                }
            }
        val primary = normalizedServers.firstOrNull()
        return McpHttpConfig(
            enabled = legacy.enabled,
            serverName = primary?.serverName ?: legacy.serverName,
            serverUrl = primary?.serverUrl ?: legacy.serverUrl,
            authToken = primary?.authToken ?: legacy.authToken,
            toolTimeoutSeconds = primary?.toolTimeoutSeconds ?: normalizeToolTimeout(legacy.toolTimeoutSeconds),
            insecureHttpAllowedOrigin = primary?.insecureHttpAllowedOrigin
                ?: normalizeAllowedOrigin(legacy.insecureHttpAllowedOrigin),
            servers = normalizedServers
        )
    }

    fun prepareForSave(config: McpHttpConfig): PersistedConfig {
        val normalizedServers = normalizeServers(config.servers)
        val primary = normalizedServers.firstOrNull()
        return PersistedConfig(
            enabled = config.enabled,
            serverName = primary?.serverName ?: config.serverName,
            serverUrl = primary?.serverUrl ?: config.serverUrl,
            authToken = primary?.authToken ?: config.authToken,
            toolTimeoutSeconds = normalizeToolTimeout(
                primary?.toolTimeoutSeconds ?: config.toolTimeoutSeconds
            ),
            insecureHttpAllowedOrigin = primary?.insecureHttpAllowedOrigin
                ?: normalizeAllowedOrigin(config.insecureHttpAllowedOrigin),
            servers = normalizedServers
        )
    }

    fun normalizeServers(servers: List<McpHttpServerConfig>): List<McpHttpServerConfig> {
        return servers.mapIndexed { index, item ->
            item.copy(
                id = item.id.ifBlank { "mcp_${index + 1}" },
                serverName = item.serverName.trim().ifBlank { AppLimits.DEFAULT_MCP_HTTP_SERVER_NAME },
                toolTimeoutSeconds = normalizeToolTimeout(item.toolTimeoutSeconds),
                insecureHttpAllowedOrigin = normalizeAllowedOrigin(item.insecureHttpAllowedOrigin)
            )
        }
    }

    private fun normalizeAllowedOrigin(origin: String?): String? {
        return origin?.trim()?.ifBlank { null }
    }

    private fun normalizeToolTimeout(timeoutSeconds: Int): Int {
        return timeoutSeconds.coerceIn(
            AppLimits.MIN_MCP_HTTP_TOOL_TIMEOUT_SECONDS,
            AppLimits.MAX_MCP_HTTP_TOOL_TIMEOUT_SECONDS
        )
    }
}
