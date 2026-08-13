package com.palmclaw.channels

internal data class ChannelReconnectState(
    val attempt: Int = 0,
    val readySinceEpochMillis: Long? = null,
    val waitingForNetwork: Boolean = false
) {
    init {
        require(attempt >= 0) { "attempt must not be negative" }
    }
}

internal sealed class ChannelReconnectEvent {
    data class Failure(
        val atEpochMillis: Long,
        val networkAvailable: Boolean
    ) : ChannelReconnectEvent()

    object NetworkLost : ChannelReconnectEvent()

    data class NetworkRestored(
        val atEpochMillis: Long
    ) : ChannelReconnectEvent()

    data class Ready(
        val atEpochMillis: Long
    ) : ChannelReconnectEvent()

    object Stopped : ChannelReconnectEvent()
}

internal data class ChannelRetrySchedule(
    val attempt: Int,
    val delayMillis: Long,
    val atEpochMillis: Long
)

internal data class ChannelReconnectTransition(
    val state: ChannelReconnectState,
    val retry: ChannelRetrySchedule? = null
)

internal fun interface ChannelRetryJitter {
    fun apply(baseDelayMillis: Long, attempt: Int): Long

    companion object {
        val NONE = ChannelRetryJitter { baseDelayMillis, _ -> baseDelayMillis }
    }
}

internal class FractionalChannelRetryJitter(
    private val unitSample: () -> Double,
    private val rangeFraction: Double = 0.2
) : ChannelRetryJitter {
    init {
        require(rangeFraction in 0.0..1.0) { "rangeFraction must be between 0 and 1" }
    }

    override fun apply(baseDelayMillis: Long, attempt: Int): Long {
        val sample = unitSample().coerceIn(0.0, 1.0)
        val multiplier = 1.0 + ((sample * 2.0) - 1.0) * rangeFraction
        return (baseDelayMillis * multiplier).toLong()
    }
}

internal class ChannelReconnectPolicy(
    private val initialDelayMillis: Long = 2_000L,
    private val maximumDelayMillis: Long = 5 * 60_000L,
    private val stableResetMillis: Long = 5 * 60_000L,
    private val jitter: ChannelRetryJitter = ChannelRetryJitter.NONE
) {
    init {
        require(initialDelayMillis > 0L) { "initialDelayMillis must be positive" }
        require(maximumDelayMillis >= initialDelayMillis) {
            "maximumDelayMillis must not be less than initialDelayMillis"
        }
        require(stableResetMillis >= 0L) { "stableResetMillis must not be negative" }
    }

    fun transition(
        current: ChannelReconnectState,
        event: ChannelReconnectEvent
    ): ChannelReconnectTransition = when (event) {
        is ChannelReconnectEvent.Failure -> afterFailure(current, event)
        ChannelReconnectEvent.NetworkLost -> ChannelReconnectTransition(
            current.copy(waitingForNetwork = true)
        )

        is ChannelReconnectEvent.NetworkRestored -> afterNetworkRestored(current, event)
        is ChannelReconnectEvent.Ready -> ChannelReconnectTransition(
            current.copy(
                readySinceEpochMillis = current.readySinceEpochMillis ?: event.atEpochMillis,
                waitingForNetwork = false
            )
        )
        ChannelReconnectEvent.Stopped -> ChannelReconnectTransition(ChannelReconnectState())
    }

    private fun afterFailure(
        current: ChannelReconnectState,
        event: ChannelReconnectEvent.Failure
    ): ChannelReconnectTransition {
        val stableBeforeFailure = current.readySinceEpochMillis?.let { readySince ->
            event.atEpochMillis >= readySince &&
                event.atEpochMillis - readySince >= stableResetMillis
        } == true
        val attempt = if (stableBeforeFailure) {
            1
        } else {
            if (current.attempt == Int.MAX_VALUE) {
                Int.MAX_VALUE
            } else {
                (current.attempt + 1).coerceAtLeast(1)
            }
        }
        val state = ChannelReconnectState(
            attempt = attempt,
            readySinceEpochMillis = null,
            waitingForNetwork = !event.networkAvailable
        )
        if (!event.networkAvailable) return ChannelReconnectTransition(state)

        return ChannelReconnectTransition(
            state = state,
            retry = schedule(attempt, event.atEpochMillis)
        )
    }

    private fun afterNetworkRestored(
        current: ChannelReconnectState,
        event: ChannelReconnectEvent.NetworkRestored
    ): ChannelReconnectTransition {
        if (!current.waitingForNetwork) return ChannelReconnectTransition(current)

        val attempt = current.attempt.coerceAtLeast(1)
        return ChannelReconnectTransition(
            state = current.copy(
                attempt = attempt,
                readySinceEpochMillis = null,
                waitingForNetwork = false
            ),
            retry = ChannelRetrySchedule(
                attempt = attempt,
                delayMillis = 0L,
                atEpochMillis = event.atEpochMillis
            )
        )
    }

    private fun schedule(attempt: Int, nowEpochMillis: Long): ChannelRetrySchedule {
        val baseDelay = exponentialDelay(attempt)
        val delay = jitter.apply(baseDelay, attempt).coerceIn(0L, maximumDelayMillis)
        val scheduledAt = nowEpochMillis.coerceAtMost(Long.MAX_VALUE - delay) + delay
        return ChannelRetrySchedule(
            attempt = attempt,
            delayMillis = delay,
            atEpochMillis = scheduledAt
        )
    }

    private fun exponentialDelay(attempt: Int): Long {
        var delay = initialDelayMillis
        var remainingDoublings = (attempt - 1).coerceAtLeast(0)
        while (remainingDoublings > 0 && delay < maximumDelayMillis) {
            delay = if (delay > maximumDelayMillis / 2) {
                maximumDelayMillis
            } else {
                (delay * 2).coerceAtMost(maximumDelayMillis)
            }
            remainingDoublings -= 1
        }
        return delay
    }
}
