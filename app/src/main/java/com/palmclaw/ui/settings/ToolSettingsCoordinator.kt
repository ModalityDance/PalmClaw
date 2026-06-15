package com.palmclaw.ui.settings

import com.palmclaw.config.SearchProviderId
import com.palmclaw.ui.ChatStateStore

internal class ToolSettingsCoordinator(
    private val stateStore: ChatStateStore,
    private val actions: Actions
) {
    data class Actions(
        val saveToolSettings: (Boolean, Boolean) -> Unit
    )

    fun onToolEnabledChanged(toolName: String, enabled: Boolean) {
        val normalizedName = toolName.trim()
        if (normalizedName.isBlank()) return
        stateStore.updateToolSettingsState { state ->
            state.copy(
                builtInTools = state.builtInTools.map { tool ->
                    if (tool.toolName == normalizedName && tool.userManageable) {
                        tool.copy(enabled = enabled)
                    } else {
                        tool
                    }
                }
            )
        }
        actions.saveToolSettings(false, false)
    }

    fun onSearchProviderChanged(provider: SearchProviderId) {
        stateStore.updateToolSettingsState { it.copy(searchProvider = provider) }
    }

    fun onSearchBraveApiKeyChanged(value: String) {
        stateStore.updateToolSettingsState { it.copy(searchBraveApiKey = value) }
    }

    fun onSearchTavilyApiKeyChanged(value: String) {
        stateStore.updateToolSettingsState { it.copy(searchTavilyApiKey = value) }
    }

    fun onSearchJinaApiKeyChanged(value: String) {
        stateStore.updateToolSettingsState { it.copy(searchJinaApiKey = value) }
    }

    fun onSearchKagiApiKeyChanged(value: String) {
        stateStore.updateToolSettingsState { it.copy(searchKagiApiKey = value) }
    }

    fun saveToolSettings(showSuccessMessage: Boolean, showErrorMessage: Boolean) =
        actions.saveToolSettings(showSuccessMessage, showErrorMessage)
}
