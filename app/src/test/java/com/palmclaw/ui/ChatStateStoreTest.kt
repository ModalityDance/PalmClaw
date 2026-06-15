package com.palmclaw.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ChatStateStoreTest {

    @Test
    fun updateChatContentState_keepsNonChatFieldsUntouched() {
        val store = ChatStateStore(
            ChatUiState(
                input = "draft",
                settingsInfo = "info",
                settingsDarkTheme = true
            )
        )

        store.updateChatContentState { it.copy(input = "updated", currentSessionTitle = "Renamed") }

        assertEquals("updated", store.value.input)
        assertEquals("Renamed", store.value.currentSessionTitle)
        assertEquals("info", store.value.settingsInfo)
        assertEquals(true, store.value.settingsDarkTheme)
    }

    @Test
    fun updateUpdateState_keepsChatDraftUntouched() {
        val store = ChatStateStore(
            ChatUiState(
                input = "keep me",
                settingsUpdateChecking = false
            )
        )

        store.updateUpdateState { it.copy(checking = true, latestVersion = "1.2.3") }

        assertEquals("keep me", store.value.input)
        assertEquals("1.2.3", store.value.settingsLatestVersion)
        assertEquals(true, store.value.settingsUpdateChecking)
    }

    @Test
    fun updateChannelsAndAlwaysOnState_canToggleRuntimeFlagsWithoutChangingOnboardingFields() {
        val store = ChatStateStore(
            ChatUiState(
                onboardingCompleted = true,
                userDisplayName = "User",
                settingsGatewayEnabled = false
            )
        )

        store.updateChannelsSettingsState { it.copy(gatewayEnabled = true) }
        store.updateAlwaysOnState { it.copy(enabled = true) }

        assertEquals("User", store.value.userDisplayName)
        assertEquals(true, store.value.onboardingCompleted)
        assertEquals(true, store.value.settingsGatewayEnabled)
        assertEquals(true, store.value.alwaysOnEnabled)
        assertFalse(store.value.settingsUpdateChecking)
    }

    @Test
    fun sliceFlows_areInitializedFromLegacyState() {
        val initialState = ChatUiState(
            input = "draft",
            settingsApiKey = "provider-secret",
            settingsClawHubSearchQuery = "calendar",
            alwaysOnEnabled = true,
            settingsLatestVersion = "1.2.3"
        )
        val store = ChatStateStore(initialState)

        assertEquals(initialState.toChatContentState(), store.chatContentState.value)
        assertEquals(initialState.toChatTimelineState(), store.chatTimelineState.value)
        assertEquals(initialState.toChatComposerState(), store.chatComposerState.value)
        assertEquals(initialState.toSessionListState(), store.sessionListState.value)
        assertEquals(initialState.toProviderSettingsState(), store.providerSettingsState.value)
        assertEquals(initialState.toSkillsDiscoveryState(), store.skillsDiscoveryState.value)
        assertEquals(initialState.toAlwaysOnSettingsState(), store.alwaysOnSettingsState.value)
        assertEquals(initialState.toUpdateSettingsState(), store.updateSettingsState.value)
    }

    @Test
    fun updateChatComposerState_syncsComposerFieldsWithoutChangingTimelineOrSessionList() {
        val message = UiMessage(id = 1L, role = "assistant", content = "hi", createdAt = 1L)
        val session = UiSessionSummary(id = "session-1", title = "Session 1", isLocal = false)
        val store = ChatStateStore(
            ChatUiState(
                messages = listOf(message),
                input = "draft",
                currentSessionId = "session-1",
                currentSessionTitle = "Session 1",
                sessions = listOf(session)
            )
        )
        val timelineBefore = store.chatTimelineState.value
        val sessionBefore = store.sessionListState.value

        store.updateChatComposerState {
            it.copy(input = "updated", composerAttachmentError = "error")
        }

        assertEquals(timelineBefore, store.chatTimelineState.value)
        assertEquals(sessionBefore, store.sessionListState.value)
        assertEquals("updated", store.chatComposerState.value.input)
        assertEquals("error", store.chatComposerState.value.composerAttachmentError)
        assertEquals("updated", store.value.input)
    }

    @Test
    fun updateChatTimelineState_syncsTimelineFieldsWithoutChangingComposerDraftOrSessionList() {
        val store = ChatStateStore(
            ChatUiState(
                input = "draft",
                currentSessionTitle = "Session 1"
            )
        )
        val composerBefore = store.chatComposerState.value
        val sessionBefore = store.sessionListState.value
        val message = UiMessage(id = 2L, role = "user", content = "hello", createdAt = 2L)

        store.updateChatTimelineState {
            it.copy(messages = listOf(message), messagesLoading = true, isGenerating = true)
        }

        assertEquals(composerBefore.copy(isGenerating = true), store.chatComposerState.value)
        assertEquals(sessionBefore, store.sessionListState.value)
        assertEquals(listOf(message), store.chatTimelineState.value.messages)
        assertEquals(true, store.chatTimelineState.value.messagesLoading)
        assertEquals(true, store.chatTimelineState.value.isGenerating)
        assertEquals(listOf(message), store.value.messages)
    }

    @Test
    fun updateSessionListState_syncsSessionFieldsWithoutChangingComposerOrTimelineMessages() {
        val message = UiMessage(id = 3L, role = "assistant", content = "hi", createdAt = 3L)
        val store = ChatStateStore(
            ChatUiState(
                messages = listOf(message),
                input = "draft"
            )
        )
        val composerBefore = store.chatComposerState.value
        val timelineBefore = store.chatTimelineState.value
        val session = UiSessionSummary(id = "session-2", title = "Session 2", isLocal = false)

        store.updateSessionListState {
            it.copy(
                sessions = listOf(session),
                currentSessionId = "session-2",
                currentSessionTitle = "Session 2"
            )
        }

        assertEquals(composerBefore, store.chatComposerState.value)
        assertEquals(timelineBefore.copy(currentSessionId = "session-2"), store.chatTimelineState.value)
        assertEquals(listOf(session), store.sessionListState.value.sessions)
        assertEquals("session-2", store.sessionListState.value.currentSessionId)
        assertEquals("Session 2", store.value.currentSessionTitle)
    }

    @Test
    fun commitOptimisticSend_appendsMessageClearsComposerAndSetsGeneratingAtomically() {
        val existingMessage = UiMessage(id = 1L, role = "assistant", content = "hi", createdAt = 1L)
        val optimisticMessage = UiMessage(id = -1L, role = "user", content = "hello", createdAt = 2L)
        val draft = UiComposerAttachmentDraft(
            id = "draft-1",
            attachment = UiAttachment(
                reference = "content://attachment",
                kind = UiAttachmentKind.File,
                label = "file.txt"
            )
        )
        val store = ChatStateStore(
            ChatUiState(
                messages = listOf(existingMessage),
                input = " hello ",
                composerAttachments = listOf(draft),
                settingsInfo = "info",
                settingsDarkTheme = true
            )
        )

        val snapshot = store.commitOptimisticSend(optimisticMessage)

        assertEquals(" hello ", snapshot.input)
        assertEquals(listOf(draft), snapshot.composerAttachments)
        assertEquals(listOf(existingMessage, optimisticMessage), store.chatTimelineState.value.messages)
        assertEquals("", store.chatComposerState.value.input)
        assertEquals(emptyList<UiComposerAttachmentDraft>(), store.chatComposerState.value.composerAttachments)
        assertEquals(true, store.chatTimelineState.value.isGenerating)
        assertEquals(true, store.chatComposerState.value.isGenerating)
        assertEquals("info", store.value.settingsInfo)
        assertEquals(true, store.value.settingsDarkTheme)
    }

    @Test
    fun rollbackOptimisticSend_removesMessageAndRestoresDraft() {
        val optimisticMessage = UiMessage(id = -1L, role = "user", content = "hello", createdAt = 2L)
        val draft = UiComposerAttachmentDraft(
            id = "draft-1",
            attachment = UiAttachment(
                reference = "content://attachment",
                kind = UiAttachmentKind.File,
                label = "file.txt"
            )
        )
        val store = ChatStateStore(
            ChatUiState(
                input = "hello",
                composerAttachments = listOf(draft)
            )
        )
        val snapshot = store.commitOptimisticSend(optimisticMessage)

        store.rollbackOptimisticSend(optimisticMessage.id, snapshot)

        assertEquals(emptyList<UiMessage>(), store.chatTimelineState.value.messages)
        assertEquals("hello", store.chatComposerState.value.input)
        assertEquals(listOf(draft), store.chatComposerState.value.composerAttachments)
        assertEquals(false, store.chatTimelineState.value.isGenerating)
        assertEquals(false, store.chatComposerState.value.isGenerating)
    }

    @Test
    fun updateProviderSettingsState_refreshesProviderSliceWithoutChangingChatSlice() {
        val store = ChatStateStore(
            ChatUiState(
                input = "draft",
                currentSessionTitle = "Chat",
                settingsApiKey = "old-secret"
            )
        )
        val chatBefore = store.chatContentState.value

        store.updateProviderSettingsState {
            it.copy(apiKeyDraft = "new-secret", model = "new-model")
        }

        assertEquals(chatBefore, store.chatContentState.value)
        assertEquals("new-secret", store.providerSettingsState.value.apiKeyDraft)
        assertEquals("new-model", store.providerSettingsState.value.model)
    }

    @Test
    fun updateChatContentState_refreshesChatSliceWithoutChangingSkillSlice() {
        val store = ChatStateStore(
            ChatUiState(
                input = "draft",
                settingsClawHubSearchQuery = "calendar"
            )
        )
        val skillsBefore = store.skillsDiscoveryState.value

        store.updateChatContentState {
            it.copy(input = "updated", currentSessionTitle = "Renamed")
        }

        assertEquals(skillsBefore, store.skillsDiscoveryState.value)
        assertEquals("updated", store.chatContentState.value.input)
        assertEquals("Renamed", store.chatContentState.value.currentSessionTitle)
    }

    @Test
    fun updateSkillsState_syncsLegacyStateAndSkillsSliceOnly() {
        val store = ChatStateStore(
            ChatUiState(
                input = "draft",
                settingsClawHubSearchQuery = "calendar"
            )
        )
        val chatBefore = store.chatContentState.value
        val providerBefore = store.providerSettingsState.value

        store.updateSkillsState {
            it.copy(
                clawHubSearchQuery = "files",
                skillsLoading = true
            )
        }

        assertEquals(chatBefore, store.chatContentState.value)
        assertEquals(providerBefore, store.providerSettingsState.value)
        assertEquals("files", store.skillsDiscoveryState.value.clawHubSearchQuery)
        assertEquals(true, store.skillsDiscoveryState.value.skillsLoading)
        assertEquals("files", store.value.settingsClawHubSearchQuery)
        assertEquals(true, store.value.settingsSkillsLoading)
    }

    @Test
    fun updateChatContentState_syncsLegacyStateWithoutChangingSettingsSlices() {
        val store = ChatStateStore(
            ChatUiState(
                input = "draft",
                settingsApiKey = "provider-secret",
                settingsClawHubSearchQuery = "calendar",
                settingsMaxToolRounds = "4"
            )
        )
        val providerBefore = store.providerSettingsState.value
        val skillsBefore = store.skillsDiscoveryState.value
        val toolBefore = store.toolSettingsState.value
        val message = UiMessage(
            id = 1L,
            role = "user",
            content = "hello",
            createdAt = 123L
        )
        val session = UiSessionSummary(
            id = "session-1",
            title = "Session 1",
            isLocal = false
        )

        store.updateChatContentState {
            it.copy(
                messages = listOf(message),
                messagesLoading = true,
                input = "updated",
                isGenerating = true,
                currentSessionId = "session-1",
                currentSessionTitle = "Session 1",
                sessions = listOf(session)
            )
        }

        assertEquals(providerBefore, store.providerSettingsState.value)
        assertEquals(skillsBefore, store.skillsDiscoveryState.value)
        assertEquals(toolBefore, store.toolSettingsState.value)
        assertEquals(listOf(message), store.chatContentState.value.messages)
        assertEquals(true, store.chatContentState.value.messagesLoading)
        assertEquals("updated", store.chatContentState.value.input)
        assertEquals(true, store.chatContentState.value.isGenerating)
        assertEquals("session-1", store.chatContentState.value.currentSessionId)
        assertEquals("Session 1", store.chatContentState.value.currentSessionTitle)
        assertEquals(listOf(session), store.chatContentState.value.sessions)
        assertEquals(listOf(message), store.value.messages)
        assertEquals(true, store.value.messagesLoading)
        assertEquals("updated", store.value.input)
        assertEquals(true, store.value.isGenerating)
        assertEquals("session-1", store.value.currentSessionId)
        assertEquals("Session 1", store.value.currentSessionTitle)
        assertEquals(listOf(session), store.value.sessions)
    }

    @Test
    fun updateChannelsSettingsState_syncsGatewayAndConnectedChannelsWithoutChangingChatDraft() {
        val store = ChatStateStore(
            ChatUiState(
                input = "draft",
                settingsGatewayEnabled = false
            )
        )
        val chatBefore = store.chatContentState.value
        val connected = UiConnectedChannelSummary(
            sessionId = "session-1",
            sessionTitle = "Session 1",
            channel = "telegram",
            chatId = "chat-1",
            enabled = true,
            status = "Configured"
        )

        store.updateChannelsSettingsState {
            it.copy(
                connectedChannels = listOf(connected),
                gatewayEnabled = true,
                telegramBotToken = "telegram-token",
                telegramAllowedChatId = "12345",
                discordWebhookUrl = "https://discord.example.com"
            )
        }

        assertEquals(chatBefore, store.chatContentState.value)
        assertEquals(listOf(connected), store.channelsSettingsState.value.connectedChannels)
        assertEquals(true, store.channelsSettingsState.value.gatewayEnabled)
        assertEquals("telegram-token", store.channelsSettingsState.value.telegramBotToken)
        assertEquals("12345", store.channelsSettingsState.value.telegramAllowedChatId)
        assertEquals("https://discord.example.com", store.channelsSettingsState.value.discordWebhookUrl)
        assertEquals(listOf(connected), store.value.settingsConnectedChannels)
        assertEquals(true, store.value.settingsGatewayEnabled)
        assertEquals("telegram-token", store.value.settingsTelegramBotToken)
        assertEquals("12345", store.value.settingsTelegramAllowedChatId)
        assertEquals("https://discord.example.com", store.value.settingsDiscordWebhookUrl)
    }

    @Test
    fun updateSessionBindingState_syncsDiscoveryStateWithoutChangingChannelCredentials() {
        val store = ChatStateStore(
            ChatUiState(
                settingsTelegramBotToken = "telegram-secret",
                settingsMcpAuthToken = "mcp-secret"
            )
        )
        val channelsBefore = store.channelsSettingsState.value
        val telegramCandidate = UiTelegramChatCandidate(
            chatId = "chat-1",
            title = "General",
            kind = "group"
        )

        store.updateSessionBindingState {
            it.copy(
                telegramDiscovering = true,
                telegramDiscoveryAttempted = true,
                telegramCandidates = listOf(telegramCandidate),
                telegramInfo = "Detected"
            )
        }

        assertEquals(channelsBefore, store.channelsSettingsState.value)
        assertEquals(true, store.sessionBindingState.value.telegramDiscovering)
        assertEquals(true, store.sessionBindingState.value.telegramDiscoveryAttempted)
        assertEquals(listOf(telegramCandidate), store.sessionBindingState.value.telegramCandidates)
        assertEquals("Detected", store.sessionBindingState.value.telegramInfo)
        assertEquals(true, store.value.sessionBindingTelegramDiscovering)
        assertEquals(true, store.value.sessionBindingTelegramDiscoveryAttempted)
        assertEquals(listOf(telegramCandidate), store.value.sessionBindingTelegramCandidates)
        assertEquals("Detected", store.value.sessionBindingTelegramInfo)
        assertEquals("telegram-secret", store.value.settingsTelegramBotToken)
        assertEquals("mcp-secret", store.value.settingsMcpAuthToken)
    }

    @Test
    fun updateAutomationState_syncsLegacyStateWithoutChangingChatOrSkillsSlices() {
        val store = ChatStateStore(
            ChatUiState(
                input = "draft",
                settingsClawHubSearchQuery = "calendar",
                settingsCronEnabled = false
            )
        )
        val chatBefore = store.chatContentState.value
        val skillsBefore = store.skillsDiscoveryState.value

        store.updateAutomationState {
            it.copy(
                cronEnabled = true,
                cronMinEveryMs = "60000"
            )
        }

        assertEquals(chatBefore, store.chatContentState.value)
        assertEquals(skillsBefore, store.skillsDiscoveryState.value)
        assertEquals(true, store.automationSettingsState.value.cronEnabled)
        assertEquals("60000", store.automationSettingsState.value.cronMinEveryMs)
        assertEquals(true, store.value.settingsCronEnabled)
        assertEquals("60000", store.value.settingsCronMinEveryMs)
    }

    @Test
    fun updateAlwaysOnState_syncsLegacyStateAndAlwaysOnSlice() {
        val store = ChatStateStore(ChatUiState(alwaysOnEnabled = false))

        store.updateAlwaysOnState {
            it.copy(
                enabled = true,
                keepScreenAwake = true,
                serviceRunning = true
            )
        }

        assertEquals(true, store.alwaysOnSettingsState.value.enabled)
        assertEquals(true, store.alwaysOnSettingsState.value.keepScreenAwake)
        assertEquals(true, store.alwaysOnSettingsState.value.serviceRunning)
        assertEquals(true, store.value.alwaysOnEnabled)
        assertEquals(true, store.value.alwaysOnKeepScreenAwake)
        assertEquals(true, store.value.alwaysOnServiceRunning)
    }

    @Test
    fun updateUpdateState_syncsLegacyStateWithoutChangingChatDraft() {
        val store = ChatStateStore(
            ChatUiState(
                input = "draft",
                settingsUpdateChecking = false
            )
        )

        store.updateUpdateState {
            it.copy(
                checking = true,
                latestVersion = "2.0.0"
            )
        }

        assertEquals("draft", store.chatContentState.value.input)
        assertEquals(true, store.updateSettingsState.value.checking)
        assertEquals("2.0.0", store.updateSettingsState.value.latestVersion)
        assertEquals(true, store.value.settingsUpdateChecking)
        assertEquals("2.0.0", store.value.settingsLatestVersion)
    }

    @Test
    fun updateSettingsShellState_syncsSharedSettingsFields() {
        val store = ChatStateStore(
            ChatUiState(
                settingsSaving = false,
                settingsInfo = null
            )
        )

        store.updateSettingsShellState {
            it.copy(
                saving = true,
                info = "Saving"
            )
        }

        assertEquals(true, store.settingsShellState.value.saving)
        assertEquals("Saving", store.settingsShellState.value.info)
        assertEquals(true, store.automationSettingsState.value.saving)
        assertEquals("Saving", store.alwaysOnSettingsState.value.info)
        assertEquals(true, store.value.settingsSaving)
        assertEquals("Saving", store.value.settingsInfo)
    }

    @Test
    fun updateToolSettingsState_syncsLegacyStateWithoutChangingChatSkillsOrProviderSlices() {
        val store = ChatStateStore(
            ChatUiState(
                input = "draft",
                settingsClawHubSearchQuery = "calendar",
                settingsApiKey = "provider-secret",
                settingsMaxToolRounds = "4",
                settingsSearchBraveApiKey = "old-key"
            )
        )
        val chatBefore = store.chatContentState.value
        val skillsBefore = store.skillsDiscoveryState.value
        val providerBefore = store.providerSettingsState.value

        store.updateToolSettingsState {
            it.copy(
                maxToolRounds = "8",
                searchBraveApiKey = "new-key",
                agentLogs = "recent logs"
            )
        }

        assertEquals(chatBefore, store.chatContentState.value)
        assertEquals(skillsBefore, store.skillsDiscoveryState.value)
        assertEquals(providerBefore, store.providerSettingsState.value)
        assertEquals("8", store.toolSettingsState.value.maxToolRounds)
        assertEquals("new-key", store.toolSettingsState.value.searchBraveApiKey)
        assertEquals("recent logs", store.toolSettingsState.value.agentLogs)
        assertEquals("8", store.value.settingsMaxToolRounds)
        assertEquals("new-key", store.value.settingsSearchBraveApiKey)
        assertEquals("recent logs", store.value.settingsAgentLogs)
    }

    @Test
    fun updateMcpSettingsState_syncsLegacyStateWithoutChangingToolOrProviderSlices() {
        val store = ChatStateStore(
            ChatUiState(
                settingsApiKey = "provider-secret",
                settingsMaxToolRounds = "4",
                settingsMcpEnabled = false,
                settingsMcpServerUrl = "https://old.example.com"
            )
        )
        val providerBefore = store.providerSettingsState.value
        val toolBefore = store.toolSettingsState.value

        store.updateMcpSettingsState {
            it.copy(
                enabled = true,
                serverName = "local",
                serverUrl = "http://127.0.0.1:3000/mcp",
                authToken = "token",
                toolTimeoutSeconds = "45"
            )
        }

        assertEquals(providerBefore, store.providerSettingsState.value)
        assertEquals(toolBefore, store.toolSettingsState.value)
        assertEquals(true, store.mcpSettingsState.value.enabled)
        assertEquals("local", store.mcpSettingsState.value.serverName)
        assertEquals("http://127.0.0.1:3000/mcp", store.mcpSettingsState.value.serverUrl)
        assertEquals("token", store.mcpSettingsState.value.authToken)
        assertEquals("45", store.mcpSettingsState.value.toolTimeoutSeconds)
        assertEquals(true, store.value.settingsMcpEnabled)
        assertEquals("local", store.value.settingsMcpServerName)
        assertEquals("http://127.0.0.1:3000/mcp", store.value.settingsMcpServerUrl)
        assertEquals("token", store.value.settingsMcpAuthToken)
        assertEquals("45", store.value.settingsMcpToolTimeoutSeconds)
    }

    @Test
    fun updateProviderSettingsState_syncsLegacyStateWithoutChangingChatSkillsToolOrMcpSlices() {
        val store = ChatStateStore(
            ChatUiState(
                input = "draft",
                settingsClawHubSearchQuery = "calendar",
                settingsMaxToolRounds = "4",
                settingsMcpServerUrl = "https://mcp.example.com",
                settingsProvider = "openai",
                settingsApiKey = "old-key",
                settingsModel = "old-model"
            )
        )
        val chatBefore = store.chatContentState.value
        val skillsBefore = store.skillsDiscoveryState.value
        val toolBefore = store.toolSettingsState.value
        val mcpBefore = store.mcpSettingsState.value

        store.updateProviderSettingsState {
            it.copy(
                provider = "anthropic",
                providerCustomName = "",
                providerProtocol = com.palmclaw.providers.ProviderProtocol.Anthropic,
                baseUrl = "https://api.anthropic.com",
                model = "claude-test",
                apiKeyDraft = "new-key",
                tokenTotal = 42L,
                providerTesting = true,
                saving = true,
                info = "Testing"
            )
        }

        assertEquals(chatBefore, store.chatContentState.value)
        assertEquals(skillsBefore, store.skillsDiscoveryState.value)
        assertEquals(toolBefore, store.toolSettingsState.value)
        assertEquals(mcpBefore, store.mcpSettingsState.value)
        assertEquals("anthropic", store.providerSettingsState.value.provider)
        assertEquals("claude-test", store.providerSettingsState.value.model)
        assertEquals("new-key", store.providerSettingsState.value.apiKeyDraft)
        assertEquals(42L, store.providerSettingsState.value.tokenTotal)
        assertEquals(true, store.providerSettingsState.value.providerTesting)
        assertEquals(true, store.providerSettingsState.value.saving)
        assertEquals("Testing", store.providerSettingsState.value.info)
        assertEquals("anthropic", store.value.settingsProvider)
        assertEquals("claude-test", store.value.settingsModel)
        assertEquals("new-key", store.value.settingsApiKey)
        assertEquals(42L, store.value.settingsTokenTotal)
        assertEquals(true, store.value.settingsProviderTesting)
        assertEquals(true, store.value.settingsSaving)
        assertEquals("Testing", store.value.settingsInfo)
    }

    @Test
    fun updateOnboardingUiState_syncsLegacyStateWithoutChangingUnrelatedSlices() {
        val store = ChatStateStore(
            ChatUiState(
                input = "draft",
                settingsClawHubSearchQuery = "calendar",
                settingsApiKey = "provider-secret",
                settingsMaxToolRounds = "4",
                onboardingCompleted = false,
                onboardingUserDisplayName = "Old user"
            )
        )
        val chatBefore = store.chatContentState.value
        val skillsBefore = store.skillsDiscoveryState.value
        val toolBefore = store.toolSettingsState.value
        val providerBefore = store.providerSettingsState.value

        store.updateOnboardingUiState {
            it.copy(
                completed = true,
                userDisplayName = "Henry",
                agentDisplayName = "Palm",
                onboardingUserDisplayName = "Henry",
                onboardingAgentDisplayName = "Palm",
                saving = true,
                info = "Saving"
            )
        }

        assertEquals(chatBefore, store.chatContentState.value)
        assertEquals(skillsBefore, store.skillsDiscoveryState.value)
        assertEquals(toolBefore, store.toolSettingsState.value)
        assertEquals(providerBefore.provider, store.providerSettingsState.value.provider)
        assertEquals(providerBefore.model, store.providerSettingsState.value.model)
        assertEquals(providerBefore.apiKeyDraft, store.providerSettingsState.value.apiKeyDraft)
        assertEquals(true, store.onboardingUiState.value.completed)
        assertEquals("Henry", store.onboardingUiState.value.userDisplayName)
        assertEquals("Palm", store.onboardingUiState.value.agentDisplayName)
        assertEquals("Henry", store.onboardingUiState.value.onboardingUserDisplayName)
        assertEquals("Palm", store.onboardingUiState.value.onboardingAgentDisplayName)
        assertEquals(true, store.onboardingUiState.value.saving)
        assertEquals("Saving", store.onboardingUiState.value.info)
        assertEquals(true, store.providerSettingsState.value.saving)
        assertEquals("Saving", store.providerSettingsState.value.info)
        assertEquals(true, store.value.onboardingCompleted)
        assertEquals("Henry", store.value.userDisplayName)
        assertEquals("Palm", store.value.agentDisplayName)
        assertEquals("Henry", store.value.onboardingUserDisplayName)
        assertEquals("Palm", store.value.onboardingAgentDisplayName)
        assertEquals(true, store.value.settingsSaving)
        assertEquals("Saving", store.value.settingsInfo)
    }

    @Test
    fun updateIdentityDisplayState_syncsLegacyStateAndIdentitySlice() {
        val store = ChatStateStore(
            ChatUiState(
                userDisplayName = "Old user",
                agentDisplayName = "Old agent",
                settingsUseChinese = false
            )
        )

        store.updateIdentityDisplayState {
            it.copy(
                useChinese = true,
                userDisplayName = "用户",
                agentDisplayName = "助手"
            )
        }

        assertEquals(true, store.identityDisplayState.value.useChinese)
        assertEquals("用户", store.identityDisplayState.value.userDisplayName)
        assertEquals("助手", store.identityDisplayState.value.agentDisplayName)
        assertEquals(true, store.value.settingsUseChinese)
        assertEquals("用户", store.value.userDisplayName)
        assertEquals("助手", store.value.agentDisplayName)
    }

    @Test
    fun updateSettingsShellState_syncsLanguageAndThemeAcrossLocalizedSlices() {
        val store = ChatStateStore(
            ChatUiState(
                settingsUseChinese = false,
                settingsDarkTheme = false
            )
        )

        store.updateSettingsShellState {
            it.copy(
                useChinese = true,
                darkTheme = true
            )
        }

        assertEquals(true, store.settingsShellState.value.useChinese)
        assertEquals(true, store.settingsShellState.value.darkTheme)
        assertEquals(true, store.onboardingUiState.value.useChinese)
        assertEquals(true, store.providerSettingsState.value.useChinese)
        assertEquals(true, store.toolSettingsState.value.useChinese)
        assertEquals(true, store.mcpSettingsState.value.useChinese)
        assertEquals(true, store.updateSettingsState.value.useChinese)
        assertEquals(true, store.value.settingsUseChinese)
        assertEquals(true, store.value.settingsDarkTheme)
    }
}
