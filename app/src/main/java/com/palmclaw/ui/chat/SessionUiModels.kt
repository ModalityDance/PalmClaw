package com.palmclaw.ui

/**
 * Presentation model for the session drawer and binding overview.
 */
data class UiSessionSummary(
    val id: String,
    val title: String,
    val isLocal: Boolean,
    val boundEnabled: Boolean = true,
    val boundChannel: String = "",
    val boundChatId: String = "",
    val boundTelegramBotToken: String = "",
    val boundTelegramAllowedChatId: String = "",
    val boundDiscordBotToken: String = "",
    val boundDiscordResponseMode: String = "mention",
    val boundDiscordAllowedUserIds: List<String> = emptyList(),
    val boundSlackBotToken: String = "",
    val boundSlackAppToken: String = "",
    val boundSlackResponseMode: String = "mention",
    val boundSlackAllowedUserIds: List<String> = emptyList(),
    val boundFeishuAppId: String = "",
    val boundFeishuAppSecret: String = "",
    val boundFeishuEncryptKey: String = "",
    val boundFeishuVerificationToken: String = "",
    val boundFeishuResponseMode: String = "mention",
    val boundFeishuAllowedOpenIds: List<String> = emptyList(),
    val boundEmailConsentGranted: Boolean = false,
    val boundEmailImapHost: String = "",
    val boundEmailImapPort: Int = 993,
    val boundEmailImapUsername: String = "",
    val boundEmailImapPassword: String = "",
    val boundEmailSmtpHost: String = "",
    val boundEmailSmtpPort: Int = 587,
    val boundEmailSmtpUsername: String = "",
    val boundEmailSmtpPassword: String = "",
    val boundEmailFromAddress: String = "",
    val boundEmailAutoReplyEnabled: Boolean = true,
    val boundWeComBotId: String = "",
    val boundWeComSecret: String = "",
    val boundWeComAllowedUserIds: List<String> = emptyList()
)
