package com.palmclaw.ui

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
import com.palmclaw.runtime.RuntimeApplicationService
import com.palmclaw.skills.ClawHubClient
import com.palmclaw.skills.SkillInstallService
import com.palmclaw.skills.SkillsLoader
import com.palmclaw.storage.AppDatabase
import com.palmclaw.storage.MessageRepository
import com.palmclaw.storage.SessionRepository
import com.palmclaw.templates.TemplateStore
import com.palmclaw.workspace.SessionLifecycleService
import com.palmclaw.workspace.SessionUiLifecycleService
import com.palmclaw.workspace.SessionWorkspaceManager
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient

/**
 * Internal dependency container for [ChatViewModel].
 *
 * Phase 1 keeps hand-wired construction but makes runtime dependencies explicit.
 */
internal class ChatViewModelEnvironment(app: Application) {
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
    val sessionRepository: SessionRepository =
        SessionRepository(
            sessionDao = database.sessionDao(),
            messageDao = database.messageDao(),
            attachmentRecordRepository = attachmentRecordRepository
        )
    val cronRepository: CronRepository = CronRepository(database.cronJobDao())
    val cronService: CronService = CronService(app, cronRepository)
    val cronLogStore: CronLogStore = CronLogStore(app)
    val agentLogStore: AgentLogStore = AgentLogStore(app)
    val configStore: ConfigStore = ConfigStore(app)
    val providerResolutionStore: ProviderResolutionStore = ProviderResolutionStore(app)
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
}
