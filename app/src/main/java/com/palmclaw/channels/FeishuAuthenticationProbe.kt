package com.palmclaw.channels

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import java.io.IOException
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal sealed interface FeishuTenantTokenResult {
    data class Success(
        val accessToken: String,
        val expiresInSeconds: Long
    ) : FeishuTenantTokenResult

    data class Failure(
        val error: NormalizedChannelError
    ) : FeishuTenantTokenResult
}

internal object FeishuTenantTokenResponseMapper {
    fun map(httpStatus: Int, responseBody: String): FeishuTenantTokenResult {
        val payload = parsePayload(responseBody)
        val businessCode = (payload
            ?.get("code") as? JsonPrimitive)
            ?.intOrNull
        val message = (payload
            ?.get("msg") as? JsonPrimitive)
            ?.contentOrNull
            .orEmpty()

        if (httpStatus !in 200..299 || businessCode != SUCCESS_CODE) {
            return FeishuTenantTokenResult.Failure(
                FeishuAuthenticationErrorMapper.fromResponse(
                    httpStatus = httpStatus,
                    message = message
                )
            )
        }

        val accessToken = (payload
            ?.get("tenant_access_token") as? JsonPrimitive)
            ?.contentOrNull
            ?.trim()
            .orEmpty()
        val expiresInSeconds = (payload
            ?.get("expire") as? JsonPrimitive)
            ?.longOrNull
            ?: DEFAULT_EXPIRY_SECONDS
        if (accessToken.isBlank() || expiresInSeconds <= 0L) {
            return FeishuTenantTokenResult.Failure(
                NormalizedChannelError(ChannelRuntimeErrorCode.PROTOCOL_ERROR)
            )
        }
        return FeishuTenantTokenResult.Success(
            accessToken = accessToken,
            expiresInSeconds = expiresInSeconds
        )
    }

    private fun parsePayload(responseBody: String): JsonObject? {
        if (responseBody.isBlank()) return null
        return runCatching {
            Json.parseToJsonElement(responseBody).jsonObject
        }.getOrNull()
    }

    private const val SUCCESS_CODE = 0
    private const val DEFAULT_EXPIRY_SECONDS = 7_200L
}

internal object FeishuAuthenticationErrorMapper {
    fun fromResponse(
        httpStatus: Int,
        message: String
    ): NormalizedChannelError {
        val normalizedMessage = message.trim().lowercase(Locale.US)
        val code = when {
            httpStatus == 401 || httpStatus == 403 ->
                ChannelRuntimeErrorCode.AUTHENTICATION_FAILED

            isAppCredentialFailure(normalizedMessage) ->
                ChannelRuntimeErrorCode.AUTHENTICATION_FAILED

            httpStatus == 429 ||
                isRateLimitFailure(normalizedMessage) ->
                ChannelRuntimeErrorCode.RATE_LIMITED

            httpStatus == 408 -> ChannelRuntimeErrorCode.NETWORK_UNAVAILABLE
            httpStatus in 400..499 -> ChannelRuntimeErrorCode.CONFIGURATION_INVALID
            httpStatus in 500..599 -> ChannelRuntimeErrorCode.UNKNOWN
            else -> ChannelRuntimeErrorCode.UNKNOWN
        }
        return NormalizedChannelError(code)
    }

    fun fromThrowable(throwable: Throwable): NormalizedChannelError {
        val causalChain = generateSequence(throwable) { current -> current.cause }
            .take(MAX_CAUSE_DEPTH)
            .toList()
        causalChain.filterIsInstance<FeishuTenantTokenRequestException>()
            .firstOrNull()
            ?.let { return it.error }
        val classificationText = causalChain.joinToString(separator = " ") { current ->
            "${current.javaClass.name} ${current.message.orEmpty()}"
        }
        val normalizedText = classificationText.lowercase(Locale.US)
        if (isAppCredentialFailure(normalizedText)) {
            return NormalizedChannelError(ChannelRuntimeErrorCode.AUTHENTICATION_FAILED)
        }
        if (causalChain.any { current -> current is IOException }) {
            return NormalizedChannelError(ChannelRuntimeErrorCode.NETWORK_UNAVAILABLE)
        }
        if (isRateLimitFailure(normalizedText)) {
            return NormalizedChannelError(ChannelRuntimeErrorCode.RATE_LIMITED)
        }
        return ChannelRuntimeErrorNormalizer.normalize(classificationText)
    }

    private fun isAppCredentialFailure(value: String): Boolean {
        val mentionsAppId = value.contains("app_id") ||
            value.contains("app id") ||
            value.contains("appid")
        val mentionsAppSecret = value.contains("app_secret") ||
            value.contains("app secret") ||
            value.contains("appsecret") ||
            value.contains("client secret")
        val rejected = value.contains("invalid") ||
            value.contains("incorrect") ||
            value.contains("wrong") ||
            value.contains("not exist") ||
            value.contains("does not exist")
        return rejected && (mentionsAppId || mentionsAppSecret)
    }

    private fun isRateLimitFailure(value: String): Boolean =
        value.contains("rate limit") ||
            value.contains("too many requests") ||
            value.contains("frequency limit") ||
            value.contains("qps limit")

    private const val MAX_CAUSE_DEPTH = 8
}

internal data class FeishuTokenHttpResponse(
    val statusCode: Int,
    val body: String
)

internal interface FeishuTokenHttpCallback {
    fun onResponse(response: FeishuTokenHttpResponse)
    fun onFailure(error: IOException)
}

internal interface FeishuTokenHttpCall {
    fun enqueue(callback: FeishuTokenHttpCallback)
    fun cancel()
}

internal suspend fun FeishuTokenHttpCall.awaitResponse(): FeishuTokenHttpResponse =
    suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { cancel() }
        try {
            enqueue(
                object : FeishuTokenHttpCallback {
                    override fun onResponse(response: FeishuTokenHttpResponse) {
                        if (continuation.isActive) {
                            continuation.resume(response)
                        }
                    }

                    override fun onFailure(error: IOException) {
                        if (continuation.isActive) {
                            continuation.resumeWithException(error)
                        }
                    }
                }
            )
        } catch (throwable: Throwable) {
            if (continuation.isActive) {
                continuation.resumeWithException(throwable)
            }
        }
    }

internal sealed interface FeishuAuthenticationProbeResult {
    data object Authenticated : FeishuAuthenticationProbeResult

    data class Failure(
        val error: NormalizedChannelError
    ) : FeishuAuthenticationProbeResult
}

internal fun interface FeishuAuthenticationProbe {
    suspend fun authenticate(): FeishuAuthenticationProbeResult
}

internal class FeishuTenantAccessTokenProbe(
    private val requestToken: suspend () -> String
) : FeishuAuthenticationProbe {
    override suspend fun authenticate(): FeishuAuthenticationProbeResult = try {
        if (requestToken().isBlank()) {
            FeishuAuthenticationProbeResult.Failure(
                NormalizedChannelError(ChannelRuntimeErrorCode.PROTOCOL_ERROR)
            )
        } else {
            FeishuAuthenticationProbeResult.Authenticated
        }
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (throwable: Throwable) {
        FeishuAuthenticationProbeResult.Failure(
            FeishuAuthenticationErrorMapper.fromThrowable(throwable)
        )
    }
}

internal class FeishuTenantTokenRequestException(
    val error: NormalizedChannelError
) : RuntimeException(error.summary)
