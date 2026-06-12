package com.palmclaw.ui

import com.palmclaw.config.AppLimits
import com.palmclaw.config.AppSession
import com.palmclaw.providers.ProviderCatalog
import com.palmclaw.providers.ProviderProtocol
import com.palmclaw.config.SearchProviderId
import com.palmclaw.ui.settings.UiBuiltInToolConfig
import com.palmclaw.ui.settings.UiClawHubSkillCard
import com.palmclaw.ui.settings.UiClawHubSkillDetail
import com.palmclaw.ui.settings.UiSkillConfig
import com.palmclaw.ui.settings.UiSkillDownloadStatus
import com.palmclaw.ui.settings.UiStagedSkillReview

data class ChatContentState(
    val messages: List<UiMessage> = emptyList(),
    val messagesLoading: Boolean = false,
    val messagesLoadingOlder: Boolean = false,
    val canLoadOlderMessages: Boolean = false,
    val input: String = "",
    val composerAttachments: List<UiComposerAttachmentDraft> = emptyList(),
    val composerImporting: Boolean = false,
    val composerAttachmentError: String? = null,
    val isGenerating: Boolean = false,
    val currentSessionId: String = AppSession.LOCAL_SESSION_ID,
    val currentSessionTitle: String = AppSession.LOCAL_SESSION_TITLE,
    val sessions: List<UiSessionSummary> = emptyList()
)

data class OnboardingUiState(
    val completed: Boolean = false,
    val useChinese: Boolean = false,
    val userDisplayName: String = "",
    val agentDisplayName: String = "PalmClaw",
    val onboardingUserDisplayName: String = "",
    val onboardingAgentDisplayName: String = "PalmClaw",
    val provider: String = AppLimits.DEFAULT_PROVIDER,
    val providerCustomName: String = "",
    val providerProtocol: ProviderProtocol = ProviderCatalog.defaultProtocol(AppLimits.DEFAULT_PROVIDER),
    val baseUrl: String = ProviderCatalog.defaultBaseUrl(
        AppLimits.DEFAULT_PROVIDER,
        ProviderCatalog.defaultProtocol(AppLimits.DEFAULT_PROVIDER)
    ),
    val model: String = ProviderCatalog.defaultModel(
        AppLimits.DEFAULT_PROVIDER,
        ProviderCatalog.defaultProtocol(AppLimits.DEFAULT_PROVIDER)
    ),
    val apiKey: String = "",
    val providerTesting: Boolean = false,
    val saving: Boolean = false,
    val info: String? = null
)

data class SettingsShellState(
    val useChinese: Boolean = false,
    val darkTheme: Boolean = false,
    val saving: Boolean = false,
    val info: String? = null
)

data class IdentityDisplayState(
    val useChinese: Boolean = false,
    val userDisplayName: String = "",
    val agentDisplayName: String = "PalmClaw"
)

data class ProviderSettingsState(
    val providerConfigs: List<UiProviderConfig> = emptyList(),
    val editingProviderConfigId: String = "",
    val provider: String = AppLimits.DEFAULT_PROVIDER,
    val providerCustomName: String = "",
    val providerProtocol: ProviderProtocol = ProviderCatalog.defaultProtocol(AppLimits.DEFAULT_PROVIDER),
    val baseUrl: String = ProviderCatalog.defaultBaseUrl(
        AppLimits.DEFAULT_PROVIDER,
        ProviderCatalog.defaultProtocol(AppLimits.DEFAULT_PROVIDER)
    ),
    val model: String = ProviderCatalog.defaultModel(
        AppLimits.DEFAULT_PROVIDER,
        ProviderCatalog.defaultProtocol(AppLimits.DEFAULT_PROVIDER)
    ),
    val apiKeyDraft: String = "",
    val tokenInput: Long = 0L,
    val tokenOutput: Long = 0L,
    val tokenTotal: Long = 0L,
    val tokenCachedInput: Long = 0L,
    val tokenRequests: Long = 0L,
    val providerTesting: Boolean = false,
    val saving: Boolean = false,
    val info: String? = null,
    val useChinese: Boolean = false
)

data class ChannelsSettingsState(
    val sessions: List<UiChannelSessionRoute> = emptyList(),
    val connectedChannels: List<UiConnectedChannelSummary> = emptyList(),
    val gatewayEnabled: Boolean = false,
    val telegramBotToken: String = "",
    val telegramAllowedChatId: String = "",
    val discordWebhookUrl: String = "",
    val discordGatewayStatus: String = "",
    val slackGatewayStatus: String = "",
    val feishuGatewayStatus: String = "",
    val emailGatewayStatus: String = "",
    val weComGatewayStatus: String = "",
    val useChinese: Boolean = false
) {
    override fun toString(): String {
        return "ChannelsSettingsState(" +
            "sessions=$sessions, " +
            "connectedChannels=$connectedChannels, " +
            "gatewayEnabled=$gatewayEnabled, " +
            "telegramBotToken=<redacted>, " +
            "telegramAllowedChatId=$telegramAllowedChatId, " +
            "discordWebhookUrl=<redacted>, " +
            "discordGatewayStatus=$discordGatewayStatus, " +
            "slackGatewayStatus=$slackGatewayStatus, " +
            "feishuGatewayStatus=$feishuGatewayStatus, " +
            "emailGatewayStatus=$emailGatewayStatus, " +
            "weComGatewayStatus=$weComGatewayStatus, " +
            "useChinese=$useChinese)"
    }
}

data class UiChannelSessionRoute(
    val id: String,
    val title: String,
    val boundEnabled: Boolean,
    val boundChannel: String,
    val boundChatId: String,
    val pendingDetection: Boolean
)

data class SkillsDiscoveryState(
    val installedSkills: List<UiSkillConfig> = emptyList(),
    val selectedSkillName: String = "",
    val selectedSkillDetail: UiSkillConfig? = null,
    val clawHubStaffPicks: List<UiClawHubSkillCard> = emptyList(),
    val clawHubPopular: List<UiClawHubSkillCard> = emptyList(),
    val clawHubSearchQuery: String = "",
    val clawHubSearchedQuery: String = "",
    val clawHubSearchResults: List<UiClawHubSkillCard> = emptyList(),
    val selectedClawHubDetail: UiClawHubSkillDetail? = null,
    val stagedSkillReview: UiStagedSkillReview? = null,
    val downloadStatus: UiSkillDownloadStatus? = null,
    val skillsLoading: Boolean = false,
    val clawHubLoading: Boolean = false,
    val skillActionInFlight: Boolean = false
) {
    val builtInLocalSkills: List<UiSkillConfig>
        get() = installedSkills.filterNot { it.source == com.palmclaw.skills.SkillSource.ClawHub }

    val clawHubInstalledSkills: List<UiSkillConfig>
        get() = installedSkills.filter { it.source == com.palmclaw.skills.SkillSource.ClawHub }
}

data class ToolSettingsState(
    val builtInTools: List<UiBuiltInToolConfig> = emptyList(),
    val searchProvider: SearchProviderId = SearchProviderId.DuckDuckGo,
    val searchBraveApiKey: String = "",
    val searchTavilyApiKey: String = "",
    val searchJinaApiKey: String = "",
    val searchKagiApiKey: String = "",
    val maxToolRounds: String = AppLimits.DEFAULT_MAX_TOOL_ROUNDS.toString(),
    val toolResultMaxChars: String = AppLimits.DEFAULT_TOOL_RESULT_MAX_CHARS.toString(),
    val memoryConsolidationWindow: String = AppLimits.DEFAULT_MEMORY_CONSOLIDATION_WINDOW.toString(),
    val llmCallTimeoutSeconds: String = AppLimits.DEFAULT_LLM_CALL_TIMEOUT_SECONDS.toString(),
    val llmConnectTimeoutSeconds: String = AppLimits.DEFAULT_LLM_CONNECT_TIMEOUT_SECONDS.toString(),
    val llmReadTimeoutSeconds: String = AppLimits.DEFAULT_LLM_READ_TIMEOUT_SECONDS.toString(),
    val defaultToolTimeoutSeconds: String = AppLimits.DEFAULT_TOOL_TIMEOUT_SECONDS.toString(),
    val contextMessages: String = AppLimits.DEFAULT_CONTEXT_MESSAGES.toString(),
    val toolArgsPreviewMaxChars: String = AppLimits.DEFAULT_TOOL_ARGS_PREVIEW_MAX_CHARS.toString(),
    val agentLogs: String = "",
    val useChinese: Boolean = false
)

data class AutomationSettingsState(
    val cronEnabled: Boolean = false,
    val cronMinEveryMs: String = AppLimits.DEFAULT_CRON_MIN_EVERY_MS.toString(),
    val cronMaxJobs: String = AppLimits.DEFAULT_CRON_MAX_JOBS.toString(),
    val cronJobs: List<UiCronJob> = emptyList(),
    val cronJobsLoading: Boolean = false,
    val cronLogs: String = "",
    val heartbeatEnabled: Boolean = false,
    val heartbeatIntervalSeconds: String = AppLimits.DEFAULT_HEARTBEAT_INTERVAL_SECONDS.toString(),
    val heartbeatDoc: String = "",
    val saving: Boolean = false,
    val useChinese: Boolean = false
)

data class AlwaysOnSettingsState(
    val enabled: Boolean = false,
    val keepScreenAwake: Boolean = false,
    val serviceRunning: Boolean = false,
    val notificationActive: Boolean = false,
    val gatewayRunning: Boolean = false,
    val networkConnected: Boolean = false,
    val charging: Boolean = false,
    val batteryOptimizationIgnored: Boolean = false,
    val exactAlarmAllowed: Boolean = false,
    val activeAdapterCount: Int = 0,
    val startedAtMs: Long = 0L,
    val lastError: String = "",
    val info: String? = null,
    val useChinese: Boolean = false
)

data class McpSettingsState(
    val enabled: Boolean = false,
    val serverName: String = AppLimits.DEFAULT_MCP_HTTP_SERVER_NAME,
    val serverUrl: String = "",
    val authToken: String = "",
    val toolTimeoutSeconds: String = AppLimits.DEFAULT_MCP_HTTP_TOOL_TIMEOUT_SECONDS.toString(),
    val servers: List<UiMcpServerConfig> = emptyList(),
    val useChinese: Boolean = false
)

data class UpdateSettingsState(
    val checking: Boolean = false,
    val available: Boolean = false,
    val promptVisible: Boolean = false,
    val noticeVisible: Boolean = false,
    val noticeTitle: String = "",
    val noticeMessage: String = "",
    val noticeActionLabel: String = "",
    val noticeActionUrl: String = "",
    val currentVersion: String = "",
    val latestVersion: String = "",
    val releaseUrl: String = "",
    val downloadUrl: String = "",
    val useChinese: Boolean = false
)

data class SessionBindingState(
    val telegramDiscovering: Boolean = false,
    val telegramDiscoveryAttempted: Boolean = false,
    val telegramCandidates: List<UiTelegramChatCandidate> = emptyList(),
    val telegramInfo: String? = null,
    val feishuDiscovering: Boolean = false,
    val feishuDiscoveryAttempted: Boolean = false,
    val feishuCandidates: List<UiFeishuChatCandidate> = emptyList(),
    val feishuInfo: String? = null,
    val emailDiscovering: Boolean = false,
    val emailDiscoveryAttempted: Boolean = false,
    val emailCandidates: List<UiEmailSenderCandidate> = emptyList(),
    val emailInfo: String? = null,
    val weComDiscovering: Boolean = false,
    val weComDiscoveryAttempted: Boolean = false,
    val weComCandidates: List<UiWeComChatCandidate> = emptyList(),
    val weComInfo: String? = null,
    val useChinese: Boolean = false
)

fun ChatUiState.toChatContentState(): ChatContentState {
    return ChatContentState(
        messages = messages,
        messagesLoading = messagesLoading,
        messagesLoadingOlder = messagesLoadingOlder,
        canLoadOlderMessages = canLoadOlderMessages,
        input = input,
        composerAttachments = composerAttachments,
        composerImporting = composerImporting,
        composerAttachmentError = composerAttachmentError,
        isGenerating = isGenerating,
        currentSessionId = currentSessionId,
        currentSessionTitle = currentSessionTitle,
        sessions = sessions
    )
}

fun ChatUiState.toOnboardingUiState(): OnboardingUiState {
    return OnboardingUiState(
        completed = onboardingCompleted,
        useChinese = settingsUseChinese,
        userDisplayName = userDisplayName,
        agentDisplayName = agentDisplayName,
        onboardingUserDisplayName = onboardingUserDisplayName,
        onboardingAgentDisplayName = onboardingAgentDisplayName,
        provider = settingsProvider,
        providerCustomName = settingsProviderCustomName,
        providerProtocol = settingsProviderProtocol,
        baseUrl = settingsBaseUrl,
        model = settingsModel,
        apiKey = settingsApiKey,
        providerTesting = settingsProviderTesting,
        saving = settingsSaving,
        info = settingsInfo
    )
}

fun ChatUiState.toSettingsShellState(): SettingsShellState {
    return SettingsShellState(
        useChinese = settingsUseChinese,
        darkTheme = settingsDarkTheme,
        saving = settingsSaving,
        info = settingsInfo
    )
}

fun ChatUiState.toIdentityDisplayState(): IdentityDisplayState {
    return IdentityDisplayState(
        useChinese = settingsUseChinese,
        userDisplayName = userDisplayName,
        agentDisplayName = agentDisplayName
    )
}

fun ChatUiState.toProviderSettingsState(): ProviderSettingsState {
    return ProviderSettingsState(
        providerConfigs = settingsProviderConfigs,
        editingProviderConfigId = settingsEditingProviderConfigId,
        provider = settingsProvider,
        providerCustomName = settingsProviderCustomName,
        providerProtocol = settingsProviderProtocol,
        baseUrl = settingsBaseUrl,
        model = settingsModel,
        apiKeyDraft = settingsApiKey,
        tokenInput = settingsTokenInput,
        tokenOutput = settingsTokenOutput,
        tokenTotal = settingsTokenTotal,
        tokenCachedInput = settingsTokenCachedInput,
        tokenRequests = settingsTokenRequests,
        providerTesting = settingsProviderTesting,
        saving = settingsSaving,
        info = settingsInfo,
        useChinese = settingsUseChinese
    )
}

fun ChatUiState.toChannelsSettingsState(): ChannelsSettingsState {
    return ChannelsSettingsState(
        sessions = sessions
            .filterNot { it.isLocal }
            .map { session ->
                UiChannelSessionRoute(
                    id = session.id,
                    title = session.title,
                    boundEnabled = session.boundEnabled,
                    boundChannel = session.boundChannel,
                    boundChatId = session.boundChatId,
                    pendingDetection = session.hasPendingChannelDetection()
                )
            },
        connectedChannels = settingsConnectedChannels,
        gatewayEnabled = settingsGatewayEnabled,
        telegramBotToken = settingsTelegramBotToken,
        telegramAllowedChatId = settingsTelegramAllowedChatId,
        discordWebhookUrl = settingsDiscordWebhookUrl,
        discordGatewayStatus = settingsDiscordGatewayStatus,
        slackGatewayStatus = settingsSlackGatewayStatus,
        feishuGatewayStatus = settingsFeishuGatewayStatus,
        emailGatewayStatus = settingsEmailGatewayStatus,
        weComGatewayStatus = settingsWeComGatewayStatus,
        useChinese = settingsUseChinese
    )
}

fun ChatUiState.toSkillsDiscoveryState(): SkillsDiscoveryState {
    return SkillsDiscoveryState(
        installedSkills = settingsInstalledSkills,
        selectedSkillName = settingsSelectedSkillName,
        selectedSkillDetail = settingsSelectedSkillDetail,
        clawHubStaffPicks = settingsClawHubStaffPicks,
        clawHubPopular = settingsClawHubPopular,
        clawHubSearchQuery = settingsClawHubSearchQuery,
        clawHubSearchedQuery = settingsClawHubSearchedQuery,
        clawHubSearchResults = settingsClawHubSearchResults,
        selectedClawHubDetail = settingsSelectedClawHubDetail,
        stagedSkillReview = settingsStagedSkillReview,
        downloadStatus = settingsSkillDownloadStatus,
        skillsLoading = settingsSkillsLoading,
        clawHubLoading = settingsClawHubLoading,
        skillActionInFlight = settingsSkillActionInFlight
    )
}

fun ChatUiState.toToolSettingsState(): ToolSettingsState {
    return ToolSettingsState(
        builtInTools = settingsBuiltInTools,
        searchProvider = settingsSearchProvider,
        searchBraveApiKey = settingsSearchBraveApiKey,
        searchTavilyApiKey = settingsSearchTavilyApiKey,
        searchJinaApiKey = settingsSearchJinaApiKey,
        searchKagiApiKey = settingsSearchKagiApiKey,
        maxToolRounds = settingsMaxToolRounds,
        toolResultMaxChars = settingsToolResultMaxChars,
        memoryConsolidationWindow = settingsMemoryConsolidationWindow,
        llmCallTimeoutSeconds = settingsLlmCallTimeoutSeconds,
        llmConnectTimeoutSeconds = settingsLlmConnectTimeoutSeconds,
        llmReadTimeoutSeconds = settingsLlmReadTimeoutSeconds,
        defaultToolTimeoutSeconds = settingsDefaultToolTimeoutSeconds,
        contextMessages = settingsContextMessages,
        toolArgsPreviewMaxChars = settingsToolArgsPreviewMaxChars,
        agentLogs = settingsAgentLogs,
        useChinese = settingsUseChinese
    )
}

fun ChatUiState.toAutomationSettingsState(): AutomationSettingsState {
    return AutomationSettingsState(
        cronEnabled = settingsCronEnabled,
        cronMinEveryMs = settingsCronMinEveryMs,
        cronMaxJobs = settingsCronMaxJobs,
        cronJobs = settingsCronJobs,
        cronJobsLoading = settingsCronJobsLoading,
        cronLogs = settingsCronLogs,
        heartbeatEnabled = settingsHeartbeatEnabled,
        heartbeatIntervalSeconds = settingsHeartbeatIntervalSeconds,
        heartbeatDoc = settingsHeartbeatDoc,
        saving = settingsSaving,
        useChinese = settingsUseChinese
    )
}

fun ChatUiState.toAlwaysOnSettingsState(): AlwaysOnSettingsState {
    return AlwaysOnSettingsState(
        enabled = alwaysOnEnabled,
        keepScreenAwake = alwaysOnKeepScreenAwake,
        serviceRunning = alwaysOnServiceRunning,
        notificationActive = alwaysOnNotificationActive,
        gatewayRunning = alwaysOnGatewayRunning,
        networkConnected = alwaysOnNetworkConnected,
        charging = alwaysOnCharging,
        batteryOptimizationIgnored = alwaysOnBatteryOptimizationIgnored,
        exactAlarmAllowed = alwaysOnExactAlarmAllowed,
        activeAdapterCount = alwaysOnActiveAdapterCount,
        startedAtMs = alwaysOnStartedAtMs,
        lastError = alwaysOnLastError,
        info = settingsInfo,
        useChinese = settingsUseChinese
    )
}

fun ChatUiState.toMcpSettingsState(): McpSettingsState {
    return McpSettingsState(
        enabled = settingsMcpEnabled,
        serverName = settingsMcpServerName,
        serverUrl = settingsMcpServerUrl,
        authToken = settingsMcpAuthToken,
        toolTimeoutSeconds = settingsMcpToolTimeoutSeconds,
        servers = settingsMcpServers,
        useChinese = settingsUseChinese
    )
}

fun ChatUiState.toUpdateSettingsState(): UpdateSettingsState {
    return UpdateSettingsState(
        checking = settingsUpdateChecking,
        available = settingsUpdateAvailable,
        promptVisible = settingsUpdatePromptVisible,
        noticeVisible = settingsUpdateNoticeVisible,
        noticeTitle = settingsUpdateNoticeTitle,
        noticeMessage = settingsUpdateNoticeMessage,
        noticeActionLabel = settingsUpdateNoticeActionLabel,
        noticeActionUrl = settingsUpdateNoticeActionUrl,
        currentVersion = settingsCurrentVersion,
        latestVersion = settingsLatestVersion,
        releaseUrl = settingsUpdateReleaseUrl,
        downloadUrl = settingsUpdateDownloadUrl,
        useChinese = settingsUseChinese
    )
}

fun ChatUiState.toSessionBindingState(): SessionBindingState {
    return SessionBindingState(
        telegramDiscovering = sessionBindingTelegramDiscovering,
        telegramDiscoveryAttempted = sessionBindingTelegramDiscoveryAttempted,
        telegramCandidates = sessionBindingTelegramCandidates,
        telegramInfo = sessionBindingTelegramInfo,
        feishuDiscovering = sessionBindingFeishuDiscovering,
        feishuDiscoveryAttempted = sessionBindingFeishuDiscoveryAttempted,
        feishuCandidates = sessionBindingFeishuCandidates,
        feishuInfo = sessionBindingFeishuInfo,
        emailDiscovering = sessionBindingEmailDiscovering,
        emailDiscoveryAttempted = sessionBindingEmailDiscoveryAttempted,
        emailCandidates = sessionBindingEmailCandidates,
        emailInfo = sessionBindingEmailInfo,
        weComDiscovering = sessionBindingWeComDiscovering,
        weComDiscoveryAttempted = sessionBindingWeComDiscoveryAttempted,
        weComCandidates = sessionBindingWeComCandidates,
        weComInfo = sessionBindingWeComInfo,
        useChinese = settingsUseChinese
    )
}

fun ChatUiState.withSettingsShellState(state: SettingsShellState): ChatUiState {
    return copy(
        settingsUseChinese = state.useChinese,
        settingsDarkTheme = state.darkTheme,
        settingsSaving = state.saving,
        settingsInfo = state.info
    )
}

fun ChatUiState.withChatContentState(state: ChatContentState): ChatUiState {
    return copy(
        messages = state.messages,
        messagesLoading = state.messagesLoading,
        messagesLoadingOlder = state.messagesLoadingOlder,
        canLoadOlderMessages = state.canLoadOlderMessages,
        input = state.input,
        composerAttachments = state.composerAttachments,
        composerImporting = state.composerImporting,
        composerAttachmentError = state.composerAttachmentError,
        isGenerating = state.isGenerating,
        currentSessionId = state.currentSessionId,
        currentSessionTitle = state.currentSessionTitle,
        sessions = state.sessions
    )
}

fun ChatUiState.withChannelsSettingsState(state: ChannelsSettingsState): ChatUiState {
    return copy(
        settingsConnectedChannels = state.connectedChannels,
        settingsGatewayEnabled = state.gatewayEnabled,
        settingsTelegramBotToken = state.telegramBotToken,
        settingsTelegramAllowedChatId = state.telegramAllowedChatId,
        settingsDiscordWebhookUrl = state.discordWebhookUrl,
        settingsDiscordGatewayStatus = state.discordGatewayStatus,
        settingsSlackGatewayStatus = state.slackGatewayStatus,
        settingsFeishuGatewayStatus = state.feishuGatewayStatus,
        settingsEmailGatewayStatus = state.emailGatewayStatus,
        settingsWeComGatewayStatus = state.weComGatewayStatus,
        settingsUseChinese = state.useChinese
    )
}

fun ChatUiState.withSessionBindingState(state: SessionBindingState): ChatUiState {
    return copy(
        sessionBindingTelegramDiscovering = state.telegramDiscovering,
        sessionBindingTelegramDiscoveryAttempted = state.telegramDiscoveryAttempted,
        sessionBindingTelegramCandidates = state.telegramCandidates,
        sessionBindingTelegramInfo = state.telegramInfo,
        sessionBindingFeishuDiscovering = state.feishuDiscovering,
        sessionBindingFeishuDiscoveryAttempted = state.feishuDiscoveryAttempted,
        sessionBindingFeishuCandidates = state.feishuCandidates,
        sessionBindingFeishuInfo = state.feishuInfo,
        sessionBindingEmailDiscovering = state.emailDiscovering,
        sessionBindingEmailDiscoveryAttempted = state.emailDiscoveryAttempted,
        sessionBindingEmailCandidates = state.emailCandidates,
        sessionBindingEmailInfo = state.emailInfo,
        sessionBindingWeComDiscovering = state.weComDiscovering,
        sessionBindingWeComDiscoveryAttempted = state.weComDiscoveryAttempted,
        sessionBindingWeComCandidates = state.weComCandidates,
        sessionBindingWeComInfo = state.weComInfo,
        settingsUseChinese = state.useChinese
    )
}

fun ChatUiState.withOnboardingUiState(state: OnboardingUiState): ChatUiState {
    return copy(
        onboardingCompleted = state.completed,
        settingsUseChinese = state.useChinese,
        userDisplayName = state.userDisplayName,
        agentDisplayName = state.agentDisplayName,
        onboardingUserDisplayName = state.onboardingUserDisplayName,
        onboardingAgentDisplayName = state.onboardingAgentDisplayName,
        settingsProvider = state.provider,
        settingsProviderCustomName = state.providerCustomName,
        settingsProviderProtocol = state.providerProtocol,
        settingsBaseUrl = state.baseUrl,
        settingsModel = state.model,
        settingsApiKey = state.apiKey,
        settingsProviderTesting = state.providerTesting,
        settingsSaving = state.saving,
        settingsInfo = state.info
    )
}

fun ChatUiState.withIdentityDisplayState(state: IdentityDisplayState): ChatUiState {
    return copy(
        settingsUseChinese = state.useChinese,
        userDisplayName = state.userDisplayName,
        agentDisplayName = state.agentDisplayName
    )
}

fun ChatUiState.withProviderSettingsState(state: ProviderSettingsState): ChatUiState {
    return copy(
        settingsProviderConfigs = state.providerConfigs,
        settingsEditingProviderConfigId = state.editingProviderConfigId,
        settingsProvider = state.provider,
        settingsProviderCustomName = state.providerCustomName,
        settingsProviderProtocol = state.providerProtocol,
        settingsBaseUrl = state.baseUrl,
        settingsModel = state.model,
        settingsApiKey = state.apiKeyDraft,
        settingsTokenInput = state.tokenInput,
        settingsTokenOutput = state.tokenOutput,
        settingsTokenTotal = state.tokenTotal,
        settingsTokenCachedInput = state.tokenCachedInput,
        settingsTokenRequests = state.tokenRequests,
        settingsProviderTesting = state.providerTesting,
        settingsSaving = state.saving,
        settingsInfo = state.info,
        settingsUseChinese = state.useChinese
    )
}

fun ChatUiState.withSkillsDiscoveryState(state: SkillsDiscoveryState): ChatUiState {
    return copy(
        settingsInstalledSkills = state.installedSkills,
        settingsSelectedSkillName = state.selectedSkillName,
        settingsSelectedSkillDetail = state.selectedSkillDetail,
        settingsClawHubStaffPicks = state.clawHubStaffPicks,
        settingsClawHubPopular = state.clawHubPopular,
        settingsClawHubSearchQuery = state.clawHubSearchQuery,
        settingsClawHubSearchedQuery = state.clawHubSearchedQuery,
        settingsClawHubSearchResults = state.clawHubSearchResults,
        settingsSelectedClawHubDetail = state.selectedClawHubDetail,
        settingsStagedSkillReview = state.stagedSkillReview,
        settingsSkillDownloadStatus = state.downloadStatus,
        settingsSkillsLoading = state.skillsLoading,
        settingsClawHubLoading = state.clawHubLoading,
        settingsSkillActionInFlight = state.skillActionInFlight
    )
}

fun ChatUiState.withToolSettingsState(state: ToolSettingsState): ChatUiState {
    return copy(
        settingsBuiltInTools = state.builtInTools,
        settingsSearchProvider = state.searchProvider,
        settingsSearchBraveApiKey = state.searchBraveApiKey,
        settingsSearchTavilyApiKey = state.searchTavilyApiKey,
        settingsSearchJinaApiKey = state.searchJinaApiKey,
        settingsSearchKagiApiKey = state.searchKagiApiKey,
        settingsMaxToolRounds = state.maxToolRounds,
        settingsToolResultMaxChars = state.toolResultMaxChars,
        settingsMemoryConsolidationWindow = state.memoryConsolidationWindow,
        settingsLlmCallTimeoutSeconds = state.llmCallTimeoutSeconds,
        settingsLlmConnectTimeoutSeconds = state.llmConnectTimeoutSeconds,
        settingsLlmReadTimeoutSeconds = state.llmReadTimeoutSeconds,
        settingsDefaultToolTimeoutSeconds = state.defaultToolTimeoutSeconds,
        settingsContextMessages = state.contextMessages,
        settingsToolArgsPreviewMaxChars = state.toolArgsPreviewMaxChars,
        settingsAgentLogs = state.agentLogs,
        settingsUseChinese = state.useChinese
    )
}

fun ChatUiState.withAutomationSettingsState(state: AutomationSettingsState): ChatUiState {
    return copy(
        settingsCronEnabled = state.cronEnabled,
        settingsCronMinEveryMs = state.cronMinEveryMs,
        settingsCronMaxJobs = state.cronMaxJobs,
        settingsCronJobs = state.cronJobs,
        settingsCronJobsLoading = state.cronJobsLoading,
        settingsCronLogs = state.cronLogs,
        settingsHeartbeatEnabled = state.heartbeatEnabled,
        settingsHeartbeatIntervalSeconds = state.heartbeatIntervalSeconds,
        settingsHeartbeatDoc = state.heartbeatDoc,
        settingsSaving = state.saving,
        settingsUseChinese = state.useChinese
    )
}

fun ChatUiState.withAlwaysOnSettingsState(state: AlwaysOnSettingsState): ChatUiState {
    return copy(
        alwaysOnEnabled = state.enabled,
        alwaysOnKeepScreenAwake = state.keepScreenAwake,
        alwaysOnServiceRunning = state.serviceRunning,
        alwaysOnNotificationActive = state.notificationActive,
        alwaysOnGatewayRunning = state.gatewayRunning,
        alwaysOnNetworkConnected = state.networkConnected,
        alwaysOnCharging = state.charging,
        alwaysOnBatteryOptimizationIgnored = state.batteryOptimizationIgnored,
        alwaysOnExactAlarmAllowed = state.exactAlarmAllowed,
        alwaysOnActiveAdapterCount = state.activeAdapterCount,
        alwaysOnStartedAtMs = state.startedAtMs,
        alwaysOnLastError = state.lastError,
        settingsInfo = state.info,
        settingsUseChinese = state.useChinese
    )
}

fun ChatUiState.withMcpSettingsState(state: McpSettingsState): ChatUiState {
    return copy(
        settingsMcpEnabled = state.enabled,
        settingsMcpServerName = state.serverName,
        settingsMcpServerUrl = state.serverUrl,
        settingsMcpAuthToken = state.authToken,
        settingsMcpToolTimeoutSeconds = state.toolTimeoutSeconds,
        settingsMcpServers = state.servers,
        settingsUseChinese = state.useChinese
    )
}

fun ChatUiState.withUpdateSettingsState(state: UpdateSettingsState): ChatUiState {
    return copy(
        settingsUpdateChecking = state.checking,
        settingsUpdateAvailable = state.available,
        settingsUpdatePromptVisible = state.promptVisible,
        settingsUpdateNoticeVisible = state.noticeVisible,
        settingsUpdateNoticeTitle = state.noticeTitle,
        settingsUpdateNoticeMessage = state.noticeMessage,
        settingsUpdateNoticeActionLabel = state.noticeActionLabel,
        settingsUpdateNoticeActionUrl = state.noticeActionUrl,
        settingsCurrentVersion = state.currentVersion,
        settingsLatestVersion = state.latestVersion,
        settingsUpdateReleaseUrl = state.releaseUrl,
        settingsUpdateDownloadUrl = state.downloadUrl,
        settingsUseChinese = state.useChinese
    )
}

private fun UiSessionSummary.hasPendingChannelDetection(): Boolean {
    return when {
        boundChannel.equals("telegram", ignoreCase = true) ->
            boundTelegramBotToken.isNotBlank() && boundChatId.isBlank()
        boundChannel.equals("feishu", ignoreCase = true) ->
            boundFeishuAppId.isNotBlank() && boundFeishuAppSecret.isNotBlank() && boundChatId.isBlank()
        boundChannel.equals("email", ignoreCase = true) ->
            boundEmailConsentGranted &&
                boundEmailImapHost.isNotBlank() &&
                boundEmailImapUsername.isNotBlank() &&
                boundEmailImapPassword.isNotBlank() &&
                boundEmailSmtpHost.isNotBlank() &&
                boundEmailSmtpUsername.isNotBlank() &&
                boundEmailSmtpPassword.isNotBlank() &&
                boundChatId.isBlank()
        boundChannel.equals("wecom", ignoreCase = true) ->
            boundWeComBotId.isNotBlank() && boundWeComSecret.isNotBlank() && boundChatId.isBlank()
        else -> false
    }
}
