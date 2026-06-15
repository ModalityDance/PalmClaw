package com.palmclaw.ui

import com.palmclaw.skills.SkillCompatibilityStatus
import com.palmclaw.skills.SkillSource
import com.palmclaw.ui.settings.UiSkillConfig
import com.palmclaw.ui.settings.UiSkillDownloadStatus
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UiStateSlicesTest {
    @Test
    fun `chat screen does not collect full ui state`() {
        val sourceFile = listOf(
            File("src/main/java/com/palmclaw/ui/ChatScreen.kt"),
            File("app/src/main/java/com/palmclaw/ui/ChatScreen.kt")
        ).first { it.exists() }
        val source = sourceFile.readText()

        assertFalse(source.contains("vm.uiState.collectAsStateWithLifecycle()"))
    }

    @Test
    fun `main activity gates chat behind lightweight startup screen`() {
        val mainActivitySource = listOf(
            File("src/main/java/com/palmclaw/ui/MainActivity.kt"),
            File("app/src/main/java/com/palmclaw/ui/MainActivity.kt")
        ).first { it.exists() }.readText()
        val startupScreenSource = listOf(
            File("src/main/java/com/palmclaw/ui/StartupScreen.kt"),
            File("app/src/main/java/com/palmclaw/ui/StartupScreen.kt")
        ).first { it.exists() }.readText()

        assertTrue(mainActivitySource.contains("vm.startupState.collectAsStateWithLifecycle()"))
        assertTrue(mainActivitySource.contains("StartupScreen("))
        assertTrue(mainActivitySource.contains("STARTUP_MIN_VISIBLE_MS"))
        assertTrue(mainActivitySource.contains("STARTUP_MAX_VISIBLE_MS"))
        assertTrue(startupScreenSource.contains("R.drawable.palmclaw"))
        assertTrue(startupScreenSource.contains("CircularProgressIndicator"))
    }

    @Test
    fun `startup readiness uses only local settings sessions and messages signals`() {
        val viewModelSource = listOf(
            File("src/main/java/com/palmclaw/ui/chat/ChatViewModel.kt"),
            File("app/src/main/java/com/palmclaw/ui/chat/ChatViewModel.kt")
        ).first { it.exists() }.readText()
        val coordinatorSource = listOf(
            File("src/main/java/com/palmclaw/ui/chat/ChatSessionCoordinator.kt"),
            File("app/src/main/java/com/palmclaw/ui/chat/ChatSessionCoordinator.kt")
        ).first { it.exists() }.readText()

        assertTrue(viewModelSource.contains("val startupState: StateFlow<StartupUiState>"))
        assertTrue(viewModelSource.contains("markStartupSettingsLoaded()"))
        assertTrue(viewModelSource.contains("markStartupSessionsLoaded()"))
        assertTrue(viewModelSource.contains("markStartupMessagesLoaded"))
        assertFalse(viewModelSource.contains("markStartupRuntimeLoaded"))
        assertTrue(coordinatorSource.contains("onSessionsObserved"))
        assertTrue(coordinatorSource.contains("onMessagesObserved"))
    }

    @Test
    fun `chat view model slice flows are not projected from legacy ui state flow`() {
        val sourceFile = listOf(
            File("src/main/java/com/palmclaw/ui/chat/ChatViewModel.kt"),
            File("app/src/main/java/com/palmclaw/ui/chat/ChatViewModel.kt")
        ).first { it.exists() }
        val source = sourceFile.readText()

        assertFalse(source.contains("val chatContentState: StateFlow<ChatContentState> = uiState"))
        assertFalse(source.contains("val providerSettingsState: StateFlow<ProviderSettingsState> = uiState"))
        assertFalse(source.contains("val skillsDiscoveryState: StateFlow<SkillsDiscoveryState> = uiState"))
        assertFalse(source.contains("val automationSettingsState: StateFlow<AutomationSettingsState> = uiState"))
        assertFalse(source.contains("val mcpSettingsState: StateFlow<McpSettingsState> = uiState"))
    }

    @Test
    fun `skills coordinator writes through skills slice store`() {
        val sourceFile = listOf(
            File("src/main/java/com/palmclaw/ui/settings/SkillSettingsCoordinator.kt"),
            File("app/src/main/java/com/palmclaw/ui/settings/SkillSettingsCoordinator.kt")
        ).first { it.exists() }
        val source = sourceFile.readText()

        assertTrue(source.contains("updateSkillsState"))
        assertFalse(source.contains("updateToolSettings"))
        assertFalse(source.contains("settingsInstalledSkills"))
        assertFalse(source.contains("settingsSelectedSkillName"))
    }

    @Test
    fun `runtime coordinator uses typed stores for automation and always on fields`() {
        val sourceFile = listOf(
            File("src/main/java/com/palmclaw/ui/settings/RuntimeCoordinator.kt"),
            File("app/src/main/java/com/palmclaw/ui/settings/RuntimeCoordinator.kt")
        ).first { it.exists() }
        val source = sourceFile.readText()

        assertTrue(source.contains("updateAutomationState"))
        assertTrue(source.contains("updateAlwaysOnState"))
        assertTrue(source.contains("updateChannelsSettingsState"))
        assertFalse(source.contains("updateRuntime {"))
        assertFalse(source.contains("it.copy(settingsCronEnabled"))
        assertFalse(source.contains("it.copy(settingsHeartbeatEnabled"))
        assertFalse(source.contains("it.copy(settingsGatewayEnabled"))
        assertFalse(source.contains("settingsTelegramBotToken ="))
        assertFalse(source.contains("settingsDiscordWebhookUrl ="))
        assertFalse(source.contains("it.copy(alwaysOnEnabled"))
    }

    @Test
    fun `tool settings coordinator writes through tool slice store`() {
        val sourceFile = listOf(
            File("src/main/java/com/palmclaw/ui/settings/ToolSettingsCoordinator.kt"),
            File("app/src/main/java/com/palmclaw/ui/settings/ToolSettingsCoordinator.kt")
        ).first { it.exists() }
        val source = sourceFile.readText()

        assertTrue(source.contains("updateToolSettingsState"))
        assertFalse(source.contains("updateToolSettings {"))
        assertFalse(source.contains("settingsBuiltInTools"))
        assertFalse(source.contains("settingsSearchProvider"))
    }

    @Test
    fun `mcp settings writes through mcp slice store`() {
        val sourceFile = listOf(
            File("src/main/java/com/palmclaw/ui/settings/RuntimeCoordinator.kt"),
            File("app/src/main/java/com/palmclaw/ui/settings/RuntimeCoordinator.kt")
        ).first { it.exists() }
        val source = sourceFile.readText()

        assertTrue(source.contains("updateMcpSettingsState"))
        assertFalse(source.contains("it.copy(settingsMcpEnabled"))
        assertFalse(source.contains("settingsMcpServerName ="))
        assertFalse(source.contains("settingsMcpServers ="))
    }

    @Test
    fun `provider coordinator uses tool slice store for agent runtime fields`() {
        val sourceFile = listOf(
            File("src/main/java/com/palmclaw/ui/settings/ProviderSettingsCoordinator.kt"),
            File("app/src/main/java/com/palmclaw/ui/settings/ProviderSettingsCoordinator.kt")
        ).first { it.exists() }
        val source = sourceFile.readText()

        assertTrue(source.contains("updateToolSettingsState"))
        assertFalse(source.contains("it.copy(settingsMaxToolRounds"))
        assertFalse(source.contains("it.copy(settingsToolResultMaxChars"))
        assertFalse(source.contains("it.copy(settingsLlmCallTimeoutSeconds"))
    }

    @Test
    fun `provider coordinator writes provider fields through provider slice store`() {
        val sourceFile = listOf(
            File("src/main/java/com/palmclaw/ui/settings/ProviderSettingsCoordinator.kt"),
            File("app/src/main/java/com/palmclaw/ui/settings/ProviderSettingsCoordinator.kt")
        ).first { it.exists() }
        val source = sourceFile.readText()

        assertTrue(source.contains("updateProviderSettingsState"))
        assertFalse(source.contains("updateProviderSettings {"))
        assertFalse(source.contains("settingsProvider ="))
        assertFalse(source.contains("settingsApiKey ="))
        assertFalse(source.contains("settingsModel ="))
    }

    @Test
    fun `provider token usage card explains totals are advisory`() {
        val sourceFile = listOf(
            File("src/main/java/com/palmclaw/ui/settings/ProviderSettingsPage.kt"),
            File("app/src/main/java/com/palmclaw/ui/settings/ProviderSettingsPage.kt")
        ).first { it.exists() }
        val source = sourceFile.readText()

        assertTrue(source.contains("Only for quick reference. Check each provider dashboard for final usage."))
        assertTrue(source.contains("仅供参考，实际用量请以各供应商后台为准。"))
        assertTrue(source.contains("ProviderTokenUsageHint("))
    }

    @Test
    fun `user guide covers current settings and workflow behavior in both languages`() {
        val sourceFile = listOf(
            File("src/main/java/com/palmclaw/ui/settings/SettingsContent.kt"),
            File("app/src/main/java/com/palmclaw/ui/settings/SettingsContent.kt")
        ).first { it.exists() }
        val source = sourceFile.readText()

        listOf(
            "Search Provider",
            "搜索提供方",
            "Tool switches take effect immediately",
            "工具开关会立即生效",
            "ClawHub is opened only when you enter it",
            "ClawHub 只会在你主动进入时加载",
            "review the skill before installing",
            "安装前先审查技能",
            "Normal mode and Always-on mode share one runtime",
            "普通模式和常驻模式共用同一个运行时"
        ).forEach { expected ->
            assertTrue("User guide should contain: $expected", source.contains(expected))
        }
    }

    @Test
    fun `onboarding coordinator writes through onboarding shell and identity slice stores`() {
        val sourceFile = listOf(
            File("src/main/java/com/palmclaw/ui/onboarding/OnboardingCoordinator.kt"),
            File("app/src/main/java/com/palmclaw/ui/onboarding/OnboardingCoordinator.kt")
        ).first { it.exists() }
        val source = sourceFile.readText()

        assertTrue(source.contains("updateOnboardingUiState"))
        assertTrue(source.contains("updateSettingsShellState"))
        assertTrue(source.contains("updateIdentityDisplayState"))
        assertFalse(source.contains("updateOnboarding {"))
        assertFalse(source.contains("updateShell {"))
    }

    @Test
    fun `language and theme entrypoints use typed slice stores`() {
        val sourceFile = listOf(
            File("src/main/java/com/palmclaw/ui/chat/ChatViewModel.kt"),
            File("app/src/main/java/com/palmclaw/ui/chat/ChatViewModel.kt")
        ).first { it.exists() }
        val source = sourceFile.readText()
        val languageStart = source.indexOf("fun setUiLanguage")
        val themeStart = source.indexOf("fun toggleUiTheme")
        val onboardingStart = source.indexOf("fun onOnboardingUserDisplayNameChanged")
        val languageAndThemeSource = source.substring(languageStart, onboardingStart)

        assertTrue(languageAndThemeSource.contains("updateSettingsShellState"))
        assertTrue(languageAndThemeSource.contains("updateOnboardingUiState"))
        assertFalse(languageAndThemeSource.contains("settingsUseChinese ="))
        assertFalse(languageAndThemeSource.contains("settingsDarkTheme ="))
        assertTrue(themeStart > languageStart)
    }

    @Test
    fun `session coordinator writes through dedicated chat composer timeline session and shell stores`() {
        val sourceFile = listOf(
            File("src/main/java/com/palmclaw/ui/chat/ChatSessionCoordinator.kt"),
            File("app/src/main/java/com/palmclaw/ui/chat/ChatSessionCoordinator.kt")
        ).first { it.exists() }
        val source = sourceFile.readText()

        assertTrue(source.contains("updateChatTimelineState"))
        assertTrue(source.contains("updateChatComposerState"))
        assertTrue(source.contains("updateSessionListState"))
        assertTrue(source.contains("updateSettingsShellState"))
        assertFalse(source.contains("updateChatContentState"))
        assertFalse(source.contains("updateSession {"))
        assertFalse(source.contains("updateShell {"))
        assertFalse(source.contains("stateStore.value.input"))
        assertFalse(source.contains("stateStore.value.composerAttachments"))
        assertFalse(source.contains("stateStore.value.isGenerating"))
    }

    @Test
    fun `session coordinator send path uses atomic optimistic send update`() {
        val sourceFile = listOf(
            File("src/main/java/com/palmclaw/ui/chat/ChatSessionCoordinator.kt"),
            File("app/src/main/java/com/palmclaw/ui/chat/ChatSessionCoordinator.kt")
        ).first { it.exists() }
        val projectionCacheFile = listOf(
            File("src/main/java/com/palmclaw/ui/chat/ChatMessageProjectionCache.kt"),
            File("app/src/main/java/com/palmclaw/ui/chat/ChatMessageProjectionCache.kt")
        ).first { it.exists() }
        val source = sourceFile.readText()
        val projectionCacheSource = projectionCacheFile.readText()
        val sendStart = source.indexOf("fun sendMessage()")
        val stopStart = source.indexOf("fun stopGeneration()", sendStart)
        val sendSource = source.substring(sendStart, stopStart)

        assertTrue(projectionCacheSource.contains("mergeWithOptimisticMessages"))
        assertTrue(sendSource.contains("messageProjectionCache.appendOptimistic"))
        assertTrue(sendSource.contains("commitOptimisticSend"))
        assertTrue(sendSource.contains("messageProjectionCache.removeOptimistic"))
        assertTrue(sendSource.contains("rollbackOptimisticSend"))
        assertFalse(sendSource.contains("updateChatTimelineState"))
        assertFalse(sendSource.contains("updateChatComposerState"))
    }

    @Test
    fun `session coordinator delegates message projection state to cache`() {
        val sourceFile = listOf(
            File("src/main/java/com/palmclaw/ui/chat/ChatSessionCoordinator.kt"),
            File("app/src/main/java/com/palmclaw/ui/chat/ChatSessionCoordinator.kt")
        ).first { it.exists() }
        val source = sourceFile.readText()

        assertTrue(source.contains("ChatMessageProjectionCache"))
        assertFalse(source.contains("loadedMessagesBySession"))
        assertFalse(source.contains("projectedMessagesBySession"))
        assertFalse(source.contains("optimisticMessagesBySession"))
        assertFalse(source.contains("canLoadOlderBySession"))
    }

    @Test
    fun `main chat components use dedicated chat slices`() {
        val messageListSource = listOf(
            File("src/main/java/com/palmclaw/ui/chat/ChatMessageListPane.kt"),
            File("app/src/main/java/com/palmclaw/ui/chat/ChatMessageListPane.kt")
        ).first { it.exists() }.readText()
        val composerSource = listOf(
            File("src/main/java/com/palmclaw/ui/chat/ChatComposerBar.kt"),
            File("app/src/main/java/com/palmclaw/ui/chat/ChatComposerBar.kt")
        ).first { it.exists() }.readText()
        val sessionDrawerSource = listOf(
            File("src/main/java/com/palmclaw/ui/chat/SessionDrawerContent.kt"),
            File("app/src/main/java/com/palmclaw/ui/chat/SessionDrawerContent.kt")
        ).first { it.exists() }.readText()
        val conversationSource = listOf(
            File("src/main/java/com/palmclaw/ui/chat/ChatConversationPane.kt"),
            File("app/src/main/java/com/palmclaw/ui/chat/ChatConversationPane.kt")
        ).first { it.exists() }.readText()
        val chatScreenSource = listOf(
            File("src/main/java/com/palmclaw/ui/ChatScreen.kt"),
            File("app/src/main/java/com/palmclaw/ui/ChatScreen.kt")
        ).first { it.exists() }.readText()

        assertTrue(messageListSource.contains("state: ChatTimelineState"))
        assertFalse(messageListSource.contains("state: ChatContentState"))
        assertFalse(messageListSource.contains("history-status"))
        assertTrue(messageListSource.contains("top = CHAT_HISTORY_EDGE_PADDING"))
        assertTrue(composerSource.contains("state: ChatComposerState"))
        assertFalse(composerSource.contains("state: ChatContentState"))
        assertFalse(composerSource.contains("shadowElevation = 6.dp,\n            shape = RoundedCornerShape(24.dp)"))
        assertTrue(sessionDrawerSource.contains("state: SessionListState"))
        assertTrue(sessionDrawerSource.contains("verticalArrangement = Arrangement.spacedBy(6.dp)"))
        assertFalse(sessionDrawerSource.contains("state: ChatContentState"))
        assertTrue(conversationSource.contains("timelineState: ChatTimelineState"))
        assertTrue(conversationSource.contains("composerState: ChatComposerState"))
        assertFalse(conversationSource.contains("ChatContentState"))
        assertFalse(conversationSource.contains("ChatUiState"))
        assertTrue(chatScreenSource.contains("vm.chatTimelineState.collectAsStateWithLifecycle()"))
        assertTrue(chatScreenSource.contains("vm.chatComposerState.collectAsStateWithLifecycle()"))
        assertTrue(chatScreenSource.contains("vm.sessionListState.collectAsStateWithLifecycle()"))
        assertFalse(chatScreenSource.contains("vm.chatContentState.collectAsStateWithLifecycle()"))
        assertTrue(chatScreenSource.contains("ChatConversationPane(\n                    timelineState = chatTimelineState"))
        assertTrue(chatScreenSource.contains("composerState = chatComposerState"))
        assertFalse(chatScreenSource.contains("ChatMessageListPane("))
        assertFalse(chatScreenSource.contains("ChatComposerBar("))
        assertTrue(chatScreenSource.contains("SessionDrawerContent(\n                state = sessionListState"))
        assertTrue(chatScreenSource.contains("SessionSettingsSheet(\n        sessionId = sessionSettingsSessionId,\n        sessionListState = sessionListState"))
    }

    @Test
    fun `chat message list delegates scroll policy and state to dedicated holder`() {
        val messageListSource = listOf(
            File("src/main/java/com/palmclaw/ui/chat/ChatMessageListPane.kt"),
            File("app/src/main/java/com/palmclaw/ui/chat/ChatMessageListPane.kt")
        ).first { it.exists() }.readText()
        val scrollStateSource = listOf(
            File("src/main/java/com/palmclaw/ui/chat/ChatScrollState.kt"),
            File("app/src/main/java/com/palmclaw/ui/chat/ChatScrollState.kt")
        ).first { it.exists() }.readText()

        assertTrue(messageListSource.contains("rememberChatScrollState()"))
        assertTrue(messageListSource.contains("rememberChatMessageRenderState()"))
        assertTrue(messageListSource.contains("ChatScrollPolicy."))
        assertFalse(messageListSource.contains("history-status"))
        assertTrue(messageListSource.contains(".align(Alignment.TopCenter)"))
        assertFalse(messageListSource.contains("private data class MessageScrollAnchor"))
        assertFalse(messageListSource.contains("private data class ChatScrollRequest"))
        assertFalse(messageListSource.contains("private sealed class ChatScrollTarget"))
        assertFalse(messageListSource.contains("val scrollAnchorsBySession"))
        assertFalse(messageListSource.contains("val displayedAssistantText ="))
        assertFalse(messageListSource.contains("var trackedMessageIds"))
        assertFalse(messageListSource.contains("val expandedToolMessages"))
        assertTrue(scrollStateSource.contains("object ChatScrollPolicy"))
        assertTrue(scrollStateSource.contains("class ChatScrollState"))
        assertFalse(scrollStateSource.contains("LazyListState"))

        val restoreStart = messageListSource.indexOf("scrollState.pendingHistoryRestore,")
        val loadTriggerStart = messageListSource.indexOf("ChatScrollPolicy.shouldRequestOlderHistory", restoreStart)
        val restoreSource = messageListSource.substring(restoreStart, loadTriggerStart)
        assertTrue(restoreSource.contains("scrollToItem"))
        assertTrue(restoreSource.contains("restore.anchorScrollOffset"))
        assertFalse(restoreSource.contains("scrollBy"))
    }

    @Test
    fun `chat view model high frequency chat writes use typed chat shell and channel stores`() {
        val sourceFile = listOf(
            File("src/main/java/com/palmclaw/ui/chat/ChatViewModel.kt"),
            File("app/src/main/java/com/palmclaw/ui/chat/ChatViewModel.kt")
        ).first { it.exists() }
        val source = sourceFile.readText()

        assertTrue(source.contains("updateChatTimelineState"))
        assertTrue(source.contains("updateChatComposerState"))
        assertTrue(source.contains("updateSessionListState"))
        assertTrue(source.contains("updateSettingsShellState"))
        assertTrue(source.contains("updateChannelsSettingsState"))
        assertFalse(source.contains("_uiState.update { it.copy(isGenerating"))
        assertFalse(source.contains("_uiState.update { it.copy(settingsInfo"))
        assertFalse(source.contains("_uiState.update { it.copy(settingsGatewayEnabled"))
    }

    @Test
    fun `chat view model no longer exposes or reads full legacy ui state`() {
        val sourceFile = listOf(
            File("src/main/java/com/palmclaw/ui/chat/ChatViewModel.kt"),
            File("app/src/main/java/com/palmclaw/ui/chat/ChatViewModel.kt")
        ).first { it.exists() }
        val source = sourceFile.readText()

        assertFalse(source.contains("val uiState: StateFlow<ChatUiState>"))
        assertFalse(source.contains("_uiState.asStateFlow()"))
        assertFalse(source.contains("_uiState.value"))
        assertFalse(source.contains("SettingsStateAssembler.assemble("))
    }

    @Test
    fun `channel discovery projector no longer depends on legacy ui state`() {
        val sourceFile = listOf(
            File("src/main/java/com/palmclaw/ui/chat/ChannelDiscoveryStateProjector.kt"),
            File("app/src/main/java/com/palmclaw/ui/chat/ChannelDiscoveryStateProjector.kt")
        ).first { it.exists() }
        val source = sourceFile.readText()

        assertTrue(source.contains("SessionBindingState"))
        assertFalse(source.contains("ChatUiState"))
    }

    @Test
    fun `settings hydration writes typed slices instead of full legacy state`() {
        val sourceFile = listOf(
            File("src/main/java/com/palmclaw/ui/chat/ChatViewModel.kt"),
            File("app/src/main/java/com/palmclaw/ui/chat/ChatViewModel.kt")
        ).first { it.exists() }
        val source = sourceFile.readText()
        val loadStart = source.indexOf("private fun loadSettingsIntoState")
        val nextStart = source.indexOf("private suspend fun refreshSkillCatalogInternal", loadStart)
        val loadSource = source.substring(loadStart, nextStart)

        assertTrue(loadSource.contains("SettingsStateAssembler.assembleSlices"))
        assertTrue(loadSource.contains("updateProviderSettingsState"))
        assertTrue(loadSource.contains("updateChannelsSettingsState"))
        assertTrue(loadSource.contains("updateSettingsShellState"))
        assertFalse(loadSource.contains("_uiState.update {"))
        assertFalse(loadSource.contains("SettingsStateAssembler.assemble("))
    }

    @Test
    fun `chat state store no longer exposes legacy domain update wrappers`() {
        val sourceFile = listOf(
            File("src/main/java/com/palmclaw/ui/chat/ChatStateStore.kt"),
            File("app/src/main/java/com/palmclaw/ui/chat/ChatStateStore.kt")
        ).first { it.exists() }
        val source = sourceFile.readText()

        assertFalse(source.contains("fun updateSession("))
        assertFalse(source.contains("fun updateProviderSettings("))
        assertFalse(source.contains("fun updateToolSettings("))
        assertFalse(source.contains("fun updateChannelBinding("))
        assertFalse(source.contains("fun updateRuntime("))
        assertFalse(source.contains("fun updateOnboarding("))
        assertFalse(source.contains("fun updateAppUpdate("))
        assertFalse(source.contains("fun updateShell("))
    }

    @Test
    fun `skills discovery state excludes provider channel and mcp secrets`() {
        val state = ChatUiState(
            settingsApiKey = "provider-secret",
            settingsTelegramBotToken = "telegram-secret",
            settingsMcpAuthToken = "mcp-secret",
            settingsClawHubSearchQuery = "calendar",
            settingsSkillDownloadStatus = UiSkillDownloadStatus(
                key = "calendar",
                title = "Calendar",
                detailUrl = "https://example.com/calendar",
                status = "Downloading...",
                inProgress = true
            ),
            settingsSkillsLoading = true
        )

        val skillState = state.toSkillsDiscoveryState()
        val rendered = skillState.toString()

        assertEquals("calendar", skillState.clawHubSearchQuery)
        assertEquals("Calendar", skillState.downloadStatus?.title)
        assertTrue(skillState.skillsLoading)
        assertFalse(rendered.contains("provider-secret"))
        assertFalse(rendered.contains("telegram-secret"))
        assertFalse(rendered.contains("mcp-secret"))
    }

    @Test
    fun `channels state keeps route summaries without channel credentials`() {
        val state = ChatUiState(
            sessions = listOf(
                UiSessionSummary(id = "local", title = "Local", isLocal = true),
                UiSessionSummary(
                    id = "session-1",
                    title = "Session",
                    isLocal = false,
                    boundChannel = "telegram",
                    boundTelegramBotToken = "telegram-secret",
                    boundChatId = ""
                )
            ),
            settingsTelegramBotToken = "global-telegram-secret"
        )

        val channelsState = state.toChannelsSettingsState()
        val route = channelsState.sessions.single()
        val rendered = channelsState.toString()

        assertEquals("session-1", route.id)
        assertEquals("telegram", route.boundChannel)
        assertTrue(route.pendingDetection)
        assertFalse(rendered.contains("telegram-secret"))
        assertFalse(rendered.contains("global-telegram-secret"))
    }

    @Test
    fun `provider state isolates provider editor draft`() {
        val state = ChatUiState(
            settingsApiKey = "provider-secret",
            settingsMcpAuthToken = "mcp-secret",
            settingsTokenTotal = 42
        )

        val providerState = state.toProviderSettingsState()

        assertEquals("provider-secret", providerState.apiKeyDraft)
        assertEquals(42L, providerState.tokenTotal)
        assertFalse(providerState.toString().contains("mcp-secret"))
    }

    @Test
    fun `runtime and automation state keep unrelated credentials out`() {
        val state = ChatUiState(
            settingsApiKey = "provider-secret",
            settingsMcpAuthToken = "mcp-secret",
            settingsTelegramBotToken = "telegram-secret",
            settingsMaxToolRounds = "9",
            settingsCronEnabled = true,
            settingsHeartbeatDoc = "heartbeat body"
        )

        val toolState = state.toToolSettingsState()
        val automationState = state.toAutomationSettingsState()
        val rendered = toolState.toString() + automationState.toString()

        assertEquals("9", toolState.maxToolRounds)
        assertTrue(automationState.cronEnabled)
        assertEquals("heartbeat body", automationState.heartbeatDoc)
        assertFalse(rendered.contains("provider-secret"))
        assertFalse(rendered.contains("mcp-secret"))
        assertFalse(rendered.contains("telegram-secret"))
    }

    @Test
    fun `always on update and binding states expose only their domains`() {
        val state = ChatUiState(
            alwaysOnEnabled = true,
            alwaysOnLastError = "service failed",
            settingsUpdateAvailable = true,
            settingsLatestVersion = "2.0",
            sessionBindingTelegramDiscovering = true,
            settingsApiKey = "provider-secret",
            settingsMcpAuthToken = "mcp-secret"
        )

        val alwaysOnState = state.toAlwaysOnSettingsState()
        val updateState = state.toUpdateSettingsState()
        val bindingState = state.toSessionBindingState()
        val rendered = alwaysOnState.toString() + updateState.toString() + bindingState.toString()

        assertTrue(alwaysOnState.enabled)
        assertEquals("service failed", alwaysOnState.lastError)
        assertTrue(updateState.available)
        assertEquals("2.0", updateState.latestVersion)
        assertTrue(bindingState.telegramDiscovering)
        assertFalse(rendered.contains("provider-secret"))
        assertFalse(rendered.contains("mcp-secret"))
    }

    @Test
    fun `skills discovery groups built in local and clawhub installed skills`() {
        val state = ChatUiState(
            settingsInstalledSkills = listOf(
                skill("channels", SkillSource.Builtin),
                skill("local-note", SkillSource.Local),
                skill("claw-calendar", SkillSource.ClawHub)
            )
        )

        val skillState = state.toSkillsDiscoveryState()

        assertEquals(listOf("channels", "local-note"), skillState.builtInLocalSkills.map { it.name })
        assertEquals(listOf("claw-calendar"), skillState.clawHubInstalledSkills.map { it.name })
    }

    private fun skill(name: String, source: SkillSource): UiSkillConfig {
        return UiSkillConfig(
            name = name,
            displayName = name,
            description = "",
            source = source,
            enabled = true,
            allowIncompatible = false,
            always = false,
            compatibilityStatus = SkillCompatibilityStatus.Compatible,
            compatibilityReasons = emptyList(),
            requirementsStatus = ""
        )
    }
}
