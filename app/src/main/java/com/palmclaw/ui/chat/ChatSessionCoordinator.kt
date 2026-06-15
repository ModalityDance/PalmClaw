package com.palmclaw.ui

import com.palmclaw.config.AppSession
import com.palmclaw.config.OnboardingConfig
import com.palmclaw.storage.entities.MessageEntity
import com.palmclaw.storage.entities.SessionEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Owns session-facing UI state transitions while delegating repository/runtime side effects.
 */
internal class ChatSessionCoordinator(
    private val scope: CoroutineScope,
    private val stateStore: ChatStateStore,
    private val dependencies: Dependencies,
    private val actions: Actions
) {
    data class Dependencies(
        val currentSessionId: () -> String,
        val setCurrentSessionId: (String) -> Unit,
        val saveLastActiveSessionId: (String) -> Unit,
        val computeIsGeneratingForSession: (String) -> Boolean,
        val observeSessionsSource: () -> Flow<List<SessionEntity>>,
        val observeRecentMessagesSource: (String, Int) -> Flow<List<MessageEntity>>,
        val loadMessagesBeforeSource: suspend (String, Long, Long, Int) -> List<MessageEntity>,
        val buildSessionSummaries: (List<SessionEntity>) -> List<UiSessionSummary>,
        val buildConnectedChannelsOverview: (List<UiSessionSummary>) -> List<UiConnectedChannelSummary>,
        val mapObservedMessagesToUi: suspend (String, List<MessageEntity>) -> List<UiMessage>,
        val resolveOnboardingConfig: () -> OnboardingConfig,
        val onSessionsObserved: () -> Unit = {},
        val onMessagesObserved: (String) -> Unit = {}
    )

    data class Actions(
        val bootstrapLocalSessions: () -> Unit,
        val sendMessage: (String) -> Unit,
        val stopGeneration: () -> Unit,
        val createSession: (String) -> Unit,
        val renameSession: (String, String) -> Unit,
        val deleteSession: (String) -> Unit
    )

    private var messagesObserveJob: Job? = null
    private var nextOptimisticMessageId = -1L
    private val messageProjectionCache = ChatMessageProjectionCache(
        initialPageSize = INITIAL_MESSAGE_PAGE_SIZE,
        olderPageSize = OLDER_MESSAGE_PAGE_SIZE,
        projectMessages = dependencies.mapObservedMessagesToUi
    )
    private val loadingOlderSessionIds = mutableSetOf<String>()

    fun bootstrapLocalSessions() = actions.bootstrapLocalSessions()

    fun observeSessions() {
        scope.launch {
            dependencies.observeSessionsSource().collectLatest { rawSessions ->
                val sessions = dependencies.buildSessionSummaries(rawSessions)
                val onboardingCfg = dependencies.resolveOnboardingConfig()
                val currentSessionId = dependencies.currentSessionId()
                val active = currentSessionId.takeIf { sessionId ->
                    sessions.any { it.id == sessionId }
                } ?: AppSession.LOCAL_SESSION_ID
                if (active != currentSessionId) {
                    dependencies.setCurrentSessionId(active)
                    dependencies.saveLastActiveSessionId(active)
                    observeMessages(active)
                } else {
                    dependencies.saveLastActiveSessionId(active)
                }
                val activeTitle = sessions.firstOrNull { it.id == active }?.title
                    ?: AppSession.LOCAL_SESSION_TITLE
                stateStore.updateSessionListState {
                    it.copy(
                        sessions = sessions,
                        currentSessionId = active,
                        currentSessionTitle = activeTitle
                    )
                }
                stateStore.updateChatTimelineState {
                    it.copy(
                        currentSessionId = active,
                        isGenerating = dependencies.computeIsGeneratingForSession(active)
                    )
                }
                stateStore.updateChannelsSettingsState {
                    it.copy(connectedChannels = dependencies.buildConnectedChannelsOverview(sessions))
                }
                stateStore.updateOnboardingUiState {
                    it.copy(
                        completed = onboardingCfg.completed,
                        userDisplayName = onboardingCfg.userDisplayName,
                        agentDisplayName = onboardingCfg.agentDisplayName,
                        onboardingUserDisplayName = onboardingCfg.userDisplayName,
                        onboardingAgentDisplayName = onboardingCfg.agentDisplayName
                    )
                }
                dependencies.onSessionsObserved()
            }
        }
    }

    fun observeMessages(sessionId: String) {
        messagesObserveJob?.cancel()
        messagesObserveJob = scope.launch {
            if (dependencies.currentSessionId() == sessionId) {
                val snapshot = messageProjectionCache.snapshot(sessionId)
                stateStore.updateChatTimelineState {
                    it.copy(
                        messages = snapshot?.messages ?: emptyList(),
                        messagesLoading = snapshot == null,
                        canLoadOlderMessages = snapshot?.canLoadOlder ?: false
                    )
                }
            }
            dependencies.observeRecentMessagesSource(sessionId, INITIAL_MESSAGE_PAGE_SIZE).collect { messages ->
                val result = messageProjectionCache.replaceRecent(sessionId, messages)
                if (dependencies.currentSessionId() != sessionId) {
                    return@collect
                }
                stateStore.updateChatTimelineState {
                    it.copy(
                        messages = result.messages,
                        messagesLoading = false,
                        canLoadOlderMessages = result.canLoadOlder
                    )
                }
                dependencies.onMessagesObserved(sessionId)
            }
        }
    }

    fun onInputChanged(value: String) {
        stateStore.updateChatComposerState { it.copy(input = value) }
    }

    fun sendMessage() {
        val composerState = stateStore.chatComposerState.value
        val text = composerState.input.trim()
        val attachments = composerState.composerAttachments
        val hasAttachments = attachments.isNotEmpty()
        if ((text.isBlank() && !hasAttachments) || composerState.isGenerating || composerState.composerImporting) return
        val optimisticMessage = UiMessage(
            id = nextOptimisticMessageId--,
            role = "user",
            content = text,
            createdAt = System.currentTimeMillis(),
            attachments = attachments.map { it.attachment }
        )
        val sessionId = dependencies.currentSessionId()
        messageProjectionCache.appendOptimistic(sessionId, optimisticMessage)
        val draftSnapshot = stateStore.commitOptimisticSend(optimisticMessage)
        runCatching {
            actions.sendMessage(text)
        }.onFailure { t ->
            messageProjectionCache.removeOptimistic(sessionId, optimisticMessage.id)
            stateStore.rollbackOptimisticSend(optimisticMessage.id, draftSnapshot)
            stateStore.updateSettingsShellState {
                it.copy(info = "Send failed: ${t.message ?: t.javaClass.simpleName}")
            }
        }
    }

    fun stopGeneration() = actions.stopGeneration()

    fun loadOlderMessages() {
        val sessionId = dependencies.currentSessionId()
        if (sessionId in loadingOlderSessionIds) return
        val snapshot = messageProjectionCache.snapshot(sessionId)
        if (snapshot?.canLoadOlder == false) return
        val currentMessages = snapshot?.entities.orEmpty()
        val oldest = currentMessages.firstOrNull() ?: return
        loadingOlderSessionIds += sessionId
        stateStore.updateChatTimelineState {
            if (it.currentSessionId == sessionId) {
                it.copy(messagesLoadingOlder = true)
            } else {
                it
            }
        }
        scope.launch {
            runCatching {
                dependencies.loadMessagesBeforeSource(
                    sessionId,
                    oldest.createdAt,
                    oldest.id,
                    OLDER_MESSAGE_PAGE_SIZE
                )
            }.onSuccess { olderMessages ->
                val result = messageProjectionCache.prependOlder(sessionId, olderMessages)
                if (dependencies.currentSessionId() == sessionId) {
                    stateStore.updateChatTimelineState {
                        it.copy(
                            messages = result.messages,
                            messagesLoadingOlder = false,
                            canLoadOlderMessages = result.canLoadOlder
                        )
                    }
                }
            }.onFailure {
                messageProjectionCache.markCannotLoadOlder(sessionId)
                if (dependencies.currentSessionId() == sessionId) {
                    stateStore.updateChatTimelineState {
                        it.copy(
                            messagesLoadingOlder = false,
                            canLoadOlderMessages = false
                        )
                    }
                }
            }
            loadingOlderSessionIds -= sessionId
        }
    }


    fun selectSession(sessionId: String) {
        val sid = sessionId.trim().ifBlank { AppSession.LOCAL_SESSION_ID }
        if (sid == dependencies.currentSessionId()) {
            return
        }
        dependencies.setCurrentSessionId(sid)
        dependencies.saveLastActiveSessionId(sid)
        val title = stateStore.sessionListState.value.sessions.firstOrNull { it.id == sid }?.title ?: sid
        val cachedMessages = messageProjectionCache.snapshot(sid)
        stateStore.updateSessionListState {
            it.copy(
                currentSessionId = sid,
                currentSessionTitle = title
            )
        }
        stateStore.updateChatTimelineState {
            it.copy(
                currentSessionId = sid,
                isGenerating = dependencies.computeIsGeneratingForSession(sid),
                messages = cachedMessages?.messages.orEmpty(),
                messagesLoading = cachedMessages == null,
                messagesLoadingOlder = false,
                canLoadOlderMessages = cachedMessages?.canLoadOlder == true
            )
        }
        stateStore.updateChatComposerState {
            it.copy(
                isGenerating = dependencies.computeIsGeneratingForSession(sid),
                composerAttachments = emptyList(),
                composerImporting = false,
                composerAttachmentError = null
            )
        }
        observeMessages(sid)
    }

    fun createSession(displayName: String) {
        val title = displayName.trim()
        if (title.isBlank()) {
            stateStore.updateSettingsShellState { it.copy(info = "Session name is required.") }
            return
        }
        actions.createSession(title)
    }

    fun renameSession(sessionId: String, displayName: String) {
        val sid = sessionId.trim()
        if (sid.isBlank()) return
        if (sid == AppSession.LOCAL_SESSION_ID) {
            stateStore.updateSettingsShellState { it.copy(info = "LOCAL session cannot be renamed.") }
            return
        }
        val title = displayName.trim()
        if (title.isBlank()) {
            stateStore.updateSettingsShellState { it.copy(info = "Session name is required.") }
            return
        }
        actions.renameSession(sid, title)
    }

    fun deleteSession(sessionId: String) {
        val sid = sessionId.trim()
        if (sid.isBlank()) return
        if (sid == AppSession.LOCAL_SESSION_ID) {
            stateStore.updateSettingsShellState { it.copy(info = "Local session cannot be deleted.") }
            return
        }
        actions.deleteSession(sid)
    }

    fun clear() {
        messagesObserveJob?.cancel()
        messagesObserveJob = null
        messageProjectionCache.clearAll()
        loadingOlderSessionIds.clear()
    }

    companion object {
        const val INITIAL_MESSAGE_PAGE_SIZE = 120
        const val OLDER_MESSAGE_PAGE_SIZE = 80
    }
}
