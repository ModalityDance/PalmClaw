package com.palmclaw.ui

internal object ChannelDiscoveryStateProjector {
    data class Presentation(
        val state: SessionBindingState,
        val settingsInfo: String? = null
    )

    fun telegramMissingToken(currentState: SessionBindingState): Presentation {
        return Presentation(
            state = currentState.copy(
                telegramDiscoveryAttempted = true,
                telegramDiscovering = false,
                telegramCandidates = emptyList(),
                telegramInfo = TELEGRAM_MISSING_TOKEN
            )
        )
    }

    fun telegramLoading(currentState: SessionBindingState): Presentation {
        return Presentation(
            state = currentState.copy(
                telegramDiscoveryAttempted = true,
                telegramDiscovering = true,
                telegramCandidates = emptyList(),
                telegramInfo = null
            ),
            settingsInfo = TELEGRAM_DISCOVERING
        )
    }

    fun telegramCompleted(
        currentState: SessionBindingState,
        candidates: List<UiTelegramChatCandidate>
    ): Presentation {
        val settingsInfo = if (candidates.isEmpty()) {
            TELEGRAM_EMPTY_RESULT
        } else {
            TELEGRAM_SUCCESS
        }
        return Presentation(
            state = currentState.copy(
                telegramDiscoveryAttempted = true,
                telegramDiscovering = false,
                telegramCandidates = candidates,
                telegramInfo = if (candidates.isEmpty()) TELEGRAM_EMPTY_RESULT else null
            ),
            settingsInfo = settingsInfo
        )
    }

    fun telegramFailed(currentState: SessionBindingState, message: String): Presentation {
        return Presentation(
            state = currentState.copy(
                telegramDiscoveryAttempted = true,
                telegramDiscovering = false,
                telegramCandidates = emptyList(),
                telegramInfo = message
            ),
            settingsInfo = message
        )
    }

    fun telegramCleared(currentState: SessionBindingState): SessionBindingState {
        return currentState.copy(
            telegramDiscoveryAttempted = false,
            telegramDiscovering = false,
            telegramCandidates = emptyList(),
            telegramInfo = null
        )
    }

    fun feishuLoading(currentState: SessionBindingState): Presentation {
        return Presentation(
            state = currentState.copy(
                feishuDiscoveryAttempted = true,
                feishuDiscovering = true,
                feishuCandidates = emptyList(),
                feishuInfo = null
            ),
            settingsInfo = FEISHU_DISCOVERING
        )
    }

    fun feishuCompleted(
        currentState: SessionBindingState,
        candidates: List<UiFeishuChatCandidate>,
        info: String
    ): Presentation {
        return Presentation(
            state = currentState.copy(
                feishuDiscoveryAttempted = true,
                feishuDiscovering = false,
                feishuCandidates = candidates,
                feishuInfo = info
            ),
            settingsInfo = info
        )
    }

    fun feishuCleared(currentState: SessionBindingState): SessionBindingState {
        return currentState.copy(
            feishuDiscoveryAttempted = false,
            feishuDiscovering = false,
            feishuCandidates = emptyList(),
            feishuInfo = null
        )
    }

    fun emailLoading(currentState: SessionBindingState): Presentation {
        return Presentation(
            state = currentState.copy(
                emailDiscoveryAttempted = true,
                emailDiscovering = true,
                emailCandidates = emptyList(),
                emailInfo = null
            ),
            settingsInfo = EMAIL_DISCOVERING
        )
    }

    fun emailCompleted(
        currentState: SessionBindingState,
        candidates: List<UiEmailSenderCandidate>
    ): Presentation {
        val settingsInfo = if (candidates.isEmpty()) {
            EMAIL_EMPTY_RESULT
        } else {
            EMAIL_SUCCESS
        }
        return Presentation(
            state = currentState.copy(
                emailDiscoveryAttempted = true,
                emailDiscovering = false,
                emailCandidates = candidates,
                emailInfo = if (candidates.isEmpty()) EMAIL_EMPTY_RESULT else null
            ),
            settingsInfo = settingsInfo
        )
    }

    fun emailFailed(
        currentState: SessionBindingState,
        fallbackCandidates: List<UiEmailSenderCandidate>,
        message: String
    ): Presentation {
        return Presentation(
            state = currentState.copy(
                emailDiscoveryAttempted = true,
                emailDiscovering = false,
                emailCandidates = fallbackCandidates,
                emailInfo = message
            ),
            settingsInfo = message
        )
    }

    fun emailCleared(currentState: SessionBindingState): SessionBindingState {
        return currentState.copy(
            emailDiscoveryAttempted = false,
            emailDiscovering = false,
            emailCandidates = emptyList(),
            emailInfo = null
        )
    }

    fun weComLoading(currentState: SessionBindingState): Presentation {
        return Presentation(
            state = currentState.copy(
                weComDiscoveryAttempted = true,
                weComDiscovering = true,
                weComCandidates = emptyList(),
                weComInfo = null
            ),
            settingsInfo = WECOM_DISCOVERING
        )
    }

    fun weComMissingCredentials(currentState: SessionBindingState): Presentation {
        return Presentation(
            state = currentState.copy(
                weComDiscoveryAttempted = true,
                weComDiscovering = false,
                weComCandidates = emptyList(),
                weComInfo = WECOM_MISSING_CREDENTIALS
            ),
            settingsInfo = WECOM_MISSING_CREDENTIALS
        )
    }

    fun weComCompleted(
        currentState: SessionBindingState,
        candidates: List<UiWeComChatCandidate>,
        info: String
    ): Presentation {
        return Presentation(
            state = currentState.copy(
                weComDiscoveryAttempted = true,
                weComDiscovering = false,
                weComCandidates = candidates,
                weComInfo = info
            ),
            settingsInfo = info
        )
    }

    fun weComCleared(currentState: SessionBindingState): SessionBindingState {
        return currentState.copy(
            weComDiscoveryAttempted = false,
            weComDiscovering = false,
            weComCandidates = emptyList(),
            weComInfo = null
        )
    }

    private const val TELEGRAM_MISSING_TOKEN = "Please enter Telegram bot token first."
    private const val TELEGRAM_DISCOVERING = "Detecting Telegram chats..."
    private const val TELEGRAM_EMPTY_RESULT =
        "No Telegram chats found yet. Send the bot one message, then detect again."
    private const val TELEGRAM_SUCCESS = "Telegram chats discovered. Tap one to use."

    private const val FEISHU_DISCOVERING = "Detecting Feishu chats..."

    private const val EMAIL_DISCOVERING = "Detecting email senders..."
    private const val EMAIL_EMPTY_RESULT =
        "No email senders found yet. Make sure one message reached INBOX, then detect again."
    private const val EMAIL_SUCCESS = "Email senders discovered. Tap one to use."

    private const val WECOM_DISCOVERING = "Detecting WeCom chats..."
    private const val WECOM_MISSING_CREDENTIALS = "Save Bot ID and Secret first, then detect again."
}
