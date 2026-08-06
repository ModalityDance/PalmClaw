package com.palmclaw.channels

import com.palmclaw.config.SessionChannelBinding
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChannelAdapterIdentityTest {

    @Test
    fun `adapter keys keep fixed vectors for all supported channels`() {
        val binding = completeBinding()

        assertEquals("telegram:cbb45e54cab2f130", primary(binding.copy(channel = " Telegram ")))
        assertEquals("discord:0017ec88d6aa85df", primary(binding.copy(channel = "discord")))
        assertEquals("slack:b4f925b716618fad", primary(binding.copy(channel = "slack")))
        assertEquals("feishu:b6f119fb5e4b8e78", primary(binding.copy(channel = "feishu")))
        assertEquals("email:289dfb5d1a10dfde", primary(binding.copy(channel = "email")))
        assertEquals("wecom:8ff49067eb16bcbc", primary(binding.copy(channel = "wecom")))
    }

    @Test
    fun `feishu exposes canonical and legacy compatible keys`() {
        val binding = completeBinding().copy(channel = "feishu")

        assertEquals(
            listOf(
                "feishu:b6f119fb5e4b8e78",
                "feishu:109bdfddd34db98a"
            ),
            ChannelAdapterIdentity.keysForBinding(binding)
        )
        assertEquals(
            "feishu:b6f119fb5e4b8e78",
            ChannelAdapterIdentity.primaryKeyForBinding(binding)
        )
    }

    @Test
    fun `feishu bindings sharing canonical credentials form one adapter group`() {
        val bindings = listOf(
            completeBinding().copy(
                sessionId = "session-b",
                channel = "feishu",
                chatId = "oc_second",
                feishuEncryptKey = "",
                feishuVerificationToken = ""
            ),
            completeBinding().copy(
                sessionId = "session-a",
                channel = "feishu",
                chatId = "oc_first",
                feishuEncryptKey = "encrypt",
                feishuVerificationToken = "verify"
            )
        )

        val group = groupFeishuBindingsByAdapterIdentity(bindings).single()

        assertEquals("feishu:b6f119fb5e4b8e78", group.adapterKey)
        assertEquals(listOf("oc_second", "oc_first"), group.bindings.map { it.chatId })
        assertEquals("session-a", group.configuration.sessionId)
        assertTrue(group.hasConfigurationConflict)
    }

    @Test
    fun `missing credentials do not produce binding keys`() {
        listOf("telegram", "discord", "slack", "feishu", "email", "wecom", "unknown")
            .forEach { channel ->
                val binding = SessionChannelBinding(sessionId = "session", channel = channel)
                assertEquals(emptyList<String>(), ChannelAdapterIdentity.keysForBinding(binding))
                assertNull(ChannelAdapterIdentity.primaryKeyForBinding(binding))
            }
    }

    @Test
    fun `key normalizes channel and seed whitespace`() {
        assertEquals(
            "telegram:cbb45e54cab2f130",
            ChannelAdapterIdentity.key(" Telegram ", " tg-token ")
        )
    }

    private fun primary(binding: SessionChannelBinding): String? =
        ChannelAdapterIdentity.primaryKeyForBinding(binding)

    private fun completeBinding() = SessionChannelBinding(
        sessionId = "session",
        telegramBotToken = " tg-token ",
        discordBotToken = " discord-token ",
        slackBotToken = " xoxb-bot ",
        slackAppToken = " xapp-app ",
        feishuAppId = " cli_app ",
        feishuAppSecret = " app-secret ",
        feishuEncryptKey = " encrypt ",
        feishuVerificationToken = " verify ",
        emailConsentGranted = true,
        emailImapHost = " imap.example.com ",
        emailImapUsername = " inbox@example.com ",
        emailImapPassword = "imap-password",
        emailSmtpHost = " smtp.example.com ",
        emailSmtpUsername = " outbox@example.com ",
        emailSmtpPassword = "smtp-password",
        emailFromAddress = " sender@example.com ",
        wecomBotId = " bot-id ",
        wecomSecret = " bot-secret "
    )
}
