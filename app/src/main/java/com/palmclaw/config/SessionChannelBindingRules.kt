package com.palmclaw.config

import java.util.Locale

/**
 * Centralizes normalization and lightweight validation rules for persisted
 * session-to-channel bindings so the UI and storage layer stay consistent.
 */
object SessionChannelBindingRules {
    private val discordChannelMentionRegex = Regex("^<#(\\d+)>$")
    private val slackChannelMentionRegex = Regex("^<#([A-Za-z0-9]+)(?:\\|[^>]+)?>$")
    private val slackChannelIdRegex = Regex("([CDG][A-Za-z0-9]{8,})")
    private val feishuTargetIdRegex = Regex("((?:ou|oc)_[A-Za-z0-9_-]+)")
    private val supportedChannels = setOf("telegram", "discord", "slack", "feishu", "email", "wecom")

    fun normalizeChannel(channel: String): String {
        val normalized = channel.trim().lowercase(Locale.US)
        return normalized.takeIf { it in supportedChannels }.orEmpty()
    }

    fun normalizeChatId(channel: String, chatId: String): String {
        return when (normalizeChannel(channel)) {
            "discord" -> normalizeDiscordChannelId(chatId)
            "slack" -> normalizeSlackChannelId(chatId)
            "feishu" -> normalizeFeishuTargetId(chatId)
            "email" -> normalizeEmailAddress(chatId)
            "wecom" -> normalizeWeComTargetId(chatId)
            else -> chatId.trim()
        }
    }

    fun normalizeDiscordChannelId(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isBlank()) return ""
        val mentionMatch = discordChannelMentionRegex.matchEntire(trimmed)
        if (mentionMatch != null) {
            return mentionMatch.groupValues.getOrNull(1).orEmpty()
        }
        val digits = trimmed.filter { it.isDigit() }
        return if (digits.length in 15..30) digits else trimmed
    }

    fun normalizeSlackChannelId(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isBlank()) return ""
        val mentionMatch = slackChannelMentionRegex.matchEntire(trimmed)
        if (mentionMatch != null) {
            return mentionMatch.groupValues.getOrNull(1).orEmpty().uppercase(Locale.US)
        }
        val detected = slackChannelIdRegex.find(trimmed)
            ?.groupValues
            ?.getOrNull(1)
        return (detected ?: trimmed).trim().uppercase(Locale.US)
    }

    fun normalizeFeishuTargetId(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isBlank()) return ""
        val detected = feishuTargetIdRegex.find(trimmed)
            ?.groupValues
            ?.getOrNull(1)
        return (detected ?: trimmed).trim()
    }

    fun normalizeWeComTargetId(raw: String): String = raw.trim()

    fun normalizeEmailAddress(raw: String): String = raw.trim().lowercase(Locale.US)

    fun normalizeDiscordResponseMode(value: String): String = normalizeMentionResponseMode(value)

    fun normalizeSlackResponseMode(value: String): String = normalizeMentionResponseMode(value)

    fun normalizeFeishuResponseMode(value: String): String = normalizeMentionResponseMode(value)

    fun normalizeAllowedIdentifiers(ids: List<String>): List<String> {
        return ids
            .asSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .toList()
    }

    fun parseAllowedIdentifiers(raw: String): List<String> {
        return normalizeAllowedIdentifiers(raw.split(',', '\n', '\r', '\t', ' '))
    }

    fun isDiscordSnowflake(value: String): Boolean {
        return value.length in 15..30 && value.all { it.isDigit() }
    }

    fun isSlackChannelId(value: String): Boolean {
        val normalized = value.trim().uppercase(Locale.US)
        if (normalized.length !in 9..30) return false
        if (!(normalized.startsWith("C") || normalized.startsWith("D") || normalized.startsWith("G"))) {
            return false
        }
        return normalized.all { it.isLetterOrDigit() }
    }

    fun isFeishuTargetId(value: String): Boolean {
        val normalized = value.trim()
        return normalized.startsWith("ou_") || normalized.startsWith("oc_")
    }

    fun normalize(binding: SessionChannelBinding): SessionChannelBinding? {
        val sessionId = binding.sessionId.trim()
        if (sessionId.isBlank()) return null
        val channel = normalizeChannel(binding.channel)
        if (channel.isBlank()) return null
        return binding.copy(
            sessionId = sessionId,
            enabled = binding.enabled,
            channel = channel,
            chatId = normalizeChatId(channel, binding.chatId),
            telegramBotToken = binding.telegramBotToken.trim(),
            telegramAllowedChatId = binding.telegramAllowedChatId?.trim()?.ifBlank { null },
            discordBotToken = binding.discordBotToken.trim(),
            discordResponseMode = normalizeDiscordResponseMode(binding.discordResponseMode),
            discordAllowedUserIds = normalizeAllowedIdentifiers(binding.discordAllowedUserIds),
            slackBotToken = binding.slackBotToken.trim(),
            slackAppToken = binding.slackAppToken.trim(),
            slackResponseMode = normalizeSlackResponseMode(binding.slackResponseMode),
            slackAllowedUserIds = normalizeAllowedIdentifiers(binding.slackAllowedUserIds),
            feishuAppId = binding.feishuAppId.trim(),
            feishuAppSecret = binding.feishuAppSecret.trim(),
            feishuEncryptKey = binding.feishuEncryptKey.trim(),
            feishuVerificationToken = binding.feishuVerificationToken.trim(),
            feishuResponseMode = normalizeFeishuResponseMode(binding.feishuResponseMode),
            feishuAllowedOpenIds = normalizeAllowedIdentifiers(binding.feishuAllowedOpenIds),
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
            wecomAllowedUserIds = normalizeAllowedIdentifiers(binding.wecomAllowedUserIds)
        )
    }

    fun normalize(bindings: List<SessionChannelBinding>): List<SessionChannelBinding> {
        return bindings.mapNotNull(::normalize).distinctBy { it.sessionId }
    }

    private fun normalizeMentionResponseMode(value: String): String {
        return when (value.trim().lowercase(Locale.US)) {
            "open" -> "open"
            else -> "mention"
        }
    }
}
