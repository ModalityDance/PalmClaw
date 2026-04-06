package com.palmclaw.ui

import android.app.Application
import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.BatteryManager
import android.os.PowerManager
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.palmclaw.runtime.AlwaysOnModeController
import com.palmclaw.runtime.AlwaysOnHealthCheckWorker
import com.palmclaw.agent.AgentLogStore
import com.palmclaw.agent.AgentLoop
import com.palmclaw.agent.ContextBuilder
import com.palmclaw.agent.MemoryConsolidator
import com.palmclaw.agent.SubagentManager
import com.palmclaw.agent.ToolCallParser
import com.palmclaw.bus.InboundMessage
import com.palmclaw.bus.MessageBus
import com.palmclaw.bus.OutboundMessage
import com.palmclaw.channels.DiscordChannelAdapter
import com.palmclaw.channels.DiscordGatewayDiagnostics
import com.palmclaw.channels.DiscordRouteRule
import com.palmclaw.channels.ChannelRuntimeDiagnostics
import com.palmclaw.channels.EmailAccountConfig
import com.palmclaw.channels.EmailChannelAdapter
import com.palmclaw.channels.EmailGatewayDiagnostics
import com.palmclaw.channels.buildFeishuAdapterSeeds
import com.palmclaw.channels.buildFeishuTargetAliases
import com.palmclaw.channels.FeishuChannelAdapter
import com.palmclaw.channels.FeishuGatewayDiagnostics
import com.palmclaw.channels.FeishuRouteRule
import com.palmclaw.channels.GatewayOrchestrator
import com.palmclaw.channels.SlackChannelAdapter
import com.palmclaw.channels.SlackGatewayDiagnostics
import com.palmclaw.channels.SlackRouteRule
import com.palmclaw.channels.TelegramChannelAdapter
import com.palmclaw.channels.WeComChannelAdapter
import com.palmclaw.channels.WeComGatewayDiagnostics
import com.palmclaw.channels.WeComRouteRule
import com.palmclaw.config.AppLimits
import com.palmclaw.config.AppSession
import com.palmclaw.config.AppStoragePaths
import com.palmclaw.config.AlwaysOnConfig
import com.palmclaw.config.ChannelsConfig
import com.palmclaw.config.ConfigStore
import com.palmclaw.config.CronConfig
import com.palmclaw.config.HeartbeatDoc
import com.palmclaw.config.HeartbeatConfig
import com.palmclaw.config.McpHttpConfig
import com.palmclaw.config.McpHttpServerConfig
import com.palmclaw.config.OnboardingConfig
import com.palmclaw.config.ProviderConnectionConfig
import com.palmclaw.config.SessionChannelBindingRules
import com.palmclaw.config.SessionChannelBinding
import com.palmclaw.cron.CronLogStore
import com.palmclaw.cron.CronJob
import com.palmclaw.cron.CronRepository
import com.palmclaw.cron.CronService
import com.palmclaw.heartbeat.HeartbeatService
import com.palmclaw.memory.MemoryStore
import com.palmclaw.providers.AdaptiveLlmProvider
import com.palmclaw.providers.ChatMessage
import com.palmclaw.providers.LlmProviderFactory
import com.palmclaw.providers.ProviderCatalog
import com.palmclaw.providers.ProviderProtocol
import com.palmclaw.providers.ProviderResolutionStore
import com.palmclaw.providers.ToolCall
import com.palmclaw.runtime.RuntimeController
import com.palmclaw.skills.SkillsLoader
import com.palmclaw.storage.AppDatabase
import com.palmclaw.storage.MessageRepository
import com.palmclaw.storage.SessionRepository
import com.palmclaw.storage.entities.MessageEntity
import com.palmclaw.storage.entities.SessionEntity
import com.palmclaw.templates.TemplateStore
import com.palmclaw.tools.MessageTool
import com.palmclaw.tools.McpHttpRuntime
import com.palmclaw.tools.McpStatusTool
import com.palmclaw.tools.HeartbeatGetTool
import com.palmclaw.tools.HeartbeatSetTool
import com.palmclaw.tools.HeartbeatTriggerTool
import com.palmclaw.tools.ChannelsGetTool
import com.palmclaw.tools.ChannelsSetTool
import com.palmclaw.tools.RuntimeGetTool
import com.palmclaw.tools.RuntimeSetTool
import com.palmclaw.tools.SessionsListTool
import com.palmclaw.tools.SessionsSendTool
import com.palmclaw.tools.SpawnTool
import com.palmclaw.tools.createToolRegistry
import java.io.File
import java.security.MessageDigest
import java.util.LinkedHashSet
import java.util.Locale
import java.text.SimpleDateFormat
import java.util.Date
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class ChatViewModel(
    app: Application
) : AndroidViewModel(app) {

    private val environment = ChatViewModelEnvironment(app)
    private val storageMigration: Unit = environment.storageMigration
    private val database = environment.database
    private val messageRepository = environment.messageRepository
    private val sessionRepository = environment.sessionRepository
    private val cronRepository = environment.cronRepository
    private val cronService = environment.cronService
    private val cronLogStore = environment.cronLogStore
    private val agentLogStore = environment.agentLogStore
    private val configStore = environment.configStore
    private val providerResolutionStore = environment.providerResolutionStore
    private val memoryStore = environment.memoryStore
    private val templateStore = environment.templateStore
    private val heartbeatDocFile = environment.heartbeatDocFile

    private var currentSessionId: String =
        configStore.getLastActiveSessionId() ?: AppSession.LOCAL_SESSION_ID
    private val initialUiPrefs = configStore.getUiPreferencesConfig()
    private val initialOnboarding = OnboardingCoordinator.resolveSyncedOnboardingConfig(
        configStore = configStore,
        memoryStore = memoryStore,
        baseConfig = configStore.getOnboardingConfig()
    )
    private val _uiState = ChatStateStore(
        ChatUiState(
            currentSessionId = currentSessionId,
            currentSessionTitle = if (currentSessionId == AppSession.LOCAL_SESSION_ID) {
                AppSession.LOCAL_SESSION_TITLE
            } else {
                currentSessionId
            },
            settingsUseChinese = initialUiPrefs.useChinese,
            settingsDarkTheme = initialUiPrefs.darkTheme,
            onboardingCompleted = initialOnboarding.completed,
            userDisplayName = initialOnboarding.userDisplayName,
            agentDisplayName = initialOnboarding.agentDisplayName,
            onboardingUserDisplayName = initialOnboarding.userDisplayName,
            onboardingAgentDisplayName = initialOnboarding.agentDisplayName
        )
    )
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()
    private val uiJson = environment.uiJson

    private var generatingJob: Job? = null
    private var firstRunAutoIntroPending = false
    private var mcpServerStatuses: Map<String, UiMcpServerRuntimeStatus> = emptyMap()
    private val gatewayProcessingSessions = mutableSetOf<String>()
    private var runtimeProcessingSessions: Set<String> = emptySet()
    private var alwaysOnProcessingSessions: Set<String> = emptySet()
    @Volatile
    private var pendingGatewayConfig: ChannelsConfig? = null
    private val telegramDiscoveryClient = environment.telegramDiscoveryClient
    private val sessionCoordinator = ChatSessionCoordinator(
        scope = viewModelScope,
        stateStore = _uiState,
        dependencies = ChatSessionCoordinator.Dependencies(
            currentSessionId = { currentSessionId },
            setCurrentSessionId = { currentSessionId = it },
            saveLastActiveSessionId = { configStore.saveLastActiveSessionId(it) },
            computeIsGeneratingForSession = ::computeIsGeneratingForSession,
            observeSessionsSource = { sessionRepository.observeSessions() },
            observeMessagesSource = { sessionId -> messageRepository.observeMessages(sessionId) },
            buildSessionSummaries = ::buildSessionSummaries,
            buildConnectedChannelsOverview = ::buildConnectedChannelsOverview,
            mapObservedMessagesToUi = { messages ->
                mapMessagesToUi(messages.filter { it.shouldDisplayInChat() })
            },
            resolveOnboardingConfig = { onboardingCoordinator.resolveSyncedOnboardingConfig() }
        ),
        actions = ChatSessionCoordinator.Actions(
            bootstrapLocalSessions = ::bootstrapLocalSessions,
            sendMessage = ::sendMessageInternal,
            stopGeneration = ::stopGenerationInternal,
            createSession = ::createSessionInternal,
            renameSession = ::renameSessionInternal,
            deleteSession = ::deleteSessionInternal
        )
    )
    private val providerSettingsCoordinator = ProviderSettingsCoordinator(
        stateStore = _uiState,
        clearTokenUsageStats = {
            configStore.clearTokenUsageStats()
            configStore.getTokenUsageStats()
        },
        persistOnboardingProviderDraftIfNeeded = ::persistOnboardingProviderDraftIfNeeded,
        actions = ProviderSettingsCoordinator.Actions(
            setActiveProviderConfig = ::setActiveProviderConfigInternal,
            deleteProviderConfig = ::deleteProviderConfigInternal,
            saveProviderSettings = ::saveProviderSettingsInternal,
            saveAgentRuntimeSettings = ::saveAgentRuntimeSettingsInternal,
            testProviderSettings = ::testProviderSettingsInternal
        )
    )
    private val channelBindingCoordinator = ChannelBindingCoordinator(
        ChannelBindingCoordinator.Actions(
            saveSessionChannelBinding = ::saveSessionChannelBindingRequestInternal,
            getSessionChannelDraft = ::getSessionChannelDraftInternal,
            setSessionChannelEnabled = ::setSessionChannelEnabledInternalFacade,
            discoverTelegramChatsForBinding = ::discoverTelegramChatsForBindingInternal,
            clearTelegramChatDiscovery = ::clearTelegramChatDiscoveryInternal,
            discoverFeishuChatsForBinding = ::discoverFeishuChatsForBindingInternal,
            clearFeishuChatDiscovery = ::clearFeishuChatDiscoveryInternal,
            discoverEmailSendersForBinding = ::discoverEmailSendersForBindingInternal,
            clearEmailSenderDiscovery = ::clearEmailSenderDiscoveryInternal,
            discoverWeComChatsForBinding = ::discoverWeComChatsForBindingInternal,
            clearWeComChatDiscovery = ::clearWeComChatDiscoveryInternal,
            refreshSessionConnectionStatus = ::refreshSessionConnectionStatusInternal
        )
    )
    private val runtimeCoordinator = RuntimeCoordinator(
        stateStore = _uiState,
        actions = RuntimeCoordinator.Actions(
            loadSettingsIntoState = ::loadSettingsIntoState,
            observeRuntimeStatus = ::observeRuntimeStatus,
            observeAlwaysOnStatus = ::observeAlwaysOnStatus,
            startGatewayIfEnabled = ::startGatewayIfEnabled,
            refreshAlwaysOnDiagnostics = ::refreshAlwaysOnDiagnosticsInternal,
            refreshCronJobs = ::refreshCronJobsInternal,
            setCronJobEnabled = ::setCronJobEnabledInternal,
            runCronJobNow = ::runCronJobNowInternal,
            removeCronJob = ::removeCronJobInternal,
            triggerHeartbeatNow = ::triggerHeartbeatNowInternal,
            loadHeartbeatDocument = ::loadHeartbeatDocumentInternal,
            saveHeartbeatDocument = ::saveHeartbeatDocumentInternal,
            refreshCronLogs = ::refreshCronLogsInternal,
            clearCronLogs = ::clearCronLogsInternal,
            refreshAgentLogs = ::refreshAgentLogsInternal,
            clearAgentLogs = ::clearAgentLogsInternal,
            saveCronSettings = ::saveCronSettingsInternal,
            saveHeartbeatSettings = ::saveHeartbeatSettingsInternal,
            saveAlwaysOnSettings = ::saveAlwaysOnSettingsInternal,
            saveChannelsSettings = ::saveChannelsSettingsInternal,
            saveMcpSettings = ::saveMcpSettingsInternal
        )
    )
    private val onboardingCoordinator = OnboardingCoordinator(
        scope = viewModelScope,
        stateStore = _uiState,
        configStore = configStore,
        memoryStore = memoryStore,
        buildProviderStateWithSavedDraft = ::buildProviderStateWithSavedDraft,
        buildProviderSettingsConfig = ::buildProviderSettingsConfig,
        selectLocalSession = { selectSession(AppSession.LOCAL_SESSION_ID) },
        loadSettingsIntoState = ::loadSettingsIntoState,
        maybeTriggerFirstRunAutoIntro = ::maybeTriggerFirstRunAutoIntro
    )
    private val appUpdateCoordinator = AppUpdateCoordinator(
        app = app,
        scope = viewModelScope,
        stateStore = _uiState,
        configStore = configStore,
        updateCheckClient = environment.updateCheckClient
    )

    init {
        storageMigration
        sessionCoordinator.bootstrapLocalSessions()
        runtimeCoordinator.loadSettingsIntoState()
        runtimeCoordinator.observeRuntimeStatus()
        runtimeCoordinator.observeAlwaysOnStatus()
        sessionCoordinator.observeSessions()
        sessionCoordinator.observeMessages(currentSessionId)
        runtimeCoordinator.startGatewayIfEnabled()
        runtimeCoordinator.refreshAlwaysOnDiagnostics()
        appUpdateCoordinator.bootstrapAutomaticCheck()
    }

    fun onInputChanged(value: String): Unit {
        sessionCoordinator.onInputChanged(value)
    }

    fun sendMessage(): Unit {
        sessionCoordinator.sendMessage()
    }

    private fun sendMessageInternal(text: String) {
        generatingJob = viewModelScope.launch {
            try {
                val sessionId = currentSessionId.trim().ifBlank { AppSession.LOCAL_SESSION_ID }
                val sessionTitle = _uiState.value.currentSessionTitle.ifBlank { sessionId }
                runUserMessageViaActiveRuntime(
                    sessionId = sessionId,
                    sessionTitle = sessionTitle,
                    text = text
                )
            } catch (_: CancellationException) {
                // User stopped generation; state cleanup happens in finally.
            } finally {
                generatingJob = null
                syncGeneratingState()
                loadSettingsIntoState()
            }
        }
    }

    fun stopGeneration(): Unit {
        sessionCoordinator.stopGeneration()
    }

    private fun stopGenerationInternal() {
        generatingJob?.cancel()
    }

    fun openSettings() {
        loadSettingsIntoState()
        refreshCronJobs()
        _uiState.update { it.copy(settingsInfo = null) }
    }

    fun showSettingsInfo(message: String) {
        val text = message.trim()
        if (text.isBlank()) return
        _uiState.update { it.copy(settingsInfo = text) }
    }

    fun clearProviderTokenUsageStats() = providerSettingsCoordinator.clearProviderTokenUsageStats()

    fun clearSettingsInfo() {
        _uiState.update {
            if (it.settingsInfo == null) it else it.copy(settingsInfo = null)
        }
    }

    private fun persistOnboardingProviderDraftIfNeeded() {
        onboardingCoordinator.persistProviderDraftIfNeeded()
    }

    fun checkAppUpdate() = appUpdateCoordinator.checkAppUpdate()

    fun dismissAppUpdatePrompt() = appUpdateCoordinator.dismissAppUpdatePrompt()
    fun dismissAppUpdateNotice() = appUpdateCoordinator.dismissAppUpdateNotice()
    fun notifyAppUpdateDownloadStarted() = appUpdateCoordinator.notifyAppUpdateDownloadStarted()

    fun notifyAppUpdateDownloadFallback(releaseUrl: String) =
        appUpdateCoordinator.notifyAppUpdateDownloadFallback(releaseUrl)

    fun onSettingsProviderChanged(value: String) =
        providerSettingsCoordinator.onSettingsProviderChanged(value)

    fun startNewProviderDraft() = providerSettingsCoordinator.startNewProviderDraft()

    fun selectProviderConfigForEditing(configId: String) =
        providerSettingsCoordinator.selectProviderConfigForEditing(configId)

    fun setActiveProviderConfig(configId: String) =
        providerSettingsCoordinator.setActiveProviderConfig(configId)

    private fun setActiveProviderConfigInternal(configId: String) {
        val targetId = configId.trim()
        if (targetId.isBlank() || _uiState.value.settingsSaving) return
        viewModelScope.launch {
            _uiState.update { it.copy(settingsSaving = true, settingsInfo = null) }
            runCatching {
                val currentState = _uiState.value
                val updatedConfigs = normalizeActiveProviderConfigs(
                    currentState.settingsProviderConfigs.map { config ->
                        config.copy(enabled = config.id == targetId)
                    }
                )
                val selected = updatedConfigs.firstOrNull { it.id == targetId }
                val updatedState = currentState.copy(
                    settingsProviderConfigs = updatedConfigs,
                    settingsEditingProviderConfigId = selected?.id.orEmpty(),
                    settingsProvider = selected?.providerName ?: currentState.settingsProvider,
                    settingsProviderCustomName = selected?.customName ?: currentState.settingsProviderCustomName,
                    settingsProviderProtocol = selected?.providerProtocol ?: currentState.settingsProviderProtocol,
                    settingsBaseUrl = selected?.let { config ->
                        config.baseUrl.ifBlank {
                            ProviderCatalog.defaultBaseUrl(config.providerName, config.providerProtocol)
                        }
                    } ?: currentState.settingsBaseUrl,
                    settingsModel = selected?.model ?: currentState.settingsModel,
                    settingsApiKey = selected?.apiKey ?: currentState.settingsApiKey
                )
                configStore.saveConfig(buildProviderSettingsConfig(updatedState))
                updatedState
            }.onSuccess { updatedState ->
                _uiState.update {
                    it.copy(
                        settingsSaving = false,
                        settingsProviderConfigs = updatedState.settingsProviderConfigs,
                        settingsEditingProviderConfigId = updatedState.settingsEditingProviderConfigId,
                        settingsProvider = updatedState.settingsProvider,
                        settingsProviderCustomName = updatedState.settingsProviderCustomName,
                        settingsProviderProtocol = updatedState.settingsProviderProtocol,
                        settingsBaseUrl = updatedState.settingsBaseUrl,
                        settingsModel = updatedState.settingsModel,
                        settingsApiKey = updatedState.settingsApiKey,
                        settingsInfo = "Provider updated."
                    )
                }
            }.onFailure { t ->
                _uiState.update {
                    it.copy(
                        settingsSaving = false,
                        settingsInfo = "Save failed: ${t.message ?: t.javaClass.simpleName}"
                    )
                }
            }
        }
    }

    fun deleteProviderConfig(configId: String) =
        providerSettingsCoordinator.deleteProviderConfig(configId)

    private fun deleteProviderConfigInternal(configId: String) {
        val targetId = configId.trim()
        if (targetId.isBlank() || _uiState.value.settingsSaving) return
        viewModelScope.launch {
            _uiState.update { it.copy(settingsSaving = true, settingsInfo = null) }
            runCatching {
                val currentState = _uiState.value
                val normalizedRemaining = normalizeActiveProviderConfigs(
                    currentState.settingsProviderConfigs.filterNot { it.id == targetId }
                )
                val nextSelection = normalizedRemaining.firstOrNull()
                val updatedState = currentState.copy(
                    settingsProviderConfigs = normalizedRemaining,
                    settingsEditingProviderConfigId = nextSelection?.id.orEmpty(),
                    settingsProvider = nextSelection?.providerName ?: AppLimits.DEFAULT_PROVIDER,
                    settingsProviderCustomName = nextSelection?.customName.orEmpty(),
                    settingsProviderProtocol = nextSelection?.providerProtocol
                        ?: ProviderCatalog.defaultProtocol(AppLimits.DEFAULT_PROVIDER),
                    settingsBaseUrl = nextSelection?.let { config ->
                        config.baseUrl.ifBlank {
                            ProviderCatalog.defaultBaseUrl(config.providerName, config.providerProtocol)
                        }
                    } ?: ProviderCatalog.defaultBaseUrl(
                        AppLimits.DEFAULT_PROVIDER,
                        ProviderCatalog.defaultProtocol(AppLimits.DEFAULT_PROVIDER)
                    ),
                    settingsModel = nextSelection?.model ?: ProviderCatalog.defaultModel(
                        AppLimits.DEFAULT_PROVIDER,
                        ProviderCatalog.defaultProtocol(AppLimits.DEFAULT_PROVIDER)
                    ),
                    settingsApiKey = nextSelection?.apiKey.orEmpty()
                )
                val cachePrefix = ProviderResolutionStore.cachePrefixForProviderConfig(targetId)
                AdaptiveLlmProvider.clearRememberedTargets(cachePrefix)
                providerResolutionStore.clearByPrefix(cachePrefix)
                configStore.saveConfig(buildProviderSettingsConfig(updatedState))
                updatedState
            }.onSuccess { updatedState ->
                _uiState.update {
                    it.copy(
                        settingsSaving = false,
                        settingsProviderConfigs = updatedState.settingsProviderConfigs,
                        settingsEditingProviderConfigId = updatedState.settingsEditingProviderConfigId,
                        settingsProvider = updatedState.settingsProvider,
                        settingsProviderCustomName = updatedState.settingsProviderCustomName,
                        settingsProviderProtocol = updatedState.settingsProviderProtocol,
                        settingsBaseUrl = updatedState.settingsBaseUrl,
                        settingsModel = updatedState.settingsModel,
                        settingsApiKey = updatedState.settingsApiKey,
                        settingsInfo = "Provider removed."
                    )
                }
            }.onFailure { t ->
                _uiState.update {
                    it.copy(
                        settingsSaving = false,
                        settingsInfo = "Save failed: ${t.message ?: t.javaClass.simpleName}"
                    )
                }
            }
        }
    }

    fun onSettingsModelChanged(value: String) =
        providerSettingsCoordinator.onSettingsModelChanged(value)

    fun onSettingsProviderCustomNameChanged(value: String) =
        providerSettingsCoordinator.onSettingsProviderCustomNameChanged(value)

    fun onSettingsApiKeyChanged(value: String) =
        providerSettingsCoordinator.onSettingsApiKeyChanged(value)

    fun onSettingsBaseUrlChanged(value: String) =
        providerSettingsCoordinator.onSettingsBaseUrlChanged(value)

    fun onSettingsMaxRoundsChanged(value: String) =
        providerSettingsCoordinator.onSettingsMaxRoundsChanged(value)

    fun onSettingsToolResultMaxCharsChanged(value: String) =
        providerSettingsCoordinator.onSettingsToolResultMaxCharsChanged(value)

    fun onSettingsMemoryConsolidationWindowChanged(value: String) =
        providerSettingsCoordinator.onSettingsMemoryConsolidationWindowChanged(value)

    fun onSettingsLlmCallTimeoutSecondsChanged(value: String) =
        providerSettingsCoordinator.onSettingsLlmCallTimeoutSecondsChanged(value)

    fun onSettingsLlmConnectTimeoutSecondsChanged(value: String) =
        providerSettingsCoordinator.onSettingsLlmConnectTimeoutSecondsChanged(value)

    fun onSettingsLlmReadTimeoutSecondsChanged(value: String) =
        providerSettingsCoordinator.onSettingsLlmReadTimeoutSecondsChanged(value)

    fun onSettingsDefaultToolTimeoutSecondsChanged(value: String) =
        providerSettingsCoordinator.onSettingsDefaultToolTimeoutSecondsChanged(value)

    fun onSettingsContextMessagesChanged(value: String) =
        providerSettingsCoordinator.onSettingsContextMessagesChanged(value)

    fun onSettingsToolArgsPreviewMaxCharsChanged(value: String) =
        providerSettingsCoordinator.onSettingsToolArgsPreviewMaxCharsChanged(value)

    fun onSettingsCronEnabledChanged(value: Boolean) =
        runtimeCoordinator.onSettingsCronEnabledChanged(value)

    fun onSettingsCronMinEveryMsChanged(value: String) =
        runtimeCoordinator.onSettingsCronMinEveryMsChanged(value)

    fun onSettingsCronMaxJobsChanged(value: String) =
        runtimeCoordinator.onSettingsCronMaxJobsChanged(value)

    fun onSettingsHeartbeatEnabledChanged(value: Boolean) =
        runtimeCoordinator.onSettingsHeartbeatEnabledChanged(value)

    fun onSettingsHeartbeatIntervalSecondsChanged(value: String) =
        runtimeCoordinator.onSettingsHeartbeatIntervalSecondsChanged(value)

    fun onSettingsGatewayEnabledChanged(value: Boolean) =
        runtimeCoordinator.onSettingsGatewayEnabledChanged(value)

    fun setUiLanguage(useChinese: Boolean) {
        val current = configStore.getUiPreferencesConfig()
        val next = current.copy(useChinese = useChinese)
        configStore.saveUiPreferencesConfig(next)
        _uiState.update {
            val fallbackUserName = if (useChinese) "你" else "You"
            val currentDefaultUserName = if (it.settingsUseChinese) "你" else "You"
            val adjustedOnboardingUserName = when {
                it.onboardingCompleted -> it.onboardingUserDisplayName
                it.onboardingUserDisplayName.isBlank() -> fallbackUserName
                it.onboardingUserDisplayName == currentDefaultUserName -> fallbackUserName
                else -> it.onboardingUserDisplayName
            }
            it.copy(
                settingsUseChinese = next.useChinese,
                onboardingUserDisplayName = adjustedOnboardingUserName
            )
        }
    }

    fun toggleUiLanguage() {
        setUiLanguage(!configStore.getUiPreferencesConfig().useChinese)
    }

    fun toggleUiTheme() {
        val current = configStore.getUiPreferencesConfig()
        val next = current.copy(darkTheme = !current.darkTheme)
        configStore.saveUiPreferencesConfig(next)
        _uiState.update {
            it.copy(settingsDarkTheme = next.darkTheme)
        }
    }

    fun onOnboardingUserDisplayNameChanged(value: String) =
        onboardingCoordinator.onUserDisplayNameChanged(value)

    fun onOnboardingAgentDisplayNameChanged(value: String) =
        onboardingCoordinator.onAgentDisplayNameChanged(value)

    fun completeOnboarding() = onboardingCoordinator.completeOnboarding()

    fun onSettingsTelegramBotTokenChanged(value: String) =
        runtimeCoordinator.onSettingsTelegramBotTokenChanged(value)

    fun onSettingsTelegramAllowedChatIdChanged(value: String) =
        runtimeCoordinator.onSettingsTelegramAllowedChatIdChanged(value)

    fun onSettingsDiscordWebhookUrlChanged(value: String) =
        runtimeCoordinator.onSettingsDiscordWebhookUrlChanged(value)

    fun onSettingsMcpEnabledChanged(value: Boolean) =
        runtimeCoordinator.onSettingsMcpEnabledChanged(value)

    fun onSettingsMcpServerNameChanged(value: String) =
        runtimeCoordinator.onSettingsMcpServerNameChanged(value)

    fun onSettingsMcpServerUrlChanged(value: String) =
        runtimeCoordinator.onSettingsMcpServerUrlChanged(value)

    fun onSettingsMcpAuthTokenChanged(value: String) =
        runtimeCoordinator.onSettingsMcpAuthTokenChanged(value)

    fun onSettingsMcpToolTimeoutSecondsChanged(value: String) =
        runtimeCoordinator.onSettingsMcpToolTimeoutSecondsChanged(value)

    fun addSettingsMcpServer() = runtimeCoordinator.addSettingsMcpServer()

    fun removeSettingsMcpServer(serverId: String) =
        runtimeCoordinator.removeSettingsMcpServer(serverId)

    fun updateSettingsMcpServerName(serverId: String, value: String) =
        runtimeCoordinator.updateSettingsMcpServerName(serverId, value)

    fun updateSettingsMcpServerUrl(serverId: String, value: String) =
        runtimeCoordinator.updateSettingsMcpServerUrl(serverId, value)

    fun updateSettingsMcpServerAuthToken(serverId: String, value: String) =
        runtimeCoordinator.updateSettingsMcpServerAuthToken(serverId, value)

    fun updateSettingsMcpServerTimeout(serverId: String, value: String) =
        runtimeCoordinator.updateSettingsMcpServerTimeout(serverId, value)

    fun refreshCronJobs() = runtimeCoordinator.refreshCronJobs()

    private fun refreshCronJobsInternal() {
        viewModelScope.launch {
            _uiState.update { it.copy(settingsCronJobsLoading = true) }
            runCatching { withContext(Dispatchers.IO) { cronService.listJobs(includeDisabled = true) } }
                .onSuccess { jobs ->
                    _uiState.update {
                        it.copy(
                            settingsCronJobsLoading = false,
                            settingsCronJobs = jobs.map { job -> job.toUiCronJob() }
                        )
                    }
                }
                .onFailure { t ->
                    _uiState.update {
                        it.copy(
                            settingsCronJobsLoading = false,
                            settingsInfo = "Load cron jobs failed: ${t.message ?: t.javaClass.simpleName}"
                        )
                    }
                }
        }
    }

    fun setCronJobEnabled(jobId: String, enabled: Boolean) =
        runtimeCoordinator.setCronJobEnabled(jobId, enabled)

    private fun setCronJobEnabledInternal(jobId: String, enabled: Boolean) {
        viewModelScope.launch {
            runCatching { withContext(Dispatchers.IO) { cronService.enableJob(jobId, enabled) } }
                .onSuccess { refreshCronJobs() }
                .onFailure { t ->
                    _uiState.update { it.copy(settingsInfo = "Update cron job failed: ${t.message ?: t.javaClass.simpleName}") }
                }
        }
    }

    fun runCronJobNow(jobId: String) = runtimeCoordinator.runCronJobNow(jobId)

    private fun runCronJobNowInternal(jobId: String) {
        viewModelScope.launch {
            runCatching { withContext(Dispatchers.IO) { cronService.runJob(jobId, force = true) } }
                .onSuccess { refreshCronJobs() }
                .onFailure { t ->
                    _uiState.update { it.copy(settingsInfo = "Run cron job failed: ${t.message ?: t.javaClass.simpleName}") }
                }
        }
    }

    fun removeCronJob(jobId: String) = runtimeCoordinator.removeCronJob(jobId)

    private fun removeCronJobInternal(jobId: String) {
        viewModelScope.launch {
            runCatching { withContext(Dispatchers.IO) { cronService.removeJob(jobId) } }
                .onSuccess { refreshCronJobs() }
                .onFailure { t ->
                    _uiState.update { it.copy(settingsInfo = "Remove cron job failed: ${t.message ?: t.javaClass.simpleName}") }
                }
        }
    }


    fun selectSession(sessionId: String): Unit {
        sessionCoordinator.selectSession(sessionId)
    }

    fun createSession(displayName: String): Unit {
        sessionCoordinator.createSession(displayName)
    }

    private fun createSessionInternal(displayName: String) {
        val title = displayName.trim()
        viewModelScope.launch {
            runCatching {
                val sessionId = "session:${System.currentTimeMillis()}"
                sessionRepository.createSession(sessionId, title)
                sessionRepository.touch(sessionId)
                sessionId
            }.onSuccess { sid ->
                selectSession(sid)
                _uiState.update { it.copy(settingsInfo = "Session created.") }
            }.onFailure { t ->
                _uiState.update { it.copy(settingsInfo = "Create session failed: ${t.message ?: t.javaClass.simpleName}") }
            }
        }
    }

    fun renameSession(sessionId: String, displayName: String): Unit {
        sessionCoordinator.renameSession(sessionId, displayName)
    }

    private fun renameSessionInternal(sessionId: String, displayName: String) {
        val sid = sessionId.trim()
        val title = displayName.trim()
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    sessionRepository.renameSession(sid, title)
                    sessionRepository.touch(sid)
                }
            }.onSuccess {
                if (currentSessionId == sid) {
                    _uiState.update { it.copy(currentSessionTitle = title) }
                }
                _uiState.update { it.copy(settingsInfo = "Session renamed.") }
            }.onFailure { t ->
                _uiState.update { it.copy(settingsInfo = "Rename session failed: ${t.message ?: t.javaClass.simpleName}") }
            }
        }
    }

    fun deleteSession(sessionId: String): Unit {
        sessionCoordinator.deleteSession(sessionId)
    }

    private fun deleteSessionInternal(sessionId: String) {
        val sid = sessionId.trim()
        viewModelScope.launch {
            runCatching {
                if (currentSessionId == sid) {
                    generatingJob?.cancel()
                }
                withContext(Dispatchers.IO) {
                    sessionRepository.deleteSession(sid)
                    configStore.clearSessionChannelBinding(sid)
                    cronService.listJobs(includeDisabled = true)
                        .filter { it.payload.sessionId?.trim() == sid }
                        .forEach { job -> cronService.removeJob(job.id) }
                }
            }.onSuccess {
                if (currentSessionId == sid) {
                    selectSession(AppSession.LOCAL_SESSION_ID)
                }
                refreshSessionBindingsInState()
                applyGatewayRuntimeConfig(configStore.getChannelsConfig())
                _uiState.update { it.copy(settingsInfo = "Session deleted.") }
            }.onFailure { t ->
                _uiState.update { it.copy(settingsInfo = "Delete session failed: ${t.message ?: t.javaClass.simpleName}") }
            }
        }
    }

    @Suppress("LongParameterList")
    fun saveSessionChannelBinding(
        sessionId: String,
        enabled: Boolean = true,
        channel: String,
        chatId: String,
        targetDisplayName: String = "",
        telegramBotToken: String = "",
        telegramAllowedChatId: String = "",
        discordBotToken: String = "",
        discordResponseMode: String = "mention",
        discordAllowedUserIds: String = "",
        slackBotToken: String = "",
        slackAppToken: String = "",
        slackResponseMode: String = "mention",
        slackAllowedUserIds: String = "",
        feishuAppId: String = "",
        feishuAppSecret: String = "",
        feishuEncryptKey: String = "",
        feishuVerificationToken: String = "",
        feishuResponseMode: String = "mention",
        feishuAllowedOpenIds: String = "",
        emailConsentGranted: Boolean = false,
        emailImapHost: String = "",
        emailImapPort: String = "993",
        emailImapUsername: String = "",
        emailImapPassword: String = "",
        emailSmtpHost: String = "",
        emailSmtpPort: String = "587",
        emailSmtpUsername: String = "",
        emailSmtpPassword: String = "",
        emailFromAddress: String = "",
        emailAutoReplyEnabled: Boolean = true,
        wecomBotId: String = "",
        wecomSecret: String = "",
        wecomAllowedUserIds: String = ""
    ) = channelBindingCoordinator.saveSessionChannelBinding(
        sessionId = sessionId,
        enabled = enabled,
        channel = channel,
        chatId = chatId,
        targetDisplayName = targetDisplayName,
        telegramBotToken = telegramBotToken,
        telegramAllowedChatId = telegramAllowedChatId,
        discordBotToken = discordBotToken,
        discordResponseMode = discordResponseMode,
        discordAllowedUserIds = discordAllowedUserIds,
        slackBotToken = slackBotToken,
        slackAppToken = slackAppToken,
        slackResponseMode = slackResponseMode,
        slackAllowedUserIds = slackAllowedUserIds,
        feishuAppId = feishuAppId,
        feishuAppSecret = feishuAppSecret,
        feishuEncryptKey = feishuEncryptKey,
        feishuVerificationToken = feishuVerificationToken,
        feishuResponseMode = feishuResponseMode,
        feishuAllowedOpenIds = feishuAllowedOpenIds,
        emailConsentGranted = emailConsentGranted,
        emailImapHost = emailImapHost,
        emailImapPort = emailImapPort,
        emailImapUsername = emailImapUsername,
        emailImapPassword = emailImapPassword,
        emailSmtpHost = emailSmtpHost,
        emailSmtpPort = emailSmtpPort,
        emailSmtpUsername = emailSmtpUsername,
        emailSmtpPassword = emailSmtpPassword,
        emailFromAddress = emailFromAddress,
        emailAutoReplyEnabled = emailAutoReplyEnabled,
        wecomBotId = wecomBotId,
        wecomSecret = wecomSecret,
        wecomAllowedUserIds = wecomAllowedUserIds
    )

    private fun saveSessionChannelBindingRequestInternal(request: SaveSessionChannelBindingRequest) {
        saveSessionChannelBindingInternal(
            sessionId = request.sessionId,
            enabled = request.enabled,
            channel = request.channel,
            chatId = request.chatId,
            targetDisplayName = request.targetDisplayName,
            telegramBotToken = request.telegramBotToken,
            telegramAllowedChatId = request.telegramAllowedChatId,
            discordBotToken = request.discordBotToken,
            discordResponseMode = request.discordResponseMode,
            discordAllowedUserIds = request.discordAllowedUserIds,
            slackBotToken = request.slackBotToken,
            slackAppToken = request.slackAppToken,
            slackResponseMode = request.slackResponseMode,
            slackAllowedUserIds = request.slackAllowedUserIds,
            feishuAppId = request.feishuAppId,
            feishuAppSecret = request.feishuAppSecret,
            feishuEncryptKey = request.feishuEncryptKey,
            feishuVerificationToken = request.feishuVerificationToken,
            feishuResponseMode = request.feishuResponseMode,
            feishuAllowedOpenIds = request.feishuAllowedOpenIds,
            emailConsentGranted = request.emailConsentGranted,
            emailImapHost = request.emailImapHost,
            emailImapPort = request.emailImapPort,
            emailImapUsername = request.emailImapUsername,
            emailImapPassword = request.emailImapPassword,
            emailSmtpHost = request.emailSmtpHost,
            emailSmtpPort = request.emailSmtpPort,
            emailSmtpUsername = request.emailSmtpUsername,
            emailSmtpPassword = request.emailSmtpPassword,
            emailFromAddress = request.emailFromAddress,
            emailAutoReplyEnabled = request.emailAutoReplyEnabled,
            wecomBotId = request.wecomBotId,
            wecomSecret = request.wecomSecret,
            wecomAllowedUserIds = request.wecomAllowedUserIds
        )
    }

    @Suppress("LongParameterList")
    private fun saveSessionChannelBindingInternal(
        sessionId: String,
        enabled: Boolean = true,
        channel: String,
        chatId: String,
        targetDisplayName: String = "",
        telegramBotToken: String = "",
        telegramAllowedChatId: String = "",
        discordBotToken: String = "",
        discordResponseMode: String = "mention",
        discordAllowedUserIds: String = "",
        slackBotToken: String = "",
        slackAppToken: String = "",
        slackResponseMode: String = "mention",
        slackAllowedUserIds: String = "",
        feishuAppId: String = "",
        feishuAppSecret: String = "",
        feishuEncryptKey: String = "",
        feishuVerificationToken: String = "",
        feishuResponseMode: String = "mention",
        feishuAllowedOpenIds: String = "",
        emailConsentGranted: Boolean = false,
        emailImapHost: String = "",
        emailImapPort: String = "993",
        emailImapUsername: String = "",
        emailImapPassword: String = "",
        emailSmtpHost: String = "",
        emailSmtpPort: String = "587",
        emailSmtpUsername: String = "",
        emailSmtpPassword: String = "",
        emailFromAddress: String = "",
        emailAutoReplyEnabled: Boolean = true,
        wecomBotId: String = "",
        wecomSecret: String = "",
        wecomAllowedUserIds: String = ""
    ) {
        val sid = sessionId.trim()
        if (sid.isBlank()) return
        viewModelScope.launch {
            var runtimeChannelsConfig: ChannelsConfig? = null
            var autoEnabledGateway = false
            var autoDisabledGateway = false
            runCatching {
                val normalizedChannel = SessionChannelBindingRules.normalizeChannel(channel)
                val normalizedAllowedChatId = telegramAllowedChatId.trim()
                val rawChatId = chatId.trim()
                val normalizedChatId = when (normalizedChannel) {
                    "discord" -> normalizeDiscordChannelId(rawChatId)
                    "slack" -> normalizeSlackChannelId(rawChatId)
                    "feishu" -> normalizeFeishuTargetId(rawChatId)
                    "email" -> normalizeEmailAddress(rawChatId)
                    "wecom" -> normalizeWeComTargetId(rawChatId)
                    "telegram" -> rawChatId.ifBlank { normalizedAllowedChatId }
                    else -> rawChatId
                }
                if (normalizedChannel.isBlank()) {
                    configStore.clearSessionChannelBinding(sid)
                    runtimeChannelsConfig = configStore.getChannelsConfig()
                } else {
                    val normalizedTelegramToken = telegramBotToken.trim()
                    val normalizedDiscordToken = discordBotToken.trim()
                    val normalizedDiscordResponseMode = normalizeDiscordResponseMode(discordResponseMode)
                    val normalizedDiscordAllowedUserIds = parseAllowedUserIds(discordAllowedUserIds)
                    val normalizedSlackBotToken = slackBotToken.trim()
                    val normalizedSlackAppToken = slackAppToken.trim()
                    val normalizedSlackResponseMode = normalizeSlackResponseMode(slackResponseMode)
                    val normalizedSlackAllowedUserIds = parseAllowedUserIds(slackAllowedUserIds)
                    val normalizedFeishuAppId = feishuAppId.trim()
                    val normalizedFeishuAppSecret = feishuAppSecret.trim()
                    val normalizedFeishuEncryptKey = feishuEncryptKey.trim()
                    val normalizedFeishuVerificationToken = feishuVerificationToken.trim()
                    val normalizedFeishuResponseMode = "mention"
                    val normalizedFeishuAllowedOpenIds = parseAllowedUserIds(feishuAllowedOpenIds)
                    val normalizedEmailImapHost = emailImapHost.trim()
                    val normalizedEmailImapPort = emailImapPort.trim().toIntOrNull()
                    val normalizedEmailImapUsername = emailImapUsername.trim()
                    val normalizedEmailImapPassword = emailImapPassword
                    val normalizedEmailSmtpHost = emailSmtpHost.trim()
                    val normalizedEmailSmtpPort = emailSmtpPort.trim().toIntOrNull()
                    val normalizedEmailSmtpUsername = emailSmtpUsername.trim()
                    val normalizedEmailSmtpPassword = emailSmtpPassword
                    val normalizedEmailFromAddress = normalizeEmailAddress(emailFromAddress)
                    val normalizedWeComBotId = wecomBotId.trim()
                    val normalizedWeComSecret = wecomSecret.trim()
                    val normalizedWeComAllowedUserIds = parseAllowedUserIds(wecomAllowedUserIds)
                    when (normalizedChannel) {
                        "telegram" -> {
                            if (normalizedTelegramToken.isBlank()) {
                                throw IllegalArgumentException("Telegram bot token is required")
                            }
                            if (normalizedChatId.isNotBlank() && normalizedChatId.any { !it.isDigit() && it != '-' }) {
                                throw IllegalArgumentException("Telegram Chat ID must be numeric")
                            }
                        }

                        "discord" -> {
                            if (normalizedChatId.isBlank()) {
                                throw IllegalArgumentException("Discord Channel ID is required")
                            }
                            if (!isDiscordSnowflake(normalizedChatId)) {
                                throw IllegalArgumentException("Discord Channel ID must be a numeric ID (15-30 digits)")
                            }
                            if (normalizedDiscordToken.isBlank()) {
                                throw IllegalArgumentException("Discord bot token is required")
                            }
                            if (normalizedDiscordResponseMode !in setOf("mention", "open")) {
                                throw IllegalArgumentException("Discord response mode must be mention or open")
                            }
                        }

                        "slack" -> {
                            if (normalizedChatId.isBlank()) {
                                throw IllegalArgumentException("Slack channel ID is required")
                            }
                            if (!isSlackChannelId(normalizedChatId)) {
                                throw IllegalArgumentException("Slack channel ID must look like C/G/D + letters/numbers")
                            }
                            if (normalizedSlackBotToken.isBlank()) {
                                throw IllegalArgumentException("Slack bot token is required")
                            }
                            if (normalizedSlackAppToken.isBlank()) {
                                throw IllegalArgumentException("Slack app token is required")
                            }
                            if (normalizedSlackResponseMode !in setOf("mention", "open")) {
                                throw IllegalArgumentException("Slack response mode must be mention or open")
                            }
                        }

                        "feishu" -> {
                            if (normalizedFeishuAppId.isBlank()) {
                                throw IllegalArgumentException("Feishu App ID is required")
                            }
                            if (normalizedFeishuAppSecret.isBlank()) {
                                throw IllegalArgumentException("Feishu App Secret is required")
                            }
                            if (normalizedFeishuResponseMode !in setOf("mention", "open")) {
                                throw IllegalArgumentException("Feishu response mode must be mention or open")
                            }
                            if (normalizedChatId.isNotBlank() && !isFeishuTargetId(normalizedChatId)) {
                                throw IllegalArgumentException("Feishu target must look like ou_xxx or oc_xxx")
                            }
                        }

                        "email" -> {
                            if (normalizedChatId.isNotBlank() && !isEmailAddress(normalizedChatId)) {
                                throw IllegalArgumentException("Email sender address is invalid")
                            }
                            if (!emailConsentGranted) {
                                throw IllegalArgumentException("Email mailbox consent must be enabled")
                            }
                            if (normalizedEmailImapHost.isBlank()) {
                                throw IllegalArgumentException("IMAP host is required")
                            }
                            if (normalizedEmailImapPort == null || normalizedEmailImapPort !in 1..65535) {
                                throw IllegalArgumentException("IMAP port must be between 1 and 65535")
                            }
                            if (normalizedEmailImapUsername.isBlank()) {
                                throw IllegalArgumentException("IMAP username is required")
                            }
                            if (normalizedEmailImapPassword.isBlank()) {
                                throw IllegalArgumentException("IMAP password is required")
                            }
                            if (normalizedEmailSmtpHost.isBlank()) {
                                throw IllegalArgumentException("SMTP host is required")
                            }
                            if (normalizedEmailSmtpPort == null || normalizedEmailSmtpPort !in 1..65535) {
                                throw IllegalArgumentException("SMTP port must be between 1 and 65535")
                            }
                            if (normalizedEmailSmtpUsername.isBlank()) {
                                throw IllegalArgumentException("SMTP username is required")
                            }
                            if (normalizedEmailSmtpPassword.isBlank()) {
                                throw IllegalArgumentException("SMTP password is required")
                            }
                            if (normalizedEmailFromAddress.isBlank() || !isEmailAddress(normalizedEmailFromAddress)) {
                                throw IllegalArgumentException("From address is required")
                            }
                        }

                        "wecom" -> {
                            if (normalizedWeComBotId.isBlank()) {
                                throw IllegalArgumentException("WeCom Bot ID is required")
                            }
                            if (normalizedWeComSecret.isBlank()) {
                                throw IllegalArgumentException("WeCom Secret is required")
                            }
                        }

                        else -> throw IllegalArgumentException("Unsupported channel: $normalizedChannel")
                    }
                    configStore.saveSessionChannelBinding(
                        SessionChannelBinding(
                            sessionId = sid,
                            enabled = enabled,
                            channel = normalizedChannel,
                            chatId = normalizedChatId,
                            telegramBotToken = normalizedTelegramToken,
                            telegramAllowedChatId = normalizedAllowedChatId.ifBlank { null },
                            discordBotToken = normalizedDiscordToken,
                            discordResponseMode = normalizedDiscordResponseMode,
                            discordAllowedUserIds = normalizedDiscordAllowedUserIds,
                            slackBotToken = normalizedSlackBotToken,
                            slackAppToken = normalizedSlackAppToken,
                            slackResponseMode = normalizedSlackResponseMode,
                            slackAllowedUserIds = normalizedSlackAllowedUserIds,
                            feishuAppId = normalizedFeishuAppId,
                            feishuAppSecret = normalizedFeishuAppSecret,
                            feishuEncryptKey = normalizedFeishuEncryptKey,
                            feishuVerificationToken = normalizedFeishuVerificationToken,
                            feishuResponseMode = normalizedFeishuResponseMode,
                            feishuAllowedOpenIds = normalizedFeishuAllowedOpenIds,
                            emailConsentGranted = emailConsentGranted,
                            emailImapHost = normalizedEmailImapHost,
                            emailImapPort = normalizedEmailImapPort ?: 993,
                            emailImapUsername = normalizedEmailImapUsername,
                            emailImapPassword = normalizedEmailImapPassword,
                            emailSmtpHost = normalizedEmailSmtpHost,
                            emailSmtpPort = normalizedEmailSmtpPort ?: 587,
                            emailSmtpUsername = normalizedEmailSmtpUsername,
                            emailSmtpPassword = normalizedEmailSmtpPassword,
                            emailFromAddress = normalizedEmailFromAddress,
                            emailAutoReplyEnabled = emailAutoReplyEnabled,
                            wecomBotId = normalizedWeComBotId,
                            wecomSecret = normalizedWeComSecret,
                            wecomAllowedUserIds = normalizedWeComAllowedUserIds
                        )
                    )
                }
                val shouldEnableGateway = hasActiveGatewayBinding(configStore.getSessionChannelBindings())
                val currentChannels = configStore.getChannelsConfig()
                runtimeChannelsConfig = if (currentChannels.enabled == shouldEnableGateway) {
                    currentChannels
                } else {
                    if (shouldEnableGateway) autoEnabledGateway = true else autoDisabledGateway = true
                    currentChannels.copy(enabled = shouldEnableGateway).also { cfg ->
                        configStore.saveChannelsConfig(cfg)
                    }
                }
            }.onSuccess {
                refreshSessionBindingsInState()
                applyGatewayRuntimeConfig(runtimeChannelsConfig ?: configStore.getChannelsConfig())
                _uiState.update {
                    val savedChannel = normalizedChannelForInfo(configStore, sid)
                    val savedTarget = normalizedTargetForInfo(configStore, sid)
                    val displayTarget = targetDisplayName.trim().ifBlank { savedTarget }
                    val channelLabel = infoChannelLabel(savedChannel, it.settingsUseChinese)
                    val baseInfo = if (autoEnabledGateway) {
                        "Session channel binding saved. Channels gateway enabled."
                    } else if (autoDisabledGateway) {
                        "Session channel binding saved. Channels gateway disabled (no active session channel)."
                    } else if (savedChannel == "telegram" && normalizedTargetMissingForInfo(configStore, sid)) {
                        "Telegram token saved. Tap Detect Chats, choose the conversation, then save again."
                    } else if (savedChannel == "feishu" && normalizedTargetMissingForInfo(configStore, sid)) {
                        "Feishu credentials saved. Next, in Events & Callbacks select Long Connection and add im.message.receive_v1, then grant the message permissions, publish/open the app, send an @mention message, and use Detect Chats."
                    } else if (savedChannel == "email" && normalizedTargetMissingForInfo(configStore, sid)) {
                        "Email account saved. Mailbox polling starting. Send one email to this account, then use Detect Senders to finish binding."
                    } else if (savedChannel == "wecom" && normalizedTargetMissingForInfo(configStore, sid)) {
                        "WeCom credentials saved. Long connection starting. Keep PalmClaw open, send one message to the bot, then use Detect Chats."
                    } else if (savedChannel.isNotBlank() && displayTarget.isNotBlank()) {
                        "Bound to $channelLabel: $displayTarget"
                    } else if (savedChannel.isNotBlank()) {
                        "Saved $channelLabel binding."
                    } else {
                        "Session channel binding saved."
                    }
                    it.copy(
                        settingsGatewayEnabled = runtimeChannelsConfig?.enabled ?: it.settingsGatewayEnabled,
                        settingsInfo = baseInfo
                    )
                }
            }.onFailure { t ->
                _uiState.update { it.copy(settingsInfo = "Save session channel binding failed: ${t.message ?: t.javaClass.simpleName}") }
            }
        }
    }

    fun getSessionChannelDraft(sessionId: String): UiSessionChannelDraft =
        channelBindingCoordinator.getSessionChannelDraft(sessionId)

    private fun getSessionChannelDraftInternal(sessionId: String): UiSessionChannelDraft {
        val sid = sessionId.trim()
        if (sid.isBlank()) return UiSessionChannelDraft()
        val binding = configStore.getSessionChannelBindings()
            .firstOrNull { it.sessionId.trim() == sid }
        return UiSessionChannelDraft(
            enabled = binding?.enabled ?: true,
            channel = binding?.channel.orEmpty(),
            chatId = binding?.chatId.orEmpty(),
            telegramBotToken = binding?.telegramBotToken.orEmpty(),
            telegramAllowedChatId = binding?.telegramAllowedChatId.orEmpty(),
            discordBotToken = binding?.discordBotToken.orEmpty(),
            discordResponseMode = normalizeDiscordResponseMode(binding?.discordResponseMode.orEmpty()),
            discordAllowedUserIds = binding?.discordAllowedUserIds.orEmpty().joinToString("\n"),
            slackBotToken = binding?.slackBotToken.orEmpty(),
            slackAppToken = binding?.slackAppToken.orEmpty(),
            slackResponseMode = normalizeSlackResponseMode(binding?.slackResponseMode.orEmpty()),
            slackAllowedUserIds = binding?.slackAllowedUserIds.orEmpty().joinToString("\n"),
            feishuAppId = binding?.feishuAppId.orEmpty(),
            feishuAppSecret = binding?.feishuAppSecret.orEmpty(),
            feishuEncryptKey = binding?.feishuEncryptKey.orEmpty(),
            feishuVerificationToken = binding?.feishuVerificationToken.orEmpty(),
            feishuResponseMode = "mention",
            feishuAllowedOpenIds = binding?.feishuAllowedOpenIds.orEmpty().joinToString("\n"),
            emailConsentGranted = binding?.emailConsentGranted ?: false,
            emailImapHost = binding?.emailImapHost.orEmpty(),
            emailImapPort = (binding?.emailImapPort ?: 993).toString(),
            emailImapUsername = binding?.emailImapUsername.orEmpty(),
            emailImapPassword = binding?.emailImapPassword.orEmpty(),
            emailSmtpHost = binding?.emailSmtpHost.orEmpty(),
            emailSmtpPort = (binding?.emailSmtpPort ?: 587).toString(),
            emailSmtpUsername = binding?.emailSmtpUsername.orEmpty(),
            emailSmtpPassword = binding?.emailSmtpPassword.orEmpty(),
            emailFromAddress = binding?.emailFromAddress.orEmpty(),
            emailAutoReplyEnabled = binding?.emailAutoReplyEnabled ?: true,
            wecomBotId = binding?.wecomBotId.orEmpty(),
            wecomSecret = binding?.wecomSecret.orEmpty(),
            wecomAllowedUserIds = binding?.wecomAllowedUserIds.orEmpty().joinToString("\n")
        )
    }

    fun setSessionChannelEnabled(sessionId: String, enabled: Boolean) =
        channelBindingCoordinator.setSessionChannelEnabled(sessionId, enabled)

    private fun setSessionChannelEnabledInternalFacade(sessionId: String, enabled: Boolean) {
        val sid = sessionId.trim()
        if (sid.isBlank()) return
        viewModelScope.launch {
            runCatching {
                setSessionChannelEnabledInternal(
                    sessionId = sid,
                    sessionTitle = null,
                    enabled = enabled
                )
            }.onSuccess {
                _uiState.update {
                    it.copy(
                        settingsGatewayEnabled = configStore.getChannelsConfig().enabled,
                        settingsInfo = if (enabled) "Session channel enabled." else "Session channel disabled."
                    )
                }
            }.onFailure { t ->
                _uiState.update {
                    it.copy(settingsInfo = "Update session channel switch failed: ${t.message ?: t.javaClass.simpleName}")
                }
            }
        }
    }

    fun discoverTelegramChatsForBinding(botToken: String) =
        channelBindingCoordinator.discoverTelegramChatsForBinding(botToken)

    private fun discoverTelegramChatsForBindingInternal(botToken: String) {
        val token = botToken.trim()
        if (token.isBlank()) {
            _uiState.update {
                it.copy(
                    sessionBindingTelegramDiscoveryAttempted = true,
                    sessionBindingTelegramDiscovering = false,
                    sessionBindingTelegramCandidates = emptyList(),
                    sessionBindingTelegramInfo = "Please enter Telegram bot token first."
                )
            }
            return
        }
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    sessionBindingTelegramDiscoveryAttempted = true,
                    sessionBindingTelegramDiscovering = true,
                    sessionBindingTelegramCandidates = emptyList(),
                    sessionBindingTelegramInfo = null
                )
            }
            showSettingsInfo("Detecting Telegram chats...")
            runCatching {
                withContext(Dispatchers.IO) { fetchTelegramChatCandidates(token) }
            }.onSuccess { candidates ->
                _uiState.update {
                    it.copy(
                        sessionBindingTelegramDiscoveryAttempted = true,
                        sessionBindingTelegramDiscovering = false,
                        sessionBindingTelegramCandidates = candidates,
                        sessionBindingTelegramInfo = if (candidates.isEmpty()) {
                            "No Telegram chats found yet. Send the bot one message, then detect again."
                        } else {
                            null
                        }
                    )
                }
                if (candidates.isNotEmpty()) {
                    showSettingsInfo("Telegram chats discovered. Tap one to use.")
                } else {
                    showSettingsInfo("No Telegram chats found yet. Send the bot one message, then detect again.")
                }
            }.onFailure { t ->
                showSettingsInfo("Discover chats failed: ${t.message ?: t.javaClass.simpleName}")
                _uiState.update {
                    it.copy(
                        sessionBindingTelegramDiscoveryAttempted = true,
                        sessionBindingTelegramDiscovering = false,
                        sessionBindingTelegramCandidates = emptyList(),
                        sessionBindingTelegramInfo = "Discover chats failed: ${t.message ?: t.javaClass.simpleName}"
                    )
                }
            }
        }
    }

    fun clearTelegramChatDiscovery() = channelBindingCoordinator.clearTelegramChatDiscovery()

    private fun clearTelegramChatDiscoveryInternal() {
        _uiState.update {
            it.copy(
                sessionBindingTelegramDiscoveryAttempted = false,
                sessionBindingTelegramDiscovering = false,
                sessionBindingTelegramCandidates = emptyList(),
                sessionBindingTelegramInfo = null
            )
        }
    }

    fun discoverFeishuChatsForBinding(
        appId: String,
        appSecret: String,
        encryptKey: String,
        verificationToken: String
    ) = channelBindingCoordinator.discoverFeishuChatsForBinding(
        appId = appId,
        appSecret = appSecret,
        encryptKey = encryptKey,
        verificationToken = verificationToken
    )

    private fun discoverFeishuChatsForBindingInternal(
        appId: String,
        appSecret: String,
        encryptKey: String,
        verificationToken: String
    ) {
        _uiState.update {
            it.copy(
                sessionBindingFeishuDiscoveryAttempted = true,
                sessionBindingFeishuDiscovering = true,
                sessionBindingFeishuCandidates = emptyList(),
                sessionBindingFeishuInfo = null
            )
        }
        viewModelScope.launch {
            showSettingsInfo("Detecting Feishu chats...")
            val requestedAdapterKeys = buildFeishuAdapterKeys(
                appId = appId,
                appSecret = appSecret,
                encryptKey = encryptKey,
                verificationToken = verificationToken
            )
            val currentBindingAdapterKeys = configStore.getSessionChannelBindings()
                .firstOrNull {
                    it.sessionId.trim() == currentSessionId.trim() &&
                        it.enabled &&
                        it.channel.trim().equals("feishu", ignoreCase = true)
                }
                ?.let(::adapterKeysForBinding)
                .orEmpty()

            var result = collectFeishuDiscoveryResult(
                requestedAdapterKeys = requestedAdapterKeys,
                currentBindingAdapterKeys = currentBindingAdapterKeys
            )
            for (attempt in 0 until FEISHU_DISCOVERY_STARTUP_RETRIES) {
                if (result.candidates.isNotEmpty() || result.snapshots.values.any(::hasFeishuSnapshotActivity)) {
                    break
                }
                delay(FEISHU_DISCOVERY_STARTUP_RETRY_DELAY_MS)
                result = collectFeishuDiscoveryResult(
                    requestedAdapterKeys = requestedAdapterKeys,
                    currentBindingAdapterKeys = currentBindingAdapterKeys
                )
            }
            val finalResult = result
            val info = if (finalResult.candidates.isEmpty()) {
                buildFeishuDiscoveryInfo(
                    requestedAdapterKeys = requestedAdapterKeys,
                    currentBindingAdapterKeys = currentBindingAdapterKeys,
                    snapshots = finalResult.snapshots
                )
            } else {
                "Feishu chats discovered. Tap one to use."
            }
            _uiState.update {
                it.copy(
                    sessionBindingFeishuDiscoveryAttempted = true,
                    sessionBindingFeishuDiscovering = false,
                    sessionBindingFeishuCandidates = finalResult.candidates,
                    sessionBindingFeishuInfo = info
                )
            }
            if (finalResult.candidates.isNotEmpty()) {
                showSettingsInfo("Feishu chats discovered. Tap one to use.")
            } else {
                showSettingsInfo(info)
            }
        }
    }

    private fun collectFeishuDiscoveryResult(
        requestedAdapterKeys: List<String>,
        currentBindingAdapterKeys: List<String>
    ): FeishuDiscoveryResult {
        val snapshotKeys = LinkedHashSet<String>().apply {
            addAll(requestedAdapterKeys)
            addAll(currentBindingAdapterKeys)
        }
        val matchedSnapshots = snapshotKeys.associateWith { FeishuGatewayDiagnostics.getSnapshot(it) }
        val snapshots = if (matchedSnapshots.values.any(::hasFeishuSnapshotActivity)) {
            matchedSnapshots
        } else {
            val activeFallbackSnapshots = FeishuGatewayDiagnostics.getSnapshots()
                .filterKeys { it !in snapshotKeys }
                .filterValues(::hasFeishuSnapshotActivity)
            if (activeFallbackSnapshots.size == 1) {
                linkedMapOf<String, com.palmclaw.channels.FeishuGatewaySnapshot>().apply {
                    putAll(matchedSnapshots)
                    putAll(activeFallbackSnapshots)
                }
            } else {
                matchedSnapshots
            }
        }
        val candidates = snapshots.values
            .asSequence()
            .flatMap { it.recentChats.asSequence() }
            .distinctBy { it.chatId }
            .map {
                UiFeishuChatCandidate(
                    chatId = it.chatId,
                    title = it.title,
                    kind = it.kind,
                    note = it.note
                )
            }
            .toList()
        return FeishuDiscoveryResult(
            snapshots = snapshots,
            candidates = candidates
        )
    }

    private fun buildFeishuDiscoveryInfo(
        requestedAdapterKeys: List<String>,
        currentBindingAdapterKeys: List<String>,
        snapshots: Map<String, com.palmclaw.channels.FeishuGatewaySnapshot>
    ): String {
        if (requestedAdapterKeys.isEmpty()) {
            return "Save App ID and App Secret first, then detect again."
        }
        val requested = requestedAdapterKeys.asSequence()
            .mapNotNull { snapshots[it] }
            .firstOrNull(::hasFeishuSnapshotActivity)
            ?: requestedAdapterKeys.firstNotNullOfOrNull { snapshots[it] }
        val current = currentBindingAdapterKeys.asSequence()
            .filterNot { it in requestedAdapterKeys }
            .mapNotNull { snapshots[it] }
            .firstOrNull(::hasFeishuSnapshotActivity)
            ?: currentBindingAdapterKeys.asSequence()
                .filterNot { it in requestedAdapterKeys }
                .firstNotNullOfOrNull { snapshots[it] }
        val fallback = snapshots.asSequence()
            .filterNot { (key, _) -> key in requestedAdapterKeys || key in currentBindingAdapterKeys }
            .map { it.value }
            .firstOrNull(::hasFeishuSnapshotActivity)
            ?: snapshots.asSequence()
                .filterNot { (key, _) -> key in requestedAdapterKeys || key in currentBindingAdapterKeys }
                .map { it.value }
                .firstOrNull()
        if (
            currentBindingAdapterKeys.any { it !in requestedAdapterKeys } &&
            !hasFeishuSnapshotActivity(requested) &&
            hasFeishuSnapshotActivity(current)
        ) {
            return "These fields do not match the running Feishu connection. Save first, then detect again."
        }
        val snapshot = listOfNotNull(requested, current, fallback)
            .firstOrNull(::hasFeishuSnapshotActivity)
            ?: requested
            ?: current
            ?: fallback
        if (snapshot == null) {
            return "Save once to start Feishu long connection, then send one message and detect again."
        }
        if (snapshot.lastError.isNotBlank()) {
            return "Feishu long connection is not ready yet."
        }
        if (!snapshot.running) {
            return "Feishu adapter is not running yet. Save once and keep PalmClaw open."
        }
        if (!snapshot.ready) {
            return "Feishu long connection is starting. Finish confirmation, then detect again."
        }
        if (snapshot.inboundSeen <= 0L) {
            return "Feishu Long Connection is ready, but PalmClaw has not received any inbound Feishu message yet. Open the app in Feishu and send one @mention message first. Group tests also need im:message.group_at_msg:readonly."
        }
        return "Feishu messages have reached PalmClaw, but no bindable chat has been cached yet. Send one more @mention message, then try Detect Chats again."
    }

    private fun hasFeishuSnapshotActivity(snapshot: com.palmclaw.channels.FeishuGatewaySnapshot?): Boolean {
        if (snapshot == null) return false
        return snapshot.running ||
            snapshot.connected ||
            snapshot.ready ||
            snapshot.inboundSeen > 0L ||
            snapshot.inboundForwarded > 0L ||
            snapshot.outboundSent > 0L ||
            snapshot.lastError.isNotBlank() ||
            snapshot.recentChats.isNotEmpty()
    }

    fun clearFeishuChatDiscovery() = channelBindingCoordinator.clearFeishuChatDiscovery()

    private fun clearFeishuChatDiscoveryInternal() {
        _uiState.update {
            it.copy(
                sessionBindingFeishuDiscoveryAttempted = false,
                sessionBindingFeishuDiscovering = false,
                sessionBindingFeishuCandidates = emptyList(),
                sessionBindingFeishuInfo = null
            )
        }
    }

    fun discoverEmailSendersForBinding(
        consentGranted: Boolean,
        imapHost: String,
        imapPort: String,
        imapUsername: String,
        imapPassword: String,
        smtpHost: String,
        smtpPort: String,
        smtpUsername: String,
        smtpPassword: String,
        fromAddress: String,
        autoReplyEnabled: Boolean
    ) = channelBindingCoordinator.discoverEmailSendersForBinding(
        consentGranted = consentGranted,
        imapHost = imapHost,
        imapPort = imapPort,
        imapUsername = imapUsername,
        imapPassword = imapPassword,
        smtpHost = smtpHost,
        smtpPort = smtpPort,
        smtpUsername = smtpUsername,
        smtpPassword = smtpPassword,
        fromAddress = fromAddress,
        autoReplyEnabled = autoReplyEnabled
    )

    private fun discoverEmailSendersForBindingInternal(
        consentGranted: Boolean,
        imapHost: String,
        imapPort: String,
        imapUsername: String,
        imapPassword: String,
        smtpHost: String,
        smtpPort: String,
        smtpUsername: String,
        smtpPassword: String,
        fromAddress: String,
        autoReplyEnabled: Boolean
    ) {
        _uiState.update {
            it.copy(
                sessionBindingEmailDiscoveryAttempted = true,
                sessionBindingEmailDiscovering = true,
                sessionBindingEmailCandidates = emptyList(),
                sessionBindingEmailInfo = null
            )
        }
        val config = EmailAccountConfig(
            consentGranted = consentGranted,
            imapHost = imapHost.trim(),
            imapPort = imapPort.toIntOrNull()?.coerceIn(1, 65535) ?: 993,
            imapUsername = normalizeEmailAddress(imapUsername),
            imapPassword = imapPassword,
            smtpHost = smtpHost.trim(),
            smtpPort = smtpPort.toIntOrNull()?.coerceIn(1, 65535) ?: 587,
            smtpUsername = normalizeEmailAddress(smtpUsername),
            smtpPassword = smtpPassword,
            fromAddress = normalizeEmailAddress(fromAddress),
            autoReplyEnabled = autoReplyEnabled
        )
        val adapterKey = buildEmailAdapterKey(config)
        viewModelScope.launch {
            showSettingsInfo("Detecting email senders...")
            runCatching {
                val fetched = withContext(Dispatchers.IO) {
                    EmailChannelAdapter.detectRecentSenders(config)
                }
                if (fetched.isEmpty()) {
                    EmailGatewayDiagnostics.getSnapshot(adapterKey).recentSenders
                } else {
                    fetched
                }
            }.onSuccess { senderCandidates ->
                val candidates = senderCandidates.map {
                    UiEmailSenderCandidate(
                        email = it.email,
                        subject = it.subject,
                        note = it.note
                    )
                }
                _uiState.update {
                    it.copy(
                        sessionBindingEmailDiscoveryAttempted = true,
                        sessionBindingEmailDiscovering = false,
                        sessionBindingEmailCandidates = candidates,
                        sessionBindingEmailInfo = if (candidates.isEmpty()) {
                            "No email senders found yet. Make sure one message reached INBOX, then detect again."
                        } else {
                            null
                        }
                    )
                }
                if (candidates.isNotEmpty()) {
                    showSettingsInfo("Email senders discovered. Tap one to use.")
                } else {
                    showSettingsInfo("No email senders found yet. Make sure one message reached INBOX, then detect again.")
                }
            }.onFailure { t ->
                val fallback = EmailGatewayDiagnostics.getSnapshot(adapterKey).recentSenders.map {
                    UiEmailSenderCandidate(
                        email = it.email,
                        subject = it.subject,
                        note = it.note
                    )
                }
                _uiState.update {
                    it.copy(
                        sessionBindingEmailDiscoveryAttempted = true,
                        sessionBindingEmailDiscovering = false,
                        sessionBindingEmailCandidates = fallback,
                        sessionBindingEmailInfo = t.message ?: "Email sender detection failed."
                    )
                }
                showSettingsInfo(t.message ?: "Email sender detection failed.")
            }
        }
    }

    fun clearEmailSenderDiscovery() = channelBindingCoordinator.clearEmailSenderDiscovery()

    private fun clearEmailSenderDiscoveryInternal() {
        _uiState.update {
            it.copy(
                sessionBindingEmailDiscoveryAttempted = false,
                sessionBindingEmailDiscovering = false,
                sessionBindingEmailCandidates = emptyList(),
                sessionBindingEmailInfo = null
            )
        }
    }

    fun discoverWeComChatsForBinding(botId: String, secret: String) =
        channelBindingCoordinator.discoverWeComChatsForBinding(botId, secret)

    private fun discoverWeComChatsForBindingInternal(botId: String, secret: String) {
        _uiState.update {
            it.copy(
                sessionBindingWeComDiscoveryAttempted = true,
                sessionBindingWeComDiscovering = true,
                sessionBindingWeComCandidates = emptyList(),
                sessionBindingWeComInfo = null
            )
        }
        val adapterKey = buildWeComAdapterKey(botId, secret)
        if (adapterKey == null) {
            _uiState.update {
                it.copy(
                    sessionBindingWeComDiscoveryAttempted = true,
                    sessionBindingWeComDiscovering = false,
                    sessionBindingWeComCandidates = emptyList(),
                    sessionBindingWeComInfo = "Save Bot ID and Secret first, then detect again."
                )
            }
            showSettingsInfo("Save Bot ID and Secret first, then detect again.")
            return
        }
        viewModelScope.launch {
            showSettingsInfo("Detecting WeCom chats...")
            var snapshot = WeComGatewayDiagnostics.getSnapshot(adapterKey)
            for (attempt in 0 until WECOM_DISCOVERY_STARTUP_RETRIES) {
                if (snapshot.recentChats.isNotEmpty() || hasWeComSnapshotActivity(snapshot)) {
                    break
                }
                delay(WECOM_DISCOVERY_STARTUP_RETRY_DELAY_MS)
                snapshot = WeComGatewayDiagnostics.getSnapshot(adapterKey)
            }
            val candidates = snapshot.recentChats.map {
                UiWeComChatCandidate(
                    chatId = it.chatId,
                    title = it.title,
                    kind = it.kind,
                    note = it.note
                )
            }
            _uiState.update {
                it.copy(
                    sessionBindingWeComDiscoveryAttempted = true,
                    sessionBindingWeComDiscovering = false,
                    sessionBindingWeComCandidates = candidates,
                    sessionBindingWeComInfo = if (candidates.isEmpty()) {
                        buildWeComDiscoveryInfo(snapshot)
                    } else {
                        "WeCom chats discovered. Tap one to use."
                    }
                )
            }
            if (candidates.isNotEmpty()) {
                showSettingsInfo("WeCom chats discovered. Tap one to use.")
            } else {
                showSettingsInfo(buildWeComDiscoveryInfo(snapshot))
            }
        }
    }

    private fun hasWeComSnapshotActivity(snapshot: com.palmclaw.channels.WeComGatewaySnapshot?): Boolean {
        if (snapshot == null) return false
        return snapshot.running ||
            snapshot.connected ||
            snapshot.ready ||
            snapshot.inboundSeen > 0L ||
            snapshot.inboundForwarded > 0L ||
            snapshot.outboundSent > 0L ||
            snapshot.lastError.isNotBlank() ||
            snapshot.recentChats.isNotEmpty()
    }

    private fun buildWeComDiscoveryInfo(snapshot: com.palmclaw.channels.WeComGatewaySnapshot?): String {
        if (snapshot == null) {
            return "Save Bot ID and Secret first, then detect again."
        }
        if (snapshot.lastError.isNotBlank()) {
            return "WeCom connection is not ready yet."
        }
        if (!snapshot.running) {
            return "WeCom adapter is not running yet. Save once and keep PalmClaw open."
        }
        if (!snapshot.ready) {
            return "WeCom connection is starting. Finish setup, then detect again."
        }
        if (snapshot.inboundSeen <= 0L) {
            return "WeCom connection is ready, but PalmClaw has not received any inbound message yet. Send one message from WeCom first."
        }
        return "WeCom messages have reached PalmClaw, but no bindable chat has been cached yet. Send one more message, then try Detect Chats again."
    }

    fun clearWeComChatDiscovery() = channelBindingCoordinator.clearWeComChatDiscovery()

    private fun clearWeComChatDiscoveryInternal() {
        _uiState.update {
            it.copy(
                sessionBindingWeComDiscoveryAttempted = false,
                sessionBindingWeComDiscovering = false,
                sessionBindingWeComCandidates = emptyList(),
                sessionBindingWeComInfo = null
            )
        }
    }

    fun triggerHeartbeatNow() = runtimeCoordinator.triggerHeartbeatNow()

    private fun triggerHeartbeatNowInternal() {
        viewModelScope.launch {
            runCatching { triggerHeartbeatViaActiveRuntime() }
                .onFailure { t ->
                    _uiState.update {
                        it.copy(settingsInfo = t.message ?: t.javaClass.simpleName)
                    }
                }
        }
    }

    fun loadHeartbeatDocument() = runtimeCoordinator.loadHeartbeatDocument()

    private fun loadHeartbeatDocumentInternal() {
        viewModelScope.launch {
            val text = withContext(Dispatchers.IO) { readHeartbeatDoc() }
            _uiState.update { it.copy(settingsHeartbeatDoc = text) }
        }
    }

    fun onSettingsHeartbeatDocChanged(value: String) =
        runtimeCoordinator.onSettingsHeartbeatDocChanged(value)

    fun saveHeartbeatDocument(
        showSuccessMessage: Boolean = true,
        showErrorMessage: Boolean = true
    ) = runtimeCoordinator.saveHeartbeatDocument(showSuccessMessage, showErrorMessage)

    private fun saveHeartbeatDocumentInternal(
        showSuccessMessage: Boolean = true,
        showErrorMessage: Boolean = true
    ) {
        viewModelScope.launch {
            val content = _uiState.value.settingsHeartbeatDoc
            runCatching {
                persistHeartbeatSettings(
                    HeartbeatSetTool.Request(documentContent = content)
                )
            }.onSuccess {
                _uiState.update {
                    it.copy(settingsInfo = if (showSuccessMessage) "HEARTBEAT.md saved." else null)
                }
            }.onFailure { t ->
                _uiState.update {
                    it.copy(
                        settingsInfo = if (showErrorMessage) {
                            "Save HEARTBEAT.md failed: ${t.message ?: t.javaClass.simpleName}"
                        } else {
                            null
                        }
                    )
                }
            }
        }
    }

    fun refreshCronLogs() = runtimeCoordinator.refreshCronLogs()

    private fun refreshCronLogsInternal() {
        viewModelScope.launch {
            val logs = withContext(Dispatchers.IO) { cronLogStore.readRecent() }
            _uiState.update { it.copy(settingsCronLogs = logs) }
        }
    }

    fun clearCronLogs() = runtimeCoordinator.clearCronLogs()

    private fun clearCronLogsInternal() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { cronLogStore.clear() }
            _uiState.update {
                it.copy(
                    settingsCronLogs = "",
                    settingsInfo = "Cron logs cleared."
                )
            }
        }
    }

    fun refreshAgentLogs() = runtimeCoordinator.refreshAgentLogs()

    private fun refreshAgentLogsInternal() {
        viewModelScope.launch {
            val logs = withContext(Dispatchers.IO) { agentLogStore.readRecent() }
            _uiState.update { it.copy(settingsAgentLogs = logs) }
        }
    }

    fun refreshSessionConnectionStatus() = channelBindingCoordinator.refreshSessionConnectionStatus()

    private fun refreshSessionConnectionStatusInternal() {
        refreshSessionBindingsInState()
    }

    fun clearAgentLogs() = runtimeCoordinator.clearAgentLogs()

    private fun clearAgentLogsInternal() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { agentLogStore.clear() }
            _uiState.update {
                it.copy(
                    settingsAgentLogs = "",
                    settingsInfo = "Agent logs cleared."
                )
            }
        }
    }

    fun saveProviderSettings(
        showSuccessMessage: Boolean = true,
        showErrorMessage: Boolean = true
    ) = providerSettingsCoordinator.saveProviderSettings(showSuccessMessage, showErrorMessage)

    private fun saveProviderSettingsInternal(
        showSuccessMessage: Boolean = true,
        showErrorMessage: Boolean = true
    ) {
        if (_uiState.value.settingsSaving) return
        viewModelScope.launch {
            _uiState.update { it.copy(settingsSaving = true, settingsInfo = null) }
            runCatching {
                val updatedState = buildProviderStateWithSavedDraft(_uiState.value)
                updatedState.settingsEditingProviderConfigId
                    .takeIf { it.isNotBlank() }
                    ?.let { configId ->
                        val cachePrefix = ProviderResolutionStore.cachePrefixForProviderConfig(configId)
                        AdaptiveLlmProvider.clearRememberedTargets(cachePrefix)
                        providerResolutionStore.clearByPrefix(cachePrefix)
                    }
                configStore.saveConfig(buildProviderSettingsConfig(updatedState))
                updatedState
            }.onSuccess { updatedState ->
                _uiState.update {
                    it.copy(
                        settingsSaving = false,
                        settingsProviderConfigs = updatedState.settingsProviderConfigs,
                        settingsEditingProviderConfigId = updatedState.settingsEditingProviderConfigId,
                        settingsProvider = updatedState.settingsProvider,
                        settingsProviderCustomName = updatedState.settingsProviderCustomName,
                        settingsProviderProtocol = updatedState.settingsProviderProtocol,
                        settingsBaseUrl = updatedState.settingsBaseUrl,
                        settingsModel = updatedState.settingsModel,
                        settingsApiKey = updatedState.settingsApiKey,
                        settingsInfo = if (showSuccessMessage) "Provider saved." else null
                    )
                }
            }.onFailure { t ->
                _uiState.update {
                    it.copy(
                        settingsSaving = false,
                        settingsInfo = if (showErrorMessage) {
                            "Save failed: ${t.message ?: t.javaClass.simpleName}"
                        } else {
                            null
                        }
                    )
                }
            }
        }
    }

    fun saveAgentRuntimeSettings(
        showSuccessMessage: Boolean = true,
        showErrorMessage: Boolean = true
    ) = providerSettingsCoordinator.saveAgentRuntimeSettings(showSuccessMessage, showErrorMessage)

    private fun saveAgentRuntimeSettingsInternal(
        showSuccessMessage: Boolean = true,
        showErrorMessage: Boolean = true
    ) {
        if (_uiState.value.settingsSaving) return
        viewModelScope.launch {
            _uiState.update { it.copy(settingsSaving = true, settingsInfo = null) }
            runCatching {
                val state = _uiState.value
                persistRuntimeSettings(
                    RuntimeSetTool.Request(
                        maxToolRounds = state.settingsMaxToolRounds.trim().toIntOrNull()
                            ?: throw IllegalArgumentException("Max rounds must be a number"),
                        toolResultMaxChars = state.settingsToolResultMaxChars.trim().toIntOrNull()
                            ?: throw IllegalArgumentException("Tool result max chars must be a number"),
                        memoryConsolidationWindow = state.settingsMemoryConsolidationWindow.trim().toIntOrNull()
                            ?: throw IllegalArgumentException("Memory consolidation window must be a number"),
                        llmCallTimeoutSeconds = state.settingsLlmCallTimeoutSeconds.trim().toIntOrNull()
                            ?: throw IllegalArgumentException("LLM call timeout must be a number"),
                        llmConnectTimeoutSeconds = state.settingsLlmConnectTimeoutSeconds.trim().toIntOrNull()
                            ?: throw IllegalArgumentException("LLM connect timeout must be a number"),
                        llmReadTimeoutSeconds = state.settingsLlmReadTimeoutSeconds.trim().toIntOrNull()
                            ?: throw IllegalArgumentException("LLM read timeout must be a number"),
                        defaultToolTimeoutSeconds = state.settingsDefaultToolTimeoutSeconds.trim().toIntOrNull()
                            ?: throw IllegalArgumentException("Default tool timeout must be a number"),
                        contextMessages = state.settingsContextMessages.trim().toIntOrNull()
                            ?: throw IllegalArgumentException("Context messages must be a number"),
                        toolArgsPreviewMaxChars = state.settingsToolArgsPreviewMaxChars.trim().toIntOrNull()
                            ?: throw IllegalArgumentException("Tool args preview max chars must be a number")
                    )
                )
            }.onSuccess {
                _uiState.update {
                    it.copy(
                        settingsSaving = false,
                        settingsInfo = if (showSuccessMessage) "Runtime saved." else null
                    )
                }
            }.onFailure { t ->
                _uiState.update {
                    it.copy(
                        settingsSaving = false,
                        settingsInfo = if (showErrorMessage) {
                            "Save failed: ${t.message ?: t.javaClass.simpleName}"
                        } else {
                            null
                        }
                    )
                }
            }
        }
    }

    fun saveCronSettings(
        showSuccessMessage: Boolean = true,
        showErrorMessage: Boolean = true
    ) = runtimeCoordinator.saveCronSettings(showSuccessMessage, showErrorMessage)

    private fun saveCronSettingsInternal(
        showSuccessMessage: Boolean = true,
        showErrorMessage: Boolean = true
    ) {
        if (_uiState.value.settingsSaving) return
        viewModelScope.launch {
            _uiState.update { it.copy(settingsSaving = true, settingsInfo = null) }
            runCatching {
                val state = _uiState.value
                persistCronSettings(
                    com.palmclaw.tools.CronConfigUpdate(
                        enabled = state.settingsCronEnabled,
                        minEveryMs = state.settingsCronMinEveryMs.trim().toLongOrNull()
                            ?: throw IllegalArgumentException("Cron min interval ms must be a number"),
                        maxJobs = state.settingsCronMaxJobs.trim().toIntOrNull()
                            ?: throw IllegalArgumentException("Cron max jobs must be a number")
                    )
                )
            }.onSuccess {
                refreshCronJobs()
                _uiState.update {
                    it.copy(
                        settingsSaving = false,
                        settingsInfo = if (showSuccessMessage) "Cron saved." else null
                    )
                }
            }.onFailure { t ->
                _uiState.update {
                    it.copy(
                        settingsSaving = false,
                        settingsInfo = if (showErrorMessage) {
                            "Save failed: ${t.message ?: t.javaClass.simpleName}"
                        } else {
                            null
                        }
                    )
                }
            }
        }
    }

    fun saveHeartbeatSettings(
        showSuccessMessage: Boolean = true,
        showErrorMessage: Boolean = true
    ) = runtimeCoordinator.saveHeartbeatSettings(showSuccessMessage, showErrorMessage)

    private fun saveHeartbeatSettingsInternal(
        showSuccessMessage: Boolean = true,
        showErrorMessage: Boolean = true
    ) {
        if (_uiState.value.settingsSaving) return
        viewModelScope.launch {
            _uiState.update { it.copy(settingsSaving = true, settingsInfo = null) }
            runCatching {
                val state = _uiState.value
                persistHeartbeatSettings(
                    HeartbeatSetTool.Request(
                        enabled = state.settingsHeartbeatEnabled,
                        intervalSeconds = state.settingsHeartbeatIntervalSeconds.trim().toLongOrNull()
                            ?: throw IllegalArgumentException("Heartbeat interval seconds must be a number")
                    )
                )
            }.onSuccess {
                _uiState.update {
                    it.copy(
                        settingsSaving = false,
                        settingsInfo = if (showSuccessMessage) "Heartbeat saved." else null
                    )
                }
            }.onFailure { t ->
                _uiState.update {
                    it.copy(
                        settingsSaving = false,
                        settingsInfo = if (showErrorMessage) {
                            "Save failed: ${t.message ?: t.javaClass.simpleName}"
                        } else {
                            null
                        }
                    )
                }
            }
        }
    }

    fun onAlwaysOnEnabledChanged(value: Boolean) =
        runtimeCoordinator.onAlwaysOnEnabledChanged(value)

    fun onAlwaysOnKeepScreenAwakeChanged(value: Boolean) =
        runtimeCoordinator.onAlwaysOnKeepScreenAwakeChanged(value)

    fun saveAlwaysOnSettings(
        showSuccessMessage: Boolean = true,
        showErrorMessage: Boolean = true
    ) = runtimeCoordinator.saveAlwaysOnSettings(showSuccessMessage, showErrorMessage)

    private fun saveAlwaysOnSettingsInternal(
        showSuccessMessage: Boolean = true,
        showErrorMessage: Boolean = true
    ) {
        if (_uiState.value.settingsSaving) return
        viewModelScope.launch {
            _uiState.update { it.copy(settingsSaving = true, settingsInfo = null) }
            runCatching {
                val next = AlwaysOnConfig(
                    enabled = _uiState.value.alwaysOnEnabled,
                    keepScreenAwake = _uiState.value.alwaysOnKeepScreenAwake
                )
                configStore.saveAlwaysOnConfig(next)
                val app = getApplication<Application>()
                if (next.enabled) {
                    RuntimeController.stop()
                    AlwaysOnHealthCheckWorker.ensureScheduled(app)
                    AlwaysOnModeController.startService(app)
                    AlwaysOnModeController.reloadAll()
                } else {
                    AlwaysOnHealthCheckWorker.cancel(app)
                    AlwaysOnModeController.stopService(app)
                    RuntimeController.start(app)
                    RuntimeController.reloadAll(app)
                }
                refreshAlwaysOnDiagnostics()
            }.onSuccess {
                _uiState.update {
                    it.copy(
                        settingsSaving = false,
                        settingsInfo = if (showSuccessMessage) "Always-on mode settings saved." else null
                    )
                }
            }.onFailure { t ->
                _uiState.update {
                    it.copy(
                        settingsSaving = false,
                        settingsInfo = if (showErrorMessage) {
                            "Save failed: ${t.message ?: t.javaClass.simpleName}"
                        } else {
                            null
                        }
                    )
                }
            }
        }
    }

    fun refreshAlwaysOnDiagnostics() = runtimeCoordinator.refreshAlwaysOnDiagnostics()

    private fun refreshAlwaysOnDiagnosticsInternal() {
        val app = getApplication<Application>()
        val status = AlwaysOnModeController.status.value
        val connectivityManager = app.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val powerManager = app.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val alarmManager = app.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
        val activeNetwork = connectivityManager?.activeNetwork
        val capabilities = activeNetwork?.let { connectivityManager.getNetworkCapabilities(it) }
        val connected = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        val batteryIntent = app.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val chargingStatus = batteryIntent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val isCharging = chargingStatus == BatteryManager.BATTERY_STATUS_CHARGING ||
            chargingStatus == BatteryManager.BATTERY_STATUS_FULL
        val ignoringOptimizations = powerManager?.let {
            runCatching { it.isIgnoringBatteryOptimizations(app.packageName) }.getOrDefault(false)
        } ?: false
        val canScheduleExactAlarm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            alarmManager?.canScheduleExactAlarms() == true
        } else {
            true
        }
        _uiState.update {
            it.copy(
                alwaysOnServiceRunning = status.serviceRunning,
                alwaysOnNotificationActive = status.notificationActive,
                alwaysOnGatewayRunning = status.gatewayRunning,
                alwaysOnActiveAdapterCount = status.activeAdapterCount,
                alwaysOnStartedAtMs = status.startedAtMs,
                alwaysOnLastError = status.lastError,
                alwaysOnNetworkConnected = connected,
                alwaysOnCharging = isCharging,
                alwaysOnBatteryOptimizationIgnored = ignoringOptimizations
                ,
                alwaysOnExactAlarmAllowed = canScheduleExactAlarm
            )
        }
    }

    fun saveChannelsSettings(
        showSuccessMessage: Boolean = true,
        showErrorMessage: Boolean = true
    ) = runtimeCoordinator.saveChannelsSettings(showSuccessMessage, showErrorMessage)

    private fun saveChannelsSettingsInternal(
        showSuccessMessage: Boolean = true,
        showErrorMessage: Boolean = true
    ) {
        if (_uiState.value.settingsSaving) return
        viewModelScope.launch {
            _uiState.update { it.copy(settingsSaving = true, settingsInfo = null) }
            runCatching {
                val bindings = configStore.getSessionChannelBindings()
                val current = configStore.getChannelsConfig()
                val shouldEnableGateway = hasActiveGatewayBinding(bindings)
                val runtimeConfig = current.copy(enabled = shouldEnableGateway)
                configStore.saveChannelsConfig(runtimeConfig)
                applyGatewayRuntimeConfig(runtimeConfig)
            }.onSuccess {
                _uiState.update {
                    it.copy(
                        settingsSaving = false,
                        settingsGatewayEnabled = configStore.getChannelsConfig().enabled,
                        settingsInfo = if (showSuccessMessage) {
                            "Channels synced."
                        } else {
                            null
                        }
                    )
                }
            }.onFailure { t ->
                _uiState.update {
                    it.copy(
                        settingsSaving = false,
                        settingsInfo = if (showErrorMessage) {
                            "Save failed: ${t.message ?: t.javaClass.simpleName}"
                        } else {
                            null
                        }
                    )
                }
            }
        }
    }

    fun saveMcpSettings(
        showSuccessMessage: Boolean = true,
        showErrorMessage: Boolean = true
    ) = runtimeCoordinator.saveMcpSettings(showSuccessMessage, showErrorMessage)

    private fun saveMcpSettingsInternal(
        showSuccessMessage: Boolean = true,
        showErrorMessage: Boolean = true
    ) {
        if (_uiState.value.settingsSaving) return
        viewModelScope.launch {
            _uiState.update { it.copy(settingsSaving = true, settingsInfo = null) }
            runCatching {
                val state = _uiState.value
                val normalizedMcpServers = buildNormalizedMcpServers(state)
                val duplicateMcpNames = normalizedMcpServers
                    .groupingBy { it.serverName.trim().lowercase(Locale.US) }
                    .eachCount()
                    .filterValues { it > 1 }
                if (duplicateMcpNames.isNotEmpty()) {
                    throw IllegalArgumentException("MCP server names must be unique.")
                }
                if (state.settingsMcpEnabled && normalizedMcpServers.isEmpty()) {
                    throw IllegalArgumentException("Enable MCP requires at least one configured server.")
                }
                val firstMcpServer = normalizedMcpServers.firstOrNull()
                val mcpConfig = McpHttpConfig(
                    enabled = state.settingsMcpEnabled,
                    serverName = firstMcpServer?.serverName ?: AppLimits.DEFAULT_MCP_HTTP_SERVER_NAME,
                    serverUrl = firstMcpServer?.serverUrl.orEmpty(),
                    authToken = firstMcpServer?.authToken.orEmpty(),
                    toolTimeoutSeconds = firstMcpServer?.toolTimeoutSeconds
                        ?: AppLimits.DEFAULT_MCP_HTTP_TOOL_TIMEOUT_SECONDS,
                    servers = normalizedMcpServers
                )
                configStore.saveMcpHttpConfig(mcpConfig)
                reloadMcpViaActiveRuntime(mcpConfig)
            }.onSuccess {
                _uiState.update {
                    it.copy(
                        settingsSaving = false,
                        settingsInfo = if (showSuccessMessage) "MCP saved." else null
                    )
                }
            }.onFailure { t ->
                _uiState.update {
                    it.copy(
                        settingsSaving = false,
                        settingsInfo = if (showErrorMessage) {
                            "Save failed: ${t.message ?: t.javaClass.simpleName}"
                        } else {
                            null
                        }
                    )
                }
            }
        }
    }

    fun testProviderSettings() = providerSettingsCoordinator.testProviderSettings()

    private fun testProviderSettingsInternal() {
        if (_uiState.value.settingsProviderTesting) return
        viewModelScope.launch {
            _uiState.update { it.copy(settingsProviderTesting = true, settingsInfo = null) }
            runCatching {
                val config = buildProviderTestConfig(_uiState.value)
                val provider = LlmProviderFactory(providerResolutionStore).create(config)
                val response = withContext(Dispatchers.IO) {
                    provider.chat(
                        messages = listOf(
                            ChatMessage(
                                role = "user",
                                content = "Reply with exactly OK."
                            )
                        ),
                        toolsSpec = emptyList()
                    )
                }
                val content = response.assistant.content.trim()
                if (content.isBlank() && response.assistant.toolCalls.isEmpty()) {
                    "Provider responded, but returned empty content."
                } else {
                    "Provider test passed."
                }
            }.onSuccess { result ->
                _uiState.update {
                    it.copy(
                        settingsProviderTesting = false,
                        settingsInfo = result
                    )
                }
            }.onFailure { t ->
                _uiState.update {
                    it.copy(
                        settingsProviderTesting = false,
                        settingsInfo = "Provider test failed: ${t.message ?: t.javaClass.simpleName}"
                    )
                }
            }
        }
    }

    fun saveSettings() {
        saveProviderSettings()
    }

    private fun buildProviderTestConfig(state: ChatUiState) : com.palmclaw.config.AppConfig {
        val provider = ProviderCatalog.resolve(state.settingsProvider).id
        val protocol = ProviderCatalog.resolveProtocol(provider, state.settingsProviderProtocol, state.settingsBaseUrl)
        val model = state.settingsModel.trim().ifBlank { ProviderCatalog.defaultModel(provider, protocol) }
        val apiKey = state.settingsApiKey.trim()
        val baseUrl = state.settingsBaseUrl.trim()
        if (baseUrl.isBlank()) {
            throw IllegalArgumentException("Endpoint URL is required")
        }
        val parsedBaseUrl = baseUrl.toHttpUrlOrNull()
            ?: throw IllegalArgumentException("Endpoint URL is invalid")
        val scheme = parsedBaseUrl.scheme.lowercase(Locale.US)
        if (scheme != "http" && scheme != "https") {
            throw IllegalArgumentException("Endpoint URL must start with http:// or https://")
        }
        val current = configStore.getConfig()
        return current.copy(
            providerName = provider,
            providerProtocol = protocol,
            apiKey = apiKey,
            model = model,
            baseUrl = baseUrl,
            activeProviderConfigId = state.settingsEditingProviderConfigId.trim()
        )
    }

    private fun buildProviderStateWithSavedDraft(state: ChatUiState): ChatUiState {
        val savedConfig = buildValidatedProviderDraft(state)
        val currentConfigs = state.settingsProviderConfigs
        val existing = currentConfigs.firstOrNull { it.id == savedConfig.id }
        val shouldEnable = existing?.enabled ?: true
        val updatedConfigs = normalizeActiveProviderConfigs(
            currentConfigs.filterNot { it.id == savedConfig.id } + savedConfig.copy(enabled = shouldEnable)
        )
        val selected = updatedConfigs.firstOrNull { it.id == savedConfig.id } ?: updatedConfigs.firstOrNull()
        return state.copy(
            settingsProviderConfigs = updatedConfigs,
            settingsEditingProviderConfigId = selected?.id.orEmpty(),
            settingsProvider = selected?.providerName ?: state.settingsProvider,
            settingsProviderCustomName = selected?.customName ?: state.settingsProviderCustomName,
            settingsProviderProtocol = selected?.providerProtocol ?: state.settingsProviderProtocol,
            settingsBaseUrl = selected?.let { config ->
                config.baseUrl.ifBlank {
                    ProviderCatalog.defaultBaseUrl(config.providerName, config.providerProtocol)
                }
            } ?: state.settingsBaseUrl,
            settingsModel = selected?.model ?: state.settingsModel,
            settingsApiKey = selected?.apiKey ?: state.settingsApiKey
        )
    }

    private fun buildValidatedProviderDraft(state: ChatUiState): UiProviderConfig {
        val provider = ProviderCatalog.resolve(state.settingsProvider).id
        val baseUrl = state.settingsBaseUrl.trim()
        val protocol = ProviderCatalog.resolveProtocol(provider, state.settingsProviderProtocol, baseUrl)
        val model = state.settingsModel.trim().ifBlank { ProviderCatalog.defaultModel(provider, protocol) }
        val apiKey = state.settingsApiKey.trim()
        if (baseUrl.isBlank()) {
            throw IllegalArgumentException("Endpoint URL is required")
        }
        val parsedBaseUrl = baseUrl.toHttpUrlOrNull()
            ?: throw IllegalArgumentException("Endpoint URL is invalid")
        val scheme = parsedBaseUrl.scheme.lowercase(Locale.US)
        if (scheme != "http" && scheme != "https") {
            throw IllegalArgumentException("Endpoint URL must start with http:// or https://")
        }
        val id = state.settingsEditingProviderConfigId.trim()
            .ifBlank { "provider_${System.currentTimeMillis()}_${state.settingsProviderConfigs.size + 1}" }
        val enabled = state.settingsProviderConfigs.firstOrNull { it.id == id }?.enabled
            ?: state.settingsProviderConfigs.isEmpty()
        return UiProviderConfig(
            id = id,
            providerName = provider,
            customName = if (provider == "custom") state.settingsProviderCustomName.trim() else "",
            providerProtocol = protocol,
            apiKey = apiKey,
            model = model,
            baseUrl = baseUrl,
            enabled = enabled
        )
    }

    private fun buildProviderSettingsConfig(state: ChatUiState): com.palmclaw.config.AppConfig {
        val normalizedConfigs = normalizeActiveProviderConfigs(state.settingsProviderConfigs)
        val activeConfig = normalizedConfigs.firstOrNull { it.enabled } ?: normalizedConfigs.firstOrNull()
        val current = configStore.getConfig()
        return current.copy(
            providerName = activeConfig?.providerName ?: ProviderCatalog.resolve(state.settingsProvider).id,
            providerProtocol = activeConfig?.providerProtocol ?: state.settingsProviderProtocol,
            apiKey = activeConfig?.apiKey ?: state.settingsApiKey.trim(),
            model = activeConfig?.model ?: state.settingsModel.trim().ifBlank {
                ProviderCatalog.defaultModel(state.settingsProvider, state.settingsProviderProtocol)
            },
            baseUrl = activeConfig?.baseUrl ?: state.settingsBaseUrl.trim(),
            providerConfigs = normalizedConfigs.map { config ->
                ProviderConnectionConfig(
                    id = config.id,
                    providerName = config.providerName,
                    customName = config.customName,
                    providerProtocol = config.providerProtocol,
                    apiKey = config.apiKey,
                    model = config.model,
                    baseUrl = config.baseUrl
                )
            },
            activeProviderConfigId = activeConfig?.id.orEmpty()
        )
    }

    private fun normalizeActiveProviderConfigs(configs: List<UiProviderConfig>): List<UiProviderConfig> {
        if (configs.isEmpty()) return emptyList()
        val activeId = configs.firstOrNull { it.enabled }?.id ?: configs.first().id
        return configs.map { it.copy(enabled = it.id == activeId) }
    }

    private fun maybeTriggerFirstRunAutoIntro() {
        val state = _uiState.value
        val activeSessionId = currentSessionId.trim().ifBlank { AppSession.LOCAL_SESSION_ID }
        if (!state.onboardingCompleted) return
        if (activeSessionId != AppSession.LOCAL_SESSION_ID) return
        if (configStore.hasCompletedFirstRunAutoIntro()) return
        if (firstRunAutoIntroPending || generatingJob != null || state.isGenerating) return

        val text = if (state.settingsUseChinese) {
            "请先简单介绍一下你自己，你现在能帮我做什么？"
        } else {
            "Please briefly introduce yourself. What can you help me with right now?"
        }

        firstRunAutoIntroPending = true
        _uiState.update { it.copy(isGenerating = true) }
        generatingJob = viewModelScope.launch {
            try {
                runUserMessageViaActiveRuntime(
                    sessionId = AppSession.LOCAL_SESSION_ID,
                    sessionTitle = AppSession.LOCAL_SESSION_TITLE,
                    text = text
                )
                configStore.markFirstRunAutoIntroCompleted()
            } catch (t: CancellationException) {
                throw t
            } finally {
                firstRunAutoIntroPending = false
                generatingJob = null
                syncGeneratingState()
                loadSettingsIntoState()
            }
        }
    }

    override fun onCleared() {
        sessionCoordinator.clear()
        generatingJob?.cancel()
        generatingJob = null
        super.onCleared()
    }

    private fun bootstrapLocalSessions() {
        viewModelScope.launch {
            runCatching {
                sessionRepository.ensureSessionExists(AppSession.LOCAL_SESSION_ID, AppSession.LOCAL_SESSION_TITLE)
                sessionRepository.touch(AppSession.LOCAL_SESSION_ID)
            }.onFailure { t ->
                Log.e(TAG, "Failed to bootstrap local session", t)
            }
        }
    }

    private fun mapMessagesToUi(messages: List<MessageEntity>): List<UiMessage> {
        val mapped = mutableListOf<UiMessage>()
        var index = 0
        while (index < messages.size) {
            val message = messages[index]
            if (message.role == "assistant" && !message.toolCallJson.isNullOrBlank()) {
                val toolCalls = parseToolCalls(message.toolCallJson.orEmpty())
                val assistantNote = message.content.trim()
                    .takeIf { it.isNotBlank() && !it.equals("[tool call]", ignoreCase = true) }
                var scan = index + 1
                val contiguousToolMessages = mutableListOf<MessageEntity>()
                while (scan < messages.size && messages[scan].role == "tool") {
                    contiguousToolMessages += messages[scan]
                    scan += 1
                }

                if (toolCalls.isNotEmpty()) {
                    val pending = contiguousToolMessages.map { entity ->
                        ToolResultEnvelope(
                            entity = entity,
                            parsed = parseToolResult(entity.toolResultJson)
                        )
                    }.toMutableList()

                    toolCalls.forEachIndexed { callIndex, call ->
                        val exactMatchIndex = pending.indexOfFirst {
                            it.parsed?.toolCallId?.trim().orEmpty() == call.id.trim()
                        }
                        val matched = when {
                            exactMatchIndex >= 0 -> pending.removeAt(exactMatchIndex)
                            pending.isNotEmpty() -> pending.removeAt(0)
                            else -> null
                        }
                        mapped += buildCombinedToolUiMessage(
                            baseMessage = message,
                            callIndex = callIndex,
                            call = call,
                            matchedResult = matched,
                            assistantNote = assistantNote
                        )
                    }

                    pending.forEachIndexed { orphanIndex, orphan ->
                        mapped += orphan.entity.toUiModel(
                            forcedId = syntheticToolMessageId(message.id, 900 + orphanIndex)
                        )
                    }
                } else {
                    mapped += message.toUiModel()
                    contiguousToolMessages.forEachIndexed { orphanIndex, orphan ->
                        mapped += orphan.toUiModel(
                            forcedId = syntheticToolMessageId(message.id, 950 + orphanIndex)
                        )
                    }
                }
                index = scan
                continue
            }

            if (message.role == "tool") {
                mapped += message.toUiModel()
                index += 1
                continue
            }

            mapped += message.toUiModel()
            index += 1
        }
        return mapped
    }

    private fun buildSessionSummaries(raw: List<SessionEntity>): List<UiSessionSummary> {
        val bindingsBySession = configStore.getSessionChannelBindings()
            .associateBy { it.sessionId.trim() }
        return UiSessionSummaryProjector.build(
            rawSessions = raw,
            bindingsBySession = bindingsBySession
        )
    }

    private fun refreshSessionBindingsInState() {
        _uiState.update { state ->
            val bindingsBySession = configStore.getSessionChannelBindings()
                .associateBy { it.sessionId.trim() }
            val sessions = UiSessionSummaryProjector.applyBindings(
                sessions = state.sessions,
                bindingsBySession = bindingsBySession
            )
            state.copy(
                sessions = sessions,
                settingsConnectedChannels = buildConnectedChannelsOverview(sessions)
            )
        }
    }

    private fun MessageEntity.shouldDisplayInChat(): Boolean {
        val text = content.trim()
        return when (role) {
            "user" -> text.isNotBlank()
            "assistant" -> {
                if (text.startsWith("[debug tool call]", ignoreCase = true)) {
                    return false
                }
                if (text == "[tool call]" && toolCallJson.isNullOrBlank()) {
                    return false
                }
                text.isNotBlank() || !toolCallJson.isNullOrBlank()
            }

            "tool" -> text.isNotBlank() || !toolResultJson.isNullOrBlank()
            else -> false
        }
    }

    private fun MessageEntity.toUiModel(forcedId: Long? = null): UiMessage {
        if (role == "assistant" && !toolCallJson.isNullOrBlank()) {
            val details = formatToolCallContent(
                toolCallJson = toolCallJson.orEmpty(),
                assistantContent = content
            )
            return UiMessage(
                id = forcedId ?: id,
                role = "tool",
                content = formatToolCallSummary(
                    toolCallJson = toolCallJson.orEmpty()
                ),
                createdAt = createdAt,
                isCollapsible = true,
                expandedContent = details,
                attachments = emptyList()
            )
        }
        if (role == "tool") {
            val attachments = extractToolResultAttachments(
                toolResultJson = toolResultJson,
                fallbackContent = content
            )
            val details = formatToolResultContent(
                toolResultJson = toolResultJson,
                fallbackContent = content
            )
            return UiMessage(
                id = forcedId ?: id,
                role = "tool",
                content = formatToolResultSummary(
                    toolResultJson = toolResultJson,
                    fallbackContent = content
                ),
                createdAt = createdAt,
                isCollapsible = true,
                expandedContent = details,
                attachments = attachments
            )
        }
        return UiMessage(
            id = forcedId ?: id,
            role = role,
            content = content.ifBlank { "[empty]" },
            createdAt = createdAt,
            attachments = emptyList()
        )
    }

    private fun buildCombinedToolUiMessage(
        baseMessage: MessageEntity,
        callIndex: Int,
        call: ToolCall,
        matchedResult: ToolResultEnvelope?,
        assistantNote: String?
    ): UiMessage {
        val resultEntity = matchedResult?.entity
        val parsedResult = matchedResult?.parsed
        val status = when {
            resultEntity == null -> "pending"
            parsedResult?.isError == true -> "error"
            else -> "ok"
        }
        val details = formatSingleToolTraceContent(
            call = call,
            matchedEntity = resultEntity,
            assistantNote = assistantNote
        )
        return UiMessage(
            id = syntheticToolMessageId(baseMessage.id, callIndex),
            role = "tool",
            content = "${call.name} [$status]",
            createdAt = baseMessage.createdAt,
            isCollapsible = true,
            expandedContent = details,
            attachments = if (resultEntity != null) {
                extractToolResultAttachments(
                    toolResultJson = resultEntity.toolResultJson,
                    fallbackContent = resultEntity.content
                )
            } else {
                emptyList()
            }
        )
    }

    private fun formatSingleToolTraceContent(
        call: ToolCall,
        matchedEntity: MessageEntity?,
        assistantNote: String?
    ): String {
        val previewMaxChars = runtimeToolArgsPreviewMaxChars()
        val argsPretty = prettyJsonOrRaw(call.argumentsJson)
        return buildString {
            appendLine("Tool Call")
            appendLine("name=${call.name}")
            appendLine("call_id=${call.id}")
            appendLine("arguments:")
            appendLine("```json")
            appendLine(argsPretty.take(previewMaxChars))
            if (argsPretty.length > previewMaxChars) {
                appendLine("...(truncated)")
            }
            appendLine("```")
            appendLine()
            if (matchedEntity != null) {
                append(formatToolResultContent(matchedEntity.toolResultJson, matchedEntity.content))
            } else {
                appendLine("Tool Result")
                appendLine("status=pending")
                appendLine()
                append("(waiting for tool result)")
            }
            if (!assistantNote.isNullOrBlank()) {
                appendLine()
                appendLine()
                appendLine("assistant_note:")
                append(assistantNote)
            }
        }.trimEnd()
    }

    private fun syntheticToolMessageId(baseId: Long, offset: Int): Long {
        return baseId * 1000L + offset.toLong() + 1L
    }

    private fun formatToolCallSummary(toolCallJson: String): String {
        val calls = parseToolCalls(toolCallJson)
        if (calls.isEmpty()) {
            return "call"
        }
        val names = calls.map { it.name.trim() }.filter { it.isNotBlank() }
        if (names.isEmpty()) return "calls (${calls.size})"
        return if (names.size == 1) {
            names.first()
        } else {
            val preview = names.take(3).joinToString(", ")
            val remain = names.size - 3
            if (remain > 0) {
                "calls (${names.size}): $preview, +$remain more"
            } else {
                "calls (${names.size}): $preview"
            }
        }
    }

    private fun formatToolResultSummary(toolResultJson: String?, fallbackContent: String): String {
        val parsed = parseToolResult(toolResultJson)
        val status = if (parsed?.isError == true) "error" else "ok"
        val toolName = (parsed?.metadata?.get("mcp_tool") as? JsonPrimitive)
            ?.contentOrNull
            ?.takeIf { it.isNotBlank() }
        val rawLead = parsed?.content?.lineSequence()?.firstOrNull()?.trim()
            .orEmpty()
            .ifBlank { fallbackContent.lineSequence().firstOrNull()?.trim().orEmpty() }
            .ifBlank { "(no output)" }
        val lead = rawLead.take(90)
        return buildString {
            append(toolName ?: "result")
            append(" [")
            append(status)
            append("] ")
            append(lead)
            if (rawLead.length > 90) append("...")
        }
    }

    private fun formatToolCallContent(toolCallJson: String, assistantContent: String): String {
        val previewMaxChars = runtimeToolArgsPreviewMaxChars()
        val calls = parseToolCalls(toolCallJson)
        if (calls.isEmpty()) {
            return buildString {
                appendLine("Tool Call")
                appendLine()
                val fallback = assistantContent.trim()
                if (fallback.isNotBlank() && !fallback.equals("[tool call]", ignoreCase = true)) {
                    append(fallback)
                } else {
                    append(toolCallJson)
                }
            }.trimEnd()
        }

        return buildString {
            appendLine(if (calls.size == 1) "Tool Call" else "Tool Calls (${calls.size})")
            calls.forEachIndexed { index, call ->
                appendLine()
                appendLine("${index + 1}. name=${call.name}")
                appendLine("call_id=${call.id}")
                appendLine("arguments:")
                appendLine("```json")
                appendLine(
                    prettyJsonOrRaw(call.argumentsJson)
                        .take(previewMaxChars)
                )
                if (call.argumentsJson.length > previewMaxChars) {
                    appendLine("...(truncated)")
                }
                appendLine("```")
            }
            val note = assistantContent.trim()
            if (note.isNotBlank() && !note.equals("[tool call]", ignoreCase = true)) {
                appendLine()
                appendLine("assistant_note:")
                append(note)
            }
        }.trimEnd()
    }

    private fun formatToolResultContent(toolResultJson: String?, fallbackContent: String): String {
        val parsed = parseToolResult(toolResultJson)
        val body = parsed?.content?.trim().orEmpty()
            .ifBlank { fallbackContent.trim() }
            .ifBlank { "(empty)" }
        return buildString {
            appendLine("Tool Result")
            parsed?.toolCallId?.takeIf { it.isNotBlank() }?.let { appendLine("call_id=$it") }
            parsed?.let {
                appendLine("status=${if (it.isError) "error" else "ok"}")
                val errorCode = (it.metadata?.get("error") as? JsonPrimitive)?.contentOrNull
                if (!errorCode.isNullOrBlank()) {
                    appendLine("error=$errorCode")
                }
                val timeoutMs = (it.metadata?.get("timeout_ms") as? JsonPrimitive)?.contentOrNull
                if (!timeoutMs.isNullOrBlank()) {
                    appendLine("timeout_ms=$timeoutMs")
                }
            }
            appendLine()
            append(body)
        }.trimEnd()
    }

    private fun extractToolResultAttachments(
        toolResultJson: String?,
        fallbackContent: String
    ): List<UiMediaAttachment> {
        val parsed = parseToolResult(toolResultJson)
        if (parsed?.isError == true) return emptyList()

        val action = metadataString(parsed?.metadata, "action")?.lowercase(Locale.US).orEmpty()
        val mode = metadataString(parsed?.metadata, "mode")?.lowercase(Locale.US).orEmpty()
        if (action == "audio_record" && mode == "start") {
            return emptyList()
        }
        val kindHint = metadataString(parsed?.metadata, "kind")?.lowercase(Locale.US).orEmpty()
        val candidates = LinkedHashSet<String>()
        val keys = listOf("output_uri", "uri", "url", "path")
        keys.forEach { key ->
            metadataString(parsed?.metadata, key)?.let { candidates += it }
        }

        val contentPool = buildString {
            append(parsed?.content.orEmpty())
            if (isNotBlank()) append('\n')
            append(fallbackContent)
        }
        extractMediaRefsFromText(contentPool).forEach { candidates += it }

        return candidates
            .mapNotNull { ref ->
                val normalized = normalizeMediaRef(ref) ?: return@mapNotNull null
                val kind = guessMediaKind(normalized, action, kindHint) ?: return@mapNotNull null
                UiMediaAttachment(
                    reference = normalized,
                    kind = kind,
                    label = deriveAttachmentLabel(normalized, kind)
                )
            }
            .take(MAX_MEDIA_ATTACHMENTS_PER_MESSAGE)
    }

    private fun metadataString(metadata: JsonObject?, key: String): String? {
        return (metadata?.get(key) as? JsonPrimitive)
            ?.contentOrNull
            ?.trim()
            ?.takeIf { it.isNotBlank() }
    }

    private fun extractMediaRefsFromText(text: String): List<String> {
        if (text.isBlank()) return emptyList()
        val refs = LinkedHashSet<String>()
        val uriPattern = Regex("""(?i)\b(?:content|file|https?)://[^\s)]+""")
        uriPattern.findAll(text).forEach { refs += it.value }
        val kvPattern = Regex("""(?i)\b(?:output_uri|uri|path)=([^\s]+)""")
        kvPattern.findAll(text).forEach { match ->
            match.groupValues.getOrNull(1)?.let { refs += it }
        }
        return refs.toList()
    }

    private fun normalizeMediaRef(raw: String): String? {
        val trimmed = raw.trim()
            .trim('"', '\'')
            .trimEnd(',', ';', ')', ']', '}')
        return trimmed.takeIf { it.isNotBlank() }
    }

    private fun guessMediaKind(reference: String, action: String, kindHint: String): UiMediaKind? {
        return when {
            action == "capture_photo" -> UiMediaKind.Image
            action == "record_video" -> UiMediaKind.Video
            action == "audio_record" || action == "audio_playback" -> UiMediaKind.Audio
            action == "list_recent" && kindHint == "images" -> UiMediaKind.Image
            action == "list_recent" && kindHint == "videos" -> UiMediaKind.Video
            action == "list_recent" && kindHint == "audio" -> UiMediaKind.Audio
            looksLikeImage(reference) -> UiMediaKind.Image
            looksLikeVideo(reference) -> UiMediaKind.Video
            looksLikeAudio(reference) -> UiMediaKind.Audio
            else -> null
        }
    }

    private fun looksLikeImage(reference: String): Boolean {
        val lower = reference.lowercase(Locale.US)
        if (lower.contains("/images/")) return true
        return listOf(".jpg", ".jpeg", ".png", ".gif", ".webp", ".bmp", ".heic").any { lower.contains(it) }
    }

    private fun looksLikeVideo(reference: String): Boolean {
        val lower = reference.lowercase(Locale.US)
        if (lower.contains("/video/")) return true
        return listOf(".mp4", ".mkv", ".webm", ".mov", ".3gp").any { lower.contains(it) }
    }

    private fun looksLikeAudio(reference: String): Boolean {
        val lower = reference.lowercase(Locale.US)
        if (lower.contains("/audio/")) return true
        return listOf(".m4a", ".aac", ".mp3", ".wav", ".ogg", ".flac").any { lower.contains(it) }
    }

    private fun deriveAttachmentLabel(reference: String, kind: UiMediaKind): String {
        val name = runCatching {
            if (reference.startsWith("http://", true) ||
                reference.startsWith("https://", true) ||
                reference.startsWith("content://", true) ||
                reference.startsWith("file://", true)
            ) {
                reference.substringAfterLast('/').substringBefore('?')
            } else {
                File(reference).name
            }
        }.getOrDefault("")
        val fallback = when (kind) {
            UiMediaKind.Image -> "Image"
            UiMediaKind.Video -> "Video"
            UiMediaKind.Audio -> "Audio"
        }
        return name.takeIf { it.isNotBlank() } ?: fallback
    }

    private fun parseToolCalls(raw: String): List<ToolCall> {
        if (raw.isBlank()) return emptyList()
        return runCatching {
            uiJson.decodeFromString<List<ToolCall>>(raw)
        }.getOrDefault(emptyList())
    }

    private fun parseToolResult(raw: String?): UiStoredToolResult? {
        if (raw.isNullOrBlank()) return null
        return runCatching {
            uiJson.decodeFromString<UiStoredToolResult>(raw)
        }.getOrNull()
    }

    private fun prettyJsonOrRaw(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isBlank()) return "{}"
        return runCatching {
            val parsed = uiJson.parseToJsonElement(trimmed)
            uiJson.encodeToString(parsed)
        }.getOrDefault(trimmed)
    }

    @kotlinx.serialization.Serializable
    private data class UiStoredToolResult(
        val toolCallId: String,
        val content: String,
        val isError: Boolean,
        val metadata: JsonObject? = null
    )

    private data class ToolResultEnvelope(
        val entity: MessageEntity,
        val parsed: UiStoredToolResult?
    )

    private fun startGatewayIfEnabled() {
        val app = getApplication<Application>()
        if (shouldDelegateRemoteGatewayToAlwaysOnService()) {
            AlwaysOnHealthCheckWorker.ensureScheduled(app)
            RuntimeController.stop()
            AlwaysOnModeController.startService(app)
            AlwaysOnModeController.reloadAll()
            return
        }
        AlwaysOnHealthCheckWorker.cancel(app)
        AlwaysOnModeController.stopService(app)
        RuntimeController.start(app)
    }

    private suspend fun deliverMessageToSessionFromTool(
        request: SessionsSendTool.Request
    ): SessionsSendTool.DeliveryResult {
        val target = resolveSessionForToolTarget(
            sessionId = request.sessionId,
            sessionTitle = request.sessionTitle
        ) ?: throw IllegalArgumentException("target session not found")

        messageRepository.appendAssistantMessage(
            sessionId = target.id,
            content = request.content
        )
        sessionRepository.touch(target.id)

        var remoteDelivered = false
        val rawBinding = if (request.deliverRemote) {
            configStore.getSessionChannelBindings()
                .firstOrNull { it.sessionId.trim() == target.id.trim() && it.enabled }
        } else {
            null
        }
        val binding = if (request.deliverRemote) findSessionChannelBinding(target.id) else null
        if (request.deliverRemote && rawBinding != null && binding == null) {
            throw IllegalStateException("target session remote channel is configured but inactive or incomplete")
        }
        if (binding != null) {
            publishGatewayOutbound(
                OutboundMessage(
                    channel = binding.channel,
                    chatId = binding.chatId,
                    content = request.content,
                    metadata = buildAdapterMetadata(adapterKeyForBinding(binding))
                )
            )
            remoteDelivered = true
        }
        val deliveryNote = when {
            request.deliverRemote && rawBinding?.channel?.trim()?.equals("wecom", ignoreCase = true) == true ->
                "WeCom remote delivery is reply-context based. It only works after that WeCom chat has sent a recent inbound message; local context is kept until app restart and up to 7 days."
            else -> null
        }

        return SessionsSendTool.DeliveryResult(
            sessionId = target.id,
            sessionTitle = target.title,
            remoteDelivered = remoteDelivered,
            note = deliveryNote
        )
    }

    private fun buildRuntimeSettingsSnapshot(config: com.palmclaw.config.AppConfig): RuntimeGetTool.Snapshot {
        return RuntimeGetTool.Snapshot(
            maxToolRounds = config.maxToolRounds,
            toolResultMaxChars = config.toolResultMaxChars,
            memoryConsolidationWindow = config.memoryConsolidationWindow,
            llmCallTimeoutSeconds = config.llmCallTimeoutSeconds,
            llmConnectTimeoutSeconds = config.llmConnectTimeoutSeconds,
            llmReadTimeoutSeconds = config.llmReadTimeoutSeconds,
            defaultToolTimeoutSeconds = config.defaultToolTimeoutSeconds,
            contextMessages = config.contextMessages,
            toolArgsPreviewMaxChars = config.toolArgsPreviewMaxChars
        )
    }

    private suspend fun buildHeartbeatSettingsSnapshot(config: HeartbeatConfig): HeartbeatGetTool.Snapshot {
        return HeartbeatGetTool.Snapshot(
            enabled = config.enabled,
            intervalSeconds = config.intervalSeconds,
            documentContent = withContext(Dispatchers.IO) { readHeartbeatDoc() },
            lastTriggeredAtMs = configStore.getHeartbeatLastTriggeredAtMs(),
            nextTriggerAtMs = configStore.getHeartbeatNextTriggerAtMs()
        )
    }

    private suspend fun persistHeartbeatSettings(
        request: HeartbeatSetTool.Request
    ): HeartbeatGetTool.Snapshot {
        val current = configStore.getHeartbeatConfig()
        val intervalSeconds = request.intervalSeconds
            ?.also {
                if (it !in AppLimits.MIN_HEARTBEAT_INTERVAL_SECONDS..AppLimits.MAX_HEARTBEAT_INTERVAL_SECONDS) {
                    throw IllegalArgumentException(
                        "Heartbeat interval seconds must be between ${AppLimits.MIN_HEARTBEAT_INTERVAL_SECONDS} and ${AppLimits.MAX_HEARTBEAT_INTERVAL_SECONDS}"
                    )
                }
            }
            ?: current.intervalSeconds
        val updated = HeartbeatConfig(
            enabled = request.enabled ?: current.enabled,
            intervalSeconds = intervalSeconds
        )
        configStore.saveHeartbeatConfig(updated)
        request.documentContent?.let { content ->
            withContext(Dispatchers.IO) {
                heartbeatDocFile.parentFile?.mkdirs()
                heartbeatDocFile.writeText(content, Charsets.UTF_8)
            }
        }
        reloadAutomationViaActiveRuntime()
        request.nextTriggerAtMs?.let { requested ->
            if (!updated.enabled) {
                throw IllegalStateException("Cannot set next heartbeat trigger while heartbeat is disabled")
            }
            HeartbeatService(getApplication<Application>()).apply {
                updateConfig(enabled = true, intervalSeconds = updated.intervalSeconds)
                armNextAlarm(requested)
            }
        }
        loadSettingsIntoState()
        return buildHeartbeatSettingsSnapshot(updated)
    }

    private suspend fun triggerHeartbeatNowFromTool(): String {
        return triggerHeartbeatViaActiveRuntime()
    }

    private suspend fun persistRuntimeSettings(
        request: RuntimeSetTool.Request
    ): RuntimeGetTool.Snapshot {
        val current = configStore.getConfig()
        val updated = current.copy(
            maxToolRounds = request.maxToolRounds
                ?.let { validateIntSetting("Max tool rounds", it, AppLimits.MIN_MAX_TOOL_ROUNDS, AppLimits.MAX_MAX_TOOL_ROUNDS) }
                ?: current.maxToolRounds,
            toolResultMaxChars = request.toolResultMaxChars
                ?.let { validateIntSetting("Tool result max chars", it, AppLimits.MIN_TOOL_RESULT_MAX_CHARS, AppLimits.MAX_TOOL_RESULT_MAX_CHARS) }
                ?: current.toolResultMaxChars,
            memoryConsolidationWindow = request.memoryConsolidationWindow
                ?.let {
                    validateIntSetting(
                        "Memory consolidation window",
                        it,
                        AppLimits.MIN_MEMORY_CONSOLIDATION_WINDOW,
                        AppLimits.MAX_MEMORY_CONSOLIDATION_WINDOW
                    )
                }
                ?: current.memoryConsolidationWindow,
            llmCallTimeoutSeconds = request.llmCallTimeoutSeconds
                ?.let {
                    validateIntSetting(
                        "LLM call timeout seconds",
                        it,
                        AppLimits.MIN_LLM_CALL_TIMEOUT_SECONDS,
                        AppLimits.MAX_LLM_CALL_TIMEOUT_SECONDS
                    )
                }
                ?: current.llmCallTimeoutSeconds,
            llmConnectTimeoutSeconds = request.llmConnectTimeoutSeconds
                ?.let {
                    validateIntSetting(
                        "LLM connect timeout seconds",
                        it,
                        AppLimits.MIN_LLM_CONNECT_TIMEOUT_SECONDS,
                        AppLimits.MAX_LLM_CONNECT_TIMEOUT_SECONDS
                    )
                }
                ?: current.llmConnectTimeoutSeconds,
            llmReadTimeoutSeconds = request.llmReadTimeoutSeconds
                ?.let {
                    validateIntSetting(
                        "LLM read timeout seconds",
                        it,
                        AppLimits.MIN_LLM_READ_TIMEOUT_SECONDS,
                        AppLimits.MAX_LLM_READ_TIMEOUT_SECONDS
                    )
                }
                ?: current.llmReadTimeoutSeconds,
            defaultToolTimeoutSeconds = request.defaultToolTimeoutSeconds
                ?.let {
                    validateIntSetting(
                        "Default tool timeout seconds",
                        it,
                        AppLimits.MIN_TOOL_TIMEOUT_SECONDS,
                        AppLimits.MAX_TOOL_TIMEOUT_SECONDS
                    )
                }
                ?: current.defaultToolTimeoutSeconds,
            contextMessages = request.contextMessages
                ?.let { validateIntSetting("Context messages", it, AppLimits.MIN_CONTEXT_MESSAGES, AppLimits.MAX_CONTEXT_MESSAGES) }
                ?: current.contextMessages,
            toolArgsPreviewMaxChars = request.toolArgsPreviewMaxChars
                ?.let {
                    validateIntSetting(
                        "Tool args preview max chars",
                        it,
                        AppLimits.MIN_TOOL_ARGS_PREVIEW_MAX_CHARS,
                        AppLimits.MAX_TOOL_ARGS_PREVIEW_MAX_CHARS
                    )
                }
                ?: current.toolArgsPreviewMaxChars
        )
        configStore.saveConfig(updated)
        loadSettingsIntoState()
        return buildRuntimeSettingsSnapshot(updated)
    }

    private fun validateIntSetting(label: String, value: Int, min: Int, max: Int): Int {
        if (value !in min..max) {
            throw IllegalArgumentException("$label must be between $min and $max")
        }
        return value
    }

    private fun shouldDelegateRemoteGatewayToAlwaysOnService(): Boolean {
        return configStore.getAlwaysOnConfig().enabled
    }

    private suspend fun publishGatewayOutbound(outbound: OutboundMessage) {
        if (shouldDelegateRemoteGatewayToAlwaysOnService()) {
            AlwaysOnModeController.publishOutbound(outbound)
            return
        }
        RuntimeController.publishOutbound(getApplication<Application>(), outbound)
    }

    private suspend fun runUserMessageViaActiveRuntime(
        sessionId: String,
        sessionTitle: String,
        text: String
    ) {
        if (shouldDelegateRemoteGatewayToAlwaysOnService()) {
            AlwaysOnModeController.runUserMessage(
                sessionId = sessionId,
                sessionTitle = sessionTitle,
                text = text
            )
            return
        }
        RuntimeController.runUserMessage(
            context = getApplication<Application>(),
            sessionId = sessionId,
            sessionTitle = sessionTitle,
            text = text
        )
    }

    private suspend fun triggerHeartbeatViaActiveRuntime(): String {
        if (shouldDelegateRemoteGatewayToAlwaysOnService()) {
            val result = AlwaysOnModeController.triggerHeartbeatNow()
            _uiState.update { it.copy(settingsInfo = result) }
            return result
        }
        val result = RuntimeController.triggerHeartbeatNow(getApplication<Application>())
        _uiState.update { it.copy(settingsInfo = result) }
        return result
    }

    private fun reloadAutomationViaActiveRuntime() {
        if (shouldDelegateRemoteGatewayToAlwaysOnService()) {
            AlwaysOnModeController.reloadAutomation()
            return
        }
        RuntimeController.reloadAutomation(getApplication<Application>())
    }

    private fun reloadMcpViaActiveRuntime(config: McpHttpConfig) {
        if (shouldDelegateRemoteGatewayToAlwaysOnService()) {
            AlwaysOnModeController.reloadMcp()
            return
        }
        RuntimeController.reloadMcp(getApplication<Application>())
    }

    private fun reloadAllViaActiveRuntime() {
        if (shouldDelegateRemoteGatewayToAlwaysOnService()) {
            AlwaysOnModeController.reloadAll()
            return
        }
        RuntimeController.reloadAll(getApplication<Application>())
    }

    private fun observeRuntimeStatus() {
        viewModelScope.launch {
            RuntimeController.status.collectLatest { status ->
                runtimeProcessingSessions = status.processingSessionIds
                    .asSequence()
                    .map { it.trim() }
                    .filter { it.isNotBlank() }
                    .toSet()
                updateObservedGatewayProcessingSessions()
            }
        }
    }

    private fun observeAlwaysOnStatus() {
        viewModelScope.launch {
            AlwaysOnModeController.status.collectLatest { status ->
                alwaysOnProcessingSessions = status.processingSessionIds
                    .asSequence()
                    .map { it.trim() }
                    .filter { it.isNotBlank() }
                    .toSet()
                _uiState.update {
                    it.copy(
                        alwaysOnServiceRunning = status.serviceRunning,
                        alwaysOnNotificationActive = status.notificationActive,
                        alwaysOnGatewayRunning = status.gatewayRunning,
                        alwaysOnActiveAdapterCount = status.activeAdapterCount,
                        alwaysOnStartedAtMs = status.startedAtMs,
                        alwaysOnLastError = status.lastError
                    )
                }
                updateObservedGatewayProcessingSessions()
            }
        }
    }

    private fun updateObservedGatewayProcessingSessions() {
        var deferredConfig: ChannelsConfig? = null
        synchronized(gatewayProcessingSessions) {
            gatewayProcessingSessions.clear()
            gatewayProcessingSessions.addAll(runtimeProcessingSessions)
            gatewayProcessingSessions.addAll(alwaysOnProcessingSessions)
            if (gatewayProcessingSessions.isEmpty()) {
                deferredConfig = pendingGatewayConfig
                pendingGatewayConfig = null
            }
        }
        if (deferredConfig != null) {
            applyGatewayRuntimeConfig(deferredConfig!!)
        }
        syncGeneratingState()
    }

    private fun buildAdapterMetadata(adapterKey: String?): Map<String, String> {
        val normalized = adapterKey?.trim()?.ifBlank { null } ?: return emptyMap()
        return mapOf(GatewayOrchestrator.KEY_ADAPTER_KEY to normalized)
    }

    private fun buildAdapterKey(channel: String, seed: String): String {
        val normalizedChannel = channel.trim().lowercase(Locale.US)
        val normalizedSeed = seed.trim()
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(normalizedSeed.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }
            .take(16)
        return "$normalizedChannel:$digest"
    }

    private fun buildFeishuAdapterKeys(
        appId: String,
        appSecret: String,
        encryptKey: String,
        verificationToken: String
    ): List<String> {
        return buildFeishuAdapterSeeds(
            appId = appId,
            appSecret = appSecret,
            encryptKey = encryptKey,
            verificationToken = verificationToken
        ).map { buildAdapterKey("feishu", it) }
    }

    private fun buildEmailAdapterKey(config: EmailAccountConfig): String? {
        val imapHost = config.imapHost.trim()
        val imapUsername = config.imapUsername.trim()
        val smtpHost = config.smtpHost.trim()
        val smtpUsername = config.smtpUsername.trim()
        if (
            imapHost.isBlank() ||
            imapUsername.isBlank() ||
            config.imapPassword.isBlank() ||
            smtpHost.isBlank() ||
            smtpUsername.isBlank() ||
            config.smtpPassword.isBlank()
        ) return null
        return buildAdapterKey(
            "email",
            "$imapHost|${config.imapPort}|$imapUsername|$smtpHost|${config.smtpPort}|$smtpUsername|${config.fromAddress.trim()}"
        )
    }

    private fun buildWeComAdapterKey(botId: String, secret: String): String? {
        val normalizedBotId = botId.trim()
        val normalizedSecret = secret.trim()
        if (normalizedBotId.isBlank() || normalizedSecret.isBlank()) return null
        return buildAdapterKey("wecom", "$normalizedBotId|$normalizedSecret")
    }

    private fun adapterKeysForBinding(binding: SessionChannelBinding): List<String> {
        val channel = binding.channel.trim().lowercase(Locale.US)
        return when (channel) {
            "telegram" -> binding.telegramBotToken.trim()
                .takeIf { it.isNotBlank() }
                ?.let { listOf(buildAdapterKey(channel, it)) }
                .orEmpty()
            "discord" -> binding.discordBotToken.trim()
                .takeIf { it.isNotBlank() }
                ?.let { listOf(buildAdapterKey(channel, it)) }
                .orEmpty()
            "slack" -> {
                val botToken = binding.slackBotToken.trim()
                val appToken = binding.slackAppToken.trim()
                if (botToken.isBlank() || appToken.isBlank()) emptyList()
                else listOf(buildAdapterKey(channel, "$botToken|$appToken"))
            }
            "feishu" -> buildFeishuAdapterSeeds(
                appId = binding.feishuAppId,
                appSecret = binding.feishuAppSecret,
                encryptKey = binding.feishuEncryptKey,
                verificationToken = binding.feishuVerificationToken
            ).map { buildAdapterKey(channel, it) }
            "email" -> {
                val imapHost = binding.emailImapHost.trim()
                val imapUsername = binding.emailImapUsername.trim()
                val smtpHost = binding.emailSmtpHost.trim()
                val smtpUsername = binding.emailSmtpUsername.trim()
                if (
                    imapHost.isBlank() ||
                    imapUsername.isBlank() ||
                    binding.emailImapPassword.isBlank() ||
                    smtpHost.isBlank() ||
                    smtpUsername.isBlank() ||
                    binding.emailSmtpPassword.isBlank()
                ) emptyList() else listOf(
                    buildAdapterKey(
                        channel,
                        "$imapHost|${binding.emailImapPort}|$imapUsername|$smtpHost|${binding.emailSmtpPort}|$smtpUsername|${binding.emailFromAddress.trim()}"
                    )
                )
            }
            "wecom" -> {
                val botId = binding.wecomBotId.trim()
                val secret = binding.wecomSecret.trim()
                if (botId.isBlank() || secret.isBlank()) emptyList()
                else listOf(buildAdapterKey(channel, "$botId|$secret"))
            }
            else -> emptyList()
        }
    }

    private fun adapterKeyForBinding(binding: SessionChannelBinding): String? {
        return adapterKeysForBinding(binding).firstOrNull()
    }

    private suspend fun resolveSessionForToolTarget(
        sessionId: String?,
        sessionTitle: String?
    ): SessionTarget? {
        val sessions = sessionRepository.listSessions()
            .map { SessionTarget(id = it.id, title = it.title) }
        val requestedId = sessionId?.trim().orEmpty()
        if (requestedId.isNotBlank()) {
            return sessions.firstOrNull { it.id.equals(requestedId, ignoreCase = true) }
        }

        val requestedTitle = sessionTitle?.trim().orEmpty()
        if (requestedTitle.isBlank()) return null
        val exactMatches = sessions.filter { it.title.equals(requestedTitle, ignoreCase = true) }
        if (exactMatches.size > 1) {
            throw IllegalArgumentException("session_title matches multiple sessions; use session_id")
        }
        exactMatches.singleOrNull()?.let { return it }
        val partialMatches = sessions.filter { it.title.contains(requestedTitle, ignoreCase = true) }
        return when {
            partialMatches.isEmpty() -> null
            partialMatches.size == 1 -> partialMatches.first()
            else -> throw IllegalArgumentException("session_title is ambiguous; use session_id")
        }
    }

    private suspend fun buildSessionsSnapshotForTool(): SessionsListTool.Snapshot {
        val bindingsBySession = configStore.getSessionChannelBindings()
            .associateBy { it.sessionId.trim() }
        val rawSessions = sessionRepository.listSessions().toMutableList()
        if (rawSessions.none { it.id == AppSession.LOCAL_SESSION_ID }) {
            rawSessions += SessionEntity(
                id = AppSession.LOCAL_SESSION_ID,
                title = AppSession.LOCAL_SESSION_TITLE,
                createdAt = 0L,
                updatedAt = 0L
            )
        }
        val ordered = rawSessions.sortedWith(
            compareBy<SessionEntity> { it.id != AppSession.LOCAL_SESSION_ID }
                .thenByDescending { it.updatedAt }
                .thenBy { it.createdAt }
        )
        val activeId = currentSessionId.trim().ifBlank { AppSession.LOCAL_SESSION_ID }
        val entries = ordered.map { session ->
            val binding = bindingsBySession[session.id]
            val boundChannel = binding?.channel?.trim().orEmpty()
            val boundTarget = binding?.chatId?.trim().orEmpty()
            val channelEnabled = binding?.enabled ?: true
            val isCurrent = session.id == activeId
            val status = when {
                isCurrent -> "current"
                !channelEnabled -> "off"
                else -> "active"
            }
            SessionsListTool.Entry(
                sessionId = session.id,
                title = session.title,
                status = status,
                isCurrent = isCurrent,
                isLocal = session.id == AppSession.LOCAL_SESSION_ID,
                channelEnabled = channelEnabled,
                boundChannel = boundChannel,
                boundTarget = boundTarget
            )
        }
        return SessionsListTool.Snapshot(
            currentSessionId = activeId,
            sessions = entries
        )
    }

    private suspend fun buildChannelBindingsSnapshotForTool(): ChannelsGetTool.Snapshot {
        val gatewayEnabled = configStore.getChannelsConfig().enabled
        val bindingsBySession = configStore.getSessionChannelBindings()
            .associateBy { it.sessionId.trim() }
        val sessions = sessionRepository.listSessions().toMutableList()
        if (sessions.none { it.id == AppSession.LOCAL_SESSION_ID }) {
            sessions += SessionEntity(
                id = AppSession.LOCAL_SESSION_ID,
                title = AppSession.LOCAL_SESSION_TITLE,
                createdAt = 0L,
                updatedAt = 0L
            )
        }
        val entries = sessions
            .sortedWith(
                compareBy<SessionEntity> { it.id != AppSession.LOCAL_SESSION_ID }
                    .thenByDescending { it.updatedAt }
                    .thenBy { it.createdAt }
            )
            .map { session ->
                val binding = bindingsBySession[session.id]
                val channel = binding?.channel?.trim()?.lowercase(Locale.US).orEmpty()
                val target = normalizedBindingTarget(binding)
                val status = resolveBindingRuntimeStatus(binding, gatewayEnabled)
                ChannelsGetTool.Entry(
                    sessionId = session.id,
                    title = session.title,
                    bindingEnabled = binding?.enabled ?: false,
                    channel = channel,
                    target = target,
                    status = status
                )
            }
        return ChannelsGetTool.Snapshot(
            gatewayEnabled = gatewayEnabled,
            sessions = entries
        )
    }

    private suspend fun buildMcpStatusSnapshot(): McpStatusTool.Snapshot {
        val config = configStore.getMcpHttpConfig()
        val servers = config.servers.ifEmpty {
            if (config.serverUrl.isNotBlank()) {
                listOf(
                    McpHttpServerConfig(
                        id = "mcp_1",
                        serverName = config.serverName,
                        serverUrl = config.serverUrl,
                        authToken = config.authToken,
                        toolTimeoutSeconds = config.toolTimeoutSeconds
                    )
                )
            } else {
                emptyList()
            }
        }
        val entries = servers.map { server ->
            val normalizedName = normalizeMcpRuntimeServerName(server.serverName)
            val status = mcpServerStatuses[normalizedName] ?: if (config.enabled) {
                UiMcpServerRuntimeStatus(status = "Not connected")
            } else {
                UiMcpServerRuntimeStatus(status = "Disabled")
            }
            McpStatusTool.Entry(
                id = server.id.ifBlank { normalizedName.ifBlank { "mcp" } },
                serverName = server.serverName,
                serverUrl = server.serverUrl,
                status = status.status,
                usable = status.usable,
                detail = status.detail,
                toolCount = status.toolCount,
                toolNames = status.toolNames
            )
        }
        return McpStatusTool.Snapshot(
            enabled = config.enabled,
            connectedServerCount = entries.count { it.status.equals("Connected", ignoreCase = true) },
            registeredToolCount = entries.sumOf { it.toolCount },
            servers = entries
        )
    }

    private fun normalizeMcpRuntimeServerName(input: String): String {
        return input.trim().lowercase(Locale.US)
            .replace(Regex("[^a-z0-9_\\-]+"), "_")
            .trim('_')
            .take(40)
            .ifBlank { AppLimits.DEFAULT_MCP_HTTP_SERVER_NAME }
    }

    private fun buildUiMcpServerConfigs(config: McpHttpConfig): List<UiMcpServerConfig> {
        val servers = config.servers.ifEmpty {
            if (config.serverUrl.isNotBlank()) {
                listOf(
                    McpHttpServerConfig(
                        id = "mcp_1",
                        serverName = config.serverName,
                        serverUrl = config.serverUrl,
                        authToken = config.authToken,
                        toolTimeoutSeconds = config.toolTimeoutSeconds
                    )
                )
            } else {
                emptyList()
            }
        }
        return servers.map { server ->
            val runtimeName = normalizeMcpRuntimeServerName(server.serverName)
            val status = mcpServerStatuses[runtimeName] ?: if (config.enabled) {
                UiMcpServerRuntimeStatus(status = "Not connected")
            } else {
                UiMcpServerRuntimeStatus(status = "Disabled")
            }
            UiMcpServerConfig(
                id = server.id.ifBlank { "mcp_${server.serverName}_${server.serverUrl.hashCode()}" },
                serverName = server.serverName,
                serverUrl = server.serverUrl,
                authToken = server.authToken,
                toolTimeoutSeconds = server.toolTimeoutSeconds.toString(),
                status = status.status,
                usable = status.usable,
                detail = status.detail,
                toolCount = status.toolCount
            )
        }
    }

    private fun buildUiProviderConfigs(config: com.palmclaw.config.AppConfig): List<UiProviderConfig> {
        val activeId = config.activeProviderConfigId.trim()
        val mapped = config.providerConfigs.map { item ->
            val resolvedProvider = ProviderCatalog.resolve(item.providerName)
            val resolvedProtocol = ProviderCatalog.resolveProtocol(
                rawProvider = resolvedProvider.id,
                requested = item.providerProtocol,
                baseUrl = item.baseUrl
            )
            UiProviderConfig(
                id = item.id.trim().ifBlank {
                    "provider_${resolvedProvider.id}_${item.model.hashCode()}"
                },
                providerName = resolvedProvider.id,
                customName = item.customName,
                providerProtocol = resolvedProtocol,
                apiKey = item.apiKey,
                model = item.model.ifBlank {
                    ProviderCatalog.defaultModel(resolvedProvider.id, resolvedProtocol)
                },
                baseUrl = item.baseUrl.ifBlank {
                    ProviderCatalog.defaultBaseUrl(resolvedProvider.id, resolvedProtocol)
                },
                enabled = item.id.trim() == activeId
            )
        }
        return normalizeActiveProviderConfigs(mapped)
    }

    private fun refreshMcpServersInState(config: McpHttpConfig = configStore.getMcpHttpConfig()) {
        val uiServers = buildUiMcpServerConfigs(config)
        _uiState.update { state ->
            val first = uiServers.firstOrNull()
            state.copy(
                settingsMcpEnabled = config.enabled,
                settingsMcpServerName = first?.serverName ?: AppLimits.DEFAULT_MCP_HTTP_SERVER_NAME,
                settingsMcpServerUrl = first?.serverUrl.orEmpty(),
                settingsMcpAuthToken = first?.authToken.orEmpty(),
                settingsMcpToolTimeoutSeconds = first?.toolTimeoutSeconds
                    ?: AppLimits.DEFAULT_MCP_HTTP_TOOL_TIMEOUT_SECONDS.toString(),
                settingsMcpServers = uiServers
            )
        }
    }

    private suspend fun setSessionChannelEnabledInternal(
        sessionId: String?,
        sessionTitle: String?,
        enabled: Boolean
    ): ChannelsSetTool.Result {
        val target = resolveSessionForToolTarget(
            sessionId = sessionId,
            sessionTitle = sessionTitle
        ) ?: throw IllegalArgumentException("target session not found")
        val binding = configStore.getSessionChannelBindings()
            .firstOrNull { it.sessionId.trim() == target.id.trim() }
            ?: throw IllegalArgumentException("target session has no channel binding")
        if (binding.channel.trim().isBlank()) {
            throw IllegalArgumentException("target session has no configured channel binding")
        }
        configStore.saveSessionChannelBinding(binding.copy(enabled = enabled))
        val current = configStore.getChannelsConfig()
        val shouldEnableGateway = hasActiveGatewayBinding(configStore.getSessionChannelBindings())
        val runtimeConfig = if (current.enabled == shouldEnableGateway) {
            current
        } else {
            current.copy(enabled = shouldEnableGateway).also { cfg ->
                configStore.saveChannelsConfig(cfg)
            }
        }
        refreshSessionBindingsInState()
        requestGatewayRuntimeConfig(runtimeConfig)
        _uiState.update { it.copy(settingsGatewayEnabled = runtimeConfig.enabled) }
        val status = buildConnectedChannelsOverview(_uiState.value.sessions)
            .firstOrNull { it.sessionId == target.id }
            ?.status
            ?: if (enabled) "Configured" else "Disabled"
        return ChannelsSetTool.Result(
            sessionId = target.id,
            sessionTitle = target.title,
            enabled = enabled,
            status = status
        )
    }

    private data class SessionTarget(
        val id: String,
        val title: String
    )

    private fun findSessionChannelBinding(sessionId: String): SessionChannelBinding? {
        val sid = sessionId.trim()
        if (sid.isBlank()) return null
        val raw = configStore.getSessionChannelBindings()
            .firstOrNull { it.sessionId.trim() == sid }
            ?: return null
        if (!raw.enabled) return null
        val channel = raw.channel.trim().lowercase(Locale.US)
        val chatId = raw.chatId.trim()
        if (channel.isBlank() || chatId.isBlank()) return null
        return when (channel) {
            "telegram" -> {
                val token = raw.telegramBotToken.trim()
                if (token.isBlank()) return null
                raw.copy(
                    channel = channel,
                    chatId = chatId,
                    telegramBotToken = token,
                    telegramAllowedChatId = raw.telegramAllowedChatId?.trim()?.ifBlank { null }
                )
            }
            "discord" -> {
                val token = raw.discordBotToken.trim()
                if (token.isBlank()) return null
                raw.copy(
                    channel = channel,
                    chatId = chatId,
                    discordBotToken = token,
                    discordResponseMode = normalizeDiscordResponseMode(raw.discordResponseMode),
                    discordAllowedUserIds = raw.discordAllowedUserIds
                        .map { it.trim() }
                        .filter { it.isNotBlank() }
                )
            }
            "slack" -> {
                val botToken = raw.slackBotToken.trim()
                val appToken = raw.slackAppToken.trim()
                val normalizedChatId = normalizeSlackChannelId(chatId)
                if (botToken.isBlank() || appToken.isBlank() || !isSlackChannelId(normalizedChatId)) return null
                raw.copy(
                    channel = channel,
                    chatId = normalizedChatId,
                    slackBotToken = botToken,
                    slackAppToken = appToken,
                    slackResponseMode = normalizeSlackResponseMode(raw.slackResponseMode),
                    slackAllowedUserIds = raw.slackAllowedUserIds
                        .map { it.trim() }
                        .filter { it.isNotBlank() }
                )
            }
            "feishu" -> {
                val appId = raw.feishuAppId.trim()
                val appSecret = raw.feishuAppSecret.trim()
                val normalizedChatId = normalizeFeishuTargetId(chatId)
                if (appId.isBlank() || appSecret.isBlank() || normalizedChatId.isBlank()) return null
                raw.copy(
                    channel = channel,
                    chatId = normalizedChatId,
                    feishuAppId = appId,
                    feishuAppSecret = appSecret,
                    feishuEncryptKey = raw.feishuEncryptKey.trim(),
                    feishuVerificationToken = raw.feishuVerificationToken.trim(),
                    feishuResponseMode = normalizeFeishuResponseMode(raw.feishuResponseMode),
                    feishuAllowedOpenIds = raw.feishuAllowedOpenIds
                        .map { it.trim() }
                        .filter { it.isNotBlank() }
                )
            }
            "email" -> {
                val normalizedChatId = normalizeEmailAddress(chatId)
                if (!raw.emailConsentGranted) return null
                val imapHost = raw.emailImapHost.trim()
                val imapUsername = raw.emailImapUsername.trim()
                val imapPassword = raw.emailImapPassword
                val smtpHost = raw.emailSmtpHost.trim()
                val smtpUsername = raw.emailSmtpUsername.trim()
                val smtpPassword = raw.emailSmtpPassword
                val fromAddress = normalizeEmailAddress(raw.emailFromAddress)
                if (
                    imapHost.isBlank() ||
                    imapUsername.isBlank() ||
                    imapPassword.isBlank() ||
                    smtpHost.isBlank() ||
                    smtpUsername.isBlank() ||
                    smtpPassword.isBlank() ||
                    !isEmailAddress(fromAddress)
                ) return null
                if (normalizedChatId.isNotBlank() && !isEmailAddress(normalizedChatId)) return null
                raw.copy(
                    channel = channel,
                    chatId = normalizedChatId,
                    emailConsentGranted = true,
                    emailImapHost = imapHost,
                    emailImapPort = raw.emailImapPort.coerceIn(1, 65535),
                    emailImapUsername = imapUsername,
                    emailImapPassword = imapPassword,
                    emailSmtpHost = smtpHost,
                    emailSmtpPort = raw.emailSmtpPort.coerceIn(1, 65535),
                    emailSmtpUsername = smtpUsername,
                    emailSmtpPassword = smtpPassword,
                    emailFromAddress = fromAddress
                )
            }
            "wecom" -> {
                val botId = raw.wecomBotId.trim()
                val secret = raw.wecomSecret.trim()
                val normalizedChatId = normalizeWeComTargetId(chatId)
                if (botId.isBlank() || secret.isBlank()) return null
                raw.copy(
                    channel = channel,
                    chatId = normalizedChatId,
                    wecomBotId = botId,
                    wecomSecret = secret,
                    wecomAllowedUserIds = raw.wecomAllowedUserIds
                        .map { it.trim() }
                        .filter { it.isNotBlank() }
                )
            }
            else -> null
        }
    }

    private fun normalizedChannelForInfo(configStore: ConfigStore, sessionId: String): String {
        return configStore.getSessionChannelBindings()
            .firstOrNull { it.sessionId.trim() == sessionId.trim() }
            ?.channel
            ?.trim()
            ?.lowercase(Locale.US)
            .orEmpty()
    }

    private fun normalizedTargetForInfo(configStore: ConfigStore, sessionId: String): String {
        return configStore.getSessionChannelBindings()
            .firstOrNull { it.sessionId.trim() == sessionId.trim() }
            ?.chatId
            .orEmpty()
            .trim()
    }

    private fun infoChannelLabel(channel: String, useChinese: Boolean): String {
        return when (channel.trim().lowercase(Locale.US)) {
            "telegram" -> "Telegram"
            "discord" -> "Discord"
            "slack" -> "Slack"
            "feishu" -> if (useChinese) "飞书" else "Feishu"
            "email" -> if (useChinese) "邮箱" else "Email"
            "wecom" -> if (useChinese) "企业微信" else "WeCom"
            else -> if (useChinese) "渠道" else "channel"
        }
    }

    private fun normalizedTargetMissingForInfo(configStore: ConfigStore, sessionId: String): Boolean {
        return configStore.getSessionChannelBindings()
            .firstOrNull { it.sessionId.trim() == sessionId.trim() }
            ?.chatId
            ?.trim()
            .isNullOrBlank()
    }

    private fun onGatewaySessionProcessingChanged(sessionId: String, processing: Boolean) {
        val sid = sessionId.trim()
        if (sid.isBlank()) return
        var deferredConfig: ChannelsConfig? = null
        synchronized(gatewayProcessingSessions) {
            if (processing) {
                gatewayProcessingSessions.add(sid)
            } else {
                gatewayProcessingSessions.remove(sid)
                if (gatewayProcessingSessions.isEmpty()) {
                    deferredConfig = pendingGatewayConfig
                    pendingGatewayConfig = null
                }
            }
            Unit
        }
        if (deferredConfig != null) {
            applyGatewayRuntimeConfig(deferredConfig!!)
        }
        syncGeneratingState()
    }

    private fun requestGatewayRuntimeConfig(config: ChannelsConfig) {
        val shouldDefer = synchronized(gatewayProcessingSessions) {
            if (gatewayProcessingSessions.isEmpty()) {
                pendingGatewayConfig = null
                false
            } else {
                pendingGatewayConfig = config
                true
            }
        }
        if (!shouldDefer) {
            applyGatewayRuntimeConfig(config)
        }
    }

    private fun computeIsGeneratingForSession(sessionId: String): Boolean {
        val sid = sessionId.trim().ifBlank { AppSession.LOCAL_SESSION_ID }
        if (generatingJob != null) return true
        return synchronized(gatewayProcessingSessions) {
            gatewayProcessingSessions.contains(sid)
        }
    }

    private fun syncGeneratingState() {
        val activeSessionId = currentSessionId.trim().ifBlank { AppSession.LOCAL_SESSION_ID }
        val busy = computeIsGeneratingForSession(activeSessionId)
        _uiState.update { state ->
            if (state.isGenerating == busy) state else state.copy(isGenerating = busy)
        }
    }

    private fun hasActiveGatewayBinding(bindings: List<SessionChannelBinding>): Boolean {
        return bindings.any { raw ->
            if (!raw.enabled) return@any false
            val channel = raw.channel.trim().lowercase(Locale.US)
            val chatId = raw.chatId.trim()
            if (channel.isBlank()) return@any false
            when (channel) {
                "telegram" -> raw.telegramBotToken.trim().isNotBlank() && chatId.isNotBlank()
                "discord" -> raw.discordBotToken.trim().isNotBlank() && isDiscordSnowflake(chatId)
                "slack" -> {
                    raw.slackBotToken.trim().isNotBlank() &&
                        raw.slackAppToken.trim().isNotBlank() &&
                        isSlackChannelId(normalizeSlackChannelId(chatId))
                }
                "feishu" -> raw.feishuAppId.trim().isNotBlank() && raw.feishuAppSecret.trim().isNotBlank()
                "email" -> {
                    raw.emailConsentGranted &&
                        raw.emailImapHost.trim().isNotBlank() &&
                        raw.emailImapUsername.trim().isNotBlank() &&
                        raw.emailImapPassword.isNotBlank() &&
                        raw.emailSmtpHost.trim().isNotBlank() &&
                        raw.emailSmtpUsername.trim().isNotBlank() &&
                        raw.emailSmtpPassword.isNotBlank()
                }
                "wecom" -> raw.wecomBotId.trim().isNotBlank() && raw.wecomSecret.trim().isNotBlank()
                else -> false
            }
        }
    }

    private fun resolveGatewaySessionBinding(message: InboundMessage): String? {
        val c = message.channel.trim().lowercase(Locale.US)
        val targetIds = when (c) {
            "discord" -> listOf(normalizeDiscordChannelId(message.chatId))
            "slack" -> listOf(normalizeSlackChannelId(message.chatId))
            "feishu" -> buildFeishuTargetAliases(
                primaryTargetId = message.chatId,
                sourceChatId = message.metadata["source_chat_id"].orEmpty(),
                senderOpenId = message.metadata["sender_open_id"].orEmpty()
            )
            "email" -> listOf(normalizeEmailAddress(message.chatId))
            "wecom" -> listOf(normalizeWeComTargetId(message.chatId))
            else -> listOf(message.chatId.trim())
        }
            .filter { it.isNotBlank() }
        if (c.isBlank() || targetIds.isEmpty()) return null
        val adapterKey = message.metadata[GatewayOrchestrator.KEY_ADAPTER_KEY]
            ?.trim()
            ?.ifBlank { null }
        val bindings = configStore.getSessionChannelBindings()
        val exact = bindings.firstOrNull {
            val channelMatches = it.enabled && it.channel.trim().lowercase(Locale.US) == c
            if (!channelMatches) return@firstOrNull false
            if (it.chatId.trim() !in targetIds) return@firstOrNull false
            if (adapterKey == null) return@firstOrNull false
            adapterKeysForBinding(it).contains(adapterKey)
        }
        if (exact != null) {
            return exact.sessionId.trim().ifBlank { null }
        }
        val fallback = bindings.firstOrNull {
            val channelMatches = it.enabled && it.channel.trim().lowercase(Locale.US) == c
            channelMatches && it.chatId.trim() in targetIds
        }
        return fallback?.sessionId?.trim()?.ifBlank { null }
    }

    private fun buildConnectedChannelsOverview(sessions: List<UiSessionSummary>): List<UiConnectedChannelSummary> {
        val gatewayEnabled = configStore.getChannelsConfig().enabled
        val bindingsBySession = configStore.getSessionChannelBindings()
            .associateBy { it.sessionId.trim() }
        return sessions
            .asSequence()
            .filterNot { it.isLocal }
            .mapNotNull { session ->
                val binding = bindingsBySession[session.id] ?: return@mapNotNull null
                val channel = binding.channel.trim().lowercase(Locale.US)
                if (channel !in setOf("telegram", "discord", "slack", "feishu", "email", "wecom")) {
                    return@mapNotNull null
                }
                UiConnectedChannelSummary(
                    sessionId = session.id,
                    sessionTitle = session.title,
                    channel = channel,
                    chatId = normalizedBindingTarget(binding),
                    enabled = binding.enabled,
                    status = resolveBindingRuntimeStatus(binding, gatewayEnabled)
                )
            }
            .sortedWith(
                compareBy<UiConnectedChannelSummary>(
                    { it.channel },
                    { it.sessionTitle.lowercase(Locale.US) }
                )
            )
            .toList()
    }

    private fun resolveBindingRuntimeStatus(
        binding: SessionChannelBinding?,
        gatewayEnabled: Boolean
    ): String {
        if (binding == null) return "Unbound"
        val channel = binding.channel.trim().lowercase(Locale.US)
        if (channel.isBlank()) return "Unbound"
        if (!binding.enabled) return "Disabled"
        val target = normalizedBindingTarget(binding)
        when (channel) {
            "telegram" -> {
                if (binding.telegramBotToken.trim().isBlank()) return "Missing token"
                if (target.isBlank()) return "Waiting for chat detection"
            }
            "discord" -> {
                if (binding.discordBotToken.trim().isBlank()) return "Missing token"
                if (!isDiscordSnowflake(normalizeDiscordChannelId(target))) return "Missing channel id"
            }
            "slack" -> {
                if (binding.slackBotToken.trim().isBlank() || binding.slackAppToken.trim().isBlank()) {
                    return "Missing bot/app token"
                }
                if (!isSlackChannelId(normalizeSlackChannelId(target))) return "Missing channel id"
            }
            "feishu" -> {
                if (binding.feishuAppId.trim().isBlank() || binding.feishuAppSecret.trim().isBlank()) {
                    return "Missing app credentials"
                }
                if (target.isBlank()) return "Waiting for chat detection"
                if (!isFeishuTargetId(normalizeFeishuTargetId(target))) return "Invalid target"
            }
            "email" -> {
                if (!binding.emailConsentGranted) return "Consent required"
                if (
                    binding.emailImapHost.trim().isBlank() ||
                    binding.emailImapUsername.trim().isBlank() ||
                    binding.emailImapPassword.isBlank() ||
                    binding.emailSmtpHost.trim().isBlank() ||
                    binding.emailSmtpUsername.trim().isBlank() ||
                    binding.emailSmtpPassword.isBlank()
                ) return "Missing mailbox credentials"
                if (target.isBlank()) return "Waiting for sender detection"
                if (!isEmailAddress(normalizeEmailAddress(target))) return "Invalid sender"
            }
            "wecom" -> {
                if (binding.wecomBotId.trim().isBlank() || binding.wecomSecret.trim().isBlank()) {
                    return "Missing bot credentials"
                }
                if (target.isBlank()) return "Waiting for chat detection"
            }
            else -> return "Configured"
        }
        if (!gatewayEnabled) return "Gateway idle"
        val snapshot = adapterKeysForBinding(binding)
            .asSequence()
            .map { ChannelRuntimeDiagnostics.getSnapshot(channel, it) }
            .firstOrNull {
                it.running ||
                    it.connected ||
                    it.ready ||
                    it.lastError.isNotBlank()
            }
            ?: adapterKeyForBinding(binding)?.let { ChannelRuntimeDiagnostics.getSnapshot(channel, it) }
            ?: return "Configured"
        return when {
            snapshot.lastError.isNotBlank() && !snapshot.ready -> "Error"
            snapshot.ready -> "Connected"
            snapshot.connected -> "Connecting"
            snapshot.running -> "Starting"
            else -> "Configured"
        }
    }

    private fun normalizedBindingTarget(binding: SessionChannelBinding?): String {
        if (binding == null) return ""
        return when (binding.channel.trim().lowercase(Locale.US)) {
            "discord" -> normalizeDiscordChannelId(binding.chatId)
            "slack" -> normalizeSlackChannelId(binding.chatId)
            "feishu" -> normalizeFeishuTargetId(binding.chatId)
            "email" -> normalizeEmailAddress(binding.chatId)
            "wecom" -> normalizeWeComTargetId(binding.chatId)
            else -> binding.chatId.trim()
        }
    }

    private fun fetchTelegramChatCandidates(botToken: String): List<UiTelegramChatCandidate> {
        val url = "https://api.telegram.org/bot$botToken/getUpdates?timeout=1&limit=100"
        val request = Request.Builder()
            .url(url)
            .get()
            .build()
        telegramDiscoveryClient.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IllegalStateException("HTTP ${response.code}: ${body.take(300)}")
            }
            val root = JSONObject(body)
            if (!root.optBoolean("ok", false)) {
                val desc = root.optString("description").ifBlank { "Telegram API error" }
                throw IllegalStateException(desc)
            }
            val result = root.optJSONArray("result") ?: return emptyList()
            val byChat = LinkedHashSet<String>()
            val candidates = mutableListOf<UiTelegramChatCandidate>()
            for (i in 0 until result.length()) {
                val update = result.optJSONObject(i) ?: continue
                val messageLike = update.optJSONObject("message")
                    ?: update.optJSONObject("edited_message")
                    ?: update.optJSONObject("channel_post")
                    ?: update.optJSONObject("edited_channel_post")
                    ?: update.optJSONObject("my_chat_member")
                    ?: update.optJSONObject("chat_member")
                    ?: update.optJSONObject("chat_join_request")
                    ?: update.optJSONObject("callback_query")?.optJSONObject("message")
                    ?: continue
                val chat = messageLike.optJSONObject("chat") ?: continue
                val chatId = chat.optLong("id").takeIf { it != 0L }?.toString().orEmpty()
                if (chatId.isBlank()) continue
                if (!byChat.add(chatId)) continue
                val chatType = chat.optString("type").ifBlank { "unknown" }
                val title = buildTelegramChatTitle(chat, chatType)
                candidates += UiTelegramChatCandidate(
                    chatId = chatId,
                    title = title,
                    kind = chatType
                )
            }
            return candidates
        }
    }

    private fun buildTelegramChatTitle(chat: JSONObject, chatType: String): String {
        return when (chatType.lowercase(Locale.US)) {
            "private" -> {
                val first = chat.optString("first_name").trim()
                val last = chat.optString("last_name").trim()
                val username = chat.optString("username").trim()
                val name = listOf(first, last).filter { it.isNotBlank() }.joinToString(" ").trim()
                when {
                    name.isNotBlank() && username.isNotBlank() -> "$name (@$username)"
                    name.isNotBlank() -> name
                    username.isNotBlank() -> "@$username"
                    else -> "Private chat"
                }
            }
            "group", "supergroup", "channel" -> {
                chat.optString("title").trim().ifBlank { "Untitled $chatType" }
            }
            else -> {
                chat.optString("title").trim().ifBlank {
                    chat.optString("username").trim().ifBlank { "Chat" }
                }
            }
        }
    }

    private fun loadSettingsIntoState() {
        val cfg = configStore.getConfig()
        val cronCfg = configStore.getCronConfig()
        val heartbeatCfg = configStore.getHeartbeatConfig()
        val channelsCfg = configStore.getChannelsConfig()
        val alwaysOnCfg = configStore.getAlwaysOnConfig()
        val uiPrefsCfg = configStore.getUiPreferencesConfig()
        val onboardingCfg = onboardingCoordinator.resolveSyncedOnboardingConfig()
        val mcpCfg = configStore.getMcpHttpConfig()
        val mcpServers = buildUiMcpServerConfigs(mcpCfg)
        val cronLogs = cronLogStore.readRecent()
        val agentLogs = agentLogStore.readRecent()
        val tokenStats = configStore.getTokenUsageStats()
        val providerConfigs = buildUiProviderConfigs(cfg)
        _uiState.update {
            val resolvedProvider = ProviderCatalog.resolve(cfg.providerName)
            val resolvedProtocol = ProviderCatalog.resolveProtocol(
                rawProvider = resolvedProvider.id,
                requested = cfg.providerProtocol,
                baseUrl = cfg.baseUrl
            )
            val selectedProviderConfig = providerConfigs.firstOrNull { item ->
                item.id == cfg.activeProviderConfigId
            } ?: providerConfigs.firstOrNull()
            val connectedChannels = buildConnectedChannelsOverview(it.sessions)
            val discordGatewayStatus = buildDiscordGatewayStatusText()
            val slackGatewayStatus = buildSlackGatewayStatusText()
            val feishuGatewayStatus = buildFeishuGatewayStatusText()
            val emailGatewayStatus = buildEmailGatewayStatusText()
            val wecomGatewayStatus = buildWeComGatewayStatusText()
            it.copy(
                settingsProviderConfigs = providerConfigs,
                settingsEditingProviderConfigId = selectedProviderConfig?.id.orEmpty(),
                settingsProvider = selectedProviderConfig?.providerName ?: resolvedProvider.id,
                settingsProviderCustomName = selectedProviderConfig?.customName.orEmpty(),
                settingsProviderProtocol = selectedProviderConfig?.providerProtocol ?: resolvedProtocol,
                settingsModel = selectedProviderConfig?.model
                    ?: cfg.model.ifBlank {
                        ProviderCatalog.defaultModel(resolvedProvider.id, resolvedProtocol)
                    },
                settingsApiKey = selectedProviderConfig?.apiKey ?: cfg.apiKey,
                settingsBaseUrl = selectedProviderConfig?.let { config ->
                    config.baseUrl.ifBlank {
                        ProviderCatalog.defaultBaseUrl(config.providerName, config.providerProtocol)
                    }
                } ?: cfg.baseUrl.ifBlank {
                    ProviderCatalog.defaultBaseUrl(resolvedProvider.id, resolvedProtocol)
                },
                settingsMaxToolRounds = cfg.maxToolRounds.toString(),
                settingsToolResultMaxChars = cfg.toolResultMaxChars.toString(),
                settingsMemoryConsolidationWindow = cfg.memoryConsolidationWindow.toString(),
                settingsLlmCallTimeoutSeconds = cfg.llmCallTimeoutSeconds.toString(),
                settingsLlmConnectTimeoutSeconds = cfg.llmConnectTimeoutSeconds.toString(),
                settingsLlmReadTimeoutSeconds = cfg.llmReadTimeoutSeconds.toString(),
                settingsDefaultToolTimeoutSeconds = cfg.defaultToolTimeoutSeconds.toString(),
                settingsContextMessages = cfg.contextMessages.toString(),
                settingsToolArgsPreviewMaxChars = cfg.toolArgsPreviewMaxChars.toString(),
                settingsCronEnabled = cronCfg.enabled,
                settingsCronMinEveryMs = cronCfg.minEveryMs.toString(),
                settingsCronMaxJobs = cronCfg.maxJobs.toString(),
                settingsTokenInput = tokenStats.inputTokens,
                settingsTokenOutput = tokenStats.outputTokens,
                settingsTokenTotal = tokenStats.totalTokens,
                settingsTokenCachedInput = tokenStats.cachedInputTokens,
                settingsTokenRequests = tokenStats.requests,
                settingsCronLogs = cronLogs,
                settingsAgentLogs = agentLogs,
                settingsHeartbeatEnabled = heartbeatCfg.enabled,
                settingsHeartbeatIntervalSeconds = heartbeatCfg.intervalSeconds.toString(),
                settingsGatewayEnabled = channelsCfg.enabled,
                settingsUseChinese = uiPrefsCfg.useChinese,
                settingsDarkTheme = uiPrefsCfg.darkTheme,
                onboardingCompleted = onboardingCfg.completed,
                userDisplayName = onboardingCfg.userDisplayName,
                agentDisplayName = onboardingCfg.agentDisplayName,
                onboardingUserDisplayName = onboardingCfg.userDisplayName,
                onboardingAgentDisplayName = onboardingCfg.agentDisplayName,
                alwaysOnEnabled = alwaysOnCfg.enabled,
                alwaysOnKeepScreenAwake = alwaysOnCfg.keepScreenAwake,
                settingsTelegramBotToken = channelsCfg.telegramBotToken,
                settingsTelegramAllowedChatId = channelsCfg.telegramAllowedChatId.orEmpty(),
                settingsDiscordWebhookUrl = channelsCfg.discordWebhookUrl,
                settingsConnectedChannels = connectedChannels,
                settingsDiscordGatewayStatus = discordGatewayStatus,
                settingsSlackGatewayStatus = slackGatewayStatus,
                settingsFeishuGatewayStatus = feishuGatewayStatus,
                settingsEmailGatewayStatus = emailGatewayStatus,
                settingsWeComGatewayStatus = wecomGatewayStatus,
                settingsMcpEnabled = mcpCfg.enabled,
                settingsMcpServerName = mcpServers.firstOrNull()?.serverName
                    ?: AppLimits.DEFAULT_MCP_HTTP_SERVER_NAME,
                settingsMcpServerUrl = mcpServers.firstOrNull()?.serverUrl.orEmpty(),
                settingsMcpAuthToken = mcpServers.firstOrNull()?.authToken.orEmpty(),
                settingsMcpToolTimeoutSeconds = mcpServers.firstOrNull()?.toolTimeoutSeconds
                    ?: AppLimits.DEFAULT_MCP_HTTP_TOOL_TIMEOUT_SECONDS.toString(),
                settingsMcpServers = mcpServers
            )
        }
    }

    private fun buildDiscordGatewayStatusText(): String {
        val snapshots = DiscordGatewayDiagnostics.getSnapshots().values.toList()
        val s = snapshots.singleOrNull()
        val lines = mutableListOf<String>()
        appendRuntimeStatusSummary(lines, "discord")
        if (s?.botUserId?.isNotBlank() == true) {
            lines += "Bot User ID: ${s.botUserId}"
        }
        lines += "Inbound seen: ${snapshots.sumOf { it.inboundSeen }}"
        lines += "Inbound forwarded: ${snapshots.sumOf { it.inboundForwarded }}"
        lines += "Outbound sent: ${snapshots.sumOf { it.outboundSent }}"
        if (s?.lastInboundChannelId?.isNotBlank() == true) {
            lines += "Last inbound channel: ${s.lastInboundChannelId}"
        }
        if (s?.lastGatewayPayload?.isNotBlank() == true) {
            lines += "Last payload: ${s.lastGatewayPayload}"
        }
        return lines.joinToString("\n")
    }

    private fun discordGatewayHintForError(error: String): String {
        val code = Regex("code=(\\d{4})").find(error)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
        return when (code) {
            4004 -> "Invalid bot token. Re-copy token from Discord Developer Portal."
            4013 -> "Invalid intents bitmask. Update app and retry."
            4014 -> "Disallowed intents. Enable Message Content Intent for this bot."
            else -> ""
        }
    }

    private fun buildSlackGatewayStatusText(): String {
        val snapshots = SlackGatewayDiagnostics.getSnapshots().values.toList()
        val s = snapshots.singleOrNull()
        val lines = mutableListOf<String>()
        appendRuntimeStatusSummary(lines, "slack")
        if (s?.botUserId?.isNotBlank() == true) {
            lines += "Bot User ID: ${s.botUserId}"
        }
        lines += "Inbound seen: ${snapshots.sumOf { it.inboundSeen }}"
        lines += "Inbound forwarded: ${snapshots.sumOf { it.inboundForwarded }}"
        lines += "Outbound sent: ${snapshots.sumOf { it.outboundSent }}"
        if (s?.lastInboundChannelId?.isNotBlank() == true) {
            lines += "Last inbound channel: ${s.lastInboundChannelId}"
        }
        if (s?.lastEnvelopeType?.isNotBlank() == true) {
            lines += "Last envelope: ${s.lastEnvelopeType}"
        }
        return lines.joinToString("\n")
    }

    private fun buildFeishuGatewayStatusText(): String {
        val snapshots = FeishuGatewayDiagnostics.getSnapshots().values.toList()
        val s = snapshots.singleOrNull()
        val lines = mutableListOf<String>()
        appendRuntimeStatusSummary(lines, "feishu")
        lines += "Inbound seen: ${snapshots.sumOf { it.inboundSeen }}"
        lines += "Inbound forwarded: ${snapshots.sumOf { it.inboundForwarded }}"
        lines += "Outbound sent: ${snapshots.sumOf { it.outboundSent }}"
        if (s?.lastInboundChatId?.isNotBlank() == true) {
            lines += "Last inbound target: ${s.lastInboundChatId}"
        }
        if (s?.lastSenderOpenId?.isNotBlank() == true) {
            lines += "Last sender open_id: ${s.lastSenderOpenId}"
        }
        if (s?.lastEventType?.isNotBlank() == true) {
            lines += "Last event: ${s.lastEventType}"
        }
        val detectedChats = snapshots.sumOf { it.recentChats.size }
        if (detectedChats > 0) {
            lines += "Detected chats: $detectedChats"
        }
        return lines.joinToString("\n")
    }

    private fun buildEmailGatewayStatusText(): String {
        val snapshots = EmailGatewayDiagnostics.getSnapshots().values.toList()
        val s = snapshots.singleOrNull()
        val lines = mutableListOf<String>()
        appendRuntimeStatusSummary(lines, "email")
        lines += "Inbound seen: ${snapshots.sumOf { it.inboundSeen }}"
        lines += "Inbound forwarded: ${snapshots.sumOf { it.inboundForwarded }}"
        lines += "Outbound sent: ${snapshots.sumOf { it.outboundSent }}"
        if (s?.lastSenderEmail?.isNotBlank() == true) {
            lines += "Last sender: ${s.lastSenderEmail}"
        }
        if (s?.lastSubject?.isNotBlank() == true) {
            lines += "Last subject: ${s.lastSubject}"
        }
        val detectedSenders = snapshots.sumOf { it.recentSenders.size }
        if (detectedSenders > 0) {
            lines += "Detected senders: $detectedSenders"
        }
        return lines.joinToString("\n")
    }

    private fun buildWeComGatewayStatusText(): String {
        val snapshots = WeComGatewayDiagnostics.getSnapshots().values.toList()
        val s = snapshots.singleOrNull()
        val lines = mutableListOf<String>()
        appendRuntimeStatusSummary(lines, "wecom")
        lines += "Inbound seen: ${snapshots.sumOf { it.inboundSeen }}"
        lines += "Inbound forwarded: ${snapshots.sumOf { it.inboundForwarded }}"
        lines += "Outbound sent: ${snapshots.sumOf { it.outboundSent }}"
        if (s?.lastInboundChatId?.isNotBlank() == true) {
            lines += "Last inbound target: ${s.lastInboundChatId}"
        }
        if (s?.lastSenderUserId?.isNotBlank() == true) {
            lines += "Last sender user ID: ${s.lastSenderUserId}"
        }
        if (s?.lastEventType?.isNotBlank() == true) {
            lines += "Last event: ${s.lastEventType}"
        }
        val detectedChats = snapshots.sumOf { it.recentChats.size }
        if (detectedChats > 0) {
            lines += "Detected chats: $detectedChats"
        }
        return lines.joinToString("\n")
    }

    private fun appendRuntimeStatusSummary(lines: MutableList<String>, channel: String) {
        val snapshots = ChannelRuntimeDiagnostics.getSnapshots(channel).values
        lines += "Adapters: ${snapshots.size}"
        lines += "Running: ${snapshots.count { it.running }}"
        lines += "Connected: ${snapshots.count { it.connected }}"
        lines += "Ready: ${snapshots.count { it.ready }}"
        val lastError = snapshots.asSequence()
            .map { it.lastError.trim() }
            .firstOrNull { it.isNotBlank() }
            .orEmpty()
        if (lastError.isNotBlank()) {
            lines += "Runtime error: $lastError"
        }
    }

    private fun weComGatewayHintForError(error: String): String {
        return when {
            error.contains("auth", ignoreCase = true) || error.contains("secret", ignoreCase = true) ->
                "Check WeCom Bot ID and Secret."
            error.contains("heartbeat", ignoreCase = true) ->
                "Connection stalled. Reopen the session settings or toggle Channels to reconnect."
            error.contains("socket", ignoreCase = true) || error.contains("websocket", ignoreCase = true) ->
                "WebSocket disconnected. Check network access and try again."
            else -> ""
        }
    }

    private fun slackGatewayHintForError(error: String): String {
        return when {
            error.contains("invalid_auth", ignoreCase = true) ->
                "Invalid token. Check Slack bot token (xoxb) and app token (xapp)."
            error.contains("missing_scope", ignoreCase = true) ->
                "Missing scope. Ensure chat:write, reactions:write, app_mentions:read are granted."
            error.contains("not_authed", ignoreCase = true) ->
                "Token missing or malformed. Re-copy from Slack app settings."
            error.contains("apps.connections.open", ignoreCase = true) ->
                "Socket mode open failed. Verify app token has connections:write and Socket Mode is enabled."
            else -> ""
        }
    }

    private fun applyCronRuntimeConfig(config: CronConfig) {
        reloadAutomationViaActiveRuntime()
    }

    private suspend fun persistCronSettings(
        update: com.palmclaw.tools.CronConfigUpdate
    ): CronConfig {
        val current = configStore.getCronConfig()
        val minEveryMs = update.minEveryMs ?: current.minEveryMs
        if (minEveryMs !in AppLimits.MIN_CRON_MIN_EVERY_MS..AppLimits.MAX_CRON_MIN_EVERY_MS) {
            throw IllegalArgumentException(
                "Cron min interval ms must be between ${AppLimits.MIN_CRON_MIN_EVERY_MS} and ${AppLimits.MAX_CRON_MIN_EVERY_MS}"
            )
        }
        val maxJobs = update.maxJobs ?: current.maxJobs
        if (maxJobs !in AppLimits.MIN_CRON_MAX_JOBS..AppLimits.MAX_CRON_MAX_JOBS) {
            throw IllegalArgumentException(
                "Cron max jobs must be between ${AppLimits.MIN_CRON_MAX_JOBS} and ${AppLimits.MAX_CRON_MAX_JOBS}"
            )
        }
        val config = CronConfig(
            enabled = update.enabled ?: current.enabled,
            minEveryMs = minEveryMs,
            maxJobs = maxJobs
        )
        configStore.saveCronConfig(config)
        reloadAutomationViaActiveRuntime()
        _uiState.update {
            it.copy(
                settingsCronEnabled = config.enabled,
                settingsCronMinEveryMs = config.minEveryMs.toString(),
                settingsCronMaxJobs = config.maxJobs.toString()
            )
        }
        return config
    }

    private suspend fun setCronEnabledFromTool(enabled: Boolean) {
        persistCronSettings(com.palmclaw.tools.CronConfigUpdate(enabled = enabled))
    }

    private fun applyHeartbeatRuntimeConfig(config: HeartbeatConfig) {
        reloadAutomationViaActiveRuntime()
    }

    private fun applyGatewayRuntimeConfig(config: ChannelsConfig) {
        val app = getApplication<Application>()
        if (shouldDelegateRemoteGatewayToAlwaysOnService()) {
            RuntimeController.stop()
            AlwaysOnModeController.startService(app)
            AlwaysOnModeController.reloadGateway()
            return
        }
        AlwaysOnModeController.stopService(app)
        if (RuntimeController.status.value.running) {
            RuntimeController.reloadGateway(app)
        } else {
            RuntimeController.start(app)
        }
    }

    private fun applyMcpRuntimeConfig(config: McpHttpConfig) {
        reloadMcpViaActiveRuntime(config)
    }

    private fun validateMcpEndpointUrl(url: String) {
        if (url.isBlank()) {
            throw IllegalArgumentException("MCP server URL is required when MCP is enabled")
        }
        val parsed = url.toHttpUrlOrNull()
            ?: throw IllegalArgumentException("MCP server URL is invalid")
        val scheme = parsed.scheme.lowercase(Locale.US)
        if (scheme != "http" && scheme != "https") {
            throw IllegalArgumentException("MCP server URL must use http or https")
        }
        if (scheme == "http" && !isLocalMcpHost(parsed.host)) {
            throw IllegalArgumentException("Use HTTPS for non-local MCP endpoints")
        }
    }

    private fun buildNormalizedMcpServers(state: ChatUiState): List<McpHttpServerConfig> {
        return state.settingsMcpServers.mapIndexedNotNull { index, item ->
            val name = item.serverName.trim().ifBlank { AppLimits.DEFAULT_MCP_HTTP_SERVER_NAME }
            val url = item.serverUrl.trim()
            val token = item.authToken.trim()
            val timeout = item.toolTimeoutSeconds.trim().toIntOrNull()
                ?: throw IllegalArgumentException("MCP server #${index + 1} timeout must be a number")
            if (timeout !in AppLimits.MIN_MCP_HTTP_TOOL_TIMEOUT_SECONDS..AppLimits.MAX_MCP_HTTP_TOOL_TIMEOUT_SECONDS) {
                throw IllegalArgumentException(
                    "MCP server #${index + 1} timeout must be between ${AppLimits.MIN_MCP_HTTP_TOOL_TIMEOUT_SECONDS} and ${AppLimits.MAX_MCP_HTTP_TOOL_TIMEOUT_SECONDS} seconds"
                )
            }
            val looksEmpty = url.isBlank() && token.isBlank() && item.serverName.trim().isBlank()
            if (looksEmpty) return@mapIndexedNotNull null
            if (url.isBlank()) {
                throw IllegalArgumentException("MCP server #${index + 1} URL is required")
            }
            validateMcpEndpointUrl(url)
            McpHttpServerConfig(
                id = item.id.ifBlank { "mcp_${index + 1}" },
                serverName = name,
                serverUrl = url,
                authToken = token,
                toolTimeoutSeconds = timeout
            )
        }
    }

    private fun CronJob.toUiCronJob(): UiCronJob {
        return UiCronJob(
            id = id,
            name = name,
            enabled = enabled,
            schedule = when (schedule.kind) {
                "every" -> "every ${schedule.everyMs?.div(1000L) ?: 0L}s"
                "at" -> "at ${schedule.atMs?.let(::formatTimeMs).orEmpty()}"
                "cron" -> schedule.expr ?: "cron"
                else -> schedule.kind
            },
            nextRunAt = state.nextRunAtMs?.let(::formatTimeMs),
            lastStatus = state.lastStatus,
            lastError = state.lastError
        )
    }

    private fun formatTimeMs(value: Long): String {
        return runCatching {
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(value))
        }.getOrElse { value.toString() }
    }

    private fun normalizeDiscordChannelId(raw: String): String {
        return SessionChannelBindingRules.normalizeDiscordChannelId(raw)
    }

    private fun normalizeDiscordResponseMode(raw: String): String {
        return SessionChannelBindingRules.normalizeDiscordResponseMode(raw)
    }

    private fun normalizeSlackChannelId(raw: String): String {
        return SessionChannelBindingRules.normalizeSlackChannelId(raw)
    }

    private fun normalizeSlackResponseMode(raw: String): String {
        return SessionChannelBindingRules.normalizeSlackResponseMode(raw)
    }

    private fun normalizeFeishuResponseMode(raw: String): String {
        return SessionChannelBindingRules.normalizeFeishuResponseMode(raw)
    }

    private fun normalizeFeishuTargetId(raw: String): String {
        return SessionChannelBindingRules.normalizeFeishuTargetId(raw)
    }

    private fun normalizeWeComTargetId(raw: String): String {
        return SessionChannelBindingRules.normalizeWeComTargetId(raw)
    }

    private fun normalizeEmailAddress(raw: String): String {
        return SessionChannelBindingRules.normalizeEmailAddress(raw)
    }

    private fun parseAllowedUserIds(raw: String): List<String> {
        return SessionChannelBindingRules.parseAllowedIdentifiers(raw)
    }

    private fun isDiscordSnowflake(value: String): Boolean {
        return SessionChannelBindingRules.isDiscordSnowflake(value)
    }

    private fun isSlackChannelId(value: String): Boolean {
        return SessionChannelBindingRules.isSlackChannelId(value)
    }

    private fun isFeishuTargetId(value: String): Boolean {
        return SessionChannelBindingRules.isFeishuTargetId(value)
    }

    private fun isEmailAddress(value: String): Boolean {
        val normalized = value.trim()
        return normalized.isNotBlank() && android.util.Patterns.EMAIL_ADDRESS.matcher(normalized).matches()
    }

    private fun isLocalMcpHost(host: String): Boolean {
        if (host.equals("localhost", ignoreCase = true)) return true
        if (host == "127.0.0.1") return true
        if (host.startsWith("10.")) return true
        if (host.startsWith("192.168.")) return true
        if (host.startsWith("172.")) {
            val second = host.split(".").getOrNull(1)?.toIntOrNull()
            if (second != null && second in 16..31) return true
        }
        return false
    }

    private fun runtimeToolArgsPreviewMaxChars(): Int {
        return configStore.getConfig().toolArgsPreviewMaxChars.coerceIn(
            AppLimits.MIN_TOOL_ARGS_PREVIEW_MAX_CHARS,
            AppLimits.MAX_TOOL_ARGS_PREVIEW_MAX_CHARS
        )
    }

    private fun readHeartbeatDoc(): String {
        heartbeatDocFile.parentFile?.mkdirs()
        if (!heartbeatDocFile.exists()) {
            heartbeatDocFile.writeText(
                templateStore.loadTemplate(HeartbeatDoc.FILE_NAME).orEmpty(),
                Charsets.UTF_8
            )
        }
        return runCatching {
            heartbeatDocFile.readText(Charsets.UTF_8)
        }.getOrDefault(templateStore.loadTemplate(HeartbeatDoc.FILE_NAME).orEmpty())
    }

    companion object {
        private const val TAG = "ChatViewModel"
        private const val MAX_MEDIA_ATTACHMENTS_PER_MESSAGE = 4
        private const val FEISHU_DISCOVERY_STARTUP_RETRIES = 8
        private const val FEISHU_DISCOVERY_STARTUP_RETRY_DELAY_MS = 350L
        private const val WECOM_DISCOVERY_STARTUP_RETRIES = 8
        private const val WECOM_DISCOVERY_STARTUP_RETRY_DELAY_MS = 350L

        fun factory(application: Application): ViewModelProvider.Factory {
            return object : ViewModelProvider.Factory {
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    @Suppress("UNCHECKED_CAST")
                    return ChatViewModel(application) as T
                }
            }
        }
    }
}

private data class FeishuDiscoveryResult(
    val snapshots: Map<String, com.palmclaw.channels.FeishuGatewaySnapshot>,
    val candidates: List<UiFeishuChatCandidate>
)

private data class UiMcpServerRuntimeStatus(
    val status: String,
    val usable: Boolean = status.equals("Connected", ignoreCase = true),
    val detail: String = "",
    val toolCount: Int = 0,
    val toolNames: List<String> = emptyList()
)

private data class Quadruple<A, B, C, D>(
    val first: A,
    val second: B,
    val third: C,
    val fourth: D
)

private data class EmailCredentialKey(
    val consentGranted: Boolean,
    val imapHost: String,
    val imapPort: Int,
    val imapUsername: String,
    val imapPassword: String,
    val smtpHost: String,
    val smtpPort: Int,
    val smtpUsername: String,
    val smtpPassword: String,
    val fromAddress: String,
    val autoReplyEnabled: Boolean
)

