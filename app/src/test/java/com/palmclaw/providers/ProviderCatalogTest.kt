package com.palmclaw.providers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderCatalogTest {

    @Test
    fun `resolve supports aliases and falls back to openai`() {
        assertEquals("anthropic", ProviderCatalog.resolve(" claude ").id)
        assertEquals("google", ProviderCatalog.resolve("google-gemini").id)
        assertEquals("openai", ProviderCatalog.resolve("unknown-provider").id)
    }

    @Test
    fun `resolveProtocol prefers inferred endpoint for custom providers`() {
        assertEquals(
            ProviderProtocol.Anthropic,
            ProviderCatalog.resolveProtocol(
                rawProvider = "custom",
                requested = ProviderProtocol.OpenAi,
                baseUrl = "https://proxy.example.com/v1/messages"
            )
        )
        assertEquals(
            ProviderProtocol.OpenAi,
            ProviderCatalog.resolveProtocol(
                rawProvider = "openai",
                requested = ProviderProtocol.Anthropic,
                baseUrl = "https://api.openai.com/v1/chat/completions"
            )
        )
    }

    @Test
    fun `custom provider keeps the selected protocol for unknown endpoint paths`() {
        assertEquals(
            ProviderProtocol.Anthropic,
            ProviderCatalog.resolveProtocol(
                rawProvider = "custom",
                requested = ProviderProtocol.Anthropic,
                baseUrl = "https://proxy.example.com/vendor/generate"
            )
        )
    }

    @Test
    fun `custom profile exposes protocol selection while fixed profiles do not`() {
        assertTrue(ProviderCatalog.allowsProtocolSelection("custom"))
        assertFalse(ProviderCatalog.allowsProtocolSelection("openai"))
    }

    @Test
    fun `minimax profile uses current anthropic endpoint and bearer-compatible auth`() {
        val profile = ProviderCatalog.resolve("minimax")

        assertEquals("https://api.minimax.io/anthropic", profile.baseUrl)
        assertEquals(ProviderProtocol.Anthropic, profile.defaultProtocol)
        assertEquals(AnthropicAuthMode.XApiKeyAndBearer, profile.anthropicAuthMode)
        assertTrue(profile.alternateBaseUrls.contains("https://api.minimaxi.com/anthropic"))
        assertTrue(profile.retryAuthFailuresAcrossTargets)
        assertTrue(profile.suggestedModels.contains("MiniMax-M2.7-highspeed"))
        assertFalse(profile.suggestedModels.contains("MiniMax-Text-01"))
    }

    @Test
    fun `perplexity profile uses the canonical sonar endpoint`() {
        val profile = ProviderCatalog.resolve("perplexity")

        assertTrue(ProviderCatalog.all().any { it.id == "perplexity" })
        assertEquals("Perplexity", profile.title)
        assertEquals("https://api.perplexity.ai/v1/sonar", profile.baseUrl)
        assertEquals(ProviderEndpointKind.Exact, profile.endpointKind)
        assertEquals(ProviderProtocol.OpenAi, profile.defaultProtocol)
        assertEquals("sonar", profile.defaultModel)
        assertEquals(
            listOf("sonar", "sonar-pro", "sonar-reasoning-pro", "sonar-deep-research"),
            profile.suggestedModels
        )
        assertEquals("perplexity", ProviderCatalog.resolve("pplx").id)
    }
}
