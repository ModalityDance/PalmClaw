package com.palmclaw.ui

import com.palmclaw.config.AppSession
import com.palmclaw.config.OnboardingConfig
import com.palmclaw.storage.entities.MessageEntity
import com.palmclaw.storage.entities.SessionEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Test

class ChatSessionCoordinatorEdgeCaseTest {

    @Test
    fun `observeSessions falls back to local session when current session disappears`() {
        runBlocking {
            var currentSessionId = "missing-session"
            var savedSessionId = ""
            var observedSessionId = ""
            val stateStore = ChatStateStore(ChatUiState())
            val coordinator = ChatSessionCoordinator(
                scope = this,
                stateStore = stateStore,
                dependencies = ChatSessionCoordinator.Dependencies(
                    currentSessionId = { currentSessionId },
                    setCurrentSessionId = { currentSessionId = it },
                    saveLastActiveSessionId = { savedSessionId = it },
                    computeIsGeneratingForSession = { false },
                    observeSessionsSource = {
                        flowOf(
                            listOf(
                                SessionEntity(
                                    id = AppSession.LOCAL_SESSION_ID,
                                    title = AppSession.LOCAL_SESSION_TITLE,
                                    createdAt = 0L,
                                    updatedAt = 0L
                                ),
                                SessionEntity(
                                    id = "session-a",
                                    title = "Session A",
                                    createdAt = 1L,
                                    updatedAt = 1L
                                )
                            )
                        )
                    },
                    observeMessagesSource = { sessionId ->
                        observedSessionId = sessionId
                        flowOf(emptyList<MessageEntity>())
                    },
                    buildSessionSummaries = { sessions ->
                        sessions.map {
                            UiSessionSummary(
                                id = it.id,
                                title = it.title,
                                isLocal = it.id == AppSession.LOCAL_SESSION_ID
                            )
                        }
                    },
                    buildConnectedChannelsOverview = { emptyList<UiConnectedChannelSummary>() },
                    mapObservedMessagesToUi = { emptyList<UiMessage>() },
                    resolveOnboardingConfig = { OnboardingConfig() }
                ),
                actions = ChatSessionCoordinator.Actions(
                    bootstrapLocalSessions = {},
                    sendMessage = {},
                    stopGeneration = {},
                    createSession = {},
                    renameSession = { _, _ -> },
                    deleteSession = {}
                )
            )

            coordinator.observeSessions()
            repeat(3) { yield() }

            assertEquals(AppSession.LOCAL_SESSION_ID, currentSessionId)
            assertEquals(AppSession.LOCAL_SESSION_ID, savedSessionId)
            assertEquals(AppSession.LOCAL_SESSION_ID, observedSessionId)
            assertEquals(AppSession.LOCAL_SESSION_ID, stateStore.value.currentSessionId)
            assertEquals(AppSession.LOCAL_SESSION_TITLE, stateStore.value.currentSessionTitle)
        }
    }

    @Test
    fun `rename and delete reject local session`() {
        val stateStore = ChatStateStore(ChatUiState())
        val coordinator = basicCoordinator(stateStore)

        coordinator.renameSession(AppSession.LOCAL_SESSION_ID, "Renamed")
        assertEquals("LOCAL session cannot be renamed.", stateStore.value.settingsInfo)

        coordinator.deleteSession(AppSession.LOCAL_SESSION_ID)
        assertEquals("Local session cannot be deleted.", stateStore.value.settingsInfo)
    }

    @Test
    fun `sendMessage ignores blank input and in-progress generation`() {
        var sentMessages = 0
        val stateStore = ChatStateStore(ChatUiState(input = "   "))
        val coordinator = basicCoordinator(stateStore, onSend = { sentMessages += 1 })

        coordinator.sendMessage()
        stateStore.updateSession { it.copy(input = "hello", isGenerating = true) }
        coordinator.sendMessage()

        assertEquals(0, sentMessages)
    }

    @Test
    fun `createSession rejects blank name`() {
        val stateStore = ChatStateStore(ChatUiState())
        val coordinator = basicCoordinator(stateStore)

        coordinator.createSession("   ")

        assertEquals("Session name is required.", stateStore.value.settingsInfo)
    }

    private fun basicCoordinator(
        stateStore: ChatStateStore,
        onSend: (String) -> Unit = {}
    ): ChatSessionCoordinator {
        return ChatSessionCoordinator(
            scope = CoroutineScope(Job()),
            stateStore = stateStore,
            dependencies = ChatSessionCoordinator.Dependencies(
                currentSessionId = { AppSession.LOCAL_SESSION_ID },
                setCurrentSessionId = {},
                saveLastActiveSessionId = {},
                computeIsGeneratingForSession = { false },
                observeSessionsSource = { flowOf(emptyList<SessionEntity>()) },
                observeMessagesSource = { flowOf(emptyList<MessageEntity>()) },
                buildSessionSummaries = { emptyList<UiSessionSummary>() },
                buildConnectedChannelsOverview = { emptyList<UiConnectedChannelSummary>() },
                mapObservedMessagesToUi = { emptyList<UiMessage>() },
                resolveOnboardingConfig = { OnboardingConfig() }
            ),
            actions = ChatSessionCoordinator.Actions(
                bootstrapLocalSessions = {},
                sendMessage = onSend,
                stopGeneration = {},
                createSession = {},
                renameSession = { _, _ -> },
                deleteSession = {}
            )
        )
    }
}
