package com.palmclaw.channels

import com.palmclaw.config.SessionChannelBinding
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChannelBindingRuntimeProjectorTest {
    private val projector = ChannelBindingRuntimeProjector(
        EmailAddressValidator { value -> value.matches(Regex("^[^@]+@[^@]+\\.[^@]+$")) }
    )
    private val emptySnapshots = ChannelRuntimeSnapshotSource { _, _ -> ChannelRuntimeSnapshot() }

    @Test
    fun `projection normalizes channel and every target kind`() {
        val cases = listOf(
            completeBinding("telegram", " 42 ") to ("telegram" to "42"),
            completeBinding("discord", " <#123456789012345> ") to ("discord" to "123456789012345"),
            completeBinding("slack", " <#c12345678|general> ") to ("slack" to "C12345678"),
            completeBinding("feishu", "https://example.test/oc_chat_1") to ("feishu" to "oc_chat_1"),
            completeBinding("email", " Sender@Example.COM ") to ("email" to "sender@example.com"),
            completeBinding("wecom", " room-1 ") to ("wecom" to "room-1")
        )

        cases.forEach { (binding, expected) ->
            val projection = projector.project(
                binding = binding,
                gatewayEnabled = false,
                snapshotSource = emptySnapshots
            )
            assertEquals(expected.first, projection.channel)
            assertEquals(expected.second, projection.target)
        }
    }

    @Test
    fun `unbound and disabled take precedence`() {
        assertEquals("Unbound", projector.project(null, true, emptySnapshots).status)
        assertEquals(
            "Unbound",
            projector.project(SessionChannelBinding(sessionId = "s"), true, emptySnapshots).status
        )
        assertEquals(
            "Disabled",
            projector.project(
                SessionChannelBinding(sessionId = "s", enabled = false, channel = "telegram"),
                true,
                emptySnapshots
            ).status
        )
    }

    @Test
    fun `credential and target errors preserve existing labels`() {
        val cases = listOf(
            SessionChannelBinding(sessionId = "s", channel = "telegram", chatId = "42") to "Missing token",
            completeBinding("telegram", "") to "Waiting for chat detection",
            SessionChannelBinding(sessionId = "s", channel = "discord", chatId = "123") to "Missing token",
            completeBinding("discord", "bad") to "Missing channel id",
            completeBinding("slack", "C12345678").copy(slackAppToken = "") to "Missing bot/app token",
            completeBinding("slack", "bad") to "Missing channel id",
            SessionChannelBinding(sessionId = "s", channel = "feishu", chatId = "oc_chat") to "Missing app credentials",
            completeBinding("feishu", "") to "Waiting for chat detection",
            completeBinding("feishu", "bad") to "Invalid target",
            completeBinding("email", "sender@example.com").copy(emailConsentGranted = false) to "Consent required",
            completeBinding("email", "sender@example.com").copy(emailImapPassword = "") to "Missing mailbox credentials",
            completeBinding("email", "") to "Waiting for sender detection",
            completeBinding("email", "bad") to "Invalid sender",
            SessionChannelBinding(sessionId = "s", channel = "wecom", chatId = "room") to "Missing bot credentials",
            completeBinding("wecom", "") to "Waiting for chat detection"
        )

        cases.forEach { (binding, expectedStatus) ->
            assertEquals(
                expectedStatus,
                projector.project(
                    binding = binding,
                    gatewayEnabled = true,
                    snapshotSource = emptySnapshots
                ).status
            )
        }
    }

    @Test
    fun `gateway idle precedes adapter runtime status`() {
        val binding = completeBinding("telegram", "42")
        val source = ChannelRuntimeSnapshotSource { _, _ ->
            ChannelRuntimeSnapshot(ready = true, lastError = "old error")
        }

        assertEquals("Gateway idle", projector.project(binding, false, source).status)
    }

    @Test
    fun `runtime snapshot status follows fixed priority`() {
        val binding = completeBinding("telegram", "42")
        val cases = listOf(
            ChannelRuntimeSnapshot(lastError = "failure") to "Error",
            ChannelRuntimeSnapshot(ready = true, lastError = "old failure") to "Connected",
            ChannelRuntimeSnapshot(connected = true) to "Connecting",
            ChannelRuntimeSnapshot(running = true) to "Starting",
            ChannelRuntimeSnapshot() to "Configured"
        )

        cases.forEach { (snapshot, expectedStatus) ->
            val source = ChannelRuntimeSnapshotSource { _, _ -> snapshot }
            assertEquals(expectedStatus, projector.project(binding, true, source).status)
        }
    }

    @Test
    fun `feishu selects an active compatible snapshot before primary fallback`() {
        val binding = completeBinding("feishu", "oc_chat")
        val keys = ChannelAdapterIdentity.keysForBinding(binding)
        listOf(
            ChannelRuntimeSnapshot(connected = true) to "Connecting",
            ChannelRuntimeSnapshot(lastError = "legacy failure") to "Error"
        ).forEach { (compatibleSnapshot, expectedStatus) ->
            val source = ChannelRuntimeSnapshotSource { _, adapterKey ->
                when (adapterKey) {
                    keys[1] -> compatibleSnapshot
                    else -> ChannelRuntimeSnapshot()
                }
            }
            val projection = projector.project(binding, true, source)

            assertEquals(keys, projection.adapterKeys)
            assertEquals(expectedStatus, projection.status)
        }
    }

    @Test
    fun `can start adapter follows gateway binding completeness rules`() {
        assertTrue(projector.canStartAdapter(completeBinding("telegram", "42")))
        assertFalse(projector.canStartAdapter(completeBinding("telegram", "")))
        assertTrue(projector.canStartAdapter(completeBinding("discord", "123456789012345")))
        assertFalse(projector.canStartAdapter(completeBinding("discord", "bad")))
        assertTrue(projector.canStartAdapter(completeBinding("slack", "C12345678")))
        assertFalse(projector.canStartAdapter(completeBinding("slack", "C12345678").copy(slackAppToken = "")))
        assertTrue(projector.canStartAdapter(completeBinding("feishu", "")))
        assertFalse(projector.canStartAdapter(completeBinding("feishu", "").copy(feishuAppSecret = "")))
        assertTrue(projector.canStartAdapter(completeBinding("email", "")))
        assertFalse(projector.canStartAdapter(completeBinding("email", "").copy(emailConsentGranted = false)))
        assertFalse(projector.canStartAdapter(completeBinding("email", "").copy(emailImapPassword = "")))
        assertTrue(projector.canStartAdapter(completeBinding("wecom", "")))
        assertFalse(projector.canStartAdapter(completeBinding("wecom", "").copy(wecomSecret = "")))
        assertFalse(projector.canStartAdapter(completeBinding("wecom", "room").copy(enabled = false)))
    }

    private fun completeBinding(channel: String, chatId: String) = SessionChannelBinding(
        sessionId = "session",
        channel = channel,
        chatId = chatId,
        telegramBotToken = "tg-token",
        discordBotToken = "discord-token",
        slackBotToken = "xoxb-bot",
        slackAppToken = "xapp-app",
        feishuAppId = "cli_app",
        feishuAppSecret = "app-secret",
        feishuEncryptKey = "encrypt",
        feishuVerificationToken = "verify",
        emailConsentGranted = true,
        emailImapHost = "imap.example.com",
        emailImapUsername = "inbox@example.com",
        emailImapPassword = "imap-password",
        emailSmtpHost = "smtp.example.com",
        emailSmtpUsername = "outbox@example.com",
        emailSmtpPassword = "smtp-password",
        emailFromAddress = "sender@example.com",
        wecomBotId = "bot-id",
        wecomSecret = "bot-secret"
    )
}
