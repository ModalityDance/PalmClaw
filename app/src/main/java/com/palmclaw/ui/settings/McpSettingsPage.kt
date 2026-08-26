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
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material.icons.outlined.WarningAmber
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
import com.palmclaw.mcp.McpEndpointDisposition
import com.palmclaw.mcp.McpEndpointIssue
import com.palmclaw.mcp.McpEndpointPolicy
import com.palmclaw.mcp.McpEndpointSecurity

internal data class McpSettingsActions(
    val onMcpEnabledChange: (Boolean) -> Unit,
    val onAddMcpServer: () -> Unit,
    val onRemoveMcpServer: (String) -> Unit,
    val onMcpServerNameChange: (String, String) -> Unit,
    val onMcpServerUrlChange: (String, String) -> Unit,
    val onMcpAuthTokenChange: (String, String) -> Unit,
    val onMcpToolTimeoutSecondsChange: (String, String) -> Unit,
    val onMcpInsecureHttpAllowedOriginChange: (String, String?) -> Unit,
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
            "HTTPS by default. Local and approved private-network HTTP are supported.",
            "默认使用 HTTPS；支持本机 HTTP 和经确认的私有网络 HTTP。"
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
        state.runtimeSnapshot.issues.forEach { issue ->
            SettingsInfoBlock(
                label = localizedText("Runtime issue", "运行时问题", useChinese),
                value = localizedUiMessage(issue.detail, useChinese),
                valueColor = MaterialTheme.colorScheme.error,
                maxLines = 4
            )
        }
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
                    label = uiLabel("Capabilities"),
                    value = "T ${server.toolCount} · R ${server.resourceCount} · RT ${server.resourceTemplateCount} · P ${server.promptCount}",
                    modifier = Modifier.weight(1f),
                    maxLines = 1
                )
            }
            listOfNotNull(
                server.transport?.replace('_', ' '),
                server.protocolVersion
            ).takeIf { it.isNotEmpty() }?.let { connection ->
                SettingsInfoBlock(
                    label = uiLabel("Protocol"),
                    value = connection.joinToString(" · "),
                    maxLines = 2
                )
            }
            server.detail.takeIf { it.isNotBlank() }?.let {
                SettingsInfoBlock(
                    label = uiLabel("Detail"),
                    value = localizedUiMessage(it, useChinese),
                    maxLines = 3
                )
            }
            server.insecureWarning?.takeIf { it.isNotBlank() }?.let {
                SettingsInfoBlock(
                    label = localizedText("Security warning", "安全警告", useChinese),
                    value = localizedUiMessage(it, useChinese),
                    valueColor = MaterialTheme.colorScheme.tertiary,
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
            McpEndpointSecurityNotice(
                server = server,
                useChinese = useChinese,
                actions = actions
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
private fun McpEndpointSecurityNotice(
    server: UiMcpServerConfig,
    useChinese: Boolean,
    actions: McpSettingsActions
) {
    if (server.serverUrl.isBlank()) return
    val decision = McpEndpointPolicy.evaluate(
        rawUrl = server.serverUrl,
        authToken = server.authToken,
        insecureHttpAllowedOrigin = server.insecureHttpAllowedOrigin
    )
    if (decision.security == McpEndpointSecurity.HTTPS) return

    val value = when {
        decision.issue == McpEndpointIssue.AUTH_REQUIRES_HTTPS -> localizedText(
            "Connection paused. Remove the auth token or use HTTPS.",
            "连接已暂停。请移除认证 Token 或改用 HTTPS。",
            useChinese
        )
        decision.issue == McpEndpointIssue.INSECURE_HTTP_CONFIRMATION_REQUIRED -> localizedText(
            "Connection paused. Confirm unencrypted HTTP access to ${decision.canonicalOrigin}.",
            "连接已暂停。请确认允许通过未加密 HTTP 访问 ${decision.canonicalOrigin}。",
            useChinese
        )
        decision.disposition == McpEndpointDisposition.REJECTED -> localizedText(
            decision.message,
            "该 MCP 地址不符合网络安全策略。",
            useChinese
        )
        decision.security == McpEndpointSecurity.PRIVATE_LAN_HTTP -> localizedText(
            "Unencrypted LAN HTTP is allowed only for ${decision.canonicalOrigin}.",
            "仅允许通过未加密 LAN HTTP 访问 ${decision.canonicalOrigin}。",
            useChinese
        )
        else -> localizedText(
            "Local HTTP is unencrypted. Use only with a trusted local server.",
            "本机 HTTP 未加密，请仅连接可信的本机服务。",
            useChinese
        )
    }
    SettingsInfoBlock(
        label = localizedText("Transport security", "传输安全", useChinese),
        value = value,
        valueColor = if (decision.canConnect) {
            MaterialTheme.colorScheme.tertiary
        } else {
            MaterialTheme.colorScheme.error
        },
        maxLines = 4
    )

    if (decision.issue == McpEndpointIssue.INSECURE_HTTP_CONFIRMATION_REQUIRED) {
        val origin = decision.canonicalOrigin ?: return
        SettingsActionButton(
            text = localizedText("Allow LAN HTTP", "允许 LAN HTTP", useChinese),
            icon = Icons.Outlined.WarningAmber,
            onClick = {
                actions.onRequestConfirmation(
                    SettingsConfirmationState(
                        title = localizedText(
                            "Allow Unencrypted LAN HTTP",
                            "允许未加密 LAN HTTP",
                            useChinese
                        ),
                        message = localizedText(
                            "Allow MCP to connect to $origin without encryption? Network traffic and tool data could be read or changed by other devices on the network. This approval applies only to this exact origin.",
                            "允许 MCP 通过未加密连接访问 $origin 吗？同一网络中的其他设备可能读取或修改网络流量与工具数据。此授权仅适用于这个准确的来源地址。",
                            useChinese
                        ),
                        confirmLabel = localizedText("Allow", "允许", useChinese),
                        onConfirm = {
                            actions.onMcpInsecureHttpAllowedOriginChange(server.id, origin)
                        }
                    )
                )
            }
        )
    } else if (
        decision.security == McpEndpointSecurity.PRIVATE_LAN_HTTP &&
        decision.canConnect
    ) {
        SettingsActionButton(
            text = localizedText("Revoke LAN HTTP", "撤销 LAN HTTP", useChinese),
            icon = Icons.Outlined.LockOpen,
            onClick = {
                actions.onMcpInsecureHttpAllowedOriginChange(server.id, null)
            }
        )
    }
}

@Composable
private fun mcpStatusColor(server: UiMcpServerConfig) = when (server.phase) {
    "ready" -> if (server.usable) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.tertiary
    }
    "error", "action_required" -> MaterialTheme.colorScheme.error
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

@Composable
private fun mcpStatusValueColor(server: UiMcpServerConfig) = when (server.phase) {
    "ready" -> if (server.usable) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.tertiary
    }
    "error", "action_required" -> MaterialTheme.colorScheme.error
    else -> MaterialTheme.colorScheme.onSurface
}
