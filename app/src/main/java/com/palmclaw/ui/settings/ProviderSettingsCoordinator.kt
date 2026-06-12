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
        stateStore.updateProviderSettingsState {
            it.copy(
                tokenInput = stats.inputTokens,
                tokenOutput = stats.outputTokens,
                tokenTotal = stats.totalTokens,
                tokenCachedInput = stats.cachedInputTokens,
                tokenRequests = stats.requests,
                info = "Provider token stats cleared."
            )
        }
    }

    fun onSettingsProviderChanged(value: String) {
        val resolved = ProviderCatalog.resolve(value)
        val protocol = ProviderCatalog.defaultProtocol(resolved.id)
        stateStore.updateProviderSettingsState {
            it.copy(
                provider = resolved.id,
                providerCustomName = if (resolved.id == "custom") {
                    it.providerCustomName
                } else {
                    ""
                },
                providerProtocol = protocol,
                baseUrl = ProviderCatalog.defaultBaseUrl(resolved.id, protocol),
                model = ProviderCatalog.defaultModel(resolved.id, protocol),
                apiKeyDraft = ""
            )
        }
        persistOnboardingProviderDraftIfNeeded()
    }

    fun startNewProviderDraft() {
        val protocol = ProviderCatalog.defaultProtocol(AppLimits.DEFAULT_PROVIDER)
        stateStore.updateProviderSettingsState {
            it.copy(
                editingProviderConfigId = "",
                provider = AppLimits.DEFAULT_PROVIDER,
                providerCustomName = "",
                providerProtocol = protocol,
                baseUrl = ProviderCatalog.defaultBaseUrl(AppLimits.DEFAULT_PROVIDER, protocol),
                model = ProviderCatalog.defaultModel(AppLimits.DEFAULT_PROVIDER, protocol),
                apiKeyDraft = "",
                info = null
            )
        }
    }

    fun selectProviderConfigForEditing(configId: String) {
        val targetId = configId.trim()
        if (targetId.isBlank()) return
        stateStore.updateProviderSettingsState { state ->
            val config = state.providerConfigs.firstOrNull { it.id == targetId }
                ?: return@updateProviderSettingsState state
            state.copy(
                editingProviderConfigId = config.id,
                provider = ProviderCatalog.resolve(config.providerName).id,
                providerCustomName = config.customName,
                providerProtocol = config.providerProtocol,
                baseUrl = config.baseUrl.ifBlank {
                    ProviderCatalog.defaultBaseUrl(config.providerName, config.providerProtocol)
                },
                model = config.model.ifBlank {
                    ProviderCatalog.defaultModel(config.providerName, config.providerProtocol)
                },
                apiKeyDraft = config.apiKey,
                info = null
            )
        }
    }

    fun setActiveProviderConfig(configId: String) = actions.setActiveProviderConfig(configId)

    fun deleteProviderConfig(configId: String) = actions.deleteProviderConfig(configId)

    fun onSettingsModelChanged(value: String) {
        stateStore.updateProviderSettingsState { it.copy(model = value) }
        persistOnboardingProviderDraftIfNeeded()
    }

    fun onSettingsProviderCustomNameChanged(value: String) {
        stateStore.updateProviderSettingsState { it.copy(providerCustomName = value) }
        persistOnboardingProviderDraftIfNeeded()
    }

    fun onSettingsApiKeyChanged(value: String) {
        stateStore.updateProviderSettingsState { it.copy(apiKeyDraft = value) }
        persistOnboardingProviderDraftIfNeeded()
    }

    fun onSettingsBaseUrlChanged(value: String) {
        stateStore.updateProviderSettingsState { state ->
            val provider = ProviderCatalog.resolve(state.provider).id
            val protocol = ProviderCatalog.resolveProtocol(
                provider,
                state.providerProtocol,
                value
            )
            state.copy(
                baseUrl = value,
                providerProtocol = protocol
            )
        }
        persistOnboardingProviderDraftIfNeeded()
    }

    fun onSettingsMaxRoundsChanged(value: String) {
        stateStore.updateToolSettingsState { it.copy(maxToolRounds = value) }
    }

    fun onSettingsToolResultMaxCharsChanged(value: String) {
        stateStore.updateToolSettingsState { it.copy(toolResultMaxChars = value) }
    }

    fun onSettingsMemoryConsolidationWindowChanged(value: String) {
        stateStore.updateToolSettingsState { it.copy(memoryConsolidationWindow = value) }
    }

    fun onSettingsLlmCallTimeoutSecondsChanged(value: String) {
        stateStore.updateToolSettingsState { it.copy(llmCallTimeoutSeconds = value) }
    }

    fun onSettingsLlmConnectTimeoutSecondsChanged(value: String) {
        stateStore.updateToolSettingsState { it.copy(llmConnectTimeoutSeconds = value) }
    }

    fun onSettingsLlmReadTimeoutSecondsChanged(value: String) {
        stateStore.updateToolSettingsState { it.copy(llmReadTimeoutSeconds = value) }
    }

    fun onSettingsDefaultToolTimeoutSecondsChanged(value: String) {
        stateStore.updateToolSettingsState { it.copy(defaultToolTimeoutSeconds = value) }
    }

    fun onSettingsContextMessagesChanged(value: String) {
        stateStore.updateToolSettingsState { it.copy(contextMessages = value) }
    }

    fun onSettingsToolArgsPreviewMaxCharsChanged(value: String) {
        stateStore.updateToolSettingsState { it.copy(toolArgsPreviewMaxChars = value) }
    }

    fun saveProviderSettings(showSuccessMessage: Boolean, showErrorMessage: Boolean) =
        actions.saveProviderSettings(showSuccessMessage, showErrorMessage)

    fun saveAgentRuntimeSettings(showSuccessMessage: Boolean, showErrorMessage: Boolean) =
        actions.saveAgentRuntimeSettings(showSuccessMessage, showErrorMessage)

    fun testProviderSettings() = actions.testProviderSettings()
}
