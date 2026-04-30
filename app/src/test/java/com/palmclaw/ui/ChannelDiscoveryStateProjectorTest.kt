package com.palmclaw.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChannelDiscoveryStateProjectorTest {

    @Test
    fun `telegramCompleted sets success message only when candidates are present`() {
        val withCandidates = ChannelDiscoveryStateProjector.telegramCompleted(
            currentState = ChatUiState(),
            candidates = listOf(
                UiTelegramChatCandidate(
                    chatId = "1",
                    title = "Main Chat",
                    kind = "private"
                )
            )
        )
        val emptyResult = ChannelDiscoveryStateProjector.telegramCompleted(
            currentState = ChatUiState(),
            candidates = emptyList()
        )

        assertEquals("Telegram chats discovered. Tap one to use.", withCandidates.settingsInfo)
        assertEquals(null, withCandidates.state.sessionBindingTelegramInfo)
        assertTrue(withCandidates.state.sessionBindingTelegramCandidates.isNotEmpty())

        assertEquals(
            "No Telegram chats found yet. Send the bot one message, then detect again.",
            emptyResult.settingsInfo
        )
        assertEquals(
            "No Telegram chats found yet. Send the bot one message, then detect again.",
            emptyResult.state.sessionBindingTelegramInfo
        )
        assertTrue(emptyResult.state.sessionBindingTelegramCandidates.isEmpty())
    }

    @Test
    fun `emailFailed keeps fallback candidates and exposes error message`() {
        val presentation = ChannelDiscoveryStateProjector.emailFailed(
            currentState = ChatUiState(),
            fallbackCandidates = listOf(
                UiEmailSenderCandidate(
                    email = "sender@example.com",
                    subject = "hello",
                    note = "cached"
                )
            ),
            message = "Email sender detection failed."
        )

        assertEquals("Email sender detection failed.", presentation.settingsInfo)
        assertEquals(
            "Email sender detection failed.",
            presentation.state.sessionBindingEmailInfo
        )
        assertEquals(1, presentation.state.sessionBindingEmailCandidates.size)
        assertFalse(presentation.state.sessionBindingEmailDiscovering)
    }

    @Test
    fun `weComMissingCredentials updates both state and settings info`() {
        val presentation = ChannelDiscoveryStateProjector.weComMissingCredentials(ChatUiState())

        assertEquals(
            "Save Bot ID and Secret first, then detect again.",
            presentation.settingsInfo
        )
        assertEquals(
            "Save Bot ID and Secret first, then detect again.",
            presentation.state.sessionBindingWeComInfo
        )
        assertFalse(presentation.state.sessionBindingWeComDiscovering)
        assertTrue(presentation.state.sessionBindingWeComDiscoveryAttempted)
    }

    @Test
    fun `feishuCleared resets discovery state`() {
        val cleared = ChannelDiscoveryStateProjector.feishuCleared(
            ChatUiState(
                sessionBindingFeishuDiscovering = true,
                sessionBindingFeishuDiscoveryAttempted = true,
                sessionBindingFeishuCandidates = listOf(
                    UiFeishuChatCandidate(chatId = "c1", title = "Chat", kind = "group")
                ),
                sessionBindingFeishuInfo = "stale"
            )
        )

        assertFalse(cleared.sessionBindingFeishuDiscovering)
        assertFalse(cleared.sessionBindingFeishuDiscoveryAttempted)
        assertTrue(cleared.sessionBindingFeishuCandidates.isEmpty())
        assertEquals(null, cleared.sessionBindingFeishuInfo)
    }
}
