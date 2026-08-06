package com.palmclaw.channels

import android.app.Application
import android.content.Context
import com.palmclaw.bus.OutboundMessage
import com.palmclaw.config.SessionChannelBinding
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfiguredChannelAdapterFactoryTest {

    @Test
    fun `creates all configured adapter types with shared identity keys`() {
        val bindings = listOf(
            telegramBinding("telegram-session", "1001", " telegram-token "),
            discordBinding("discord-session", "123456789012345678", "discord-token"),
            slackBinding("slack-session", " C12345678 ", "xoxb-token", "xapp-token"),
            feishuBinding("feishu-session", " oc_chat ", "cli_app", "secret"),
            emailBinding("email-session", " Recipient@Example.com "),
            wecomBinding("wecom-session", " wr_chat ", "bot-id", "secret")
        ).mapIndexed { index, binding ->
            when (index) {
                0 -> binding.copy(channel = " telegram ")
                1 -> binding.copy(channel = "DISCORD")
                else -> binding
            }
        }

        val adapters = factory().create(bindings)

        assertEquals(6, adapters.size)
        assertEquals(
            setOf("telegram", "discord", "slack", "feishu", "email", "wecom"),
            adapters.map { it.channelName }.toSet()
        )
        bindings.forEach { binding ->
            val channel = binding.channel.trim().lowercase(Locale.US)
            assertEquals(
                ChannelAdapterIdentity.primaryKeyForBinding(binding),
                adapters.single { it.channelName == channel }.adapterKey
            )
        }
    }

    @Test
    fun `omits disabled unsupported and incomplete bindings`() {
        val bindings = listOf(
            telegramBinding("disabled", "1", "token").copy(enabled = false),
            SessionChannelBinding(sessionId = "unsupported", channel = "matrix", chatId = "room"),
            telegramBinding("telegram-missing", "1", "").copy(telegramBotToken = ""),
            discordBinding("discord-invalid", "not-a-snowflake", "token"),
            slackBinding("slack-invalid", "general", "xoxb", "xapp"),
            emailBinding("email-no-consent", "to@example.com").copy(emailConsentGranted = false)
        )

        assertTrue(factory().create(bindings).isEmpty())
    }

    @Test
    fun `groups Telegram credentials and routes every configured target`() {
        val adapters = factory().create(
            listOf(
                telegramBinding("one", "100", "shared-token"),
                telegramBinding("two", "200", "shared-token"),
                telegramBinding("three", "300", "other-token")
            )
        ).filter { it.channelName == "telegram" }

        assertEquals(2, adapters.size)
        listOf("100", "200", "300").forEach { target ->
            assertTrue(adapters.any { it.canHandleOutbound(outbound("telegram", target)) })
        }
    }

    @Test
    fun `groups same credentials for Discord Slack Email and WeCom`() {
        val adapters = factory().create(
            listOf(
                discordBinding("discord-one", "123456789012345678", "discord-token"),
                discordBinding("discord-two", "223456789012345678", "discord-token"),
                slackBinding("slack-one", "C12345678", "xoxb-token", "xapp-token"),
                slackBinding("slack-two", "C87654321", "xoxb-token", "xapp-token"),
                emailBinding("email-one", "one@example.com"),
                emailBinding("email-two", "two@example.com"),
                wecomBinding("wecom-one", "wr_one", "bot-id", "secret"),
                wecomBinding("wecom-two", "wr_two", "bot-id", "secret")
            )
        )

        listOf("discord", "slack", "email", "wecom").forEach { channel ->
            assertEquals(1, adapters.count { it.channelName == channel })
        }
        assertTrue(adapterFor(adapters, "discord").canHandleOutbound(outbound("discord", "223456789012345678")))
        assertTrue(adapterFor(adapters, "slack").canHandleOutbound(outbound("slack", "C87654321")))
        val email = adapterFor(adapters, "email")
        assertTrue(email.canHandleOutbound(outbound("email", "two@example.com", email.adapterKey)))
        assertTrue(adapterFor(adapters, "wecom").canHandleOutbound(outbound("wecom", "wr_two")))
    }

    @Test
    fun `groups compatible Feishu bindings under their shared primary key`() {
        val first = feishuBinding("feishu-one", "oc_first", "cli_app", "secret")
        val second = feishuBinding("feishu-two", "oc_second", "cli_app", "secret").copy(
            feishuEncryptKey = "encrypt",
            feishuVerificationToken = "verify"
        )

        val adapters = factory().create(listOf(first, second))

        assertEquals(1, adapters.size)
        assertEquals(ChannelAdapterIdentity.primaryKeyForBinding(first), adapters.single().adapterKey)
        assertTrue(adapters.single().canHandleOutbound(outbound("feishu", "oc_second")))
    }

    @Test
    fun `normalizes configured outbound targets before adapter construction`() {
        val adapters = factory().create(
            listOf(
                slackBinding("slack", "<#C12345678|general>", "xoxb-token", "xapp-token"),
                emailBinding("email", "Recipient@Example.com"),
                wecomBinding("wecom", "  wr_target  ", "bot-id", "secret"),
                feishuBinding("feishu", "selected target: oc_target", "cli_app", "secret")
            )
        )

        assertTrue(adapterFor(adapters, "slack").canHandleOutbound(outbound("slack", "C12345678")))
        val email = adapterFor(adapters, "email")
        assertTrue(email.canHandleOutbound(outbound("email", "recipient@example.com", email.adapterKey)))
        assertTrue(adapterFor(adapters, "wecom").canHandleOutbound(outbound("wecom", "wr_target")))
        assertTrue(adapterFor(adapters, "feishu").canHandleOutbound(outbound("feishu", "oc_target")))
    }

    private fun factory() = ConfiguredChannelAdapterFactory(TestApplication())

    private fun adapterFor(adapters: List<ChannelAdapter>, channel: String): ChannelAdapter =
        adapters.single { it.channelName == channel }

    private fun outbound(channel: String, chatId: String, adapterKey: String? = null): OutboundMessage =
        OutboundMessage(
            channel = channel,
            chatId = chatId,
            content = "message",
            metadata = adapterKey?.let { mapOf(GatewayOrchestrator.KEY_ADAPTER_KEY to it) }.orEmpty()
        )

    private fun telegramBinding(sessionId: String, chatId: String, token: String) =
        SessionChannelBinding(
            sessionId = sessionId,
            channel = "telegram",
            chatId = chatId,
            telegramBotToken = token
        )

    private fun discordBinding(sessionId: String, chatId: String, token: String) =
        SessionChannelBinding(
            sessionId = sessionId,
            channel = "discord",
            chatId = chatId,
            discordBotToken = token
        )

    private fun slackBinding(sessionId: String, chatId: String, botToken: String, appToken: String) =
        SessionChannelBinding(
            sessionId = sessionId,
            channel = "slack",
            chatId = chatId,
            slackBotToken = botToken,
            slackAppToken = appToken
        )

    private fun feishuBinding(sessionId: String, chatId: String, appId: String, appSecret: String) =
        SessionChannelBinding(
            sessionId = sessionId,
            channel = "feishu",
            chatId = chatId,
            feishuAppId = appId,
            feishuAppSecret = appSecret
        )

    private fun emailBinding(sessionId: String, chatId: String) =
        SessionChannelBinding(
            sessionId = sessionId,
            channel = "email",
            chatId = chatId,
            emailConsentGranted = true,
            emailImapHost = "imap.example.com",
            emailImapUsername = "mailbox@example.com",
            emailImapPassword = "imap-password",
            emailSmtpHost = "smtp.example.com",
            emailSmtpUsername = "mailbox@example.com",
            emailSmtpPassword = "smtp-password",
            emailFromAddress = "mailbox@example.com"
        )

    private fun wecomBinding(sessionId: String, chatId: String, botId: String, secret: String) =
        SessionChannelBinding(
            sessionId = sessionId,
            channel = "wecom",
            chatId = chatId,
            wecomBotId = botId,
            wecomSecret = secret
        )

    private class TestApplication : Application() {
        override fun getApplicationContext(): Context = this
    }
}
