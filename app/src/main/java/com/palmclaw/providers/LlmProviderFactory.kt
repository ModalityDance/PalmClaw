package com.palmclaw.providers

import com.palmclaw.config.AppConfig
import com.palmclaw.config.AppLimits
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

internal class LlmProviderFactory(
    private val resolutionStore: ProviderResolutionStore? = ProviderResolutionStore.default()
) {
    fun create(config: AppConfig): LlmProvider {
        val profile = ProviderCatalog.resolve(config.providerName)
        val callTimeoutSeconds = config.llmCallTimeoutSeconds
            .coerceIn(AppLimits.MIN_LLM_CALL_TIMEOUT_SECONDS, AppLimits.MAX_LLM_CALL_TIMEOUT_SECONDS)
        val connectTimeoutSeconds = config.llmConnectTimeoutSeconds
            .coerceIn(AppLimits.MIN_LLM_CONNECT_TIMEOUT_SECONDS, AppLimits.MAX_LLM_CONNECT_TIMEOUT_SECONDS)
        val readTimeoutSeconds = config.llmReadTimeoutSeconds
            .coerceIn(AppLimits.MIN_LLM_READ_TIMEOUT_SECONDS, AppLimits.MAX_LLM_READ_TIMEOUT_SECONDS)
        val client = OkHttpClient.Builder()
            .callTimeout(callTimeoutSeconds.toLong(), TimeUnit.SECONDS)
            .connectTimeout(connectTimeoutSeconds.toLong(), TimeUnit.SECONDS)
            .readTimeout(readTimeoutSeconds.toLong(), TimeUnit.SECONDS)
            .build()
        return AdaptiveLlmProvider(
            profile = profile,
            config = config,
            client = client,
            resolutionStore = resolutionStore
        )
    }
}

