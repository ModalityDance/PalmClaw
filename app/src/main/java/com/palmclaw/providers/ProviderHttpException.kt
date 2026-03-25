package com.palmclaw.providers

import java.io.IOException
import java.util.Locale

internal class ProviderHttpException(
    val providerLabel: String,
    val statusCode: Int,
    val responseBody: String,
    val streaming: Boolean = false
) : IOException(buildMessage(providerLabel, statusCode, responseBody, streaming)) {

    val requiresStreaming: Boolean
        get() {
            val detail = responseBody.lowercase(Locale.US)
            return detail.contains("stream must be set to true") ||
                detail.contains("stream=true") ||
                detail.contains("streaming only") ||
                detail.contains("only supports streaming")
        }

    val isRetryableCandidateFailure: Boolean
        get() = statusCode in RETRYABLE_STATUS_CODES

    companion object {
        private val RETRYABLE_STATUS_CODES = setOf(400, 404, 405, 415, 422)

        private fun buildMessage(
            providerLabel: String,
            statusCode: Int,
            responseBody: String,
            streaming: Boolean
        ): String {
            val phase = if (streaming) "stream HTTP" else "HTTP"
            val detail = responseBody.trim().take(MAX_BODY_CHARS)
            return if (detail.isBlank()) {
                "$providerLabel $phase $statusCode"
            } else {
                "$providerLabel $phase $statusCode: $detail"
            }
        }

        private const val MAX_BODY_CHARS = 2000
    }
}
