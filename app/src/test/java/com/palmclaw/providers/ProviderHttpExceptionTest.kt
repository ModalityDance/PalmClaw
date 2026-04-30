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
}
