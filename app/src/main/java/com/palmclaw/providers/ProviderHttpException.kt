package com.palmclaw.providers

import java.io.IOException
import java.util.Locale
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

internal class ProviderHttpException(
    val providerLabel: String,
    val statusCode: Int,
    val responseBody: String,
    val streaming: Boolean = false,
    endpointUrl: String? = null
) : IOException(buildMessage(providerLabel, statusCode, responseBody, streaming, endpointUrl)) {

    val isContinuationFailure: Boolean
        get() = isContinuationFailure(statusCode, responseBody)

    val requiresStreaming: Boolean
        get() {
            if (isContinuationFailure) return false
            val detail = responseBody.lowercase(Locale.US)
            return detail.contains("stream must be set to true") ||
                detail.contains("stream=true") ||
                detail.contains("streaming only") ||
                detail.contains("only supports streaming")
        }

    companion object {
        fun isContinuationFailure(statusCode: Int, responseBody: String): Boolean {
            if (statusCode != 400 && statusCode != 422) return false
            val detail = responseBody.lowercase(Locale.US)
            return detail.contains("thought_signature") || detail.contains("thoughtsignature")
        }

        private fun buildMessage(
            providerLabel: String,
            statusCode: Int,
            responseBody: String,
            streaming: Boolean,
            endpointUrl: String?
        ): String {
            if (isContinuationFailure(statusCode, responseBody)) {
                return "$providerLabel model continuation rejected (HTTP $statusCode): " +
                    "required thought signature is missing or invalid. " +
                    "This is a model protocol compatibility error, not a tool execution failure. " +
                    "Previously completed tool actions remain completed. Inspect their results before repeating any action. " +
                    "If this conversation predates the fix or used another model, start a new conversation."
            }
            val phase = if (streaming) "stream HTTP" else "HTTP"
            val sanitizedEndpoint = safeEndpoint(endpointUrl)
            val heading = "$providerLabel $phase $statusCode"
            val providerDetail = redactSensitiveText(responseBody)
                .trim()
                .take(MAX_BODY_CHARS)
            val detail = buildList {
                if (sanitizedEndpoint.isNotBlank()) add("endpoint=$sanitizedEndpoint")
                if (providerDetail.isNotBlank()) add(providerDetail)
            }.joinToString("; ")
            return if (detail.isBlank()) {
                heading
            } else {
                "$heading: $detail"
            }
        }

        private fun safeEndpoint(endpointUrl: String?): String {
            val parsed = endpointUrl?.trim()?.toHttpUrlOrNull() ?: return ""
            val host = if (':' in parsed.host) "[${parsed.host}]" else parsed.host
            val defaultPort = if (parsed.scheme.equals("https", ignoreCase = true)) 443 else 80
            val port = if (parsed.port == defaultPort) "" else ":${parsed.port}"
            return "$host$port${parsed.encodedPath}".take(MAX_ENDPOINT_CHARS)
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
        private const val MAX_ENDPOINT_CHARS = 240
        private val SECRET_PATTERNS = listOf(
            Regex(
                pattern = """(?i)(?<prefix>\bBearer\s+)[A-Za-z0-9._~+/=-]{8,}"""
            ),
            Regex(
                pattern = """(?i)(?<prefix>"(?:api[_-]?key|access[_-]?token|auth[_-]?token|token|secret|password|thought_?signature)"\s*:\s*")[^"]+"""
            ),
            Regex(
                pattern = """(?i)(?<prefix>\b(?:api[_-]?key|access[_-]?token|auth[_-]?token|token|secret|password|thought_?signature)\s*[:=]\s*)[^\s,;]+"""
            )
        )
    }
}
