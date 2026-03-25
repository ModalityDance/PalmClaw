package com.palmclaw.providers

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import java.io.IOException

internal suspend fun collectStreamResponse(
    events: Flow<LlmStreamEvent>
): LlmResponse {
    var finalResponse: LlmResponse? = null
    var lastError: LlmStreamEvent.Error? = null
    events.collect { event ->
        when (event) {
            is LlmStreamEvent.DeltaText -> Unit
            is LlmStreamEvent.Final -> finalResponse = event.fullResponse
            is LlmStreamEvent.Error -> lastError = event
        }
    }
    finalResponse?.let { return it }
    val error = lastError
    if (error != null) {
        throw error.throwable ?: IOException(error.message)
    }
    throw IOException("Stream finished without a final response.")
}
