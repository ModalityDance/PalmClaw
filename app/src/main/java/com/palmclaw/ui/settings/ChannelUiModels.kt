package com.palmclaw.ui

/**
 * Models used by channel binding setup and connection overview screens.
 */
data class UiSessionChannelDraft(
    val enabled: Boolean = true,
    val channel: String = "",
    val chatId: String = "",
    val telegramBotToken: String = "",
    val telegramAllowedChatId: String = "",
    val discordBotToken: String = "",
    val discordResponseMode: String = "mention",
    val discordAllowedUserIds: String = "",
    val slackBotToken: String = "",
    val slackAppToken: String = "",
    val slackResponseMode: String = "mention",
    val slackAllowedUserIds: String = "",
    val feishuAppId: String = "",
    val feishuAppSecret: String = "",
    val feishuEncryptKey: String = "",
    val feishuVerificationToken: String = "",
    val feishuResponseMode: String = "mention",
    val feishuAllowedOpenIds: String = "",
    val emailConsentGranted: Boolean = false,
    val emailImapHost: String = "",
    val emailImapPort: String = "993",
    val emailImapUsername: String = "",
    val emailImapPassword: String = "",
    val emailSmtpHost: String = "",
    val emailSmtpPort: String = "587",
    val emailSmtpUsername: String = "",
    val emailSmtpPassword: String = "",
    val emailFromAddress: String = "",
    val emailAutoReplyEnabled: Boolean = true,
    val wecomBotId: String = "",
    val wecomSecret: String = "",
    val wecomAllowedUserIds: String = ""
)

data class UiConnectedChannelSummary(
    val sessionId: String,
    val sessionTitle: String,
    val channel: String,
    val chatId: String,
    val enabled: Boolean,
    val status: String
)

data class UiTelegramChatCandidate(
    val chatId: String,
    val title: String,
    val kind: String
)

data class UiFeishuChatCandidate(
    val chatId: String,
    val title: String,
    val kind: String,
    val note: String = ""
)

data class UiEmailSenderCandidate(
    val email: String,
    val subject: String,
    val note: String = ""
)

data class UiWeComChatCandidate(
    val chatId: String,
    val title: String,
    val kind: String,
    val note: String = ""
)
