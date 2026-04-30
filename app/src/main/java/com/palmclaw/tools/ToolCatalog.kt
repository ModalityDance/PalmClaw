package com.palmclaw.tools

import android.content.Context
import android.os.Environment
import com.palmclaw.config.AppConfig
import com.palmclaw.config.AppLimits
import com.palmclaw.config.CronConfig
import com.palmclaw.cron.CronService
import com.palmclaw.memory.MemoryStore
import com.palmclaw.workspace.SessionWorkspaceManager
import com.palmclaw.workspace.WorkspacePathResolver
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

fun createToolRegistry(
    context: Context,
    cronService: CronService?,
    memoryStore: MemoryStore = MemoryStore(context),
    configProvider: () -> AppConfig = {
        AppConfig(
            providerName = AppLimits.DEFAULT_PROVIDER,
            apiKey = "",
            model = AppLimits.DEFAULT_MODEL
        )
    },
    currentSessionIdProvider: () -> String = { "local" },
    sessionWorkspaceManager: SessionWorkspaceManager = SessionWorkspaceManager(context),
    onSetCronEnabled: (suspend (Boolean) -> Unit)? = null,
    onUpdateCronConfig: (suspend (CronConfigUpdate) -> CronConfig)? = null,
    defaultTimeoutMsProvider: () -> Long = { 60_000L }
): ToolRegistry {
    val tools = buildCoreTools(
        context = context,
        memoryStore = memoryStore,
        currentSessionIdProvider = currentSessionIdProvider,
        sessionWorkspaceManager = sessionWorkspaceManager,
        searchSettingsProvider = {
            val config = configProvider()
            SearchProviderRuntimeConfig(
                providerId = config.searchProvider,
                configs = config.searchProviderConfigs
            )
        }
    )
    if (cronService != null) {
        tools += createCronToolSet(cronService, onSetCronEnabled, onUpdateCronConfig)
    }
    val config = configProvider()
    return ToolRegistry(
        initialTools = tools
            .filter { BuiltInToolCatalog.isEnabled(config, it.name) }
            .associateBy { it.name },
        timeoutMsProvider = defaultTimeoutMsProvider
    )
}

internal fun buildCoreTools(
    context: Context,
    memoryStore: MemoryStore,
    currentSessionIdProvider: () -> String,
    sessionWorkspaceManager: SessionWorkspaceManager,
    searchSettingsProvider: () -> SearchProviderRuntimeConfig = { SearchProviderRuntimeConfig() }
): MutableList<Tool> {
    val client = OkHttpClient.Builder()
        .callTimeout(60, TimeUnit.SECONDS)
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()
    val appContext = context.applicationContext
    val pathResolver = WorkspacePathResolver(
        currentSessionIdProvider = currentSessionIdProvider,
        workspaceManager = sessionWorkspaceManager,
        sharedExternalRoot = runCatching { Environment.getExternalStorageDirectory().canonicalFile }.getOrNull(),
        hasSharedStorageAccess = { hasAllFilesAccess(appContext) }
    )
    return mutableListOf<Tool>(
        MessageTool()
    ).apply {
        addAll(createAndroidDeviceToolSet(context))
        addAll(createAndroidMediaToolSet(context, pathResolver))
        addAll(createAndroidBluetoothToolSet(context))
        addAll(createAndroidPersonalToolSet(context))
        addAll(createWebToolSet(context, client, searchSettingsProvider))
        addAll(createSummarizeToolSet(context, client, pathResolver))
        addAll(createWeatherToolSet(client))
        addAll(createFileToolSet(context, pathResolver))
        addAll(createMemoryToolSet(memoryStore, currentSessionIdProvider))
        add(WorkspaceGetTool(sessionWorkspaceManager, currentSessionIdProvider))
    }
}
