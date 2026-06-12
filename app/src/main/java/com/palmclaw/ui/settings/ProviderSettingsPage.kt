package com.palmclaw.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.palmclaw.providers.ProviderCatalog

internal data class ProviderSettingsPageActions(
    val onStartNewProviderDraft: () -> Unit,
    val onSelectProviderConfig: (String) -> Unit,
    val onDeleteProviderConfig: (String) -> Unit,
    val onSetActiveProviderConfig: (String) -> Unit,
    val onProviderChange: (String) -> Unit,
    val onProviderCustomNameChange: (String) -> Unit,
    val onModelChange: (String) -> Unit,
    val onApiKeyChange: (String) -> Unit,
    val onBaseUrlChange: (String) -> Unit,
    val onRevealToggle: () -> Unit,
    val onTestProvider: () -> Unit,
    val onSaveProviderDraft: () -> Unit,
    val onClearProviderTokenStats: () -> Unit,
    val onRequestConfirmation: (SettingsConfirmationState) -> Unit
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ProviderSettingsPage(
    state: ProviderSettingsState,
    revealApiKey: Boolean,
    useChinese: Boolean,
    actions: ProviderSettingsPageActions
) {
    val context = LocalContext.current
    val providerOptions = remember { ProviderCatalog.all() }
    var showProviderEditor by rememberSaveable { mutableStateOf(false) }
    var pendingCloseProviderEditor by rememberSaveable { mutableStateOf(false) }
    var providerMenuExpanded by rememberSaveable { mutableStateOf(false) }
    val selectedProvider = ProviderCatalog.resolve(state.provider)
    val providerPortalUrl = providerApiPortalUrl(selectedProvider.id)
    val isEditingSavedConfig = state.editingProviderConfigId.isNotBlank()

    LaunchedEffect(showProviderEditor, pendingCloseProviderEditor, state.saving, state.info) {
        if (!showProviderEditor || !pendingCloseProviderEditor || state.saving) return@LaunchedEffect
        when (state.info?.trim().orEmpty()) {
            "Provider saved." -> {
                pendingCloseProviderEditor = false
                showProviderEditor = false
                providerMenuExpanded = false
            }
            else -> {
                if (state.info?.startsWith("Save failed") == true) {
                    pendingCloseProviderEditor = false
                }
            }
        }
    }

    SettingsSectionCard(
        title = tr("Provider", "提供方"),
        subtitle = tr("Add the API account uses for chat.", "添加用于聊天的账号。"),
        actions = {
            OutlinedButton(
                onClick = {
                    actions.onStartNewProviderDraft()
                    pendingCloseProviderEditor = false
                    providerMenuExpanded = false
                    showProviderEditor = true
                },
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                modifier = Modifier.height(36.dp),
                colors = ButtonDefaults.outlinedButtonColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.28f),
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                ),
                border = BorderStroke(
                    1.dp,
                    MaterialTheme.colorScheme.outline.copy(alpha = 0.32f)
                )
            ) {
                Icon(
                    imageVector = Icons.Rounded.Add,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = tr("Add", "新增"),
                    maxLines = 1
                )
            }
        }
    ) {
        if (state.providerConfigs.isEmpty()) {
            Surface(
                tonalElevation = 0.dp,
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.22f),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = tr("No API account yet. Add one below, test it, then save it.", "还没有 API 账号。先在下方添加，测试后再保存。"),
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                state.providerConfigs.forEach { config ->
                    val providerServiceTitle = providerConfigServiceTitle(config)
                    val providerModelTitle = providerConfigModelTitle(config)
                    ProviderConfigRow(
                        config = config,
                        providerServiceTitle = providerServiceTitle,
                        providerModelTitle = providerModelTitle,
                        useChinese = useChinese,
                        onEdit = {
                            actions.onSelectProviderConfig(config.id)
                            providerMenuExpanded = false
                            showProviderEditor = true
                            pendingCloseProviderEditor = false
                        },
                        onSetActive = {
                            if (!config.enabled) {
                                actions.onSetActiveProviderConfig(config.id)
                            }
                        },
                        onDelete = {
                            actions.onRequestConfirmation(
                                deleteProviderConfirmation(
                                    providerServiceTitle = providerServiceTitle,
                                    providerModelTitle = providerModelTitle,
                                    useChinese = useChinese,
                                    onConfirm = { actions.onDeleteProviderConfig(config.id) }
                                )
                            )
                        }
                    )
                }
            }
        }
    }

    if (showProviderEditor) {
        var clearApiKeyOnNextFocus by rememberSaveable(
            state.editingProviderConfigId,
            state.provider
        ) { mutableStateOf(true) }
        AlertDialog(
            onDismissRequest = {
                pendingCloseProviderEditor = false
                showProviderEditor = false
                providerMenuExpanded = false
            },
            containerColor = MaterialTheme.colorScheme.surface,
            titleContentColor = MaterialTheme.colorScheme.onSurface,
            textContentColor = MaterialTheme.colorScheme.onSurface,
            title = {
                Text(
                    if (isEditingSavedConfig) {
                        tr("Edit Provider", "编辑提供方")
                    } else {
                        tr("Add Provider", "新增提供方")
                    }
                )
            },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    ExposedDropdownMenuBox(
                        expanded = providerMenuExpanded,
                        onExpandedChange = { providerMenuExpanded = !providerMenuExpanded }
                    ) {
                        SettingsSelectField(
                            value = providerDisplayTitle(selectedProvider.id),
                            modifier = Modifier
                                .menuAnchor()
                                .fillMaxWidth(),
                            label = tr("Service", "服务"),
                            trailingIcon = {
                                ExposedDropdownMenuDefaults.TrailingIcon(expanded = providerMenuExpanded)
                            }
                        )
                        DropdownMenu(
                            expanded = providerMenuExpanded,
                            onDismissRequest = { providerMenuExpanded = false },
                            shape = settingsTextFieldShape(),
                            containerColor = MaterialTheme.colorScheme.surface,
                            tonalElevation = 0.dp,
                            shadowElevation = 0.dp,
                            border = settingsDropdownMenuBorder()
                        ) {
                            providerOptions.forEach { option ->
                                DropdownMenuItem(
                                    text = {
                                        ProviderDropdownText(providerId = option.id)
                                    },
                                    onClick = {
                                        actions.onProviderChange(option.id)
                                        providerMenuExpanded = false
                                    }
                                )
                            }
                        }
                    }
                    OutlinedButton(
                        onClick = {
                            providerPortalUrl?.let { openExternalUrl(context, it) }
                        },
                        enabled = providerPortalUrl != null,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.outlinedButtonColors(
                            containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.20f),
                            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                            disabledContainerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.12f),
                            disabledContentColor = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.42f)
                        )
                    ) {
                        Text(
                            text = providerPortalButtonText(
                                useChinese = useChinese,
                                providerTitle = providerDisplayTitle(selectedProvider.id),
                                enabled = providerPortalUrl != null
                            ),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.ArrowForward,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                    OutlinedTextField(
                        value = state.baseUrl,
                        onValueChange = actions.onBaseUrlChange,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(uiLabel("Endpoint URL")) },
                        singleLine = true,
                        shape = settingsTextFieldShape(),
                        textStyle = MaterialTheme.typography.bodyMedium,
                        colors = settingsTextFieldColors()
                    )
                    if (selectedProvider.id == "custom") {
                        OutlinedTextField(
                            value = state.providerCustomName,
                            onValueChange = actions.onProviderCustomNameChange,
                            modifier = Modifier.fillMaxWidth(),
                            label = {
                                Text(
                                    localizedText(
                                        "Custom Name",
                                        "自定义名称",
                                        useChinese = useChinese
                                    )
                                )
                            },
                            singleLine = true,
                            shape = settingsTextFieldShape(),
                            textStyle = MaterialTheme.typography.bodyMedium,
                            colors = settingsTextFieldColors()
                        )
                    }
                    ProviderModelField(
                        providerId = selectedProvider.id,
                        value = state.model,
                        onValueChange = actions.onModelChange,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = state.apiKeyDraft,
                        onValueChange = actions.onApiKeyChange,
                        modifier = Modifier
                            .fillMaxWidth()
                            .onFocusChanged { focusState ->
                                if (focusState.isFocused && clearApiKeyOnNextFocus) {
                                    if (state.apiKeyDraft.isNotBlank()) {
                                        actions.onApiKeyChange("")
                                    }
                                    clearApiKeyOnNextFocus = false
                                }
                            },
                        label = { Text(uiLabel("API Key")) },
                        singleLine = true,
                        visualTransformation = if (revealApiKey) VisualTransformation.None else PasswordVisualTransformation(),
                        shape = settingsTextFieldShape(),
                        textStyle = MaterialTheme.typography.bodyMedium,
                        colors = settingsTextFieldColors()
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        SettingsActionButton(
                            text = if (revealApiKey) uiLabel("Hide Key") else uiLabel("Show Key"),
                            icon = if (revealApiKey) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                            onClick = actions.onRevealToggle
                        )
                        SettingsActionButton(
                            text = if (state.providerTesting) uiLabel("Testing...") else uiLabel("Test API"),
                            icon = Icons.Rounded.Refresh,
                            onClick = actions.onTestProvider,
                            enabled = !state.providerTesting
                        )
                    }
                    state.info?.takeIf { it.isNotBlank() }?.let { info ->
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.28f),
                            contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                        ) {
                            Text(
                                text = localizedUiMessage(info, useChinese),
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        actions.onSaveProviderDraft()
                        pendingCloseProviderEditor = true
                    },
                    enabled = !state.saving &&
                        state.baseUrl.isNotBlank() &&
                        state.model.isNotBlank()
                ) {
                    Text(tr("Save", "保存"))
                }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = {
                        pendingCloseProviderEditor = false
                        showProviderEditor = false
                        providerMenuExpanded = false
                    }
                ) {
                    Text(tr("Cancel", "取消"))
                }
            }
        )
    }

    ProviderTokenUsageCard(
        state = state,
        useChinese = useChinese,
        onClear = {
            actions.onRequestConfirmation(
                clearTokenUsageConfirmation(
                    useChinese = useChinese,
                    onConfirm = actions.onClearProviderTokenStats
                )
            )
        }
    )
}

@Composable
private fun ProviderConfigRow(
    config: UiProviderConfig,
    providerServiceTitle: String,
    providerModelTitle: String,
    useChinese: Boolean,
    onEdit: () -> Unit,
    onSetActive: () -> Unit,
    onDelete: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        tonalElevation = if (config.enabled) 2.dp else 0.dp,
        shape = RoundedCornerShape(12.dp),
        color = if (config.enabled) {
            MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f)
        } else {
            MaterialTheme.colorScheme.surface
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = providerServiceTitle,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    ProviderActionButton(
                        icon = Icons.Outlined.Edit,
                        contentDescription = localizedText("Edit Provider", "编辑提供方", useChinese),
                        onClick = onEdit
                    )
                    ProviderActionButton(
                        icon = Icons.Rounded.CheckCircle,
                        contentDescription = localizedText("Use Provider", "启用提供方", useChinese),
                        onClick = onSetActive,
                        tint = if (config.enabled) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                    ProviderActionButton(
                        icon = Icons.Outlined.DeleteOutline,
                        contentDescription = localizedText("Delete Provider", "删除提供方", useChinese),
                        onClick = onDelete,
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }
            Text(
                text = providerModelTitle,
                modifier = Modifier.fillMaxWidth(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun ProviderTokenUsageCard(
    state: ProviderSettingsState,
    useChinese: Boolean,
    onClear: () -> Unit
) {
    val inputTokens = state.tokenInput.coerceAtLeast(0L)
    val outputTokens = state.tokenOutput.coerceAtLeast(0L)
    val totalTokens = state.tokenTotal.coerceAtLeast(0L)
    val cachedInputTokens = state.tokenCachedInput.coerceAtLeast(0L)
    val requests = state.tokenRequests.coerceAtLeast(0L)
    val cacheHitRate = if (inputTokens > 0L) {
        (cachedInputTokens.toDouble() / inputTokens.toDouble()) * 100.0
    } else {
        0.0
    }
    SettingsSectionCard(
        title = localizedText("Token Usage", "Token 统计", useChinese),
        subtitle = localizedText("Current totals for requests", "当前请求累计统计", useChinese),
        actions = {
            MinimalActionIconButton(onClick = onClear) {
                Icon(
                    imageVector = Icons.Outlined.DeleteOutline,
                    contentDescription = localizedText("Clear", "清除", useChinese)
                )
            }
        }
    ) {
        SettingsValueRow(localizedText("Input", "输入", useChinese), inputTokens.toString())
        SettingsValueRow(localizedText("Output", "输出", useChinese), outputTokens.toString())
        SettingsValueRow(localizedText("Total", "总计", useChinese), totalTokens.toString())
        SettingsValueRow(localizedText("Cached Input", "缓存输入", useChinese), cachedInputTokens.toString())
        SettingsValueRow(localizedText("Cache Hit Rate", "缓存命中率", useChinese), "${"%.1f".format(cacheHitRate)}%")
        SettingsValueRow(localizedText("Requests", "请求数", useChinese), requests.toString())
    }
}

private fun deleteProviderConfirmation(
    providerServiceTitle: String,
    providerModelTitle: String,
    useChinese: Boolean,
    onConfirm: () -> Unit
): SettingsConfirmationState {
    return SettingsConfirmationState(
        title = localizedText("Delete Provider", "删除提供方", useChinese),
        message = irreversibleConfirmMessage(
            prompt = localizedText(
                "Delete %1\$s / %2\$s?",
                "删除 %1\$s / %2\$s？",
                useChinese
            ).format(providerServiceTitle, providerModelTitle),
            useChinese = useChinese
        ),
        confirmLabel = localizedText("Delete", "删除", useChinese),
        onConfirm = onConfirm
    )
}

private fun clearTokenUsageConfirmation(
    useChinese: Boolean,
    onConfirm: () -> Unit
): SettingsConfirmationState {
    return SettingsConfirmationState(
        title = localizedText("Clear Token Usage", "清除 Token 统计", useChinese),
        message = irreversibleConfirmMessage(
            prompt = localizedText("Clear token usage totals?", "清除 Token 统计？", useChinese),
            useChinese = useChinese
        ),
        confirmLabel = localizedText("Clear", "清除", useChinese),
        onConfirm = onConfirm
    )
}
