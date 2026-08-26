package com.palmclaw.channels

import com.palmclaw.bus.OutboundMessage
import javax.mail.AuthenticationFailedException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EmailChannelAdapterHealthTest {
    @Test
    fun `smtp authentication failure blocks binding and exposes only safe summary`() = runBlocking {
        var attempts = 0
        val adapter = EmailChannelAdapter(
            adapterKey = ADAPTER_KEY,
            config = configuredAccount(),
            transport = EmailTransport {
                attempts += 1
                throw AuthenticationFailedException(
                    "SMTP rejected user@example.com password=secret-value"
                )
            }
        )

        val failure = runCatching {
            adapter.send(
                OutboundMessage(
                    channel = "email",
                    chatId = "recipient@example.com",
                    content = "Hello"
                )
            )
        }.exceptionOrNull()

        assertTrue(failure is ChannelOperationFailedException)
        assertEquals("Authentication required", failure?.message)
        assertFalse(failure?.message.orEmpty().contains("user@example.com"))
        assertFalse(failure?.message.orEmpty().contains("secret-value"))
        assertEquals(1, attempts)

        val snapshot = ChannelRuntimeDiagnostics.getSnapshot("email", ADAPTER_KEY)
        assertEquals(ChannelBindingHealthState.BLOCKED, snapshot.state)
        assertEquals(ChannelRuntimeErrorCode.AUTHENTICATION_FAILED, snapshot.error?.code)

        val secondFailure = runCatching {
            adapter.send(
                OutboundMessage(
                    channel = "email",
                    chatId = "recipient@example.com",
                    content = "Retry"
                )
            )
        }.exceptionOrNull()
        assertEquals("Authentication required", secondFailure?.message)
        assertEquals(1, attempts)
    }

    private fun configuredAccount(): EmailAccountConfig = EmailAccountConfig(
        consentGranted = true,
        imapHost = "imap.example.com",
        imapPort = 993,
        imapUsername = "user@example.com",
        imapPassword = "imap-secret",
        smtpHost = "smtp.example.com",
        smtpPort = 465,
        smtpUsername = "user@example.com",
        smtpPassword = "smtp-secret",
        fromAddress = "user@example.com"
    )

    private companion object {
        const val ADAPTER_KEY = "email-binding"
    }
}
