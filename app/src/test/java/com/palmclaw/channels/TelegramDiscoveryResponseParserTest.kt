package com.palmclaw.channels

import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class TelegramDiscoveryResponseParserTest {
    @Test
    fun `parser deduplicates chats and builds private and group titles`() {
        val body = """
            {
              "ok": true,
              "result": [
                {"message":{"chat":{"id":42,"type":"private","first_name":"Ada","last_name":"Lovelace","username":"ada"}}},
                {"edited_message":{"chat":{"id":42,"type":"private","first_name":"Ada"}}},
                {"channel_post":{"chat":{"id":-7,"type":"group","title":"Build Room"}}}
              ]
            }
        """.trimIndent()

        val candidates = TelegramDiscoveryResponseParser.parse(200, true, body)

        assertEquals(listOf("42", "-7"), candidates.map { it.chatId })
        assertEquals("Ada Lovelace (@ada)", candidates.first().title)
        assertEquals("Build Room", candidates.last().title)
    }

    @Test
    fun `parser skips missing zero and non numeric chat ids`() {
        val body = """
            {
              "ok": true,
              "result": [
                {"message":{"chat":{"type":"private","first_name":"Missing"}}},
                {"message":{"chat":{"id":0,"type":"private","first_name":"Zero"}}},
                {"message":{"chat":{"id":"not-a-number","type":"private","first_name":"Invalid"}}},
                {"message":{"chat":{"id":7,"type":"private","first_name":"Valid"}}}
              ]
            }
        """.trimIndent()

        val candidates = TelegramDiscoveryResponseParser.parse(200, true, body)

        assertEquals(listOf("7"), candidates.map { it.chatId })
    }

    @Test
    fun `parser classifies a missing bot endpoint as authentication failure`() {
        try {
            TelegramDiscoveryResponseParser.parse(
                statusCode = 404,
                successful = false,
                body = """{"ok":false,"description":"Not Found"}"""
            )
            fail("Expected ChannelDiscoveryException")
        } catch (failure: ChannelDiscoveryException) {
            assertEquals(ChannelDiscoveryFailureKind.AUTHENTICATION, failure.kind)
            assertEquals(
                "Telegram API returned 404. Check the Bot Token and paste only the token from BotFather, not the full API URL.",
                failure.message
            )
        }
    }

    @Test
    fun `parser bounds server error detail`() {
        val detail = "x".repeat(500)

        try {
            TelegramDiscoveryResponseParser.parse(
                statusCode = 500,
                successful = false,
                body = detail
            )
            fail("Expected ChannelDiscoveryException")
        } catch (failure: ChannelDiscoveryException) {
            assertEquals(ChannelDiscoveryFailureKind.NETWORK, failure.kind)
            assertEquals(323, failure.message?.length)
        }
    }
}
