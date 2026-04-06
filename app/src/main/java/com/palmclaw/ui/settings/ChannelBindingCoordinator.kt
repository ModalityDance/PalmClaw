package com.palmclaw.ui

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
    private val actions: Actions
) {
    data class Actions(
        val saveSessionChannelBinding: (SaveSessionChannelBindingRequest) -> Unit,
        val getSessionChannelDraft: (String) -> UiSessionChannelDraft,
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
        val refreshSessionConnectionStatus: () -> Unit
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
    ) = actions.saveSessionChannelBinding(
        SaveSessionChannelBindingRequest(
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

    fun getSessionChannelDraft(sessionId: String): UiSessionChannelDraft =
        actions.getSessionChannelDraft(sessionId)

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
}
