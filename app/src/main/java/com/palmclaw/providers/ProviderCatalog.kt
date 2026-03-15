package com.palmclaw.providers

data class ProviderProfile(
    val id: String,
    val title: String,
    val baseUrl: String,
    val extraHeaders: Map<String, String> = emptyMap(),
    val cacheMode: ProviderCacheMode = ProviderCacheMode.Auto
)

enum class ProviderCacheMode {
    Auto,
    ExplicitHint
}

object ProviderCatalog {
    private const val OPENAI_BASE_URL = "https://api.openai.com/v1/chat/completions"
    private const val ANTHROPIC_BASE_URL = "https://api.anthropic.com/v1/messages"
    private const val GOOGLE_BASE_URL = "https://generativelanguage.googleapis.com/v1beta/openai/chat/completions"
    private const val OPENROUTER_BASE_URL = "https://openrouter.ai/api/v1/chat/completions"
    private const val DEEPSEEK_BASE_URL = "https://api.deepseek.com/chat/completions"
    private const val GROQ_BASE_URL = "https://api.groq.com/openai/v1/chat/completions"
    private const val MINIMAX_BASE_URL = "https://api.minimax.io/v1/text/chatcompletion_v2"
    private const val DASHSCOPE_BASE_URL = "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions"
    private const val MOONSHOT_BASE_URL = "https://api.moonshot.ai/v1/chat/completions"
    private const val ZHIPU_BASE_URL = "https://open.bigmodel.cn/api/paas/v4/chat/completions"
    private const val CUSTOM_BASE_URL = ""

    private val providers = listOf(
        ProviderProfile(
            id = "openai",
            title = "OpenAI",
            baseUrl = OPENAI_BASE_URL
        ),
        ProviderProfile(
            id = "anthropic",
            title = "Anthropic",
            baseUrl = ANTHROPIC_BASE_URL,
            cacheMode = ProviderCacheMode.ExplicitHint
        ),
        ProviderProfile(
            id = "google",
            title = "Google AI",
            baseUrl = GOOGLE_BASE_URL
        ),
        ProviderProfile(
            id = "openrouter",
            title = "OpenRouter",
            baseUrl = OPENROUTER_BASE_URL,
            extraHeaders = mapOf(
                "HTTP-Referer" to "https://palmclaw.local",
                "X-Title" to "palmclaw"
            )
        ),
        ProviderProfile(
            id = "deepseek",
            title = "DeepSeek",
            baseUrl = DEEPSEEK_BASE_URL
        ),
        ProviderProfile(
            id = "groq",
            title = "Groq",
            baseUrl = GROQ_BASE_URL
        ),
        ProviderProfile(
            id = "minimax",
            title = "MiniMax",
            baseUrl = MINIMAX_BASE_URL
        ),
        ProviderProfile(
            id = "dashscope",
            title = "Alibaba Cloud (DashScope)",
            baseUrl = DASHSCOPE_BASE_URL
        ),
        ProviderProfile(
            id = "moonshot",
            title = "Moonshot AI",
            baseUrl = MOONSHOT_BASE_URL,
            cacheMode = ProviderCacheMode.ExplicitHint
        ),
        ProviderProfile(
            id = "zhipu",
            title = "Zhipu AI",
            baseUrl = ZHIPU_BASE_URL
        ),
        ProviderProfile(
            id = "custom",
            title = "Custom",
            baseUrl = CUSTOM_BASE_URL
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

    fun defaultBaseUrl(raw: String?): String = resolve(raw).baseUrl

    fun resolve(raw: String?): ProviderProfile {
        val key = raw?.trim()?.lowercase().orEmpty()
        val normalized = aliases[key] ?: key
        return byId[normalized] ?: byId["openai"]!!
    }
}
