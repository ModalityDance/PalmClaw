package com.palmclaw.ui

import com.palmclaw.config.SessionChannelBinding
import com.palmclaw.config.SessionChannelBindingRules
import com.palmclaw.ui.domain.ChannelBindingService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

internal data class SaveSessionChannelBindingRequest(
    val sessionId: String,
    val enabled: Boolean,
    val channel: String,
    val chatId: String,
    val targetDisplayName: String,
    val telegramBotToken: String,
    val telegramAllowedChatId: String,
    val discordBotToken: String,
    val discordResponseMode: String,
    val discordAllowedUserIds: String,
    val slackBotToken: String,
    val slackAppToken: String,
    val slackResponseMode: String,
    val slackAllowedUserIds: String,
    val feishuAppId: String,
    val feishuAppSecret: String,
    val feishuEncryptKey: String,
    val feishuVerificationToken: String,
    val feishuResponseMode: String,
    val feishuAllowedOpenIds: String,
    val emailConsentGranted: Boolean,
    val emailImapHost: String,
    val emailImapPort: String,
    val emailImapUsername: String,
    val emailImapPassword: String,
    val emailSmtpHost: String,
    val emailSmtpPort: String,
    val emailSmtpUsername: String,
    val emailSmtpPassword: String,
    val emailFromAddress: String,
    val emailAutoReplyEnabled: Boolean,
    val wecomBotId: String,
    val wecomSecret: String,
    val wecomAllowedUserIds: String
)

internal class ChannelBindingCoordinator(
    private val scope: CoroutineScope,
    private val stateStore: ChatStateStore,
    private val channelBindingService: ChannelBindingService,
    private val actions: Actions
) {
    data class Actions(
        val setSessionChannelEnabled: (String, Boolean) -> Unit,
        val discoverTelegramChatsForBinding: (String) -> Unit,
        val clearTelegramChatDiscovery: () -> Unit,
        val discoverFeishuChatsForBinding: (String, String, String, String) -> Unit,
        val clearFeishuChatDiscovery: () -> Unit,
        val discoverEmailSendersForBinding: (
            Boolean,
            String,
            String,
            String,
            String,
            String,
            String,
            String,
            String,
            String,
            Boolean
        ) -> Unit,
        val clearEmailSenderDiscovery: () -> Unit,
        val discoverWeComChatsForBinding: (String, String) -> Unit,
        val clearWeComChatDiscovery: () -> Unit,
        val refreshSessionConnectionStatus: () -> Unit,
        val refreshSessionBindingsInState: () -> Unit,
        val refreshGatewayRuntimeConfig: () -> Unit
    )

    @Suppress("LongParameterList")
    fun saveSessionChannelBinding(
        sessionId: String,
        enabled: Boolean,
        channel: String,
        chatId: String,
        targetDisplayName: String,
        telegramBotToken: String,
        telegramAllowedChatId: String,
        discordBotToken: String,
        discordResponseMode: String,
        discordAllowedUserIds: String,
        slackBotToken: String,
        slackAppToken: String,
        slackResponseMode: String,
        slackAllowedUserIds: String,
        feishuAppId: String,
        feishuAppSecret: String,
        feishuEncryptKey: String,
        feishuVerificationToken: String,
        feishuResponseMode: String,
        feishuAllowedOpenIds: String,
        emailConsentGranted: Boolean,
        emailImapHost: String,
        emailImapPort: String,
        emailImapUsername: String,
        emailImapPassword: String,
        emailSmtpHost: String,
        emailSmtpPort: String,
        emailSmtpUsername: String,
        emailSmtpPassword: String,
        emailFromAddress: String,
        emailAutoReplyEnabled: Boolean,
        wecomBotId: String,
        wecomSecret: String,
        wecomAllowedUserIds: String
    ) = saveSessionChannelBinding(
        request = SaveSessionChannelBindingRequest(
            sessionId = sessionId,
            enabled = enabled,
            channel = channel,
            chatId = chatId,
            targetDisplayName = targetDisplayName,
            telegramBotToken = telegramBotToken,
            telegramAllowedChatId = telegramAllowedChatId,
            discordBotToken = discordBotToken,
            discordResponseMode = discordResponseMode,
            discordAllowedUserIds = discordAllowedUserIds,
            slackBotToken = slackBotToken,
            slackAppToken = slackAppToken,
            slackResponseMode = slackResponseMode,
            slackAllowedUserIds = slackAllowedUserIds,
            feishuAppId = feishuAppId,
            feishuAppSecret = feishuAppSecret,
            feishuEncryptKey = feishuEncryptKey,
            feishuVerificationToken = feishuVerificationToken,
            feishuResponseMode = feishuResponseMode,
            feishuAllowedOpenIds = feishuAllowedOpenIds,
            emailConsentGranted = emailConsentGranted,
            emailImapHost = emailImapHost,
            emailImapPort = emailImapPort,
            emailImapUsername = emailImapUsername,
            emailImapPassword = emailImapPassword,
            emailSmtpHost = emailSmtpHost,
            emailSmtpPort = emailSmtpPort,
            emailSmtpUsername = emailSmtpUsername,
            emailSmtpPassword = emailSmtpPassword,
            emailFromAddress = emailFromAddress,
            emailAutoReplyEnabled = emailAutoReplyEnabled,
            wecomBotId = wecomBotId,
            wecomSecret = wecomSecret,
            wecomAllowedUserIds = wecomAllowedUserIds
        )
    )

    fun getSessionChannelDraft(sessionId: String): UiSessionChannelDraft {
        val sid = sessionId.trim()
        if (sid.isBlank()) return UiSessionChannelDraft()
        val binding = channelBindingService.getSessionChannelBindings()
            .firstOrNull { it.sessionId.trim() == sid }
        return UiSessionChannelDraft(
            enabled = binding?.enabled ?: true,
            channel = binding?.channel.orEmpty(),
            chatId = binding?.chatId.orEmpty(),
            telegramBotToken = binding?.telegramBotToken.orEmpty(),
            telegramAllowedChatId = binding?.telegramAllowedChatId.orEmpty(),
            discordBotToken = binding?.discordBotToken.orEmpty(),
            discordResponseMode = SessionChannelBindingRules.normalizeDiscordResponseMode(binding?.discordResponseMode.orEmpty()),
            discordAllowedUserIds = binding?.discordAllowedUserIds.orEmpty().joinToString("\n"),
            slackBotToken = binding?.slackBotToken.orEmpty(),
            slackAppToken = binding?.slackAppToken.orEmpty(),
            slackResponseMode = SessionChannelBindingRules.normalizeSlackResponseMode(binding?.slackResponseMode.orEmpty()),
            slackAllowedUserIds = binding?.slackAllowedUserIds.orEmpty().joinToString("\n"),
            feishuAppId = binding?.feishuAppId.orEmpty(),
            feishuAppSecret = binding?.feishuAppSecret.orEmpty(),
            feishuEncryptKey = binding?.feishuEncryptKey.orEmpty(),
            feishuVerificationToken = binding?.feishuVerificationToken.orEmpty(),
            feishuResponseMode = "mention",
            feishuAllowedOpenIds = binding?.feishuAllowedOpenIds.orEmpty().joinToString("\n"),
            emailConsentGranted = binding?.emailConsentGranted ?: false,
            emailImapHost = binding?.emailImapHost.orEmpty(),
            emailImapPort = (binding?.emailImapPort ?: 993).toString(),
            emailImapUsername = binding?.emailImapUsername.orEmpty(),
            emailImapPassword = binding?.emailImapPassword.orEmpty(),
            emailSmtpHost = binding?.emailSmtpHost.orEmpty(),
            emailSmtpPort = (binding?.emailSmtpPort ?: 587).toString(),
            emailSmtpUsername = binding?.emailSmtpUsername.orEmpty(),
            emailSmtpPassword = binding?.emailSmtpPassword.orEmpty(),
            emailFromAddress = binding?.emailFromAddress.orEmpty(),
            emailAutoReplyEnabled = binding?.emailAutoReplyEnabled ?: true,
            wecomBotId = binding?.wecomBotId.orEmpty(),
            wecomSecret = binding?.wecomSecret.orEmpty(),
            wecomAllowedUserIds = binding?.wecomAllowedUserIds.orEmpty().joinToString("\n")
        )
    }

    fun setSessionChannelEnabled(sessionId: String, enabled: Boolean) =
        actions.setSessionChannelEnabled(sessionId, enabled)

    fun discoverTelegramChatsForBinding(botToken: String) =
        actions.discoverTelegramChatsForBinding(botToken)

    fun clearTelegramChatDiscovery() = actions.clearTelegramChatDiscovery()

    fun discoverFeishuChatsForBinding(
        appId: String,
        appSecret: String,
        encryptKey: String,
        verificationToken: String
    ) = actions.discoverFeishuChatsForBinding(
        appId,
        appSecret,
        encryptKey,
        verificationToken
    )

    fun clearFeishuChatDiscovery() = actions.clearFeishuChatDiscovery()

    @Suppress("LongParameterList")
    fun discoverEmailSendersForBinding(
        consentGranted: Boolean,
        imapHost: String,
        imapPort: String,
        imapUsername: String,
        imapPassword: String,
        smtpHost: String,
        smtpPort: String,
        smtpUsername: String,
        smtpPassword: String,
        fromAddress: String,
        autoReplyEnabled: Boolean
    ) = actions.discoverEmailSendersForBinding(
        consentGranted,
        imapHost,
        imapPort,
        imapUsername,
        imapPassword,
        smtpHost,
        smtpPort,
        smtpUsername,
        smtpPassword,
        fromAddress,
        autoReplyEnabled
    )

    fun clearEmailSenderDiscovery() = actions.clearEmailSenderDiscovery()

    fun discoverWeComChatsForBinding(botId: String, secret: String) =
        actions.discoverWeComChatsForBinding(botId, secret)

    fun clearWeComChatDiscovery() = actions.clearWeComChatDiscovery()

    fun refreshSessionConnectionStatus() = actions.refreshSessionConnectionStatus()

    private fun saveSessionChannelBinding(request: SaveSessionChannelBindingRequest) {
        val sid = request.sessionId.trim()
        if (sid.isBlank()) return
        scope.launch {
            var runtimeChannelsEnabled = channelBindingService.getChannelsConfig().enabled
            var autoEnabledGateway = false
            var autoDisabledGateway = false
            runCatching {
                val normalizedChannel = SessionChannelBindingRules.normalizeChannel(request.channel)
                val normalizedAllowedChatId = request.telegramAllowedChatId.trim()
                val rawChatId = request.chatId.trim()
                val normalizedChatId = when (normalizedChannel) {
                    "discord" -> SessionChannelBindingRules.normalizeDiscordChannelId(rawChatId)
                    "slack" -> SessionChannelBindingRules.normalizeSlackChannelId(rawChatId)
                    "feishu" -> SessionChannelBindingRules.normalizeFeishuTargetId(rawChatId)
                    "email" -> SessionChannelBindingRules.normalizeEmailAddress(rawChatId)
                    "wecom" -> SessionChannelBindingRules.normalizeWeComTargetId(rawChatId)
                    "telegram" -> rawChatId.ifBlank { normalizedAllowedChatId }
                    else -> rawChatId
                }
                if (normalizedChannel.isBlank()) {
                    channelBindingService.clearSessionChannelBinding(sid)
                    runtimeChannelsEnabled = channelBindingService.getChannelsConfig().enabled
                } else {
                    val binding = buildValidatedBinding(
                        request = request,
                        sessionId = sid,
                        channel = normalizedChannel,
                        chatId = normalizedChatId,
                        telegramAllowedChatId = normalizedAllowedChatId
                    )
                    channelBindingService.saveSessionChannelBinding(binding)
                }
                val shouldEnableGateway = channelBindingService.getSessionChannelBindings()
                    .any { it.enabled && it.channel.trim().isNotBlank() }
                val currentChannels = channelBindingService.getChannelsConfig()
                runtimeChannelsEnabled = if (currentChannels.enabled == shouldEnableGateway) {
                    currentChannels.enabled
                } else {
                    if (shouldEnableGateway) autoEnabledGateway = true else autoDisabledGateway = true
                    channelBindingService.saveChannelsConfig(currentChannels.copy(enabled = shouldEnableGateway))
                    shouldEnableGateway
                }
            }.onSuccess {
                actions.refreshSessionBindingsInState()
                actions.refreshGatewayRuntimeConfig()
                val savedBinding = channelBindingService.getSessionChannelBindings()
                    .firstOrNull { it.sessionId.trim() == sid }
                val savedChannel = savedBinding?.channel.orEmpty()
                val savedTarget = normalizedTargetForInfo(savedBinding)
                val displayTarget = request.targetDisplayName.trim().ifBlank { savedTarget }
                val channelLabel = infoChannelLabel(savedChannel, stateStore.settingsShellState.value.useChinese)
                val baseInfo = when {
                    autoEnabledGateway -> "Session channel binding saved. Channels gateway enabled."
                    autoDisabledGateway -> "Session channel binding saved. Channels gateway disabled (no active session channel)."
                    savedChannel == "telegram" && savedTarget.isBlank() ->
                        "Telegram token saved. Tap Detect Chats, choose the conversation, then save again."
                    savedChannel == "feishu" && savedTarget.isBlank() ->
                        "Feishu credentials saved. Next, in Events & Callbacks select Long Connection and add im.message.receive_v1, then grant the message permissions, publish/open the app, send an @mention message, and use Detect Chats."
                    savedChannel == "email" && savedTarget.isBlank() ->
                        "Email account saved. Mailbox polling starting. Send one email to this account, then use Detect Senders to finish binding."
                    savedChannel == "wecom" && savedTarget.isBlank() ->
                        "WeCom credentials saved. Long connection starting. Keep PalmClaw open, send one message to the bot, then use Detect Chats."
                    savedChannel.isNotBlank() && displayTarget.isNotBlank() -> "Bound to $channelLabel: $displayTarget"
                    savedChannel.isNotBlank() -> "Saved $channelLabel binding."
                    else -> "Session channel binding saved."
                }
                stateStore.updateChannelsSettingsState {
                    it.copy(gatewayEnabled = runtimeChannelsEnabled)
                }
                stateStore.updateSettingsShellState {
                    it.copy(info = baseInfo)
                }
            }.onFailure { t ->
                stateStore.updateSettingsShellState {
                    it.copy(info = "Save session channel binding failed: ${t.message ?: t.javaClass.simpleName}")
                }
            }
        }
    }

    private fun buildValidatedBinding(
        request: SaveSessionChannelBindingRequest,
        sessionId: String,
        channel: String,
        chatId: String,
        telegramAllowedChatId: String
    ): SessionChannelBinding {
        val telegramToken = SessionChannelBindingRules.normalizeTelegramBotToken(request.telegramBotToken)
        val discordToken = request.discordBotToken.trim()
        val discordResponseMode = SessionChannelBindingRules.normalizeDiscordResponseMode(request.discordResponseMode)
        val discordAllowedUserIds = SessionChannelBindingRules.parseAllowedIdentifiers(request.discordAllowedUserIds)
        val slackBotToken = request.slackBotToken.trim()
        val slackAppToken = request.slackAppToken.trim()
        val slackResponseMode = SessionChannelBindingRules.normalizeSlackResponseMode(request.slackResponseMode)
        val slackAllowedUserIds = SessionChannelBindingRules.parseAllowedIdentifiers(request.slackAllowedUserIds)
        val feishuAppId = request.feishuAppId.trim()
        val feishuAppSecret = request.feishuAppSecret.trim()
        val feishuEncryptKey = request.feishuEncryptKey.trim()
        val feishuVerificationToken = request.feishuVerificationToken.trim()
        val feishuResponseMode = SessionChannelBindingRules.normalizeFeishuResponseMode(request.feishuResponseMode)
        val feishuAllowedOpenIds = SessionChannelBindingRules.parseAllowedIdentifiers(request.feishuAllowedOpenIds)
        val emailImapHost = request.emailImapHost.trim()
        val emailImapPort = request.emailImapPort.trim().toIntOrNull()
        val emailImapUsername = request.emailImapUsername.trim()
        val emailSmtpHost = request.emailSmtpHost.trim()
        val emailSmtpPort = request.emailSmtpPort.trim().toIntOrNull()
        val emailSmtpUsername = request.emailSmtpUsername.trim()
        val emailFromAddress = SessionChannelBindingRules.normalizeEmailAddress(request.emailFromAddress)
        val weComBotId = request.wecomBotId.trim()
        val weComSecret = request.wecomSecret.trim()
        val weComAllowedUserIds = SessionChannelBindingRules.parseAllowedIdentifiers(request.wecomAllowedUserIds)
        validateBinding(
            channel = channel,
            chatId = chatId,
            telegramToken = telegramToken,
            discordToken = discordToken,
            discordResponseMode = discordResponseMode,
            slackBotToken = slackBotToken,
            slackAppToken = slackAppToken,
            slackResponseMode = slackResponseMode,
            feishuAppId = feishuAppId,
            feishuAppSecret = feishuAppSecret,
            feishuResponseMode = feishuResponseMode,
            emailConsentGranted = request.emailConsentGranted,
            emailImapHost = emailImapHost,
            emailImapPort = emailImapPort,
            emailImapUsername = emailImapUsername,
            emailImapPassword = request.emailImapPassword,
            emailSmtpHost = emailSmtpHost,
            emailSmtpPort = emailSmtpPort,
            emailSmtpUsername = emailSmtpUsername,
            emailSmtpPassword = request.emailSmtpPassword,
            emailFromAddress = emailFromAddress,
            weComBotId = weComBotId,
            weComSecret = weComSecret
        )
        return SessionChannelBinding(
            sessionId = sessionId,
            enabled = request.enabled,
            channel = channel,
            chatId = chatId,
            telegramBotToken = telegramToken,
            telegramAllowedChatId = telegramAllowedChatId.ifBlank { null },
            discordBotToken = discordToken,
            discordResponseMode = discordResponseMode,
            discordAllowedUserIds = discordAllowedUserIds,
            slackBotToken = slackBotToken,
            slackAppToken = slackAppToken,
            slackResponseMode = slackResponseMode,
            slackAllowedUserIds = slackAllowedUserIds,
            feishuAppId = feishuAppId,
            feishuAppSecret = feishuAppSecret,
            feishuEncryptKey = feishuEncryptKey,
            feishuVerificationToken = feishuVerificationToken,
            feishuResponseMode = feishuResponseMode,
            feishuAllowedOpenIds = feishuAllowedOpenIds,
            emailConsentGranted = request.emailConsentGranted,
            emailImapHost = emailImapHost,
            emailImapPort = emailImapPort ?: 993,
            emailImapUsername = emailImapUsername,
            emailImapPassword = request.emailImapPassword,
            emailSmtpHost = emailSmtpHost,
            emailSmtpPort = emailSmtpPort ?: 587,
            emailSmtpUsername = emailSmtpUsername,
            emailSmtpPassword = request.emailSmtpPassword,
            emailFromAddress = emailFromAddress,
            emailAutoReplyEnabled = request.emailAutoReplyEnabled,
            wecomBotId = weComBotId,
            wecomSecret = weComSecret,
            wecomAllowedUserIds = weComAllowedUserIds
        )
    }

    @Suppress("LongParameterList")
    private fun validateBinding(
        channel: String,
        chatId: String,
        telegramToken: String,
        discordToken: String,
        discordResponseMode: String,
        slackBotToken: String,
        slackAppToken: String,
        slackResponseMode: String,
        feishuAppId: String,
        feishuAppSecret: String,
        feishuResponseMode: String,
        emailConsentGranted: Boolean,
        emailImapHost: String,
        emailImapPort: Int?,
        emailImapUsername: String,
        emailImapPassword: String,
        emailSmtpHost: String,
        emailSmtpPort: Int?,
        emailSmtpUsername: String,
        emailSmtpPassword: String,
        emailFromAddress: String,
        weComBotId: String,
        weComSecret: String
    ) {
        when (channel) {
            "telegram" -> {
                if (telegramToken.isBlank()) throw IllegalArgumentException("Telegram bot token is required")
                if (chatId.isNotBlank() && chatId.any { !it.isDigit() && it != '-' }) {
                    throw IllegalArgumentException("Telegram Chat ID must be numeric")
                }
            }
            "discord" -> {
                if (chatId.isBlank()) throw IllegalArgumentException("Discord Channel ID is required")
                if (!SessionChannelBindingRules.isDiscordSnowflake(chatId)) {
                    throw IllegalArgumentException("Discord Channel ID must be a numeric ID (15-30 digits)")
                }
                if (discordToken.isBlank()) throw IllegalArgumentException("Discord bot token is required")
                if (discordResponseMode !in setOf("mention", "open")) {
                    throw IllegalArgumentException("Discord response mode must be mention or open")
                }
            }
            "slack" -> {
                if (chatId.isBlank()) throw IllegalArgumentException("Slack channel ID is required")
                if (!SessionChannelBindingRules.isSlackChannelId(chatId)) {
                    throw IllegalArgumentException("Slack channel ID must look like C/G/D + letters/numbers")
                }
                if (slackBotToken.isBlank()) throw IllegalArgumentException("Slack bot token is required")
                if (slackAppToken.isBlank()) throw IllegalArgumentException("Slack app token is required")
                if (slackResponseMode !in setOf("mention", "open")) {
                    throw IllegalArgumentException("Slack response mode must be mention or open")
                }
            }
            "feishu" -> {
                if (feishuAppId.isBlank()) throw IllegalArgumentException("Feishu App ID is required")
                if (feishuAppSecret.isBlank()) throw IllegalArgumentException("Feishu App Secret is required")
                if (feishuResponseMode !in setOf("mention", "open")) {
                    throw IllegalArgumentException("Feishu response mode must be mention or open")
                }
                if (chatId.isNotBlank() && !SessionChannelBindingRules.isFeishuTargetId(chatId)) {
                    throw IllegalArgumentException("Feishu target must look like ou_xxx or oc_xxx")
                }
            }
            "email" -> {
                if (chatId.isNotBlank() && !isEmailAddress(chatId)) {
                    throw IllegalArgumentException("Email sender address is invalid")
                }
                if (!emailConsentGranted) throw IllegalArgumentException("Email mailbox consent must be enabled")
                if (emailImapHost.isBlank()) throw IllegalArgumentException("IMAP host is required")
                if (emailImapPort == null || emailImapPort !in 1..65535) {
                    throw IllegalArgumentException("IMAP port must be between 1 and 65535")
                }
                if (emailImapUsername.isBlank()) throw IllegalArgumentException("IMAP username is required")
                if (emailImapPassword.isBlank()) throw IllegalArgumentException("IMAP password is required")
                if (emailSmtpHost.isBlank()) throw IllegalArgumentException("SMTP host is required")
                if (emailSmtpPort == null || emailSmtpPort !in 1..65535) {
                    throw IllegalArgumentException("SMTP port must be between 1 and 65535")
                }
                if (emailSmtpUsername.isBlank()) throw IllegalArgumentException("SMTP username is required")
                if (emailSmtpPassword.isBlank()) throw IllegalArgumentException("SMTP password is required")
                if (emailFromAddress.isBlank() || !isEmailAddress(emailFromAddress)) {
                    throw IllegalArgumentException("From address is required")
                }
            }
            "wecom" -> {
                if (weComBotId.isBlank()) throw IllegalArgumentException("WeCom Bot ID is required")
                if (weComSecret.isBlank()) throw IllegalArgumentException("WeCom Secret is required")
            }
            else -> throw IllegalArgumentException("Unsupported channel: $channel")
        }
    }

    private fun normalizedTargetForInfo(binding: SessionChannelBinding?): String {
        if (binding == null) return ""
        return when (binding.channel.trim().lowercase()) {
            "discord" -> SessionChannelBindingRules.normalizeDiscordChannelId(binding.chatId)
            "slack" -> SessionChannelBindingRules.normalizeSlackChannelId(binding.chatId)
            "feishu" -> SessionChannelBindingRules.normalizeFeishuTargetId(binding.chatId)
            "email" -> SessionChannelBindingRules.normalizeEmailAddress(binding.chatId)
            "wecom" -> SessionChannelBindingRules.normalizeWeComTargetId(binding.chatId)
            else -> binding.chatId.trim()
        }
    }

    private fun infoChannelLabel(channel: String, useChinese: Boolean): String {
        return when (channel.trim().lowercase()) {
            "telegram" -> "Telegram"
            "discord" -> "Discord"
            "slack" -> "Slack"
            "feishu" -> if (useChinese) "飞书" else "Feishu"
            "email" -> if (useChinese) "邮箱" else "Email"
            "wecom" -> if (useChinese) "企业微信" else "WeCom"
            else -> if (useChinese) "渠道" else "channel"
        }
    }

    private fun isEmailAddress(value: String): Boolean {
        val normalized = value.trim()
        return normalized.isNotBlank() && android.util.Patterns.EMAIL_ADDRESS.matcher(normalized).matches()
    }
}
