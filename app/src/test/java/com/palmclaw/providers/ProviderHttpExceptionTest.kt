package com.palmclaw.providers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderHttpExceptionTest {

    @Test
    fun `requiresStreaming detects streaming-only provider responses`() {
        val error = ProviderHttpException(
            providerLabel = "OpenAI",
            statusCode = 400,
            responseBody = "This endpoint only supports streaming. stream must be set to true."
        )

        assertTrue(error.requiresStreaming)
        assertTrue(error.isRetryableCandidateFailure)
        assertTrue(error.message!!.contains("OpenAI HTTP 400"))
    }

    @Test
    fun `non streaming hint and server errors are not treated as streaming retries`() {
        val error = ProviderHttpException(
            providerLabel = "Anthropic",
            statusCode = 500,
            responseBody = "temporary upstream failure",
            streaming = true
        )

        assertFalse(error.requiresStreaming)
        assertFalse(error.isRetryableCandidateFailure)
        assertEquals("Anthropic stream HTTP 500: temporary upstream failure", error.message)
    }

    @Test
    fun `message redacts credentials from provider response body`() {
        val error = ProviderHttpException(
            providerLabel = "OpenAI",
            statusCode = 401,
            responseBody = """
                Authorization: Bearer sk-live-secret-token
                {"api_key":"sk-json-secret","access_token":"token-value","message":"bad key"}
            """.trimIndent()
        )

        val message = error.message.orEmpty()
        assertTrue(message.contains("OpenAI HTTP 401"))
        assertTrue(message.contains("bad key"))
        assertFalse(message.contains("sk-live-secret-token"))
        assertFalse(message.contains("sk-json-secret"))
        assertFalse(message.contains("token-value"))
    }
}
