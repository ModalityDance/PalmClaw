package com.palmclaw.channels

import android.util.Log
import com.palmclaw.bus.InboundMessage
import com.palmclaw.bus.OutboundMessage
import java.io.ByteArrayOutputStream
import java.util.Date
import java.util.Locale
import java.util.Properties
import javax.mail.Address
import javax.mail.Flags
import javax.mail.Folder
import javax.mail.Message
import javax.mail.Multipart
import javax.mail.Part
import javax.mail.Session
import javax.mail.Store
import javax.mail.Transport
import javax.mail.UIDFolder
import javax.mail.internet.InternetAddress
import javax.mail.internet.MimeMessage
import javax.mail.search.FlagTerm
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class EmailAccountConfig(
    val consentGranted: Boolean,
    val imapHost: String,
    val imapPort: Int,
    val imapUsername: String,
    val imapPassword: String,
    val smtpHost: String,
    val smtpPort: Int,
    val smtpUsername: String,
    val smtpPassword: String,
    val fromAddress: String,
    val autoReplyEnabled: Boolean = true,
    val pollIntervalSeconds: Long = 15L,
    val maxBodyChars: Int = 12_000
)

class EmailChannelAdapter(
    override val adapterKey: String,
    private val config: EmailAccountConfig
) : ChannelAdapter {
    override val channelName: String = "email"

    private var pollingJob: Job? = null
    private val processedUids = linkedSetOf<String>()
    private val lastSubjectByChat = linkedMapOf<String, String>()
    private val lastMessageIdByChat = linkedMapOf<String, String>()

    override fun start(scope: CoroutineScope, publishInbound: suspend (InboundMessage) -> Unit) {
        if (pollingJob != null) return
        ChannelRuntimeDiagnostics.reset(channelName, adapterKey)
        EmailGatewayDiagnostics.reset()
        if (!config.consentGranted) {
            ChannelRuntimeDiagnostics.markError(channelName, adapterKey, "Mailbox consent is not granted")
            EmailGatewayDiagnostics.markError("Mailbox consent is not granted")
            return
        }
        if (!isConfigured()) {
            ChannelRuntimeDiagnostics.markError(channelName, adapterKey, "Email account is incomplete")
            EmailGatewayDiagnostics.markError("Email account is incomplete")
            return
        }
        ChannelRuntimeDiagnostics.markRunning(channelName, adapterKey, true)
        EmailGatewayDiagnostics.markRunning(true)
        pollingJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                try {
                    val inbound = fetchUnreadMessages()
                    if (inbound.isNotEmpty()) {
                        ChannelRuntimeDiagnostics.markConnected(channelName, adapterKey, true)
                        ChannelRuntimeDiagnostics.markReady(channelName, adapterKey)
                        EmailGatewayDiagnostics.markConnected(true)
                        EmailGatewayDiagnostics.markReady()
                    }
                    inbound.forEach { item ->
                        EmailGatewayDiagnostics.markInboundSeen(item.senderEmail, item.subject)
                        EmailGatewayDiagnostics.recordSender(
                            EmailSenderCandidate(
                                email = item.senderEmail,
                                subject = item.subject,
                                note = item.preview
                            )
                        )
                        publishInbound(
                            InboundMessage(
                                channel = channelName,
                                senderId = item.senderEmail,
                                chatId = item.senderEmail,
                                content = item.content,
                                metadata = buildMap {
                                    put(GatewayOrchestrator.KEY_ADAPTER_KEY, adapterKey)
                                    put("message_id", item.messageId)
                                    put("subject", item.subject)
                                    put("date", item.dateText)
                                    put("sender_email", item.senderEmail)
                                }
                            )
                        )
                        EmailGatewayDiagnostics.markInboundForwarded()
                    }
                    if (inbound.isEmpty()) {
                        ChannelRuntimeDiagnostics.markConnected(channelName, adapterKey, true)
                        ChannelRuntimeDiagnostics.markReady(channelName, adapterKey)
                        EmailGatewayDiagnostics.markConnected(true)
                        if (EmailGatewayDiagnostics.getSnapshot().ready.not()) {
                            EmailGatewayDiagnostics.markReady()
                        }
                    }
                } catch (t: Throwable) {
                    Log.e(TAG, "Email polling failed", t)
                    ChannelRuntimeDiagnostics.markConnected(channelName, adapterKey, false)
                    ChannelRuntimeDiagnostics.markError(channelName, adapterKey, t.message ?: t.javaClass.simpleName)
                    EmailGatewayDiagnostics.markConnected(false)
                    EmailGatewayDiagnostics.markError(t.message ?: t.javaClass.simpleName)
                }
                delay(config.pollIntervalSeconds.coerceAtLeast(5L) * 1000L)
            }
        }
    }

    override suspend fun send(message: OutboundMessage) {
        if (!config.consentGranted || !isConfigured()) return
        if (message.metadata["_progress"]?.equals("true", ignoreCase = true) == true) return

        val toAddress = normalizeEmail(message.chatId)
        if (toAddress.isBlank()) return

        val existingSubject = lastSubjectByChat[toAddress].orEmpty()
        val forceSend = message.metadata["force_send"]?.equals("true", ignoreCase = true) == true
        if (existingSubject.isNotBlank() && !config.autoReplyEnabled && !forceSend) {
            Log.d(TAG, "Skip automatic email reply to $toAddress because autoReplyEnabled=false")
            return
        }

        val subjectOverride = message.metadata["subject"]?.trim().orEmpty()
        val subject = when {
            subjectOverride.isNotBlank() -> subjectOverride
            existingSubject.isNotBlank() -> replySubject(existingSubject)
            else -> "PalmClaw reply"
        }

        val mailSession = createSmtpSession()
        val mime = MimeMessage(mailSession).apply {
            setFrom(InternetAddress(config.fromAddress.ifBlank { config.smtpUsername }))
            setRecipients(Message.RecipientType.TO, InternetAddress.parse(toAddress))
            this.subject = subject
            sentDate = Date()
            setText(message.content)
            val inReplyTo = lastMessageIdByChat[toAddress].orEmpty()
            if (inReplyTo.isNotBlank()) {
                setHeader("In-Reply-To", inReplyTo)
                setHeader("References", inReplyTo)
            }
        }
        Transport.send(mime)
        EmailGatewayDiagnostics.markOutboundSent()
    }

    override fun canHandleOutbound(message: OutboundMessage): Boolean {
        val requestedKey = message.metadata[GatewayOrchestrator.KEY_ADAPTER_KEY]
            ?.trim()
            ?.ifBlank { null }
        return requestedKey != null && requestedKey == adapterKey
    }

    override fun stop() {
        pollingJob?.cancel()
        pollingJob = null
        ChannelRuntimeDiagnostics.markRunning(channelName, adapterKey, false)
        ChannelRuntimeDiagnostics.markConnected(channelName, adapterKey, false)
        EmailGatewayDiagnostics.markRunning(false)
        EmailGatewayDiagnostics.markConnected(false)
    }

    private fun isConfigured(): Boolean {
        return config.imapHost.isNotBlank() &&
            config.imapUsername.isNotBlank() &&
            config.imapPassword.isNotBlank() &&
            config.smtpHost.isNotBlank() &&
            config.smtpUsername.isNotBlank() &&
            config.smtpPassword.isNotBlank()
    }

    private fun fetchUnreadMessages(): List<InboundEmail> {
        val store = openImapStore()
        try {
            val folder = store.getFolder("INBOX")
            folder.open(Folder.READ_WRITE)
            val unseen = folder.search(FlagTerm(Flags(Flags.Flag.SEEN), false))
                .sortedBy { it.receivedDate ?: it.sentDate ?: Date(0L) }
            return unseen.mapNotNull { message ->
                val uid = (folder as? UIDFolder)?.getUID(message)?.toString().orEmpty()
                if (uid.isNotBlank() && processedUids.contains(uid)) {
                    null
                } else {
                    parseInboundMessage(message)?.also {
                        if (uid.isNotBlank()) {
                            rememberUid(uid)
                        }
                        message.setFlag(Flags.Flag.SEEN, true)
                    }
                }
            }
        } finally {
            runCatching { store.close() }
        }
    }

    private fun parseInboundMessage(message: Message): InboundEmail? {
        val senderEmail = (message.from?.firstOrNull() as? InternetAddress)
            ?.address
            ?.let(::normalizeEmail)
            .orEmpty()
        if (senderEmail.isBlank()) return null

        val subject = message.subject?.trim().orEmpty()
        val messageId = message.getHeader("Message-ID")?.firstOrNull().orEmpty()
        val body = extractTextBody(message)
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .trim()
            .ifBlank { "(empty email body)" }
            .take(config.maxBodyChars)
        val dateText = (message.sentDate ?: message.receivedDate)?.toString().orEmpty()
        lastSubjectByChat[senderEmail] = subject
        if (messageId.isNotBlank()) {
            lastMessageIdByChat[senderEmail] = messageId
        }
        val preview = body.lineSequence().firstOrNull().orEmpty().take(120)
        return InboundEmail(
            senderEmail = senderEmail,
            subject = subject,
            messageId = messageId,
            dateText = dateText,
            preview = preview,
            content = buildString {
                appendLine("Email received.")
                appendLine("From: $senderEmail")
                appendLine("Subject: ${subject.ifBlank { "(no subject)" }}")
                if (dateText.isNotBlank()) {
                    appendLine("Date: $dateText")
                }
                appendLine()
                append(body)
            }.trim()
        )
    }

    private fun extractTextBody(part: Part): String {
        if (part.isMimeType("text/plain")) {
            return part.content?.toString().orEmpty()
        }
        if (part.isMimeType("text/html")) {
            return htmlToText(part.content?.toString().orEmpty())
        }
        if (part.isMimeType("multipart/*")) {
            val multipart = part.content as? Multipart ?: return ""
            var htmlFallback = ""
            for (index in 0 until multipart.count) {
                val bodyPart = multipart.getBodyPart(index)
                if (Part.ATTACHMENT.equals(bodyPart.disposition, ignoreCase = true)) continue
                val text = extractTextBody(bodyPart)
                if (text.isBlank()) continue
                if (bodyPart.isMimeType("text/plain")) return text
                if (htmlFallback.isBlank()) {
                    htmlFallback = text
                }
            }
            return htmlFallback
        }
        return when (val content = part.content) {
            is String -> content
            is ByteArrayOutputStream -> content.toString()
            else -> ""
        }
    }

    private fun openImapStore(): Store {
        val properties = Properties().apply {
            put("mail.store.protocol", "imaps")
            put("mail.imaps.host", config.imapHost)
            put("mail.imaps.port", config.imapPort.toString())
            put("mail.imaps.ssl.enable", "true")
            put("mail.imaps.connectiontimeout", "15000")
            put("mail.imaps.timeout", "30000")
        }
        val session = Session.getInstance(properties)
        return session.getStore("imaps").apply {
            connect(config.imapHost, config.imapPort, config.imapUsername, config.imapPassword)
        }
    }

    private fun createSmtpSession(): Session {
        val properties = Properties().apply {
            put("mail.transport.protocol", "smtp")
            put("mail.smtp.host", config.smtpHost)
            put("mail.smtp.port", config.smtpPort.toString())
            put("mail.smtp.auth", "true")
            put("mail.smtp.starttls.enable", "true")
            put("mail.smtp.connectiontimeout", "15000")
            put("mail.smtp.timeout", "30000")
        }
        return Session.getInstance(properties, object : javax.mail.Authenticator() {
            override fun getPasswordAuthentication(): javax.mail.PasswordAuthentication {
                return javax.mail.PasswordAuthentication(config.smtpUsername, config.smtpPassword)
            }
        })
    }

    private fun htmlToText(raw: String): String {
        return raw
            .replace(Regex("(?i)<br\\s*/?>"), "\n")
            .replace(Regex("(?i)</p>"), "\n")
            .replace(Regex("<[^>]+>"), "")
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
    }

    private fun replySubject(base: String): String {
        val trimmed = base.trim().ifBlank { "PalmClaw reply" }
        return if (trimmed.lowercase(Locale.US).startsWith("re:")) trimmed else "Re: $trimmed"
    }

    private fun rememberUid(uid: String) {
        processedUids += uid
        while (processedUids.size > MAX_PROCESSED_UIDS) {
            processedUids.firstOrNull()?.let { processedUids.remove(it) } ?: break
        }
    }

    private fun normalizeEmail(value: String): String {
        return value.trim().lowercase(Locale.US)
    }

    private data class InboundEmail(
        val senderEmail: String,
        val subject: String,
        val messageId: String,
        val dateText: String,
        val preview: String,
        val content: String
    )

    companion object {
        private const val TAG = "EmailAdapter"
        private const val MAX_PROCESSED_UIDS = 10_000

        fun detectRecentSenders(
            config: EmailAccountConfig,
            limit: Int = 20
        ): List<EmailSenderCandidate> {
            if (
                !config.consentGranted ||
                config.imapHost.isBlank() ||
                config.imapUsername.isBlank() ||
                config.imapPassword.isBlank()
            ) {
                return emptyList()
            }

            val store = openImapStore(config)
            try {
                val folder = store.getFolder("INBOX")
                folder.open(Folder.READ_ONLY)
                val total = folder.messageCount.coerceAtLeast(0)
                if (total <= 0) return emptyList()
                val start = (total - 49).coerceAtLeast(1)
                val messages = folder.getMessages(start, total)
                    .sortedByDescending { it.receivedDate ?: it.sentDate ?: Date(0L) }

                val deduped = linkedMapOf<String, EmailSenderCandidate>()
                messages.forEach { message ->
                    val sender = extractSenderEmail(message.from?.firstOrNull()).orEmpty()
                    if (sender.isBlank() || deduped.containsKey(sender)) return@forEach
                    val subject = message.subject?.trim().orEmpty()
                    val preview = runCatching {
                        extractStaticTextBody(message)
                            .replace("\r\n", "\n")
                            .replace('\r', '\n')
                            .trim()
                            .lineSequence()
                            .firstOrNull()
                            .orEmpty()
                            .take(120)
                    }.getOrDefault("")
                    deduped[sender] = EmailSenderCandidate(
                        email = sender,
                        subject = subject,
                        note = preview
                    )
                    if (deduped.size >= limit) return@forEach
                }
                return deduped.values.toList()
            } finally {
                runCatching { store.close() }
            }
        }

        private fun openImapStore(config: EmailAccountConfig): Store {
            val properties = Properties().apply {
                put("mail.store.protocol", "imaps")
                put("mail.imaps.host", config.imapHost)
                put("mail.imaps.port", config.imapPort.toString())
                put("mail.imaps.ssl.enable", "true")
                put("mail.imaps.connectiontimeout", "15000")
                put("mail.imaps.timeout", "30000")
            }
            val session = Session.getInstance(properties)
            return session.getStore("imaps").apply {
                connect(config.imapHost, config.imapPort, config.imapUsername, config.imapPassword)
            }
        }

        private fun extractSenderEmail(address: Address?): String? {
            return (address as? InternetAddress)
                ?.address
                ?.trim()
                ?.lowercase(Locale.US)
        }

        private fun extractStaticTextBody(part: Part): String {
            if (part.isMimeType("text/plain")) {
                return part.content?.toString().orEmpty()
            }
            if (part.isMimeType("text/html")) {
                return part.content?.toString().orEmpty()
                    .replace(Regex("(?i)<br\\s*/?>"), "\n")
                    .replace(Regex("(?i)</p>"), "\n")
                    .replace(Regex("<[^>]+>"), "")
                    .replace("&nbsp;", " ")
                    .replace("&amp;", "&")
                    .replace("&lt;", "<")
                    .replace("&gt;", ">")
            }
            if (part.isMimeType("multipart/*")) {
                val multipart = part.content as? Multipart ?: return ""
                var htmlFallback = ""
                for (index in 0 until multipart.count) {
                    val bodyPart = multipart.getBodyPart(index)
                    if (Part.ATTACHMENT.equals(bodyPart.disposition, ignoreCase = true)) continue
                    val text = extractStaticTextBody(bodyPart)
                    if (text.isBlank()) continue
                    if (bodyPart.isMimeType("text/plain")) return text
                    if (htmlFallback.isBlank()) {
                        htmlFallback = text
                    }
                }
                return htmlFallback
            }
            return when (val content = part.content) {
                is String -> content
                is ByteArrayOutputStream -> content.toString()
                else -> ""
            }
        }
    }
}
