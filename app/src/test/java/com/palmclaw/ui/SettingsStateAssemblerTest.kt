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
}
