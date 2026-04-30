package com.palmclaw.providers

import com.palmclaw.config.AppConfig
import kotlinx.coroutines.flow.Flow
import java.io.InterruptedIOException
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap
import okhttp3.OkHttpClient

internal class AdaptiveLlmProvider(
    private val profile: ProviderProfile,
    private val config: AppConfig,
    private val client: OkHttpClient,
    private val resolutionStore: ProviderResolutionStore? = null
) : LlmProvider {

    override suspend fun chat(messages: List<ChatMessage>, toolsSpec: List<ToolSpec>): LlmResponse {
        val targets = plannedTargets()
        var lastFailure: Throwable? = null
        for ((index, target) in targets.withIndex()) {
            try {
                val response = createDelegate(target).chat(messages, toolsSpec)
                rememberSuccessfulTarget(target)
                return response
            } catch (t: Throwable) {
                lastFailure = t
                if (!shouldTryNextCandidate(t, index, targets.lastIndex)) {
                    throw t
                }
            }
        }
        throw lastFailure ?: IOException("${profile.title} request failed.")
    }

    override fun chatStream(messages: List<ChatMessage>, toolsSpec: List<ToolSpec>): Flow<LlmStreamEvent> {
        val target = plannedTargets().firstOrNull()
            ?: throw IllegalStateException("No usable endpoint target resolved.")
        return createDelegate(target).chatStream(messages, toolsSpec)
    }

    private fun plannedTargets(): List<ProviderExecutionTarget> {
        val planned = ProviderEndpointPlanner.planTargets(
            profile = profile,
            requestedProtocol = config.providerProtocol,
            rawBaseUrl = config.baseUrl
        )
        val cached = successfulTargetCache[cacheKey()]
            ?: resolutionStore?.load(cacheKey())
        return if (cached == null) {
            planned
        } else {
            listOf(cached) + planned.filterNot {
                it.protocol == cached.protocol && it.endpointUrl == cached.endpointUrl
            }
        }
    }

    private fun createDelegate(target: ProviderExecutionTarget): LlmProvider {
        return when (target.protocol) {
            ProviderProtocol.OpenAi -> OpenAiCompatibleProvider(
                providerLabel = profile.title,
                apiKey = config.apiKey,
                model = config.model,
                client = client,
                baseUrl = target.endpointUrl,
                extraHeaders = profile.extraHeaders,
                cacheMode = profile.cacheMode
            )

            ProviderProtocol.OpenAiResponses -> OpenAiResponsesProvider(
                providerLabel = profile.title,
                apiKey = config.apiKey,
                model = config.model,
                client = client,
                baseUrl = target.endpointUrl,
                extraHeaders = profile.extraHeaders
            )

            ProviderProtocol.Anthropic -> AnthropicCompatibleProvider(
                providerLabel = profile.title,
                apiKey = config.apiKey,
                model = config.model,
                client = client,
                baseUrl = target.endpointUrl,
                extraHeaders = profile.extraHeaders,
                authMode = profile.anthropicAuthMode
            )
        }
    }

    private fun shouldTryNextCandidate(
        throwable: Throwable,
        index: Int,
        lastIndex: Int
    ): Boolean {
        if (index >= lastIndex) return false
        if (throwable is SocketTimeoutException || throwable is InterruptedIOException) return true
        if (throwable is ConnectException || throwable is UnknownHostException) return true
        val httpFailure = throwable as? ProviderHttpException ?: return false
        if (profile.retryAuthFailuresAcrossTargets && httpFailure.statusCode == 401) return true
        return httpFailure.isRetryableCandidateFailure
    }

    private fun rememberSuccessfulTarget(target: ProviderExecutionTarget) {
        successfulTargetCache[cacheKey()] = target
        resolutionStore?.remember(cacheKey(), target)
    }

    private fun cacheKey(): String {
        return ProviderResolutionStore.cacheKeyFor(
            configId = config.activeProviderConfigId,
            providerName = profile.id,
            baseUrl = config.baseUrl,
            model = config.model
        )
    }

    companion object {
        private val successfulTargetCache = ConcurrentHashMap<String, ProviderExecutionTarget>()

        fun clearRememberedTargets(prefix: String) {
            val normalizedPrefix = prefix.trim()
            if (normalizedPrefix.isBlank()) return
            successfulTargetCache.keys
                .filter { it.startsWith(normalizedPrefix) }
                .forEach { successfulTargetCache.remove(it) }
        }
    }
}
