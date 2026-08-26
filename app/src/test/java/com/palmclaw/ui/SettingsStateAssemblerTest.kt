package com.palmclaw.ui

import com.palmclaw.config.AlwaysOnConfig
import com.palmclaw.config.AppConfig
import com.palmclaw.config.ChannelsConfig
import com.palmclaw.config.CronConfig
import com.palmclaw.config.HeartbeatConfig
import com.palmclaw.config.McpHttpConfig
import com.palmclaw.config.OnboardingConfig
import com.palmclaw.config.SearchProviderConfigs
import com.palmclaw.config.SearchProviderId
import com.palmclaw.config.TokenUsageStats
import com.palmclaw.config.UiPreferencesConfig
import com.palmclaw.providers.ProviderCatalog
import com.palmclaw.providers.ProviderProtocol
import com.palmclaw.ui.domain.AlwaysOnUiStatus
import com.palmclaw.tools.BuiltInToolSettingsKind
import com.palmclaw.ui.settings.UiBuiltInToolConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsStateAssemblerTest {

    @Test
    fun `assemble uses selected provider config and status summaries`() {
        val currentState = ChatUiState(
            messages = listOf(UiMessage(id = 1L, role = "user", content = "hi", createdAt = 1L)),
            currentSessionId = "session-a"
        )
        val selectedProvider = UiProviderConfig(
            id = "provider-b",
            providerName = "custom-provider",
            customName = "Primary",
            providerProtocol = ProviderProtocol.OpenAiResponses,
            apiKey = "provider-key",
            model = "gpt-enterprise",
            baseUrl = "https://provider.example.com",
            enabled = true
        )
        val assembled = SettingsStateAssembler.assemble(
            currentState = currentState,
            inputs = SettingsStateAssembler.Inputs(
                appConfig = AppConfig(
                    providerName = "openai",
                    providerProtocol = ProviderProtocol.OpenAi,
                    apiKey = "root-key",
                    model = "gpt-root",
                    baseUrl = "https://root.example.com",
                    activeProviderConfigId = "provider-b",
                    searchProvider = SearchProviderId.Brave,
                    searchProviderConfigs = SearchProviderConfigs(braveApiKey = "brave-key")
                ),
                cronConfig = CronConfig(enabled = true, minEveryMs = 15_000L, maxJobs = 9),
                heartbeatConfig = HeartbeatConfig(enabled = true, intervalSeconds = 1800L),
                channelsConfig = ChannelsConfig(
                    enabled = true,
                    telegramBotToken = "telegram-token",
                    telegramAllowedChatId = "12345",
                    discordWebhookUrl = "https://discord.example.com"
                ),
                alwaysOnConfig = AlwaysOnConfig(enabled = true, keepScreenAwake = true),
                uiPreferencesConfig = UiPreferencesConfig(useChinese = true, darkTheme = true),
                onboardingConfig = OnboardingConfig(
                    completed = true,
                    userDisplayName = "User",
                    agentDisplayName = "Agent"
                ),
                mcpConfig = McpHttpConfig(enabled = true),
                tokenStats = TokenUsageStats(
                    inputTokens = 10L,
                    outputTokens = 20L,
                    totalTokens = 30L,
                    cachedInputTokens = 4L,
                    requests = 2L
                ),
                providerConfigs = listOf(
                    UiProviderConfig(id = "provider-a", providerName = "other"),
                    selectedProvider
                ),
                builtInTools = listOf(
                    UiBuiltInToolConfig(
                        toolName = "web_search",
                        displayName = "Web Search",
                        description = "Search the web",
                        category = "Web",
                        enabled = true,
                        enabledByDefault = true,
                        supportsSettings = true,
                        settingsKind = BuiltInToolSettingsKind.SearchProvider
                    )
                ),
                installedSkills = emptyList(),
                mcpServers = listOf(
                    UiMcpServerConfig(
                        id = "mcp-1",
                        serverName = "primary",
                        serverUrl = "https://mcp.example.com",
                        authToken = "secret",
                        toolTimeoutSeconds = "45"
                    )
                ),
                cronLogs = "cron log",
                agentLogs = "agent log",
                connectedChannels = listOf(
                    UiConnectedChannelSummary(
                        sessionId = "session-a",
                        sessionTitle = "Session A",
                        channel = "telegram",
                        chatId = "12345",
                        enabled = true,
                        status = "Connected"
                    )
                ),
                gatewayStatuses = SettingsStateAssembler.GatewayStatuses(
                    discord = "discord ok",
                    slack = "slack ok",
                    feishu = "feishu ok",
                    email = "email ok",
                    wecom = "wecom ok"
                )
            )
        )

        assertEquals("provider-b", assembled.settingsEditingProviderConfigId)
        assertEquals("custom-provider", assembled.settingsProvider)
        assertEquals("Primary", assembled.settingsProviderCustomName)
        assertEquals(ProviderProtocol.OpenAiResponses, assembled.settingsProviderProtocol)
        assertEquals("gpt-enterprise", assembled.settingsModel)
        assertEquals("provider-key", assembled.settingsApiKey)
        assertEquals("https://provider.example.com", assembled.settingsBaseUrl)
        assertEquals(SearchProviderId.Brave, assembled.settingsSearchProvider)
        assertEquals("brave-key", assembled.settingsSearchBraveApiKey)
        assertEquals(1, assembled.settingsBuiltInTools.size)
        assertEquals("primary", assembled.settingsMcpServerName)
        assertEquals("https://mcp.example.com", assembled.settingsMcpServerUrl)
        assertEquals("secret", assembled.settingsMcpAuthToken)
        assertEquals("45", assembled.settingsMcpToolTimeoutSeconds)
        assertEquals("discord ok", assembled.settingsDiscordGatewayStatus)
        assertEquals("wecom ok", assembled.settingsWeComGatewayStatus)
        assertTrue(assembled.settingsConnectedChannels.isNotEmpty())
        assertTrue(assembled.messages.isNotEmpty())
        assertEquals("session-a", assembled.currentSessionId)
    }

    @Test
    fun `assembleSlices projects settings without erasing live always on status`() {
        val currentShell = SettingsShellState(saving = true, info = "keep info")
        val runtimeStatus = AlwaysOnUiStatus(
            desired = true,
            phase = AlwaysOnUiStatus.Phase.ONLINE,
            shell = AlwaysOnUiStatus.LifecycleState.RUNNING,
            notificationVisible = true,
            runtime = AlwaysOnUiStatus.LifecycleState.RUNNING,
            gateway = AlwaysOnUiStatus.LifecycleState.RUNNING,
            network = AlwaysOnUiStatus.NetworkState.ONLINE,
            channels = AlwaysOnUiStatus.ChannelCounts(configured = 1, ready = 1),
            processingSessionIds = setOf("active-turn")
        )
        val currentAlwaysOn = AlwaysOnSettingsState(
            enabled = false,
            runtimeStatus = runtimeStatus,
            charging = true,
            batteryOptimizationIgnored = true
        )
        val selectedProvider = UiProviderConfig(
            id = "provider-b",
            providerName = "custom-provider",
            customName = "Primary",
            providerProtocol = ProviderProtocol.OpenAiResponses,
            apiKey = "provider-key",
            model = "gpt-enterprise",
            baseUrl = "https://provider.example.com",
            enabled = true
        )
        val slices = SettingsStateAssembler.assembleSlices(
            currentShell = currentShell,
            currentAlwaysOn = currentAlwaysOn,
            currentMcp = McpSettingsState(),
            inputs = SettingsStateAssembler.Inputs(
                appConfig = AppConfig(
                    providerName = "openai",
                    providerProtocol = ProviderProtocol.OpenAi,
                    apiKey = "root-key",
                    model = "gpt-root",
                    baseUrl = "https://root.example.com",
                    activeProviderConfigId = "provider-b",
                    searchProvider = SearchProviderId.Brave,
                    searchProviderConfigs = SearchProviderConfigs(braveApiKey = "brave-key")
                ),
                cronConfig = CronConfig(enabled = true, minEveryMs = 15_000L, maxJobs = 9),
                heartbeatConfig = HeartbeatConfig(enabled = true, intervalSeconds = 1800L),
                channelsConfig = ChannelsConfig(
                    enabled = true,
                    telegramBotToken = "telegram-token",
                    telegramAllowedChatId = "12345",
                    discordWebhookUrl = "https://discord.example.com"
                ),
                alwaysOnConfig = AlwaysOnConfig(enabled = true, keepScreenAwake = true),
                uiPreferencesConfig = UiPreferencesConfig(useChinese = true, darkTheme = true),
                onboardingConfig = OnboardingConfig(
                    completed = true,
                    userDisplayName = "User",
                    agentDisplayName = "Agent"
                ),
                mcpConfig = McpHttpConfig(enabled = true),
                tokenStats = TokenUsageStats(inputTokens = 10L, outputTokens = 20L, totalTokens = 30L),
                providerConfigs = listOf(selectedProvider),
                builtInTools = emptyList(),
                installedSkills = emptyList(),
                mcpServers = emptyList(),
                cronLogs = "cron log",
                agentLogs = "agent log",
                connectedChannels = listOf(
                    UiConnectedChannelSummary(
                        sessionId = "session-a",
                        sessionTitle = "Session A",
                        channel = "telegram",
                        chatId = "12345",
                        enabled = true,
                        status = "Connected"
                    )
                )
            )
        )

        assertEquals("custom-provider", slices.provider.provider)
        assertEquals("provider-key", slices.provider.apiKeyDraft)
        assertEquals("20", slices.tool.maxToolRounds)
        assertEquals(true, slices.automation.cronEnabled)
        assertEquals(true, slices.alwaysOn.enabled)
        assertEquals(true, slices.mcp.enabled)
        assertEquals(true, slices.channels.gatewayEnabled)
        assertEquals("telegram-token", slices.channels.telegramBotToken)
        assertEquals(runtimeStatus, slices.alwaysOn.runtimeStatus)
        assertTrue(slices.alwaysOn.runtimeStatus.notificationVisible)
        assertEquals(setOf("active-turn"), slices.alwaysOn.runtimeStatus.processingSessionIds)
        assertTrue(slices.alwaysOn.charging)
        assertTrue(slices.alwaysOn.batteryOptimizationIgnored)
        assertEquals("12345", slices.channels.telegramAllowedChatId)
        assertEquals("https://discord.example.com", slices.channels.discordWebhookUrl)
        assertEquals(true, slices.onboarding.completed)
        assertEquals("User", slices.identity.userDisplayName)
        assertEquals(true, slices.settingsShell.useChinese)
        assertEquals(true, slices.settingsShell.darkTheme)
        assertEquals(true, slices.settingsShell.saving)
        assertEquals("keep info", slices.settingsShell.info)
    }

    @Test
    fun `assembleSlices reloads persisted mcp fields without erasing runtime snapshot`() {
        val runtimeSnapshot = UiMcpRuntimeSnapshot(
            enabled = true,
            generation = 17L,
            issues = listOf(
                UiMcpRuntimeIssue(
                    code = "server_unavailable",
                    detail = "Primary server is reconnecting"
                )
            )
        )
        val slices = SettingsStateAssembler.assembleSlices(
            currentShell = SettingsShellState(),
            currentAlwaysOn = AlwaysOnSettingsState(),
            currentMcp = McpSettingsState(runtimeSnapshot = runtimeSnapshot),
            inputs = SettingsStateAssembler.Inputs(
                appConfig = minimalAppConfig(),
                cronConfig = disabledCronConfig(),
                heartbeatConfig = disabledHeartbeatConfig(),
                channelsConfig = disabledChannelsConfig(),
                alwaysOnConfig = AlwaysOnConfig(),
                uiPreferencesConfig = UiPreferencesConfig(),
                onboardingConfig = OnboardingConfig(),
                mcpConfig = McpHttpConfig(enabled = false),
                tokenStats = TokenUsageStats(),
                providerConfigs = emptyList(),
                builtInTools = emptyList(),
                installedSkills = emptyList(),
                mcpServers = listOf(
                    UiMcpServerConfig(
                        id = "mcp-primary",
                        serverName = "updated-primary",
                        serverUrl = "https://updated.example.com/mcp",
                        authToken = "updated-token",
                        toolTimeoutSeconds = "45"
                    )
                ),
                cronLogs = "",
                agentLogs = ""
            )
        )

        assertEquals(false, slices.mcp.enabled)
        assertEquals("updated-primary", slices.mcp.serverName)
        assertEquals("https://updated.example.com/mcp", slices.mcp.serverUrl)
        assertEquals("updated-token", slices.mcp.authToken)
        assertEquals("45", slices.mcp.toolTimeoutSeconds)
        assertEquals(runtimeSnapshot, slices.mcp.runtimeSnapshot)
        assertEquals(true, slices.mcp.runtimeSnapshot.enabled)
        assertEquals(17L, slices.mcp.runtimeSnapshot.generation)
        assertEquals("server_unavailable", slices.mcp.runtimeSnapshot.issues.single().code)
    }

    @Test
    fun `assembleSlices preserves the complete unsaved mcp draft during settings hydration`() {
        val runtimeSnapshot = UiMcpRuntimeSnapshot(
            enabled = true,
            generation = 23L,
            issues = listOf(
                UiMcpRuntimeIssue(
                    code = "server_reconnecting",
                    detail = "Draft server is reconnecting"
                )
            )
        )
        val draftServer = UiMcpServerConfig(
            id = "draft-server",
            serverName = "draft-name",
            serverUrl = "http://192.168.1.10:8080/mcp?workspace=draft",
            authToken = "draft-token",
            toolTimeoutSeconds = "75",
            insecureHttpAllowedOrigin = "http://192.168.1.10:8080",
            phase = "ready",
            status = "Connected",
            usable = true,
            detail = "Draft runtime detail",
            toolCount = 4,
            resourceCount = 3,
            resourceTemplateCount = 2,
            promptCount = 1,
            completionSupported = true,
            toolNames = listOf("draft_tool"),
            transport = "streamable_http",
            protocolVersion = "2025-11-25",
            endpointSecurity = "private_http_approved",
            insecureWarning = "Unencrypted private network connection",
            dirty = true
        )
        val currentMcp = McpSettingsState(
            enabled = true,
            serverName = "draft-name",
            serverUrl = draftServer.serverUrl,
            authToken = "draft-token",
            toolTimeoutSeconds = "75",
            servers = listOf(draftServer),
            runtimeSnapshot = runtimeSnapshot,
            hasUnsavedChanges = true,
            useChinese = false
        )

        val slices = SettingsStateAssembler.assembleSlices(
            currentShell = SettingsShellState(info = "fresh info"),
            currentAlwaysOn = AlwaysOnSettingsState(),
            currentMcp = currentMcp,
            inputs = SettingsStateAssembler.Inputs(
                appConfig = minimalAppConfig(),
                cronConfig = disabledCronConfig(),
                heartbeatConfig = disabledHeartbeatConfig(),
                channelsConfig = disabledChannelsConfig(),
                alwaysOnConfig = AlwaysOnConfig(),
                uiPreferencesConfig = UiPreferencesConfig(useChinese = true),
                onboardingConfig = OnboardingConfig(),
                mcpConfig = McpHttpConfig(enabled = false),
                tokenStats = TokenUsageStats(),
                providerConfigs = emptyList(),
                builtInTools = emptyList(),
                installedSkills = emptyList(),
                mcpServers = listOf(
                    UiMcpServerConfig(
                        id = "persisted-server",
                        serverName = "persisted-name",
                        serverUrl = "https://persisted.example.com/mcp",
                        authToken = "persisted-token",
                        toolTimeoutSeconds = "30"
                    )
                ),
                cronLogs = "",
                agentLogs = ""
            )
        )

        assertEquals(true, slices.mcp.enabled)
        assertEquals("draft-name", slices.mcp.serverName)
        assertEquals(draftServer.serverUrl, slices.mcp.serverUrl)
        assertEquals("draft-token", slices.mcp.authToken)
        assertEquals("75", slices.mcp.toolTimeoutSeconds)
        assertEquals(listOf(draftServer), slices.mcp.servers)
        assertEquals(runtimeSnapshot, slices.mcp.runtimeSnapshot)
        assertEquals(true, slices.mcp.hasUnsavedChanges)
        assertEquals(true, slices.mcp.useChinese)
        assertEquals("fresh info", slices.settingsShell.info)
        assertEquals(true, slices.settingsShell.useChinese)
    }

    @Test
    fun `successful mcp save acknowledgement lets hydration load persisted normalized config`() {
        val runtimeSnapshot = UiMcpRuntimeSnapshot(
            enabled = true,
            generation = 31L
        )
        val dirtyDraft = McpSettingsState(
            enabled = true,
            serverName = " Draft Name ",
            serverUrl = "HTTP://192.168.1.10:80/mcp",
            authToken = "draft-token",
            toolTimeoutSeconds = "060",
            servers = listOf(
                UiMcpServerConfig(
                    id = "server-a",
                    serverName = " Draft Name ",
                    serverUrl = "HTTP://192.168.1.10:80/mcp",
                    authToken = "draft-token",
                    toolTimeoutSeconds = "060",
                    insecureHttpAllowedOrigin = "HTTP://192.168.1.10:80",
                    dirty = true
                )
            ),
            runtimeSnapshot = runtimeSnapshot,
            hasUnsavedChanges = true
        )

        val acknowledged = SettingsStateAssembler.acknowledgeMcpSave(dirtyDraft)
        val slices = SettingsStateAssembler.assembleSlices(
            currentShell = SettingsShellState(),
            currentAlwaysOn = AlwaysOnSettingsState(),
            currentMcp = acknowledged,
            inputs = SettingsStateAssembler.Inputs(
                appConfig = minimalAppConfig(),
                cronConfig = disabledCronConfig(),
                heartbeatConfig = disabledHeartbeatConfig(),
                channelsConfig = disabledChannelsConfig(),
                alwaysOnConfig = AlwaysOnConfig(),
                uiPreferencesConfig = UiPreferencesConfig(),
                onboardingConfig = OnboardingConfig(),
                mcpConfig = McpHttpConfig(enabled = true),
                tokenStats = TokenUsageStats(),
                providerConfigs = emptyList(),
                builtInTools = emptyList(),
                installedSkills = emptyList(),
                mcpServers = listOf(
                    UiMcpServerConfig(
                        id = "server-a",
                        serverName = "Draft Name",
                        serverUrl = "http://192.168.1.10/mcp",
                        authToken = "persisted-token",
                        toolTimeoutSeconds = "60",
                        insecureHttpAllowedOrigin = "http://192.168.1.10"
                    )
                ),
                cronLogs = "",
                agentLogs = ""
            )
        )

        assertEquals(false, acknowledged.hasUnsavedChanges)
        assertEquals(false, acknowledged.servers.single().dirty)
        assertEquals("Draft Name", slices.mcp.serverName)
        assertEquals("http://192.168.1.10/mcp", slices.mcp.serverUrl)
        assertEquals("persisted-token", slices.mcp.authToken)
        assertEquals("60", slices.mcp.toolTimeoutSeconds)
        assertEquals("http://192.168.1.10", slices.mcp.servers.single().insecureHttpAllowedOrigin)
        assertEquals(false, slices.mcp.hasUnsavedChanges)
        assertEquals(false, slices.mcp.servers.single().dirty)
        assertEquals(runtimeSnapshot, slices.mcp.runtimeSnapshot)
    }

    @Test
    fun `assemble falls back to root config defaults when no provider config is selected`() {
        val assembled = SettingsStateAssembler.assemble(
            currentState = ChatUiState(),
            inputs = SettingsStateAssembler.Inputs(
                appConfig = AppConfig(
                    providerName = "openai",
                    providerProtocol = ProviderProtocol.OpenAi,
                    apiKey = "root-key",
                    model = "",
                    baseUrl = ""
                ),
                cronConfig = CronConfig(enabled = false, minEveryMs = 60_000L, maxJobs = 5),
                heartbeatConfig = HeartbeatConfig(enabled = false, intervalSeconds = 120L),
                channelsConfig = ChannelsConfig(
                    enabled = false,
                    telegramBotToken = "",
                    telegramAllowedChatId = null,
                    discordWebhookUrl = ""
                ),
                alwaysOnConfig = AlwaysOnConfig(),
                uiPreferencesConfig = UiPreferencesConfig(),
                onboardingConfig = OnboardingConfig(),
                mcpConfig = McpHttpConfig(),
                tokenStats = TokenUsageStats(),
                providerConfigs = emptyList(),
                builtInTools = emptyList(),
                installedSkills = emptyList(),
                mcpServers = emptyList(),
                cronLogs = "",
                agentLogs = ""
            )
        )

        assertEquals("", assembled.settingsEditingProviderConfigId)
        assertEquals("openai", assembled.settingsProvider)
        assertEquals("root-key", assembled.settingsApiKey)
        assertEquals(SearchProviderId.DuckDuckGo, assembled.settingsSearchProvider)
        assertEquals(
            ProviderCatalog.defaultModel("openai", ProviderProtocol.OpenAi),
            assembled.settingsModel
        )
        assertEquals(
            ProviderCatalog.defaultBaseUrl("openai", ProviderProtocol.OpenAi),
            assembled.settingsBaseUrl
        )
    }

    @Test
    fun `applyMcpServerFields falls back to defaults when server list is empty`() {
        val currentState = ChatUiState(
            settingsMcpServerName = "stale",
            settingsMcpServerUrl = "https://old.example.com",
            settingsMcpAuthToken = "old"
        )

        val updated = SettingsStateAssembler.applyMcpServerFields(
            currentState = currentState,
            enabled = false,
            mcpServers = emptyList()
        )

        assertEquals("default", updated.settingsMcpServerName)
        assertEquals("", updated.settingsMcpServerUrl)
        assertEquals("", updated.settingsMcpAuthToken)
        assertEquals("30", updated.settingsMcpToolTimeoutSeconds)
        assertTrue(updated.settingsMcpServers.isEmpty())
    }

    private fun minimalAppConfig() = AppConfig(
        providerName = "openai",
        apiKey = "",
        model = ""
    )

    private fun disabledCronConfig() = CronConfig(
        enabled = false,
        minEveryMs = 60_000L,
        maxJobs = 5
    )

    private fun disabledHeartbeatConfig() = HeartbeatConfig(
        enabled = false,
        intervalSeconds = 120L
    )

    private fun disabledChannelsConfig() = ChannelsConfig(
        enabled = false,
        telegramBotToken = "",
        telegramAllowedChatId = null,
        discordWebhookUrl = ""
    )
}
