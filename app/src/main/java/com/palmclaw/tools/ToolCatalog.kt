package com.palmclaw.tools

import android.content.Context
import com.palmclaw.config.AppStoragePaths
import com.palmclaw.config.CronConfig
import com.palmclaw.cron.CronService
import com.palmclaw.memory.MemoryStore
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

fun createToolRegistry(
    context: Context,
    cronService: CronService?,
    memoryStore: MemoryStore = MemoryStore(context),
    currentSessionIdProvider: () -> String = { "local" },
    onSetCronEnabled: (suspend (Boolean) -> Unit)? = null,
    onUpdateCronConfig: (suspend (CronConfigUpdate) -> CronConfig)? = null,
    defaultTimeoutMsProvider: () -> Long = { 60_000L }
): ToolRegistry {
    val tools = buildCoreTools(context, memoryStore, currentSessionIdProvider)
    if (cronService != null) {
        tools += createCronToolSet(cronService, onSetCronEnabled, onUpdateCronConfig)
    }
    return ToolRegistry(
        initialTools = tools.associateBy { it.name },
        timeoutMsProvider = defaultTimeoutMsProvider
    )
}

private fun buildCoreTools(
    context: Context,
    memoryStore: MemoryStore,
    currentSessionIdProvider: () -> String
): MutableList<Tool> {
    val client = OkHttpClient.Builder()
        .callTimeout(60, TimeUnit.SECONDS)
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()
    return mutableListOf<Tool>(
        MessageTool()
    ).apply {
        addAll(createAndroidDeviceToolSet(context))
        addAll(createAndroidMediaToolSet(context))
        addAll(createAndroidBluetoothToolSet(context))
        addAll(createAndroidPersonalToolSet(context))
        addAll(createWebToolSet(client))
        addAll(createSummarizeToolSet(context, client))
        addAll(createWeatherToolSet(client))
        addAll(createFileToolSet(context, AppStoragePaths.storageRoot(context)))
        addAll(createMemoryToolSet(memoryStore, currentSessionIdProvider))
    }
}
