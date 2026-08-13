package com.palmclaw.channels

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.selects.select
import kotlin.random.Random

internal interface ChannelNetworkAvailability {
    val available: StateFlow<Boolean>
}

internal class MutableChannelNetworkAvailability(
    initiallyAvailable: Boolean = true
) : ChannelNetworkAvailability {
    private val mutableAvailable = MutableStateFlow(initiallyAvailable)
    override val available: StateFlow<Boolean> = mutableAvailable.asStateFlow()

    fun update(available: Boolean) {
        mutableAvailable.value = available
    }
}

internal object ProcessChannelNetworkAvailability : ChannelNetworkAvailability {
    private val delegate = MutableChannelNetworkAvailability()
    override val available: StateFlow<Boolean>
        get() = delegate.available

    fun update(available: Boolean) {
        delegate.update(available)
    }
}

internal class ChannelAdapterRuntimeHealth(
    private val channel: String,
    private val adapterKey: String,
    private val networkAvailability: ChannelNetworkAvailability =
        ProcessChannelNetworkAvailability,
    private val reconnectPolicy: ChannelReconnectPolicy = ChannelReconnectPolicy(
        jitter = FractionalChannelRetryJitter(
            unitSample = { Random.nextDouble() }
        )
    ),
    private val nowEpochMillis: () -> Long = System::currentTimeMillis,
    private val sleep: suspend (Long) -> Unit = { delayMillis -> delay(delayMillis) }
) {
    private val stateLock = Any()
    private var reconnectState = ChannelReconnectState()

    fun starting() {
        synchronized(stateLock) {
            reconnectState = reconnectPolicy.transition(
                reconnectState,
                ChannelReconnectEvent.Stopped
            ).state
        }
        ChannelRuntimeDiagnostics.reset(channel, adapterKey)
        ChannelRuntimeDiagnostics.markRunning(channel, adapterKey, true)
    }

    fun connected() {
        ChannelRuntimeDiagnostics.markConnected(channel, adapterKey, true)
    }

    fun authenticationSucceeded() {
        ChannelRuntimeDiagnostics.recordSuccessfulOperation(
            channel = channel,
            adapterKey = adapterKey,
            operation = ChannelOperation.AUTHENTICATION,
            atEpochMillis = nowEpochMillis()
        )
    }

    fun succeeded(operation: ChannelOperation) {
        if (operation.establishesInboundReadiness) {
            recordReadiness(operation, connectionObserved = true)
        } else {
            ChannelRuntimeDiagnostics.recordSuccessfulOperation(
                channel = channel,
                adapterKey = adapterKey,
                operation = operation,
                atEpochMillis = nowEpochMillis()
            )
        }
    }

    fun warning(operation: ChannelOperation, message: String) {
        ChannelRuntimeDiagnostics.markOperationWarning(
            channel = channel,
            adapterKey = adapterKey,
            operation = operation,
            error = ChannelRuntimeErrorNormalizer.normalize(message),
            atEpochMillis = nowEpochMillis()
        )
    }

    fun warning(operation: ChannelOperation, throwable: Throwable) {
        warning(operation, throwable.classificationText())
    }

    suspend fun <T> runOperation(
        operation: ChannelOperation,
        block: suspend () -> T
    ): T = try {
        block().also { succeeded(operation) }
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (throwable: Exception) {
        val error = ChannelRuntimeErrorNormalizer.normalize(throwable.classificationText())
        if (error.retryable) {
            ChannelRuntimeDiagnostics.markOperationWarning(
                channel = channel,
                adapterKey = adapterKey,
                operation = operation,
                error = error,
                atEpochMillis = nowEpochMillis()
            )
        } else {
            ChannelRuntimeDiagnostics.markBlocked(channel, adapterKey, error)
        }
        throw ChannelOperationFailedException(error)
    }

    fun failure(message: String) {
        failure(ChannelRuntimeErrorNormalizer.normalize(message))
    }

    fun failure(error: NormalizedChannelError) {
        if (error.retryable) {
            ChannelRuntimeDiagnostics.markConnected(channel, adapterKey, false)
            ChannelRuntimeDiagnostics.markError(channel, adapterKey, error)
        } else {
            ChannelRuntimeDiagnostics.markBlocked(channel, adapterKey, error)
        }
    }

    fun failure(throwable: Throwable) {
        failure(throwable.classificationText())
    }

    fun blocked(code: ChannelRuntimeErrorCode) {
        ChannelRuntimeDiagnostics.markBlocked(
            channel,
            adapterKey,
            NormalizedChannelError(code)
        )
    }

    suspend fun awaitReconnect(): Boolean {
        val error = ChannelRuntimeDiagnostics.getSnapshot(channel, adapterKey).error
            ?: NormalizedChannelError(ChannelRuntimeErrorCode.UNKNOWN)
        if (!error.retryable) {
            ChannelRuntimeDiagnostics.markBlocked(channel, adapterKey, error)
            return false
        }

        var transition = transition(
            ChannelReconnectEvent.Failure(
                atEpochMillis = nowEpochMillis(),
                networkAvailable = networkAvailability.available.value
            )
        )
        publishRetry(transition, error)

        while (true) {
            if (transition.retry == null) {
                networkAvailability.available.first { available -> available }
                transition = transition(
                    ChannelReconnectEvent.NetworkRestored(nowEpochMillis())
                )
                publishRetry(transition, error)
            }

            val retry = transition.retry ?: return false
            if (waitForRetryOrNetworkLoss(retry.delayMillis)) return true

            transition = transition(ChannelReconnectEvent.NetworkLost)
            publishRetry(transition, error)
        }
    }

    fun stopped() {
        synchronized(stateLock) {
            reconnectState = reconnectPolicy.transition(
                reconnectState,
                ChannelReconnectEvent.Stopped
            ).state
        }
        ChannelRuntimeDiagnostics.markStopped(channel, adapterKey)
    }

    private fun recordReadiness(
        operation: ChannelOperation,
        connectionObserved: Boolean
    ) {
        val now = nowEpochMillis()
        synchronized(stateLock) {
            reconnectState = reconnectPolicy.transition(
                reconnectState,
                ChannelReconnectEvent.Ready(now)
            ).state
        }
        ChannelRuntimeDiagnostics.markReadyAfterOperation(
            channel = channel,
            adapterKey = adapterKey,
            operation = operation,
            atEpochMillis = now,
            connectionObserved = connectionObserved
        )
    }

    private fun transition(event: ChannelReconnectEvent): ChannelReconnectTransition =
        synchronized(stateLock) {
            reconnectPolicy.transition(reconnectState, event)
                .also { transition -> reconnectState = transition.state }
        }

    private fun publishRetry(
        transition: ChannelReconnectTransition,
        error: NormalizedChannelError
    ) {
        ChannelRuntimeDiagnostics.markReconnecting(
            channel = channel,
            adapterKey = adapterKey,
            attempt = transition.state.attempt,
            nextRetryAtEpochMillis = transition.retry?.atEpochMillis,
            error = error
        )
    }

    private suspend fun waitForRetryOrNetworkLoss(delayMillis: Long): Boolean {
        if (!networkAvailability.available.value) return false
        if (delayMillis == 0L) {
            sleep(0L)
            return networkAvailability.available.value
        }

        return coroutineScope {
            val retryWait = async { sleep(delayMillis) }
            val networkLossWait = async {
                networkAvailability.available.first { available -> !available }
            }
            try {
                select {
                    retryWait.onAwait { networkAvailability.available.value }
                    networkLossWait.onAwait { false }
                }
            } finally {
                retryWait.cancel()
                networkLossWait.cancel()
            }
        }
    }
}

internal fun safeChannelErrorSummary(message: String): String =
    ChannelRuntimeErrorNormalizer.normalize(message).summary

internal fun safeChannelErrorSummary(throwable: Throwable): String =
    safeChannelErrorSummary(throwable.classificationText())

internal class ChannelOperationFailedException(
    val normalizedError: NormalizedChannelError
) : RuntimeException(normalizedError.summary)

private fun Throwable.classificationText(): String =
    "${javaClass.name} ${message.orEmpty()}"

private val ChannelOperation.establishesInboundReadiness: Boolean
    get() = when (this) {
        ChannelOperation.AUTHENTICATION, ChannelOperation.POLL, ChannelOperation.INBOUND -> true
        ChannelOperation.CONNECTION, ChannelOperation.OUTBOUND, ChannelOperation.HEARTBEAT -> false
    }
