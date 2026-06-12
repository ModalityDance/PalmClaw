package com.palmclaw.ui

import com.palmclaw.config.AppSession
import com.palmclaw.config.OnboardingConfig
import com.palmclaw.storage.entities.MessageEntity
import com.palmclaw.storage.entities.SessionEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
        val resolveOnboardingConfig: () -> OnboardingConfig
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
    private val loadedMessagesBySession = mutableMapOf<String, List<MessageEntity>>()
    private val projectedMessagesBySession = mutableMapOf<String, List<UiMessage>>()
    private val canLoadOlderBySession = mutableMapOf<String, Boolean>()
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
                stateStore.updateChatContentState {
                    it.copy(
                        sessions = sessions,
                        currentSessionId = active,
                        currentSessionTitle = activeTitle,
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
            }
        }
    }

    fun observeMessages(sessionId: String) {
        messagesObserveJob?.cancel()
        messagesObserveJob = scope.launch {
            if (dependencies.currentSessionId() == sessionId && projectedMessagesBySession[sessionId] == null) {
                stateStore.updateChatContentState { it.copy(messagesLoading = true) }
            }
            dependencies.observeRecentMessagesSource(sessionId, INITIAL_MESSAGE_PAGE_SIZE).collect { messages ->
                val onboardingCfg = dependencies.resolveOnboardingConfig()
                val mergedMessages = mergeMessages(
                    existing = loadedMessagesBySession[sessionId].orEmpty(),
                    incoming = messages
                )
                val projectedMessages = withContext(Dispatchers.Default) {
                    dependencies.mapObservedMessagesToUi(sessionId, mergedMessages)
                }
                loadedMessagesBySession[sessionId] = mergedMessages
                projectedMessagesBySession[sessionId] = projectedMessages
                canLoadOlderBySession[sessionId] = when {
                    mergedMessages.size > messages.size -> canLoadOlderBySession[sessionId] ?: true
                    else -> messages.size >= INITIAL_MESSAGE_PAGE_SIZE
                }
                if (dependencies.currentSessionId() != sessionId) {
                    return@collect
                }
                stateStore.updateChatContentState {
                    it.copy(
                        messages = projectedMessages,
                        messagesLoading = false,
                        canLoadOlderMessages = canLoadOlderBySession[sessionId] == true
                    )
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
            }
        }
    }

    fun onInputChanged(value: String) {
        stateStore.updateChatContentState { it.copy(input = value) }
    }

    fun sendMessage() {
        val chatState = stateStore.chatContentState.value
        val text = chatState.input.trim()
        val attachments = chatState.composerAttachments
        val hasAttachments = attachments.isNotEmpty()
        if ((text.isBlank() && !hasAttachments) || chatState.isGenerating || chatState.composerImporting) return
        val optimisticMessage = UiMessage(
            id = nextOptimisticMessageId--,
            role = "user",
            content = text,
            createdAt = System.currentTimeMillis(),
            attachments = attachments.map { it.attachment }
        )
        val sessionId = dependencies.currentSessionId()
        projectedMessagesBySession[sessionId] = projectedMessagesBySession[sessionId].orEmpty() + optimisticMessage
        stateStore.updateChatContentState {
            it.copy(
                messages = it.messages + optimisticMessage,
                messagesLoading = false,
                input = "",
                isGenerating = true,
                composerAttachments = emptyList(),
                composerAttachmentError = null
            )
        }
        actions.sendMessage(text)
    }

    fun stopGeneration() = actions.stopGeneration()

    fun loadOlderMessages() {
        val sessionId = dependencies.currentSessionId()
        if (sessionId in loadingOlderSessionIds) return
        if (canLoadOlderBySession[sessionId] == false) return
        val currentMessages = loadedMessagesBySession[sessionId].orEmpty()
        val oldest = currentMessages.firstOrNull() ?: return
        loadingOlderSessionIds += sessionId
        stateStore.updateChatContentState {
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
                val mergedMessages = mergeMessages(
                    existing = loadedMessagesBySession[sessionId].orEmpty(),
                    incoming = olderMessages
                )
                val projectedMessages = withContext(Dispatchers.Default) {
                    dependencies.mapObservedMessagesToUi(sessionId, mergedMessages)
                }
                loadedMessagesBySession[sessionId] = mergedMessages
                projectedMessagesBySession[sessionId] = projectedMessages
                canLoadOlderBySession[sessionId] = olderMessages.size >= OLDER_MESSAGE_PAGE_SIZE
                if (dependencies.currentSessionId() == sessionId) {
                    stateStore.updateChatContentState {
                        it.copy(
                            messages = projectedMessages,
                            messagesLoadingOlder = false,
                            canLoadOlderMessages = canLoadOlderBySession[sessionId] == true
                        )
                    }
                }
            }.onFailure {
                canLoadOlderBySession[sessionId] = false
                if (dependencies.currentSessionId() == sessionId) {
                    stateStore.updateChatContentState {
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
        val title = stateStore.chatContentState.value.sessions.firstOrNull { it.id == sid }?.title ?: sid
        val cachedMessages = projectedMessagesBySession[sid]
        stateStore.updateChatContentState {
            it.copy(
                currentSessionId = sid,
                currentSessionTitle = title,
                isGenerating = dependencies.computeIsGeneratingForSession(sid),
                messages = cachedMessages.orEmpty(),
                messagesLoading = cachedMessages == null,
                messagesLoadingOlder = false,
                canLoadOlderMessages = canLoadOlderBySession[sid] == true,
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
        loadedMessagesBySession.clear()
        projectedMessagesBySession.clear()
        canLoadOlderBySession.clear()
        loadingOlderSessionIds.clear()
    }

    private fun mergeMessages(
        existing: List<MessageEntity>,
        incoming: List<MessageEntity>
    ): List<MessageEntity> {
        if (existing.isEmpty()) return incoming.sortedWith(messageOrder)
        if (incoming.isEmpty()) return existing.sortedWith(messageOrder)
        val byId = LinkedHashMap<Long, MessageEntity>(existing.size + incoming.size)
        existing.forEach { byId[it.id] = it }
        incoming.forEach { byId[it.id] = it }
        return byId.values.sortedWith(messageOrder)
    }

    companion object {
        const val INITIAL_MESSAGE_PAGE_SIZE = 120
        const val OLDER_MESSAGE_PAGE_SIZE = 80

        private val messageOrder = compareBy<MessageEntity> { it.createdAt }.thenBy { it.id }
    }
}
