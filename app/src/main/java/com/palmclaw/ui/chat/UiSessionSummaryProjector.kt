package com.palmclaw.ui

import com.palmclaw.config.AppSession
import com.palmclaw.config.SessionChannelBinding
import com.palmclaw.config.SessionChannelBindingRules
import com.palmclaw.storage.entities.SessionEntity

/**
 * Centralizes projection from persisted sessions and bindings into UI summaries.
 *
 * Keeping this mapping in one place prevents the binding fields on [UiSessionSummary]
 * from drifting when new channel properties are added.
 */
internal object UiSessionSummaryProjector {
    fun build(
        rawSessions: List<SessionEntity>,
        bindingsBySession: Map<String, SessionChannelBinding>
    ): List<UiSessionSummary> {
        val local = rawSessions.firstOrNull { it.id == AppSession.LOCAL_SESSION_ID }
            ?: SessionEntity(
                id = AppSession.LOCAL_SESSION_ID,
                title = AppSession.LOCAL_SESSION_TITLE,
                createdAt = 0L,
                updatedAt = 0L
            )
        val ordered = listOf(local) + rawSessions
            .filterNot { it.id == AppSession.LOCAL_SESSION_ID }
            .sortedWith(
                compareBy<SessionEntity> { it.createdAt }
                    .thenBy { it.id }
            )
        return ordered.map { session ->
            session.toUiSummary(bindingsBySession[session.id])
        }
    }

    fun applyBindings(
        sessions: List<UiSessionSummary>,
        bindingsBySession: Map<String, SessionChannelBinding>
    ): List<UiSessionSummary> = sessions.map { summary ->
        summary.withBinding(bindingsBySession[summary.id])
    }

    private fun SessionEntity.toUiSummary(binding: SessionChannelBinding?): UiSessionSummary {
        return UiSessionSummary(
            id = id,
            title = if (id == AppSession.LOCAL_SESSION_ID) AppSession.LOCAL_SESSION_TITLE else title,
            isLocal = id == AppSession.LOCAL_SESSION_ID
        ).withBinding(binding)
    }

    private fun UiSessionSummary.withBinding(binding: SessionChannelBinding?): UiSessionSummary {
        return copy(
            boundEnabled = binding?.enabled ?: true,
            boundChannel = binding?.channel.orEmpty(),
            boundChatId = binding?.chatId.orEmpty(),
            boundTelegramBotToken = binding?.telegramBotToken.orEmpty(),
            boundTelegramAllowedChatId = binding?.telegramAllowedChatId.orEmpty(),
            boundDiscordBotToken = binding?.discordBotToken.orEmpty(),
            boundDiscordResponseMode = SessionChannelBindingRules.normalizeDiscordResponseMode(
                binding?.discordResponseMode.orEmpty()
            ),
            boundDiscordAllowedUserIds = binding?.discordAllowedUserIds.orEmpty(),
            boundSlackBotToken = binding?.slackBotToken.orEmpty(),
            boundSlackAppToken = binding?.slackAppToken.orEmpty(),
            boundSlackResponseMode = SessionChannelBindingRules.normalizeSlackResponseMode(
                binding?.slackResponseMode.orEmpty()
            ),
            boundSlackAllowedUserIds = binding?.slackAllowedUserIds.orEmpty(),
            boundFeishuAppId = binding?.feishuAppId.orEmpty(),
            boundFeishuAppSecret = binding?.feishuAppSecret.orEmpty(),
            boundFeishuEncryptKey = binding?.feishuEncryptKey.orEmpty(),
            boundFeishuVerificationToken = binding?.feishuVerificationToken.orEmpty(),
            boundFeishuResponseMode = SessionChannelBindingRules.normalizeFeishuResponseMode(
                binding?.feishuResponseMode.orEmpty()
            ),
            boundFeishuAllowedOpenIds = binding?.feishuAllowedOpenIds.orEmpty(),
            boundEmailConsentGranted = binding?.emailConsentGranted ?: false,
            boundEmailImapHost = binding?.emailImapHost.orEmpty(),
            boundEmailImapPort = binding?.emailImapPort ?: 993,
            boundEmailImapUsername = binding?.emailImapUsername.orEmpty(),
            boundEmailImapPassword = binding?.emailImapPassword.orEmpty(),
            boundEmailSmtpHost = binding?.emailSmtpHost.orEmpty(),
            boundEmailSmtpPort = binding?.emailSmtpPort ?: 587,
            boundEmailSmtpUsername = binding?.emailSmtpUsername.orEmpty(),
            boundEmailSmtpPassword = binding?.emailSmtpPassword.orEmpty(),
            boundEmailFromAddress = binding?.emailFromAddress.orEmpty(),
            boundEmailAutoReplyEnabled = binding?.emailAutoReplyEnabled ?: true,
            boundWeComBotId = binding?.wecomBotId.orEmpty(),
            boundWeComSecret = binding?.wecomSecret.orEmpty(),
            boundWeComAllowedUserIds = binding?.wecomAllowedUserIds.orEmpty()
        )
    }
}
