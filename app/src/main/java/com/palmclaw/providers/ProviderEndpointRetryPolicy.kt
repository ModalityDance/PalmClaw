package com.palmclaw.providers

import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.UnknownHostException
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

internal object ProviderEndpointRetryPolicy {
    fun shouldTryNext(
        profile: ProviderProfile,
        current: ProviderExecutionTarget,
        next: ProviderExecutionTarget,
        failure: Throwable
    ): Boolean {
        if (!allowsCredentialScope(profile, current, next, failure)) return false
        return when (failure) {
            is ConnectException,
            is UnknownHostException -> true
            is InterruptedIOException -> false
            is ProviderHttpException -> failure.statusCode in RETRYABLE_HTTP_STATUS_CODES
            else -> false
        }
    }

    private fun allowsCredentialScope(
        profile: ProviderProfile,
        current: ProviderExecutionTarget,
        next: ProviderExecutionTarget,
        failure: Throwable
    ): Boolean {
        val currentOrigin = origin(current.endpointUrl) ?: return false
        val nextOrigin = origin(next.endpointUrl) ?: return false
        if (currentOrigin == nextOrigin) return true
        if (!isCatalogTransition(profile, currentOrigin, nextOrigin)) return false
        val statusCode = (failure as? ProviderHttpException)?.statusCode
        return statusCode == null ||
            statusCode !in AUTH_STATUS_CODES ||
            profile.retryAuthFailuresAcrossTargets
    }

    private fun isCatalogTransition(
        profile: ProviderProfile,
        currentOrigin: Origin,
        nextOrigin: Origin
    ): Boolean {
        val catalogOrigins = (listOf(profile.baseUrl) + profile.alternateBaseUrls)
            .mapNotNull(::origin)
            .toSet()
        return currentOrigin in catalogOrigins && nextOrigin in catalogOrigins
    }

    private fun origin(url: String): Origin? {
        val parsed = url.toHttpUrlOrNull() ?: return null
        return parsed.toOrigin()
    }

    private fun HttpUrl.toOrigin() = Origin(
        scheme = scheme.lowercase(),
        host = host.lowercase(),
        port = port
    )

    private data class Origin(
        val scheme: String,
        val host: String,
        val port: Int
    )

    private val RETRYABLE_HTTP_STATUS_CODES = setOf(400, 401, 403, 404, 405, 415, 422)
    private val AUTH_STATUS_CODES = setOf(401, 403)
}
