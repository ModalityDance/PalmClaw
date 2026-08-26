package com.palmclaw.providers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderEndpointPlannerTest {

    @Test
    fun `cached target is reused only within the current plan and declared priority`() {
        val first = ProviderExecutionTarget(
            protocol = ProviderProtocol.OpenAi,
            endpointUrl = "https://gateway.example.com/v1/chat/completions"
        )
        val second = ProviderExecutionTarget(
            protocol = ProviderProtocol.OpenAi,
            endpointUrl = "https://gateway.example.com/chat/completions"
        )
        val stale = ProviderExecutionTarget(
            protocol = ProviderProtocol.OpenAi,
            endpointUrl = "https://old-gateway.example.com/v1/chat/completions"
        )

        assertEquals(
            listOf(second, first),
            ProviderEndpointPlanner.prioritizeCachedTarget(
                planned = listOf(first, second),
                cached = second,
                preserveFirst = false
            )
        )
        assertEquals(
            listOf(first, second),
            ProviderEndpointPlanner.prioritizeCachedTarget(
                planned = listOf(first, second),
                cached = second,
                preserveFirst = true
            )
        )
        assertEquals(
            listOf(first, second),
            ProviderEndpointPlanner.prioritizeCachedTarget(
                planned = listOf(first, second),
                cached = stale,
                preserveFirst = false
            )
        )
    }

    @Test
    fun `planTargets keeps explicit anthropic endpoint unchanged`() {
        val targets = ProviderEndpointPlanner.planTargets(
            profile = ProviderCatalog.resolve("custom"),
            requestedProtocol = ProviderProtocol.OpenAi,
            rawBaseUrl = "https://gateway.example.com/v1/messages"
        )

        assertEquals(1, targets.size)
        assertEquals(
            ProviderExecutionTarget(
                protocol = ProviderProtocol.Anthropic,
                endpointUrl = "https://gateway.example.com/v1/messages"
            ),
            targets.single()
        )
    }

    @Test
    fun `planTargets generates endpoint candidates without duplicate protocol-endpoint pairs`() {
        val targets = ProviderEndpointPlanner.planTargets(
            profile = ProviderCatalog.resolve("custom"),
            requestedProtocol = ProviderProtocol.OpenAiResponses,
            rawBaseUrl = "https://gateway.example.com"
        )

        assertTrue(
            targets.any {
                it.protocol == ProviderProtocol.OpenAiResponses &&
                    it.endpointUrl == "https://gateway.example.com/v1/responses"
            }
        )
        assertTrue(targets.all { it.protocol == ProviderProtocol.OpenAiResponses })
        assertEquals(targets.distinct().size, targets.size)
    }

    @Test
    fun `perplexity catalog endpoint is never expanded`() {
        val targets = ProviderEndpointPlanner.planTargets(
            profile = ProviderCatalog.resolve("perplexity"),
            requestedProtocol = ProviderProtocol.OpenAi,
            rawBaseUrl = "https://api.perplexity.ai/v1/sonar"
        )

        assertEquals(
            listOf(
                ProviderExecutionTarget(
                    protocol = ProviderProtocol.OpenAi,
                    endpointUrl = "https://api.perplexity.ai/v1/sonar"
                )
            ),
            targets
        )
    }

    @Test
    fun `custom perplexity endpoint is also tried unchanged first`() {
        val targets = ProviderEndpointPlanner.planTargets(
            profile = ProviderCatalog.resolve("custom"),
            requestedProtocol = ProviderProtocol.OpenAi,
            rawBaseUrl = "https://api.perplexity.ai/v1/sonar"
        )

        assertEquals("https://api.perplexity.ai/v1/sonar", targets.first().endpointUrl)
        assertTrue(targets.all { it.protocol == ProviderProtocol.OpenAi })
    }

    @Test
    fun `unknown custom endpoint is tried unchanged before derived paths`() {
        val targets = ProviderEndpointPlanner.planTargets(
            profile = ProviderCatalog.resolve("custom"),
            requestedProtocol = ProviderProtocol.OpenAi,
            rawBaseUrl = "https://gateway.example.com/vendor/generate"
        )

        assertEquals("https://gateway.example.com/vendor/generate", targets.first().endpointUrl)
        assertTrue(targets.all { it.protocol == ProviderProtocol.OpenAi })
        assertTrue(
            targets.any {
                it.endpointUrl == "https://gateway.example.com/vendor/generate/v1/chat/completions"
            }
        )
    }

    @Test
    fun `planTargets preserves already normalized openai chat endpoint`() {
        val targets = ProviderEndpointPlanner.planTargets(
            profile = ProviderCatalog.resolve("openai"),
            requestedProtocol = ProviderProtocol.Anthropic,
            rawBaseUrl = "https://api.openai.com/v1/chat/completions/"
        )

        assertEquals(
            listOf(
                ProviderExecutionTarget(
                    protocol = ProviderProtocol.OpenAi,
                    endpointUrl = "https://api.openai.com/v1/chat/completions"
                )
            ),
            targets
        )
    }

    @Test
    fun `planTargets includes minimax global and cn anthropic endpoints`() {
        val targets = ProviderEndpointPlanner.planTargets(
            profile = ProviderCatalog.resolve("minimax"),
            requestedProtocol = ProviderProtocol.Anthropic,
            rawBaseUrl = ""
        )

        assertTrue(
            targets.any {
                it.protocol == ProviderProtocol.Anthropic &&
                    it.endpointUrl == "https://api.minimax.io/anthropic/v1/messages"
            }
        )
        assertTrue(
            targets.any {
                it.protocol == ProviderProtocol.Anthropic &&
                    it.endpointUrl == "https://api.minimaxi.com/anthropic/v1/messages"
            }
        )
    }
}
