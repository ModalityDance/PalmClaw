package com.palmclaw.providers

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class ToolCall(
    val id: String,
    val name: String,
    val argumentsJson: String
)

@Serializable
data class AssistantMessage(
    val content: String,
    val toolCalls: List<ToolCall> = emptyList()
)

@Serializable
data class LlmResponse(
    val assistant: AssistantMessage,
    val usage: LlmUsage? = null
)

@Serializable
data class LlmUsage(
    val inputTokens: Long = 0,
    val outputTokens: Long = 0,
    val totalTokens: Long = 0,
    val cachedInputTokens: Long = 0
)

@Serializable
data class ChatMessage(
    val role: String,
    val content: String,
    val toolCallId: String? = null,
    val toolCalls: List<ToolCall>? = null
)

data class ToolSpec(
    val name: String,
    val description: String,
    val parameters: JsonObject
)

@Serializable
data class LlmRequest(
    val model: String,
    val messages: List<ChatMessagePayload>,
    val tools: List<ToolSpecPayload>,
    val stream: Boolean? = null,
    @SerialName("cache_control") val cacheControl: CacheControlPayload? = null
)

@Serializable
data class CacheControlPayload(
    val type: String = "ephemeral"
)

@Serializable
data class ChatMessagePayload(
    val role: String,
    val content: String,
    @SerialName("tool_call_id") val toolCallId: String? = null,
    @SerialName("tool_calls") val toolCalls: List<ChatCompletionsResponse.ResponseToolCall>? = null
)

@Serializable
data class ToolSpecPayload(
    val type: String,
    val function: FunctionSpecPayload
)

@Serializable
data class FunctionSpecPayload(
    val name: String,
    val description: String,
    val parameters: JsonObject
)

@Serializable
data class ChatCompletionsResponse(
    val choices: List<Choice>,
    val usage: Usage? = null
) {
    @Serializable
    data class Choice(
        val message: ResponseMessage
    )

    @Serializable
    data class ResponseMessage(
        val role: String,
        val content: String? = null,
        @SerialName("tool_calls") val toolCalls: List<ResponseToolCall>? = null
    )

    @Serializable
    data class ResponseToolCall(
        val id: String,
        val type: String? = null,
        val function: ResponseToolFunction
    )

    @Serializable
    data class ResponseToolFunction(
        val name: String,
        val arguments: String
    )

    @Serializable
    data class Usage(
        @SerialName("prompt_tokens") val promptTokens: Long? = null,
        @SerialName("completion_tokens") val completionTokens: Long? = null,
        @SerialName("total_tokens") val totalTokens: Long? = null,
        @SerialName("prompt_tokens_details") val promptTokensDetails: PromptTokensDetails? = null
    )

    @Serializable
    data class PromptTokensDetails(
        @SerialName("cached_tokens") val cachedTokens: Long? = null
    )
}

@Serializable
data class ChatCompletionChunk(
    val choices: List<ChoiceChunk> = emptyList()
) {
    @Serializable
    data class ChoiceChunk(
        val delta: Delta = Delta(),
        @SerialName("finish_reason") val finishReason: String? = null
    )

    @Serializable
    data class Delta(
        val role: String? = null,
        val content: String? = null,
        @SerialName("tool_calls") val toolCalls: List<DeltaToolCall>? = null
    )

    @Serializable
    data class DeltaToolCall(
        val index: Int? = null,
        val id: String? = null,
        val function: DeltaToolFunction? = null
    )

    @Serializable
    data class DeltaToolFunction(
        val name: String? = null,
        val arguments: String? = null
    )
}

