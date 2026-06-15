package com.palmclaw.providers

import java.util.Locale

internal data class ProviderExecutionTarget(
    val protocol: ProviderProtocol,
    val endpointUrl: String
)

internal object ProviderEndpointPlanner {
    fun planTargets(
        profile: ProviderProfile,
        requestedProtocol: ProviderProtocol?,
        rawBaseUrl: String
    ): List<ProviderExecutionTarget> {
        val baseUrl = rawBaseUrl.trim().ifBlank { profile.baseUrl }
        if (baseUrl.isBlank()) return emptyList()
        val protocols = ProviderCatalog.candidateProtocols(
            rawProvider = profile.id,
            requested = requestedProtocol,
            baseUrl = baseUrl
        )
        val baseUrls = buildList {
            add(baseUrl)
            addAll(profile.alternateBaseUrls)
        }.map { it.trim() }
            .filter { it.isNotBlank() }
            .distinctBy { it.trimEnd('/').lowercase(Locale.US) }
        return baseUrls
            .flatMap { candidateBaseUrl ->
                protocols.flatMap { protocol ->
                    endpointCandidates(candidateBaseUrl, protocol).map { endpoint ->
                        ProviderExecutionTarget(
                            protocol = protocol,
                            endpointUrl = endpoint
                        )
                    }
                }
            }
            .distinctBy { "${it.protocol.wireValue}|${it.endpointUrl}" }
    }

    private fun endpointCandidates(
        rawBaseUrl: String,
        protocol: ProviderProtocol
    ): List<String> {
        val inputUrl = rawBaseUrl.trim().trimEnd('/')
        if (inputUrl.isBlank()) return emptyList()
        val inputLower = inputUrl.lowercase(Locale.US)
        val baseRoot = stripKnownEndpointSuffix(inputUrl)
        val rootLower = baseRoot.lowercase(Locale.US)
        val rawMatchesProtocol = when (protocol) {
            ProviderProtocol.OpenAi -> looksLikeOpenAiChatEndpoint(inputLower)
            ProviderProtocol.OpenAiResponses -> looksLikeResponsesEndpoint(inputLower)
            ProviderProtocol.Anthropic -> looksLikeAnthropicEndpoint(inputLower)
        }
        val suffixes = when (protocol) {
            ProviderProtocol.OpenAi -> when {
                rawMatchesProtocol -> emptyList()
                rootLower.endsWith("/v1") -> listOf("/chat/completions")
                rootLower.endsWith("/chat") -> listOf("/completions")
                else -> listOf("/v1/chat/completions", "/chat/completions")
            }

            ProviderProtocol.OpenAiResponses -> when {
                rawMatchesProtocol -> emptyList()
                rootLower.endsWith("/v1") -> listOf("/responses")
                else -> listOf("/v1/responses", "/responses")
            }

            ProviderProtocol.Anthropic -> when {
                rawMatchesProtocol -> emptyList()
                rootLower.endsWith("/v1") -> listOf("/messages")
                else -> listOf("/v1/messages", "/messages")
            }
        }
        return buildList {
            if (rawMatchesProtocol) {
                add(inputUrl)
            }
            addAll(suffixes.map { suffix -> "$baseRoot$suffix" })
            if (!looksLikeKnownEndpoint(inputLower)) {
                add(inputUrl)
            }
        }.map { it.trimEnd('/') }.distinct()
    }

    private fun looksLikeOpenAiChatEndpoint(url: String): Boolean {
        return url.endsWith("/chat/completions")
    }

    private fun looksLikeResponsesEndpoint(url: String): Boolean {
        return url.endsWith("/responses")
    }

    private fun looksLikeAnthropicEndpoint(url: String): Boolean {
        return url.endsWith("/messages")
    }

    private fun looksLikeKnownEndpoint(url: String): Boolean {
        return looksLikeOpenAiChatEndpoint(url) ||
            looksLikeResponsesEndpoint(url) ||
            looksLikeAnthropicEndpoint(url)
    }

    private fun stripKnownEndpointSuffix(url: String): String {
        val trimmed = url.trim().trimEnd('/')
        val lower = trimmed.lowercase(Locale.US)
        return when {
            looksLikeOpenAiChatEndpoint(lower) -> trimmed.dropLast("/chat/completions".length)
            looksLikeResponsesEndpoint(lower) -> trimmed.dropLast("/responses".length)
            looksLikeAnthropicEndpoint(lower) -> trimmed.dropLast("/messages".length)
            else -> trimmed
        }.trimEnd('/')
    }
}
