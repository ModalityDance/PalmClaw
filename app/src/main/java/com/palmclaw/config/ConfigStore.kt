package com.palmclaw.config

import android.content.Context
import com.palmclaw.providers.LlmUsage
import com.palmclaw.providers.ProviderCatalog
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import java.util.Locale

class ConfigStore(context: Context) {
    private val prefs = context.getSharedPreferences("palmclaw_config", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    fun getUiPreferencesConfig(): UiPreferencesConfig {
        return UiPreferencesConfig(
            useChinese = prefs.getBoolean(KEY_UI_USE_CHINESE, false),
            darkTheme = prefs.getBoolean(KEY_UI_DARK_THEME, false)
        )
    }

    fun saveUiPreferencesConfig(config: UiPreferencesConfig) {
        prefs.edit()
            .putBoolean(KEY_UI_USE_CHINESE, config.useChinese)
            .putBoolean(KEY_UI_DARK_THEME, config.darkTheme)
            .apply()
    }

    fun getOnboardingConfig(): OnboardingConfig {
        return OnboardingConfig(
            completed = prefs.getBoolean(KEY_ONBOARDING_COMPLETED, false),
            userDisplayName = prefs.getString(KEY_ONBOARDING_USER_DISPLAY_NAME, "")
                .orEmpty()
                .trim(),
            agentDisplayName = prefs.getString(KEY_ONBOARDING_AGENT_DISPLAY_NAME, "PalmClaw")
                .orEmpty()
                .ifBlank { "PalmClaw" }
        )
    }

    fun saveOnboardingConfig(config: OnboardingConfig) {
        prefs.edit()
            .putBoolean(KEY_ONBOARDING_COMPLETED, config.completed)
            .putString(
                KEY_ONBOARDING_USER_DISPLAY_NAME,
                config.userDisplayName.trim()
            )
            .putString(
                KEY_ONBOARDING_AGENT_DISPLAY_NAME,
                config.agentDisplayName.trim().ifBlank { "PalmClaw" }
            )
            .apply()
    }

    fun hasCompletedFirstRunAutoIntro(): Boolean {
        return prefs.getBoolean(KEY_FIRST_RUN_AUTO_INTRO_COMPLETED, false)
    }

    fun markFirstRunAutoIntroCompleted() {
        prefs.edit()
            .putBoolean(KEY_FIRST_RUN_AUTO_INTRO_COMPLETED, true)
            .apply()
    }

    fun getConfig(): AppConfig {
        val storedRounds = prefs.getInt(KEY_MAX_TOOL_ROUNDS, AppLimits.DEFAULT_MAX_TOOL_ROUNDS)
        val storedToolResultMaxChars =
            prefs.getInt(KEY_TOOL_RESULT_MAX_CHARS, AppLimits.DEFAULT_TOOL_RESULT_MAX_CHARS)
        val storedMemoryConsolidationWindow =
            prefs.getInt(KEY_MEMORY_CONSOLIDATION_WINDOW, AppLimits.DEFAULT_MEMORY_CONSOLIDATION_WINDOW)
        val storedLlmCallTimeoutSeconds =
            prefs.getInt(KEY_LLM_CALL_TIMEOUT_SECONDS, AppLimits.DEFAULT_LLM_CALL_TIMEOUT_SECONDS)
        val storedLlmConnectTimeoutSeconds =
            prefs.getInt(KEY_LLM_CONNECT_TIMEOUT_SECONDS, AppLimits.DEFAULT_LLM_CONNECT_TIMEOUT_SECONDS)
        val storedLlmReadTimeoutSeconds =
            prefs.getInt(KEY_LLM_READ_TIMEOUT_SECONDS, AppLimits.DEFAULT_LLM_READ_TIMEOUT_SECONDS)
        val storedDefaultToolTimeoutSeconds =
            prefs.getInt(KEY_DEFAULT_TOOL_TIMEOUT_SECONDS, AppLimits.DEFAULT_TOOL_TIMEOUT_SECONDS)
        val storedContextMessages =
            prefs.getInt(KEY_CONTEXT_MESSAGES, AppLimits.DEFAULT_CONTEXT_MESSAGES)
        val storedToolArgsPreviewMaxChars =
            prefs.getInt(KEY_TOOL_ARGS_PREVIEW_MAX_CHARS, AppLimits.DEFAULT_TOOL_ARGS_PREVIEW_MAX_CHARS)
        val providerConfigs = loadProviderConnectionConfigs()
        val activeProviderConfigId = resolveActiveProviderConfigId(providerConfigs)
        val activeProviderConfig = providerConfigs.firstOrNull { it.id == activeProviderConfigId }
            ?: providerConfigs.firstOrNull()
        val legacyProviderName = prefs.getString(KEY_PROVIDER, AppLimits.DEFAULT_PROVIDER).orEmpty()
        val legacyApiKey = prefs.getString(KEY_API_KEY, "").orEmpty()
        val legacyModel = prefs.getString(KEY_MODEL, "").orEmpty()
        val legacyBaseUrl = prefs.getString(KEY_BASE_URL, "").orEmpty()
        val resolvedLegacyProvider = ProviderCatalog.resolve(legacyProviderName)
        return AppConfig(
            providerName = activeProviderConfig?.providerName
                ?.trim()
                ?.ifBlank { resolvedLegacyProvider.id }
                ?: resolvedLegacyProvider.id,
            apiKey = activeProviderConfig?.apiKey ?: legacyApiKey,
            model = activeProviderConfig?.model
                ?.trim()
                ?.ifBlank {
                    legacyModel.ifBlank {
                        ProviderCatalog.defaultModel(activeProviderConfig?.providerName ?: resolvedLegacyProvider.id)
                    }
                }
                ?: legacyModel.ifBlank { ProviderCatalog.defaultModel(resolvedLegacyProvider.id) },
            baseUrl = activeProviderConfig?.baseUrl ?: legacyBaseUrl,
            providerConfigs = providerConfigs,
            activeProviderConfigId = activeProviderConfigId,
            maxToolRounds = storedRounds.coerceIn(AppLimits.MIN_MAX_TOOL_ROUNDS, AppLimits.MAX_MAX_TOOL_ROUNDS),
            toolResultMaxChars = storedToolResultMaxChars.coerceIn(
                AppLimits.MIN_TOOL_RESULT_MAX_CHARS,
                AppLimits.MAX_TOOL_RESULT_MAX_CHARS
            ),
            memoryConsolidationWindow = storedMemoryConsolidationWindow.coerceIn(
                AppLimits.MIN_MEMORY_CONSOLIDATION_WINDOW,
                AppLimits.MAX_MEMORY_CONSOLIDATION_WINDOW
            ),
            llmCallTimeoutSeconds = storedLlmCallTimeoutSeconds.coerceIn(
                AppLimits.MIN_LLM_CALL_TIMEOUT_SECONDS,
                AppLimits.MAX_LLM_CALL_TIMEOUT_SECONDS
            ),
            llmConnectTimeoutSeconds = storedLlmConnectTimeoutSeconds.coerceIn(
                AppLimits.MIN_LLM_CONNECT_TIMEOUT_SECONDS,
                AppLimits.MAX_LLM_CONNECT_TIMEOUT_SECONDS
            ),
            llmReadTimeoutSeconds = storedLlmReadTimeoutSeconds.coerceIn(
                AppLimits.MIN_LLM_READ_TIMEOUT_SECONDS,
                AppLimits.MAX_LLM_READ_TIMEOUT_SECONDS
            ),
            defaultToolTimeoutSeconds = storedDefaultToolTimeoutSeconds.coerceIn(
                AppLimits.MIN_TOOL_TIMEOUT_SECONDS,
                AppLimits.MAX_TOOL_TIMEOUT_SECONDS
            ),
            contextMessages = storedContextMessages.coerceIn(
                AppLimits.MIN_CONTEXT_MESSAGES,
                AppLimits.MAX_CONTEXT_MESSAGES
            ),
            toolArgsPreviewMaxChars = storedToolArgsPreviewMaxChars.coerceIn(
                AppLimits.MIN_TOOL_ARGS_PREVIEW_MAX_CHARS,
                AppLimits.MAX_TOOL_ARGS_PREVIEW_MAX_CHARS
            )
        )
    }

    fun saveConfig(config: AppConfig) {
        val normalizedProviderConfigs = normalizeProviderConnectionConfigs(config.providerConfigs)
        val activeProviderConfigId = resolveActiveProviderConfigId(
            normalizedProviderConfigs,
            requestedId = config.activeProviderConfigId
        )
        val activeProviderConfig = normalizedProviderConfigs.firstOrNull { it.id == activeProviderConfigId }
        val fallbackProvider = ProviderCatalog.resolve(config.providerName)
        val fallbackModel = config.model.trim().ifBlank { ProviderCatalog.defaultModel(fallbackProvider.id) }
        val fallbackBaseUrl = config.baseUrl.trim()
        prefs.edit()
            .putString(KEY_PROVIDER, activeProviderConfig?.providerName ?: fallbackProvider.id)
            .putString(KEY_API_KEY, activeProviderConfig?.apiKey ?: config.apiKey)
            .putString(KEY_MODEL, activeProviderConfig?.model ?: fallbackModel)
            .putString(KEY_BASE_URL, activeProviderConfig?.baseUrl ?: fallbackBaseUrl)
            .putString(KEY_PROVIDER_CONFIGS_JSON, json.encodeToString(normalizedProviderConfigs))
            .putString(KEY_ACTIVE_PROVIDER_CONFIG_ID, activeProviderConfigId.takeIf { it.isNotBlank() })
            .putInt(
                KEY_MAX_TOOL_ROUNDS,
                config.maxToolRounds.coerceIn(AppLimits.MIN_MAX_TOOL_ROUNDS, AppLimits.MAX_MAX_TOOL_ROUNDS)
            )
            .putInt(
                KEY_TOOL_RESULT_MAX_CHARS,
                config.toolResultMaxChars.coerceIn(
                    AppLimits.MIN_TOOL_RESULT_MAX_CHARS,
                    AppLimits.MAX_TOOL_RESULT_MAX_CHARS
                )
            )
            .putInt(
                KEY_MEMORY_CONSOLIDATION_WINDOW,
                config.memoryConsolidationWindow.coerceIn(
                    AppLimits.MIN_MEMORY_CONSOLIDATION_WINDOW,
                    AppLimits.MAX_MEMORY_CONSOLIDATION_WINDOW
                )
            )
            .putInt(
                KEY_LLM_CALL_TIMEOUT_SECONDS,
                config.llmCallTimeoutSeconds.coerceIn(
                    AppLimits.MIN_LLM_CALL_TIMEOUT_SECONDS,
                    AppLimits.MAX_LLM_CALL_TIMEOUT_SECONDS
                )
            )
            .putInt(
                KEY_LLM_CONNECT_TIMEOUT_SECONDS,
                config.llmConnectTimeoutSeconds.coerceIn(
                    AppLimits.MIN_LLM_CONNECT_TIMEOUT_SECONDS,
                    AppLimits.MAX_LLM_CONNECT_TIMEOUT_SECONDS
                )
            )
            .putInt(
                KEY_LLM_READ_TIMEOUT_SECONDS,
                config.llmReadTimeoutSeconds.coerceIn(
                    AppLimits.MIN_LLM_READ_TIMEOUT_SECONDS,
                    AppLimits.MAX_LLM_READ_TIMEOUT_SECONDS
                )
            )
            .putInt(
                KEY_DEFAULT_TOOL_TIMEOUT_SECONDS,
                config.defaultToolTimeoutSeconds.coerceIn(
                    AppLimits.MIN_TOOL_TIMEOUT_SECONDS,
                    AppLimits.MAX_TOOL_TIMEOUT_SECONDS
                )
            )
            .putInt(
                KEY_CONTEXT_MESSAGES,
                config.contextMessages.coerceIn(
                    AppLimits.MIN_CONTEXT_MESSAGES,
                    AppLimits.MAX_CONTEXT_MESSAGES
                )
            )
            .putInt(
                KEY_TOOL_ARGS_PREVIEW_MAX_CHARS,
                config.toolArgsPreviewMaxChars.coerceIn(
                    AppLimits.MIN_TOOL_ARGS_PREVIEW_MAX_CHARS,
                    AppLimits.MAX_TOOL_ARGS_PREVIEW_MAX_CHARS
                )
            )
            .apply()
    }

    private fun loadProviderConnectionConfigs(): List<ProviderConnectionConfig> {
        val stored = prefs.getString(KEY_PROVIDER_CONFIGS_JSON, null)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.let { raw ->
                runCatching { json.decodeFromString<List<ProviderConnectionConfig>>(raw) }
                    .getOrDefault(emptyList())
            }
            .orEmpty()
        val normalizedStored = normalizeProviderConnectionConfigs(stored)
        if (normalizedStored.isNotEmpty() || prefs.contains(KEY_PROVIDER_CONFIGS_JSON)) {
            return normalizedStored
        }
        val legacy = loadLegacyProviderConnectionConfig()
        return if (legacy == null) emptyList() else listOf(legacy)
    }

    private fun loadLegacyProviderConnectionConfig(): ProviderConnectionConfig? {
        val providerName = prefs.getString(KEY_PROVIDER, AppLimits.DEFAULT_PROVIDER).orEmpty().trim()
        val apiKey = prefs.getString(KEY_API_KEY, "").orEmpty()
        val model = prefs.getString(KEY_MODEL, "").orEmpty().trim()
        val baseUrl = prefs.getString(KEY_BASE_URL, "").orEmpty().trim()
        val looksConfigured = apiKey.isNotBlank() ||
            baseUrl.isNotBlank() ||
            (prefs.contains(KEY_MODEL) && model.isNotBlank()) ||
            (prefs.contains(KEY_PROVIDER) && providerName.isNotBlank() && providerName != AppLimits.DEFAULT_PROVIDER)
        if (!looksConfigured) return null
        return ProviderConnectionConfig(
            id = "provider_legacy",
            providerName = ProviderCatalog.resolve(providerName).id,
            apiKey = apiKey,
            model = model.ifBlank { ProviderCatalog.defaultModel(providerName) },
            baseUrl = baseUrl
        )
    }

    private fun normalizeProviderConnectionConfigs(
        configs: List<ProviderConnectionConfig>
    ): List<ProviderConnectionConfig> {
        return configs.mapIndexedNotNull { index, config ->
            val providerId = ProviderCatalog.resolve(config.providerName).id
            val model = config.model.trim().ifBlank { ProviderCatalog.defaultModel(providerId) }
            val baseUrl = config.baseUrl.trim()
            val apiKey = config.apiKey.trim()
            val id = config.id.trim().ifBlank { "provider_${index + 1}" }
            if (providerId.isBlank() && model.isBlank() && baseUrl.isBlank() && apiKey.isBlank()) {
                null
            } else {
                ProviderConnectionConfig(
                    id = id,
                    providerName = providerId,
                    apiKey = apiKey,
                    model = model,
                    baseUrl = baseUrl
                )
            }
        }.distinctBy { it.id }
    }

    private fun resolveActiveProviderConfigId(
        configs: List<ProviderConnectionConfig>,
        requestedId: String = prefs.getString(KEY_ACTIVE_PROVIDER_CONFIG_ID, "").orEmpty()
    ): String {
        val normalizedRequested = requestedId.trim()
        if (normalizedRequested.isNotBlank() && configs.any { it.id == normalizedRequested }) {
            return normalizedRequested
        }
        return configs.firstOrNull()?.id.orEmpty()
    }

    fun getTokenUsageStats(): TokenUsageStats {
        return TokenUsageStats(
            inputTokens = prefs.getLong(KEY_TOKEN_INPUT_TOKENS, 0L).coerceAtLeast(0L),
            outputTokens = prefs.getLong(KEY_TOKEN_OUTPUT_TOKENS, 0L).coerceAtLeast(0L),
            totalTokens = prefs.getLong(KEY_TOKEN_TOTAL_TOKENS, 0L).coerceAtLeast(0L),
            cachedInputTokens = prefs.getLong(KEY_TOKEN_CACHED_INPUT_TOKENS, 0L).coerceAtLeast(0L),
            requests = prefs.getLong(KEY_TOKEN_REQUESTS, 0L).coerceAtLeast(0L)
        )
    }

    fun recordTokenUsage(usage: LlmUsage): TokenUsageStats {
        val current = getTokenUsageStats()
        val merged = TokenUsageStats(
            inputTokens = (current.inputTokens + usage.inputTokens.coerceAtLeast(0L)).coerceAtLeast(0L),
            outputTokens = (current.outputTokens + usage.outputTokens.coerceAtLeast(0L)).coerceAtLeast(0L),
            totalTokens = (current.totalTokens + usage.totalTokens.coerceAtLeast(0L)).coerceAtLeast(0L),
            cachedInputTokens = (current.cachedInputTokens + usage.cachedInputTokens.coerceAtLeast(0L)).coerceAtLeast(0L),
            requests = (current.requests + 1L).coerceAtLeast(0L)
        )
        saveTokenUsageStats(merged)
        return merged
    }

    fun clearTokenUsageStats() {
        saveTokenUsageStats(TokenUsageStats())
    }

    private fun saveTokenUsageStats(stats: TokenUsageStats) {
        prefs.edit()
            .putLong(KEY_TOKEN_INPUT_TOKENS, stats.inputTokens.coerceAtLeast(0L))
            .putLong(KEY_TOKEN_OUTPUT_TOKENS, stats.outputTokens.coerceAtLeast(0L))
            .putLong(KEY_TOKEN_TOTAL_TOKENS, stats.totalTokens.coerceAtLeast(0L))
            .putLong(KEY_TOKEN_CACHED_INPUT_TOKENS, stats.cachedInputTokens.coerceAtLeast(0L))
            .putLong(KEY_TOKEN_REQUESTS, stats.requests.coerceAtLeast(0L))
            .apply()
    }

    fun getChannelsConfig(): ChannelsConfig {
        return ChannelsConfig(
            enabled = prefs.getBoolean(KEY_GATEWAY_ENABLED, false),
            telegramEnabled = prefs.getBoolean(KEY_TELEGRAM_ENABLED, true),
            discordEnabled = prefs.getBoolean(KEY_DISCORD_ENABLED, true),
            slackEnabled = prefs.getBoolean(KEY_SLACK_ENABLED, true),
            feishuEnabled = prefs.getBoolean(KEY_FEISHU_ENABLED, true),
            emailEnabled = prefs.getBoolean(KEY_EMAIL_ENABLED, false),
            wecomEnabled = prefs.getBoolean(KEY_WECOM_ENABLED, true),
            telegramBotToken = prefs.getString(KEY_TELEGRAM_BOT_TOKEN, "").orEmpty(),
            telegramAllowedChatId = prefs.getString(KEY_TELEGRAM_ALLOWED_CHAT_ID, null),
            discordWebhookUrl = prefs.getString(KEY_DISCORD_WEBHOOK_URL, "").orEmpty()
        )
    }

    fun saveChannelsConfig(config: ChannelsConfig) {
        prefs.edit()
            .putBoolean(KEY_GATEWAY_ENABLED, config.enabled)
            .putBoolean(KEY_TELEGRAM_ENABLED, config.telegramEnabled)
            .putBoolean(KEY_DISCORD_ENABLED, config.discordEnabled)
            .putBoolean(KEY_SLACK_ENABLED, config.slackEnabled)
            .putBoolean(KEY_FEISHU_ENABLED, config.feishuEnabled)
            .putBoolean(KEY_EMAIL_ENABLED, config.emailEnabled)
            .putBoolean(KEY_WECOM_ENABLED, config.wecomEnabled)
            .putString(KEY_TELEGRAM_BOT_TOKEN, config.telegramBotToken)
            .putString(KEY_TELEGRAM_ALLOWED_CHAT_ID, config.telegramAllowedChatId)
            .putString(KEY_DISCORD_WEBHOOK_URL, config.discordWebhookUrl)
            .apply()
    }

    fun getSessionChannelBindings(): List<SessionChannelBinding> {
        return prefs.getString(KEY_SESSION_CHANNEL_BINDINGS_JSON, null)
            ?.takeIf { it.isNotBlank() }
            ?.let { raw ->
                runCatching { json.decodeFromString<List<SessionChannelBinding>>(raw) }
                    .getOrDefault(emptyList())
            }
            .orEmpty()
            .mapNotNull { item ->
                val sid = item.sessionId.trim()
                if (sid.isBlank()) return@mapNotNull null
                val normalizedChannel = normalizeSessionChannel(item.channel)
                if (normalizedChannel.isBlank()) return@mapNotNull null
                item.copy(
                    sessionId = sid,
                    enabled = item.enabled,
                    channel = normalizedChannel,
                    chatId = normalizeChannelChatId(normalizedChannel, item.chatId.trim()),
                    telegramBotToken = item.telegramBotToken.trim(),
                    telegramAllowedChatId = item.telegramAllowedChatId?.trim()?.ifBlank { null },
                    discordBotToken = item.discordBotToken.trim(),
                    discordResponseMode = normalizeDiscordResponseMode(item.discordResponseMode),
                    discordAllowedUserIds = normalizeDiscordAllowedUserIds(item.discordAllowedUserIds),
                    slackBotToken = item.slackBotToken.trim(),
                    slackAppToken = item.slackAppToken.trim(),
                    slackResponseMode = normalizeSlackResponseMode(item.slackResponseMode),
                    slackAllowedUserIds = normalizeSlackAllowedUserIds(item.slackAllowedUserIds),
                    feishuAppId = item.feishuAppId.trim(),
                    feishuAppSecret = item.feishuAppSecret.trim(),
                    feishuEncryptKey = item.feishuEncryptKey.trim(),
                    feishuVerificationToken = item.feishuVerificationToken.trim(),
                    feishuAllowedOpenIds = normalizeFeishuAllowedOpenIds(item.feishuAllowedOpenIds),
                    emailConsentGranted = item.emailConsentGranted,
                    emailImapHost = item.emailImapHost.trim(),
                    emailImapPort = item.emailImapPort.coerceIn(1, 65535),
                    emailImapUsername = item.emailImapUsername.trim(),
                    emailImapPassword = item.emailImapPassword,
                    emailSmtpHost = item.emailSmtpHost.trim(),
                    emailSmtpPort = item.emailSmtpPort.coerceIn(1, 65535),
                    emailSmtpUsername = item.emailSmtpUsername.trim(),
                    emailSmtpPassword = item.emailSmtpPassword,
                    emailFromAddress = normalizeEmailAddress(item.emailFromAddress),
                    emailAutoReplyEnabled = item.emailAutoReplyEnabled,
                    wecomBotId = item.wecomBotId.trim(),
                    wecomSecret = item.wecomSecret.trim(),
                    wecomAllowedUserIds = normalizeWeComAllowedUserIds(item.wecomAllowedUserIds)
                )
            }
            .distinctBy { it.sessionId }
    }

    fun saveSessionChannelBindings(bindings: List<SessionChannelBinding>) {
        val normalized = bindings.mapNotNull { item ->
            val sid = item.sessionId.trim()
            if (sid.isBlank()) return@mapNotNull null
            val normalizedChannel = normalizeSessionChannel(item.channel)
            if (normalizedChannel.isBlank()) return@mapNotNull null
            item.copy(
                sessionId = sid,
                enabled = item.enabled,
                channel = normalizedChannel,
                chatId = normalizeChannelChatId(normalizedChannel, item.chatId.trim()),
                telegramBotToken = item.telegramBotToken.trim(),
                telegramAllowedChatId = item.telegramAllowedChatId?.trim()?.ifBlank { null },
                discordBotToken = item.discordBotToken.trim(),
                discordResponseMode = normalizeDiscordResponseMode(item.discordResponseMode),
                discordAllowedUserIds = normalizeDiscordAllowedUserIds(item.discordAllowedUserIds),
                slackBotToken = item.slackBotToken.trim(),
                slackAppToken = item.slackAppToken.trim(),
                slackResponseMode = normalizeSlackResponseMode(item.slackResponseMode),
                slackAllowedUserIds = normalizeSlackAllowedUserIds(item.slackAllowedUserIds),
                feishuAppId = item.feishuAppId.trim(),
                feishuAppSecret = item.feishuAppSecret.trim(),
                feishuEncryptKey = item.feishuEncryptKey.trim(),
                feishuVerificationToken = item.feishuVerificationToken.trim(),
                feishuAllowedOpenIds = normalizeFeishuAllowedOpenIds(item.feishuAllowedOpenIds),
                emailConsentGranted = item.emailConsentGranted,
                emailImapHost = item.emailImapHost.trim(),
                emailImapPort = item.emailImapPort.coerceIn(1, 65535),
                emailImapUsername = item.emailImapUsername.trim(),
                emailImapPassword = item.emailImapPassword,
                emailSmtpHost = item.emailSmtpHost.trim(),
                emailSmtpPort = item.emailSmtpPort.coerceIn(1, 65535),
                emailSmtpUsername = item.emailSmtpUsername.trim(),
                emailSmtpPassword = item.emailSmtpPassword,
                emailFromAddress = normalizeEmailAddress(item.emailFromAddress),
                emailAutoReplyEnabled = item.emailAutoReplyEnabled,
                wecomBotId = item.wecomBotId.trim(),
                wecomSecret = item.wecomSecret.trim(),
                wecomAllowedUserIds = normalizeWeComAllowedUserIds(item.wecomAllowedUserIds)
            )
        }.distinctBy { it.sessionId }

        prefs.edit()
            .putString(KEY_SESSION_CHANNEL_BINDINGS_JSON, json.encodeToString(normalized))
            .apply()
    }

    fun saveSessionChannelBinding(binding: SessionChannelBinding) {
        val sid = binding.sessionId.trim()
        if (sid.isBlank()) return
        val normalizedChannel = normalizeSessionChannel(binding.channel)
        if (normalizedChannel.isBlank()) {
            clearSessionChannelBinding(sid)
            return
        }
        val normalizedBinding = binding.copy(
            sessionId = sid,
            enabled = binding.enabled,
            channel = normalizedChannel,
            chatId = normalizeChannelChatId(normalizedChannel, binding.chatId.trim()),
            telegramBotToken = binding.telegramBotToken.trim(),
            telegramAllowedChatId = binding.telegramAllowedChatId?.trim()?.ifBlank { null },
            discordBotToken = binding.discordBotToken.trim(),
            discordResponseMode = normalizeDiscordResponseMode(binding.discordResponseMode),
            discordAllowedUserIds = normalizeDiscordAllowedUserIds(binding.discordAllowedUserIds),
            slackBotToken = binding.slackBotToken.trim(),
            slackAppToken = binding.slackAppToken.trim(),
            slackResponseMode = normalizeSlackResponseMode(binding.slackResponseMode),
            slackAllowedUserIds = normalizeSlackAllowedUserIds(binding.slackAllowedUserIds),
            feishuAppId = binding.feishuAppId.trim(),
            feishuAppSecret = binding.feishuAppSecret.trim(),
            feishuEncryptKey = binding.feishuEncryptKey.trim(),
            feishuVerificationToken = binding.feishuVerificationToken.trim(),
            feishuAllowedOpenIds = normalizeFeishuAllowedOpenIds(binding.feishuAllowedOpenIds),
            emailConsentGranted = binding.emailConsentGranted,
            emailImapHost = binding.emailImapHost.trim(),
            emailImapPort = binding.emailImapPort.coerceIn(1, 65535),
            emailImapUsername = binding.emailImapUsername.trim(),
            emailImapPassword = binding.emailImapPassword,
            emailSmtpHost = binding.emailSmtpHost.trim(),
            emailSmtpPort = binding.emailSmtpPort.coerceIn(1, 65535),
            emailSmtpUsername = binding.emailSmtpUsername.trim(),
            emailSmtpPassword = binding.emailSmtpPassword,
            emailFromAddress = normalizeEmailAddress(binding.emailFromAddress),
            emailAutoReplyEnabled = binding.emailAutoReplyEnabled,
            wecomBotId = binding.wecomBotId.trim(),
            wecomSecret = binding.wecomSecret.trim(),
            wecomAllowedUserIds = normalizeWeComAllowedUserIds(binding.wecomAllowedUserIds)
        )
        val next = getSessionChannelBindings()
            .filterNot { it.sessionId == sid } + normalizedBinding
        saveSessionChannelBindings(next)
    }

    fun clearSessionChannelBinding(sessionId: String) {
        val sid = sessionId.trim()
        if (sid.isBlank()) return
        val next = getSessionChannelBindings().filterNot { it.sessionId == sid }
        saveSessionChannelBindings(next)
    }

    fun getCronConfig(): CronConfig {
        val minEveryMs = prefs.getLong(KEY_CRON_MIN_EVERY_MS, AppLimits.DEFAULT_CRON_MIN_EVERY_MS)
        val maxJobs = prefs.getInt(KEY_CRON_MAX_JOBS, AppLimits.DEFAULT_CRON_MAX_JOBS)
        return CronConfig(
            enabled = prefs.getBoolean(KEY_CRON_ENABLED, AppLimits.DEFAULT_CRON_ENABLED),
            minEveryMs = minEveryMs.coerceIn(AppLimits.MIN_CRON_MIN_EVERY_MS, AppLimits.MAX_CRON_MIN_EVERY_MS),
            maxJobs = maxJobs.coerceIn(AppLimits.MIN_CRON_MAX_JOBS, AppLimits.MAX_CRON_MAX_JOBS)
        )
    }

    fun saveCronConfig(config: CronConfig) {
        prefs.edit()
            .putBoolean(KEY_CRON_ENABLED, config.enabled)
            .putLong(
                KEY_CRON_MIN_EVERY_MS,
                config.minEveryMs.coerceIn(AppLimits.MIN_CRON_MIN_EVERY_MS, AppLimits.MAX_CRON_MIN_EVERY_MS)
            )
            .putInt(KEY_CRON_MAX_JOBS, config.maxJobs.coerceIn(AppLimits.MIN_CRON_MAX_JOBS, AppLimits.MAX_CRON_MAX_JOBS))
            .apply()
    }

    fun getHeartbeatConfig(): HeartbeatConfig {
        return HeartbeatConfig(
            enabled = prefs.getBoolean(KEY_HEARTBEAT_ENABLED, false),
            intervalSeconds = prefs.getLong(
                KEY_HEARTBEAT_INTERVAL_SECONDS,
                AppLimits.DEFAULT_HEARTBEAT_INTERVAL_SECONDS
            ).coerceIn(AppLimits.MIN_HEARTBEAT_INTERVAL_SECONDS, AppLimits.MAX_HEARTBEAT_INTERVAL_SECONDS)
        )
    }

    fun saveHeartbeatConfig(config: HeartbeatConfig) {
        prefs.edit()
            .putBoolean(KEY_HEARTBEAT_ENABLED, config.enabled)
            .putLong(
                KEY_HEARTBEAT_INTERVAL_SECONDS,
                config.intervalSeconds.coerceIn(
                    AppLimits.MIN_HEARTBEAT_INTERVAL_SECONDS,
                    AppLimits.MAX_HEARTBEAT_INTERVAL_SECONDS
                )
            )
            .remove(KEY_HEARTBEAT_NOTIFY_SESSION_ID)
            .remove(KEY_HEARTBEAT_NOTIFY_CHANNEL)
            .remove(KEY_HEARTBEAT_NOTIFY_TO)
            .putLong(
                KEY_HEARTBEAT_NEXT_TRIGGER_AT_MS,
                if (config.enabled) getHeartbeatNextTriggerAtMs() else 0L
            )
            .apply()
    }

    fun getHeartbeatLastTriggeredAtMs(): Long {
        return prefs.getLong(KEY_HEARTBEAT_LAST_TRIGGERED_AT_MS, 0L).coerceAtLeast(0L)
    }

    fun saveHeartbeatLastTriggeredAtMs(timestampMs: Long) {
        prefs.edit()
            .putLong(KEY_HEARTBEAT_LAST_TRIGGERED_AT_MS, timestampMs.coerceAtLeast(0L))
            .apply()
    }

    fun getHeartbeatNextTriggerAtMs(): Long {
        return prefs.getLong(KEY_HEARTBEAT_NEXT_TRIGGER_AT_MS, 0L).coerceAtLeast(0L)
    }

    fun saveHeartbeatNextTriggerAtMs(timestampMs: Long) {
        prefs.edit()
            .putLong(KEY_HEARTBEAT_NEXT_TRIGGER_AT_MS, timestampMs.coerceAtLeast(0L))
            .apply()
    }

    fun getAlwaysOnConfig(): AlwaysOnConfig {
        return AlwaysOnConfig(
            enabled = prefs.getBoolean(KEY_ALWAYS_ON_ENABLED, false),
            keepScreenAwake = prefs.getBoolean(KEY_ALWAYS_ON_KEEP_SCREEN_AWAKE, false)
        )
    }

    fun saveAlwaysOnConfig(config: AlwaysOnConfig) {
        prefs.edit()
            .putBoolean(KEY_ALWAYS_ON_ENABLED, config.enabled)
            .putBoolean(KEY_ALWAYS_ON_KEEP_SCREEN_AWAKE, config.keepScreenAwake)
            .apply()
    }

    fun getMcpHttpConfig(): McpHttpConfig {
        val timeout = prefs.getInt(
            KEY_MCP_HTTP_TOOL_TIMEOUT_SECONDS,
            AppLimits.DEFAULT_MCP_HTTP_TOOL_TIMEOUT_SECONDS
        )
        val legacyServerName = prefs.getString(
            KEY_MCP_HTTP_SERVER_NAME,
            AppLimits.DEFAULT_MCP_HTTP_SERVER_NAME
        ).orEmpty()
        val legacyServerUrl = prefs.getString(KEY_MCP_HTTP_SERVER_URL, "").orEmpty()
        val legacyAuthToken = prefs.getString(KEY_MCP_HTTP_AUTH_TOKEN, "").orEmpty()
        val legacyTimeout = timeout.coerceIn(
            AppLimits.MIN_MCP_HTTP_TOOL_TIMEOUT_SECONDS,
            AppLimits.MAX_MCP_HTTP_TOOL_TIMEOUT_SECONDS
        )
        val decodedServers = prefs.getString(KEY_MCP_HTTP_SERVERS_JSON, null)
            ?.takeIf { it.isNotBlank() }
            ?.let { raw ->
                runCatching { json.decodeFromString<List<McpHttpServerConfig>>(raw) }
                    .getOrNull()
            }
            .orEmpty()
        val servers = decodedServers
            .mapIndexed { index, item ->
                item.copy(
                    id = item.id.ifBlank { "mcp_${index + 1}" },
                    serverName = item.serverName.trim().ifBlank { AppLimits.DEFAULT_MCP_HTTP_SERVER_NAME },
                    toolTimeoutSeconds = item.toolTimeoutSeconds.coerceIn(
                        AppLimits.MIN_MCP_HTTP_TOOL_TIMEOUT_SECONDS,
                        AppLimits.MAX_MCP_HTTP_TOOL_TIMEOUT_SECONDS
                    )
                )
            }
            .ifEmpty {
                if (legacyServerUrl.isNotBlank()) {
                    listOf(
                        McpHttpServerConfig(
                            id = "mcp_1",
                            serverName = legacyServerName.trim().ifBlank { AppLimits.DEFAULT_MCP_HTTP_SERVER_NAME },
                            serverUrl = legacyServerUrl,
                            authToken = legacyAuthToken,
                            toolTimeoutSeconds = legacyTimeout
                        )
                    )
                } else {
                    emptyList()
                }
            }
        val primary = servers.firstOrNull()
        return McpHttpConfig(
            enabled = prefs.getBoolean(KEY_MCP_HTTP_ENABLED, false),
            serverName = primary?.serverName ?: legacyServerName,
            serverUrl = primary?.serverUrl ?: legacyServerUrl,
            authToken = primary?.authToken ?: legacyAuthToken,
            toolTimeoutSeconds = primary?.toolTimeoutSeconds ?: legacyTimeout,
            servers = servers
        )
    }

    fun saveMcpHttpConfig(config: McpHttpConfig) {
        val servers = config.servers.mapIndexed { index, item ->
            item.copy(
                id = item.id.ifBlank { "mcp_${index + 1}" },
                serverName = item.serverName.trim().ifBlank { AppLimits.DEFAULT_MCP_HTTP_SERVER_NAME },
                toolTimeoutSeconds = item.toolTimeoutSeconds.coerceIn(
                    AppLimits.MIN_MCP_HTTP_TOOL_TIMEOUT_SECONDS,
                    AppLimits.MAX_MCP_HTTP_TOOL_TIMEOUT_SECONDS
                )
            )
        }
        val primary = servers.firstOrNull()
        prefs.edit()
            .putBoolean(KEY_MCP_HTTP_ENABLED, config.enabled)
            .putString(KEY_MCP_HTTP_SERVER_NAME, primary?.serverName ?: config.serverName)
            .putString(KEY_MCP_HTTP_SERVER_URL, primary?.serverUrl ?: config.serverUrl)
            .putString(KEY_MCP_HTTP_AUTH_TOKEN, primary?.authToken ?: config.authToken)
            .putInt(
                KEY_MCP_HTTP_TOOL_TIMEOUT_SECONDS,
                (primary?.toolTimeoutSeconds ?: config.toolTimeoutSeconds).coerceIn(
                    AppLimits.MIN_MCP_HTTP_TOOL_TIMEOUT_SECONDS,
                    AppLimits.MAX_MCP_HTTP_TOOL_TIMEOUT_SECONDS
                )
            )
            .putString(
                KEY_MCP_HTTP_SERVERS_JSON,
                json.encodeToString(servers)
            )
            .apply()
    }

    fun getLastActiveSessionId(): String? {
        return prefs.getString(KEY_LAST_ACTIVE_SESSION_ID, null)
            ?.trim()
            ?.ifBlank { null }
    }

    fun saveLastActiveSessionId(sessionId: String) {
        val sid = sessionId.trim()
        if (sid.isBlank()) return
        prefs.edit()
            .putString(KEY_LAST_ACTIVE_SESSION_ID, sid)
            .apply()
    }

    companion object {
        private const val KEY_PROVIDER = "provider_name"
        private const val KEY_API_KEY = "api_key"
        private const val KEY_MODEL = "model"
        private const val KEY_BASE_URL = "base_url"
        private const val KEY_PROVIDER_CONFIGS_JSON = "provider_configs_json"
        private const val KEY_ACTIVE_PROVIDER_CONFIG_ID = "active_provider_config_id"
        private const val KEY_MAX_TOOL_ROUNDS = "max_tool_rounds"
        private const val KEY_TOOL_RESULT_MAX_CHARS = "tool_result_max_chars"
        private const val KEY_MEMORY_CONSOLIDATION_WINDOW = "memory_consolidation_window"
        private const val KEY_LLM_CALL_TIMEOUT_SECONDS = "llm_call_timeout_seconds"
        private const val KEY_LLM_CONNECT_TIMEOUT_SECONDS = "llm_connect_timeout_seconds"
        private const val KEY_LLM_READ_TIMEOUT_SECONDS = "llm_read_timeout_seconds"
        private const val KEY_DEFAULT_TOOL_TIMEOUT_SECONDS = "default_tool_timeout_seconds"
        private const val KEY_CONTEXT_MESSAGES = "context_messages"
        private const val KEY_TOOL_ARGS_PREVIEW_MAX_CHARS = "tool_args_preview_max_chars"
        private const val KEY_TOKEN_INPUT_TOKENS = "token_input_tokens"
        private const val KEY_TOKEN_OUTPUT_TOKENS = "token_output_tokens"
        private const val KEY_TOKEN_TOTAL_TOKENS = "token_total_tokens"
        private const val KEY_TOKEN_CACHED_INPUT_TOKENS = "token_cached_input_tokens"
        private const val KEY_TOKEN_REQUESTS = "token_requests"
        private const val KEY_GATEWAY_ENABLED = "gateway_enabled"
        private const val KEY_TELEGRAM_ENABLED = "telegram_enabled"
        private const val KEY_DISCORD_ENABLED = "discord_enabled"
        private const val KEY_SLACK_ENABLED = "slack_enabled"
        private const val KEY_FEISHU_ENABLED = "feishu_enabled"
        private const val KEY_WECOM_ENABLED = "wecom_enabled"
        private const val KEY_CRON_ENABLED = "cron_enabled"
        private const val KEY_CRON_MIN_EVERY_MS = "cron_min_every_ms"
        private const val KEY_CRON_MAX_JOBS = "cron_max_jobs"
        private const val KEY_TELEGRAM_BOT_TOKEN = "telegram_bot_token"
        private const val KEY_TELEGRAM_ALLOWED_CHAT_ID = "telegram_allowed_chat_id"
        private const val KEY_DISCORD_WEBHOOK_URL = "discord_webhook_url"
        private const val KEY_EMAIL_ENABLED = "email_enabled"
        private const val KEY_HEARTBEAT_ENABLED = "heartbeat_enabled"
        private const val KEY_HEARTBEAT_INTERVAL_SECONDS = "heartbeat_interval_seconds"
        private const val KEY_HEARTBEAT_NOTIFY_SESSION_ID = "heartbeat_notify_session_id"
        private const val KEY_HEARTBEAT_NOTIFY_CHANNEL = "heartbeat_notify_channel"
        private const val KEY_HEARTBEAT_NOTIFY_TO = "heartbeat_notify_to"
        private const val KEY_HEARTBEAT_LAST_TRIGGERED_AT_MS = "heartbeat_last_triggered_at_ms"
        private const val KEY_HEARTBEAT_NEXT_TRIGGER_AT_MS = "heartbeat_next_trigger_at_ms"
        private const val KEY_ALWAYS_ON_ENABLED = "always_on_enabled"
        private const val KEY_ALWAYS_ON_KEEP_SCREEN_AWAKE = "always_on_keep_screen_awake"
        private const val KEY_MCP_HTTP_ENABLED = "mcp_http_enabled"
        private const val KEY_MCP_HTTP_SERVER_NAME = "mcp_http_server_name"
        private const val KEY_MCP_HTTP_SERVER_URL = "mcp_http_server_url"
        private const val KEY_MCP_HTTP_AUTH_TOKEN = "mcp_http_auth_token"
        private const val KEY_MCP_HTTP_TOOL_TIMEOUT_SECONDS = "mcp_http_tool_timeout_seconds"
        private const val KEY_MCP_HTTP_SERVERS_JSON = "mcp_http_servers_json"
        private const val KEY_SESSION_CHANNEL_BINDINGS_JSON = "session_channel_bindings_json"
        private const val KEY_LAST_ACTIVE_SESSION_ID = "last_active_session_id"
        private const val KEY_UI_USE_CHINESE = "ui_use_chinese"
        private const val KEY_UI_DARK_THEME = "ui_dark_theme"
        private const val KEY_ONBOARDING_COMPLETED = "onboarding_completed"
        private const val KEY_ONBOARDING_USER_DISPLAY_NAME = "onboarding_user_display_name"
        private const val KEY_ONBOARDING_AGENT_DISPLAY_NAME = "onboarding_agent_display_name"
        private const val KEY_FIRST_RUN_AUTO_INTRO_COMPLETED = "first_run_auto_intro_completed"

    }

    private fun normalizeChannelChatId(channel: String, chatId: String): String {
        val normalizedChannel = channel.trim().lowercase()
        if (normalizedChannel == "feishu") {
            return normalizeFeishuTargetId(chatId)
        }
        if (normalizedChannel == "email") {
            return normalizeEmailAddress(chatId)
        }
        if (normalizedChannel == "wecom") {
            return normalizeWeComTargetId(chatId)
        }
        if (normalizedChannel == "slack") {
            return normalizeSlackChatId(chatId)
        }
        if (normalizedChannel != "discord") return chatId.trim()
        val trimmed = chatId.trim()
        if (trimmed.isBlank()) return ""
        val mentionMatch = Regex("^<#(\\d+)>$").matchEntire(trimmed)
        if (mentionMatch != null) {
            return mentionMatch.groupValues.getOrNull(1).orEmpty()
        }
        val digits = trimmed.filter { it.isDigit() }
        return if (digits.length in 15..30) digits else trimmed
    }

    private fun normalizeSessionChannel(channel: String): String {
        return when (channel.trim().lowercase(Locale.US)) {
            "telegram" -> "telegram"
            "discord" -> "discord"
            "slack" -> "slack"
            "feishu" -> "feishu"
            "email" -> "email"
            "wecom" -> "wecom"
            else -> ""
        }
    }

    private fun normalizeDiscordResponseMode(value: String): String {
        return when (value.trim().lowercase(Locale.US)) {
            "open" -> "open"
            else -> "mention"
        }
    }

    private fun normalizeDiscordAllowedUserIds(ids: List<String>): List<String> {
        return ids
            .asSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .toList()
    }

    private fun normalizeSlackChatId(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isBlank()) return ""
        val mention = Regex("^<#([A-Za-z0-9]+)(?:\\|[^>]+)?>$").matchEntire(trimmed)
        if (mention != null) {
            return mention.groupValues.getOrNull(1).orEmpty().uppercase(Locale.US)
        }
        val detected = Regex("([CDG][A-Za-z0-9]{8,})").find(trimmed)
            ?.groupValues
            ?.getOrNull(1)
        return (detected ?: trimmed).trim().uppercase(Locale.US)
    }

    private fun normalizeSlackResponseMode(value: String): String {
        return when (value.trim().lowercase(Locale.US)) {
            "open" -> "open"
            else -> "mention"
        }
    }

    private fun normalizeSlackAllowedUserIds(ids: List<String>): List<String> {
        return ids
            .asSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .toList()
    }

    private fun normalizeFeishuTargetId(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isBlank()) return ""
        val detected = Regex("((?:ou|oc)_[A-Za-z0-9_-]+)").find(trimmed)
            ?.groupValues
            ?.getOrNull(1)
        return (detected ?: trimmed).trim()
    }

    private fun normalizeFeishuAllowedOpenIds(ids: List<String>): List<String> {
        return ids
            .asSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .toList()
    }

    private fun normalizeEmailAddress(raw: String): String {
        return raw.trim().lowercase(Locale.US)
    }

    private fun normalizeWeComAllowedUserIds(ids: List<String>): List<String> {
        return ids
            .asSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .toList()
    }

    private fun normalizeWeComTargetId(raw: String): String {
        return raw.trim()
    }

}
