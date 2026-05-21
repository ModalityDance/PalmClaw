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
        val observeMessagesSource: (String) -> Flow<List<MessageEntity>>,
        val buildSessionSummaries: (List<SessionEntity>) -> List<UiSessionSummary>,
        val buildConnectedChannelsOverview: (List<UiSessionSummary>) -> List<UiConnectedChannelSummary>,
        val mapObservedMessagesToUi: suspend (List<MessageEntity>) -> List<UiMessage>,
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
    private val projectedMessagesBySession = mutableMapOf<String, List<UiMessage>>()

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
                stateStore.update {
                    it.copy(
                        sessions = sessions,
                        currentSessionId = active,
                        currentSessionTitle = activeTitle,
                        isGenerating = dependencies.computeIsGeneratingForSession(active),
                        settingsConnectedChannels = dependencies.buildConnectedChannelsOverview(sessions),
                        onboardingCompleted = onboardingCfg.completed,
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
                stateStore.updateSession { it.copy(messagesLoading = true) }
            }
            dependencies.observeMessagesSource(sessionId).collect { messages ->
                val onboardingCfg = dependencies.resolveOnboardingConfig()
                val projectedMessages = withContext(Dispatchers.Default) {
                    dependencies.mapObservedMessagesToUi(messages)
                }
                projectedMessagesBySession[sessionId] = projectedMessages
                if (dependencies.currentSessionId() != sessionId) {
                    return@collect
                }
                stateStore.update {
                    it.copy(
                        messages = projectedMessages,
                        messagesLoading = false,
                        onboardingCompleted = onboardingCfg.completed,
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
        stateStore.updateSession { it.copy(input = value) }
    }

    fun sendMessage() {
        val text = stateStore.value.input.trim()
        val attachments = stateStore.value.composerAttachments
        val hasAttachments = attachments.isNotEmpty()
        if ((text.isBlank() && !hasAttachments) || stateStore.value.isGenerating || stateStore.value.composerImporting) return
        val optimisticMessage = UiMessage(
            id = nextOptimisticMessageId--,
            role = "user",
            content = text,
            createdAt = System.currentTimeMillis(),
            attachments = attachments.map { it.attachment }
        )
        stateStore.updateSession {
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


    fun selectSession(sessionId: String) {
        val sid = sessionId.trim().ifBlank { AppSession.LOCAL_SESSION_ID }
        if (sid == dependencies.currentSessionId()) {
            return
        }
        dependencies.setCurrentSessionId(sid)
        dependencies.saveLastActiveSessionId(sid)
        val title = stateStore.value.sessions.firstOrNull { it.id == sid }?.title ?: sid
        val cachedMessages = projectedMessagesBySession[sid]
        stateStore.updateSession {
            it.copy(
                currentSessionId = sid,
                currentSessionTitle = title,
                isGenerating = dependencies.computeIsGeneratingForSession(sid),
                messages = cachedMessages.orEmpty(),
                messagesLoading = cachedMessages == null,
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
            stateStore.updateShell { it.copy(settingsInfo = "Session name is required.") }
            return
        }
        actions.createSession(title)
    }

    fun renameSession(sessionId: String, displayName: String) {
        val sid = sessionId.trim()
        if (sid.isBlank()) return
        if (sid == AppSession.LOCAL_SESSION_ID) {
            stateStore.updateShell { it.copy(settingsInfo = "LOCAL session cannot be renamed.") }
            return
        }
        val title = displayName.trim()
        if (title.isBlank()) {
            stateStore.updateShell { it.copy(settingsInfo = "Session name is required.") }
            return
        }
        actions.renameSession(sid, title)
    }

    fun deleteSession(sessionId: String) {
        val sid = sessionId.trim()
        if (sid.isBlank()) return
        if (sid == AppSession.LOCAL_SESSION_ID) {
            stateStore.updateShell { it.copy(settingsInfo = "Local session cannot be deleted.") }
            return
        }
        actions.deleteSession(sid)
    }

    fun clear() {
        messagesObserveJob?.cancel()
        messagesObserveJob = null
        projectedMessagesBySession.clear()
    }
}
