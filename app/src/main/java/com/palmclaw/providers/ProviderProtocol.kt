package com.palmclaw.providers

import kotlinx.serialization.Serializable

@Serializable
enum class ProviderProtocol(val wireValue: String) {
    OpenAi("openai"),
    OpenAiResponses("openai-responses"),
    Anthropic("anthropic");

    companion object {
        fun fromRaw(raw: String?): ProviderProtocol? {
            return when (raw?.trim()?.lowercase()) {
                OpenAi.wireValue -> OpenAi
                "openai-chat" -> OpenAi
                OpenAiResponses.wireValue -> OpenAiResponses
                "responses" -> OpenAiResponses
                Anthropic.wireValue -> Anthropic
                else -> null
            }
        }
    }
}
