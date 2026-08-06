package com.palmclaw.channels

import java.util.LinkedHashSet
import java.util.Locale
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request

class TelegramApiDiscoveryClient(
    private val client: OkHttpClient
) : TelegramDiscoveryClient {
    override fun discover(botToken: String): List<TelegramChatCandidate> {
        val request = Request.Builder()
            .url("https://api.telegram.org/bot$botToken/getUpdates?timeout=1&limit=100")
            .get()
            .build()
        client.newCall(request).execute().use { response ->
            return TelegramDiscoveryResponseParser.parse(
                statusCode = response.code,
                successful = response.isSuccessful,
                body = response.body?.string().orEmpty()
            )
        }
    }
}

internal object TelegramDiscoveryResponseParser {
    private val json = Json { ignoreUnknownKeys = true }

    fun parse(
        statusCode: Int,
        successful: Boolean,
        body: String
    ): List<TelegramChatCandidate> {
        if (!successful) {
            val description = runCatching {
                json.parseToJsonElement(body).jsonObject["description"]
                    ?.jsonPrimitive
                    ?.contentOrNull
                    .orEmpty()
            }.getOrDefault("").ifBlank { body.take(MAX_ERROR_DETAIL_CHARS) }
            val kind = if (statusCode == 401 || statusCode == 404) {
                ChannelDiscoveryFailureKind.AUTHENTICATION
            } else {
                ChannelDiscoveryFailureKind.NETWORK
            }
            val message = if (statusCode == 404) {
                "Telegram API returned 404. Check the Bot Token and paste only the token from BotFather, not the full API URL."
            } else {
                "Telegram API HTTP $statusCode: ${description.take(MAX_ERROR_DETAIL_CHARS)}"
            }
            throw ChannelDiscoveryException(kind, message)
        }
        val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrElse {
            throw ChannelDiscoveryException(
                ChannelDiscoveryFailureKind.UNEXPECTED,
                "Telegram API returned an invalid response."
            )
        }
        if (root["ok"]?.jsonPrimitive?.booleanOrNull != true) {
            val description = root["description"]
                ?.jsonPrimitive
                ?.contentOrNull
                .orEmpty()
                .take(MAX_ERROR_DETAIL_CHARS)
                .ifBlank { "Telegram API rejected the request." }
            throw ChannelDiscoveryException(
                ChannelDiscoveryFailureKind.AUTHENTICATION,
                description
            )
        }
        val result = runCatching { root["result"]?.jsonArray }.getOrNull() ?: return emptyList()
        val seenChatIds = LinkedHashSet<String>()
        return buildList {
            result.forEach { element ->
                val update = runCatching { element.jsonObject }.getOrNull() ?: return@forEach
                val messageLike = MESSAGE_KEYS.firstNotNullOfOrNull { key ->
                    runCatching { update[key]?.jsonObject }.getOrNull()
                } ?: runCatching {
                    update["callback_query"]?.jsonObject?.get("message")?.jsonObject
                }.getOrNull()
                    ?: return@forEach
                val chat = runCatching { messageLike["chat"]?.jsonObject }.getOrNull()
                    ?: return@forEach
                val chatId = chat["id"]?.jsonPrimitive?.contentOrNull
                    ?.takeIf { value -> value.toLongOrNull()?.let { it != 0L } == true }
                    .orEmpty()
                if (chatId.isBlank() || !seenChatIds.add(chatId)) return@forEach
                val chatType = chat.string("type").ifBlank { "unknown" }
                add(
                    TelegramChatCandidate(
                        chatId = chatId,
                        title = buildChatTitle(chat, chatType),
                        kind = chatType
                    )
                )
            }
        }
    }

    private fun buildChatTitle(
        chat: JsonObject,
        chatType: String
    ): String =
        when (chatType.lowercase(Locale.US)) {
            "private" -> {
                val first = chat.string("first_name").trim()
                val last = chat.string("last_name").trim()
                val username = chat.string("username").trim()
                val name = listOf(first, last).filter { it.isNotBlank() }.joinToString(" ").trim()
                when {
                    name.isNotBlank() && username.isNotBlank() -> "$name (@$username)"
                    name.isNotBlank() -> name
                    username.isNotBlank() -> "@$username"
                    else -> "Private chat"
                }
            }
            "group", "supergroup", "channel" ->
                chat.string("title").trim().ifBlank { "Untitled $chatType" }
            else -> chat.string("title").trim().ifBlank {
                chat.string("username").trim().ifBlank { "Chat" }
            }
        }

    private fun JsonObject.string(key: String): String =
        this[key]?.jsonPrimitive?.contentOrNull.orEmpty()

    private const val MAX_ERROR_DETAIL_CHARS = 300
    private val MESSAGE_KEYS = listOf(
        "message",
        "edited_message",
        "channel_post",
        "edited_channel_post",
        "my_chat_member",
        "chat_member",
        "chat_join_request"
    )
}
