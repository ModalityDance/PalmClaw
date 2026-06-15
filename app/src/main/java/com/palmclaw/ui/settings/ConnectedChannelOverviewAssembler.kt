package com.palmclaw.ui

import android.util.Patterns
import com.palmclaw.channels.ChannelRuntimeDiagnostics
import com.palmclaw.config.SessionChannelBinding
import com.palmclaw.config.SessionChannelBindingRules
import java.util.Locale

internal object ConnectedChannelOverviewAssembler {
    fun build(
        sessions: List<UiSessionSummary>,
        gatewayEnabled: Boolean,
        bindings: List<SessionChannelBinding>,
        adapterKeysForBinding: (SessionChannelBinding) -> List<String>,
        adapterKeyForBinding: (SessionChannelBinding) -> String?
    ): List<UiConnectedChannelSummary> {
        val bindingsBySession = bindings.associateBy { it.sessionId.trim() }
        return sessions
            .asSequence()
            .filterNot { it.isLocal }
            .mapNotNull { session ->
                val binding = bindingsBySession[session.id] ?: return@mapNotNull null
                val channel = binding.channel.trim().lowercase(Locale.US)
                if (channel !in SUPPORTED_CHANNELS) return@mapNotNull null
                UiConnectedChannelSummary(
                    sessionId = session.id,
                    sessionTitle = session.title,
                    channel = channel,
                    chatId = normalizedBindingTarget(binding),
                    enabled = binding.enabled,
                    status = resolveRuntimeStatus(
                        binding = binding,
                        gatewayEnabled = gatewayEnabled,
                        adapterKeysForBinding = adapterKeysForBinding,
                        adapterKeyForBinding = adapterKeyForBinding
                    )
                )
            }
            .sortedWith(
                compareBy<UiConnectedChannelSummary>(
                    { it.channel },
                    { it.sessionTitle.lowercase(Locale.US) }
                )
            )
            .toList()
    }

    private fun resolveRuntimeStatus(
        binding: SessionChannelBinding?,
        gatewayEnabled: Boolean,
        adapterKeysForBinding: (SessionChannelBinding) -> List<String>,
        adapterKeyForBinding: (SessionChannelBinding) -> String?
    ): String {
        if (binding == null) return "Unbound"
        val channel = binding.channel.trim().lowercase(Locale.US)
        if (channel.isBlank()) return "Unbound"
        if (!binding.enabled) return "Disabled"
        val target = normalizedBindingTarget(binding)
        when (channel) {
            "telegram" -> {
                if (binding.telegramBotToken.trim().isBlank()) return "Missing token"
                if (target.isBlank()) return "Waiting for chat detection"
            }
            "discord" -> {
                if (binding.discordBotToken.trim().isBlank()) return "Missing token"
                if (!SessionChannelBindingRules.isDiscordSnowflake(
                        SessionChannelBindingRules.normalizeDiscordChannelId(target)
                    )
                ) return "Missing channel id"
            }
            "slack" -> {
                if (binding.slackBotToken.trim().isBlank() || binding.slackAppToken.trim().isBlank()) {
                    return "Missing bot/app token"
                }
                if (!SessionChannelBindingRules.isSlackChannelId(
                        SessionChannelBindingRules.normalizeSlackChannelId(target)
                    )
                ) return "Missing channel id"
            }
            "feishu" -> {
                if (binding.feishuAppId.trim().isBlank() || binding.feishuAppSecret.trim().isBlank()) {
                    return "Missing app credentials"
                }
                if (target.isBlank()) return "Waiting for chat detection"
                if (!SessionChannelBindingRules.isFeishuTargetId(
                        SessionChannelBindingRules.normalizeFeishuTargetId(target)
                    )
                ) return "Invalid target"
            }
            "email" -> {
                if (!binding.emailConsentGranted) return "Consent required"
                if (
                    binding.emailImapHost.trim().isBlank() ||
                    binding.emailImapUsername.trim().isBlank() ||
                    binding.emailImapPassword.isBlank() ||
                    binding.emailSmtpHost.trim().isBlank() ||
                    binding.emailSmtpUsername.trim().isBlank() ||
                    binding.emailSmtpPassword.isBlank()
                ) return "Missing mailbox credentials"
                if (target.isBlank()) return "Waiting for sender detection"
                if (!isEmailAddress(SessionChannelBindingRules.normalizeEmailAddress(target))) {
                    return "Invalid sender"
                }
            }
            "wecom" -> {
                if (binding.wecomBotId.trim().isBlank() || binding.wecomSecret.trim().isBlank()) {
                    return "Missing bot credentials"
                }
                if (target.isBlank()) return "Waiting for chat detection"
            }
            else -> return "Configured"
        }
        if (!gatewayEnabled) return "Gateway idle"
        val snapshot = adapterKeysForBinding(binding)
            .asSequence()
            .map { ChannelRuntimeDiagnostics.getSnapshot(channel, it) }
            .firstOrNull {
                it.running ||
                    it.connected ||
                    it.ready ||
                    it.lastError.isNotBlank()
            }
            ?: adapterKeyForBinding(binding)?.let { ChannelRuntimeDiagnostics.getSnapshot(channel, it) }
            ?: return "Configured"
        return when {
            snapshot.lastError.isNotBlank() && !snapshot.ready -> "Error"
            snapshot.ready -> "Connected"
            snapshot.connected -> "Connecting"
            snapshot.running -> "Starting"
            else -> "Configured"
        }
    }

    private fun normalizedBindingTarget(binding: SessionChannelBinding?): String {
        if (binding == null) return ""
        return when (binding.channel.trim().lowercase(Locale.US)) {
            "discord" -> SessionChannelBindingRules.normalizeDiscordChannelId(binding.chatId)
            "slack" -> SessionChannelBindingRules.normalizeSlackChannelId(binding.chatId)
            "feishu" -> SessionChannelBindingRules.normalizeFeishuTargetId(binding.chatId)
            "email" -> SessionChannelBindingRules.normalizeEmailAddress(binding.chatId)
            "wecom" -> SessionChannelBindingRules.normalizeWeComTargetId(binding.chatId)
            else -> binding.chatId.trim()
        }
    }

    private fun isEmailAddress(value: String): Boolean {
        val normalized = value.trim()
        return normalized.isNotBlank() && Patterns.EMAIL_ADDRESS.matcher(normalized).matches()
    }

    private val SUPPORTED_CHANNELS = setOf("telegram", "discord", "slack", "feishu", "email", "wecom")
}
