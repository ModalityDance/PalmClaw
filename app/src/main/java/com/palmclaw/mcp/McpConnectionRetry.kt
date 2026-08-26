package com.palmclaw.mcp

import com.palmclaw.mcp.transport.McpTransportErrorCode
import com.palmclaw.mcp.transport.McpTransportException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlin.math.pow

/** Network state seam. Waiting is suspending and never polls. */
interface McpNetworkAvailability {
    fun isAvailable(scope: McpEndpointNetworkScope): Boolean

    suspend fun awaitAvailable(scope: McpEndpointNetworkScope)
}

object AlwaysOnlineMcpNetworkAvailability : McpNetworkAvailability {
    override fun isAvailable(scope: McpEndpointNetworkScope): Boolean = true

    override suspend fun awaitAvailable(scope: McpEndpointNetworkScope) = Unit
}

class StateFlowMcpNetworkAvailability(
    private val available: StateFlow<Boolean>
) : McpNetworkAvailability {
    override fun isAvailable(scope: McpEndpointNetworkScope): Boolean =
        scope != McpEndpointNetworkScope.EXTERNAL || available.value

    override suspend fun awaitAvailable(scope: McpEndpointNetworkScope) {
        if (scope == McpEndpointNetworkScope.EXTERNAL) {
            available.first { it }
        }
    }
}

internal fun interface McpRetryDelay {
    suspend fun wait(millis: Long)
}

internal object CoroutineMcpRetryDelay : McpRetryDelay {
    override suspend fun wait(millis: Long) {
        delay(millis)
    }
}

internal fun interface McpRetryJitter {
    /** Returns a normalized value in [0, 1]. */
    fun unitSample(): Double
}

internal object RandomMcpRetryJitter : McpRetryJitter {
    override fun unitSample(): Double = Math.random()
}

/** Pure connection retry decision. It is never used for an established session operation. */
internal data class McpConnectionRetryPolicy(
    val maxAttempts: Int = 3,
    val baseDelayMillis: Long = 500L,
    val maxDelayMillis: Long = 4_000L,
    val jitterRatio: Double = 0.2
) {
    init {
        require(maxAttempts >= 1) { "maxAttempts must be at least one" }
        require(baseDelayMillis >= 0L) { "baseDelayMillis must not be negative" }
        require(maxDelayMillis >= baseDelayMillis) { "maxDelayMillis must be at least baseDelayMillis" }
        require(jitterRatio in 0.0..1.0) { "jitterRatio must be between zero and one" }
    }

    fun delayBeforeNextAttempt(
        failedAttempt: Int,
        failure: Throwable,
        jitterUnit: Double
    ): Long? {
        if (failedAttempt >= maxAttempts || failedAttempt < 1) return null
        val transport = failure as? McpTransportException ?: return null
        if (!transport.recoverable || transport.code !in RETRYABLE_CODES) return null
        val exponential = baseDelayMillis.toDouble() * 2.0.pow((failedAttempt - 1).toDouble())
        val capped = exponential.coerceAtMost(maxDelayMillis.toDouble())
        val positiveJitter = jitterUnit.coerceIn(0.0, 1.0) * jitterRatio
        return (capped * (1.0 + positiveJitter)).toLong()
            .coerceAtMost(maxDelayMillis)
            .coerceAtLeast(0L)
    }

    fun isRetryable(failure: Throwable): Boolean {
        val transport = failure as? McpTransportException ?: return false
        return transport.recoverable && transport.code in RETRYABLE_CODES
    }

    private companion object {
        val RETRYABLE_CODES = setOf(
            McpTransportErrorCode.NETWORK,
            McpTransportErrorCode.TIMEOUT,
            McpTransportErrorCode.RATE_LIMITED,
            McpTransportErrorCode.SERVER_ERROR
        )
    }
}

internal data class McpBackgroundRecoveryPolicy(
    val baseDelayMillis: Long = 5_000L,
    val maxDelayMillis: Long = 300_000L,
    val jitterRatio: Double = 0.2
) {
    init {
        require(baseDelayMillis >= 0L) { "baseDelayMillis must not be negative" }
        require(maxDelayMillis >= baseDelayMillis) { "maxDelayMillis must be at least baseDelayMillis" }
        require(jitterRatio in 0.0..1.0) { "jitterRatio must be between zero and one" }
    }

    fun delayBeforeAttempt(attempt: Int, jitterUnit: Double): Long {
        require(attempt >= 1) { "attempt must be at least one" }
        val exponential = baseDelayMillis.toDouble() * 2.0.pow((attempt - 1).toDouble())
        val capped = exponential.coerceAtMost(maxDelayMillis.toDouble())
        val positiveJitter = jitterUnit.coerceIn(0.0, 1.0) * jitterRatio
        return (capped * (1.0 + positiveJitter)).toLong()
            .coerceAtMost(maxDelayMillis)
            .coerceAtLeast(0L)
    }
}
