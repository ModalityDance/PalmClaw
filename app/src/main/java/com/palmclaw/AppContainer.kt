package com.palmclaw

import android.app.Application
import android.util.Log
import com.palmclaw.attachments.AttachmentRecordRepository
import com.palmclaw.attachments.AttachmentTransferService
import com.palmclaw.agent.AgentLogStore
import com.palmclaw.channels.AndroidEmailAddressValidator
import com.palmclaw.channels.AndroidChannelDiscoveryAdapterFactory
import com.palmclaw.channels.ChannelBindingRuntimeProjector
import com.palmclaw.channels.ChannelDiscoveryService
import com.palmclaw.channels.ChannelRuntimeDiagnostics
import com.palmclaw.channels.ChannelRuntimeSnapshotSource
import com.palmclaw.channels.DefaultEmailSenderDetector
import com.palmclaw.channels.EmailAddressValidator
import com.palmclaw.channels.ProcessChannelDiscoveryDiagnosticsSource
import com.palmclaw.channels.ProcessChannelGatewayDiagnosticsSource
import com.palmclaw.channels.ProcessChannelRuntimeSnapshotSource
import com.palmclaw.channels.TelegramApiDiscoveryClient
import com.palmclaw.config.AppStoragePaths
import com.palmclaw.config.ConfigStore
import com.palmclaw.cron.CronLogStore
import com.palmclaw.cron.CronRepository
import com.palmclaw.cron.CronService
import com.palmclaw.memory.MemoryStore
import com.palmclaw.providers.ProviderResolutionStore
import com.palmclaw.runtime.ConfigStoreRuntimeModeConfigGateway
import com.palmclaw.runtime.GatewayRuntimeDependencies
import com.palmclaw.runtime.RuntimeApplicationService
import com.palmclaw.runtime.RuntimeForegroundLifecycleCoordinator
import com.palmclaw.runtime.alwayson.AlwaysOnCoordinator
import com.palmclaw.runtime.alwayson.AlwaysOnRuntimeAccess
import com.palmclaw.runtime.alwayson.AlwaysOnTrigger
import com.palmclaw.runtime.alwayson.AndroidAlwaysOnPlatform
import com.palmclaw.runtime.alwayson.ConfigStoreAlwaysOnConfigStore
import com.palmclaw.runtime.alwayson.GatewayAvailabilityAdapter
import com.palmclaw.runtime.alwayson.SupervisorAlwaysOnGatewayRuntimePort
import com.palmclaw.runtime.control.AppRuntimeControlPersistence
import com.palmclaw.runtime.control.HeartbeatRuntimePort
import com.palmclaw.runtime.control.RuntimeControlService
import com.palmclaw.heartbeat.HeartbeatService
import com.palmclaw.skills.ClawHubClient
import com.palmclaw.skills.SkillInstallService
import com.palmclaw.skills.SkillsLoader
import com.palmclaw.storage.AppDatabase
import com.palmclaw.storage.MessageRepository
import com.palmclaw.storage.SessionRepository
import com.palmclaw.templates.TemplateStore
import com.palmclaw.ui.domain.ConfigStoreChannelBindingService
import com.palmclaw.ui.domain.ChannelBindingService
import com.palmclaw.ui.domain.ChatViewModelDependencies
import com.palmclaw.ui.domain.ChatRepository
import com.palmclaw.ui.domain.DefaultChatRepository
import com.palmclaw.ui.domain.DefaultSkillRepository
import com.palmclaw.ui.domain.RuntimeApplicationGateway
import com.palmclaw.ui.domain.SkillRepository
import com.palmclaw.ui.GatewayStatusOverviewAssembler
import com.palmclaw.workspace.SessionLifecycleService
import com.palmclaw.workspace.SessionUiLifecycleService
import com.palmclaw.workspace.SessionWorkspaceManager
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient

class AppContainer(private val app: Application) {
    val storageMigration: Unit = AppStoragePaths.migrateLegacyLayout(app)
    val database: AppDatabase = AppDatabase.getInstance(app)
    val attachmentRecordRepository: AttachmentRecordRepository = AttachmentRecordRepository(
        attachmentRecordDao = database.attachmentRecordDao(),
        messageDao = database.messageDao()
    )
    val messageRepository: MessageRepository = MessageRepository(
        dao = database.messageDao(),
        attachmentRecordRepository = attachmentRecordRepository,
        database = database
    )
    val sessionRepository: SessionRepository = SessionRepository(
        sessionDao = database.sessionDao(),
        messageDao = database.messageDao(),
        attachmentRecordRepository = attachmentRecordRepository,
        database = database
    )
    val cronRepository: CronRepository = CronRepository(database.cronJobDao())
    val cronService: CronService = CronService(app, cronRepository)
    val cronLogStore: CronLogStore = CronLogStore(app)
    val agentLogStore: AgentLogStore = AgentLogStore(app)
    val configStore: ConfigStore = ConfigStore(app)
    internal val providerResolutionStore: ProviderResolutionStore = ProviderResolutionStore(app)
    val memoryStore: MemoryStore = MemoryStore(app)
    val templateStore: TemplateStore = TemplateStore(app)
    val workspaceManager: SessionWorkspaceManager = SessionWorkspaceManager(app)
    val skillsLoader: SkillsLoader = SkillsLoader(
        context = app,
        skillStatesProvider = { configStore.getConfig().skillStates }
    )
    val attachmentTransferService: AttachmentTransferService = AttachmentTransferService(
        context = app,
        workspaceManager = workspaceManager
    )
    val heartbeatService: HeartbeatService = HeartbeatService(app)
    val heartbeatDocFile: File = AppStoragePaths.heartbeatDocFile(app)
    internal val emailAddressValidator: EmailAddressValidator = AndroidEmailAddressValidator
    internal val channelBindingRuntimeProjector = ChannelBindingRuntimeProjector(emailAddressValidator)
    internal val channelRuntimeSnapshotSource: ChannelRuntimeSnapshotSource =
        ProcessChannelRuntimeSnapshotSource
    private val alwaysOnPlatform = AndroidAlwaysOnPlatform(app)
    private val alwaysOnGatewayAvailability = GatewayAvailabilityAdapter(
        bindingsProvider = configStore::getSessionChannelBindings,
        bindingProjector = channelBindingRuntimeProjector,
        snapshotSource = channelRuntimeSnapshotSource,
        runtime = SupervisorAlwaysOnGatewayRuntimePort { app }
    )
    internal val alwaysOnCoordinator = AlwaysOnCoordinator(
        platform = alwaysOnPlatform,
        gateway = alwaysOnGatewayAvailability,
        configStore = ConfigStoreAlwaysOnConfigStore(configStore)
    )
    init {
        AlwaysOnRuntimeAccess.install(alwaysOnCoordinator)
    }
    private val applicationScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @Suppress("unused")
    private val alwaysOnDiagnosticsObservation = applicationScope.launch {
        ChannelRuntimeDiagnostics.state
            .map { diagnostics ->
                diagnostics.values
                    .groupingBy { snapshot -> snapshot.state }
                    .eachCount()
            }
            .distinctUntilChanged()
            .drop(1)
            .collect {
                try {
                    alwaysOnCoordinator.reconcile(AlwaysOnTrigger.GATEWAY_STATE_CHANGED)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    Log.w(TAG, "Always-on diagnostics reconciliation failed")
                }
            }
    }
    internal val channelGatewayDiagnosticsSource = ProcessChannelGatewayDiagnosticsSource
    internal val gatewayStatusOverviewAssembler = GatewayStatusOverviewAssembler(
        channelGatewayDiagnosticsSource
    )
    private val telegramDiscoveryClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(20, TimeUnit.SECONDS)
        .build()
    internal val channelDiscoveryService = ChannelDiscoveryService(
        telegramClient = TelegramApiDiscoveryClient(telegramDiscoveryClient),
        emailDetector = DefaultEmailSenderDetector,
        diagnosticsSource = ProcessChannelDiscoveryDiagnosticsSource,
        runtimeSnapshotSource = channelRuntimeSnapshotSource,
        adapterFactory = AndroidChannelDiscoveryAdapterFactory(app)
    )
    internal val runtimeControlPersistence = AppRuntimeControlPersistence(
        configStore = configStore,
        messageRepository = messageRepository,
        sessionRepository = sessionRepository,
        heartbeatDocument = heartbeatDocFile
    )
    internal val runtimeControlService = RuntimeControlService(
        persistence = runtimeControlPersistence,
        channelProjector = channelBindingRuntimeProjector
    )
    val clawHubClient: ClawHubClient = ClawHubClient(
        client = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .callTimeout(30, TimeUnit.SECONDS)
            .build()
    )
    val skillInstallService: SkillInstallService = SkillInstallService(
        context = app,
        clawHubClient = clawHubClient
    )
    val sessionLifecycleService: SessionLifecycleService = SessionLifecycleService(
        sessionRepository = sessionRepository,
        workspaceManager = workspaceManager,
        clearSessionChannelBinding = { sessionId ->
            configStore.clearSessionChannelBinding(sessionId)
        },
        listCronJobIdsForSession = { sessionId ->
            cronService.listJobs(includeDisabled = true)
                .filter { it.payload.sessionId?.trim() == sessionId.trim() }
                .map { it.id }
        },
        removeCronJob = { jobId ->
            cronService.removeJob(jobId)
        }
    )
    val runtimeApplicationService: RuntimeApplicationService = RuntimeApplicationService(
        appProvider = { app },
        modeConfigGateway = ConfigStoreRuntimeModeConfigGateway(configStore),
        alwaysOnControl = alwaysOnCoordinator
    )
    private val runtimeForegroundLifecycleCoordinator = RuntimeForegroundLifecycleCoordinator(
        scope = applicationScope,
        enterForeground = runtimeApplicationService::onAppForegrounded,
        leaveForeground = runtimeApplicationService::onAppBackgrounded
    )

    internal fun setAppForegrounded(foregrounded: Boolean) {
        runtimeForegroundLifecycleCoordinator.requestForegrounded(foregrounded)
    }

    val sessionUiLifecycleService: SessionUiLifecycleService = SessionUiLifecycleService(
        sessionLifecycleService = sessionLifecycleService,
        refreshGatewayRuntimeConfig = runtimeApplicationService::refreshGatewayRuntimeConfig
    )
    val chatRepository: ChatRepository = DefaultChatRepository(
        messageRepository = messageRepository,
        sessionRepository = sessionRepository,
        sessionUiLifecycleService = sessionUiLifecycleService
    )
    internal val runtimeApplicationGateway = RuntimeApplicationGateway(
        runtimeApplicationService,
        alwaysOnCoordinator.status
    )
    internal val uiHeartbeatRuntimePort = object : HeartbeatRuntimePort {
        override fun armNextAlarm(
            config: com.palmclaw.config.HeartbeatConfig,
            timestampMs: Long
        ) {
            heartbeatService.updateConfig(
                enabled = config.enabled,
                intervalSeconds = config.intervalSeconds
            )
            heartbeatService.armNextAlarm(timestampMs)
        }

        override suspend fun triggerNow(): String = runtimeApplicationGateway.triggerHeartbeatNow()
    }
    val skillRepository: SkillRepository = DefaultSkillRepository(
        skillsLoader = skillsLoader,
        clawHubClient = clawHubClient,
        skillInstallService = skillInstallService
    )
    val channelBindingService: ChannelBindingService = ConfigStoreChannelBindingService(configStore)
    val chatViewModelDependencies: ChatViewModelDependencies = ChatViewModelDependencies(
        chatRepository = chatRepository,
        runtimeStatusSource = runtimeApplicationGateway,
        runtimeExecutionGateway = runtimeApplicationGateway,
        runtimeRefreshGateway = runtimeApplicationGateway,
        skillRepository = skillRepository,
        channelBindingService = channelBindingService
    )
    val uiJson: Json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
        prettyPrintIndent = "  "
    }
    val updateCheckClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(20, TimeUnit.SECONDS)
        .build()
    val gatewayRuntimeDependencies: GatewayRuntimeDependencies = GatewayRuntimeDependencies(
        storageMigration = storageMigration,
        database = database,
        messageRepository = messageRepository,
        sessionRepository = sessionRepository,
        memoryStore = memoryStore,
        cronRepository = cronRepository,
        cronService = cronService,
        cronLogStore = cronLogStore,
        agentLogStore = agentLogStore,
        configStore = configStore,
        skillsLoader = skillsLoader,
        templateStore = templateStore,
        heartbeatDocFile = heartbeatDocFile,
        heartbeatService = heartbeatService,
        workspaceManager = workspaceManager,
        attachmentTransferService = attachmentTransferService,
        runtimeControlOperations = runtimeControlService,
        channelBindingRuntimeProjector = channelBindingRuntimeProjector,
        channelRuntimeSnapshotSource = channelRuntimeSnapshotSource,
        emailAddressValidator = emailAddressValidator
    )

    companion object {
        private const val TAG = "AppContainer"

        fun from(application: Application): AppContainer {
            return (application as? PalmClawApplication)?.appContainer ?: AppContainer(application)
        }
    }
}
