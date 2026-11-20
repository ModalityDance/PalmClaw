package com.palmclaw.tools

import kotlinx.coroutines.CancellationException

internal enum class MediaOutputState(val wireName: String) {
    AVAILABLE("available"), EMPTY("empty"), ABSENT("absent"), UNAVAILABLE("unavailable")
}

/** A missing row, empty output and failed query are different observations. */
internal fun observeMediaOutput(readSize: () -> Long?): MediaOutputState = try {
    val size = readSize()
    when {
        size == null -> MediaOutputState.ABSENT
        size > 0 -> MediaOutputState.AVAILABLE
        size == 0L -> MediaOutputState.EMPTY
        else -> MediaOutputState.UNAVAILABLE
    }
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (_: Exception) {
    MediaOutputState.UNAVAILABLE
}
