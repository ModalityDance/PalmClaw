package com.palmclaw.ui

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Single mutable owner for [ChatUiState].
 *
 * Phase 1 keeps the public UI contract unchanged while making state ownership explicit.
 */
internal class ChatStateStore(initialState: ChatUiState) {
    private val backingState = MutableStateFlow(initialState)

    val value: ChatUiState
        get() = backingState.value

    fun asStateFlow(): StateFlow<ChatUiState> = backingState.asStateFlow()

    fun update(transform: (ChatUiState) -> ChatUiState) {
        backingState.update(transform)
    }

    fun updateSession(transform: (ChatUiState) -> ChatUiState) = update(transform)

    fun updateProviderSettings(transform: (ChatUiState) -> ChatUiState) = update(transform)

    fun updateChannelBinding(transform: (ChatUiState) -> ChatUiState) = update(transform)

    fun updateRuntime(transform: (ChatUiState) -> ChatUiState) = update(transform)

    fun updateOnboarding(transform: (ChatUiState) -> ChatUiState) = update(transform)

    fun updateAppUpdate(transform: (ChatUiState) -> ChatUiState) = update(transform)

    fun updateShell(transform: (ChatUiState) -> ChatUiState) = update(transform)
}
