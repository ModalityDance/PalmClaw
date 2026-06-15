package com.palmclaw.ui

import com.palmclaw.config.AppSession
import com.palmclaw.config.OnboardingConfig
import com.palmclaw.storage.entities.MessageEntity
import com.palmclaw.storage.entities.SessionEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
                    observeRecentMessagesSource = { sessionId, _ ->
                        observedSessionId = sessionId
                        flowOf(emptyList<MessageEntity>())
                    },
                    loadMessagesBeforeSource = { _, _, _, _ -> emptyList() },
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
                    mapObservedMessagesToUi = { _, _ -> emptyList<UiMessage>() },
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
        stateStore.updateChatComposerState { it.copy(input = "hello", isGenerating = true) }
        coordinator.sendMessage()

        assertEquals(0, sentMessages)
    }

    @Test
    fun `sendMessage shows optimistic user message immediately`() {
        var sentMessage = ""
        val stateStore = ChatStateStore(ChatUiState(input = " hello "))
        val coordinator = basicCoordinator(stateStore, onSend = { sentMessage = it })

        coordinator.sendMessage()

        assertEquals("hello", sentMessage)
        assertEquals("", stateStore.value.input)
        assertTrue(stateStore.value.isGenerating)
        assertTrue(stateStore.chatTimelineState.value.isGenerating)
        assertTrue(stateStore.chatComposerState.value.isGenerating)
        assertEquals("", stateStore.chatComposerState.value.input)
        assertEquals("hello", stateStore.value.messages.single().content)
        assertEquals("user", stateStore.value.messages.single().role)
    }

    @Test
    fun `sendMessage keeps optimistic user message when stale database flow arrives`() {
        runBlocking {
            val observedMessages = MutableSharedFlow<List<MessageEntity>>(replay = 1)
            val stateStore = ChatStateStore(ChatUiState(input = "hello"))
            val coordinator = observedMessagesCoordinator(
                scope = this,
                stateStore = stateStore,
                observedMessages = observedMessages
            )

            try {
                coordinator.observeMessages(AppSession.LOCAL_SESSION_ID)
                observedMessages.emit(emptyList())
                repeat(20) {
                    if (!stateStore.chatTimelineState.value.messagesLoading) return@repeat
                    delay(10)
                }

                coordinator.sendMessage()
                observedMessages.emit(emptyList())
                repeat(20) {
                    if (stateStore.chatTimelineState.value.messages.any { it.id < 0 }) return@repeat
                    delay(10)
                }

                val messages = stateStore.chatTimelineState.value.messages
                assertEquals(1, messages.size)
                assertEquals("hello", messages.single().content)
                assertTrue(messages.single().id < 0)
                assertTrue(stateStore.chatTimelineState.value.isGenerating)
            } finally {
                coordinator.clear()
            }
        }
    }

    @Test
    fun `sendMessage removes optimistic duplicate when database confirms user message`() {
        runBlocking {
            val observedMessages = MutableSharedFlow<List<MessageEntity>>(replay = 1)
            val stateStore = ChatStateStore(ChatUiState(input = "hello"))
            val coordinator = observedMessagesCoordinator(
                scope = this,
                stateStore = stateStore,
                observedMessages = observedMessages
            )

            try {
                coordinator.observeMessages(AppSession.LOCAL_SESSION_ID)
                observedMessages.emit(emptyList())
                repeat(20) {
                    if (!stateStore.chatTimelineState.value.messagesLoading) return@repeat
                    delay(10)
                }

                coordinator.sendMessage()
                val optimisticCreatedAt = stateStore.chatTimelineState.value.messages.single().createdAt
                observedMessages.emit(
                    listOf(
                        MessageEntity(
                            id = 42L,
                            sessionId = AppSession.LOCAL_SESSION_ID,
                            role = "user",
                            content = "hello",
                            createdAt = optimisticCreatedAt + 1L
                        )
                    )
                )
                repeat(20) {
                    if (stateStore.chatTimelineState.value.messages.singleOrNull()?.id == 42L) return@repeat
                    delay(10)
                }

                val messages = stateStore.chatTimelineState.value.messages
                assertEquals(1, messages.size)
                assertEquals(42L, messages.single().id)
                assertEquals("hello", messages.single().content)
            } finally {
                coordinator.clear()
            }
        }
    }

    @Test
    fun `sendMessage rolls back optimistic message when action throws synchronously`() {
        val stateStore = ChatStateStore(ChatUiState(input = "hello"))
        val coordinator = basicCoordinator(
            stateStore = stateStore,
            onSend = { error("boom") }
        )

        coordinator.sendMessage()

        assertEquals(emptyList<UiMessage>(), stateStore.chatTimelineState.value.messages)
        assertEquals("hello", stateStore.chatComposerState.value.input)
        assertFalse(stateStore.chatTimelineState.value.isGenerating)
        assertFalse(stateStore.chatComposerState.value.isGenerating)
        assertEquals("Send failed: boom", stateStore.settingsShellState.value.info)
    }

    @Test
    fun `selectSession clears stale messages before observed messages arrive`() {
        var currentSessionId = AppSession.LOCAL_SESSION_ID
        val stateStore = ChatStateStore(
            ChatUiState(
                messages = listOf(UiMessage(id = 1L, role = "assistant", content = "old", createdAt = 1L)),
                sessions = listOf(
                    UiSessionSummary(id = AppSession.LOCAL_SESSION_ID, title = AppSession.LOCAL_SESSION_TITLE, isLocal = true),
                    UiSessionSummary(id = "session-2", title = "Session 2", isLocal = false)
                )
            )
        )
        val coordinator = basicCoordinator(
            stateStore = stateStore,
            currentSessionId = { currentSessionId },
            setCurrentSessionId = { currentSessionId = it }
        )

        coordinator.selectSession("session-2")

        assertEquals("session-2", stateStore.value.currentSessionId)
        assertEquals("Session 2", stateStore.value.currentSessionTitle)
        assertTrue(stateStore.value.messages.isEmpty())
        assertTrue(stateStore.value.messagesLoading)
    }

    @Test
    fun `selectSession restores cached messages immediately for visited session`() {
        runBlocking {
            var currentSessionId = "session-2"
            var observedLimit = 0
            val session2Messages = MutableSharedFlow<List<MessageEntity>>(replay = 1)
            val stateStore = ChatStateStore(
                ChatUiState(
                    currentSessionId = "session-2",
                    currentSessionTitle = "Session 2",
                    sessions = listOf(
                        UiSessionSummary(id = AppSession.LOCAL_SESSION_ID, title = AppSession.LOCAL_SESSION_TITLE, isLocal = true),
                        UiSessionSummary(id = "session-2", title = "Session 2", isLocal = false)
                    )
                )
            )
            val coordinator = ChatSessionCoordinator(
                scope = this,
                stateStore = stateStore,
                dependencies = ChatSessionCoordinator.Dependencies(
                    currentSessionId = { currentSessionId },
                    setCurrentSessionId = { currentSessionId = it },
                    saveLastActiveSessionId = {},
                    computeIsGeneratingForSession = { false },
                    observeSessionsSource = { flowOf(emptyList<SessionEntity>()) },
                    observeRecentMessagesSource = { sessionId, limit ->
                        observedLimit = limit
                        if (sessionId == "session-2") {
                            session2Messages
                        } else {
                            flowOf(emptyList<MessageEntity>())
                        }
                    },
                    loadMessagesBeforeSource = { _, _, _, _ -> emptyList() },
                    buildSessionSummaries = { emptyList() },
                    buildConnectedChannelsOverview = { emptyList() },
                    mapObservedMessagesToUi = { _, messages ->
                        messages.map {
                            UiMessage(
                                id = it.id,
                                role = it.role,
                                content = it.content,
                                createdAt = it.createdAt
                            )
                        }
                    },
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

            try {
                coordinator.observeMessages("session-2")
                session2Messages.emit(
                    listOf(
                        MessageEntity(
                            id = 10L,
                            sessionId = "session-2",
                            role = "assistant",
                            content = "cached",
                            createdAt = 10L
                        )
                    )
                )
                repeat(20) {
                    if (stateStore.value.messages.isNotEmpty()) return@repeat
                    delay(10)
                }
                currentSessionId = AppSession.LOCAL_SESSION_ID
                coordinator.selectSession(AppSession.LOCAL_SESSION_ID)

                coordinator.selectSession("session-2")

                assertEquals("session-2", stateStore.value.currentSessionId)
                assertEquals("cached", stateStore.value.messages.single().content)
                assertEquals(false, stateStore.value.messagesLoading)
                assertEquals(ChatSessionCoordinator.INITIAL_MESSAGE_PAGE_SIZE, observedLimit)
            } finally {
                coordinator.clear()
            }
        }
    }

    @Test
    fun `selectSession restores generating state when returning to active session`() {
        var currentSessionId = "session-active"
        val generatingSessions = mutableSetOf("session-active")
        val stateStore = ChatStateStore(
            ChatUiState(
                currentSessionId = "session-active",
                sessions = listOf(
                    UiSessionSummary(id = "session-active", title = "Active", isLocal = false),
                    UiSessionSummary(id = "session-idle", title = "Idle", isLocal = false)
                ),
                messages = listOf(
                    UiMessage(id = 1L, role = "user", content = "work", createdAt = 1L)
                ),
                isGenerating = true
            )
        )
        val coordinator = basicCoordinator(
            stateStore = stateStore,
            currentSessionId = { currentSessionId },
            setCurrentSessionId = { currentSessionId = it },
            isGeneratingForSession = { it in generatingSessions }
        )

        coordinator.selectSession("session-idle")

        assertEquals("session-idle", stateStore.chatTimelineState.value.currentSessionId)
        assertFalse(stateStore.chatTimelineState.value.isGenerating)
        assertFalse(stateStore.chatComposerState.value.isGenerating)

        coordinator.selectSession("session-active")

        assertEquals("session-active", stateStore.chatTimelineState.value.currentSessionId)
        assertTrue(stateStore.chatTimelineState.value.isGenerating)
        assertTrue(stateStore.chatComposerState.value.isGenerating)
    }

    @Test
    fun `loadOlderMessages prepends older page and updates pagination state`() {
        runBlocking {
            var currentSessionId = "session-page"
            val observedMessages = MutableSharedFlow<List<MessageEntity>>(replay = 1)
            val loadedBefore = mutableListOf<Pair<Long, Long>>()
            val stateStore = ChatStateStore(
                ChatUiState(
                    currentSessionId = "session-page",
                    sessions = listOf(
                        UiSessionSummary(id = "session-page", title = "Session Page", isLocal = false)
                    )
                )
            )
            val coordinator = ChatSessionCoordinator(
                scope = this,
                stateStore = stateStore,
                dependencies = ChatSessionCoordinator.Dependencies(
                    currentSessionId = { currentSessionId },
                    setCurrentSessionId = { currentSessionId = it },
                    saveLastActiveSessionId = {},
                    computeIsGeneratingForSession = { false },
                    observeSessionsSource = { flowOf(emptyList<SessionEntity>()) },
                    observeRecentMessagesSource = { _, _ -> observedMessages },
                    loadMessagesBeforeSource = { _, beforeCreatedAt, beforeId, _ ->
                        loadedBefore += beforeCreatedAt to beforeId
                        listOf(
                            MessageEntity(
                                id = 1L,
                                sessionId = "session-page",
                                role = "user",
                                content = "older",
                                createdAt = 1L
                            )
                        )
                    },
                    buildSessionSummaries = { emptyList() },
                    buildConnectedChannelsOverview = { emptyList() },
                    mapObservedMessagesToUi = { _, messages ->
                        messages.map {
                            UiMessage(
                                id = it.id,
                                role = it.role,
                                content = it.content,
                                createdAt = it.createdAt
                            )
                        }
                    },
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

            try {
                coordinator.observeMessages("session-page")
                observedMessages.emit(
                    List(ChatSessionCoordinator.INITIAL_MESSAGE_PAGE_SIZE) { index ->
                        MessageEntity(
                            id = (index + 10).toLong(),
                            sessionId = "session-page",
                            role = if (index % 2 == 0) "user" else "assistant",
                            content = "recent-$index",
                            createdAt = (index + 10).toLong()
                        )
                    }
                )
                repeat(20) {
                    if (stateStore.chatTimelineState.value.canLoadOlderMessages) return@repeat
                    delay(10)
                }

                coordinator.loadOlderMessages()
                repeat(20) {
                    if (stateStore.chatTimelineState.value.messages.firstOrNull()?.content == "older") return@repeat
                    delay(10)
                }

                assertEquals(10L to 10L, loadedBefore.single())
                assertEquals("older", stateStore.chatTimelineState.value.messages.first().content)
                assertEquals(false, stateStore.chatTimelineState.value.messagesLoadingOlder)
            } finally {
                coordinator.clear()
            }
        }
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
        onSend: (String) -> Unit = {},
        currentSessionId: () -> String = { AppSession.LOCAL_SESSION_ID },
        setCurrentSessionId: (String) -> Unit = {},
        isGeneratingForSession: (String) -> Boolean = { false }
    ): ChatSessionCoordinator {
        return ChatSessionCoordinator(
            scope = CoroutineScope(Job()),
            stateStore = stateStore,
            dependencies = ChatSessionCoordinator.Dependencies(
                currentSessionId = currentSessionId,
                setCurrentSessionId = setCurrentSessionId,
                saveLastActiveSessionId = {},
                computeIsGeneratingForSession = isGeneratingForSession,
                observeSessionsSource = { flowOf(emptyList<SessionEntity>()) },
                observeRecentMessagesSource = { _, _ -> flowOf(emptyList<MessageEntity>()) },
                loadMessagesBeforeSource = { _, _, _, _ -> emptyList() },
                buildSessionSummaries = { emptyList<UiSessionSummary>() },
                buildConnectedChannelsOverview = { emptyList<UiConnectedChannelSummary>() },
                mapObservedMessagesToUi = { _, _ -> emptyList<UiMessage>() },
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

    private fun observedMessagesCoordinator(
        scope: CoroutineScope,
        stateStore: ChatStateStore,
        observedMessages: MutableSharedFlow<List<MessageEntity>>,
        onSend: (String) -> Unit = {}
    ): ChatSessionCoordinator {
        return ChatSessionCoordinator(
            scope = scope,
            stateStore = stateStore,
            dependencies = ChatSessionCoordinator.Dependencies(
                currentSessionId = { AppSession.LOCAL_SESSION_ID },
                setCurrentSessionId = {},
                saveLastActiveSessionId = {},
                computeIsGeneratingForSession = { false },
                observeSessionsSource = { flowOf(emptyList<SessionEntity>()) },
                observeRecentMessagesSource = { _, _ -> observedMessages },
                loadMessagesBeforeSource = { _, _, _, _ -> emptyList() },
                buildSessionSummaries = { emptyList<UiSessionSummary>() },
                buildConnectedChannelsOverview = { emptyList<UiConnectedChannelSummary>() },
                mapObservedMessagesToUi = { _, messages ->
                    messages.map {
                        UiMessage(
                            id = it.id,
                            role = it.role,
                            content = it.content,
                            createdAt = it.createdAt
                        )
                    }
                },
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
