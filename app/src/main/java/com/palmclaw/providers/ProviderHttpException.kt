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
            val detail = redactSensitiveText(responseBody)
                .trim()
                .take(MAX_BODY_CHARS)
            return if (detail.isBlank()) {
                "$providerLabel $phase $statusCode"
            } else {
                "$providerLabel $phase $statusCode: $detail"
            }
        }

        private fun redactSensitiveText(input: String): String {
            return SECRET_PATTERNS.fold(input) { current, pattern ->
                pattern.replace(current) { match ->
                    val prefix = match.groups["prefix"]?.value.orEmpty()
                    if (prefix.isBlank()) {
                        "[redacted]"
                    } else {
                        "$prefix[redacted]"
                    }
                }
            }
        }

        private const val MAX_BODY_CHARS = 500
        private val SECRET_PATTERNS = listOf(
            Regex(
                pattern = """(?i)(?<prefix>\bBearer\s+)[A-Za-z0-9._~+/=-]{8,}"""
            ),
            Regex(
                pattern = """(?i)(?<prefix>"(?:api[_-]?key|access[_-]?token|auth[_-]?token|token|secret|password)"\s*:\s*")[^"]+"""
            ),
            Regex(
                pattern = """(?i)(?<prefix>\b(?:api[_-]?key|access[_-]?token|auth[_-]?token|token|secret|password)\s*[:=]\s*)[^\s,;]+"""
            )
        )
    }
}
