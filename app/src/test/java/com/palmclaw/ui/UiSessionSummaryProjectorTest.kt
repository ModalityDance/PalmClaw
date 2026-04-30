package com.palmclaw.ui

import com.palmclaw.config.AppSession
import com.palmclaw.config.SessionChannelBinding
import com.palmclaw.storage.entities.SessionEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UiSessionSummaryProjectorTest {

    @Test
    fun build_places_local_first_and_projects_binding_fields() {
        val rawSessions = listOf(
            SessionEntity(
                id = "session-b",
                title = "Bravo",
                createdAt = 20L,
                updatedAt = 20L
            ),
            SessionEntity(
                id = "session-a",
                title = "Alpha",
                createdAt = 10L,
                updatedAt = 10L
            )
        )
        val bindingsBySession = mapOf(
            "session-a" to SessionChannelBinding(
                sessionId = "session-a",
                channel = "slack",
                chatId = "C1234567890",
                slackBotToken = "xoxb-test",
                slackAppToken = "xapp-test",
                slackResponseMode = "OPEN",
                slackAllowedUserIds = listOf("U1", "U2")
            )
        )

        val summaries = UiSessionSummaryProjector.build(rawSessions, bindingsBySession)

        assertEquals(AppSession.LOCAL_SESSION_ID, summaries.first().id)
        assertEquals(listOf(AppSession.LOCAL_SESSION_ID, "session-a", "session-b"), summaries.map { it.id })
        val alpha = summaries.first { it.id == "session-a" }
        assertEquals("slack", alpha.boundChannel)
        assertEquals("C1234567890", alpha.boundChatId)
        assertEquals("open", alpha.boundSlackResponseMode)
        assertEquals(listOf("U1", "U2"), alpha.boundSlackAllowedUserIds)
    }

    @Test
    fun applyBindings_refreshes_existing_summaries_without_changing_defaults() {
        val sessions = listOf(
            UiSessionSummary(
                id = "session-1",
                title = "Session 1",
                isLocal = false
            ),
            UiSessionSummary(
                id = "session-2",
                title = "Session 2",
                isLocal = false,
                boundEnabled = false
            )
        )
        val refreshed = UiSessionSummaryProjector.applyBindings(
            sessions = sessions,
            bindingsBySession = mapOf(
                "session-2" to SessionChannelBinding(
                    sessionId = "session-2",
                    enabled = false,
                    channel = "discord",
                    chatId = "123456789012345",
                    discordBotToken = "bot",
                    discordResponseMode = "mention",
                    discordAllowedUserIds = listOf("42")
                )
            )
        )

        val unbound = refreshed.first { it.id == "session-1" }
        val bound = refreshed.first { it.id == "session-2" }

        assertTrue(unbound.boundEnabled)
        assertEquals("", unbound.boundChannel)
        assertEquals(false, bound.boundEnabled)
        assertEquals("discord", bound.boundChannel)
        assertEquals(listOf("42"), bound.boundDiscordAllowedUserIds)
    }

    @Test
    fun build_projects_feishu_response_mode_from_binding() {
        val rawSessions = listOf(
            SessionEntity(
                id = "session-feishu",
                title = "Feishu",
                createdAt = 1L,
                updatedAt = 1L
            )
        )
        val bindingsBySession = mapOf(
            "session-feishu" to SessionChannelBinding(
                sessionId = "session-feishu",
                channel = "feishu",
                chatId = "oc_123",
                feishuAppId = "app-id",
                feishuAppSecret = "app-secret",
                feishuResponseMode = "OPEN"
            )
        )

        val summaries = UiSessionSummaryProjector.build(rawSessions, bindingsBySession)
        val feishu = summaries.first { it.id == "session-feishu" }

        assertEquals("feishu", feishu.boundChannel)
        assertEquals("open", feishu.boundFeishuResponseMode)
    }
}
