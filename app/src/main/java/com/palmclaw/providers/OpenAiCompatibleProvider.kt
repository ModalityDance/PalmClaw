package com.palmclaw.providers

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withContext
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
    private val cacheMode: ProviderCacheMode = ProviderCacheMode.Auto,
    providerIdentity: String = providerLabel,
    private val log: (String) -> Unit = { Log.d(TAG, it) }
) : LlmProvider {

    private val codec = OpenAiMessageCodec(
        OpenAiMessageCodec.scope(providerIdentity, baseUrl, model), model
    )

    override suspend fun chat(messages: List<ChatMessage>, toolsSpec: List<ToolSpec>): LlmResponse {
        if (apiKey.isBlank()) {
            throw IllegalStateException("API key is empty. Please set API key in ConfigStore.")
        }
        val withCacheHint = cacheMode == ProviderCacheMode.ExplicitHint
        val reqBody = codec.encodeRequest(
            messages = messages,
            tools = toolsSpec,
            stream = false,
            includeCacheHint = withCacheHint
        )
        log(
            "Requesting $providerLabel non-stream, messages=${messages.size}, tools=${toolsSpec.size}, cacheHint=$withCacheHint"
        )

        return withContext(Dispatchers.IO) {
            val firstAttempt = executeNonStream(reqBody)
            if (firstAttempt.isSuccessful) {
                return@withContext codec.decodeResponse(firstAttempt.body)
            }

            if (withCacheHint && shouldRetryWithoutCacheHint(firstAttempt.code) &&
                !ProviderHttpException.isContinuationFailure(firstAttempt.code, firstAttempt.body)
            ) {
                log(
                    "Retrying $providerLabel non-stream without cache hint due to HTTP ${firstAttempt.code}"
                )
                val fallbackBody = codec.encodeRequest(
                    messages = messages,
                    tools = toolsSpec,
                    stream = false,
                    includeCacheHint = false
                )
                val secondAttempt = executeNonStream(fallbackBody)
                if (secondAttempt.isSuccessful) {
                    return@withContext codec.decodeResponse(secondAttempt.body)
                }
                val retryFailure = ProviderHttpException(
                    providerLabel = providerLabel,
                    statusCode = secondAttempt.code,
                    responseBody = secondAttempt.body,
                    endpointUrl = baseUrl
                )
                if (retryFailure.requiresStreaming) {
                    return@withContext collectStreamResponse(chatStream(messages, toolsSpec))
                }
                throw retryFailure
            }

            val failure = ProviderHttpException(
                providerLabel = providerLabel,
                statusCode = firstAttempt.code,
                responseBody = firstAttempt.body,
                endpointUrl = baseUrl
            )
            if (failure.requiresStreaming) {
                return@withContext collectStreamResponse(chatStream(messages, toolsSpec))
            }
            throw failure
        }
    }

    override fun chatStream(messages: List<ChatMessage>, toolsSpec: List<ToolSpec>): Flow<LlmStreamEvent> = callbackFlow {
        if (apiKey.isBlank()) {
            trySend(LlmStreamEvent.Error("API key is empty. Please set API key in ConfigStore."))
            close()
            return@callbackFlow
        }

        val reqBody = codec.encodeRequest(
            messages = messages,
            tools = toolsSpec,
            stream = true,
            includeCacheHint = cacheMode == ProviderCacheMode.ExplicitHint
        )
        val request = buildHttpRequest(reqBody, stream = true)

        val accumulator = codec.newStream()
        var terminalEmitted = false
        val listener = object : EventSourceListener() {
            override fun onOpen(eventSource: EventSource, response: Response) {
                if (!response.isSuccessful) {
                    val body = response.peekBody(MAX_ERROR_BODY_BYTES).string().trim()
                    val error = ProviderHttpException(
                        providerLabel = providerLabel,
                        statusCode = response.code,
                        responseBody = body,
                        streaming = true,
                        endpointUrl = baseUrl
                    )
                    trySend(LlmStreamEvent.Error(error.message.orEmpty(), error))
                    terminalEmitted = true
                    eventSource.cancel()
                    close()
                    return
                }
                val contentType = response.header("Content-Type").orEmpty().lowercase()
                if (!contentType.contains("text/event-stream")) {
                    val message = "Unexpected stream content-type: $contentType"
                    trySend(LlmStreamEvent.Error(message))
                    terminalEmitted = true
                    eventSource.cancel()
                    close()
                }
            }

            override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                try {
                    if (data == "[DONE]") {
                        val response = accumulator.finish()
                        terminalEmitted = true
                        trySend(LlmStreamEvent.Final(response))
                        eventSource.cancel()
                        close()
                        return
                    }
                    val delta = accumulator.append(data)
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
                val providerError = if (code != null) {
                    ProviderHttpException(
                        providerLabel = providerLabel,
                        statusCode = code,
                        responseBody = body,
                        streaming = true,
                        endpointUrl = baseUrl
                    )
                } else {
                    null
                }
                val message = providerError?.message ?: buildString {
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

    companion object {
        private const val TAG = "OpenAiCompatProvider"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private const val MAX_ERROR_BODY_BYTES = 64L * 1024L
        private const val MAX_ERROR_TEXT_CHARS = 2000
        private val CACHE_HINT_RETRY_HTTP_CODES = setOf(400, 404, 415, 422)
    }
}

internal fun ChatCompletionsResponse.Usage.toLlmUsage(): LlmUsage {
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
