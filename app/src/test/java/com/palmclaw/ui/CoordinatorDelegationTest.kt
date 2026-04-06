package com.palmclaw.ui

import com.palmclaw.config.AppSession
import com.palmclaw.config.OnboardingConfig
import com.palmclaw.config.TokenUsageStats
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield

class CoordinatorDelegationTest {

    @Test
    fun sessionCoordinator_delegatesSuccessAndFailure() {
        runBlocking {
            var currentSessionId = AppSession.LOCAL_SESSION_ID
            var savedSessionId = ""
            var observedSessionId = ""
            var sentMessage = ""
            var createdTitle = ""
            var renamedSessionId = ""
            var renamedTitle = ""
            var deletedSessionId = ""
            val stateStore = ChatStateStore(
                ChatUiState(
                    sessions = listOf(
                        UiSessionSummary(
                            id = AppSession.LOCAL_SESSION_ID,
                            title = AppSession.LOCAL_SESSION_TITLE,
                            isLocal = true
                        ),
                        UiSessionSummary(
                            id = "session-1",
                            title = "Session 1",
                            isLocal = false
                        )
                    )
                )
            )
            val coordinator = ChatSessionCoordinator(
                scope = this,
                stateStore = stateStore,
                dependencies = ChatSessionCoordinator.Dependencies(
                    currentSessionId = { currentSessionId },
                    setCurrentSessionId = { currentSessionId = it },
                    saveLastActiveSessionId = { savedSessionId = it },
                    computeIsGeneratingForSession = { it == "session-1" },
                    observeSessionsSource = {
                        flowOf(
                            listOf(
                                UiSessionSummary(
                                    id = AppSession.LOCAL_SESSION_ID,
                                    title = AppSession.LOCAL_SESSION_TITLE,
                                    isLocal = true
                                ),
                                UiSessionSummary(
                                    id = "session-1",
                                    title = "Session 1",
                                    isLocal = false
                                )
                            ).map {
                                com.palmclaw.storage.entities.SessionEntity(
                                    id = it.id,
                                    title = it.title,
                                    createdAt = if (it.isLocal) 0L else 1L,
                                    updatedAt = if (it.isLocal) 0L else 1L
                                )
                            }
                        )
                    },
                    observeMessagesSource = { sessionId ->
                        observedSessionId = sessionId
                        flowOf(
                            listOf(
                                com.palmclaw.storage.entities.MessageEntity(
                                    id = 1L,
                                    sessionId = sessionId,
                                    role = "user",
                                    content = "observed",
                                    createdAt = 1L
                                )
                            )
                        )
                    },
                    buildSessionSummaries = {
                        listOf(
                            UiSessionSummary(
                                id = AppSession.LOCAL_SESSION_ID,
                                title = AppSession.LOCAL_SESSION_TITLE,
                                isLocal = true
                            ),
                            UiSessionSummary(
                                id = "session-1",
                                title = "Session 1",
                                isLocal = false
                            )
                        )
                    },
                    buildConnectedChannelsOverview = { emptyList() },
                    mapObservedMessagesToUi = { messages ->
                        messages.map {
                            UiMessage(
                                id = it.id,
                                role = it.role,
                                content = it.content,
                                createdAt = it.createdAt
                            )
                        }
                    },
                    resolveOnboardingConfig = {
                        OnboardingConfig(
                            completed = true,
                            userDisplayName = "You",
                            agentDisplayName = "PalmClaw"
                        )
                    }
                ),
                actions = ChatSessionCoordinator.Actions(
                    bootstrapLocalSessions = {},
                    sendMessage = { sentMessage = it },
                    stopGeneration = { throw IllegalStateException("stop") },
                    createSession = { createdTitle = it },
                    renameSession = { sessionId, title ->
                        renamedSessionId = sessionId
                        renamedTitle = title
                    },
                    deleteSession = { deletedSessionId = it }
                )
            )

            coordinator.observeSessions()
            yield()
            coordinator.onInputChanged(" hello ")
            coordinator.sendMessage()
            coordinator.selectSession(" session-1 ")
            repeat(5) {
                if (stateStore.value.messages.isNotEmpty()) return@repeat
                yield()
            }
            coordinator.createSession(" New Session ")
            coordinator.renameSession("session-1", " Renamed ")
            coordinator.deleteSession("session-1")

            assertEquals("", stateStore.value.input)
            assertTrue(stateStore.value.isGenerating)
            assertEquals("hello", sentMessage)
            assertEquals("session-1", currentSessionId)
            assertEquals("session-1", savedSessionId)
            assertEquals("session-1", observedSessionId)
            assertEquals("Session 1", stateStore.value.currentSessionTitle)
            assertTrue(stateStore.value.isGenerating)
            assertTrue(stateStore.value.messages.isNotEmpty())
            assertEquals("observed", stateStore.value.messages.first().content)
            assertEquals("New Session", createdTitle)
            assertEquals("session-1", renamedSessionId)
            assertEquals("Renamed", renamedTitle)
            assertEquals("session-1", deletedSessionId)
            coordinator.createSession("   ")
            assertEquals("Session name is required.", stateStore.value.settingsInfo)

            try {
                coordinator.stopGeneration()
            } catch (error: IllegalStateException) {
                assertEquals("stop", error.message)
                return@runBlocking
            }
            throw AssertionError("Expected failure to propagate")
        }
    }

    @Test
    fun providerCoordinator_delegatesSuccessAndFailure() {
        var persistedDraft = false
        val stateStore = ChatStateStore(
            ChatUiState(
                settingsProviderConfigs = listOf(
                    UiProviderConfig(
                        id = "cfg-1",
                        providerName = "custom",
                        customName = "Primary",
                        apiKey = "secret",
                        model = "gpt-test",
                        baseUrl = "https://example.com"
                    )
                )
            )
        )
        val coordinator = ProviderSettingsCoordinator(
            stateStore = stateStore,
            clearTokenUsageStats = {
                TokenUsageStats(
                    inputTokens = 1L,
                    outputTokens = 2L,
                    totalTokens = 3L,
                    cachedInputTokens = 4L,
                    requests = 5L
                )
            },
            persistOnboardingProviderDraftIfNeeded = { persistedDraft = true },
            actions = ProviderSettingsCoordinator.Actions(
                setActiveProviderConfig = { _ -> },
                deleteProviderConfig = { _ -> },
                saveProviderSettings = { _, _ -> throw IllegalStateException("provider") },
                saveAgentRuntimeSettings = { _, _ -> },
                testProviderSettings = {}
            )
        )

        coordinator.clearProviderTokenUsageStats()
        coordinator.onSettingsProviderChanged("openai")
        coordinator.onSettingsModelChanged("gpt-4.1")
        coordinator.selectProviderConfigForEditing("cfg-1")
        assertEquals(3L, stateStore.value.settingsTokenTotal)
        assertEquals("cfg-1", stateStore.value.settingsEditingProviderConfigId)
        assertEquals("custom", stateStore.value.settingsProvider)
        assertEquals("Primary", stateStore.value.settingsProviderCustomName)
        assertEquals("gpt-test", stateStore.value.settingsModel)
        assertTrue(persistedDraft)

        try {
            coordinator.saveProviderSettings(true, true)
        } catch (error: IllegalStateException) {
            assertEquals("provider", error.message)
            return
        }
        throw AssertionError("Expected failure to propagate")
    }

    @Test
    fun channelBindingCoordinator_delegatesSuccessAndFailure() {
        var savedSessionId = ""
        val coordinator = ChannelBindingCoordinator(
            ChannelBindingCoordinator.Actions(
                saveSessionChannelBinding = { savedSessionId = it.sessionId },
                getSessionChannelDraft = { _ -> throw IllegalStateException("draft") },
                setSessionChannelEnabled = { _, _ -> },
                discoverTelegramChatsForBinding = { _ -> },
                clearTelegramChatDiscovery = {},
                discoverFeishuChatsForBinding = { _, _, _, _ -> },
                clearFeishuChatDiscovery = {},
                discoverEmailSendersForBinding = { _, _, _, _, _, _, _, _, _, _, _ -> },
                clearEmailSenderDiscovery = {},
                discoverWeComChatsForBinding = { _, _ -> },
                clearWeComChatDiscovery = {},
                refreshSessionConnectionStatus = {}
            )
        )

        coordinator.saveSessionChannelBinding(
            sessionId = "session-2",
            enabled = true,
            channel = "telegram",
            chatId = "123",
            targetDisplayName = "",
            telegramBotToken = "",
            telegramAllowedChatId = "",
            discordBotToken = "",
            discordResponseMode = "mention",
            discordAllowedUserIds = "",
            slackBotToken = "",
            slackAppToken = "",
            slackResponseMode = "mention",
            slackAllowedUserIds = "",
            feishuAppId = "",
            feishuAppSecret = "",
            feishuEncryptKey = "",
            feishuVerificationToken = "",
            feishuResponseMode = "mention",
            feishuAllowedOpenIds = "",
            emailConsentGranted = false,
            emailImapHost = "",
            emailImapPort = "993",
            emailImapUsername = "",
            emailImapPassword = "",
            emailSmtpHost = "",
            emailSmtpPort = "587",
            emailSmtpUsername = "",
            emailSmtpPassword = "",
            emailFromAddress = "",
            emailAutoReplyEnabled = true,
            wecomBotId = "",
            wecomSecret = "",
            wecomAllowedUserIds = ""
        )
        assertEquals("session-2", savedSessionId)

        try {
            coordinator.getSessionChannelDraft("session-2")
        } catch (error: IllegalStateException) {
            assertEquals("draft", error.message)
            return
        }
        throw AssertionError("Expected failure to propagate")
    }

    @Test
    fun runtimeCoordinator_delegatesSuccessAndFailure() {
        var refreshed = false
        val stateStore = ChatStateStore(
            ChatUiState(
                settingsMcpServers = listOf(UiMcpServerConfig(id = "server-1"))
            )
        )
        val coordinator = RuntimeCoordinator(
            stateStore = stateStore,
            actions = RuntimeCoordinator.Actions(
                loadSettingsIntoState = {},
                observeRuntimeStatus = {},
                observeAlwaysOnStatus = {},
                startGatewayIfEnabled = {},
                refreshAlwaysOnDiagnostics = { refreshed = true },
                refreshCronJobs = {},
                setCronJobEnabled = { _, _ -> },
                runCronJobNow = { _ -> },
                removeCronJob = { _ -> },
                triggerHeartbeatNow = {},
                loadHeartbeatDocument = {},
                saveHeartbeatDocument = { _, _ -> },
                refreshCronLogs = {},
                clearCronLogs = {},
                refreshAgentLogs = {},
                clearAgentLogs = {},
                saveCronSettings = { _, _ -> },
                saveHeartbeatSettings = { _, _ -> },
                saveAlwaysOnSettings = { _, _ -> },
                saveChannelsSettings = { _, _ -> },
                saveMcpSettings = { _, _ -> throw IllegalStateException("runtime") }
            )
        )

        coordinator.refreshAlwaysOnDiagnostics()
        assertTrue(refreshed)
        coordinator.onSettingsCronEnabledChanged(true)
        coordinator.updateSettingsMcpServerName("server-1", "Primary")
        assertTrue(stateStore.value.settingsCronEnabled)
        assertEquals("Primary", stateStore.value.settingsMcpServers.first().serverName)

        try {
            coordinator.saveMcpSettings(true, true)
        } catch (error: IllegalStateException) {
            assertEquals("runtime", error.message)
            return
        }
        throw AssertionError("Expected failure to propagate")
    }
}
