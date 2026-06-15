package com.palmclaw.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

internal data class ChannelSettingsActions(
    val onCreateSessionRequest: () -> Unit,
    val onSetSessionChannelEnabled: (String, Boolean) -> Unit
)

@Composable
internal fun ChannelSettingsPage(
    state: ChannelsSettingsState,
    actions: ChannelSettingsActions
) {
    val routeSessions = state.sessions
    SettingsSectionCard(
        title = tr("Session Routes", "会话路由"),
        subtitle = if (routeSessions.isEmpty()) {
            tr("Create a session first, then connect it to a channel", "先创建一个会话，再把它连接到渠道")
        } else {
            tr("Manage channel bindings for each session", "管理每个会话的渠道绑定")
        }
    ) {
        if (routeSessions.isEmpty()) {
            EmptyChannelRoutesCard(onCreateSessionRequest = actions.onCreateSessionRequest)
        } else {
            routeSessions.forEach { session ->
                ChannelRouteRow(
                    session = session,
                    connectedChannels = state.connectedChannels,
                    onSetSessionChannelEnabled = actions.onSetSessionChannelEnabled
                )
            }
        }
    }

    ChannelDiagnosticsCard(state = state)
}

@Composable
private fun EmptyChannelRoutesCard(
    onCreateSessionRequest: () -> Unit
) {
    Surface(
        tonalElevation = 0.dp,
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.22f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = tr("No user-created sessions yet", "还没有用户创建的会话"),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = tr("Create a session from the chat sidebar first", "先从聊天侧边栏创建一个会话"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = tr(
                    "After that, open Session Settings for that session and configure its channel binding",
                    "然后打开该会话的会话设置，完成渠道绑定。"
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            SettingsActionButton(
                text = tr("Create Session", "创建会话"),
                icon = Icons.Rounded.Add,
                onClick = onCreateSessionRequest
            )
        }
    }
}

@Composable
private fun ChannelRouteRow(
    session: UiChannelSessionRoute,
    connectedChannels: List<UiConnectedChannelSummary>,
    onSetSessionChannelEnabled: (String, Boolean) -> Unit
) {
    val hasBinding = session.boundChannel.isNotBlank() &&
        (
            session.boundChatId.isNotBlank() ||
                session.pendingDetection
            )
    val channelSummary = if (hasBinding) {
        channelDisplayLabel(session.boundChannel)
    } else {
        tr("Not configured", "")
    }
    val status = connectedChannels
        .firstOrNull { it.sessionId == session.id }
        ?.status
        ?: if (hasBinding) uiLabel("Configured") else uiLabel("Not configured")
    val connectionSummary = if (hasBinding) {
        buildString {
            append(
                when {
                    session.boundChatId.isNotBlank() -> session.boundChatId
                    session.pendingDetection -> tr("Pending detection", "")
                    else -> tr("Not configured", "")
                }
            )
            append(" · ")
            append(uiLabel(status))
            if (!session.boundEnabled) {
                append(" · ")
                append(tr("Off", ""))
            }
        }
    } else {
        tr("Not configured", "")
    }
    Surface(
        tonalElevation = 1.dp,
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.22f)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = session.title,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = channelSummary,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = connectionSummary,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            PalmClawSwitch(
                checked = hasBinding && session.boundEnabled,
                onCheckedChange = { checked ->
                    if (hasBinding) {
                        onSetSessionChannelEnabled(session.id, checked)
                    }
                },
                enabled = hasBinding
            )
        }
    }
}

@Composable
private fun ChannelDiagnosticsCard(
    state: ChannelsSettingsState
) {
    val routeSessions = state.sessions
    val boundCount = state.connectedChannels.size
    val readyCount = state.connectedChannels.count {
        it.status.startsWith("Ready", ignoreCase = true) ||
            it.status.startsWith("Experimental", ignoreCase = true)
    }
    val issueCount = state.connectedChannels.count {
        !it.status.startsWith("Ready", ignoreCase = true) &&
            !it.status.startsWith("Experimental", ignoreCase = true)
    }
    val unboundCount = routeSessions.count { session ->
        session.boundChannel.isBlank() || (session.boundChatId.isBlank() && !session.pendingDetection)
    }
    val telegramBound = state.connectedChannels.count { it.channel.equals("telegram", ignoreCase = true) }
    val discordBound = state.connectedChannels.count { it.channel.equals("discord", ignoreCase = true) }
    val slackBound = state.connectedChannels.count { it.channel.equals("slack", ignoreCase = true) }
    val feishuBound = state.connectedChannels.count { it.channel.equals("feishu", ignoreCase = true) }
    val emailBound = state.connectedChannels.count { it.channel.equals("email", ignoreCase = true) }
    val wecomBound = state.connectedChannels.count { it.channel.equals("wecom", ignoreCase = true) }

    SettingsSectionCard(
        title = uiLabel("Connection Diagnostics"),
        subtitle = uiLabel("Session and route status")
    ) {
        SettingsValueRow(uiLabel("Gateway"), uiLabel(if (state.gatewayEnabled) "Enabled" else "Disabled"))
        SettingsValueRow(uiLabel("Sessions"), routeSessions.size.toString())
        SettingsValueRow(uiLabel("Bound"), boundCount.toString())
        SettingsValueRow(uiLabel("Ready"), readyCount.toString())
        SettingsValueRow(uiLabel("Issues"), issueCount.toString())
        SettingsValueRow(uiLabel("Unbound"), unboundCount.toString())
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
        SettingsValueRow(uiLabel("Telegram"), telegramBound.toString())
        SettingsValueRow(uiLabel("Discord"), discordBound.toString())
        SettingsValueRow(uiLabel("Slack"), slackBound.toString())
        SettingsValueRow(uiLabel("Feishu"), feishuBound.toString())
        SettingsValueRow(uiLabel("Email"), emailBound.toString())
        SettingsValueRow(uiLabel("WeCom"), wecomBound.toString())
    }
}
