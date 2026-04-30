package com.palmclaw.providers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderEndpointPlannerTest {

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
        assertTrue(
            targets.any {
                it.protocol == ProviderProtocol.OpenAi &&
                    it.endpointUrl == "https://gateway.example.com/v1/chat/completions"
            }
        )
        assertTrue(
            targets.any {
                it.protocol == ProviderProtocol.Anthropic &&
                    it.endpointUrl == "https://gateway.example.com/v1/messages"
            }
        )
        assertEquals(targets.distinct().size, targets.size)
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
