package com.palmclaw.providers

import java.io.IOException
import java.security.MessageDigest
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.encodeToString
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/** Owns the wire format, including opaque provider state, for Chat Completions. */
internal class OpenAiMessageCodec(
    private val scope: String,
    private val model: String
) {
    @OptIn(ExperimentalSerializationApi::class)
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    fun encodeRequest(
        messages: List<ChatMessage>,
        tools: List<ToolSpec>,
        stream: Boolean,
        includeCacheHint: Boolean
    ): String {
        validateContinuationScope(messages, scope)
        return json.encodeToString(LlmRequest(
            model = model,
            messages = messages.map { message ->
                ChatMessagePayload(
                    role = message.role,
                    content = message.content,
                    toolCallId = message.toolCallId,
                    toolCalls = message.toolCalls?.map { call ->
                        ChatCompletionsResponse.ResponseToolCall(
                            id = call.id,
                            type = "function",
                            function = ChatCompletionsResponse.ResponseToolFunction(call.name, call.argumentsJson),
                            extraContent = replay(call.protocolMetadata)
                        )
                    },
                    reasoningContent = message.reasoningContent,
                    extraContent = if (message.role == "assistant") replay(message.protocolMetadata) else null
                )
            },
            tools = tools.map { ToolSpecPayload("function", FunctionSpecPayload(it.name, it.description, it.parameters)) },
            stream = stream,
            cacheControl = if (includeCacheHint) CacheControlPayload() else null
        ))
    }

    fun decodeResponse(body: String): LlmResponse {
        val parsed = decode<ChatCompletionsResponse>(body)
        val message = parsed.choices.firstOrNull()?.message
            ?: throw IOException("Model response missing choices")
        return LlmResponse(
            assistant = AssistantMessage(
                content = message.content.orEmpty(),
                toolCalls = message.toolCalls.orEmpty().map {
                    ToolCall(it.id, it.function.name, it.function.arguments, capture(it.extraContent))
                },
                reasoningContent = message.reasoningContent,
                protocolMetadata = capture(message.extraContent)
            ),
            usage = parsed.usage?.toLlmUsage()
        )
    }

    fun newStream(): Stream = Stream()

    private fun capture(extra: JsonObject?): ProtocolMetadata? = extra?.let { ProtocolMetadata(scope, it) }

    private fun replay(metadata: ProtocolMetadata?): JsonObject? =
        metadata?.takeIf { it.scope == scope }?.extraContent

    private inline fun <reified T> decode(body: String): T = try {
        json.decodeFromString<T>(body)
    } catch (_: SerializationException) {
        // Serialization exception messages can contain the raw response and its signatures.
        throw IOException("Invalid Chat Completions response format")
    }

    inner class Stream internal constructor() {
        private val content = StringBuilder()
        private val reasoning = StringBuilder()
        private var extra: JsonObject? = null
        private val calls = sortedMapOf<Int, PartialCall>()

        /** Processes even chunks without visible text; signatures can arrive after arguments. */
        fun append(data: String): String? {
            val chunk = decode<ChatCompletionChunk>(data)
            val delta = chunk.choices.firstOrNull()?.delta ?: return null
            delta.content?.let(content::append)
            delta.reasoningContent?.let(reasoning::append)
            extra = mergeExtra(extra, delta.extraContent)
            val incoming = delta.toolCalls.orEmpty()
            val initiallyEmpty = calls.isEmpty()
            incoming.forEachIndexed { position, tool ->
                val index = tool.index
                    ?: tool.id?.let { id -> calls.entries.firstOrNull { it.value.id == id }?.key }
                    ?: when {
                        // Some compatible endpoints emit a complete, unindexed tool-call array.
                        initiallyEmpty && tool.id != null && tool.function?.name != null -> position
                        incoming.size == 1 && calls.size == 1 && tool.id == null -> calls.firstKey()
                        else -> throw IOException("Ambiguous streamed tool call: missing index")
                    }
                val call = calls.getOrPut(index) { PartialCall() }
                tool.id?.let {
                    if (call.id != null && call.id != it) throw IOException("Conflicting streamed tool call ID")
                    call.id = it
                }
                tool.function?.name?.let { call.name = it }
                tool.function?.arguments?.let(call.arguments::append)
                call.extra = mergeExtra(call.extra, tool.extraContent)
            }
            return delta.content
        }

        fun finish(): LlmResponse = LlmResponse(
            AssistantMessage(
                content = content.toString(),
                toolCalls = calls.values.map { call ->
                    ToolCall(
                        id = call.id ?: throw IOException("Incomplete streamed tool call: missing ID"),
                        name = call.name ?: throw IOException("Incomplete streamed tool call: missing name"),
                        argumentsJson = call.arguments.toString(),
                        protocolMetadata = capture(call.extra)
                    )
                },
                reasoningContent = reasoning.toString().ifBlank { null },
                protocolMetadata = capture(extra)
            )
        )
    }

    private class PartialCall {
        var id: String? = null
        var name: String? = null
        val arguments = StringBuilder()
        var extra: JsonObject? = null
    }

    companion object {
        /** Store only a routing fingerprint, never a URL credential or API key. */
        fun scope(providerIdentity: String, endpoint: String, model: String): String {
            val identity = Json.encodeToString(listOf("openai-chat-v1", providerIdentity, endpoint.trim(), model))
            return MessageDigest.getInstance("SHA-256").digest(identity.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        }

        private fun mergeExtra(previous: JsonObject?, incoming: JsonObject?): JsonObject? {
            if (previous == null) return incoming
            if (incoming == null) return previous
            val merged = previous.toMutableMap()
            incoming.forEach { (key, value) ->
                val old = merged[key]
                merged[key] = when {
                    old == null || old == value -> value
                    old is JsonObject && value is JsonObject -> mergeExtra(old, value)!!
                    // Opaque strings are complete values, not text deltas. Never concatenate them.
                    else -> throw IOException("Conflicting provider continuation data in stream")
                }
            }
            return JsonObject(merged)
        }
    }
}
