package com.palmclaw.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChannelDiscoveryStateProjectorTest {

    @Test
    fun `telegramCompleted sets success message only when candidates are present`() {
        val withCandidates = ChannelDiscoveryStateProjector.telegramCompleted(
            currentState = SessionBindingState(),
            candidates = listOf(
                UiTelegramChatCandidate(
                    chatId = "1",
                    title = "Main Chat",
                    kind = "private"
                )
            )
        )
        val emptyResult = ChannelDiscoveryStateProjector.telegramCompleted(
            currentState = SessionBindingState(),
            candidates = emptyList()
        )

        assertEquals("Telegram chats discovered. Tap one to use.", withCandidates.settingsInfo)
        assertEquals(null, withCandidates.state.telegramInfo)
        assertTrue(withCandidates.state.telegramCandidates.isNotEmpty())

        assertEquals(
            "No Telegram chats found yet. Send the bot one message, then detect again.",
            emptyResult.settingsInfo
        )
        assertEquals(
            "No Telegram chats found yet. Send the bot one message, then detect again.",
            emptyResult.state.telegramInfo
        )
        assertTrue(emptyResult.state.telegramCandidates.isEmpty())
    }

    @Test
    fun `emailFailed keeps fallback candidates and exposes error message`() {
        val presentation = ChannelDiscoveryStateProjector.emailFailed(
            currentState = SessionBindingState(),
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
            presentation.state.emailInfo
        )
        assertEquals(1, presentation.state.emailCandidates.size)
        assertFalse(presentation.state.emailDiscovering)
    }

    @Test
    fun `weComMissingCredentials updates both state and settings info`() {
        val presentation = ChannelDiscoveryStateProjector.weComMissingCredentials(SessionBindingState())

        assertEquals(
            "Save Bot ID and Secret first, then detect again.",
            presentation.settingsInfo
        )
        assertEquals(
            "Save Bot ID and Secret first, then detect again.",
            presentation.state.weComInfo
        )
        assertFalse(presentation.state.weComDiscovering)
        assertTrue(presentation.state.weComDiscoveryAttempted)
    }

    @Test
    fun `feishuCleared resets discovery state`() {
        val cleared = ChannelDiscoveryStateProjector.feishuCleared(
            SessionBindingState(
                feishuDiscovering = true,
                feishuDiscoveryAttempted = true,
                feishuCandidates = listOf(
                    UiFeishuChatCandidate(chatId = "c1", title = "Chat", kind = "group")
                ),
                feishuInfo = "stale"
            )
        )

        assertFalse(cleared.feishuDiscovering)
        assertFalse(cleared.feishuDiscoveryAttempted)
        assertTrue(cleared.feishuCandidates.isEmpty())
        assertEquals(null, cleared.feishuInfo)
    }
}
