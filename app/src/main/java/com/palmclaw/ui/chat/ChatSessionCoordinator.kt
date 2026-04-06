package com.palmclaw.ui

import com.palmclaw.config.AppSession
import com.palmclaw.config.OnboardingConfig
import com.palmclaw.storage.entities.MessageEntity
import com.palmclaw.storage.entities.SessionEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
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
        val observeMessagesSource: (String) -> Flow<List<MessageEntity>>,
        val buildSessionSummaries: (List<SessionEntity>) -> List<UiSessionSummary>,
        val buildConnectedChannelsOverview: (List<UiSessionSummary>) -> List<UiConnectedChannelSummary>,
        val mapObservedMessagesToUi: (List<MessageEntity>) -> List<UiMessage>,
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
            dependencies.observeMessagesSource(sessionId).collectLatest { messages ->
                val onboardingCfg = dependencies.resolveOnboardingConfig()
                stateStore.update {
                    it.copy(
                        messages = dependencies.mapObservedMessagesToUi(messages),
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
        if (text.isBlank() || stateStore.value.isGenerating) return
        stateStore.updateSession { it.copy(input = "", isGenerating = true) }
        actions.sendMessage(text)
    }

    fun stopGeneration() = actions.stopGeneration()


    fun selectSession(sessionId: String) {
        val sid = sessionId.trim().ifBlank { AppSession.LOCAL_SESSION_ID }
        dependencies.setCurrentSessionId(sid)
        dependencies.saveLastActiveSessionId(sid)
        val title = stateStore.value.sessions.firstOrNull { it.id == sid }?.title ?: sid
        stateStore.updateSession {
            it.copy(
                currentSessionId = sid,
                currentSessionTitle = title,
                isGenerating = dependencies.computeIsGeneratingForSession(sid)
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
    }
}
