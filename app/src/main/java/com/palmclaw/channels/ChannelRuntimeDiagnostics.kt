package com.palmclaw.channels

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

data class ChannelRuntimeBindingId(
    val channel: String,
    val adapterKey: String
)

data class ChannelRuntimeSnapshot(
    val running: Boolean = false,
    val connected: Boolean = false,
    val ready: Boolean = false,
    val lastError: String = "",
    val state: ChannelBindingHealthState = deriveChannelBindingHealthState(
        running = running,
        connected = connected,
        ready = ready,
        lastError = lastError
    ),
    val lastSuccessfulOperation: ChannelSuccessfulOperation? = null,
    val retryAttempt: Int = 0,
    val nextRetryAtEpochMillis: Long? = null,
    val error: NormalizedChannelError? = null,
    val lastOperationWarning: ChannelOperationWarning? = null
)

fun interface ChannelRuntimeSnapshotSource {
    fun getSnapshot(channel: String, adapterKey: String): ChannelRuntimeSnapshot
}

object ProcessChannelRuntimeSnapshotSource : ChannelRuntimeSnapshotSource {
    override fun getSnapshot(channel: String, adapterKey: String): ChannelRuntimeSnapshot =
        ChannelRuntimeDiagnostics.getSnapshot(channel, adapterKey)
}

object ChannelRuntimeDiagnostics {
    private val lock = Any()
    private val mutableState =
        MutableStateFlow<Map<ChannelRuntimeBindingId, ChannelRuntimeSnapshot>>(emptyMap())
    val state: StateFlow<Map<ChannelRuntimeBindingId, ChannelRuntimeSnapshot>> =
        mutableState.asStateFlow()

    fun reset(channel: String, adapterKey: String) = synchronized(lock) {
        mutableState.value = mutableState.value + (key(channel, adapterKey) to ChannelRuntimeSnapshot())
    }

    fun markRunning(channel: String, adapterKey: String, running: Boolean) {
        update(channel, adapterKey) { current ->
            if (running) {
                current.copy(
                    running = true,
                    state = if (current.ready) {
                        ChannelBindingHealthState.READY
                    } else {
                        ChannelBindingHealthState.STARTING
                    }
                )
            } else {
                current.copy(
                    running = false,
                    connected = false,
                    ready = false,
                    state = ChannelBindingHealthState.STOPPED,
                    lastError = "",
                    retryAttempt = 0,
                    nextRetryAtEpochMillis = null,
                    error = null,
                    lastOperationWarning = null
                )
            }
        }
    }

    fun markConnected(channel: String, adapterKey: String, connected: Boolean) {
        update(channel, adapterKey) { current ->
            if (connected) {
                current.copy(
                    running = true,
                    connected = true,
                    state = if (current.ready) {
                        ChannelBindingHealthState.READY
                    } else {
                        ChannelBindingHealthState.STARTING
                    }
                )
            } else {
                current.copy(
                    connected = false,
                    ready = false,
                    state = when {
                        current.state == ChannelBindingHealthState.BLOCKED ->
                            ChannelBindingHealthState.BLOCKED
                        current.running -> ChannelBindingHealthState.RECONNECTING
                        else -> ChannelBindingHealthState.STOPPED
                    }
                )
            }
        }
    }

    fun markReady(channel: String, adapterKey: String) {
        update(channel, adapterKey) { current ->
            current.copy(
                running = true,
                connected = true,
                ready = true,
                lastError = "",
                state = ChannelBindingHealthState.READY,
                retryAttempt = 0,
                nextRetryAtEpochMillis = null,
                error = null
            )
        }
    }

    fun markError(channel: String, adapterKey: String, message: String) {
        markError(channel, adapterKey, ChannelRuntimeErrorNormalizer.normalize(message))
    }

    fun markError(
        channel: String,
        adapterKey: String,
        error: NormalizedChannelError
    ) {
        update(channel, adapterKey) { current ->
            current.copy(
                lastError = error.summary,
                state = when {
                    current.ready -> ChannelBindingHealthState.READY
                    !error.retryable -> ChannelBindingHealthState.BLOCKED
                    current.running -> ChannelBindingHealthState.RECONNECTING
                    else -> ChannelBindingHealthState.BLOCKED
                },
                error = error
            )
        }
    }

    fun markReconnecting(
        channel: String,
        adapterKey: String,
        attempt: Int,
        nextRetryAtEpochMillis: Long?,
        error: NormalizedChannelError? = null
    ) {
        update(channel, adapterKey) { current ->
            current.copy(
                running = true,
                connected = false,
                ready = false,
                lastError = error?.summary.orEmpty(),
                state = ChannelBindingHealthState.RECONNECTING,
                retryAttempt = attempt.coerceAtLeast(1),
                nextRetryAtEpochMillis = nextRetryAtEpochMillis,
                error = error
            )
        }
    }

    fun markBlocked(
        channel: String,
        adapterKey: String,
        error: NormalizedChannelError
    ) {
        update(channel, adapterKey) { current ->
            current.copy(
                running = false,
                connected = false,
                ready = false,
                lastError = error.summary,
                state = ChannelBindingHealthState.BLOCKED,
                retryAttempt = 0,
                nextRetryAtEpochMillis = null,
                error = error
            )
        }
    }

    fun recordSuccessfulOperation(
        channel: String,
        adapterKey: String,
        operation: ChannelOperation,
        atEpochMillis: Long
    ) {
        val success = ChannelSuccessfulOperation(operation, atEpochMillis)
        update(channel, adapterKey) { current ->
            current.copy(
                lastSuccessfulOperation = success,
                lastOperationWarning = current.lastOperationWarning
                    ?.takeUnless { warning -> warning.operation == operation }
            )
        }
    }

    fun markOperationWarning(
        channel: String,
        adapterKey: String,
        operation: ChannelOperation,
        error: NormalizedChannelError,
        atEpochMillis: Long
    ) {
        update(channel, adapterKey) { current ->
            current.copy(
                lastOperationWarning = ChannelOperationWarning(operation, error, atEpochMillis)
            )
        }
    }

    fun markReadyAfterOperation(
        channel: String,
        adapterKey: String,
        operation: ChannelOperation,
        atEpochMillis: Long,
        connectionObserved: Boolean = true
    ) {
        val success = ChannelSuccessfulOperation(operation, atEpochMillis)
        update(channel, adapterKey) { current ->
            if (current.state == ChannelBindingHealthState.BLOCKED) {
                current.copy(
                    lastSuccessfulOperation = success,
                    lastOperationWarning = current.lastOperationWarning
                        ?.takeUnless { warning -> warning.operation == operation }
                )
            } else {
                current.copy(
                    running = true,
                    connected = current.connected || connectionObserved,
                    ready = true,
                    lastError = "",
                    state = ChannelBindingHealthState.READY,
                    lastSuccessfulOperation = success,
                    retryAttempt = 0,
                    nextRetryAtEpochMillis = null,
                    error = null,
                    lastOperationWarning = current.lastOperationWarning
                        ?.takeUnless { warning -> warning.operation == operation }
                )
            }
        }
    }

    fun markStopped(channel: String, adapterKey: String) {
        markRunning(channel, adapterKey, false)
    }

    fun getSnapshot(channel: String, adapterKey: String): ChannelRuntimeSnapshot = synchronized(lock) {
        mutableState.value[key(channel, adapterKey)] ?: ChannelRuntimeSnapshot()
    }

    fun getSnapshots(channel: String): Map<String, ChannelRuntimeSnapshot> = synchronized(lock) {
        val normalizedChannel = channel.trim().lowercase(Locale.US)
        mutableState.value
            .filterKeys { identity -> identity.channel == normalizedChannel }
            .mapKeys { (identity, _) -> identity.adapterKey }
    }

    private fun update(
        channel: String,
        adapterKey: String,
        transform: (ChannelRuntimeSnapshot) -> ChannelRuntimeSnapshot
    ) = synchronized(lock) {
        val identity = key(channel, adapterKey)
        val current = mutableState.value[identity] ?: ChannelRuntimeSnapshot()
        mutableState.value = mutableState.value + (identity to transform(current))
    }

    private fun key(channel: String, adapterKey: String): ChannelRuntimeBindingId =
        ChannelRuntimeBindingId(
            channel = channel.trim().lowercase(Locale.US),
            adapterKey = adapterKey.trim()
        )
}
