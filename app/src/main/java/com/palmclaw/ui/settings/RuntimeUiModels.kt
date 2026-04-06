package com.palmclaw.ui

import com.palmclaw.config.AppLimits

/**
 * Models used by runtime, cron, and MCP settings screens.
 */
data class UiCronJob(
    val id: String,
    val name: String,
    val enabled: Boolean,
    val schedule: String,
    val nextRunAt: String?,
    val lastStatus: String?,
    val lastError: String?
)

data class UiMcpServerConfig(
    val id: String,
    val serverName: String = AppLimits.DEFAULT_MCP_HTTP_SERVER_NAME,
    val serverUrl: String = "",
    val authToken: String = "",
    val toolTimeoutSeconds: String = AppLimits.DEFAULT_MCP_HTTP_TOOL_TIMEOUT_SECONDS.toString(),
    val status: String = "Not connected",
    val usable: Boolean = false,
    val detail: String = "",
    val toolCount: Int = 0
)
