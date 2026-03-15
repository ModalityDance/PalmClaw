package com.palmclaw.config

import kotlinx.serialization.Serializable

data class AppConfig(
    val providerName: String,
    val apiKey: String,
    val model: String,
    val baseUrl: String = "",
    val maxToolRounds: Int = AppLimits.DEFAULT_MAX_TOOL_ROUNDS,
    val toolResultMaxChars: Int = AppLimits.DEFAULT_TOOL_RESULT_MAX_CHARS,
    val memoryConsolidationWindow: Int = AppLimits.DEFAULT_MEMORY_CONSOLIDATION_WINDOW,
    val llmCallTimeoutSeconds: Int = AppLimits.DEFAULT_LLM_CALL_TIMEOUT_SECONDS,
    val llmConnectTimeoutSeconds: Int = AppLimits.DEFAULT_LLM_CONNECT_TIMEOUT_SECONDS,
    val llmReadTimeoutSeconds: Int = AppLimits.DEFAULT_LLM_READ_TIMEOUT_SECONDS,
    val defaultToolTimeoutSeconds: Int = AppLimits.DEFAULT_TOOL_TIMEOUT_SECONDS,
    val contextMessages: Int = AppLimits.DEFAULT_CONTEXT_MESSAGES,
    val toolArgsPreviewMaxChars: Int = AppLimits.DEFAULT_TOOL_ARGS_PREVIEW_MAX_CHARS
)

data class CronConfig(
    val enabled: Boolean,
    val minEveryMs: Long,
    val maxJobs: Int
)

data class HeartbeatConfig(
    val enabled: Boolean,
    val intervalSeconds: Long
)

data class AlwaysOnConfig(
    val enabled: Boolean = false,
    val keepScreenAwake: Boolean = false
)

data class UiPreferencesConfig(
    val useChinese: Boolean = false,
    val darkTheme: Boolean = false
)

data class OnboardingConfig(
    val completed: Boolean = false,
    val userDisplayName: String = "",
    val agentDisplayName: String = "PalmClaw"
)

object HeartbeatDoc {
    const val FILE_NAME = "HEARTBEAT.md"
}

data class ChannelsConfig(
    val enabled: Boolean,
    val telegramEnabled: Boolean = true,
    val discordEnabled: Boolean = true,
    val slackEnabled: Boolean = true,
    val feishuEnabled: Boolean = true,
    val emailEnabled: Boolean = false,
    val wecomEnabled: Boolean = true,
    val telegramBotToken: String,
    val telegramAllowedChatId: String?,
    val discordWebhookUrl: String
)

data class TokenUsageStats(
    val inputTokens: Long = 0L,
    val outputTokens: Long = 0L,
    val totalTokens: Long = 0L,
    val cachedInputTokens: Long = 0L,
    val requests: Long = 0L
)

@Serializable
data class SessionChannelBinding(
    val sessionId: String,
    val enabled: Boolean = true,
    val channel: String = "",
    val chatId: String = "",
    val telegramBotToken: String = "",
    val telegramAllowedChatId: String? = null,
    val discordBotToken: String = "",
    val discordResponseMode: String = "mention",
    val discordAllowedUserIds: List<String> = emptyList(),
    val slackBotToken: String = "",
    val slackAppToken: String = "",
    val slackResponseMode: String = "mention",
    val slackAllowedUserIds: List<String> = emptyList(),
    val feishuAppId: String = "",
    val feishuAppSecret: String = "",
    val feishuEncryptKey: String = "",
    val feishuVerificationToken: String = "",
    val feishuAllowedOpenIds: List<String> = emptyList(),
    val emailConsentGranted: Boolean = false,
    val emailImapHost: String = "",
    val emailImapPort: Int = 993,
    val emailImapUsername: String = "",
    val emailImapPassword: String = "",
    val emailSmtpHost: String = "",
    val emailSmtpPort: Int = 587,
    val emailSmtpUsername: String = "",
    val emailSmtpPassword: String = "",
    val emailFromAddress: String = "",
    val emailAutoReplyEnabled: Boolean = true,
    val wecomBotId: String = "",
    val wecomSecret: String = "",
    val wecomAllowedUserIds: List<String> = emptyList()
)

@Serializable
data class McpHttpServerConfig(
    val id: String = "",
    val serverName: String = "default",
    val serverUrl: String = "",
    val authToken: String = "",
    val toolTimeoutSeconds: Int = 30
)

data class McpHttpConfig(
    val enabled: Boolean = false,
    val serverName: String = "default",
    val serverUrl: String = "",
    val authToken: String = "",
    val toolTimeoutSeconds: Int = 30,
    val servers: List<McpHttpServerConfig> = emptyList()
)

object AppLimits {
    const val DEFAULT_PROVIDER = "openai"
    const val DEFAULT_MODEL = "gpt-5-nano"

    const val DEFAULT_MAX_TOOL_ROUNDS = 10
    const val MIN_MAX_TOOL_ROUNDS = 1
    const val MAX_MAX_TOOL_ROUNDS = 100

    const val DEFAULT_TOOL_RESULT_MAX_CHARS = 500
    const val MIN_TOOL_RESULT_MAX_CHARS = 100
    const val MAX_TOOL_RESULT_MAX_CHARS = 20_000

    const val DEFAULT_MEMORY_CONSOLIDATION_WINDOW = 50
    const val MIN_MEMORY_CONSOLIDATION_WINDOW = 10
    const val MAX_MEMORY_CONSOLIDATION_WINDOW = 400

    const val DEFAULT_LLM_CALL_TIMEOUT_SECONDS = 120
    const val MIN_LLM_CALL_TIMEOUT_SECONDS = 30
    const val MAX_LLM_CALL_TIMEOUT_SECONDS = 600

    const val DEFAULT_LLM_CONNECT_TIMEOUT_SECONDS = 20
    const val MIN_LLM_CONNECT_TIMEOUT_SECONDS = 5
    const val MAX_LLM_CONNECT_TIMEOUT_SECONDS = 120

    const val DEFAULT_LLM_READ_TIMEOUT_SECONDS = 120
    const val MIN_LLM_READ_TIMEOUT_SECONDS = 30
    const val MAX_LLM_READ_TIMEOUT_SECONDS = 600

    const val DEFAULT_TOOL_TIMEOUT_SECONDS = 60
    const val MIN_TOOL_TIMEOUT_SECONDS = 5
    const val MAX_TOOL_TIMEOUT_SECONDS = 600

    const val DEFAULT_CONTEXT_MESSAGES = 20
    const val MIN_CONTEXT_MESSAGES = 10
    const val MAX_CONTEXT_MESSAGES = 200

    const val DEFAULT_TOOL_ARGS_PREVIEW_MAX_CHARS = 4_000
    const val MIN_TOOL_ARGS_PREVIEW_MAX_CHARS = 500
    const val MAX_TOOL_ARGS_PREVIEW_MAX_CHARS = 50_000

    const val DEFAULT_CRON_ENABLED = false
    const val DEFAULT_CRON_MIN_EVERY_MS = 60_000L
    const val DEFAULT_CRON_MAX_JOBS = 50
    const val MIN_CRON_MIN_EVERY_MS = 1_000L
    const val MAX_CRON_MIN_EVERY_MS = 86_400_000L
    const val MIN_CRON_MAX_JOBS = 1
    const val MAX_CRON_MAX_JOBS = 500

    const val DEFAULT_HEARTBEAT_INTERVAL_SECONDS = 30L * 60L
    const val MIN_HEARTBEAT_INTERVAL_SECONDS = 30L
    const val MAX_HEARTBEAT_INTERVAL_SECONDS = 86_400L

    const val DEFAULT_MCP_HTTP_SERVER_NAME = "default"
    const val DEFAULT_MCP_HTTP_TOOL_TIMEOUT_SECONDS = 30
    const val MIN_MCP_HTTP_TOOL_TIMEOUT_SECONDS = 5
    const val MAX_MCP_HTTP_TOOL_TIMEOUT_SECONDS = 300
}

object AppSession {
    const val LOCAL_SESSION_ID = "local"
    const val LOCAL_SESSION_TITLE = "local"
    const val SHARED_SESSION_ID = LOCAL_SESSION_ID
    const val SHARED_SESSION_TITLE = LOCAL_SESSION_TITLE
}
