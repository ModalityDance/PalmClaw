package com.palmclaw.ui.settings

import com.palmclaw.config.SearchProviderId
import com.palmclaw.tools.BuiltInToolSettingsKind

data class UiBuiltInToolConfig(
    val toolName: String,
    val displayName: String,
    val description: String,
    val category: String,
    val enabled: Boolean,
    val enabledByDefault: Boolean,
    val supportsSettings: Boolean = false,
    val settingsKind: BuiltInToolSettingsKind = BuiltInToolSettingsKind.None,
    val userManageable: Boolean = true
)

data class UiSearchProviderOption(
    val id: SearchProviderId,
    val displayName: String,
    val envHint: String
)
