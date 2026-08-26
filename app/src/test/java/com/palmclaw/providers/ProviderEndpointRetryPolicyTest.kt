package com.palmclaw.providers

import java.net.SocketTimeoutException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderEndpointRetryPolicyTest {

    private val customProfile = ProviderCatalog.resolve("custom")

    @Test
    fun `same-origin auth rejection can try a derived path`() {
        assertTrue(
            ProviderEndpointRetryPolicy.shouldTryNext(
                profile = customProfile,
                current = target("https://gateway.example.com/vendor"),
                next = target("https://gateway.example.com/vendor/v1/chat/completions"),
                failure = httpFailure(401)
            )
        )
    }

    @Test
    fun `auth rejection never sends credentials to an unrelated origin`() {
        assertFalse(
            ProviderEndpointRetryPolicy.shouldTryNext(
                profile = customProfile,
                current = target("https://gateway.example.com/vendor"),
                next = target("https://other.example.com/v1/chat/completions"),
                failure = httpFailure(401)
            )
        )
    }

    @Test
    fun `uncertain timeout never replays a model request`() {
        assertFalse(
            ProviderEndpointRetryPolicy.shouldTryNext(
                profile = customProfile,
                current = target("https://gateway.example.com/vendor"),
                next = target("https://gateway.example.com/vendor/v1/chat/completions"),
                failure = SocketTimeoutException("timed out")
            )
        )
    }

    @Test
    fun `catalog-approved minimax origins retain auth fallback`() {
        assertTrue(
            ProviderEndpointRetryPolicy.shouldTryNext(
                profile = ProviderCatalog.resolve("minimax"),
                current = ProviderExecutionTarget(
                    ProviderProtocol.Anthropic,
                    "https://api.minimax.io/anthropic/v1/messages"
                ),
                next = ProviderExecutionTarget(
                    ProviderProtocol.Anthropic,
                    "https://api.minimaxi.com/anthropic/v1/messages"
                ),
                failure = httpFailure(401)
            )
        )
    }

    @Test
    fun `server failure never switches endpoint candidates`() {
        assertFalse(
            ProviderEndpointRetryPolicy.shouldTryNext(
                profile = customProfile,
                current = target("https://gateway.example.com/vendor"),
                next = target("https://gateway.example.com/vendor/v1/chat/completions"),
                failure = httpFailure(500)
            )
        )
    }

    private fun target(url: String) = ProviderExecutionTarget(
        protocol = ProviderProtocol.OpenAi,
        endpointUrl = url
    )

    private fun httpFailure(status: Int) = ProviderHttpException(
        providerLabel = "Custom",
        statusCode = status,
        responseBody = "failure"
    )
}
