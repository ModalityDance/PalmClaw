package com.palmclaw.attachments

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
sealed class AttachmentRemoteLocator {
    @Serializable
    @SerialName("public_url")
    data class PublicUrl(
        val url: String
    ) : AttachmentRemoteLocator()

    @Serializable
    @SerialName("bearer_url")
    data class BearerUrl(
        val url: String,
        val bearerToken: String
    ) : AttachmentRemoteLocator()

    @Serializable
    @SerialName("telegram_bot_file")
    data class TelegramBotFile(
        val url: String,
        val fileId: String
    ) : AttachmentRemoteLocator()

    @Serializable
    @SerialName("feishu_file_key")
    data class FeishuFileKey(
        val url: String,
        val fileKey: String
    ) : AttachmentRemoteLocator()

    @Serializable
    @SerialName("slack_private_file")
    data class SlackPrivateFile(
        val url: String,
        val fileId: String
    ) : AttachmentRemoteLocator()

    @Serializable
    @SerialName("wecom_encrypted_url")
    data class WeComEncryptedUrl(
        val url: String
    ) : AttachmentRemoteLocator()

    @Serializable
    @SerialName("email_part")
    data class EmailPart(
        val messageId: String,
        val filename: String
    ) : AttachmentRemoteLocator()
}

object AttachmentRemoteLocatorJsonCodec {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
    }

    fun encode(locator: AttachmentRemoteLocator?): String? {
        if (locator == null) return null
        return json.encodeToString(locator)
    }

    fun decode(raw: String?): AttachmentRemoteLocator? {
        if (raw.isNullOrBlank()) return null
        return runCatching {
            json.decodeFromString<AttachmentRemoteLocator>(raw)
        }.getOrNull()
    }
}
