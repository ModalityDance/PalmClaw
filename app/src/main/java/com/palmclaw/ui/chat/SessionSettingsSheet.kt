package com.palmclaw.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenu
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp

@Composable
internal fun rememberSessionSettingsDraftState(): SessionSettingsDraftState = remember { SessionSettingsDraftState() }

internal class SessionSettingsDraftState {
    var bindingEnabledDraft by mutableStateOf(true)
    var bindingChannelDraft by mutableStateOf("")
    var bindingChatIdDraft by mutableStateOf("")
    var bindingTelegramBotTokenDraft by mutableStateOf("")
    var bindingTelegramAllowedChatIdDraft by mutableStateOf("")
    var bindingDiscordBotTokenDraft by mutableStateOf("")
    var bindingDiscordResponseModeDraft by mutableStateOf("mention")
    var bindingDiscordAllowedUserIdsDraft by mutableStateOf("")
    var bindingSlackBotTokenDraft by mutableStateOf("")
    var bindingSlackAppTokenDraft by mutableStateOf("")
    var bindingSlackResponseModeDraft by mutableStateOf("mention")
    var bindingSlackAllowedUserIdsDraft by mutableStateOf("")
    var bindingFeishuAppIdDraft by mutableStateOf("")
    var bindingFeishuAppSecretDraft by mutableStateOf("")
    var bindingFeishuEncryptKeyDraft by mutableStateOf("")
    var bindingFeishuVerificationTokenDraft by mutableStateOf("")
    var bindingFeishuResponseModeDraft by mutableStateOf("mention")
    var bindingFeishuAllowedOpenIdsDraft by mutableStateOf("")
    var bindingEmailConsentGrantedDraft by mutableStateOf(true)
    var bindingEmailImapHostDraft by mutableStateOf("imap.gmail.com")
    var bindingEmailImapPortDraft by mutableStateOf("993")
    var bindingEmailImapUsernameDraft by mutableStateOf("")
    var bindingEmailImapPasswordDraft by mutableStateOf("")
    var bindingEmailSmtpHostDraft by mutableStateOf("smtp.gmail.com")
    var bindingEmailSmtpPortDraft by mutableStateOf("587")
    var bindingEmailSmtpUsernameDraft by mutableStateOf("")
    var bindingEmailSmtpPasswordDraft by mutableStateOf("")
    var bindingEmailFromAddressDraft by mutableStateOf("")
    var bindingEmailAutoReplyEnabledDraft by mutableStateOf(true)
    var bindingWeComBotIdDraft by mutableStateOf("")
    var bindingWeComSecretDraft by mutableStateOf("")
    var bindingWeComAllowedUserIdsDraft by mutableStateOf("")
    var bindingChannelMenuExpanded by mutableStateOf(false)
    var bindingDiscordResponseModeMenuExpanded by mutableStateOf(false)
    var bindingSlackResponseModeMenuExpanded by mutableStateOf(false)
    var bindingFeishuResponseModeMenuExpanded by mutableStateOf(false)
    var closeAfterDetectedBindingSave by mutableStateOf(false)
    var telegramAdvancedExpanded by mutableStateOf(false)
    var discordAdvancedExpanded by mutableStateOf(false)
    var slackAdvancedExpanded by mutableStateOf(false)
    var feishuAdvancedExpanded by mutableStateOf(false)
    var emailAdvancedExpanded by mutableStateOf(false)
    var weComAdvancedExpanded by mutableStateOf(false)

    fun closeMenus() {
        bindingChannelMenuExpanded = false
        bindingDiscordResponseModeMenuExpanded = false
        bindingSlackResponseModeMenuExpanded = false
        bindingFeishuResponseModeMenuExpanded = false
    }

    fun loadFrom(draft: UiSessionChannelDraft) {
        bindingEnabledDraft = draft.enabled
        bindingChannelDraft = draft.channel
        bindingChatIdDraft = draft.chatId
        bindingTelegramBotTokenDraft = draft.telegramBotToken
        bindingTelegramAllowedChatIdDraft = draft.telegramAllowedChatId
        bindingDiscordBotTokenDraft = draft.discordBotToken
        bindingSlackBotTokenDraft = draft.slackBotToken
        bindingSlackAppTokenDraft = draft.slackAppToken
        bindingFeishuAppIdDraft = draft.feishuAppId
        bindingFeishuAppSecretDraft = draft.feishuAppSecret
        bindingFeishuEncryptKeyDraft = draft.feishuEncryptKey
        bindingFeishuVerificationTokenDraft = draft.feishuVerificationToken
        bindingFeishuResponseModeDraft = draft.feishuResponseMode
        bindingEmailConsentGrantedDraft = true
        bindingEmailImapHostDraft = draft.emailImapHost
        bindingEmailImapPortDraft = draft.emailImapPort
        bindingEmailImapUsernameDraft = draft.emailImapUsername
        bindingEmailImapPasswordDraft = draft.emailImapPassword
        bindingEmailSmtpHostDraft = draft.emailSmtpHost
        bindingEmailSmtpPortDraft = draft.emailSmtpPort
        bindingEmailSmtpUsernameDraft = draft.emailSmtpUsername
        bindingEmailSmtpPasswordDraft = draft.emailSmtpPassword
        bindingEmailFromAddressDraft = draft.emailFromAddress
        bindingEmailAutoReplyEnabledDraft = draft.emailAutoReplyEnabled
        bindingWeComBotIdDraft = draft.wecomBotId
        bindingWeComSecretDraft = draft.wecomSecret
        bindingDiscordResponseModeDraft = draft.discordResponseMode
        bindingDiscordAllowedUserIdsDraft = draft.discordAllowedUserIds
        bindingSlackResponseModeDraft = draft.slackResponseMode
        bindingSlackAllowedUserIdsDraft = draft.slackAllowedUserIds
        bindingFeishuAllowedOpenIdsDraft = draft.feishuAllowedOpenIds
        bindingWeComAllowedUserIdsDraft = draft.wecomAllowedUserIds
        closeMenus()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SessionSettingsSheet(
    sessionId: String?,
    chatContentState: ChatContentState,
    channelsSettingsState: ChannelsSettingsState,
    sessionBindingState: SessionBindingState,
    settingsShellState: SettingsShellState,
    sessionSettingsPage: SessionSettingsPage,
    sessionSettingsDraft: SessionSettingsDraftState,
    revealApiKey: Boolean,
    vm: ChatViewModel,
    dismissSessionSettings: () -> Unit,
    onPageChange: (SessionSettingsPage) -> Unit,
    onCloseSessionSettings: () -> Unit
) {
    sessionId?.let { sessionId ->
        val item = chatContentState.sessions.firstOrNull { it.id == sessionId }
        if (item != null) {
            val normalizedChannel = sessionSettingsDraft.bindingChannelDraft.trim().lowercase()
            val channelLabel = when (normalizedChannel) {
                "telegram" -> uiLabel("Telegram")
                "discord" -> uiLabel("Discord")
                "slack" -> uiLabel("Slack")
                "feishu" -> uiLabel("Feishu")
                "email" -> uiLabel("Email")
                "wecom" -> uiLabel("WeCom")
                else -> uiLabel("None")
            }
            val connected = channelsSettingsState.connectedChannels.firstOrNull { it.sessionId == sessionId }
            val selectedTargetDisplay = when (normalizedChannel) {
                "telegram" -> sessionBindingState.telegramCandidates
                    .firstOrNull { it.chatId.trim() == sessionSettingsDraft.bindingChatIdDraft.trim() }
                    ?.let { candidate ->
                        if (candidate.title.isBlank() || candidate.title == candidate.chatId) {
                            candidate.chatId
                        } else {
                            "${candidate.title} · ${candidate.chatId}"
                        }
                    }
                "feishu" -> sessionBindingState.feishuCandidates
                    .firstOrNull { it.chatId.trim() == sessionSettingsDraft.bindingChatIdDraft.trim() }
                    ?.let { candidate ->
                        if (candidate.title.isBlank() || candidate.title == candidate.chatId) {
                            candidate.chatId
                        } else {
                            "${candidate.title} · ${candidate.chatId}"
                        }
                    }
                "email" -> sessionBindingState.emailCandidates
                    .firstOrNull { it.email.trim().equals(sessionSettingsDraft.bindingChatIdDraft.trim(), ignoreCase = true) }
                    ?.email
                "wecom" -> sessionBindingState.weComCandidates
                    .firstOrNull { it.chatId.trim() == sessionSettingsDraft.bindingChatIdDraft.trim() }
                    ?.let { candidate ->
                        if (candidate.title.isBlank() || candidate.title == candidate.chatId) {
                            candidate.chatId
                        } else {
                            "${candidate.title} · ${candidate.chatId}"
                        }
                    }
                else -> null
            }
            val hasPendingDetection = when (normalizedChannel) {
                "feishu" -> sessionSettingsDraft.bindingFeishuAppIdDraft.isNotBlank() && sessionSettingsDraft.bindingFeishuAppSecretDraft.isNotBlank() && sessionSettingsDraft.bindingChatIdDraft.isBlank()
                "email" -> sessionSettingsDraft.bindingEmailImapHostDraft.isNotBlank() &&
                    sessionSettingsDraft.bindingEmailImapUsernameDraft.isNotBlank() &&
                    sessionSettingsDraft.bindingEmailSmtpHostDraft.isNotBlank() &&
                    sessionSettingsDraft.bindingEmailSmtpUsernameDraft.isNotBlank() &&
                    sessionSettingsDraft.bindingChatIdDraft.isBlank()
                "wecom" -> sessionSettingsDraft.bindingWeComBotIdDraft.isNotBlank() && sessionSettingsDraft.bindingWeComSecretDraft.isNotBlank() && sessionSettingsDraft.bindingChatIdDraft.isBlank()
                else -> false
            }
            val targetLabel = when {
                sessionSettingsDraft.bindingChannelDraft.isBlank() -> uiLabel("This session stays local.")
                selectedTargetDisplay != null -> selectedTargetDisplay
                sessionSettingsDraft.bindingChatIdDraft.isNotBlank() -> sessionSettingsDraft.bindingChatIdDraft.trim()
                hasPendingDetection -> uiLabel("Waiting for detection")
                else -> tr("Not set", "")
            }
            val activeChannel = item.boundChannel.trim().lowercase()
            val activeChannelLabel = when (activeChannel) {
                "telegram" -> uiLabel("Telegram")
                "discord" -> uiLabel("Discord")
                "slack" -> uiLabel("Slack")
                "feishu" -> uiLabel("Feishu")
                "email" -> uiLabel("Email")
                "wecom" -> uiLabel("WeCom")
                else -> uiLabel("None")
            }
            val activeTargetLabel = when {
                activeChannel.isBlank() -> uiLabel("This session stays local.")
                connected?.chatId?.trim()?.isNotBlank() == true -> connected.chatId.trim()
                item.boundChatId.trim().isNotBlank() -> item.boundChatId.trim()
                else -> tr("Not set", "")
            }
            val activeEnabledLabel = when {
                activeChannel.isBlank() -> tr("Local only", "")
                item.boundEnabled -> tr("On", "")
                else -> tr("Off", "")
            }
            val activeConnectedLabel = when {
                activeChannel.isBlank() -> tr("Local only", "")
                connected?.status?.equals("Connected", ignoreCase = true) == true -> tr("Yes", "")
                else -> tr("No", "")
            }
            val sessionSettingsScrollState = rememberScrollState()
            AlertDialog(
                onDismissRequest = dismissSessionSettings,
                containerColor = MaterialTheme.colorScheme.surface,
                titleContentColor = MaterialTheme.colorScheme.onSurface,
                textContentColor = MaterialTheme.colorScheme.onSurface,
                title = {
                    Text(
                        text = when (sessionSettingsPage) {
                            SessionSettingsPage.Menu -> tr("Session Settings", "")
                            SessionSettingsPage.Configure -> tr("Channels", "")
                            SessionSettingsPage.Diagnostics -> tr("Connection Diagnostics", "")
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                },
                text = {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 460.dp)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .verticalScroll(sessionSettingsScrollState),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = item.title,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold
                            )
                            if (sessionSettingsPage == SessionSettingsPage.Menu) {
                                SettingsSectionCard(
                                    title = tr("Connection", ""),
                                    subtitle = tr("Current routing and status.", ""),
                                    actions = {
                                        SettingsSectionIconButton(
                                            icon = Icons.Rounded.Refresh,
                                            contentDescription = tr("Refresh connection status", ""),
                                            onClick = vm::refreshSessionConnectionStatus,
                                            containerSize = 30.dp,
                                            iconSize = 12.dp
                                        )
                                    }
                                ) {
                                    SettingsValueRow(tr("Channel", ""), activeChannelLabel.ifBlank { tr("Not selected", "") })
                                    SettingsValueRow(tr("Target", ""), activeTargetLabel)
                                    SettingsValueRow(tr("Enabled", ""), activeEnabledLabel)
                                    SettingsValueRow(tr("Connected", ""), activeConnectedLabel)
                                }
                                SettingsSectionCard(
                                    title = tr("Configure", ""),
                                    subtitle = tr("Channel settings.", ""),
                                    actions = {
                                        SettingsSectionIconButton(
                                            icon = Icons.Rounded.KeyboardArrowUp,
                                            contentDescription = tr("Open channel settings", ""),
                                            onClick = { onPageChange(SessionSettingsPage.Configure) },
                                            rotateZ = 90f,
                                            containerSize = 30.dp,
                                            iconSize = 12.dp
                                        )
                                    }
                                ) {
                                    SettingsValueRow(tr("Channel", ""), channelLabel.ifBlank { tr("Not selected", "") })
                                }
                            } else if (sessionSettingsPage == SessionSettingsPage.Diagnostics) {
                                SettingsSectionCard(
                                    title = tr("Connection", ""),
                                    subtitle = tr("Current routing and status.", ""),
                                    actions = {
                                        SettingsSectionIconButton(
                                            icon = Icons.Rounded.Refresh,
                                            contentDescription = tr("Refresh connection status", ""),
                                            onClick = vm::refreshSessionConnectionStatus,
                                            containerSize = 30.dp,
                                            iconSize = 12.dp
                                        )
                                    }
                                ) {
                                    SettingsValueRow(tr("Channel", ""), activeChannelLabel.ifBlank { tr("Not selected", "") })
                                    SettingsValueRow(tr("Target", ""), activeTargetLabel)
                                    SettingsValueRow(tr("Enabled", ""), activeEnabledLabel)
                                    SettingsValueRow(tr("Connected", ""), activeConnectedLabel)
                                }
                            } else {
                                Column(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalArrangement = Arrangement.spacedBy(2.dp)
                                ) {
                                    Text(
                                        text = uiLabel("Current Route"),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Text(
                                        text = when {
                                            sessionSettingsDraft.bindingChannelDraft.isBlank() -> uiLabel("Not connected")
                                            targetLabel != tr("Not set", "") &&
                                                targetLabel != uiLabel("Waiting for detection") ->
                                                "$channelLabel: $targetLabel"
                                            sessionSettingsDraft.bindingChannelDraft.equals("feishu", ignoreCase = true) &&
                                                sessionSettingsDraft.bindingFeishuAppIdDraft.isNotBlank() &&
                                                sessionSettingsDraft.bindingFeishuAppSecretDraft.isNotBlank() ->
                                                "${uiLabel("Feishu")}: ${uiLabel("Pending detection")}"
                                            sessionSettingsDraft.bindingChannelDraft.equals("email", ignoreCase = true) &&
                                                sessionSettingsDraft.bindingEmailConsentGrantedDraft &&
                                                sessionSettingsDraft.bindingEmailImapHostDraft.isNotBlank() &&
                                                sessionSettingsDraft.bindingEmailImapUsernameDraft.isNotBlank() &&
                                                sessionSettingsDraft.bindingEmailImapPasswordDraft.isNotBlank() &&
                                                sessionSettingsDraft.bindingEmailSmtpHostDraft.isNotBlank() &&
                                                sessionSettingsDraft.bindingEmailSmtpUsernameDraft.isNotBlank() &&
                                                sessionSettingsDraft.bindingEmailSmtpPasswordDraft.isNotBlank() ->
                                                "${uiLabel("Email")}: ${uiLabel("Pending detection")}"
                                            sessionSettingsDraft.bindingChannelDraft.equals("wecom", ignoreCase = true) &&
                                                sessionSettingsDraft.bindingWeComBotIdDraft.isNotBlank() &&
                                                sessionSettingsDraft.bindingWeComSecretDraft.isNotBlank() ->
                                                "${uiLabel("WeCom")}: ${uiLabel("Pending detection")}"
                                            else -> uiLabel("Not connected")
                                        },
                                        style = MaterialTheme.typography.bodySmall,
                                        fontWeight = FontWeight.Medium,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                }
                                ExposedDropdownMenuBox(
                                    expanded = sessionSettingsDraft.bindingChannelMenuExpanded,
                                    onExpandedChange = { sessionSettingsDraft.bindingChannelMenuExpanded = it }
                                ) {
                                    SettingsSelectField(
                                        value = channelLabel,
                                        modifier = Modifier
                                            .menuAnchor()
                                            .fillMaxWidth(),
                                        label = "Select Channel",
                                        trailingIcon = {
                                            ExposedDropdownMenuDefaults.TrailingIcon(expanded = sessionSettingsDraft.bindingChannelMenuExpanded)
                                        }
                                    )
                                    ExposedDropdownMenu(
                                        expanded = sessionSettingsDraft.bindingChannelMenuExpanded,
                                        onDismissRequest = { sessionSettingsDraft.bindingChannelMenuExpanded = false },
                                        shape = settingsTextFieldShape(),
                                        containerColor = MaterialTheme.colorScheme.surface,
                                        tonalElevation = 0.dp,
                                        shadowElevation = 0.dp,
                                        border = settingsDropdownMenuBorder()
                                    ) {
                                        DropdownMenuItem(
                                            text = {
                                                SettingsDropdownMenuText(text = "None")
                                            },
                                            onClick = {
                                                sessionSettingsDraft.closeAfterDetectedBindingSave = false
                                                sessionSettingsDraft.bindingChannelDraft = ""
                                                sessionSettingsDraft.bindingChatIdDraft = ""
                                                sessionSettingsDraft.bindingTelegramBotTokenDraft = ""
                                                sessionSettingsDraft.bindingTelegramAllowedChatIdDraft = ""
                                                sessionSettingsDraft.bindingDiscordBotTokenDraft = ""
                                                sessionSettingsDraft.bindingEnabledDraft = true
                                                sessionSettingsDraft.bindingDiscordResponseModeDraft = "mention"
                                                sessionSettingsDraft.bindingDiscordAllowedUserIdsDraft = ""
                                                sessionSettingsDraft.bindingSlackBotTokenDraft = ""
                                                sessionSettingsDraft.bindingSlackAppTokenDraft = ""
                                                sessionSettingsDraft.bindingSlackResponseModeDraft = "mention"
                                                sessionSettingsDraft.bindingSlackAllowedUserIdsDraft = ""
                                                sessionSettingsDraft.bindingFeishuAppIdDraft = ""
                                                sessionSettingsDraft.bindingFeishuAppSecretDraft = ""
                                                sessionSettingsDraft.bindingFeishuEncryptKeyDraft = ""
                                                sessionSettingsDraft.bindingFeishuVerificationTokenDraft = ""
                                                sessionSettingsDraft.bindingFeishuResponseModeDraft = "mention"
                                                sessionSettingsDraft.bindingFeishuAllowedOpenIdsDraft = ""
                                                sessionSettingsDraft.bindingEmailConsentGrantedDraft = true
                                                sessionSettingsDraft.bindingEmailImapHostDraft = "imap.gmail.com"
                                                sessionSettingsDraft.bindingEmailImapPortDraft = "993"
                                                sessionSettingsDraft.bindingEmailImapUsernameDraft = ""
                                                sessionSettingsDraft.bindingEmailImapPasswordDraft = ""
                                                sessionSettingsDraft.bindingEmailSmtpHostDraft = "smtp.gmail.com"
                                                sessionSettingsDraft.bindingEmailSmtpPortDraft = "587"
                                                sessionSettingsDraft.bindingEmailSmtpUsernameDraft = ""
                                                sessionSettingsDraft.bindingEmailSmtpPasswordDraft = ""
                                                sessionSettingsDraft.bindingEmailFromAddressDraft = ""
                                                sessionSettingsDraft.bindingEmailAutoReplyEnabledDraft = true
                                                sessionSettingsDraft.bindingWeComBotIdDraft = ""
                                                sessionSettingsDraft.bindingWeComSecretDraft = ""
                                                sessionSettingsDraft.bindingWeComAllowedUserIdsDraft = ""
                                                sessionSettingsDraft.bindingSlackResponseModeMenuExpanded = false
                                                sessionSettingsDraft.bindingFeishuResponseModeMenuExpanded = false
                                                sessionSettingsDraft.bindingChannelMenuExpanded = false
                                                vm.clearTelegramChatDiscovery()
                                                vm.clearFeishuChatDiscovery()
                                                vm.clearEmailSenderDiscovery()
                                                vm.clearWeComChatDiscovery()
                                            }
                                        )
                                        DropdownMenuItem(
                                            text = {
                                                SettingsDropdownMenuText(text = "Telegram")
                                            },
                                            onClick = {
                                                sessionSettingsDraft.closeAfterDetectedBindingSave = false
                                                sessionSettingsDraft.bindingChannelDraft = "telegram"
                                                sessionSettingsDraft.bindingDiscordResponseModeMenuExpanded = false
                                                sessionSettingsDraft.bindingSlackResponseModeMenuExpanded = false
                                                sessionSettingsDraft.bindingFeishuResponseModeMenuExpanded = false
                                                sessionSettingsDraft.bindingChannelMenuExpanded = false
                                                vm.clearFeishuChatDiscovery()
                                                vm.clearEmailSenderDiscovery()
                                                vm.clearWeComChatDiscovery()
                                            }
                                        )
                                        DropdownMenuItem(
                                            text = {
                                                SettingsDropdownMenuText(text = "Discord")
                                            },
                                            onClick = {
                                                sessionSettingsDraft.closeAfterDetectedBindingSave = false
                                                sessionSettingsDraft.bindingChannelDraft = "discord"
                                                if (sessionSettingsDraft.bindingDiscordResponseModeDraft.isBlank()) {
                                                    sessionSettingsDraft.bindingDiscordResponseModeDraft = "mention"
                                                }
                                                sessionSettingsDraft.bindingSlackResponseModeMenuExpanded = false
                                                sessionSettingsDraft.bindingFeishuResponseModeMenuExpanded = false
                                                sessionSettingsDraft.bindingChannelMenuExpanded = false
                                                vm.clearTelegramChatDiscovery()
                                                vm.clearFeishuChatDiscovery()
                                                vm.clearEmailSenderDiscovery()
                                                vm.clearWeComChatDiscovery()
                                            }
                                        )
                                        DropdownMenuItem(
                                            text = {
                                                SettingsDropdownMenuText(text = "Slack")
                                            },
                                            onClick = {
                                                sessionSettingsDraft.closeAfterDetectedBindingSave = false
                                                sessionSettingsDraft.bindingChannelDraft = "slack"
                                                if (sessionSettingsDraft.bindingSlackResponseModeDraft.isBlank()) {
                                                    sessionSettingsDraft.bindingSlackResponseModeDraft = "mention"
                                                }
                                                sessionSettingsDraft.bindingDiscordResponseModeMenuExpanded = false
                                                sessionSettingsDraft.bindingFeishuResponseModeMenuExpanded = false
                                                sessionSettingsDraft.bindingChannelMenuExpanded = false
                                                vm.clearTelegramChatDiscovery()
                                                vm.clearFeishuChatDiscovery()
                                                vm.clearEmailSenderDiscovery()
                                                vm.clearWeComChatDiscovery()
                                            }
                                        )
                                        DropdownMenuItem(
                                            text = {
                                                SettingsDropdownMenuText(text = "Feishu")
                                            },
                                            onClick = {
                                                sessionSettingsDraft.closeAfterDetectedBindingSave = false
                                                sessionSettingsDraft.bindingChannelDraft = "feishu"
                                                if (sessionSettingsDraft.bindingFeishuResponseModeDraft.isBlank()) {
                                                    sessionSettingsDraft.bindingFeishuResponseModeDraft = "mention"
                                                }
                                                sessionSettingsDraft.bindingDiscordResponseModeMenuExpanded = false
                                                sessionSettingsDraft.bindingSlackResponseModeMenuExpanded = false
                                                sessionSettingsDraft.bindingFeishuResponseModeMenuExpanded = false
                                                sessionSettingsDraft.bindingChannelMenuExpanded = false
                                                vm.clearTelegramChatDiscovery()
                                                vm.clearFeishuChatDiscovery()
                                                vm.clearEmailSenderDiscovery()
                                                vm.clearWeComChatDiscovery()
                                            }
                                        )
                                        DropdownMenuItem(
                                            text = {
                                                SettingsDropdownMenuText(text = "Email")
                                            },
                                            onClick = {
                                                sessionSettingsDraft.closeAfterDetectedBindingSave = false
                                                sessionSettingsDraft.bindingChannelDraft = "email"
                                                sessionSettingsDraft.bindingDiscordResponseModeMenuExpanded = false
                                                sessionSettingsDraft.bindingSlackResponseModeMenuExpanded = false
                                                sessionSettingsDraft.bindingFeishuResponseModeMenuExpanded = false
                                                sessionSettingsDraft.bindingChannelMenuExpanded = false
                                                vm.clearTelegramChatDiscovery()
                                                vm.clearFeishuChatDiscovery()
                                                vm.clearEmailSenderDiscovery()
                                                vm.clearWeComChatDiscovery()
                                            }
                                        )
                                        DropdownMenuItem(
                                            text = {
                                                SettingsDropdownMenuText(text = "WeCom")
                                            },
                                            onClick = {
                                                sessionSettingsDraft.closeAfterDetectedBindingSave = false
                                                sessionSettingsDraft.bindingChannelDraft = "wecom"
                                                sessionSettingsDraft.bindingDiscordResponseModeMenuExpanded = false
                                                sessionSettingsDraft.bindingSlackResponseModeMenuExpanded = false
                                                sessionSettingsDraft.bindingFeishuResponseModeMenuExpanded = false
                                                sessionSettingsDraft.bindingChannelMenuExpanded = false
                                                vm.clearTelegramChatDiscovery()
                                                vm.clearFeishuChatDiscovery()
                                                vm.clearEmailSenderDiscovery()
                                                vm.clearWeComChatDiscovery()
                                            }
                                        )
                                    }
                                }
                                if (sessionSettingsPage == SessionSettingsPage.Configure && normalizedChannel == "telegram") {
                            SessionSetupStepCard(
                                step = 1,
                                text = uiLabel("Open BotFather, send /newbot, then create a bot and copy its HTTP API token.")
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    SettingsActionButton(
                                        text = uiLabel("BotFather"),
                                        icon = Icons.Rounded.Description,
                                        onClick = { openExternalUrl(context, "https://t.me/BotFather") }
                                    )
                                    SettingsActionButton(
                                        text = uiLabel("Guide"),
                                        icon = Icons.Rounded.Description,
                                        onClick = { openExternalUrl(context, "https://core.telegram.org/bots#6-botfather") }
                                    )
                                }
                                SettingsInfoBlock(
                                    label = uiLabel("Token example"),
                                    value = "123456789:AAExampleBotTokenAbCdEfGhIjKlMnOpQrStUv"
                                )
                                SettingsTextField(
                                    value = sessionSettingsDraft.bindingTelegramBotTokenDraft,
                                    onValueChange = { sessionSettingsDraft.bindingTelegramBotTokenDraft = it },
                                    modifier = Modifier.fillMaxWidth(),
                                    singleLine = true,
                                    label = "Telegram Bot Token",
                                    visualTransformation = if (revealApiKey) VisualTransformation.None else PasswordVisualTransformation()
                                )
                            }
                            SessionSetupStepCard(
                                step = 2,
                                text = uiLabel("Paste the token, then tap Save at the bottom. This starts Telegram polling.")
                            )
                            SessionSetupStepCard(
                                step = 3,
                                text = uiLabel("From the Telegram account you want to bind, send one message to the bot.")
                            )
                            SessionSetupStepCard(
                                step = 4,
                                text = uiLabel("Tap Detect Chats, choose the conversation, then tap Save again to finish binding.")
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    SettingsActionButton(
                                        text = uiLabel("Detect Chats"),
                                        icon = Icons.Rounded.Refresh,
                                        onClick = {
                                            vm.discoverTelegramChatsForBinding(sessionSettingsDraft.bindingTelegramBotTokenDraft)
                                        },
                                        enabled = sessionSettingsDraft.bindingTelegramBotTokenDraft.isNotBlank() && !sessionBindingState.telegramDiscovering
                                    )
                                    if (sessionBindingState.telegramDiscovering) {
                                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                        Text(
                                            text = uiLabel("Detecting..."),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                                if (sessionBindingState.telegramCandidates.isNotEmpty()) {
                                    sessionBindingState.telegramCandidates.forEach { candidate ->
                                        val isSelected = sessionSettingsDraft.bindingChatIdDraft.trim() == candidate.chatId
                                        SessionSetupSelectableItemCard(
                                            selected = isSelected,
                                            title = candidate.title,
                                            subtitle = "${candidate.kind}: ${candidate.chatId}",
                                            onClick = {
                                                sessionSettingsDraft.bindingChatIdDraft = candidate.chatId
                                                sessionSettingsDraft.bindingTelegramAllowedChatIdDraft = candidate.chatId
                                                sessionSettingsDraft.closeAfterDetectedBindingSave = true
                                                vm.showSettingsInfo("Telegram chat selected. Tap Save again to finish binding.")
                                            }
                                        )
                                    }
                                }
                                SessionSetupFeedbackText(
                                    message = sessionBindingState.telegramInfo,
                                    visible = sessionBindingState.telegramDiscoveryAttempted,
                                    useChinese = settingsShellState.useChinese
                                )
                            }
                            SettingsAdvancedSection(
                                expanded = sessionSettingsDraft.telegramAdvancedExpanded,
                                onToggle = { sessionSettingsDraft.telegramAdvancedExpanded = !sessionSettingsDraft.telegramAdvancedExpanded }
                            ) {
                                SettingsAdvancedOptionCard(
                                    title = "Telegram Chat ID",
                                    description = "Manual target override. Usually filled by Detect Chats."
                                ) {
                                    SettingsTextField(
                                        value = sessionSettingsDraft.bindingChatIdDraft,
                                        onValueChange = { sessionSettingsDraft.bindingChatIdDraft = it },
                                        modifier = Modifier.fillMaxWidth(),
                                        singleLine = true,
                                        label = "Telegram Chat ID",
                                        placeholder = "Filled automatically after Detect Chats"
                                    )
                                }
                                SettingsAdvancedOptionCard(
                                    title = "Allowed Chat ID",
                                    description = "Restricts replies to one chat. Usually the same as Telegram Chat ID."
                                ) {
                                    SettingsTextField(
                                        value = sessionSettingsDraft.bindingTelegramAllowedChatIdDraft,
                                        onValueChange = { sessionSettingsDraft.bindingTelegramAllowedChatIdDraft = it },
                                        modifier = Modifier.fillMaxWidth(),
                                        singleLine = true,
                                        label = "Allowed Chat ID",
                                        placeholder = "Usually same as chat ID"
                                    )
                                }
                            }
                                } else if (sessionSettingsPage == SessionSettingsPage.Configure && normalizedChannel == "discord") {
                            SessionSetupStepCard(
                                step = 1,
                                text = uiLabel("Open the Discord Developer Portal, create an application, then open Bot and add a bot.")
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    SettingsActionButton(
                                        text = uiLabel("Developer Portal"),
                                        icon = Icons.Rounded.Description,
                                        onClick = { openExternalUrl(context, "https://discord.com/developers/applications") }
                                    )
                                }
                                SettingsInfoBlock(
                                    label = uiLabel("Token example"),
                                    value = "MTIzNDU2Nzg5MDEyMzQ1Njc4.GExample.AbcDefGhIjKlMnOpQrStUvWxYz"
                                )
                                SettingsTextField(
                                    value = sessionSettingsDraft.bindingDiscordBotTokenDraft,
                                    onValueChange = { sessionSettingsDraft.bindingDiscordBotTokenDraft = it },
                                    modifier = Modifier.fillMaxWidth(),
                                    singleLine = true,
                                    label = "Discord Bot Token",
                                    visualTransformation = if (revealApiKey) VisualTransformation.None else PasswordVisualTransformation()
                                )
                            }
                            SessionSetupStepCard(
                                step = 2,
                                text = uiLabel("In Bot settings, enable MESSAGE CONTENT INTENT. If you plan to use an allow list, enable SERVER MEMBERS INTENT too.")
                            )
                            SessionSetupStepCard(
                                step = 3,
                                text = uiLabel("Invite the bot to your server from OAuth2 URL Generator. Use scope bot and permissions Send Messages and Read Message History.")
                            )
                            SessionSetupStepCard(
                                step = 4,
                                text = uiLabel("Enable Developer Mode in Discord. Right-click your avatar and Copy User ID if you want an allow list. Right-click the target channel and Copy Channel ID.")
                            ) {
                                SettingsInfoBlock(
                                    label = uiLabel("User ID example"),
                                    value = "123456789012345678"
                                )
                                SettingsTextField(
                                    value = sessionSettingsDraft.bindingChatIdDraft,
                                    onValueChange = { sessionSettingsDraft.bindingChatIdDraft = it },
                                    modifier = Modifier.fillMaxWidth(),
                                    singleLine = true,
                                    label = "Target Channel ID",
                                    placeholder = "Example: 123456789012345678"
                                )
                            }
                            SessionSetupStepCard(
                                step = 5,
                                text = uiLabel("Choose how the bot should respond in this channel.")
                            ) {
                                ExposedDropdownMenuBox(
                                    expanded = sessionSettingsDraft.bindingDiscordResponseModeMenuExpanded,
                                    onExpandedChange = { sessionSettingsDraft.bindingDiscordResponseModeMenuExpanded = it }
                                ) {
                                    SettingsSelectField(
                                        value = uiLabel(sessionSettingsDraft.bindingDiscordResponseModeDraft.ifBlank { "mention" }),
                                        modifier = Modifier
                                            .menuAnchor()
                                            .fillMaxWidth(),
                                        label = "Response Mode",
                                        trailingIcon = {
                                            ExposedDropdownMenuDefaults.TrailingIcon(expanded = sessionSettingsDraft.bindingDiscordResponseModeMenuExpanded)
                                        }
                                    )
                                    ExposedDropdownMenu(
                                        expanded = sessionSettingsDraft.bindingDiscordResponseModeMenuExpanded,
                                        onDismissRequest = { sessionSettingsDraft.bindingDiscordResponseModeMenuExpanded = false },
                                        shape = settingsTextFieldShape(),
                                        containerColor = MaterialTheme.colorScheme.surface,
                                        tonalElevation = 0.dp,
                                        shadowElevation = 0.dp,
                                        border = settingsDropdownMenuBorder()
                                    ) {
                                        DropdownMenuItem(
                                            text = {
                                                SettingsDropdownMenuText(text = "mention")
                                            },
                                            onClick = {
                                                sessionSettingsDraft.bindingDiscordResponseModeDraft = "mention"
                                                sessionSettingsDraft.bindingDiscordResponseModeMenuExpanded = false
                                            }
                                        )
                                        DropdownMenuItem(
                                            text = {
                                                SettingsDropdownMenuText(text = "open")
                                            },
                                            onClick = {
                                                sessionSettingsDraft.bindingDiscordResponseModeDraft = "open"
                                                sessionSettingsDraft.bindingDiscordResponseModeMenuExpanded = false
                                            }
                                        )
                                    }
                                }
                                SettingsInfoBlock(
                                    label = uiLabel("Response modes"),
                                    value = uiLabel("mention: reply only when @mentioned. open: reply to all messages in this channel.")
                                )
                            }
                            SessionSetupStepCard(
                                step = 6,
                                text = uiLabel("After filling the fields, tap Save at the bottom to start the Discord connection.")
                            )
                            SettingsAdvancedSection(
                                expanded = sessionSettingsDraft.discordAdvancedExpanded,
                                onToggle = { sessionSettingsDraft.discordAdvancedExpanded = !sessionSettingsDraft.discordAdvancedExpanded }
                            ) {
                                SettingsAdvancedOptionCard(
                                    title = "Allowed User IDs",
                                    description = "Leave blank to allow anyone in the channel to trigger replies."
                                ) {
                                    SettingsTextField(
                                        value = sessionSettingsDraft.bindingDiscordAllowedUserIdsDraft,
                                        onValueChange = { sessionSettingsDraft.bindingDiscordAllowedUserIdsDraft = it },
                                        modifier = Modifier.fillMaxWidth(),
                                        minLines = 2,
                                        maxLines = 4,
                                        label = "Allowed User IDs",
                                        placeholder = "One ID per line or comma-separated"
                                    )
                                }
                            }
                                } else if (sessionSettingsPage == SessionSettingsPage.Configure && normalizedChannel == "slack") {
                            SessionSetupStepCard(
                                step = 1,
                                text = uiLabel("Create a Slack app from scratch, then enable Socket Mode.")
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    SettingsActionButton(
                                        text = uiLabel("Slack API"),
                                        icon = Icons.Rounded.Description,
                                        onClick = { openExternalUrl(context, "https://api.slack.com/apps") }
                                    )
                                }
                            }
                            SessionSetupStepCard(
                                step = 2,
                                text = uiLabel("Turn on Socket Mode, then create an app-level token with connections:write.")
                            ) {
                                SettingsInfoBlock(
                                    label = uiLabel("App token example"),
                                    value = "xapp-1-A1234567890-1234567890-abcdefghijklmnopqrstuvwxyz"
                                )
                                SettingsTextField(
                                    value = sessionSettingsDraft.bindingSlackAppTokenDraft,
                                    onValueChange = { sessionSettingsDraft.bindingSlackAppTokenDraft = it },
                                    modifier = Modifier.fillMaxWidth(),
                                    singleLine = true,
                                    label = "Slack App Token (xapp)",
                                    visualTransformation = if (revealApiKey) VisualTransformation.None else PasswordVisualTransformation()
                                )
                            }
                            SessionSetupStepCard(
                                step = 3,
                                text = uiLabel("Add bot scopes chat:write, reactions:write, app_mentions:read, then enable Event Subscriptions for message and app mention events.")
                            )
                            SessionSetupStepCard(
                                step = 4,
                                text = uiLabel("Install the app to your workspace, then copy the bot token.")
                            ) {
                                SettingsInfoBlock(
                                    label = uiLabel("Bot token example"),
                                    value = "xoxb-123456789012-123456789012-abcdefghijklmnopqrstuvwxyz"
                                )
                                SettingsTextField(
                                    value = sessionSettingsDraft.bindingSlackBotTokenDraft,
                                    onValueChange = { sessionSettingsDraft.bindingSlackBotTokenDraft = it },
                                    modifier = Modifier.fillMaxWidth(),
                                    singleLine = true,
                                    label = "Slack Bot Token (xoxb)",
                                    visualTransformation = if (revealApiKey) VisualTransformation.None else PasswordVisualTransformation()
                                )
                            }
                            SessionSetupStepCard(
                                step = 5,
                                text = uiLabel("Enter the target conversation ID. Slack channel, group, and DM IDs usually start with C, G, or D.")
                            ) {
                                SettingsTextField(
                                    value = sessionSettingsDraft.bindingChatIdDraft,
                                    onValueChange = { sessionSettingsDraft.bindingChatIdDraft = it },
                                    modifier = Modifier.fillMaxWidth(),
                                    singleLine = true,
                                    label = "Target Channel ID",
                                    placeholder = "Example: C123ABC45 or D123ABC45"
                                )
                            }
                            SessionSetupStepCard(
                                step = 6,
                                text = uiLabel("Choose how the bot should respond in this conversation.")
                            ) {
                                ExposedDropdownMenuBox(
                                    expanded = sessionSettingsDraft.bindingSlackResponseModeMenuExpanded,
                                    onExpandedChange = { sessionSettingsDraft.bindingSlackResponseModeMenuExpanded = it }
                                ) {
                                    SettingsSelectField(
                                        value = uiLabel(sessionSettingsDraft.bindingSlackResponseModeDraft.ifBlank { "mention" }),
                                        modifier = Modifier
                                            .menuAnchor()
                                            .fillMaxWidth(),
                                        label = "Response Mode",
                                        trailingIcon = {
                                            ExposedDropdownMenuDefaults.TrailingIcon(expanded = sessionSettingsDraft.bindingSlackResponseModeMenuExpanded)
                                        }
                                    )
                                    ExposedDropdownMenu(
                                        expanded = sessionSettingsDraft.bindingSlackResponseModeMenuExpanded,
                                        onDismissRequest = { sessionSettingsDraft.bindingSlackResponseModeMenuExpanded = false },
                                        shape = settingsTextFieldShape(),
                                        containerColor = MaterialTheme.colorScheme.surface,
                                        tonalElevation = 0.dp,
                                        shadowElevation = 0.dp,
                                        border = settingsDropdownMenuBorder()
                                    ) {
                                        DropdownMenuItem(
                                            text = {
                                                SettingsDropdownMenuText(text = "mention")
                                            },
                                            onClick = {
                                                sessionSettingsDraft.bindingSlackResponseModeDraft = "mention"
                                                sessionSettingsDraft.bindingSlackResponseModeMenuExpanded = false
                                            }
                                        )
                                        DropdownMenuItem(
                                            text = {
                                                SettingsDropdownMenuText(text = "open")
                                            },
                                            onClick = {
                                                sessionSettingsDraft.bindingSlackResponseModeDraft = "open"
                                                sessionSettingsDraft.bindingSlackResponseModeMenuExpanded = false
                                            }
                                        )
                                    }
                                }
                            }
                            SessionSetupStepCard(
                                step = 7,
                                text = uiLabel("After filling the fields, tap Save at the bottom to start the Slack connection.")
                            )
                            SettingsAdvancedSection(
                                expanded = sessionSettingsDraft.slackAdvancedExpanded,
                                onToggle = { sessionSettingsDraft.slackAdvancedExpanded = !sessionSettingsDraft.slackAdvancedExpanded }
                            ) {
                                SettingsAdvancedOptionCard(
                                    title = "Allowed User IDs",
                                    description = "Leave blank to allow anyone in this conversation to trigger replies."
                                ) {
                                    SettingsTextField(
                                        value = sessionSettingsDraft.bindingSlackAllowedUserIdsDraft,
                                        onValueChange = { sessionSettingsDraft.bindingSlackAllowedUserIdsDraft = it },
                                        modifier = Modifier.fillMaxWidth(),
                                        minLines = 2,
                                        maxLines = 4,
                                        label = "Allowed User IDs",
                                        placeholder = "One ID per line or comma-separated"
                                    )
                                }
                            }
                                } else if (sessionSettingsPage == SessionSettingsPage.Configure && normalizedChannel == "feishu") {
                            SessionSetupStepCard(
                                step = 1,
                                text = uiLabel("Create a Feishu app in Feishu Open Platform, enable Bot capability, then copy App ID and App Secret.")
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    SettingsActionButton(
                                        text = uiLabel("Open Platform"),
                                        icon = Icons.Rounded.Description,
                                        onClick = { openExternalUrl(context, "https://open.feishu.cn/") }
                                    )
                                }
                                SettingsTextField(
                                    value = sessionSettingsDraft.bindingFeishuAppIdDraft,
                                    onValueChange = { sessionSettingsDraft.bindingFeishuAppIdDraft = it },
                                    modifier = Modifier.fillMaxWidth(),
                                    singleLine = true,
                                    label = "Feishu App ID"
                                )
                                SettingsTextField(
                                    value = sessionSettingsDraft.bindingFeishuAppSecretDraft,
                                    onValueChange = { sessionSettingsDraft.bindingFeishuAppSecretDraft = it },
                                    modifier = Modifier.fillMaxWidth(),
                                    singleLine = true,
                                    label = "Feishu App Secret",
                                    visualTransformation = if (revealApiKey) VisualTransformation.None else PasswordVisualTransformation()
                                )
                            }
                            SessionSetupStepCard(
                                step = 2,
                                text = uiLabel("After filling App ID and App Secret, tap Save once at the bottom so PalmClaw starts Long Connection.")
                            )
                            SessionSetupStepCard(
                                step = 3,
                                text = uiLabel("In Events & Callbacks, select Long Connection, then add im.message.receive_v1.")
                            )
                            SessionSetupStepCard(
                                step = 4,
                                text = uiLabel("In Permission Management, add im:message and im:message.p2p_msg:readonly. If you test with @ in a group, also add im:message.group_at_msg:readonly.")
                            )
                            SessionSetupStepCard(
                                step = 5,
                                text = uiLabel("Publish the app, open it in Feishu, and confirm Long Connection while PalmClaw is running.")
                            )
                            SessionSetupStepCard(
                                step = 6,
                                text = uiLabel("Choose how this Feishu binding should react to incoming messages.")
                            ) {
                                ExposedDropdownMenuBox(
                                    expanded = sessionSettingsDraft.bindingFeishuResponseModeMenuExpanded,
                                    onExpandedChange = { sessionSettingsDraft.bindingFeishuResponseModeMenuExpanded = it }
                                ) {
                                    SettingsSelectField(
                                        value = uiLabel(sessionSettingsDraft.bindingFeishuResponseModeDraft.ifBlank { "mention" }),
                                        modifier = Modifier
                                            .menuAnchor()
                                            .fillMaxWidth(),
                                        label = "Response Mode",
                                        trailingIcon = {
                                            ExposedDropdownMenuDefaults.TrailingIcon(expanded = sessionSettingsDraft.bindingFeishuResponseModeMenuExpanded)
                                        }
                                    )
                                    ExposedDropdownMenu(
                                        expanded = sessionSettingsDraft.bindingFeishuResponseModeMenuExpanded,
                                        onDismissRequest = { sessionSettingsDraft.bindingFeishuResponseModeMenuExpanded = false },
                                        shape = settingsTextFieldShape(),
                                        containerColor = MaterialTheme.colorScheme.surface,
                                        tonalElevation = 0.dp,
                                        shadowElevation = 0.dp,
                                        border = settingsDropdownMenuBorder()
                                    ) {
                                        DropdownMenuItem(
                                            text = {
                                                SettingsDropdownMenuText(text = "mention")
                                            },
                                            onClick = {
                                                sessionSettingsDraft.bindingFeishuResponseModeDraft = "mention"
                                                sessionSettingsDraft.bindingFeishuResponseModeMenuExpanded = false
                                            }
                                        )
                                        DropdownMenuItem(
                                            text = {
                                                SettingsDropdownMenuText(text = "open")
                                            },
                                            onClick = {
                                                sessionSettingsDraft.bindingFeishuResponseModeDraft = "open"
                                                sessionSettingsDraft.bindingFeishuResponseModeMenuExpanded = false
                                            }
                                        )
                                    }
                                }
                                SettingsInfoBlock(
                                    label = uiLabel("Response modes"),
                                    value = uiLabel("mention: text messages require @bot. file, image, audio, and media messages can still arrive directly. open: reply to all messages in this chat.")
                                )
                            }
                            SessionSetupStepCard(
                                step = 7,
                                text = uiLabel("If you keep mention mode, send one @mention text message once before using Detect Chats. File messages do not need @.")
                            )
                            SessionSetupStepCard(
                                step = 8,
                                text = uiLabel("Tap Detect Chats, choose the conversation to bind, then tap Save again.")
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    SettingsActionButton(
                                        text = uiLabel("Detect Chats"),
                                        icon = Icons.Rounded.Refresh,
                                        onClick = {
                                            vm.discoverFeishuChatsForBinding(
                                                appId = sessionSettingsDraft.bindingFeishuAppIdDraft,
                                                appSecret = sessionSettingsDraft.bindingFeishuAppSecretDraft,
                                                encryptKey = sessionSettingsDraft.bindingFeishuEncryptKeyDraft,
                                                verificationToken = sessionSettingsDraft.bindingFeishuVerificationTokenDraft
                                            )
                                        },
                                        enabled = !sessionBindingState.feishuDiscovering
                                    )
                                    if (sessionBindingState.feishuDiscovering) {
                                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                        Text(
                                            text = uiLabel("Detecting..."),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                                if (sessionBindingState.feishuCandidates.isNotEmpty()) {
                                    sessionBindingState.feishuCandidates.forEach { candidate ->
                                        val isSelected = sessionSettingsDraft.bindingChatIdDraft.trim() == candidate.chatId
                                        SessionSetupSelectableItemCard(
                                            selected = isSelected,
                                            title = candidate.title,
                                            subtitle = "${candidate.kind}: ${candidate.chatId}",
                                            note = candidate.note.takeIf { it.isNotBlank() }?.let {
                                                localizedUiMessage(it, settingsShellState.useChinese)
                                            }.orEmpty(),
                                            onClick = {
                                                sessionSettingsDraft.bindingChatIdDraft = candidate.chatId
                                                sessionSettingsDraft.closeAfterDetectedBindingSave = true
                                                vm.showSettingsInfo("Feishu chat selected. Tap Save again to finish binding.")
                                            }
                                        )
                                    }
                                }
                                SessionSetupFeedbackText(
                                    message = sessionBindingState.feishuInfo,
                                    visible = sessionBindingState.feishuDiscoveryAttempted,
                                    useChinese = settingsShellState.useChinese
                                )
                            }
                            SettingsAdvancedSection(
                                expanded = sessionSettingsDraft.feishuAdvancedExpanded,
                                onToggle = { sessionSettingsDraft.feishuAdvancedExpanded = !sessionSettingsDraft.feishuAdvancedExpanded }
                            ) {
                                SettingsAdvancedOptionCard(
                                    title = "Encrypt Key",
                                    description = "Only fill this if your Feishu app requires encrypted events."
                                ) {
                                    SettingsTextField(
                                        value = sessionSettingsDraft.bindingFeishuEncryptKeyDraft,
                                        onValueChange = { sessionSettingsDraft.bindingFeishuEncryptKeyDraft = it },
                                        modifier = Modifier.fillMaxWidth(),
                                        singleLine = true,
                                        label = "Encrypt Key"
                                    )
                                }
                                SettingsAdvancedOptionCard(
                                    title = "Verification Token",
                                    description = "Only fill this if your Feishu app has a verification token configured."
                                ) {
                                    SettingsTextField(
                                        value = sessionSettingsDraft.bindingFeishuVerificationTokenDraft,
                                        onValueChange = { sessionSettingsDraft.bindingFeishuVerificationTokenDraft = it },
                                        modifier = Modifier.fillMaxWidth(),
                                        singleLine = true,
                                        label = "Verification Token"
                                    )
                                }
                                SettingsAdvancedOptionCard(
                                    title = "Target ID",
                                    description = "Manual target override. Usually filled automatically after Detect Chats."
                                ) {
                                    SettingsTextField(
                                        value = sessionSettingsDraft.bindingChatIdDraft,
                                        onValueChange = { sessionSettingsDraft.bindingChatIdDraft = it },
                                        modifier = Modifier.fillMaxWidth(),
                                        singleLine = true,
                                        label = "Target ID",
                                        placeholder = "Private: ou_xxx, Group: oc_xxx"
                                    )
                                }
                                SettingsAdvancedOptionCard(
                                    title = "Allowed Open IDs",
                                    description = "Restricts which senders can trigger replies for this binding."
                                ) {
                                    SettingsTextField(
                                        value = sessionSettingsDraft.bindingFeishuAllowedOpenIdsDraft,
                                        onValueChange = { sessionSettingsDraft.bindingFeishuAllowedOpenIdsDraft = it },
                                        modifier = Modifier.fillMaxWidth(),
                                        minLines = 2,
                                        maxLines = 4,
                                        label = "Allowed Open IDs",
                                        placeholder = "One open_id per line, or * to allow all"
                                    )
                                }
                            }
                                } else if (sessionSettingsPage == SessionSettingsPage.Configure && normalizedChannel == "email") {
                            SessionSetupStepCard(
                                step = 1,
                                text = uiLabel("Prepare a mailbox for the bot. IMAP is used to read mail and SMTP is used to send replies.")
                            )
                            SessionSetupStepCard(
                                step = 2,
                                text = uiLabel("Enter IMAP settings for receiving mail.")
                            ) {
                                SettingsTextField(
                                    value = sessionSettingsDraft.bindingEmailImapHostDraft,
                                    onValueChange = { sessionSettingsDraft.bindingEmailImapHostDraft = it },
                                    modifier = Modifier.fillMaxWidth(),
                                    singleLine = true,
                                    label = "IMAP Host"
                                )
                                SettingsTextField(
                                    value = sessionSettingsDraft.bindingEmailImapPortDraft,
                                    onValueChange = { sessionSettingsDraft.bindingEmailImapPortDraft = it },
                                    modifier = Modifier.fillMaxWidth(),
                                    singleLine = true,
                                    label = "IMAP Port",
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                                )
                                SettingsTextField(
                                    value = sessionSettingsDraft.bindingEmailImapUsernameDraft,
                                    onValueChange = {
                                        sessionSettingsDraft.bindingEmailImapUsernameDraft = it
                                        if (sessionSettingsDraft.bindingEmailFromAddressDraft.isBlank()) {
                                            sessionSettingsDraft.bindingEmailFromAddressDraft = it
                                        }
                                        if (sessionSettingsDraft.bindingEmailSmtpUsernameDraft.isBlank()) {
                                            sessionSettingsDraft.bindingEmailSmtpUsernameDraft = it
                                        }
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                    singleLine = true,
                                    label = "IMAP Username"
                                )
                                SettingsTextField(
                                    value = sessionSettingsDraft.bindingEmailImapPasswordDraft,
                                    onValueChange = { sessionSettingsDraft.bindingEmailImapPasswordDraft = it },
                                    modifier = Modifier.fillMaxWidth(),
                                    singleLine = true,
                                    label = "IMAP Password / App Password",
                                    visualTransformation = if (revealApiKey) VisualTransformation.None else PasswordVisualTransformation()
                                )
                            }
                            SessionSetupStepCard(
                                step = 3,
                                text = uiLabel("Enter SMTP settings for replies.")
                            ) {
                                SettingsTextField(
                                    value = sessionSettingsDraft.bindingEmailSmtpHostDraft,
                                    onValueChange = { sessionSettingsDraft.bindingEmailSmtpHostDraft = it },
                                    modifier = Modifier.fillMaxWidth(),
                                    singleLine = true,
                                    label = "SMTP Host"
                                )
                                SettingsTextField(
                                    value = sessionSettingsDraft.bindingEmailSmtpPortDraft,
                                    onValueChange = { sessionSettingsDraft.bindingEmailSmtpPortDraft = it },
                                    modifier = Modifier.fillMaxWidth(),
                                    singleLine = true,
                                    label = "SMTP Port",
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                                )
                                SettingsTextField(
                                    value = sessionSettingsDraft.bindingEmailSmtpUsernameDraft,
                                    onValueChange = { sessionSettingsDraft.bindingEmailSmtpUsernameDraft = it },
                                    modifier = Modifier.fillMaxWidth(),
                                    singleLine = true,
                                    label = "SMTP Username"
                                )
                                SettingsTextField(
                                    value = sessionSettingsDraft.bindingEmailSmtpPasswordDraft,
                                    onValueChange = { sessionSettingsDraft.bindingEmailSmtpPasswordDraft = it },
                                    modifier = Modifier.fillMaxWidth(),
                                    singleLine = true,
                                    label = "SMTP Password / App Password",
                                    visualTransformation = if (revealApiKey) VisualTransformation.None else PasswordVisualTransformation()
                                )
                                SettingsTextField(
                                    value = sessionSettingsDraft.bindingEmailFromAddressDraft,
                                    onValueChange = { sessionSettingsDraft.bindingEmailFromAddressDraft = it },
                                    modifier = Modifier.fillMaxWidth(),
                                    singleLine = true,
                                    label = "From Address"
                                )
                            }
                            SessionSetupStepCard(
                                step = 4,
                                text = uiLabel("Tap Save once to start mailbox polling, then send one email to this account.")
                            )
                            SessionSetupStepCard(
                                step = 5,
                                text = uiLabel("Tap Detect Senders, choose the sender address to bind, then tap Save again.")
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    SettingsActionButton(
                                        text = uiLabel("Detect Senders"),
                                        icon = Icons.Rounded.Refresh,
                                        onClick = {
                                            vm.discoverEmailSendersForBinding(
                                                consentGranted = true,
                                                imapHost = sessionSettingsDraft.bindingEmailImapHostDraft,
                                                imapPort = sessionSettingsDraft.bindingEmailImapPortDraft,
                                                imapUsername = sessionSettingsDraft.bindingEmailImapUsernameDraft,
                                                imapPassword = sessionSettingsDraft.bindingEmailImapPasswordDraft,
                                                smtpHost = sessionSettingsDraft.bindingEmailSmtpHostDraft,
                                                smtpPort = sessionSettingsDraft.bindingEmailSmtpPortDraft,
                                                smtpUsername = sessionSettingsDraft.bindingEmailSmtpUsernameDraft,
                                                smtpPassword = sessionSettingsDraft.bindingEmailSmtpPasswordDraft,
                                                fromAddress = sessionSettingsDraft.bindingEmailFromAddressDraft,
                                                autoReplyEnabled = sessionSettingsDraft.bindingEmailAutoReplyEnabledDraft
                                            )
                                        },
                                        enabled = sessionSettingsDraft.bindingEmailImapHostDraft.isNotBlank() &&
                                            sessionSettingsDraft.bindingEmailImapUsernameDraft.isNotBlank() &&
                                            sessionSettingsDraft.bindingEmailImapPasswordDraft.isNotBlank() &&
                                            !sessionBindingState.emailDiscovering
                                    )
                                    if (sessionBindingState.emailDiscovering) {
                                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                        Text(
                                            text = uiLabel("Detecting..."),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                                if (sessionBindingState.emailCandidates.isNotEmpty()) {
                                    sessionBindingState.emailCandidates.forEach { candidate ->
                                        val isSelected = sessionSettingsDraft.bindingChatIdDraft.trim().equals(candidate.email, ignoreCase = true)
                                        SessionSetupSelectableItemCard(
                                            selected = isSelected,
                                            title = candidate.email,
                                            subtitle = candidate.subject.takeIf { it.isNotBlank() }?.let {
                                                "${tr("Last subject", "")}: $it"
                                            } ?: candidate.email,
                                            note = candidate.note.takeIf { it.isNotBlank() }?.let {
                                                localizedUiMessage(it, settingsShellState.useChinese)
                                            }.orEmpty(),
                                            onClick = {
                                                sessionSettingsDraft.bindingChatIdDraft = candidate.email
                                                sessionSettingsDraft.closeAfterDetectedBindingSave = true
                                                vm.showSettingsInfo("Email sender selected. Tap Save again to finish binding.")
                                            }
                                        )
                                    }
                                }
                                SessionSetupFeedbackText(
                                    message = sessionBindingState.emailInfo,
                                    visible = sessionBindingState.emailDiscoveryAttempted,
                                    useChinese = settingsShellState.useChinese
                                )
                            }
                            SettingsAdvancedSection(
                                expanded = sessionSettingsDraft.emailAdvancedExpanded,
                                onToggle = { sessionSettingsDraft.emailAdvancedExpanded = !sessionSettingsDraft.emailAdvancedExpanded }
                            ) {
                                SettingsAdvancedOptionCard(
                                    title = "Auto reply",
                                    description = "Turn this off if you only want detection and manual replies."
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = uiLabel("Auto reply"),
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                        PalmClawSwitch(
                                            checked = sessionSettingsDraft.bindingEmailAutoReplyEnabledDraft,
                                            onCheckedChange = { sessionSettingsDraft.bindingEmailAutoReplyEnabledDraft = it }
                                        )
                                    }
                                }
                                SettingsAdvancedOptionCard(
                                    title = "Sender Email Address",
                                    description = "Manual sender override. Usually chosen from Detect Senders."
                                ) {
                                    SettingsTextField(
                                        value = sessionSettingsDraft.bindingChatIdDraft,
                                        onValueChange = { sessionSettingsDraft.bindingChatIdDraft = it },
                                        modifier = Modifier.fillMaxWidth(),
                                        singleLine = true,
                                        label = "Sender Email Address",
                                        placeholder = "someone@example.com"
                                    )
                                }
                            }
                                } else if (sessionSettingsPage == SessionSettingsPage.Configure && normalizedChannel == "wecom") {
                        SessionSetupStepCard(
                            step = 1,
                            text = uiLabel("In WeCom Admin, go to Security & Management > Management Tools, then create an AI Bot.")
                        )
                        SessionSetupStepCard(
                            step = 2,
                            text = uiLabel("Choose Manual Create, then choose API mode with long connection. Copy the Bot ID and Secret.")
                        )
                        SessionSetupStepCard(
                            step = 3,
                            text = uiLabel("Fill WeCom Bot ID and Secret below, then tap Save once to start the long connection.")
                        ) {
                            SettingsTextField(
                                value = sessionSettingsDraft.bindingWeComBotIdDraft,
                                onValueChange = { sessionSettingsDraft.bindingWeComBotIdDraft = it },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                    label = "WeCom Bot ID"
                                )
                                SettingsTextField(
                                    value = sessionSettingsDraft.bindingWeComSecretDraft,
                                    onValueChange = { sessionSettingsDraft.bindingWeComSecretDraft = it },
                                    modifier = Modifier.fillMaxWidth(),
                                    singleLine = true,
                                    label = "WeCom Secret",
                                    visualTransformation = if (revealApiKey) VisualTransformation.None else PasswordVisualTransformation()
                                )
                            }
                            SessionSetupStepCard(
                                step = 4,
                                text = uiLabel("After Save, go to Available Permissions and grant the message permission for the bot.")
                            )
                            SessionSetupStepCard(
                                step = 5,
                                text = uiLabel("Open the bot in WeCom and send one message so the app can detect the conversation.")
                            )
                            SessionSetupStepCard(
                                step = 6,
                                text = uiLabel("Tap Detect Chats, choose the conversation to bind, then tap Save again.")
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    SettingsActionButton(
                                        text = uiLabel("Detect Chats"),
                                        icon = Icons.Rounded.Refresh,
                                        onClick = {
                                            vm.discoverWeComChatsForBinding(
                                                botId = sessionSettingsDraft.bindingWeComBotIdDraft,
                                                secret = sessionSettingsDraft.bindingWeComSecretDraft
                                            )
                                        },
                                        enabled = !sessionBindingState.weComDiscovering
                                    )
                                    if (sessionBindingState.weComDiscovering) {
                                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                        Text(
                                            text = uiLabel("Detecting..."),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                                if (sessionBindingState.weComCandidates.isNotEmpty()) {
                                    sessionBindingState.weComCandidates.forEach { candidate ->
                                        val isSelected = sessionSettingsDraft.bindingChatIdDraft.trim() == candidate.chatId
                                        SessionSetupSelectableItemCard(
                                            selected = isSelected,
                                            title = candidate.title,
                                            subtitle = "${candidate.kind}: ${candidate.chatId}",
                                            note = candidate.note.takeIf { it.isNotBlank() }?.let {
                                                localizedUiMessage(it, settingsShellState.useChinese)
                                            }.orEmpty(),
                                            onClick = {
                                                sessionSettingsDraft.bindingChatIdDraft = candidate.chatId
                                                sessionSettingsDraft.closeAfterDetectedBindingSave = true
                                                vm.showSettingsInfo("WeCom chat selected. Tap Save again to finish binding.")
                                            }
                                        )
                                    }
                                }
                                SessionSetupFeedbackText(
                                    message = sessionBindingState.weComInfo,
                                    visible = sessionBindingState.weComDiscoveryAttempted,
                                    useChinese = settingsShellState.useChinese
                                )
                            }
                            SettingsAdvancedSection(
                                expanded = sessionSettingsDraft.weComAdvancedExpanded,
                                onToggle = { sessionSettingsDraft.weComAdvancedExpanded = !sessionSettingsDraft.weComAdvancedExpanded }
                            ) {
                                SettingsAdvancedOptionCard(
                                    title = "Target ID",
                                    description = "Manual target override. Use a detected chatId or a specific userId."
                                ) {
                                    SettingsTextField(
                                        value = sessionSettingsDraft.bindingChatIdDraft,
                                        onValueChange = { sessionSettingsDraft.bindingChatIdDraft = it },
                                        modifier = Modifier.fillMaxWidth(),
                                        singleLine = true,
                                        label = "Target ID",
                                        placeholder = "userId or detected chatId"
                                    )
                                }
                                SettingsAdvancedOptionCard(
                                    title = "Allowed User IDs",
                                    description = "Restricts which users can trigger replies. Use * to allow all."
                                ) {
                                    SettingsTextField(
                                        value = sessionSettingsDraft.bindingWeComAllowedUserIdsDraft,
                                        onValueChange = { sessionSettingsDraft.bindingWeComAllowedUserIdsDraft = it },
                                        modifier = Modifier.fillMaxWidth(),
                                        minLines = 2,
                                        maxLines = 4,
                                        label = "Allowed User IDs",
                                        placeholder = "One user ID per line, or * to allow all"
                                    )
                                }
                            }
                                } else if (sessionSettingsPage == SessionSettingsPage.Configure) {
                                    Text(
                                        text = uiLabel("Select a channel to configure binding for this session."),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                        if (
                            sessionSettingsPage == SessionSettingsPage.Configure &&
                            sessionSettingsScrollState.maxValue > 0 &&
                            sessionSettingsScrollState.value < sessionSettingsScrollState.maxValue
                        ) {
                            Surface(
                                shape = RoundedCornerShape(999.dp),
                                tonalElevation = 2.dp,
                                color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.95f),
                                modifier = Modifier
                                    .align(Alignment.BottomCenter)
                                    .padding(bottom = 6.dp)
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.KeyboardArrowDown,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSecondaryContainer,
                                        modifier = Modifier.size(14.dp)
                                    )
                                    Text(
                                        text = uiLabel("More settings below"),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSecondaryContainer
                                    )
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    when (sessionSettingsPage) {
                        SessionSettingsPage.Configure -> {
                            Button(
                                onClick = {
                                    vm.saveSessionChannelBinding(
                                        sessionId = sessionId,
                                        enabled = sessionSettingsDraft.bindingEnabledDraft,
                                        channel = sessionSettingsDraft.bindingChannelDraft,
                                        chatId = sessionSettingsDraft.bindingChatIdDraft,
                                        targetDisplayName = selectedTargetDisplay ?: sessionSettingsDraft.bindingChatIdDraft.trim(),
                                        telegramBotToken = sessionSettingsDraft.bindingTelegramBotTokenDraft,
                                        telegramAllowedChatId = sessionSettingsDraft.bindingTelegramAllowedChatIdDraft,
                                        discordBotToken = sessionSettingsDraft.bindingDiscordBotTokenDraft,
                                        discordResponseMode = sessionSettingsDraft.bindingDiscordResponseModeDraft,
                                        discordAllowedUserIds = sessionSettingsDraft.bindingDiscordAllowedUserIdsDraft,
                                        slackBotToken = sessionSettingsDraft.bindingSlackBotTokenDraft,
                                        slackAppToken = sessionSettingsDraft.bindingSlackAppTokenDraft,
                                        slackResponseMode = sessionSettingsDraft.bindingSlackResponseModeDraft,
                                        slackAllowedUserIds = sessionSettingsDraft.bindingSlackAllowedUserIdsDraft,
                                        feishuAppId = sessionSettingsDraft.bindingFeishuAppIdDraft,
                                        feishuAppSecret = sessionSettingsDraft.bindingFeishuAppSecretDraft,
                                        feishuEncryptKey = sessionSettingsDraft.bindingFeishuEncryptKeyDraft,
                                        feishuVerificationToken = sessionSettingsDraft.bindingFeishuVerificationTokenDraft,
                                        feishuResponseMode = sessionSettingsDraft.bindingFeishuResponseModeDraft,
                                        feishuAllowedOpenIds = sessionSettingsDraft.bindingFeishuAllowedOpenIdsDraft,
                                        emailConsentGranted = true,
                                        emailImapHost = sessionSettingsDraft.bindingEmailImapHostDraft,
                                        emailImapPort = sessionSettingsDraft.bindingEmailImapPortDraft,
                                        emailImapUsername = sessionSettingsDraft.bindingEmailImapUsernameDraft,
                                        emailImapPassword = sessionSettingsDraft.bindingEmailImapPasswordDraft,
                                        emailSmtpHost = sessionSettingsDraft.bindingEmailSmtpHostDraft,
                                        emailSmtpPort = sessionSettingsDraft.bindingEmailSmtpPortDraft,
                                        emailSmtpUsername = sessionSettingsDraft.bindingEmailSmtpUsernameDraft,
                                        emailSmtpPassword = sessionSettingsDraft.bindingEmailSmtpPasswordDraft,
                                        emailFromAddress = sessionSettingsDraft.bindingEmailFromAddressDraft,
                                        emailAutoReplyEnabled = sessionSettingsDraft.bindingEmailAutoReplyEnabledDraft,
                                        wecomBotId = sessionSettingsDraft.bindingWeComBotIdDraft,
                                        wecomSecret = sessionSettingsDraft.bindingWeComSecretDraft,
                                        wecomAllowedUserIds = sessionSettingsDraft.bindingWeComAllowedUserIdsDraft
                                    )
                                    sessionSettingsDraft.bindingChannelMenuExpanded = false
                                    sessionSettingsDraft.bindingDiscordResponseModeMenuExpanded = false
                                    sessionSettingsDraft.bindingSlackResponseModeMenuExpanded = false
                                    sessionSettingsDraft.bindingFeishuResponseModeMenuExpanded = false
                                },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.primary,
                                    contentColor = MaterialTheme.colorScheme.onPrimary,
                                    disabledContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.36f),
                                    disabledContentColor = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.56f)
                                )
                            ) {
                                Text(uiLabel("Save"))
                            }
                        }

                        else -> {
                            OutlinedButton(
                                onClick = {
                                    sessionSettingsDraft.bindingChannelMenuExpanded = false
                                    sessionSettingsDraft.bindingDiscordResponseModeMenuExpanded = false
                                    sessionSettingsDraft.bindingSlackResponseModeMenuExpanded = false
                                    sessionSettingsDraft.bindingFeishuResponseModeMenuExpanded = false
                                    vm.clearTelegramChatDiscovery()
                                    vm.clearFeishuChatDiscovery()
                                    vm.clearEmailSenderDiscovery()
                                    vm.clearWeComChatDiscovery()
                                    onCloseSessionSettings()
                                    onPageChange(SessionSettingsPage.Menu)
                                },
                                colors = ButtonDefaults.outlinedButtonColors(
                                    containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.32f),
                                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                                ),
                                border = BorderStroke(
                                    1.dp,
                                    MaterialTheme.colorScheme.outline.copy(alpha = 0.32f)
                                )
                            ) {
                                Text(uiLabel("Close"))
                            }
                        }
                    }
                },
                dismissButton = {
                    if (sessionSettingsPage != SessionSettingsPage.Menu) {
                        OutlinedButton(
                            onClick = {
                                sessionSettingsDraft.bindingChannelMenuExpanded = false
                                sessionSettingsDraft.bindingDiscordResponseModeMenuExpanded = false
                                sessionSettingsDraft.bindingSlackResponseModeMenuExpanded = false
                                sessionSettingsDraft.bindingFeishuResponseModeMenuExpanded = false
                                vm.clearTelegramChatDiscovery()
                                vm.clearFeishuChatDiscovery()
                                vm.clearEmailSenderDiscovery()
                                vm.clearWeComChatDiscovery()
                                onPageChange(SessionSettingsPage.Menu)
                            },
                            colors = ButtonDefaults.outlinedButtonColors(
                                containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.32f),
                                contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                            ),
                            border = BorderStroke(
                                1.dp,
                                MaterialTheme.colorScheme.outline.copy(alpha = 0.32f)
                            )
                        ) {
                            Text(uiLabel("Back"))
                        }
                    }
                }
            )
        } else {
            sessionSettingsDraft.bindingChannelMenuExpanded = false
            sessionSettingsDraft.bindingDiscordResponseModeMenuExpanded = false
            sessionSettingsDraft.bindingSlackResponseModeMenuExpanded = false
            sessionSettingsDraft.bindingFeishuResponseModeMenuExpanded = false
            vm.clearTelegramChatDiscovery()
            vm.clearFeishuChatDiscovery()
            vm.clearEmailSenderDiscovery()
            vm.clearWeComChatDiscovery()
            onCloseSessionSettings()
            onPageChange(SessionSettingsPage.Menu)
        }
    }
}
