package com.palmclaw.channels

import android.app.Application
import android.util.Log
import com.palmclaw.config.SessionChannelBinding
import com.palmclaw.config.SessionChannelBindingRules

internal fun interface ChannelAdapterFactory {
    fun create(bindings: List<SessionChannelBinding>): List<ChannelAdapter>
}

internal class ConfiguredChannelAdapterFactory(
    private val app: Application,
    private val reportWarning: (String) -> Unit = { message ->
        Log.w("ConfiguredAdapterFactory", message)
    }
) : ChannelAdapterFactory {
    override fun create(bindings: List<SessionChannelBinding>): List<ChannelAdapter> {
        val activeBindings = bindings.filter { it.enabled }
        val telegramBindings = activeBindings
            .filter { it.channel.trim().equals("telegram", ignoreCase = true) }
            .mapNotNull { binding ->
                val token = binding.telegramBotToken.trim()
                val chatId = binding.chatId.trim()
                if (token.isBlank() || chatId.isBlank()) null else binding.copy(
                    channel = "telegram",
                    chatId = chatId,
                    telegramBotToken = token,
                    telegramAllowedChatId = binding.telegramAllowedChatId?.trim()?.ifBlank { null }
                )
            }
        val discordBindings = activeBindings
            .filter { it.channel.trim().equals("discord", ignoreCase = true) }
            .mapNotNull { binding ->
                val token = binding.discordBotToken.trim()
                val chatId = binding.chatId.trim()
                if (
                    token.isBlank() ||
                    chatId.isBlank() ||
                    !SessionChannelBindingRules.isDiscordSnowflake(chatId)
                ) {
                    null
                } else {
                    binding.copy(
                        channel = "discord",
                        chatId = chatId,
                        discordBotToken = token,
                        discordResponseMode = SessionChannelBindingRules.normalizeDiscordResponseMode(
                            binding.discordResponseMode
                        ),
                        discordAllowedUserIds = binding.discordAllowedUserIds
                            .map { it.trim() }
                            .filter { it.isNotBlank() }
                    )
                }
            }
        val slackBindings = activeBindings
            .filter { it.channel.trim().equals("slack", ignoreCase = true) }
            .mapNotNull { binding ->
                val botToken = binding.slackBotToken.trim()
                val appToken = binding.slackAppToken.trim()
                val chatId = SessionChannelBindingRules.normalizeSlackChannelId(binding.chatId)
                if (
                    botToken.isBlank() ||
                    appToken.isBlank() ||
                    chatId.isBlank() ||
                    !SessionChannelBindingRules.isSlackChannelId(chatId)
                ) {
                    null
                } else {
                    binding.copy(
                        channel = "slack",
                        chatId = chatId,
                        slackBotToken = botToken,
                        slackAppToken = appToken,
                        slackResponseMode = SessionChannelBindingRules.normalizeSlackResponseMode(
                            binding.slackResponseMode
                        ),
                        slackAllowedUserIds = binding.slackAllowedUserIds
                            .map { it.trim() }
                            .filter { it.isNotBlank() }
                    )
                }
            }
        val feishuBindings = activeBindings
            .filter { it.channel.trim().equals("feishu", ignoreCase = true) }
            .mapNotNull { binding ->
                val appId = binding.feishuAppId.trim()
                val appSecret = binding.feishuAppSecret.trim()
                val chatId = normalizeFeishuTargetId(binding.chatId)
                if (appId.isBlank() || appSecret.isBlank()) null else binding.copy(
                    channel = "feishu",
                    chatId = chatId,
                    feishuAppId = appId,
                    feishuAppSecret = appSecret,
                    feishuEncryptKey = binding.feishuEncryptKey.trim(),
                    feishuVerificationToken = binding.feishuVerificationToken.trim(),
                    feishuAllowedOpenIds = binding.feishuAllowedOpenIds
                        .map { it.trim() }
                        .filter { it.isNotBlank() }
                )
            }
        val emailBindings = activeBindings
            .filter { it.channel.trim().equals("email", ignoreCase = true) }
            .mapNotNull { binding ->
                val imapHost = binding.emailImapHost.trim()
                val imapUsername = binding.emailImapUsername.trim()
                val imapPassword = binding.emailImapPassword
                val smtpHost = binding.emailSmtpHost.trim()
                val smtpUsername = binding.emailSmtpUsername.trim()
                val smtpPassword = binding.emailSmtpPassword
                if (
                    !binding.emailConsentGranted ||
                    imapHost.isBlank() ||
                    imapUsername.isBlank() ||
                    imapPassword.isBlank() ||
                    smtpHost.isBlank() ||
                    smtpUsername.isBlank() ||
                    smtpPassword.isBlank()
                ) {
                    null
                } else {
                    binding.copy(
                        channel = "email",
                        chatId = SessionChannelBindingRules.normalizeEmailAddress(binding.chatId),
                        emailImapHost = imapHost,
                        emailImapPort = binding.emailImapPort.coerceIn(1, 65535),
                        emailImapUsername = imapUsername,
                        emailImapPassword = imapPassword,
                        emailSmtpHost = smtpHost,
                        emailSmtpPort = binding.emailSmtpPort.coerceIn(1, 65535),
                        emailSmtpUsername = smtpUsername,
                        emailSmtpPassword = smtpPassword,
                        emailFromAddress = SessionChannelBindingRules.normalizeEmailAddress(
                            binding.emailFromAddress
                        )
                    )
                }
            }
        val weComBindings = activeBindings
            .filter { it.channel.trim().equals("wecom", ignoreCase = true) }
            .mapNotNull { binding ->
                val botId = binding.wecomBotId.trim()
                val secret = binding.wecomSecret.trim()
                val chatId = SessionChannelBindingRules.normalizeWeComTargetId(binding.chatId)
                if (botId.isBlank() || secret.isBlank()) null else binding.copy(
                    channel = "wecom",
                    chatId = chatId,
                    wecomBotId = botId,
                    wecomSecret = secret,
                    wecomAllowedUserIds = binding.wecomAllowedUserIds
                        .map { it.trim() }
                        .filter { it.isNotBlank() }
                )
            }

        return buildList {
            telegramBindings.groupBy { it.telegramBotToken }.forEach { (token, grouped) ->
                val allowed = buildSet {
                    grouped.map { it.chatId.trim() }
                        .filter { it.isNotBlank() }
                        .forEach { add(it) }
                    grouped.mapNotNull { it.telegramAllowedChatId?.trim()?.ifBlank { null } }
                        .forEach { add(it) }
                }
                val adapterKey = checkNotNull(
                    ChannelAdapterIdentity.primaryKeyForBinding(grouped.first())
                )
                add(
                    TelegramChannelAdapter(
                        adapterKey = adapterKey,
                        botToken = token,
                        allowedChatIds = allowed
                    )
                )
            }
            discordBindings.groupBy { it.discordBotToken }.forEach { (token, grouped) ->
                val allowedChannels = grouped.map { it.chatId }.distinct().toSet()
                val routeRules = grouped.associate { binding ->
                    binding.chatId to DiscordRouteRule(
                        responseMode = SessionChannelBindingRules.normalizeDiscordResponseMode(
                            binding.discordResponseMode
                        ),
                        allowedUserIds = binding.discordAllowedUserIds
                            .asSequence()
                            .map { it.trim() }
                            .filter { it.isNotBlank() }
                            .toSet()
                    )
                }
                val adapterKey = checkNotNull(
                    ChannelAdapterIdentity.primaryKeyForBinding(grouped.first())
                )
                add(
                    DiscordChannelAdapter(
                        adapterKey = adapterKey,
                        botToken = token,
                        allowedChannelIds = allowedChannels,
                        routeRules = routeRules
                    )
                )
            }
            slackBindings.groupBy { it.slackBotToken to it.slackAppToken }
                .forEach { (pair, grouped) ->
                    val (botToken, appToken) = pair
                    val allowedChannels = grouped.map { it.chatId }.distinct().toSet()
                    val routeRules = grouped.associate { binding ->
                        binding.chatId to SlackRouteRule(
                            responseMode = SessionChannelBindingRules.normalizeSlackResponseMode(
                                binding.slackResponseMode
                            ),
                            allowedUserIds = binding.slackAllowedUserIds
                                .asSequence()
                                .map { it.trim() }
                                .filter { it.isNotBlank() }
                                .toSet()
                        )
                    }
                    val adapterKey = checkNotNull(
                        ChannelAdapterIdentity.primaryKeyForBinding(grouped.first())
                    )
                    add(
                        SlackChannelAdapter(
                            adapterKey = adapterKey,
                            botToken = botToken,
                            appToken = appToken,
                            allowedChannelIds = allowedChannels,
                            routeRules = routeRules
                        )
                    )
                }
            groupFeishuBindingsByAdapterIdentity(feishuBindings).forEach { group ->
                val grouped = group.bindings
                val configuration = group.configuration
                val allowedTargets = grouped.map { it.chatId }
                    .filter { it.isNotBlank() }
                    .distinct()
                    .toSet()
                val routeRules = grouped.filter { it.chatId.isNotBlank() }.associate { binding ->
                    binding.chatId to FeishuRouteRule(
                        responseMode = SessionChannelBindingRules.normalizeFeishuResponseMode(
                            binding.feishuResponseMode
                        ),
                        allowedOpenIds = binding.feishuAllowedOpenIds
                            .asSequence()
                            .map { it.trim() }
                            .filter { it.isNotBlank() }
                            .toSet()
                    )
                }
                if (group.hasConfigurationConflict) {
                    reportWarning(
                        "Feishu adapter ${group.adapterKey} has conflicting optional credentials; " +
                            "using the most complete stable configuration"
                    )
                }
                add(
                    FeishuChannelAdapter(
                        adapterKey = group.adapterKey,
                        appId = configuration.feishuAppId,
                        appSecret = configuration.feishuAppSecret,
                        encryptKey = configuration.feishuEncryptKey,
                        verificationToken = configuration.feishuVerificationToken,
                        allowedChatTargets = allowedTargets,
                        routeRules = routeRules
                    )
                )
            }
            emailBindings.groupBy {
                EmailCredentialKey(
                    consentGranted = it.emailConsentGranted,
                    imapHost = it.emailImapHost,
                    imapPort = it.emailImapPort,
                    imapUsername = it.emailImapUsername,
                    imapPassword = it.emailImapPassword,
                    smtpHost = it.emailSmtpHost,
                    smtpPort = it.emailSmtpPort,
                    smtpUsername = it.emailSmtpUsername,
                    smtpPassword = it.emailSmtpPassword,
                    fromAddress = it.emailFromAddress,
                    autoReplyEnabled = it.emailAutoReplyEnabled
                )
            }.forEach { (credentials, grouped) ->
                val adapterKey = checkNotNull(
                    ChannelAdapterIdentity.primaryKeyForBinding(grouped.first())
                )
                add(
                    EmailChannelAdapter(
                        context = app,
                        adapterKey = adapterKey,
                        config = EmailAccountConfig(
                            consentGranted = credentials.consentGranted,
                            imapHost = credentials.imapHost,
                            imapPort = credentials.imapPort,
                            imapUsername = credentials.imapUsername,
                            imapPassword = credentials.imapPassword,
                            smtpHost = credentials.smtpHost,
                            smtpPort = credentials.smtpPort,
                            smtpUsername = credentials.smtpUsername,
                            smtpPassword = credentials.smtpPassword,
                            fromAddress = credentials.fromAddress,
                            autoReplyEnabled = credentials.autoReplyEnabled
                        )
                    )
                )
            }
            weComBindings.groupBy { it.wecomBotId to it.wecomSecret }
                .forEach { (credentials, grouped) ->
                    val allowedTargets = grouped.map { it.chatId }
                        .filter { it.isNotBlank() }
                        .distinct()
                        .toSet()
                    val routeRules = grouped.filter { it.chatId.isNotBlank() }.associate { binding ->
                        binding.chatId to WeComRouteRule(
                            allowedUserIds = binding.wecomAllowedUserIds
                                .asSequence()
                                .map { it.trim() }
                                .filter { it.isNotBlank() }
                                .toSet()
                        )
                    }
                    val adapterKey = checkNotNull(
                        ChannelAdapterIdentity.primaryKeyForBinding(grouped.first())
                    )
                    add(
                        WeComChannelAdapter(
                            context = app,
                            adapterKey = adapterKey,
                            botId = credentials.first,
                            secret = credentials.second,
                            allowedChatTargets = allowedTargets,
                            routeRules = routeRules
                        )
                    )
                }
        }
    }

}

private data class EmailCredentialKey(
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
    val autoReplyEnabled: Boolean
)
