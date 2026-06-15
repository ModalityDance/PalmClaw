package com.palmclaw.ui

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Single mutable owner for [ChatUiState].
 *
 * Phase 1 keeps the public UI contract unchanged while making state ownership explicit.
 */
internal class ChatStateStore(initialState: ChatUiState) {
    private val lock = Any()
    private val backingState = MutableStateFlow(initialState)
    private val backingChatContentState = MutableStateFlow(initialState.toChatContentState())
    private val backingChatTimelineState = MutableStateFlow(initialState.toChatTimelineState())
    private val backingChatComposerState = MutableStateFlow(initialState.toChatComposerState())
    private val backingSessionListState = MutableStateFlow(initialState.toSessionListState())
    private val backingOnboardingUiState = MutableStateFlow(initialState.toOnboardingUiState())
    private val backingSettingsShellState = MutableStateFlow(initialState.toSettingsShellState())
    private val backingIdentityDisplayState = MutableStateFlow(initialState.toIdentityDisplayState())
    private val backingProviderSettingsState = MutableStateFlow(initialState.toProviderSettingsState())
    private val backingChannelsSettingsState = MutableStateFlow(initialState.toChannelsSettingsState())
    private val backingSkillsDiscoveryState = MutableStateFlow(initialState.toSkillsDiscoveryState())
    private val backingToolSettingsState = MutableStateFlow(initialState.toToolSettingsState())
    private val backingAutomationSettingsState = MutableStateFlow(initialState.toAutomationSettingsState())
    private val backingAlwaysOnSettingsState = MutableStateFlow(initialState.toAlwaysOnSettingsState())
    private val backingMcpSettingsState = MutableStateFlow(initialState.toMcpSettingsState())
    private val backingUpdateSettingsState = MutableStateFlow(initialState.toUpdateSettingsState())
    private val backingSessionBindingState = MutableStateFlow(initialState.toSessionBindingState())

    val value: ChatUiState
        get() = backingState.value

    val chatContentState: StateFlow<ChatContentState> = backingChatContentState.asStateFlow()
    val chatTimelineState: StateFlow<ChatTimelineState> = backingChatTimelineState.asStateFlow()
    val chatComposerState: StateFlow<ChatComposerState> = backingChatComposerState.asStateFlow()
    val sessionListState: StateFlow<SessionListState> = backingSessionListState.asStateFlow()
    val onboardingUiState: StateFlow<OnboardingUiState> = backingOnboardingUiState.asStateFlow()
    val settingsShellState: StateFlow<SettingsShellState> = backingSettingsShellState.asStateFlow()
    val identityDisplayState: StateFlow<IdentityDisplayState> = backingIdentityDisplayState.asStateFlow()
    val providerSettingsState: StateFlow<ProviderSettingsState> = backingProviderSettingsState.asStateFlow()
    val channelsSettingsState: StateFlow<ChannelsSettingsState> = backingChannelsSettingsState.asStateFlow()
    val skillsDiscoveryState: StateFlow<SkillsDiscoveryState> = backingSkillsDiscoveryState.asStateFlow()
    val toolSettingsState: StateFlow<ToolSettingsState> = backingToolSettingsState.asStateFlow()
    val automationSettingsState: StateFlow<AutomationSettingsState> = backingAutomationSettingsState.asStateFlow()
    val alwaysOnSettingsState: StateFlow<AlwaysOnSettingsState> = backingAlwaysOnSettingsState.asStateFlow()
    val mcpSettingsState: StateFlow<McpSettingsState> = backingMcpSettingsState.asStateFlow()
    val updateSettingsState: StateFlow<UpdateSettingsState> = backingUpdateSettingsState.asStateFlow()
    val sessionBindingState: StateFlow<SessionBindingState> = backingSessionBindingState.asStateFlow()

    fun update(transform: (ChatUiState) -> ChatUiState) {
        synchronized(lock) {
            val nextState = transform(backingState.value)
            backingState.value = nextState
            refreshSlices(nextState)
        }
    }

    fun updateSettingsShellState(transform: (SettingsShellState) -> SettingsShellState) {
        synchronized(lock) {
            val nextState = backingState.value.withSettingsShellState(
                transform(backingSettingsShellState.value)
            )
            backingState.value = nextState
            refreshSlices(nextState)
        }
    }

    fun updateChatContentState(transform: (ChatContentState) -> ChatContentState) {
        synchronized(lock) {
            val nextState = backingState.value.withChatContentState(
                transform(backingChatContentState.value)
            )
            backingState.value = nextState
            refreshSlices(nextState)
        }
    }

    fun updateChatTimelineState(transform: (ChatTimelineState) -> ChatTimelineState) {
        synchronized(lock) {
            val nextState = backingState.value.withChatTimelineState(
                transform(backingChatTimelineState.value)
            )
            backingState.value = nextState
            refreshSlices(nextState)
        }
    }

    fun updateChatComposerState(transform: (ChatComposerState) -> ChatComposerState) {
        synchronized(lock) {
            val nextState = backingState.value.withChatComposerState(
                transform(backingChatComposerState.value)
            )
            backingState.value = nextState
            refreshSlices(nextState)
        }
    }

    fun commitOptimisticSend(message: UiMessage): ChatSendDraftSnapshot {
        synchronized(lock) {
            val previousComposer = backingChatComposerState.value
            val nextState = backingState.value.copy(
                messages = backingChatTimelineState.value.messages + message,
                messagesLoading = false,
                isGenerating = true,
                input = "",
                composerAttachments = emptyList(),
                composerAttachmentError = null
            )
            backingState.value = nextState
            refreshSlices(nextState)
            return ChatSendDraftSnapshot(
                input = previousComposer.input,
                composerAttachments = previousComposer.composerAttachments,
                composerImporting = previousComposer.composerImporting,
                composerAttachmentError = previousComposer.composerAttachmentError
            )
        }
    }

    fun rollbackOptimisticSend(messageId: Long, draftSnapshot: ChatSendDraftSnapshot) {
        synchronized(lock) {
            val nextMessages = backingChatTimelineState.value.messages.filterNot { it.id == messageId }
            val nextState = backingState.value.copy(
                messages = nextMessages,
                isGenerating = false,
                input = draftSnapshot.input,
                composerAttachments = draftSnapshot.composerAttachments,
                composerImporting = draftSnapshot.composerImporting,
                composerAttachmentError = draftSnapshot.composerAttachmentError
            )
            backingState.value = nextState
            refreshSlices(nextState)
        }
    }

    fun updateSessionListState(transform: (SessionListState) -> SessionListState) {
        synchronized(lock) {
            val nextState = backingState.value.withSessionListState(
                transform(backingSessionListState.value)
            )
            backingState.value = nextState
            refreshSlices(nextState)
        }
    }

    fun updateChannelsSettingsState(transform: (ChannelsSettingsState) -> ChannelsSettingsState) {
        synchronized(lock) {
            val nextState = backingState.value.withChannelsSettingsState(
                transform(backingChannelsSettingsState.value)
            )
            backingState.value = nextState
            refreshSlices(nextState)
        }
    }

    fun updateSessionBindingState(transform: (SessionBindingState) -> SessionBindingState) {
        synchronized(lock) {
            val nextState = backingState.value.withSessionBindingState(
                transform(backingSessionBindingState.value)
            )
            backingState.value = nextState
            refreshSlices(nextState)
        }
    }

    fun updateOnboardingUiState(transform: (OnboardingUiState) -> OnboardingUiState) {
        synchronized(lock) {
            val nextState = backingState.value.withOnboardingUiState(
                transform(backingOnboardingUiState.value)
            )
            backingState.value = nextState
            refreshSlices(nextState)
        }
    }

    fun updateIdentityDisplayState(transform: (IdentityDisplayState) -> IdentityDisplayState) {
        synchronized(lock) {
            val nextState = backingState.value.withIdentityDisplayState(
                transform(backingIdentityDisplayState.value)
            )
            backingState.value = nextState
            refreshSlices(nextState)
        }
    }

    fun updateProviderSettingsState(transform: (ProviderSettingsState) -> ProviderSettingsState) {
        synchronized(lock) {
            val nextState = backingState.value.withProviderSettingsState(
                transform(backingProviderSettingsState.value)
            )
            backingState.value = nextState
            refreshSlices(nextState)
        }
    }

    fun updateSkillsState(transform: (SkillsDiscoveryState) -> SkillsDiscoveryState) {
        synchronized(lock) {
            val nextState = backingState.value.withSkillsDiscoveryState(
                transform(backingSkillsDiscoveryState.value)
            )
            backingState.value = nextState
            refreshSlices(nextState)
        }
    }

    fun updateToolSettingsState(transform: (ToolSettingsState) -> ToolSettingsState) {
        synchronized(lock) {
            val nextState = backingState.value.withToolSettingsState(
                transform(backingToolSettingsState.value)
            )
            backingState.value = nextState
            refreshSlices(nextState)
        }
    }

    fun updateAutomationState(transform: (AutomationSettingsState) -> AutomationSettingsState) {
        synchronized(lock) {
            val nextState = backingState.value.withAutomationSettingsState(
                transform(backingAutomationSettingsState.value)
            )
            backingState.value = nextState
            refreshSlices(nextState)
        }
    }

    fun updateAlwaysOnState(transform: (AlwaysOnSettingsState) -> AlwaysOnSettingsState) {
        synchronized(lock) {
            val nextState = backingState.value.withAlwaysOnSettingsState(
                transform(backingAlwaysOnSettingsState.value)
            )
            backingState.value = nextState
            refreshSlices(nextState)
        }
    }

    fun updateMcpSettingsState(transform: (McpSettingsState) -> McpSettingsState) {
        synchronized(lock) {
            val nextState = backingState.value.withMcpSettingsState(
                transform(backingMcpSettingsState.value)
            )
            backingState.value = nextState
            refreshSlices(nextState)
        }
    }

    fun updateUpdateState(transform: (UpdateSettingsState) -> UpdateSettingsState) {
        synchronized(lock) {
            val nextState = backingState.value.withUpdateSettingsState(
                transform(backingUpdateSettingsState.value)
            )
            backingState.value = nextState
            refreshSlices(nextState)
        }
    }

    private fun refreshSlices(state: ChatUiState) {
        backingChatContentState.value = state.toChatContentState()
        backingChatTimelineState.value = state.toChatTimelineState()
        backingChatComposerState.value = state.toChatComposerState()
        backingSessionListState.value = state.toSessionListState()
        backingOnboardingUiState.value = state.toOnboardingUiState()
        backingSettingsShellState.value = state.toSettingsShellState()
        backingIdentityDisplayState.value = state.toIdentityDisplayState()
        backingProviderSettingsState.value = state.toProviderSettingsState()
        backingChannelsSettingsState.value = state.toChannelsSettingsState()
        backingSkillsDiscoveryState.value = state.toSkillsDiscoveryState()
        backingToolSettingsState.value = state.toToolSettingsState()
        backingAutomationSettingsState.value = state.toAutomationSettingsState()
        backingAlwaysOnSettingsState.value = state.toAlwaysOnSettingsState()
        backingMcpSettingsState.value = state.toMcpSettingsState()
        backingUpdateSettingsState.value = state.toUpdateSettingsState()
        backingSessionBindingState.value = state.toSessionBindingState()
    }
}

internal data class ChatSendDraftSnapshot(
    val input: String,
    val composerAttachments: List<UiComposerAttachmentDraft>,
    val composerImporting: Boolean,
    val composerAttachmentError: String?
)
