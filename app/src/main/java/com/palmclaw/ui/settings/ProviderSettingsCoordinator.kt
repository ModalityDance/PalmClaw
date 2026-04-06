package com.palmclaw.ui

import com.palmclaw.config.AppLimits
import com.palmclaw.config.TokenUsageStats
import com.palmclaw.providers.ProviderCatalog

internal class ProviderSettingsCoordinator(
    private val stateStore: ChatStateStore,
    private val clearTokenUsageStats: () -> TokenUsageStats,
    private val persistOnboardingProviderDraftIfNeeded: () -> Unit,
    private val actions: Actions
) {
    data class Actions(
        val setActiveProviderConfig: (String) -> Unit,
        val deleteProviderConfig: (String) -> Unit,
        val saveProviderSettings: (Boolean, Boolean) -> Unit,
        val saveAgentRuntimeSettings: (Boolean, Boolean) -> Unit,
        val testProviderSettings: () -> Unit
    )

    fun clearProviderTokenUsageStats() {
        val stats = clearTokenUsageStats()
        stateStore.updateProviderSettings {
            it.copy(
                settingsTokenInput = stats.inputTokens,
                settingsTokenOutput = stats.outputTokens,
                settingsTokenTotal = stats.totalTokens,
                settingsTokenCachedInput = stats.cachedInputTokens,
                settingsTokenRequests = stats.requests,
                settingsInfo = "Provider token stats cleared."
            )
        }
    }

    fun onSettingsProviderChanged(value: String) {
        val resolved = ProviderCatalog.resolve(value)
        val protocol = ProviderCatalog.defaultProtocol(resolved.id)
        stateStore.updateProviderSettings {
            it.copy(
                settingsProvider = resolved.id,
                settingsProviderCustomName = if (resolved.id == "custom") {
                    it.settingsProviderCustomName
                } else {
                    ""
                },
                settingsProviderProtocol = protocol,
                settingsBaseUrl = ProviderCatalog.defaultBaseUrl(resolved.id, protocol),
                settingsModel = ProviderCatalog.defaultModel(resolved.id, protocol),
                settingsApiKey = ""
            )
        }
        persistOnboardingProviderDraftIfNeeded()
    }

    fun startNewProviderDraft() {
        val protocol = ProviderCatalog.defaultProtocol(AppLimits.DEFAULT_PROVIDER)
        stateStore.updateProviderSettings {
            it.copy(
                settingsEditingProviderConfigId = "",
                settingsProvider = AppLimits.DEFAULT_PROVIDER,
                settingsProviderCustomName = "",
                settingsProviderProtocol = protocol,
                settingsBaseUrl = ProviderCatalog.defaultBaseUrl(AppLimits.DEFAULT_PROVIDER, protocol),
                settingsModel = ProviderCatalog.defaultModel(AppLimits.DEFAULT_PROVIDER, protocol),
                settingsApiKey = "",
                settingsInfo = null
            )
        }
    }

    fun selectProviderConfigForEditing(configId: String) {
        val targetId = configId.trim()
        if (targetId.isBlank()) return
        stateStore.updateProviderSettings { state ->
            val config = state.settingsProviderConfigs.firstOrNull { it.id == targetId } ?: return@updateProviderSettings state
            state.copy(
                settingsEditingProviderConfigId = config.id,
                settingsProvider = ProviderCatalog.resolve(config.providerName).id,
                settingsProviderCustomName = config.customName,
                settingsProviderProtocol = config.providerProtocol,
                settingsBaseUrl = config.baseUrl.ifBlank {
                    ProviderCatalog.defaultBaseUrl(config.providerName, config.providerProtocol)
                },
                settingsModel = config.model.ifBlank {
                    ProviderCatalog.defaultModel(config.providerName, config.providerProtocol)
                },
                settingsApiKey = config.apiKey,
                settingsInfo = null
            )
        }
    }

    fun setActiveProviderConfig(configId: String) = actions.setActiveProviderConfig(configId)

    fun deleteProviderConfig(configId: String) = actions.deleteProviderConfig(configId)

    fun onSettingsModelChanged(value: String) {
        stateStore.updateProviderSettings { it.copy(settingsModel = value) }
        persistOnboardingProviderDraftIfNeeded()
    }

    fun onSettingsProviderCustomNameChanged(value: String) {
        stateStore.updateProviderSettings { it.copy(settingsProviderCustomName = value) }
        persistOnboardingProviderDraftIfNeeded()
    }

    fun onSettingsApiKeyChanged(value: String) {
        stateStore.updateProviderSettings { it.copy(settingsApiKey = value) }
        persistOnboardingProviderDraftIfNeeded()
    }

    fun onSettingsBaseUrlChanged(value: String) {
        stateStore.updateProviderSettings { state ->
            val provider = ProviderCatalog.resolve(state.settingsProvider).id
            val protocol = ProviderCatalog.resolveProtocol(
                provider,
                state.settingsProviderProtocol,
                value
            )
            state.copy(
                settingsBaseUrl = value,
                settingsProviderProtocol = protocol
            )
        }
        persistOnboardingProviderDraftIfNeeded()
    }

    fun onSettingsMaxRoundsChanged(value: String) {
        stateStore.updateProviderSettings { it.copy(settingsMaxToolRounds = value) }
    }

    fun onSettingsToolResultMaxCharsChanged(value: String) {
        stateStore.updateProviderSettings { it.copy(settingsToolResultMaxChars = value) }
    }

    fun onSettingsMemoryConsolidationWindowChanged(value: String) {
        stateStore.updateProviderSettings { it.copy(settingsMemoryConsolidationWindow = value) }
    }

    fun onSettingsLlmCallTimeoutSecondsChanged(value: String) {
        stateStore.updateProviderSettings { it.copy(settingsLlmCallTimeoutSeconds = value) }
    }

    fun onSettingsLlmConnectTimeoutSecondsChanged(value: String) {
        stateStore.updateProviderSettings { it.copy(settingsLlmConnectTimeoutSeconds = value) }
    }

    fun onSettingsLlmReadTimeoutSecondsChanged(value: String) {
        stateStore.updateProviderSettings { it.copy(settingsLlmReadTimeoutSeconds = value) }
    }

    fun onSettingsDefaultToolTimeoutSecondsChanged(value: String) {
        stateStore.updateProviderSettings { it.copy(settingsDefaultToolTimeoutSeconds = value) }
    }

    fun onSettingsContextMessagesChanged(value: String) {
        stateStore.updateProviderSettings { it.copy(settingsContextMessages = value) }
    }

    fun onSettingsToolArgsPreviewMaxCharsChanged(value: String) {
        stateStore.updateProviderSettings { it.copy(settingsToolArgsPreviewMaxChars = value) }
    }

    fun saveProviderSettings(showSuccessMessage: Boolean, showErrorMessage: Boolean) =
        actions.saveProviderSettings(showSuccessMessage, showErrorMessage)

    fun saveAgentRuntimeSettings(showSuccessMessage: Boolean, showErrorMessage: Boolean) =
        actions.saveAgentRuntimeSettings(showSuccessMessage, showErrorMessage)

    fun testProviderSettings() = actions.testProviderSettings()
}
