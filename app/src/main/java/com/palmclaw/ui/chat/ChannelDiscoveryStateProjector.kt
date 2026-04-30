package com.palmclaw.ui

internal object ChannelDiscoveryStateProjector {
    data class Presentation(
        val state: ChatUiState,
        val settingsInfo: String? = null
    )

    fun telegramMissingToken(currentState: ChatUiState): Presentation {
        return Presentation(
            state = currentState.copy(
                sessionBindingTelegramDiscoveryAttempted = true,
                sessionBindingTelegramDiscovering = false,
                sessionBindingTelegramCandidates = emptyList(),
                sessionBindingTelegramInfo = TELEGRAM_MISSING_TOKEN
            )
        )
    }

    fun telegramLoading(currentState: ChatUiState): Presentation {
        return Presentation(
            state = currentState.copy(
                sessionBindingTelegramDiscoveryAttempted = true,
                sessionBindingTelegramDiscovering = true,
                sessionBindingTelegramCandidates = emptyList(),
                sessionBindingTelegramInfo = null
            ),
            settingsInfo = TELEGRAM_DISCOVERING
        )
    }

    fun telegramCompleted(
        currentState: ChatUiState,
        candidates: List<UiTelegramChatCandidate>
    ): Presentation {
        val settingsInfo = if (candidates.isEmpty()) {
            TELEGRAM_EMPTY_RESULT
        } else {
            TELEGRAM_SUCCESS
        }
        return Presentation(
            state = currentState.copy(
                sessionBindingTelegramDiscoveryAttempted = true,
                sessionBindingTelegramDiscovering = false,
                sessionBindingTelegramCandidates = candidates,
                sessionBindingTelegramInfo = if (candidates.isEmpty()) TELEGRAM_EMPTY_RESULT else null
            ),
            settingsInfo = settingsInfo
        )
    }

    fun telegramFailed(currentState: ChatUiState, message: String): Presentation {
        return Presentation(
            state = currentState.copy(
                sessionBindingTelegramDiscoveryAttempted = true,
                sessionBindingTelegramDiscovering = false,
                sessionBindingTelegramCandidates = emptyList(),
                sessionBindingTelegramInfo = message
            ),
            settingsInfo = message
        )
    }

    fun telegramCleared(currentState: ChatUiState): ChatUiState {
        return currentState.copy(
            sessionBindingTelegramDiscoveryAttempted = false,
            sessionBindingTelegramDiscovering = false,
            sessionBindingTelegramCandidates = emptyList(),
            sessionBindingTelegramInfo = null
        )
    }

    fun feishuLoading(currentState: ChatUiState): Presentation {
        return Presentation(
            state = currentState.copy(
                sessionBindingFeishuDiscoveryAttempted = true,
                sessionBindingFeishuDiscovering = true,
                sessionBindingFeishuCandidates = emptyList(),
                sessionBindingFeishuInfo = null
            ),
            settingsInfo = FEISHU_DISCOVERING
        )
    }

    fun feishuCompleted(
        currentState: ChatUiState,
        candidates: List<UiFeishuChatCandidate>,
        info: String
    ): Presentation {
        return Presentation(
            state = currentState.copy(
                sessionBindingFeishuDiscoveryAttempted = true,
                sessionBindingFeishuDiscovering = false,
                sessionBindingFeishuCandidates = candidates,
                sessionBindingFeishuInfo = info
            ),
            settingsInfo = info
        )
    }

    fun feishuCleared(currentState: ChatUiState): ChatUiState {
        return currentState.copy(
            sessionBindingFeishuDiscoveryAttempted = false,
            sessionBindingFeishuDiscovering = false,
            sessionBindingFeishuCandidates = emptyList(),
            sessionBindingFeishuInfo = null
        )
    }

    fun emailLoading(currentState: ChatUiState): Presentation {
        return Presentation(
            state = currentState.copy(
                sessionBindingEmailDiscoveryAttempted = true,
                sessionBindingEmailDiscovering = true,
                sessionBindingEmailCandidates = emptyList(),
                sessionBindingEmailInfo = null
            ),
            settingsInfo = EMAIL_DISCOVERING
        )
    }

    fun emailCompleted(
        currentState: ChatUiState,
        candidates: List<UiEmailSenderCandidate>
    ): Presentation {
        val settingsInfo = if (candidates.isEmpty()) {
            EMAIL_EMPTY_RESULT
        } else {
            EMAIL_SUCCESS
        }
        return Presentation(
            state = currentState.copy(
                sessionBindingEmailDiscoveryAttempted = true,
                sessionBindingEmailDiscovering = false,
                sessionBindingEmailCandidates = candidates,
                sessionBindingEmailInfo = if (candidates.isEmpty()) EMAIL_EMPTY_RESULT else null
            ),
            settingsInfo = settingsInfo
        )
    }

    fun emailFailed(
        currentState: ChatUiState,
        fallbackCandidates: List<UiEmailSenderCandidate>,
        message: String
    ): Presentation {
        return Presentation(
            state = currentState.copy(
                sessionBindingEmailDiscoveryAttempted = true,
                sessionBindingEmailDiscovering = false,
                sessionBindingEmailCandidates = fallbackCandidates,
                sessionBindingEmailInfo = message
            ),
            settingsInfo = message
        )
    }

    fun emailCleared(currentState: ChatUiState): ChatUiState {
        return currentState.copy(
            sessionBindingEmailDiscoveryAttempted = false,
            sessionBindingEmailDiscovering = false,
            sessionBindingEmailCandidates = emptyList(),
            sessionBindingEmailInfo = null
        )
    }

    fun weComLoading(currentState: ChatUiState): Presentation {
        return Presentation(
            state = currentState.copy(
                sessionBindingWeComDiscoveryAttempted = true,
                sessionBindingWeComDiscovering = true,
                sessionBindingWeComCandidates = emptyList(),
                sessionBindingWeComInfo = null
            ),
            settingsInfo = WECOM_DISCOVERING
        )
    }

    fun weComMissingCredentials(currentState: ChatUiState): Presentation {
        return Presentation(
            state = currentState.copy(
                sessionBindingWeComDiscoveryAttempted = true,
                sessionBindingWeComDiscovering = false,
                sessionBindingWeComCandidates = emptyList(),
                sessionBindingWeComInfo = WECOM_MISSING_CREDENTIALS
            ),
            settingsInfo = WECOM_MISSING_CREDENTIALS
        )
    }

    fun weComCompleted(
        currentState: ChatUiState,
        candidates: List<UiWeComChatCandidate>,
        info: String
    ): Presentation {
        return Presentation(
            state = currentState.copy(
                sessionBindingWeComDiscoveryAttempted = true,
                sessionBindingWeComDiscovering = false,
                sessionBindingWeComCandidates = candidates,
                sessionBindingWeComInfo = info
            ),
            settingsInfo = info
        )
    }

    fun weComCleared(currentState: ChatUiState): ChatUiState {
        return currentState.copy(
            sessionBindingWeComDiscoveryAttempted = false,
            sessionBindingWeComDiscovering = false,
            sessionBindingWeComCandidates = emptyList(),
            sessionBindingWeComInfo = null
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
