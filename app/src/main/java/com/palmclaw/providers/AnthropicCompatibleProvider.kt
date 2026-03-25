package com.palmclaw.providers

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import java.io.IOException

internal class AnthropicCompatibleProvider(
    private val providerLabel: String,
    private val apiKey: String,
    private val model: String,
    private val client: OkHttpClient,
    private val baseUrl: String,
    private val extraHeaders: Map<String, String> = emptyMap()
) : LlmProvider {

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun chat(messages: List<ChatMessage>, toolsSpec: List<ToolSpec>): LlmResponse {
        if (apiKey.isBlank()) {
            throw IllegalStateException("API key is empty. Please set API key in ConfigStore.")
        }
        val requestBody = json.encodeToString(buildRequest(messages, toolsSpec, stream = false))
        Log.d(
            TAG,
            "Requesting $providerLabel non-stream, messages=${messages.size}, tools=${toolsSpec.size}"
        )
        return withContext(Dispatchers.IO) {
            val result = executeNonStream(requestBody)
            if (!result.isSuccessful) {
                val failure = ProviderHttpException(
                    providerLabel = providerLabel,
                    statusCode = result.code,
                    responseBody = result.body
                )
                if (failure.requiresStreaming) {
                    return@withContext collectStreamResponse(chatStream(messages, toolsSpec))
                }
                throw failure
            }
            parseNonStreamBody(result.body)
        }
    }

    override fun chatStream(messages: List<ChatMessage>, toolsSpec: List<ToolSpec>): Flow<LlmStreamEvent> = callbackFlow {
        if (apiKey.isBlank()) {
            trySend(LlmStreamEvent.Error("API key is empty. Please set API key in ConfigStore."))
            close()
            return@callbackFlow
        }

        val requestBody = json.encodeToString(buildRequest(messages, toolsSpec, stream = true))
        val request = buildHttpRequest(requestBody, stream = true)
        val accumulator = StreamAccumulator()
        var terminalEmitted = false

        val listener = object : EventSourceListener() {
            override fun onOpen(eventSource: EventSource, response: Response) {
                if (!response.isSuccessful) {
                    val body = response.peekBody(MAX_ERROR_BODY_BYTES).string().trim()
                    val error = ProviderHttpException(
                        providerLabel = providerLabel,
                        statusCode = response.code,
                        responseBody = body,
                        streaming = true
                    )
                    terminalEmitted = true
                    trySend(LlmStreamEvent.Error(error.message.orEmpty(), error))
                    eventSource.cancel()
                    close()
                }
            }

            override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                try {
                    val root = json.parseToJsonElement(data).jsonObject
                    when (type ?: root.stringOrNull("type").orEmpty()) {
                        "message_start" -> accumulator.applyMessageStart(root)
                        "content_block_start" -> accumulator.applyContentBlockStart(root)
                        "content_block_delta" -> {
                            accumulator.applyContentBlockDelta(root)?.let { delta ->
                                trySend(LlmStreamEvent.DeltaText(delta))
                            }
                        }

                        "message_delta" -> accumulator.applyMessageDelta(root)
                        "message_stop" -> {
                            terminalEmitted = true
                            trySend(LlmStreamEvent.Final(accumulator.toFinalResponse()))
                            eventSource.cancel()
                            close()
                        }

                        "error" -> {
                            terminalEmitted = true
                            val message = root["error"]?.jsonObject?.stringOrNull("message")
                                ?: "$providerLabel stream error"
                            trySend(LlmStreamEvent.Error(message))
                            eventSource.cancel()
                            close()
                        }

                        else -> Unit
                    }
                } catch (t: Throwable) {
                    terminalEmitted = true
                    trySend(LlmStreamEvent.Error("Failed to parse Anthropic stream chunk", t))
                    eventSource.cancel()
                    close()
                }
            }

            override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                if (terminalEmitted) return
                terminalEmitted = true
                val code = response?.code
                val prefix = if (code != null) "$providerLabel stream HTTP $code" else "$providerLabel stream failed"
                val body = runCatching {
                    response?.peekBody(MAX_ERROR_BODY_BYTES)?.string().orEmpty()
                }.getOrDefault("")
                val providerError = if (code != null) {
                    ProviderHttpException(
                        providerLabel = providerLabel,
                        statusCode = code,
                        responseBody = body,
                        streaming = true
                    )
                } else {
                    null
                }
                val message = buildString {
                    append(prefix)
                    if (body.isNotBlank()) {
                        append(": ")
                        append(body.trim().take(MAX_ERROR_TEXT_CHARS))
                    }
                    if (!t?.message.isNullOrBlank()) {
                        append(if (body.isNotBlank()) " | " else ": ")
                        append(t?.message)
                    }
                }
                trySend(LlmStreamEvent.Error(message, providerError ?: t))
                eventSource.cancel()
                close()
            }

            override fun onClosed(eventSource: EventSource) {
                if (!terminalEmitted) {
                    terminalEmitted = true
                    trySend(LlmStreamEvent.Error("$providerLabel stream closed before message_stop"))
                }
                close()
            }
        }

        val eventSource = EventSources.createFactory(client).newEventSource(request, listener)
        awaitClose { eventSource.cancel() }
    }

    private fun buildRequest(
        messages: List<ChatMessage>,
        toolsSpec: List<ToolSpec>,
        stream: Boolean
    ): AnthropicMessagesRequest {
        val systemPrompt = messages
            .filter { it.role == "system" }
            .joinToString("\n\n") { it.content.trim() }
            .trim()
            .ifBlank { null }
        val conversation = buildAnthropicMessages(messages)
        return AnthropicMessagesRequest(
            model = model,
            maxTokens = DEFAULT_MAX_OUTPUT_TOKENS,
            system = systemPrompt,
            messages = conversation,
            tools = toolsSpec.takeIf { it.isNotEmpty() }?.map { tool ->
                AnthropicToolSpec(
                    name = tool.name,
                    description = tool.description,
                    inputSchema = tool.parameters
                )
            },
            stream = stream
        )
    }

    private fun buildAnthropicMessages(messages: List<ChatMessage>): List<AnthropicMessagePayload> {
        val output = mutableListOf<AnthropicMessagePayload>()
        messages.forEach { message ->
            when (message.role) {
                "system" -> Unit
                "assistant" -> {
                    val blocks = mutableListOf<JsonObject>()
                    val text = message.content.trim()
                    if (text.isNotBlank()) {
                        blocks += textBlock(text)
                    }
                    message.toolCalls.orEmpty().forEach { call ->
                        blocks += toolUseBlock(call)
                    }
                    if (blocks.isNotEmpty()) {
                        output += AnthropicMessagePayload(role = "assistant", content = blocks)
                    }
                }

                "tool" -> {
                    val toolCallId = message.toolCallId?.trim().orEmpty()
                    if (toolCallId.isBlank()) return@forEach
                    val last = output.lastOrNull()
                    if (last?.role == "user" && last.content.all { it.stringOrNull("type") == "tool_result" }) {
                        last.content += toolResultBlock(toolCallId, message.content)
                    } else {
                        output += AnthropicMessagePayload(
                            role = "user",
                            content = mutableListOf(toolResultBlock(toolCallId, message.content))
                        )
                    }
                }

                else -> {
                    val text = when (message.role) {
                        "user", "internal_user" -> message.content
                        else -> "[${message.role}] ${message.content}"
                    }.trim()
                    if (text.isNotBlank()) {
                        output += AnthropicMessagePayload(
                            role = "user",
                            content = mutableListOf(textBlock(text))
                        )
                    }
                }
            }
        }
        return output
    }

    private fun textBlock(text: String): JsonObject = buildJsonObject {
        put("type", JsonPrimitive("text"))
        put("text", JsonPrimitive(text))
    }

    private fun toolUseBlock(call: ToolCall): JsonObject = buildJsonObject {
        put("type", JsonPrimitive("tool_use"))
        put("id", JsonPrimitive(call.id))
        put("name", JsonPrimitive(call.name))
        put("input", toolInputObject(call.argumentsJson))
    }

    private fun toolResultBlock(toolCallId: String, content: String): JsonObject = buildJsonObject {
        put("type", JsonPrimitive("tool_result"))
        put("tool_use_id", JsonPrimitive(toolCallId))
        put(
            "content",
            buildJsonArray {
                add(textBlock(content))
            }
        )
    }

    private fun toolInputObject(argumentsJson: String): JsonObject {
        val parsed = runCatching { json.parseToJsonElement(argumentsJson) }.getOrNull()
        return when (parsed) {
            is JsonObject -> parsed
            null, JsonNull -> buildJsonObject { }
            else -> buildJsonObject {
                put("_value", parsed)
            }
        }
    }

    private fun buildHttpRequest(requestBody: String, stream: Boolean): Request {
        val builder = Request.Builder()
            .url(baseUrl)
            .header("x-api-key", apiKey)
            .header("anthropic-version", ANTHROPIC_VERSION)
            .header("Content-Type", "application/json")
            .post(requestBody.toRequestBody(JSON_MEDIA_TYPE))

        if (stream) {
            builder.header("Accept", "text/event-stream")
        }
        extraHeaders.forEach { (key, value) ->
            if (value.isNotBlank()) {
                builder.header(key, value)
            }
        }
        return builder.build()
    }

    private fun executeNonStream(requestBody: String): NonStreamResult {
        val request = buildHttpRequest(requestBody, stream = false)
        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            return NonStreamResult(
                code = response.code,
                body = body,
                isSuccessful = response.isSuccessful
            )
        }
    }

    private fun parseNonStreamBody(body: String): LlmResponse {
        val parsed = json.decodeFromString(AnthropicMessagesResponse.serializer(), body)
        val contentText = parsed.content
            .filter { it.type == "text" }
            .joinToString(separator = "") { it.text.orEmpty() }
        val toolCalls = parsed.content
            .filter { it.type == "tool_use" }
            .mapNotNull { block ->
                val id = block.id ?: return@mapNotNull null
                val name = block.name ?: return@mapNotNull null
                ToolCall(
                    id = id,
                    name = name,
                    argumentsJson = json.encodeToString(JsonObject.serializer(), block.input ?: buildJsonObject { })
                )
            }
        return LlmResponse(
            assistant = AssistantMessage(
                content = contentText,
                toolCalls = toolCalls
            ),
            usage = parsed.usage?.toLlmUsage()
        )
    }

    private data class NonStreamResult(
        val code: Int,
        val body: String,
        val isSuccessful: Boolean
    )

    private inner class StreamAccumulator {
        private val text = StringBuilder()
        private val toolCalls = linkedMapOf<Int, PartialToolCall>()
        private var inputTokens: Long = 0L
        private var outputTokens: Long = 0L

        fun applyMessageStart(root: JsonObject) {
            val usage = root["message"]?.jsonObject?.get("usage")?.jsonObject ?: return
            inputTokens = usage.longOrZero("input_tokens")
            outputTokens = usage.longOrZero("output_tokens")
        }

        fun applyContentBlockStart(root: JsonObject) {
            val index = root.intOrNull("index") ?: return
            val block = root["content_block"]?.jsonObject ?: return
            if (block.stringOrNull("type") == "tool_use") {
                val partial = toolCalls.getOrPut(index) { PartialToolCall() }
                partial.id = block.stringOrNull("id")
                partial.name = block.stringOrNull("name")
            }
        }

        fun applyContentBlockDelta(root: JsonObject): String? {
            val index = root.intOrNull("index") ?: return null
            val delta = root["delta"]?.jsonObject ?: return null
            return when (delta.stringOrNull("type")) {
                "text_delta" -> delta.stringOrNull("text")?.also { text.append(it) }
                "input_json_delta" -> {
                    val partial = toolCalls.getOrPut(index) { PartialToolCall() }
                    delta.stringOrNull("partial_json")?.also { partial.arguments.append(it) }
                    null
                }

                else -> null
            }
        }

        fun applyMessageDelta(root: JsonObject) {
            val usage = root["usage"]?.jsonObject ?: return
            outputTokens = usage.longOrZero("output_tokens").coerceAtLeast(outputTokens)
        }

        fun toFinalResponse(): LlmResponse {
            val toolCallList = toolCalls.values.mapNotNull { partial ->
                val id = partial.id ?: return@mapNotNull null
                val name = partial.name ?: return@mapNotNull null
                val arguments = partial.arguments.toString().trim().ifBlank { "{}" }
                ToolCall(
                    id = id,
                    name = name,
                    argumentsJson = arguments
                )
            }
            val usage = if (inputTokens > 0L || outputTokens > 0L) {
                LlmUsage(
                    inputTokens = inputTokens,
                    outputTokens = outputTokens,
                    totalTokens = inputTokens + outputTokens
                )
            } else {
                null
            }
            return LlmResponse(
                assistant = AssistantMessage(
                    content = text.toString(),
                    toolCalls = toolCallList
                ),
                usage = usage
            )
        }
    }

    private class PartialToolCall {
        var id: String? = null
        var name: String? = null
        val arguments = StringBuilder()
    }

    companion object {
        private const val TAG = "AnthropicProvider"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private const val ANTHROPIC_VERSION = "2023-06-01"
        private const val DEFAULT_MAX_OUTPUT_TOKENS = 4096
        private const val MAX_ERROR_BODY_BYTES = 64L * 1024L
        private const val MAX_ERROR_TEXT_CHARS = 2000
    }
}

@Serializable
private data class AnthropicMessagesRequest(
    val model: String,
    @SerialName("max_tokens") val maxTokens: Int,
    val messages: List<AnthropicMessagePayload>,
    val system: String? = null,
    val tools: List<AnthropicToolSpec>? = null,
    val stream: Boolean? = null
)

@Serializable
private data class AnthropicMessagePayload(
    val role: String,
    val content: MutableList<JsonObject>
)

@Serializable
private data class AnthropicToolSpec(
    val name: String,
    val description: String,
    @SerialName("input_schema") val inputSchema: JsonObject
)

@Serializable
private data class AnthropicMessagesResponse(
    val content: List<AnthropicContentBlock> = emptyList(),
    val usage: AnthropicUsage? = null
)

@Serializable
private data class AnthropicContentBlock(
    val type: String,
    val text: String? = null,
    val id: String? = null,
    val name: String? = null,
    val input: JsonObject? = null
)

@Serializable
private data class AnthropicUsage(
    @SerialName("input_tokens") val inputTokens: Long = 0L,
    @SerialName("output_tokens") val outputTokens: Long = 0L
)

private fun AnthropicUsage.toLlmUsage(): LlmUsage {
    return LlmUsage(
        inputTokens = inputTokens.coerceAtLeast(0L),
        outputTokens = outputTokens.coerceAtLeast(0L),
        totalTokens = (inputTokens + outputTokens).coerceAtLeast(0L)
    )
}

private fun JsonObject.stringOrNull(key: String): String? {
    return (this[key] as? JsonPrimitive)?.contentOrNull
}

private fun JsonObject.intOrNull(key: String): Int? {
    return stringOrNull(key)?.toIntOrNull()
}

private fun JsonObject.longOrZero(key: String): Long {
    return stringOrNull(key)?.toLongOrNull() ?: 0L
}
