package com.palmclaw.ui

import com.palmclaw.config.AlwaysOnConfig
import com.palmclaw.config.AppConfig
import com.palmclaw.config.AppLimits
import com.palmclaw.config.ChannelsConfig
import com.palmclaw.config.CronConfig
import com.palmclaw.config.HeartbeatConfig
import com.palmclaw.config.McpHttpConfig
import com.palmclaw.config.OnboardingConfig
import com.palmclaw.config.TokenUsageStats
import com.palmclaw.config.UiPreferencesConfig
import com.palmclaw.providers.ProviderCatalog
import com.palmclaw.ui.settings.UiBuiltInToolConfig
import com.palmclaw.ui.settings.UiSkillConfig

/**
 * Centralizes projection of persisted settings and runtime summaries into [ChatUiState].
 *
 * Keeping the settings hydration logic in one pure component makes the fallback rules
 * easier to test and keeps [ChatViewModel] focused on orchestration.
 */
internal object SettingsStateAssembler {

    data class Inputs(
        val appConfig: AppConfig,
        val cronConfig: CronConfig,
        val heartbeatConfig: HeartbeatConfig,
        val channelsConfig: ChannelsConfig,
        val alwaysOnConfig: AlwaysOnConfig,
        val uiPreferencesConfig: UiPreferencesConfig,
        val onboardingConfig: OnboardingConfig,
        val mcpConfig: McpHttpConfig,
        val tokenStats: TokenUsageStats,
        val providerConfigs: List<UiProviderConfig>,
        val builtInTools: List<UiBuiltInToolConfig>,
        val installedSkills: List<UiSkillConfig>,
        val mcpServers: List<UiMcpServerConfig>,
        val cronLogs: String,
        val agentLogs: String,
        val connectedChannels: List<UiConnectedChannelSummary> = emptyList(),
        val gatewayStatuses: GatewayStatuses = GatewayStatuses()
    )

    data class GatewayStatuses(
        val discord: String = "",
        val slack: String = "",
        val feishu: String = "",
        val email: String = "",
        val wecom: String = ""
    )

    fun assemble(currentState: ChatUiState, inputs: Inputs): ChatUiState {
        val config = inputs.appConfig
        val resolvedProvider = ProviderCatalog.resolve(config.providerName)
        val resolvedProtocol = ProviderCatalog.resolveProtocol(
            rawProvider = resolvedProvider.id,
            requested = config.providerProtocol,
            baseUrl = config.baseUrl
        )
        val selectedProviderConfig = inputs.providerConfigs.firstOrNull { item ->
            item.id == config.activeProviderConfigId
        } ?: inputs.providerConfigs.firstOrNull()
        return applyMcpServerFields(
            currentState = currentState,
            enabled = inputs.mcpConfig.enabled,
            mcpServers = inputs.mcpServers
        ).copy(
            settingsProviderConfigs = inputs.providerConfigs,
            settingsEditingProviderConfigId = selectedProviderConfig?.id.orEmpty(),
            settingsProvider = selectedProviderConfig?.providerName ?: resolvedProvider.id,
            settingsProviderCustomName = selectedProviderConfig?.customName.orEmpty(),
            settingsProviderProtocol = selectedProviderConfig?.providerProtocol ?: resolvedProtocol,
            settingsModel = selectedProviderConfig?.model
                ?: config.model.ifBlank {
                    ProviderCatalog.defaultModel(resolvedProvider.id, resolvedProtocol)
                },
            settingsApiKey = selectedProviderConfig?.apiKey ?: config.apiKey,
            settingsBaseUrl = selectedProviderConfig?.let { providerConfig ->
                providerConfig.baseUrl.ifBlank {
                    ProviderCatalog.defaultBaseUrl(
                        providerConfig.providerName,
                        providerConfig.providerProtocol
                    )
                }
            } ?: config.baseUrl.ifBlank {
                ProviderCatalog.defaultBaseUrl(resolvedProvider.id, resolvedProtocol)
            },
            settingsMaxToolRounds = config.maxToolRounds.toString(),
            settingsToolResultMaxChars = config.toolResultMaxChars.toString(),
            settingsMemoryConsolidationWindow = config.memoryConsolidationWindow.toString(),
            settingsLlmCallTimeoutSeconds = config.llmCallTimeoutSeconds.toString(),
            settingsLlmConnectTimeoutSeconds = config.llmConnectTimeoutSeconds.toString(),
            settingsLlmReadTimeoutSeconds = config.llmReadTimeoutSeconds.toString(),
            settingsDefaultToolTimeoutSeconds = config.defaultToolTimeoutSeconds.toString(),
            settingsContextMessages = config.contextMessages.toString(),
            settingsToolArgsPreviewMaxChars = config.toolArgsPreviewMaxChars.toString(),
            settingsBuiltInTools = inputs.builtInTools,
            settingsInstalledSkills = inputs.installedSkills,
            settingsSearchProvider = config.searchProvider,
            settingsSearchBraveApiKey = config.searchProviderConfigs.braveApiKey,
            settingsSearchTavilyApiKey = config.searchProviderConfigs.tavilyApiKey,
            settingsSearchJinaApiKey = config.searchProviderConfigs.jinaApiKey,
            settingsSearchKagiApiKey = config.searchProviderConfigs.kagiApiKey,
            settingsCronEnabled = inputs.cronConfig.enabled,
            settingsCronMinEveryMs = inputs.cronConfig.minEveryMs.toString(),
            settingsCronMaxJobs = inputs.cronConfig.maxJobs.toString(),
            settingsTokenInput = inputs.tokenStats.inputTokens,
            settingsTokenOutput = inputs.tokenStats.outputTokens,
            settingsTokenTotal = inputs.tokenStats.totalTokens,
            settingsTokenCachedInput = inputs.tokenStats.cachedInputTokens,
            settingsTokenRequests = inputs.tokenStats.requests,
            settingsCronLogs = inputs.cronLogs,
            settingsAgentLogs = inputs.agentLogs,
            settingsHeartbeatEnabled = inputs.heartbeatConfig.enabled,
            settingsHeartbeatIntervalSeconds = inputs.heartbeatConfig.intervalSeconds.toString(),
            settingsGatewayEnabled = inputs.channelsConfig.enabled,
            settingsUseChinese = inputs.uiPreferencesConfig.useChinese,
            settingsDarkTheme = inputs.uiPreferencesConfig.darkTheme,
            onboardingCompleted = inputs.onboardingConfig.completed,
            userDisplayName = inputs.onboardingConfig.userDisplayName,
            agentDisplayName = inputs.onboardingConfig.agentDisplayName,
            onboardingUserDisplayName = inputs.onboardingConfig.userDisplayName,
            onboardingAgentDisplayName = inputs.onboardingConfig.agentDisplayName,
            alwaysOnEnabled = inputs.alwaysOnConfig.enabled,
            alwaysOnKeepScreenAwake = inputs.alwaysOnConfig.keepScreenAwake,
            settingsTelegramBotToken = inputs.channelsConfig.telegramBotToken,
            settingsTelegramAllowedChatId = inputs.channelsConfig.telegramAllowedChatId.orEmpty(),
            settingsDiscordWebhookUrl = inputs.channelsConfig.discordWebhookUrl,
            settingsConnectedChannels = inputs.connectedChannels,
            settingsDiscordGatewayStatus = inputs.gatewayStatuses.discord,
            settingsSlackGatewayStatus = inputs.gatewayStatuses.slack,
            settingsFeishuGatewayStatus = inputs.gatewayStatuses.feishu,
            settingsEmailGatewayStatus = inputs.gatewayStatuses.email,
            settingsWeComGatewayStatus = inputs.gatewayStatuses.wecom
        )
    }

    fun applyMcpServerFields(
        currentState: ChatUiState,
        enabled: Boolean,
        mcpServers: List<UiMcpServerConfig>
    ): ChatUiState {
        val primaryServer = mcpServers.firstOrNull()
        return currentState.copy(
            settingsMcpEnabled = enabled,
            settingsMcpServerName = primaryServer?.serverName ?: AppLimits.DEFAULT_MCP_HTTP_SERVER_NAME,
            settingsMcpServerUrl = primaryServer?.serverUrl.orEmpty(),
            settingsMcpAuthToken = primaryServer?.authToken.orEmpty(),
            settingsMcpToolTimeoutSeconds = primaryServer?.toolTimeoutSeconds
                ?: AppLimits.DEFAULT_MCP_HTTP_TOOL_TIMEOUT_SECONDS.toString(),
            settingsMcpServers = mcpServers
        )
    }
}
