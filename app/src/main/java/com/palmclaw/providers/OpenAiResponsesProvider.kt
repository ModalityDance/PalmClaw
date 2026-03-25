package com.palmclaw.providers

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources

internal class OpenAiResponsesProvider(
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
        val requestBody = buildRequestBody(messages, toolsSpec, stream = false)
        Log.d(
            TAG,
            "Requesting $providerLabel responses non-stream, messages=${messages.size}, tools=${toolsSpec.size}"
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
            parseResponseBody(result.body)
        }
    }

    override fun chatStream(messages: List<ChatMessage>, toolsSpec: List<ToolSpec>): Flow<LlmStreamEvent> = callbackFlow {
        if (apiKey.isBlank()) {
            trySend(LlmStreamEvent.Error("API key is empty. Please set API key in ConfigStore."))
            close()
            return@callbackFlow
        }

        val requestBody = buildRequestBody(messages, toolsSpec, stream = true)
        val request = buildHttpRequest(requestBody, stream = true)
        val fallbackAccumulator = ResponsesStreamAccumulator()
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
                    return
                }
                val contentType = response.header("Content-Type").orEmpty().lowercase()
                if (!contentType.contains("text/event-stream")) {
                    val body = response.peekBody(MAX_ERROR_BODY_BYTES).string().trim()
                    terminalEmitted = true
                    trySend(
                        LlmStreamEvent.Error(
                            buildString {
                                append("Unexpected content-type: $contentType")
                                if (body.isNotEmpty()) {
                                    append("; body=")
                                    append(body.take(MAX_ERROR_TEXT_CHARS))
                                }
                            }
                        )
                    )
                    eventSource.cancel()
                    close()
                }
            }

            override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                try {
                    val root = json.parseToJsonElement(data).jsonObject
                    when (val eventType = (type ?: root.stringOrNull("type")).orEmpty()) {
                        "response.output_text.delta" -> {
                            val delta = root.stringOrNull("delta").orEmpty()
                            if (delta.isNotEmpty()) {
                                fallbackAccumulator.appendText(delta)
                                trySend(LlmStreamEvent.DeltaText(delta))
                            }
                        }

                        "response.output_item.added",
                        "response.output_item.done" -> fallbackAccumulator.applyOutputItem(
                            root["item"]?.jsonObject ?: root["output_item"]?.jsonObject
                        )

                        "response.function_call_arguments.delta" -> {
                            fallbackAccumulator.appendFunctionArguments(
                                itemId = root.stringOrNull("item_id"),
                                delta = root.stringOrNull("delta").orEmpty()
                            )
                        }

                        "response.function_call_arguments.done" -> {
                            fallbackAccumulator.finishFunctionArguments(
                                itemId = root.stringOrNull("item_id"),
                                arguments = root.stringOrNull("arguments")
                            )
                        }

                        "response.completed" -> {
                            terminalEmitted = true
                            val responseObject = root["response"]?.jsonObject
                            val finalResponse = if (responseObject != null) {
                                parseResponseObject(responseObject)
                            } else {
                                fallbackAccumulator.toFinalResponse()
                            }
                            trySend(LlmStreamEvent.Final(finalResponse))
                            eventSource.cancel()
                            close()
                        }

                        "response.failed", "error" -> {
                            terminalEmitted = true
                            val message = root["error"]?.jsonObject?.stringOrNull("message")
                                ?: root["response"]?.jsonObject
                                    ?.get("error")
                                    ?.jsonObject
                                    ?.stringOrNull("message")
                                ?: "$providerLabel responses stream failed"
                            trySend(LlmStreamEvent.Error(message))
                            eventSource.cancel()
                            close()
                        }

                        else -> {
                            if (eventType.endsWith(".delta")) {
                                // ignore unsupported delta events
                            }
                        }
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
                    if (fallbackAccumulator.hasOutput()) {
                        trySend(LlmStreamEvent.Final(fallbackAccumulator.toFinalResponse()))
                    } else {
                        trySend(LlmStreamEvent.Error("$providerLabel stream closed before [DONE]"))
                    }
                }
                close()
            }
        }

        val eventSource = EventSources.createFactory(client).newEventSource(request, listener)
        awaitClose { eventSource.cancel() }
    }

    private fun buildRequestBody(
        messages: List<ChatMessage>,
        toolsSpec: List<ToolSpec>,
        stream: Boolean
    ): String {
        val systemInstructions = messages
            .filter { it.role == "system" }
            .joinToString("\n\n") { it.content.trim() }
            .trim()
            .ifBlank { null }
        val inputItems = buildJsonArray {
            messages.forEach { message ->
                when (message.role) {
                    "system" -> Unit
                    "user", "internal_user" -> {
                        if (message.content.isNotBlank()) {
                            add(responseMessageInput(role = "user", content = message.content))
                        }
                    }

                    "assistant" -> {
                        if (message.content.isNotBlank()) {
                            add(responseMessageInput(role = "assistant", content = message.content))
                        }
                        message.toolCalls.orEmpty().forEach { call ->
                            add(
                                buildJsonObject {
                                    put("type", JsonPrimitive("function_call"))
                                    put("call_id", JsonPrimitive(call.id))
                                    put("name", JsonPrimitive(call.name))
                                    put("arguments", JsonPrimitive(call.argumentsJson))
                                }
                            )
                        }
                    }

                    "tool" -> {
                        val toolCallId = message.toolCallId?.trim().orEmpty()
                        if (toolCallId.isNotBlank()) {
                            add(
                                buildJsonObject {
                                    put("type", JsonPrimitive("function_call_output"))
                                    put("call_id", JsonPrimitive(toolCallId))
                                    put("output", JsonPrimitive(message.content))
                                }
                            )
                        }
                    }

                    else -> {
                        if (message.content.isNotBlank()) {
                            add(
                                responseMessageInput(
                                    role = "user",
                                    content = "[${message.role}] ${message.content}"
                                )
                            )
                        }
                    }
                }
            }
        }
        val body = buildJsonObject {
            put("model", JsonPrimitive(model))
            put("input", inputItems)
            if (systemInstructions != null) {
                put("instructions", JsonPrimitive(systemInstructions))
            }
            if (toolsSpec.isNotEmpty()) {
                put(
                    "tools",
                    buildJsonArray {
                        toolsSpec.forEach { tool ->
                            add(
                                buildJsonObject {
                                    put("type", JsonPrimitive("function"))
                                    put("name", JsonPrimitive(tool.name))
                                    put("description", JsonPrimitive(tool.description))
                                    put("parameters", tool.parameters)
                                    put("strict", JsonPrimitive(false))
                                }
                            )
                        }
                    }
                )
            }
            put("stream", JsonPrimitive(stream))
        }
        return json.encodeToString(JsonObject.serializer(), body)
    }

    private fun responseMessageInput(role: String, content: String): JsonObject {
        val contentType = when (role) {
            "assistant" -> "output_text"
            else -> "input_text"
        }
        return buildJsonObject {
            put("type", JsonPrimitive("message"))
            put("role", JsonPrimitive(role))
            put(
                "content",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("type", JsonPrimitive(contentType))
                            put("text", JsonPrimitive(content))
                        }
                    )
                }
            )
        }
    }

    private fun buildHttpRequest(requestBody: String, stream: Boolean): Request {
        val builder = Request.Builder()
            .url(baseUrl)
            .header("Authorization", "Bearer $apiKey")
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

    private fun parseResponseBody(rawBody: String): LlmResponse {
        val root = json.parseToJsonElement(rawBody).jsonObject
        return parseResponseObject(root)
    }

    private fun parseResponseObject(root: JsonObject): LlmResponse {
        val outputItems = root["output"] as? JsonArray ?: JsonArray(emptyList())
        val assistantText = buildString {
            outputItems.forEach { item ->
                val obj = item as? JsonObject ?: return@forEach
                if (obj.stringOrNull("type") != "message") return@forEach
                val role = obj.stringOrNull("role").orEmpty()
                if (role != "assistant") return@forEach
                val content = obj["content"] as? JsonArray ?: return@forEach
                content.forEach { block ->
                    val blockObj = block as? JsonObject ?: return@forEach
                    val type = blockObj.stringOrNull("type").orEmpty()
                    if (type == "output_text" || type == "text") {
                        append(blockObj.stringOrNull("text").orEmpty())
                    }
                }
            }
        }
        val toolCalls = outputItems.mapNotNull { item ->
            val obj = item as? JsonObject ?: return@mapNotNull null
            if (obj.stringOrNull("type") != "function_call") return@mapNotNull null
            val id = obj.stringOrNull("call_id")
                ?: obj.stringOrNull("id")
                ?: return@mapNotNull null
            val name = obj.stringOrNull("name") ?: return@mapNotNull null
            ToolCall(
                id = id,
                name = name,
                argumentsJson = obj.stringOrNull("arguments").orEmpty().ifBlank { "{}" }
            )
        }
        val usage = root["usage"]?.jsonObject?.let { parseUsage(it) }
        return LlmResponse(
            assistant = AssistantMessage(
                content = assistantText,
                toolCalls = toolCalls
            ),
            usage = usage
        )
    }

    private fun parseUsage(usage: JsonObject): LlmUsage {
        val input = usage.longOrZero("input_tokens")
        val output = usage.longOrZero("output_tokens")
        val total = usage.longOrZero("total_tokens").takeIf { it > 0L } ?: (input + output)
        return LlmUsage(
            inputTokens = input.coerceAtLeast(0L),
            outputTokens = output.coerceAtLeast(0L),
            totalTokens = total.coerceAtLeast(0L)
        )
    }

    private data class NonStreamResult(
        val code: Int,
        val body: String,
        val isSuccessful: Boolean
    )

    private class ResponsesStreamAccumulator {
        private val text = StringBuilder()
        private val toolCallsByItemId = linkedMapOf<String, PartialToolCall>()

        fun hasOutput(): Boolean {
            return text.isNotEmpty() || toolCallsByItemId.isNotEmpty()
        }

        fun appendText(delta: String) {
            text.append(delta)
        }

        fun applyOutputItem(item: JsonObject?) {
            if (item == null) return
            if (item.stringOrNull("type") != "function_call") return
            val itemId = item.stringOrNull("id") ?: return
            val partial = toolCallsByItemId.getOrPut(itemId) { PartialToolCall() }
            partial.callId = item.stringOrNull("call_id") ?: partial.callId
            partial.name = item.stringOrNull("name") ?: partial.name
            item.stringOrNull("arguments")?.takeIf { it.isNotBlank() }?.let {
                partial.arguments.clear()
                partial.arguments.append(it)
            }
        }

        fun appendFunctionArguments(itemId: String?, delta: String) {
            val normalizedItemId = itemId?.trim().orEmpty()
            if (normalizedItemId.isBlank() || delta.isEmpty()) return
            val partial = toolCallsByItemId.getOrPut(normalizedItemId) { PartialToolCall() }
            partial.arguments.append(delta)
        }

        fun finishFunctionArguments(itemId: String?, arguments: String?) {
            val normalizedItemId = itemId?.trim().orEmpty()
            if (normalizedItemId.isBlank() || arguments.isNullOrBlank()) return
            val partial = toolCallsByItemId.getOrPut(normalizedItemId) { PartialToolCall() }
            partial.arguments.clear()
            partial.arguments.append(arguments)
        }

        fun toFinalResponse(): LlmResponse {
            val toolCalls = toolCallsByItemId.values.mapNotNull { partial ->
                val id = partial.callId ?: return@mapNotNull null
                val name = partial.name ?: return@mapNotNull null
                ToolCall(
                    id = id,
                    name = name,
                    argumentsJson = partial.arguments.toString().ifBlank { "{}" }
                )
            }
            return LlmResponse(
                assistant = AssistantMessage(
                    content = text.toString(),
                    toolCalls = toolCalls
                )
            )
        }

        private class PartialToolCall {
            var callId: String? = null
            var name: String? = null
            val arguments = StringBuilder()
        }
    }

    companion object {
        private const val TAG = "OpenAiResponsesProvider"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private const val MAX_ERROR_BODY_BYTES = 64L * 1024L
        private const val MAX_ERROR_TEXT_CHARS = 2000
    }
}

private fun JsonObject.stringOrNull(key: String): String? {
    return (this[key] as? JsonPrimitive)?.contentOrNull
}

private fun JsonObject.longOrZero(key: String): Long {
    return stringOrNull(key)?.toLongOrNull() ?: 0L
}
