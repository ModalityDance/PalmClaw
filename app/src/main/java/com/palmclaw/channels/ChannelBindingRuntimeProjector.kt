package com.palmclaw.channels

import android.util.Patterns
import com.palmclaw.config.SessionChannelBinding
import com.palmclaw.config.SessionChannelBindingRules
import java.util.Locale

fun interface EmailAddressValidator {
    fun isValid(value: String): Boolean
}

object AndroidEmailAddressValidator : EmailAddressValidator {
    override fun isValid(value: String): Boolean {
        val normalized = value.trim()
        return normalized.isNotBlank() && Patterns.EMAIL_ADDRESS.matcher(normalized).matches()
    }
}

data class ChannelBindingRuntimeProjection(
    val channel: String,
    val target: String,
    val adapterKeys: List<String>,
    val status: String
)

class ChannelBindingRuntimeProjector(
    private val emailAddressValidator: EmailAddressValidator
) {
    fun project(
        binding: SessionChannelBinding?,
        gatewayEnabled: Boolean,
        snapshotSource: ChannelRuntimeSnapshotSource
    ): ChannelBindingRuntimeProjection {
        val channel = binding?.channel?.trim()?.lowercase(Locale.US).orEmpty()
        val target = normalizedTarget(binding)
        val adapterKeys = binding?.let(ChannelAdapterIdentity::keysForBinding).orEmpty()
        val status = resolveStatus(binding, channel, target, adapterKeys, gatewayEnabled, snapshotSource)
        return ChannelBindingRuntimeProjection(channel, target, adapterKeys, status)
    }

    fun canStartAdapter(binding: SessionChannelBinding?): Boolean {
        if (binding == null || !binding.enabled) return false
        val channel = binding.channel.trim().lowercase(Locale.US)
        val target = normalizedTarget(binding)
        return when (channel) {
            "telegram" -> binding.telegramBotToken.trim().isNotBlank() && target.isNotBlank()
            "discord" -> binding.discordBotToken.trim().isNotBlank() &&
                SessionChannelBindingRules.isDiscordSnowflake(target)
            "slack" -> binding.slackBotToken.trim().isNotBlank() &&
                binding.slackAppToken.trim().isNotBlank() &&
                SessionChannelBindingRules.isSlackChannelId(target)
            "feishu" -> binding.feishuAppId.trim().isNotBlank() &&
                binding.feishuAppSecret.trim().isNotBlank()
            "email" -> binding.emailConsentGranted && hasEmailCredentials(binding)
            "wecom" -> binding.wecomBotId.trim().isNotBlank() && binding.wecomSecret.trim().isNotBlank()
            else -> false
        }
    }

    private fun resolveStatus(
        binding: SessionChannelBinding?,
        channel: String,
        target: String,
        adapterKeys: List<String>,
        gatewayEnabled: Boolean,
        snapshotSource: ChannelRuntimeSnapshotSource
    ): String {
        if (binding == null || channel.isBlank()) return "Unbound"
        if (!binding.enabled) return "Disabled"
        when (channel) {
            "telegram" -> {
                if (binding.telegramBotToken.trim().isBlank()) return "Missing token"
                if (target.isBlank()) return "Waiting for chat detection"
            }
            "discord" -> {
                if (binding.discordBotToken.trim().isBlank()) return "Missing token"
                if (!SessionChannelBindingRules.isDiscordSnowflake(target)) return "Missing channel id"
            }
            "slack" -> {
                if (binding.slackBotToken.trim().isBlank() || binding.slackAppToken.trim().isBlank()) {
                    return "Missing bot/app token"
                }
                if (!SessionChannelBindingRules.isSlackChannelId(target)) return "Missing channel id"
            }
            "feishu" -> {
                if (binding.feishuAppId.trim().isBlank() || binding.feishuAppSecret.trim().isBlank()) {
                    return "Missing app credentials"
                }
                if (target.isBlank()) return "Waiting for chat detection"
                if (!SessionChannelBindingRules.isFeishuTargetId(target)) return "Invalid target"
            }
            "email" -> {
                if (!binding.emailConsentGranted) return "Consent required"
                if (!hasEmailCredentials(binding)) return "Missing mailbox credentials"
                if (target.isBlank()) return "Waiting for sender detection"
                if (!emailAddressValidator.isValid(target)) return "Invalid sender"
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
        val snapshot = adapterKeys
            .asSequence()
            .map { adapterKey -> snapshotSource.getSnapshot(channel, adapterKey) }
            .firstOrNull { it.isActiveOrFailed() }
            ?: ChannelAdapterIdentity.primaryKeyForBinding(binding)
                ?.let { adapterKey -> snapshotSource.getSnapshot(channel, adapterKey) }
            ?: return "Configured"
        return when {
            snapshot.lastError.isNotBlank() && !snapshot.ready -> "Error"
            snapshot.ready -> "Connected"
            snapshot.connected -> "Connecting"
            snapshot.running -> "Starting"
            else -> "Configured"
        }
    }

    private fun normalizedTarget(binding: SessionChannelBinding?): String {
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

    private fun hasEmailCredentials(binding: SessionChannelBinding): Boolean =
        binding.emailImapHost.trim().isNotBlank() &&
            binding.emailImapUsername.trim().isNotBlank() &&
            binding.emailImapPassword.isNotBlank() &&
            binding.emailSmtpHost.trim().isNotBlank() &&
            binding.emailSmtpUsername.trim().isNotBlank() &&
            binding.emailSmtpPassword.isNotBlank()

    private fun ChannelRuntimeSnapshot.isActiveOrFailed(): Boolean =
        running || connected || ready || lastError.isNotBlank()
}
