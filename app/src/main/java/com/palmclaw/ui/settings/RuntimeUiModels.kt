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
    val insecureHttpAllowedOrigin: String? = null,
    val phase: String = "connecting",
    val status: String = "Not connected",
    val usable: Boolean = false,
    val detail: String = "",
    val toolCount: Int = 0,
    val resourceCount: Int = 0,
    val resourceTemplateCount: Int = 0,
    val promptCount: Int = 0,
    val completionSupported: Boolean = false,
    val toolNames: List<String> = emptyList(),
    val transport: String? = null,
    val protocolVersion: String? = null,
    val endpointSecurity: String? = null,
    val insecureWarning: String? = null,
    /** True while this draft differs from the persisted MCP configuration. */
    val dirty: Boolean = false
)

data class UiMcpRuntimeIssue(
    val code: String,
    val detail: String
)

data class UiMcpRuntimeSnapshot(
    val enabled: Boolean = false,
    val generation: Long = 0L,
    val issues: List<UiMcpRuntimeIssue> = emptyList()
)

internal data class UiMcpServerRuntimeStatus(
    val serverName: String = "",
    val endpoint: String = "",
    val status: String,
    val phase: String = when {
        status.equals("Connected", ignoreCase = true) -> "ready"
        status.equals("Disabled", ignoreCase = true) -> "disabled"
        status.equals("Error", ignoreCase = true) -> "error"
        else -> "connecting"
    },
    val usable: Boolean = status.equals("Connected", ignoreCase = true),
    val detail: String = "",
    val toolCount: Int = 0,
    val toolNames: List<String> = emptyList(),
    val resourceCount: Int = 0,
    val resourceTemplateCount: Int = 0,
    val promptCount: Int = 0,
    val completionSupported: Boolean = false,
    val transport: String? = null,
    val protocolVersion: String? = null,
    val endpointSecurity: String? = null,
    val insecureWarning: String? = null
)
