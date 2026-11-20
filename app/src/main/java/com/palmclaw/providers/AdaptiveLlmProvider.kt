package com.palmclaw.providers

import com.palmclaw.config.AppConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.onEach
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import okhttp3.OkHttpClient

internal class AdaptiveLlmProvider(
    private val profile: ProviderProfile,
    private val config: AppConfig,
    private val client: OkHttpClient,
    private val resolutionStore: ProviderResolutionStore? = null,
    private val delegateFactory: ((ProviderExecutionTarget) -> LlmProvider)? = null
) : LlmProvider {

    @Volatile
    private var pinnedTarget: ProviderExecutionTarget? = null

    override suspend fun chat(messages: List<ChatMessage>, toolsSpec: List<ToolSpec>): LlmResponse {
        val targets = plannedTargets()
        var lastFailure: Throwable? = null
        for ((index, target) in targets.withIndex()) {
            try {
                validateTarget(messages, target)
                val response = createDelegate(target).chat(messages, toolsSpec)
                pinnedTarget = target
                rememberSuccessfulTarget(target)
                return response
            } catch (t: Throwable) {
                lastFailure = t
                val nextTarget = targets.getOrNull(index + 1)
                if (nextTarget == null || !ProviderEndpointRetryPolicy.shouldTryNext(
                        profile = profile,
                        current = target,
                        next = nextTarget,
                        failure = t
                    )
                ) {
                    throw t
                }
            }
        }
        throw lastFailure ?: IOException("${profile.title} request failed.")
    }

    override fun chatStream(messages: List<ChatMessage>, toolsSpec: List<ToolSpec>): Flow<LlmStreamEvent> {
        val target = plannedTargets().firstOrNull()
            ?: throw IllegalStateException("No usable endpoint target resolved.")
        validateTarget(messages, target)
        return createDelegate(target).chatStream(messages, toolsSpec).onEach { event ->
            if (event is LlmStreamEvent.Final) {
                pinnedTarget = target
                rememberSuccessfulTarget(target)
            }
        }
    }

    private fun plannedTargets(): List<ProviderExecutionTarget> {
        pinnedTarget?.let { return listOf(it) }
        val planned = ProviderEndpointPlanner.planTargets(
            profile = profile,
            requestedProtocol = config.providerProtocol,
            rawBaseUrl = config.baseUrl
        )
        val cached = successfulTargetCache[cacheKey()]
            ?: resolutionStore?.load(cacheKey())
        val configuredUrl = config.baseUrl.trim().trimEnd('/')
        val preserveFirst = configuredUrl.isNotBlank() &&
            planned.firstOrNull()?.endpointUrl == configuredUrl
        return ProviderEndpointPlanner.prioritizeCachedTarget(
            planned = planned,
            cached = cached,
            preserveFirst = preserveFirst
        )
    }

    private fun createDelegate(target: ProviderExecutionTarget): LlmProvider {
        delegateFactory?.let { return it(target) }
        return when (target.protocol) {
            ProviderProtocol.OpenAi -> OpenAiCompatibleProvider(
                providerLabel = profile.title,
                apiKey = config.apiKey,
                model = config.model,
                client = client,
                baseUrl = target.endpointUrl,
                extraHeaders = profile.extraHeaders,
                cacheMode = profile.cacheMode,
                providerIdentity = "${profile.id}:${config.activeProviderConfigId}"
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

    private fun validateTarget(messages: List<ChatMessage>, target: ProviderExecutionTarget) {
        val scope = if (target.protocol == ProviderProtocol.OpenAi) {
            OpenAiMessageCodec.scope("${profile.id}:${config.activeProviderConfigId}", target.endpointUrl, config.model)
        } else null
        validateContinuationScope(messages, scope)
    }

    private fun rememberSuccessfulTarget(target: ProviderExecutionTarget) {
        successfulTargetCache[cacheKey()] = target
        resolutionStore?.remember(cacheKey(), target)
    }

    private fun cacheKey(): String {
        val resolvedProtocol = ProviderCatalog.resolveProtocol(
            rawProvider = profile.id,
            requested = config.providerProtocol,
            baseUrl = config.baseUrl
        )
        return ProviderResolutionStore.cacheKeyFor(
            configId = config.activeProviderConfigId,
            providerName = profile.id,
            protocol = resolvedProtocol,
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
