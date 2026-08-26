package com.palmclaw.providers

import java.util.Locale
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

internal data class ProviderExecutionTarget(
    val protocol: ProviderProtocol,
    val endpointUrl: String
)

internal object ProviderEndpointPlanner {
    fun prioritizeCachedTarget(
        planned: List<ProviderExecutionTarget>,
        cached: ProviderExecutionTarget?,
        preserveFirst: Boolean
    ): List<ProviderExecutionTarget> {
        if (cached == null || cached !in planned) return planned
        if (preserveFirst && planned.firstOrNull() != cached) {
            return listOf(planned.first(), cached) + planned.drop(1).filterNot { it == cached }
        }
        return listOf(cached) + planned.filterNot { it == cached }
    }

    fun planTargets(
        profile: ProviderProfile,
        requestedProtocol: ProviderProtocol?,
        rawBaseUrl: String
    ): List<ProviderExecutionTarget> {
        val configuredUrl = rawBaseUrl.trim().ifBlank { profile.baseUrl }
        if (configuredUrl.isBlank()) return emptyList()

        val protocol = ProviderCatalog.resolveProtocol(
            rawProvider = profile.id,
            requested = requestedProtocol,
            baseUrl = configuredUrl
        )
        val usesCatalogDefault = sameUrl(configuredUrl, profile.baseUrl)
        val candidateUrls = buildList {
            add(
                EndpointInput(
                    url = configuredUrl,
                    kind = if (usesCatalogDefault) {
                        profile.endpointKind.toResolutionKind()
                    } else {
                        EndpointResolutionKind.Auto
                    }
                )
            )
            if (usesCatalogDefault) {
                profile.alternateBaseUrls.forEach { alternate ->
                    add(EndpointInput(alternate, profile.endpointKind.toResolutionKind()))
                }
            }
        }

        return candidateUrls
            .flatMap { input ->
                endpointCandidates(
                    rawUrl = input.url,
                    protocol = protocol,
                    kind = input.kind
                )
            }
            .map { endpoint ->
                ProviderExecutionTarget(
                    protocol = protocol,
                    endpointUrl = endpoint
                )
            }
            .distinctBy { "${it.protocol.wireValue}|${it.endpointUrl}" }
    }

    private fun endpointCandidates(
        rawUrl: String,
        protocol: ProviderProtocol,
        kind: EndpointResolutionKind
    ): List<String> {
        val inputUrl = rawUrl.trim().trimEnd('/')
        if (inputUrl.isBlank()) return emptyList()
        if (kind == EndpointResolutionKind.Exact) return listOf(inputUrl)

        val inputLower = inputUrl.lowercase(Locale.US)
        if (looksLikeProtocolEndpoint(inputLower, protocol)) {
            return listOf(inputUrl)
        }

        val derived = derivedEndpointCandidates(inputUrl, protocol)
        return when (kind) {
            EndpointResolutionKind.Base -> derived
            EndpointResolutionKind.Auto -> {
                if (looksLikeBaseUrl(inputUrl)) {
                    derived + inputUrl
                } else {
                    listOf(inputUrl) + derived
                }
            }
            EndpointResolutionKind.Exact -> listOf(inputUrl)
        }.map { it.trimEnd('/') }.distinct()
    }

    private fun derivedEndpointCandidates(
        baseUrl: String,
        protocol: ProviderProtocol
    ): List<String> {
        val root = baseUrl.trimEnd('/')
        val rootLower = root.lowercase(Locale.US)
        val suffixes = when (protocol) {
            ProviderProtocol.OpenAi -> when {
                rootLower.endsWith("/v1") -> listOf("/chat/completions")
                rootLower.endsWith("/chat") -> listOf("/completions")
                else -> listOf("/v1/chat/completions", "/chat/completions")
            }
            ProviderProtocol.OpenAiResponses -> when {
                rootLower.endsWith("/v1") -> listOf("/responses")
                else -> listOf("/v1/responses", "/responses")
            }
            ProviderProtocol.Anthropic -> when {
                rootLower.endsWith("/v1") -> listOf("/messages")
                else -> listOf("/v1/messages", "/messages")
            }
        }
        return suffixes.map { suffix -> "$root$suffix" }
    }

    private fun looksLikeProtocolEndpoint(
        url: String,
        protocol: ProviderProtocol
    ): Boolean {
        return when (protocol) {
            ProviderProtocol.OpenAi -> url.endsWith("/chat/completions")
            ProviderProtocol.OpenAiResponses -> url.endsWith("/responses")
            ProviderProtocol.Anthropic -> url.endsWith("/messages")
        }
    }

    private fun looksLikeBaseUrl(url: String): Boolean {
        val parsed = url.toHttpUrlOrNull() ?: return false
        val segments = parsed.pathSegments.filter { it.isNotBlank() }
        if (segments.isEmpty()) return true
        return VERSION_SEGMENT.matches(segments.last())
    }

    private fun sameUrl(first: String, second: String): Boolean {
        if (first.isBlank() || second.isBlank()) return false
        return first.trim().trimEnd('/').equals(
            second.trim().trimEnd('/'),
            ignoreCase = true
        )
    }

    private data class EndpointInput(
        val url: String,
        val kind: EndpointResolutionKind
    )

    private enum class EndpointResolutionKind {
        Exact,
        Base,
        Auto
    }

    private fun ProviderEndpointKind.toResolutionKind(): EndpointResolutionKind {
        return when (this) {
            ProviderEndpointKind.Exact -> EndpointResolutionKind.Exact
            ProviderEndpointKind.Base -> EndpointResolutionKind.Base
        }
    }

    private val VERSION_SEGMENT = Regex("""v\d+(?:beta\d*)?""", RegexOption.IGNORE_CASE)
}
