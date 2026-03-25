package com.palmclaw.providers

data class ProviderProfile(
    val id: String,
    val title: String,
    val baseUrl: String,
    val defaultModel: String = "",
    val defaultProtocol: ProviderProtocol = ProviderProtocol.OpenAi,
    val supportedProtocols: List<ProviderProtocol> = emptyList(),
    val allowProtocolSelection: Boolean = false,
    val suggestedModels: List<String> = emptyList(),
    val extraHeaders: Map<String, String> = emptyMap(),
    val cacheMode: ProviderCacheMode = ProviderCacheMode.Auto
)

enum class ProviderCacheMode {
    Auto,
    ExplicitHint
}

object ProviderCatalog {
    private const val OPENAI_BASE_URL = "https://api.openai.com/v1/chat/completions"
    private const val ANTHROPIC_BASE_URL = "https://api.anthropic.com/v1/messages" // Corrected native endpoint
    private const val GOOGLE_BASE_URL = "https://generativelanguage.googleapis.com/v1beta/openai/chat/completions"
    private const val OPENROUTER_BASE_URL = "https://openrouter.ai/api/v1/chat/completions"
    private const val DEEPSEEK_BASE_URL = "https://api.deepseek.com/chat/completions"
    private const val GROQ_BASE_URL = "https://api.groq.com/openai/v1/chat/completions"
    private const val MINIMAX_BASE_URL = "https://api.minimax.io/v1/chat/completions"
    private const val DASHSCOPE_BASE_URL = "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions"
    private const val MOONSHOT_BASE_URL = "https://api.moonshot.cn/v1/chat/completions"
    private const val ZHIPU_BASE_URL = "https://open.bigmodel.cn/api/paas/v4/chat/completions"
    private const val VOLCENGINE_BASE_URL = "https://ark.cn-beijing.volces.com/api/v3/chat/completions"
    private const val BYTEPLUS_BASE_URL = "https://ark.ap-southeast.bytepluses.com/api/v3/chat/completions"
    private const val MISTRAL_BASE_URL = "https://api.mistral.ai/v1/chat/completions"
    private const val CUSTOM_BASE_URL = ""

    private val providers = listOf(
        ProviderProfile(
            id = "openai",
            title = "OpenAI",
            baseUrl = OPENAI_BASE_URL,
            defaultModel = "gpt-5.4-mini",
            suggestedModels = listOf(
                "gpt-5.4",
                "gpt-5.4-pro",
                "gpt-5.4-mini",
                "gpt-5.4-nano"
            )
        ),
        ProviderProfile(
            id = "anthropic",
            title = "Anthropic",
            baseUrl = ANTHROPIC_BASE_URL,
            defaultModel = "claude-sonnet-4-6",
            defaultProtocol = ProviderProtocol.Anthropic,
            suggestedModels = listOf(
                "claude-opus-4-6",
                "claude-sonnet-4-6",
                "claude-haiku-4-5"
            ),
            cacheMode = ProviderCacheMode.ExplicitHint
        ),
        ProviderProfile(
            id = "google",
            title = "Google AI",
            baseUrl = GOOGLE_BASE_URL,
            defaultModel = "gemini-3-flash-preview",
            suggestedModels = listOf(
                "gemini-3.1-pro-preview",
                "gemini-3-flash-preview",
                "gemini-3.1-flash-lite-preview"
            )
        ),
        ProviderProfile(
            id = "openrouter",
            title = "OpenRouter",
            baseUrl = OPENROUTER_BASE_URL,
            defaultModel = "openai/gpt-5.4-mini",
            suggestedModels = listOf(
                "openai/gpt-5.4-mini",
                "anthropic/claude-sonnet-4.6",
                "google/gemini-3-flash-preview",
                "deepseek/deepseek-chat"
            ),
            extraHeaders = mapOf(
                "HTTP-Referer" to "https://palmclaw.local",
                "X-Title" to "palmclaw"
            )
        ),
        ProviderProfile(
            id = "deepseek",
            title = "DeepSeek",
            baseUrl = DEEPSEEK_BASE_URL,
            defaultModel = "deepseek-chat",
            suggestedModels = listOf(
                "deepseek-chat",
                "deepseek-reasoner"
            )
        ),
        ProviderProfile(
            id = "groq",
            title = "Groq",
            baseUrl = GROQ_BASE_URL,
            defaultModel = "llama-3.3-70b-versatile",
            suggestedModels = listOf(
                "llama-3.3-70b-versatile",
                "llama-3.1-8b-instant",
                "qwen-qwq-32b",
                "openai/gpt-oss-20b"
            )
        ),
        ProviderProfile(
            id = "minimax",
            title = "MiniMax",
            baseUrl = MINIMAX_BASE_URL,
            defaultModel = "MiniMax-M2.7",
            suggestedModels = listOf(
                "MiniMax-M2.7",
                "MiniMax-M2.5",
                "MiniMax-M2.1",
                "MiniMax-M1",
                "MiniMax-Text-01"
            )
        ),
        ProviderProfile(
            id = "dashscope",
            title = "Alibaba Cloud (DashScope)",
            baseUrl = DASHSCOPE_BASE_URL,
            defaultModel = "qwen-plus",
            suggestedModels = listOf(
                "qwen-max",
                "qwen-plus",
                "qwen-turbo",
                "qwen-max-latest",
                "qwen-plus-latest",
                "qwen-turbo-latest"
            )
        ),
        ProviderProfile(
            id = "moonshot",
            title = "Moonshot AI",
            baseUrl = MOONSHOT_BASE_URL,
            defaultModel = "kimi-latest",
            suggestedModels = listOf(
                "kimi-latest",
                "kimi-k2-0905-preview",
                "moonshot-v1-8k",
                "moonshot-v1-32k",
                "moonshot-v1-128k"
            ),
            cacheMode = ProviderCacheMode.ExplicitHint
        ),
        ProviderProfile(
            id = "zhipu",
            title = "Zhipu AI",
            baseUrl = ZHIPU_BASE_URL,
            defaultModel = "glm-4.5-air",
            suggestedModels = listOf(
                "glm-4.5",
                "glm-4.5-air",
                "glm-4.5-airx",
                "glm-4.5-flash"
            )
        ),
        ProviderProfile(
            id = "volcengine",
            title = "Volcengine",
            baseUrl = VOLCENGINE_BASE_URL,
            defaultModel = "doubao-seed-1-6-251015",
            suggestedModels = listOf(
                "doubao-seed-1-6-251015",
                "doubao-seed-1-6-250615",
                "doubao-1-5-pro-32k-250115",
                "doubao-1-5-lite-32k-250115"
            )
        ),
        ProviderProfile(
            id = "byteplus",
            title = "BytePlus",
            baseUrl = BYTEPLUS_BASE_URL,
            defaultModel = "seed-2-0-lite-001",
            suggestedModels = listOf(
                "seed-2-0-lite-001",
                "seed-2-0-mini-001",
                "seed-1-8-251228",
                "seed-1-6-250915",
                "seed-1-6-flash-250715"
            )
        ),
        ProviderProfile(
            id = "mistral",
            title = "Mistral AI",
            baseUrl = MISTRAL_BASE_URL,
            defaultModel = "mistral-small-latest",
            suggestedModels = listOf(
                "mistral-large-latest",
                "mistral-medium-latest",
                "mistral-small-latest",
                "codestral-latest",
                "devstral-small-latest",
                "ministral-8b-latest",
                "ministral-3b-latest"
            )
        ),
        ProviderProfile(
            id = "custom",
            title = "Custom",
            baseUrl = CUSTOM_BASE_URL,
            defaultProtocol = ProviderProtocol.OpenAi,
            supportedProtocols = listOf(
                ProviderProtocol.OpenAi,
                ProviderProtocol.OpenAiResponses,
                ProviderProtocol.Anthropic
            ),
            allowProtocolSelection = true,
            defaultModel = "",
            suggestedModels = emptyList()
        )
    )

    private val byId = providers.associateBy { it.id }
    // Backward-compatible aliases for older labels saved in local config.
    private val aliases = mapOf(
        "claude" to "anthropic",
        "gemini" to "google",
        "google-gemini" to "google"
    )

    fun all(): List<ProviderProfile> = providers

    fun defaultBaseUrl(
        raw: String?,
        protocol: ProviderProtocol? = null
    ): String {
        val profile = resolve(raw)
        resolveProtocol(profile.id, protocol, baseUrl = null)
        return profile.baseUrl
    }

    fun defaultModel(
        raw: String?,
        protocol: ProviderProtocol? = null
    ): String {
        val profile = resolve(raw)
        resolveProtocol(profile.id, protocol, baseUrl = null)
        return profile.defaultModel
    }

    fun suggestedModels(raw: String?): List<String> = resolve(raw).suggestedModels

    fun defaultProtocol(raw: String?): ProviderProtocol = resolve(raw).defaultProtocol

    fun supportedProtocols(raw: String?): List<ProviderProtocol> {
        val profile = resolve(raw)
        return profile.supportedProtocols.ifEmpty { listOf(profile.defaultProtocol) }
    }

    fun allowsProtocolSelection(raw: String?): Boolean {
        val profile = resolve(raw)
        return profile.allowProtocolSelection && supportedProtocols(profile.id).size > 1
    }

    fun resolveProtocol(
        rawProvider: String?,
        requested: ProviderProtocol?,
        baseUrl: String? = null
    ): ProviderProtocol {
        val profile = resolve(rawProvider)
        val supported = supportedProtocols(profile.id)
        val inferred = inferProtocolFromBaseUrl(baseUrl)
        if (profile.allowProtocolSelection && inferred != null && inferred in supported) {
            return inferred
        }
        if (requested != null && requested in supported) {
            return if (profile.allowProtocolSelection) requested else profile.defaultProtocol
        }
        return profile.defaultProtocol
    }

    fun candidateProtocols(
        rawProvider: String?,
        requested: ProviderProtocol?,
        baseUrl: String? = null
    ): List<ProviderProtocol> {
        val profile = resolve(rawProvider)
        val supported = supportedProtocols(profile.id)
        if (!profile.allowProtocolSelection) {
            return listOf(resolveProtocol(profile.id, requested, baseUrl))
        }
        val explicitEndpointProtocol = inferProtocolFromExplicitEndpoint(baseUrl)
        if (explicitEndpointProtocol != null && explicitEndpointProtocol in supported) {
            return listOf(explicitEndpointProtocol)
        }
        val inferred = inferProtocolFromBaseUrl(baseUrl)
        return buildList {
            if (inferred != null && inferred in supported) add(inferred)
            if (requested != null && requested in supported) add(requested)
            addAll(supported)
        }.distinct()
    }

    fun resolve(raw: String?): ProviderProfile {
        val key = raw?.trim()?.lowercase().orEmpty()
        val normalized = aliases[key] ?: key
        return byId[normalized] ?: byId["openai"]!!
    }

    private fun inferProtocolFromBaseUrl(baseUrl: String?): ProviderProtocol? {
        val normalized = baseUrl?.trim()?.lowercase().orEmpty()
        if (normalized.isBlank()) return null
        return when {
            normalized.contains("/responses") -> ProviderProtocol.OpenAiResponses
            normalized.contains("/v1/messages") -> ProviderProtocol.Anthropic
            normalized.contains("anthropic.com") -> ProviderProtocol.Anthropic
            normalized.contains("/chat/completions") -> ProviderProtocol.OpenAi
            else -> null
        }
    }

    private fun inferProtocolFromExplicitEndpoint(baseUrl: String?): ProviderProtocol? {
        val normalized = baseUrl?.trim()?.trimEnd('/')?.lowercase().orEmpty()
        if (normalized.isBlank()) return null
        return when {
            normalized.endsWith("/responses") -> ProviderProtocol.OpenAiResponses
            normalized.endsWith("/v1/messages") || normalized.endsWith("/messages") -> ProviderProtocol.Anthropic
            normalized.endsWith("/chat/completions") -> ProviderProtocol.OpenAi
            else -> null
        }
    }
}
