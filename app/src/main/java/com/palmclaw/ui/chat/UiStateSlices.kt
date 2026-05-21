package com.palmclaw.ui

import com.palmclaw.config.AppLimits
import com.palmclaw.config.AppSession
import com.palmclaw.providers.ProviderCatalog
import com.palmclaw.providers.ProviderProtocol
import com.palmclaw.ui.settings.UiClawHubSkillCard
import com.palmclaw.ui.settings.UiClawHubSkillDetail
import com.palmclaw.ui.settings.UiSkillConfig
import com.palmclaw.ui.settings.UiSkillDownloadStatus
import com.palmclaw.ui.settings.UiStagedSkillReview

data class ChatContentState(
    val messages: List<UiMessage> = emptyList(),
    val messagesLoading: Boolean = false,
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
    val saving: Boolean = false,
    val info: String? = null
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
    val useChinese: Boolean = false
)

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

fun ChatUiState.toChatContentState(): ChatContentState {
    return ChatContentState(
        messages = messages,
        messagesLoading = messagesLoading,
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
        saving = settingsSaving,
        info = settingsInfo
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
