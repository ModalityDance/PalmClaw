package com.palmclaw.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp

internal data class McpSettingsActions(
    val onMcpEnabledChange: (Boolean) -> Unit,
    val onAddMcpServer: () -> Unit,
    val onRemoveMcpServer: (String) -> Unit,
    val onMcpServerNameChange: (String, String) -> Unit,
    val onMcpServerUrlChange: (String, String) -> Unit,
    val onMcpAuthTokenChange: (String, String) -> Unit,
    val onMcpToolTimeoutSecondsChange: (String, String) -> Unit,
    val onRevealToggle: () -> Unit,
    val onRequestConfirmation: (SettingsConfirmationState) -> Unit
)

@Composable
internal fun McpSettingsPage(
    state: McpSettingsState,
    revealApiKey: Boolean,
    useChinese: Boolean,
    actions: McpSettingsActions
) {
    SettingsSectionCard(
        title = uiLabel("MCP Remote"),
        subtitle = tr(
            "Remote HTTPS only. Local HTTP allowed",
            "远程仅支持 HTTPS，本地可用 HTTP"
        )
    ) {
        SettingsToggleRow(
            title = uiLabel("Enable MCP Remote"),
            checked = state.enabled,
            onCheckedChange = actions.onMcpEnabledChange
        )
        SettingsActionButton(
            text = if (revealApiKey) uiLabel("Hide Tokens") else uiLabel("Show Tokens"),
            icon = if (revealApiKey) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
            onClick = actions.onRevealToggle
        )
    }
    SettingsSectionCard(
        title = uiLabel("Servers"),
        actions = {
            SettingsActionButton(
                text = uiLabel("Add Server"),
                icon = Icons.Rounded.Add,
                onClick = actions.onAddMcpServer
            )
        }
    ) {
        if (state.servers.isEmpty()) {
            Text(
                text = uiLabel("No MCP servers configured"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        state.servers.forEachIndexed { index, server ->
            McpServerCard(
                index = index,
                server = server,
                revealApiKey = revealApiKey,
                useChinese = useChinese,
                actions = actions
            )
        }
    }
}

@Composable
private fun McpServerCard(
    index: Int,
    server: UiMcpServerConfig,
    revealApiKey: Boolean,
    useChinese: Boolean,
    actions: McpSettingsActions
) {
    val serverDisplayName = server.serverName.trim().ifBlank {
        "${uiLabel("Server")} ${index + 1}"
    }
    val removeServerTitle = localizedText(
        "Remove Server",
        "移除 Server",
        useChinese = useChinese
    )
    val removeServerLabel = localizedText(
        "Remove",
        "移除",
        useChinese = useChinese
    )
    val removeServerMessage = irreversibleConfirmMessage(
        prompt = localizedText(
            "Remove '%s'?",
            "移除 '%s'？",
            useChinese = useChinese
        ).format(serverDisplayName),
        useChinese = useChinese
    )
    val serverUsableLabel = uiLabel(if (server.usable) "Usable" else "Unavailable")
    val serverStatusLabel = uiLabel(server.status)
    Surface(
        tonalElevation = 0.dp,
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.22f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    Text(
                        text = "${uiLabel("Server")} ${index + 1}",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "$serverUsableLabel · ${uiLabel("Status")}: $serverStatusLabel",
                        style = MaterialTheme.typography.labelSmall,
                        color = mcpStatusColor(server)
                    )
                }
                SettingsActionButton(
                    text = uiLabel("Remove"),
                    icon = Icons.Outlined.DeleteOutline,
                    onClick = {
                        actions.onRequestConfirmation(
                            SettingsConfirmationState(
                                title = removeServerTitle,
                                message = removeServerMessage,
                                confirmLabel = removeServerLabel,
                                onConfirm = { actions.onRemoveMcpServer(server.id) }
                            )
                        )
                    }
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                SettingsInfoBlock(
                    label = uiLabel("Status"),
                    value = server.status,
                    modifier = Modifier.weight(1f),
                    valueColor = mcpStatusValueColor(server),
                    maxLines = 1
                )
                SettingsInfoBlock(
                    label = uiLabel("Tools"),
                    value = server.toolCount.toString(),
                    modifier = Modifier.weight(1f),
                    maxLines = 1
                )
            }
            server.detail.takeIf { it.isNotBlank() }?.let {
                SettingsInfoBlock(
                    label = uiLabel("Detail"),
                    value = localizedUiMessage(it, useChinese),
                    maxLines = 3
                )
            }
            OutlinedTextField(
                value = server.serverName,
                onValueChange = { value -> actions.onMcpServerNameChange(server.id, value) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(uiLabel("Server Name")) },
                singleLine = true,
                shape = settingsTextFieldShape(),
                textStyle = MaterialTheme.typography.bodyMedium,
                colors = settingsTextFieldColors()
            )
            OutlinedTextField(
                value = server.serverUrl,
                onValueChange = { value -> actions.onMcpServerUrlChange(server.id, value) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(uiLabel("Endpoint URL")) },
                singleLine = true,
                shape = settingsTextFieldShape(),
                textStyle = MaterialTheme.typography.bodyMedium,
                colors = settingsTextFieldColors()
            )
            OutlinedTextField(
                value = server.authToken,
                onValueChange = { value -> actions.onMcpAuthTokenChange(server.id, value) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(uiLabel("Auth Token")) },
                singleLine = true,
                visualTransformation = if (revealApiKey) VisualTransformation.None else PasswordVisualTransformation(),
                shape = settingsTextFieldShape(),
                textStyle = MaterialTheme.typography.bodyMedium,
                colors = settingsTextFieldColors()
            )
            OutlinedTextField(
                value = server.toolTimeoutSeconds,
                onValueChange = { value -> actions.onMcpToolTimeoutSecondsChange(server.id, value) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(uiLabel("Tool Timeout (sec)")) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                shape = settingsTextFieldShape(),
                textStyle = MaterialTheme.typography.bodyMedium,
                colors = settingsTextFieldColors()
            )
        }
    }
}

@Composable
private fun mcpStatusColor(server: UiMcpServerConfig) = when (server.status.lowercase()) {
    "connected" -> if (server.usable) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.tertiary
    }
    "error" -> MaterialTheme.colorScheme.error
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

@Composable
private fun mcpStatusValueColor(server: UiMcpServerConfig) = when (server.status.lowercase()) {
    "connected" -> if (server.usable) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.tertiary
    }
    "error" -> MaterialTheme.colorScheme.error
    else -> MaterialTheme.colorScheme.onSurface
}
