package com.palmclaw.tools

import com.palmclaw.config.AppConfig

enum class BuiltInToolSettingsKind {
    None,
    SearchProvider
}

data class BuiltInToolDescriptor(
    val toolName: String,
    val displayName: String,
    val description: String,
    val category: String,
    val enabledByDefault: Boolean = true,
    val supportsSettings: Boolean = false,
    val settingsKind: BuiltInToolSettingsKind = BuiltInToolSettingsKind.None,
    val userManageable: Boolean = true
)

internal object BuiltInToolCatalog {
    private val descriptors = listOf(
        BuiltInToolDescriptor("message", "Message", "Deliver a reply to the current chat target.", "Communication", userManageable = false),
        BuiltInToolDescriptor("sessions_send", "Session Send", "Send a reply to a specific session.", "Communication", userManageable = false),
        BuiltInToolDescriptor("sessions_list", "Session List", "List available sessions.", "Sessions", userManageable = false),
        BuiltInToolDescriptor("workspace_get", "Workspace", "Inspect the current workspace root and key directories.", "Workspace", userManageable = false),
        BuiltInToolDescriptor("sessions_spawn", "Subagent", "Spawn a subagent for parallel work.", "Sessions"),
        BuiltInToolDescriptor("session_status", "Session Status", "Inspect current session channel routing.", "Sessions"),
        BuiltInToolDescriptor("session_set", "Session Set", "Update session-level channel routing.", "Sessions"),
        BuiltInToolDescriptor("runtime_get", "Runtime Get", "Read runtime limits and timeout settings.", "Runtime"),
        BuiltInToolDescriptor("runtime_set", "Runtime Set", "Update runtime limits and timeout settings.", "Runtime"),
        BuiltInToolDescriptor("heartbeat_get", "Heartbeat Get", "Read heartbeat settings.", "Automation"),
        BuiltInToolDescriptor("heartbeat_set", "Heartbeat Set", "Update heartbeat settings.", "Automation"),
        BuiltInToolDescriptor("heartbeat_trigger", "Heartbeat Trigger", "Trigger heartbeat immediately.", "Automation"),
        BuiltInToolDescriptor("cron", "Cron", "Manage scheduled jobs.", "Automation"),
        BuiltInToolDescriptor("mcp_status", "MCP Status", "Inspect MCP server status.", "Runtime"),
        BuiltInToolDescriptor("web_search", "Web Search", "Search the web using the configured search provider.", "Web", supportsSettings = true, settingsKind = BuiltInToolSettingsKind.SearchProvider),
        BuiltInToolDescriptor("web_fetch", "Web Fetch", "Fetch and extract a web page or remote document.", "Web"),
        BuiltInToolDescriptor("summarize", "Summarize", "Extract and summarize local or remote content.", "Web"),
        BuiltInToolDescriptor("weather", "Weather", "Get current weather and forecast.", "Web"),
        BuiltInToolDescriptor("list", "List", "List files and directories.", "Files"),
        BuiltInToolDescriptor("glob", "Glob", "Find files by glob pattern.", "Files"),
        BuiltInToolDescriptor("read", "Read", "Read a supported local file.", "Files"),
        BuiltInToolDescriptor("write", "Write", "Write a UTF-8 text file.", "Files"),
        BuiltInToolDescriptor("edit", "Edit", "Find and replace in a text file.", "Files"),
        BuiltInToolDescriptor("grep", "Grep", "Search text inside files.", "Files"),
        BuiltInToolDescriptor("memory_get", "Memory Get", "Read long-term memory.", "Memory"),
        BuiltInToolDescriptor("memory_set", "Memory Set", "Update long-term memory.", "Memory"),
        BuiltInToolDescriptor("memory_history", "Memory History", "Read recent session history.", "Memory"),
        BuiltInToolDescriptor("memory_search", "Memory Search", "Search session history.", "Memory"),
        BuiltInToolDescriptor("device_status", "Device Status", "Inspect device status, permissions, and location.", "Device"),
        BuiltInToolDescriptor("device", "Device", "Run device actions such as opening settings or toggles.", "Device"),
        BuiltInToolDescriptor("media", "Media", "Record, capture, and open media workflows.", "Device"),
        BuiltInToolDescriptor("bluetooth", "Bluetooth", "Inspect and manage Bluetooth state.", "Device"),
        BuiltInToolDescriptor("calendar", "Calendar", "Search and manage calendar events.", "Personal"),
        BuiltInToolDescriptor("contacts", "Contacts", "Search and manage contacts.", "Personal")
    )

    fun all(): List<BuiltInToolDescriptor> = descriptors

    fun find(toolName: String): BuiltInToolDescriptor? = descriptors.firstOrNull { it.toolName == toolName }

    fun isEnabled(config: AppConfig, toolName: String): Boolean {
        val descriptor = find(toolName) ?: return true
        if (!descriptor.userManageable) return true
        return config.toolToggles[toolName] ?: descriptor.enabledByDefault
    }

    fun forcedEnabledToolNames(): Set<String> {
        return descriptors.filterNot { it.userManageable }.mapTo(linkedSetOf()) { it.toolName }
    }
}
