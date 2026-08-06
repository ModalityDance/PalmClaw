package com.palmclaw.channels

data class TelegramDiscoveryRequest(
    val botToken: String
)

data class FeishuDiscoveryRequest(
    val appId: String,
    val appSecret: String,
    val encryptKey: String = "",
    val verificationToken: String = ""
)

data class EmailDiscoveryRequest(
    val consentGranted: Boolean,
    val imapHost: String,
    val imapPort: String,
    val imapUsername: String,
    val imapPassword: String,
    val smtpHost: String,
    val smtpPort: String,
    val smtpUsername: String,
    val smtpPassword: String,
    val fromAddress: String,
    val autoReplyEnabled: Boolean
)

data class WeComDiscoveryRequest(
    val botId: String,
    val secret: String
)

data class TelegramChatCandidate(
    val chatId: String,
    val title: String,
    val kind: String
)

enum class ChannelDiscoveryCompletionReason {
    NO_CANDIDATES,
    TIMEOUT
}

enum class ChannelDiscoveryFailureKind {
    INVALID_INPUT,
    AUTHENTICATION,
    NETWORK,
    RUNTIME_CONFLICT,
    UNEXPECTED
}

class ChannelDiscoveryException(
    val kind: ChannelDiscoveryFailureKind,
    message: String
) : IllegalStateException(message)

sealed interface ChannelDiscoveryOutcome<out T> {
    data class Completed<out T>(
        val candidates: List<T>,
        val info: String = "",
        val reason: ChannelDiscoveryCompletionReason? = null
    ) : ChannelDiscoveryOutcome<T>

    data class Failed<out T>(
        val kind: ChannelDiscoveryFailureKind,
        val message: String,
        val fallbackCandidates: List<T> = emptyList()
    ) : ChannelDiscoveryOutcome<T>
}

fun interface TelegramDiscoveryClient {
    fun discover(botToken: String): List<TelegramChatCandidate>
}

fun interface EmailSenderDetector {
    fun detect(config: EmailAccountConfig): List<EmailSenderCandidate>
}

interface ChannelDiscoveryDiagnosticsSource {
    fun feishuSnapshots(): Map<String, FeishuGatewaySnapshot>
    fun weComSnapshot(adapterKey: String): WeComGatewaySnapshot
    fun emailSnapshot(adapterKey: String): EmailGatewaySnapshot
}

interface ChannelDiscoveryAdapterFactory {
    fun createFeishu(request: FeishuDiscoveryRequest, adapterKey: String): ChannelAdapter
    fun createWeCom(request: WeComDiscoveryRequest, adapterKey: String): ChannelAdapter
}

fun interface ChannelDiscoveryClock {
    fun nowMs(): Long
}

fun interface ChannelDiscoverySleeper {
    suspend fun sleep(durationMs: Long)
}
