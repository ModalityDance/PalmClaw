package com.palmclaw.providers

import kotlinx.coroutines.flow.Flow

interface LlmProvider {
    suspend fun chat(messages: List<ChatMessage>, toolsSpec: List<ToolSpec>): LlmResponse
    fun chatStream(messages: List<ChatMessage>, toolsSpec: List<ToolSpec>): Flow<LlmStreamEvent>
}

sealed class LlmStreamEvent {
    data class DeltaText(val text: String) : LlmStreamEvent()
    data class Final(val fullResponse: LlmResponse) : LlmStreamEvent()
    data class Error(val message: String, val throwable: Throwable? = null) : LlmStreamEvent()
}

