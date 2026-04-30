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
        stateStore.updateToolSettings { state ->
            state.copy(
                settingsBuiltInTools = state.settingsBuiltInTools.map { tool ->
                    if (tool.toolName == normalizedName && tool.userManageable) {
                        tool.copy(enabled = enabled)
                    } else {
                        tool
                    }
                }
            )
        }
    }

    fun onSearchProviderChanged(provider: SearchProviderId) {
        stateStore.updateToolSettings { it.copy(settingsSearchProvider = provider) }
    }

    fun onSearchBraveApiKeyChanged(value: String) {
        stateStore.updateToolSettings { it.copy(settingsSearchBraveApiKey = value) }
    }

    fun onSearchTavilyApiKeyChanged(value: String) {
        stateStore.updateToolSettings { it.copy(settingsSearchTavilyApiKey = value) }
    }

    fun onSearchJinaApiKeyChanged(value: String) {
        stateStore.updateToolSettings { it.copy(settingsSearchJinaApiKey = value) }
    }

    fun onSearchKagiApiKeyChanged(value: String) {
        stateStore.updateToolSettings { it.copy(settingsSearchKagiApiKey = value) }
    }

    fun saveToolSettings(showSuccessMessage: Boolean, showErrorMessage: Boolean) =
        actions.saveToolSettings(showSuccessMessage, showErrorMessage)
}
