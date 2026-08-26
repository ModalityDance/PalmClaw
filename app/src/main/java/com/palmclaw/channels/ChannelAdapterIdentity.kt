package com.palmclaw.channels

import com.palmclaw.config.SessionChannelBinding
import java.security.MessageDigest
import java.util.Locale

object ChannelAdapterIdentity {
    fun key(channel: String, seed: String): String {
        val normalizedChannel = channel.trim().lowercase(Locale.US)
        val normalizedSeed = seed.trim()
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(normalizedSeed.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }
            .take(16)
        return "$normalizedChannel:$digest"
    }

    fun keysForBinding(binding: SessionChannelBinding): List<String> {
        val channel = binding.channel.trim().lowercase(Locale.US)
        return seedsForBinding(binding, channel).map { seed -> key(channel, seed) }
    }

    fun primaryKeyForBinding(binding: SessionChannelBinding): String? =
        keysForBinding(binding).firstOrNull()

    private fun seedsForBinding(binding: SessionChannelBinding, channel: String): List<String> =
        when (channel) {
            "telegram" -> binding.telegramBotToken.trim()
                .takeIf { it.isNotBlank() }
                ?.let { listOf(it) }
                .orEmpty()
            "discord" -> binding.discordBotToken.trim()
                .takeIf { it.isNotBlank() }
                ?.let { listOf(it) }
                .orEmpty()
            "slack" -> pairedSeed(binding.slackBotToken, binding.slackAppToken)
            "feishu" -> buildFeishuAdapterSeeds(
                appId = binding.feishuAppId,
                appSecret = binding.feishuAppSecret,
                encryptKey = binding.feishuEncryptKey,
                verificationToken = binding.feishuVerificationToken
            )
            "email" -> emailSeeds(binding)
            "wecom" -> pairedSeed(binding.wecomBotId, binding.wecomSecret)
            else -> emptyList()
        }

    private fun pairedSeed(first: String, second: String): List<String> {
        val normalizedFirst = first.trim()
        val normalizedSecond = second.trim()
        return if (normalizedFirst.isBlank() || normalizedSecond.isBlank()) {
            emptyList()
        } else {
            listOf("$normalizedFirst|$normalizedSecond")
        }
    }

    private fun emailSeeds(binding: SessionChannelBinding): List<String> {
        val imapHost = binding.emailImapHost.trim()
        val imapUsername = binding.emailImapUsername.trim()
        val smtpHost = binding.emailSmtpHost.trim()
        val smtpUsername = binding.emailSmtpUsername.trim()
        if (
            imapHost.isBlank() ||
            imapUsername.isBlank() ||
            binding.emailImapPassword.isBlank() ||
            smtpHost.isBlank() ||
            smtpUsername.isBlank() ||
            binding.emailSmtpPassword.isBlank()
        ) {
            return emptyList()
        }
        return listOf(
            "$imapHost|${binding.emailImapPort}|$imapUsername|$smtpHost|${binding.emailSmtpPort}|$smtpUsername|${binding.emailFromAddress.trim()}"
        )
    }
}

internal data class FeishuAdapterIdentityGroup(
    val adapterKey: String,
    val configuration: SessionChannelBinding,
    val bindings: List<SessionChannelBinding>,
    val hasConfigurationConflict: Boolean
)

internal fun groupFeishuBindingsByAdapterIdentity(
    bindings: List<SessionChannelBinding>
): List<FeishuAdapterIdentityGroup> =
    bindings
        .groupBy { binding -> checkNotNull(ChannelAdapterIdentity.primaryKeyForBinding(binding)) }
        .toSortedMap()
        .map { (adapterKey, grouped) ->
            val configurations = grouped.map { binding ->
                binding.feishuEncryptKey.trim() to binding.feishuVerificationToken.trim()
            }.distinct()
            val selected = grouped.sortedWith(
                compareByDescending<SessionChannelBinding> { binding ->
                    listOf(binding.feishuEncryptKey, binding.feishuVerificationToken)
                        .count { it.isNotBlank() }
                }
                    .thenBy { it.feishuEncryptKey.trim() }
                    .thenBy { it.feishuVerificationToken.trim() }
                    .thenBy { it.sessionId.trim() }
                    .thenBy { it.chatId.trim() }
            ).first()
            FeishuAdapterIdentityGroup(
                adapterKey = adapterKey,
                configuration = selected,
                bindings = grouped,
                hasConfigurationConflict = configurations.size > 1
            )
        }
