package com.palmclaw.providers

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import java.io.IOException

internal class OpenAiCompatibleProvider(
    private val providerLabel: String,
    private val apiKey: String,
    private val model: String,
    private val client: OkHttpClient,
    private val baseUrl: String,
    private val extraHeaders: Map<String, String> = emptyMap(),
    private val cacheMode: ProviderCacheMode = ProviderCacheMode.Auto
) : LlmProvider {

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun chat(messages: List<ChatMessage>, toolsSpec: List<ToolSpec>): LlmResponse {
        if (apiKey.isBlank()) {
            throw IllegalStateException("API key is empty. Please set API key in ConfigStore.")
        }
        val withCacheHint = cacheMode == ProviderCacheMode.ExplicitHint
        val reqBody = json.encodeToString(
            buildRequest(
                messages = messages,
                toolsSpec = toolsSpec,
                stream = false,
                includeCacheHint = withCacheHint
            )
        )
        Log.d(
            TAG,
            "Requesting $providerLabel non-stream, messages=${messages.size}, tools=${toolsSpec.size}, cacheHint=$withCacheHint"
        )

        return withContext(Dispatchers.IO) {
            val firstAttempt = executeNonStream(reqBody)
            if (firstAttempt.isSuccessful) {
                return@withContext parseNonStreamBody(firstAttempt.body)
            }

            if (withCacheHint && shouldRetryWithoutCacheHint(firstAttempt.code)) {
                Log.w(
                    TAG,
                    "Retrying $providerLabel non-stream without cache hint due to HTTP ${firstAttempt.code}"
                )
                val fallbackBody = json.encodeToString(
                    buildRequest(
                        messages = messages,
                        toolsSpec = toolsSpec,
                        stream = false,
                        includeCacheHint = false
                    )
                )
                val secondAttempt = executeNonStream(fallbackBody)
                if (secondAttempt.isSuccessful) {
                    return@withContext parseNonStreamBody(secondAttempt.body)
                }
                throw IOException(
                    "$providerLabel HTTP ${secondAttempt.code}: ${secondAttempt.body.take(800)}"
                )
            }

            throw IOException("$providerLabel HTTP ${firstAttempt.code}: ${firstAttempt.body.take(800)}")
        }
    }

    override fun chatStream(messages: List<ChatMessage>, toolsSpec: List<ToolSpec>): Flow<LlmStreamEvent> = callbackFlow {
        if (apiKey.isBlank()) {
            trySend(LlmStreamEvent.Error("API key is empty. Please set API key in ConfigStore."))
            close()
            return@callbackFlow
        }

        val reqBody = json.encodeToString(
            buildRequest(
                messages = messages,
                toolsSpec = toolsSpec,
                stream = true,
                includeCacheHint = cacheMode == ProviderCacheMode.ExplicitHint
            )
        )
        val request = buildHttpRequest(reqBody, stream = true)

        val accumulator = StreamAccumulator()
        var terminalEmitted = false
        val listener = object : EventSourceListener() {
            override fun onOpen(eventSource: EventSource, response: Response) {
                if (!response.isSuccessful) {
                    val body = response.peekBody(MAX_ERROR_BODY_BYTES).string().trim()
                    val message = buildString {
                        append("$providerLabel stream HTTP ${response.code}")
                        if (body.isNotEmpty()) {
                            append(": ")
                            append(body.take(MAX_ERROR_TEXT_CHARS))
                        }
                    }
                    trySend(LlmStreamEvent.Error(message))
                    terminalEmitted = true
                    eventSource.cancel()
                    close()
                    return
                }
                val contentType = response.header("Content-Type").orEmpty().lowercase()
                if (!contentType.contains("text/event-stream")) {
                    val body = response.peekBody(MAX_ERROR_BODY_BYTES).string().trim()
                    val message = buildString {
                        append("Unexpected content-type: $contentType")
                        if (body.isNotEmpty()) {
                            append("; body=")
                            append(body.take(MAX_ERROR_TEXT_CHARS))
                        }
                    }
                    trySend(LlmStreamEvent.Error(message))
                    terminalEmitted = true
                    eventSource.cancel()
                    close()
                }
            }

            override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                if (data == "[DONE]") {
                    terminalEmitted = true
                    trySend(LlmStreamEvent.Final(accumulator.toFinalResponse()))
                    eventSource.cancel()
                    close()
                    return
                }
                try {
                    val chunk = json.decodeFromString(ChatCompletionChunk.serializer(), data)
                    accumulator.appendChunk(chunk)
                    val delta = chunk.choices.firstOrNull()?.delta?.content
                    if (!delta.isNullOrEmpty()) {
                        trySend(LlmStreamEvent.DeltaText(delta))
                    }
                } catch (t: Throwable) {
                    terminalEmitted = true
                    trySend(LlmStreamEvent.Error("Failed to parse stream chunk", t))
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
                trySend(LlmStreamEvent.Error(message, t))
                eventSource.cancel()
                close()
            }

            override fun onClosed(eventSource: EventSource) {
                if (!terminalEmitted) {
                    terminalEmitted = true
                    trySend(
                        LlmStreamEvent.Error(
                            "$providerLabel stream closed before [DONE]"
                        )
                    )
                }
                close()
            }
        }

        val eventSource = EventSources.createFactory(client).newEventSource(request, listener)
        awaitClose { eventSource.cancel() }
    }

    private fun buildHttpRequest(reqBody: String, stream: Boolean): Request {
        val builder = Request.Builder()
            .url(baseUrl)
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .post(reqBody.toRequestBody(JSON_MEDIA_TYPE))

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

    private fun buildRequest(
        messages: List<ChatMessage>,
        toolsSpec: List<ToolSpec>,
        stream: Boolean,
        includeCacheHint: Boolean
    ): LlmRequest {
        return LlmRequest(
            model = model,
            messages = messages.map {
                ChatMessagePayload(
                    role = it.role,
                    content = it.content,
                    toolCallId = it.toolCallId,
                    toolCalls = it.toolCalls?.map { call ->
                        ChatCompletionsResponse.ResponseToolCall(
                            id = call.id,
                            type = "function",
                            function = ChatCompletionsResponse.ResponseToolFunction(
                                name = call.name,
                                arguments = call.argumentsJson
                            )
                        )
                    }
                )
            },
            tools = toolsSpec.map {
                ToolSpecPayload(
                    type = "function",
                    function = FunctionSpecPayload(
                        name = it.name,
                        description = it.description,
                        parameters = it.parameters
                    )
                )
            },
            stream = stream,
            cacheControl = if (includeCacheHint) {
                CacheControlPayload(type = CACHE_CONTROL_TYPE_EPHEMERAL)
            } else {
                null
            }
        )
    }

    private fun executeNonStream(reqBody: String): NonStreamResult {
        val request = buildHttpRequest(reqBody, stream = false)
        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            return NonStreamResult(
                code = response.code,
                body = body,
                isSuccessful = response.isSuccessful
            )
        }
    }

    private fun shouldRetryWithoutCacheHint(code: Int): Boolean {
        return code in CACHE_HINT_RETRY_HTTP_CODES
    }

    private data class NonStreamResult(
        val code: Int,
        val body: String,
        val isSuccessful: Boolean
    )

    private fun parseNonStreamBody(body: String): LlmResponse {
        val parsed = json.decodeFromString(ChatCompletionsResponse.serializer(), body)
        val message = parsed.choices.firstOrNull()?.message
            ?: throw IOException("$providerLabel response missing choices")

        val toolCalls = message.toolCalls.orEmpty().map {
            ToolCall(
                id = it.id,
                name = it.function.name,
                argumentsJson = it.function.arguments
            )
        }
        return LlmResponse(
            assistant = AssistantMessage(
                content = message.content.orEmpty(),
                toolCalls = toolCalls
            ),
            usage = parsed.usage?.toLlmUsage()
        )
    }

    private class StreamAccumulator {
        private val content = StringBuilder()
        private val toolCallsByIndex = linkedMapOf<Int, PartialToolCall>()

        fun appendChunk(chunk: ChatCompletionChunk) {
            val choice = chunk.choices.firstOrNull() ?: return
            val delta = choice.delta
            delta.content?.let { content.append(it) }
            delta.toolCalls.orEmpty().forEach { tool ->
                val index = tool.index ?: return@forEach
                val current = toolCallsByIndex.getOrPut(index) { PartialToolCall() }
                tool.id?.let { current.id = it }
                tool.function?.name?.let { current.name = it }
                tool.function?.arguments?.let { current.arguments.append(it) }
            }
        }

        fun toFinalResponse(): LlmResponse {
            val toolCalls = toolCallsByIndex.values.mapNotNull { partial ->
                val id = partial.id ?: return@mapNotNull null
                val name = partial.name ?: return@mapNotNull null
                ToolCall(id = id, name = name, argumentsJson = partial.arguments.toString())
            }
            return LlmResponse(
                assistant = AssistantMessage(
                    content = content.toString(),
                    toolCalls = toolCalls
                ),
                usage = null
            )
        }

        private class PartialToolCall {
            var id: String? = null
            var name: String? = null
            val arguments: StringBuilder = StringBuilder()
        }
    }

    companion object {
        private const val TAG = "OpenAiCompatProvider"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private const val MAX_ERROR_BODY_BYTES = 64L * 1024L
        private const val MAX_ERROR_TEXT_CHARS = 2000
        private const val CACHE_CONTROL_TYPE_EPHEMERAL = "ephemeral"
        private val CACHE_HINT_RETRY_HTTP_CODES = setOf(400, 404, 415, 422)
    }
}

private fun ChatCompletionsResponse.Usage.toLlmUsage(): LlmUsage {
    val input = promptTokens ?: 0L
    val output = completionTokens ?: 0L
    val total = totalTokens ?: (input + output)
    val cached = promptTokensDetails?.cachedTokens ?: 0L
    return LlmUsage(
        inputTokens = input.coerceAtLeast(0L),
        outputTokens = output.coerceAtLeast(0L),
        totalTokens = total.coerceAtLeast(0L),
        cachedInputTokens = cached.coerceAtLeast(0L)
    )
}
