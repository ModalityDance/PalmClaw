package com.palmclaw.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.palmclaw.config.SearchProviderId
import com.palmclaw.tools.BuiltInToolSettingsKind
import com.palmclaw.ui.settings.UiSearchProviderOption
import java.util.Locale
import kotlinx.coroutines.delay

internal data class ToolSettingsPageActions(
    val onToolEnabledChange: (String, Boolean) -> Unit,
    val onSearchProviderChange: (SearchProviderId) -> Unit,
    val onSearchBraveApiKeyChange: (String) -> Unit,
    val onSearchTavilyApiKeyChange: (String) -> Unit,
    val onSearchJinaApiKeyChange: (String) -> Unit,
    val onSearchKagiApiKeyChange: (String) -> Unit,
    val onSaveToolsPage: () -> Unit
)

@Composable
internal fun ToolSettingsPage(
    state: ToolSettingsState,
    actions: ToolSettingsPageActions
) {
    var expandedSearchProviderId by rememberSaveable { mutableStateOf<String?>(null) }
    var searchProviderFeedback by rememberSaveable { mutableStateOf("") }
    val searchProviderOptions = searchProviderOptions()
    val enabledCount = state.builtInTools.count { it.enabled }
    val selectedSearchProviderOption = searchProviderOptions.firstOrNull {
        it.id == state.searchProvider
    }

    LaunchedEffect(searchProviderFeedback) {
        if (searchProviderFeedback.isBlank()) return@LaunchedEffect
        delay(2400)
        searchProviderFeedback = ""
    }

    SettingsSectionCard(
        title = tr("Built-in Tools", "内置工具"),
        subtitle = tr(
            "Manage the built-in tools exposed to the agent.",
            "管理暴露给 Agent 的内置工具。"
        )
    ) {
        SettingsValueRow(uiLabel("Enabled"), "$enabledCount / ${state.builtInTools.size}")
        SettingsValueRow(
            uiLabel("Search Provider"),
            selectedSearchProviderOption?.displayName ?: SearchProviderId.DuckDuckGo.wireValue
        )
    }

    SettingsSectionCard(
        title = tr("Tool Toggles", "工具开关"),
        subtitle = tr(
            "Disabled built-in tools are removed from the runtime tool list.",
            "关闭后的内置工具会从运行时工具列表中移除。"
        )
    ) {
        val groupedTools = state.builtInTools.groupBy { it.category }
        groupedTools.entries
            .sortedBy { it.key.lowercase(Locale.US) }
            .forEachIndexed { index, (category, tools) ->
                if (index > 0) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.10f))
                }
                Text(
                    text = uiLabel(category),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                tools.forEach { tool ->
                    SettingsToggleRow(
                        title = tool.displayName,
                        subtitle = if (tool.userManageable) {
                            tool.description
                        } else {
                            uiLabel(tool.description) + " " + tr(
                                "This core tool always stays enabled.",
                                "此核心工具会始终保持启用。"
                            )
                        },
                        checked = tool.enabled,
                        onCheckedChange = { enabled ->
                            actions.onToolEnabledChange(tool.toolName, enabled)
                        },
                        enabled = tool.userManageable
                    )

                    if (tool.settingsKind == BuiltInToolSettingsKind.SearchProvider && tool.enabled) {
                        SearchProviderSettingsSection(
                            state = state,
                            options = searchProviderOptions,
                            expandedSearchProviderId = expandedSearchProviderId,
                            searchProviderFeedback = searchProviderFeedback,
                            onExpandedSearchProviderIdChange = { expandedSearchProviderId = it },
                            onSearchProviderFeedbackChange = { searchProviderFeedback = it },
                            actions = actions
                        )
                    }
                }
            }
    }
}

@Composable
private fun SearchProviderSettingsSection(
    state: ToolSettingsState,
    options: List<UiSearchProviderOption>,
    expandedSearchProviderId: String?,
    searchProviderFeedback: String,
    onExpandedSearchProviderIdChange: (String?) -> Unit,
    onSearchProviderFeedbackChange: (String) -> Unit,
    actions: ToolSettingsPageActions
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = tr("Search Provider", "搜索提供方"),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold
        )
        options.forEach { option ->
            val currentApiKey = when (option.id) {
                SearchProviderId.Brave -> state.searchBraveApiKey
                SearchProviderId.Tavily -> state.searchTavilyApiKey
                SearchProviderId.Jina -> state.searchJinaApiKey
                SearchProviderId.Kagi -> state.searchKagiApiKey
                SearchProviderId.DuckDuckGo -> ""
            }
            val providerEnabledMessage = tr(
                "${option.displayName} enabled.",
                "已启用 ${option.displayName}"
            )
            val providerApiKeySavedMessage = tr(
                "${option.displayName} API key saved.",
                "${option.displayName} API Key 已保存"
            )
            SearchProviderSettingsCard(
                option = option,
                selected = option.id == state.searchProvider,
                expanded = expandedSearchProviderId == option.id.wireValue,
                currentApiKey = currentApiKey,
                onEnable = {
                    actions.onSearchProviderChange(option.id)
                    actions.onSaveToolsPage()
                    onSearchProviderFeedbackChange(providerEnabledMessage)
                },
                onToggleEditor = {
                    onExpandedSearchProviderIdChange(
                        if (expandedSearchProviderId == option.id.wireValue) {
                            null
                        } else {
                            option.id.wireValue
                        }
                    )
                },
                onSaveApiKey = { value ->
                    when (option.id) {
                        SearchProviderId.Brave -> actions.onSearchBraveApiKeyChange(value)
                        SearchProviderId.Tavily -> actions.onSearchTavilyApiKeyChange(value)
                        SearchProviderId.Jina -> actions.onSearchJinaApiKeyChange(value)
                        SearchProviderId.Kagi -> actions.onSearchKagiApiKeyChange(value)
                        SearchProviderId.DuckDuckGo -> Unit
                    }
                    actions.onSaveToolsPage()
                    onExpandedSearchProviderIdChange(null)
                    onSearchProviderFeedbackChange(providerApiKeySavedMessage)
                }
            )
        }
        if (searchProviderFeedback.isNotBlank()) {
            Text(
                text = searchProviderFeedback,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}

@Composable
private fun SearchProviderSettingsCard(
    option: UiSearchProviderOption,
    selected: Boolean,
    expanded: Boolean,
    currentApiKey: String,
    onEnable: () -> Unit,
    onToggleEditor: () -> Unit,
    onSaveApiKey: (String) -> Unit
) {
    val requiresApiKey = option.id != SearchProviderId.DuckDuckGo
    var draftApiKey by remember(option.id.wireValue, currentApiKey) {
        mutableStateOf(currentApiKey)
    }
    val statusText = when {
        !requiresApiKey -> tr("No API key required", "无需 API Key")
        currentApiKey.trim().isBlank() -> tr("API key not saved", "未保存 API Key")
        else -> tr("API key saved", "已保存 API Key")
    }
    val detailText = if (requiresApiKey) {
        "${option.envHint} · $statusText"
    } else {
        statusText
    }

    Surface(
        tonalElevation = if (selected) 3.dp else 1.dp,
        shape = RoundedCornerShape(10.dp),
        color = if (selected) {
            MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.58f)
        } else {
            MaterialTheme.colorScheme.surface
        },
        border = if (selected) {
            BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.55f))
        } else {
            null
        },
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = option.displayName,
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Medium
                        )
                        if (selected) {
                            Icon(
                                imageVector = Icons.Rounded.CheckCircle,
                                contentDescription = uiLabel("Selected"),
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                    Text(
                        text = detailText,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                ProviderActionButton(
                    icon = Icons.Rounded.CheckCircle,
                    contentDescription = if (selected) tr("Enabled", "已启用") else tr("Enable", "启用"),
                    onClick = onEnable,
                    enabled = !selected,
                    tint = if (selected) MaterialTheme.colorScheme.primary else Color.Unspecified
                )
                if (requiresApiKey) {
                    ProviderActionButton(
                        icon = if (expanded) {
                            Icons.Rounded.KeyboardArrowUp
                        } else {
                            Icons.Outlined.Edit
                        },
                        contentDescription = if (expanded) {
                            uiLabel("Collapse")
                        } else {
                            uiLabel("Edit")
                        },
                        onClick = onToggleEditor
                    )
                }
            }

            if (expanded && requiresApiKey) {
                OutlinedTextField(
                    value = draftApiKey,
                    onValueChange = { draftApiKey = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(uiLabel("${option.displayName} API Key")) },
                    singleLine = true,
                    shape = settingsTextFieldShape(),
                    textStyle = MaterialTheme.typography.bodyMedium,
                    colors = settingsTextFieldColors()
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    OutlinedButton(onClick = {
                        onSaveApiKey(draftApiKey.trim())
                    }) {
                        Text(tr("Save", "保存"))
                    }
                }
                if (selected && draftApiKey.trim().isBlank()) {
                    Text(
                        text = tr(
                            "This provider needs an API key before web_search can use it.",
                            "此提供方需要先配置 API Key，web_search 才能使用。"
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }
}

@Composable
private fun searchProviderOptions(): List<UiSearchProviderOption> {
    return listOf(
        UiSearchProviderOption(
            id = SearchProviderId.DuckDuckGo,
            displayName = "DuckDuckGo",
            envHint = localizedText("No API key required", "无需 API Key", LocalUiLanguage.current == UiLanguage.Chinese)
        ),
        UiSearchProviderOption(
            id = SearchProviderId.Brave,
            displayName = "Brave",
            envHint = "BRAVE_API_KEY"
        ),
        UiSearchProviderOption(
            id = SearchProviderId.Tavily,
            displayName = "Tavily",
            envHint = "TAVILY_API_KEY"
        ),
        UiSearchProviderOption(
            id = SearchProviderId.Jina,
            displayName = "Jina",
            envHint = "JINA_API_KEY"
        ),
        UiSearchProviderOption(
            id = SearchProviderId.Kagi,
            displayName = "Kagi",
            envHint = "KAGI_API_KEY"
        )
    )
}
