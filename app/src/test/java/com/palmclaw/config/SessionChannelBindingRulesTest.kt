package com.palmclaw.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionChannelBindingRulesTest {
    @Test
    fun `normalize binding trims and coerces channel specific fields`() {
        val binding = SessionChannelBinding(
            sessionId = "  session-1  ",
            channel = " Slack ",
            chatId = " <#c1234567890|general> ",
            slackBotToken = " xoxb-token ",
            slackAppToken = " xapp-token ",
            slackResponseMode = " OPEN ",
            slackAllowedUserIds = listOf(" U1 ", "", "U2", "U1 ")
        )

        val normalized = SessionChannelBindingRules.normalize(binding)

        assertEquals("session-1", normalized?.sessionId)
        assertEquals("slack", normalized?.channel)
        assertEquals("C1234567890", normalized?.chatId)
        assertEquals("xoxb-token", normalized?.slackBotToken)
        assertEquals("xapp-token", normalized?.slackAppToken)
        assertEquals("open", normalized?.slackResponseMode)
        assertEquals(listOf("U1", "U2"), normalized?.slackAllowedUserIds)
    }

    @Test
    fun `normalize list drops invalid items and deduplicates by session`() {
        val normalized = SessionChannelBindingRules.normalize(
            listOf(
                SessionChannelBinding(sessionId = "s1", channel = "discord", chatId = "<#123456789012345>"),
                SessionChannelBinding(sessionId = "s1", channel = "telegram", chatId = "keep-latest"),
                SessionChannelBinding(sessionId = "  ", channel = "slack", chatId = "C123456789"),
                SessionChannelBinding(sessionId = "s2", channel = "unknown", chatId = "x")
            )
        )

        assertEquals(1, normalized.size)
        assertEquals("s1", normalized.first().sessionId)
        assertEquals("discord", normalized.first().channel)
        assertEquals("123456789012345", normalized.first().chatId)
    }

    @Test
    fun `parse allowed identifiers and validators stay stable`() {
        val parsed = SessionChannelBindingRules.parseAllowedIdentifiers(" user-1,\nuser-2  user-1 ")

        assertEquals(listOf("user-1", "user-2"), parsed)
        assertTrue(SessionChannelBindingRules.isDiscordSnowflake("123456789012345"))
        assertTrue(SessionChannelBindingRules.isSlackChannelId("C1234567890"))
        assertTrue(SessionChannelBindingRules.isFeishuTargetId("ou_abc123"))
        assertNull(SessionChannelBindingRules.normalize(SessionChannelBinding(sessionId = "", channel = "slack")))
    }
}
