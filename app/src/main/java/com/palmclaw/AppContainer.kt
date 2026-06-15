package com.palmclaw

import android.app.Application
import com.palmclaw.attachments.AttachmentRecordRepository
import com.palmclaw.attachments.AttachmentTransferService
import com.palmclaw.agent.AgentLogStore
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
import com.palmclaw.ui.domain.RuntimeGateway
import com.palmclaw.ui.domain.RuntimeApplicationGateway
import com.palmclaw.ui.domain.SkillRepository
import com.palmclaw.workspace.SessionLifecycleService
import com.palmclaw.workspace.SessionUiLifecycleService
import com.palmclaw.workspace.SessionWorkspaceManager
import java.io.File
import java.util.concurrent.TimeUnit
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
        modeConfigGateway = ConfigStoreRuntimeModeConfigGateway(configStore)
    )
    val sessionUiLifecycleService: SessionUiLifecycleService = SessionUiLifecycleService(
        sessionLifecycleService = sessionLifecycleService,
        refreshGatewayRuntimeConfig = runtimeApplicationService::refreshGatewayRuntimeConfig
    )
    val chatRepository: ChatRepository = DefaultChatRepository(
        messageRepository = messageRepository,
        sessionRepository = sessionRepository,
        sessionUiLifecycleService = sessionUiLifecycleService
    )
    val runtimeGateway: RuntimeGateway = RuntimeApplicationGateway(runtimeApplicationService)
    val skillRepository: SkillRepository = DefaultSkillRepository(
        skillsLoader = skillsLoader,
        clawHubClient = clawHubClient,
        skillInstallService = skillInstallService
    )
    val channelBindingService: ChannelBindingService = ConfigStoreChannelBindingService(configStore)
    val chatViewModelDependencies: ChatViewModelDependencies = ChatViewModelDependencies(
        chatRepository = chatRepository,
        runtimeGateway = runtimeGateway,
        skillRepository = skillRepository,
        channelBindingService = channelBindingService
    )
    val heartbeatDocFile: File = AppStoragePaths.heartbeatDocFile(app)
    val uiJson: Json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
        prettyPrintIndent = "  "
    }
    val telegramDiscoveryClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(20, TimeUnit.SECONDS)
        .build()
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
        attachmentTransferService = attachmentTransferService
    )

    companion object {
        fun from(application: Application): AppContainer {
            return (application as? PalmClawApplication)?.appContainer ?: AppContainer(application)
        }
    }
}
