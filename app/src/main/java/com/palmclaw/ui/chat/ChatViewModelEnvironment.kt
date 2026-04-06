package com.palmclaw.ui

import android.app.Application
import com.palmclaw.agent.AgentLogStore
import com.palmclaw.config.AppStoragePaths
import com.palmclaw.config.ConfigStore
import com.palmclaw.cron.CronLogStore
import com.palmclaw.cron.CronRepository
import com.palmclaw.cron.CronService
import com.palmclaw.memory.MemoryStore
import com.palmclaw.providers.ProviderResolutionStore
import com.palmclaw.storage.AppDatabase
import com.palmclaw.storage.MessageRepository
import com.palmclaw.storage.SessionRepository
import com.palmclaw.templates.TemplateStore
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
    val messageRepository: MessageRepository = MessageRepository(database.messageDao())
    val sessionRepository: SessionRepository =
        SessionRepository(database.sessionDao(), database.messageDao())
    val cronRepository: CronRepository = CronRepository(database.cronJobDao())
    val cronService: CronService = CronService(app, cronRepository)
    val cronLogStore: CronLogStore = CronLogStore(app)
    val agentLogStore: AgentLogStore = AgentLogStore(app)
    val configStore: ConfigStore = ConfigStore(app)
    val providerResolutionStore: ProviderResolutionStore = ProviderResolutionStore(app)
    val memoryStore: MemoryStore = MemoryStore(app)
    val templateStore: TemplateStore = TemplateStore(app)
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
