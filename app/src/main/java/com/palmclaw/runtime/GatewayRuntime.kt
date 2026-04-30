package com.palmclaw.runtime

import android.app.Application
import android.util.Log
import com.palmclaw.attachments.AttachmentRecordRepository
import com.palmclaw.attachments.AttachmentTransferService
import com.palmclaw.agent.AgentLoop
import com.palmclaw.agent.ContextBuilder
import com.palmclaw.agent.MemoryConsolidator
import com.palmclaw.agent.SubagentManager
import com.palmclaw.agent.ToolCallParser
import com.palmclaw.bus.InboundMessage
import com.palmclaw.bus.MessageAttachment
import com.palmclaw.bus.MessageAttachmentJsonCodec
import com.palmclaw.bus.MessageBus
import com.palmclaw.bus.OutboundMessage
import com.palmclaw.bus.normalizeMessageAttachments
import com.palmclaw.channels.ChannelAdapter
import com.palmclaw.channels.ChannelRuntimeDiagnostics
import com.palmclaw.channels.DiscordChannelAdapter
import com.palmclaw.channels.DiscordRouteRule
import com.palmclaw.channels.EmailAccountConfig
import com.palmclaw.channels.EmailChannelAdapter
import com.palmclaw.channels.FeishuChannelAdapter
import com.palmclaw.channels.FeishuRouteRule
import com.palmclaw.channels.GatewayOrchestrator
import com.palmclaw.channels.SlackChannelAdapter
import com.palmclaw.channels.SlackRouteRule
import com.palmclaw.channels.TelegramChannelAdapter
import com.palmclaw.channels.WeComChannelAdapter
import com.palmclaw.channels.WeComRouteRule
import com.palmclaw.config.AppConfig
import com.palmclaw.config.AppLimits
import com.palmclaw.config.AppSession
import com.palmclaw.config.AppStoragePaths
import com.palmclaw.config.ChannelsConfig
import com.palmclaw.config.ConfigStore
import com.palmclaw.config.CronConfig
import com.palmclaw.config.HeartbeatConfig
import com.palmclaw.config.HeartbeatDoc
import com.palmclaw.config.McpHttpConfig
import com.palmclaw.config.McpHttpServerConfig
import com.palmclaw.config.SessionChannelBinding
import com.palmclaw.cron.CronExecutionPromptBuilder
import com.palmclaw.cron.CronLogStore
import com.palmclaw.cron.CronRepository
import com.palmclaw.cron.CronService
import com.palmclaw.heartbeat.HeartbeatService
import com.palmclaw.memory.MemoryStore
import com.palmclaw.providers.ChatMessage
import com.palmclaw.providers.LlmProviderFactory
import com.palmclaw.providers.ToolSpec
import com.palmclaw.skills.SkillsLoader
import com.palmclaw.agent.AgentLogStore
import com.palmclaw.storage.AppDatabase
import com.palmclaw.storage.MessageRepository
import com.palmclaw.storage.SessionRepository
import com.palmclaw.storage.entities.SessionEntity
import com.palmclaw.templates.TemplateStore
import com.palmclaw.tools.ChannelsGetTool
import com.palmclaw.tools.ChannelsSetTool
import com.palmclaw.tools.CronConfigUpdate
import com.palmclaw.tools.McpHttpRuntime
import com.palmclaw.tools.McpStatusTool
import com.palmclaw.tools.MessageTool
import com.palmclaw.tools.RuntimeGetTool
import com.palmclaw.tools.RuntimeSetTool
import com.palmclaw.tools.SessionsListTool
import com.palmclaw.tools.SessionsSendTool
import com.palmclaw.tools.SpawnTool
import com.palmclaw.tools.Tool
import com.palmclaw.tools.ToolRegistry
import com.palmclaw.tools.BuiltInToolCatalog
import com.palmclaw.tools.SearchProviderRuntimeConfig
import com.palmclaw.tools.buildCoreTools
import com.palmclaw.tools.createCronToolSet
import com.palmclaw.workspace.SessionWorkspaceManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONObject
import java.security.MessageDigest
import java.util.Locale

data class GatewayRuntimeState(
    val gatewayRunning: Boolean = false,
    val activeAdapterCount: Int = 0,
    val lastError: String = "",
    val processingSessionIds: Set<String> = emptySet()
)

class GatewayRuntime(
    private val app: Application,
    private val enableAutomation: Boolean = true,
    private val enableMcp: Boolean = true,
    private val onStateChanged: (GatewayRuntimeState) -> Unit = {}
) {
    private val storageMigration: Unit = AppStoragePaths.migrateLegacyLayout(app)
    private val database = AppDatabase.getInstance(app)
    private val attachmentRecordRepository = AttachmentRecordRepository(
        attachmentRecordDao = database.attachmentRecordDao(),
        messageDao = database.messageDao()
    )
    private val messageRepository = MessageRepository(
        dao = database.messageDao(),
        attachmentRecordRepository = attachmentRecordRepository,
        database = database
    )
    private val sessionRepository = SessionRepository(
        sessionDao = database.sessionDao(),
        messageDao = database.messageDao(),
        attachmentRecordRepository = attachmentRecordRepository
    )
    private val memoryStore = MemoryStore(app)
    private val cronRepository = CronRepository(database.cronJobDao())
    private val cronService = CronService(app, cronRepository)
    private val cronLogStore = CronLogStore(app)
    private val agentLogStore = AgentLogStore(app)
    private val configStore = ConfigStore(app)
    private val providerFactory = LlmProviderFactory()
    private val skillsLoader = SkillsLoader(
        context = app,
        skillStatesProvider = { configStore.getConfig().skillStates }
    )
    private val templateStore = TemplateStore(app)
    private val toolCallParser = ToolCallParser()
    private val heartbeatDocFile = AppStoragePaths.heartbeatDocFile(app)
    private val heartbeatService = HeartbeatService(app)
    private val workspaceManager = SessionWorkspaceManager(app)
    private val attachmentTransferService = AttachmentTransferService(app, workspaceManager)
    private val runtimeScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val json = Json { ignoreUnknownKeys = true }

    @Volatile
    private var ambientSessionId: String = AppSession.LOCAL_SESSION_ID

    private val coreBuiltInTools = buildCoreTools(
        context = app,
        memoryStore = memoryStore,
        currentSessionIdProvider = {
            AgentLoop.currentSessionId()
                ?.trim()
                ?.ifBlank { null }
                ?: ambientSessionId.trim().ifBlank { AppSession.LOCAL_SESSION_ID }
        },
        sessionWorkspaceManager = workspaceManager,
        searchSettingsProvider = {
            val config = configStore.getConfig()
            SearchProviderRuntimeConfig(
                providerId = config.searchProvider,
                configs = config.searchProviderConfigs
            )
        }
    )
    private val cronBuiltInTools = if (enableAutomation) {
        createCronToolSet(
            cronService,
            onSetServiceEnabled = { enabled -> setCronEnabledFromTool(enabled) },
            onUpdateConfig = { update -> persistCronSettings(update) }
        )
    } else {
        emptyList()
    }
    private val toolRegistry = ToolRegistry(
        initialTools = initialEnabledTools().associateBy { it.name },
        timeoutMsProvider = {
            configStore.getConfig().defaultToolTimeoutSeconds
                .coerceIn(AppLimits.MIN_TOOL_TIMEOUT_SECONDS, AppLimits.MAX_TOOL_TIMEOUT_SECONDS)
                .toLong() * 1000L
        }
    )
    private val messageTool = coreBuiltInTools.firstOrNull { it.name == "message" } as? MessageTool
    private var runtimeGetTool: RuntimeGetTool? = null
    private var runtimeSetTool: RuntimeSetTool? = null
    private var heartbeatGetTool: com.palmclaw.tools.HeartbeatGetTool? = null
    private var heartbeatSetTool: com.palmclaw.tools.HeartbeatSetTool? = null
    private var heartbeatTriggerTool: com.palmclaw.tools.HeartbeatTriggerTool? = null
    private var channelsGetTool: ChannelsGetTool? = null
    private var channelsSetTool: ChannelsSetTool? = null
    private var sessionsListTool: SessionsListTool? = null
    private var sessionsSendTool: SessionsSendTool? = null
    private var spawnTool: SpawnTool? = null
    private var subagentManager: SubagentManager? = null
    private var mcpStatusTool: McpStatusTool? = null

    private val memoryConsolidator = MemoryConsolidator(
        repository = messageRepository,
        memoryStore = memoryStore,
        providerFactory = { providerFactory.create(configStore.getConfig()) },
        toolCallParser = toolCallParser
    )
    private val agentLoop = AgentLoop(
        repository = messageRepository,
        contextBuilder = ContextBuilder(
            workspaceContextProvider = ::buildWorkspaceContext
        ),
        toolCallParser = toolCallParser,
        toolRegistry = toolRegistry,
        llmProviderFactory = { providerFactory.create(configStore.getConfig()) },
        memoryStore = memoryStore,
        memoryConsolidator = memoryConsolidator,
        skillsLoader = skillsLoader,
        templateStore = templateStore,
        processLogger = { line -> agentLogStore.append(line) },
        usageReporter = { usage -> configStore.recordTokenUsage(usage) },
        maxRoundsProvider = { configStore.getConfig().maxToolRounds },
        toolResultMaxCharsProvider = { configStore.getConfig().toolResultMaxChars },
        memoryWindowProvider = { configStore.getConfig().memoryConsolidationWindow },
        maxContextMessagesProvider = { configStore.getConfig().contextMessages }
    )
    private val gatewayBus = MessageBus()
    private var gatewayOrchestrator: GatewayOrchestrator? = null
    private val mcpRuntimes = mutableListOf<McpHttpRuntime>()
    private var mcpServerStatuses: Map<String, RuntimeMcpServerStatus> = emptyMap()
    private val gatewayProcessingSessions = mutableSetOf<String>()
    @Volatile
    private var runtimeState = GatewayRuntimeState()
    @Volatile
    private var pendingGatewayConfig: ChannelsConfig? = null

    init {
        storageMigration
        if (enableAutomation) {
            wireCronCallback()
            wireCronLogging()
        }
        configureMessageTool()
        configureRuntimeSettingsTools()
        if (enableAutomation) {
            configureHeartbeatTools()
        }
        configureChannelsTools()
        configureMcpStatusTool()
        configureSessionsListTool()
        configureSessionsSendTool()
        configureSpawnTool()
        if (enableAutomation) {
            applyCronRuntimeConfig(configStore.getCronConfig())
            applyHeartbeatRuntimeConfig(configStore.getHeartbeatConfig())
        }
        if (enableMcp) {
            applyMcpRuntimeConfig(configStore.getMcpHttpConfig())
        }
    }

    fun start() {
        runtimeScope.launch {
            runCatching { ensureLocalSessionWorkspace() }
                .onFailure { t -> Log.e(TAG, "Failed to bootstrap local session workspace", t) }
        }
        reloadAllFromStoredConfig()
    }

    private fun initialEnabledTools(): List<Tool> {
        val config = configStore.getConfig()
        return (coreBuiltInTools + cronBuiltInTools)
            .filter { BuiltInToolCatalog.isEnabled(config, it.name) }
    }

    private fun syncBuiltInToolsFromStoredConfig() {
        val config = configStore.getConfig()
        syncManagedToolSet(coreBuiltInTools, config)
        syncManagedToolSet(cronBuiltInTools, config)
        runtimeGetTool?.let(::syncManagedTool)
        runtimeSetTool?.let(::syncManagedTool)
        heartbeatGetTool?.let(::syncManagedTool)
        heartbeatSetTool?.let(::syncManagedTool)
        heartbeatTriggerTool?.let(::syncManagedTool)
        channelsGetTool?.let(::syncManagedTool)
        channelsSetTool?.let(::syncManagedTool)
        mcpStatusTool?.let(::syncManagedTool)
        sessionsListTool?.let(::syncManagedTool)
        sessionsSendTool?.let(::syncManagedTool)
        spawnTool?.let(::syncManagedTool)
    }

    private fun syncManagedToolSet(tools: List<Tool>, config: AppConfig) {
        tools.forEach { tool ->
            if (BuiltInToolCatalog.isEnabled(config, tool.name)) {
                toolRegistry.register(tool)
            } else {
                toolRegistry.unregister(tool.name)
            }
        }
    }

    private fun syncManagedTool(tool: Tool) {
        if (BuiltInToolCatalog.isEnabled(configStore.getConfig(), tool.name)) {
            toolRegistry.register(tool)
        } else {
            toolRegistry.unregister(tool.name)
        }
    }

    suspend fun deliverOutboundViaOwnedGateway(outbound: OutboundMessage) {
        val orchestrator = gatewayOrchestrator
            ?: throw IllegalStateException("Gateway is not running; cannot deliver outbound message")
        gatewayBus.publishOutbound(outbound)
        updateState(gatewayRunning = true, activeAdapterCount = orchestrator.adapterCount)
    }

    fun reloadGatewayFromStoredConfig() {
        syncBuiltInToolsFromStoredConfig()
        applyGatewayRuntimeConfig(configStore.getChannelsConfig())
    }

    fun reloadAutomationFromStoredConfig() {
        if (!enableAutomation) return
        syncBuiltInToolsFromStoredConfig()
        applyCronRuntimeConfig(configStore.getCronConfig())
        applyHeartbeatRuntimeConfig(configStore.getHeartbeatConfig())
    }

    fun reloadMcpFromStoredConfig() {
        if (!enableMcp) return
        syncBuiltInToolsFromStoredConfig()
        applyMcpRuntimeConfig(configStore.getMcpHttpConfig())
    }

    fun reloadAllFromStoredConfig() {
        reloadGatewayFromStoredConfig()
        reloadAutomationFromStoredConfig()
        reloadMcpFromStoredConfig()
    }

    suspend fun runUserMessage(
        sessionId: String,
        sessionTitle: String,
        text: String,
        attachments: List<MessageAttachment> = emptyList()
    ) {
        val normalizedSessionId = normalizeSessionId(sessionId)
        val execution = executeAgentTurn(
            AgentTurnRequest(
                sessionId = normalizedSessionId,
                sessionTitle = normalizedSessionTitle(normalizedSessionId, sessionTitle),
                inputText = text,
                inputAttachments = attachments,
                deliveryMode = AgentTurnDeliveryMode.UseSessionBinding,
                requireExistingSession = normalizedSessionId != AppSession.LOCAL_SESSION_ID
            )
        )
        execution.throwIfFailed()
        mirrorLatestAssistantToBoundChannel(
            sessionId = execution.sessionId,
            beforeAssistantId = execution.beforeLatestAssistantId,
            binding = execution.binding
        )
    }

    suspend fun triggerHeartbeatNow(): String {
        if (!enableAutomation) {
            throw IllegalStateException("Heartbeat automation is not enabled in this runtime")
        }
        return triggerHeartbeatNowFromTool()
    }

    suspend fun processHeartbeatTick(): String? {
        if (!enableAutomation) {
            throw IllegalStateException("Heartbeat automation is not enabled in this runtime")
        }
        configStore.saveHeartbeatLastTriggeredAtMs(System.currentTimeMillis())
        val content = readHeartbeatDoc().trim()
        if (content.isBlank()) return null

        val parsed = parseHeartbeatTasks(content)
        val tasks = if (parsed.hasActiveSection) {
            parsed.tasks.trim().ifBlank { return null }
        } else {
            val decision = decideHeartbeat(content)
            if (decision.action != HEARTBEAT_ACTION_RUN) return null
            decision.tasks.trim().ifBlank { return null }
        }

        val execution = executeAgentTurn(
            AgentTurnRequest(
                sessionId = AppSession.SHARED_SESSION_ID,
                sessionTitle = AppSession.SHARED_SESSION_TITLE,
                inputText = tasks,
                inputRole = "internal_user",
                deliveryMode = AgentTurnDeliveryMode.LocalOnly
            )
        )
        execution.throwIfFailed()
        return execution.latestAssistantContentIfNew()?.trim().orEmpty().ifBlank { null }
    }

    suspend fun processDueCronJobs(resync: Boolean = false) {
        if (!enableAutomation) {
            throw IllegalStateException("Cron automation is not enabled in this runtime")
        }
        if (resync) {
            cronService.onSystemResync()
        } else {
            cronService.processDueJobs()
        }
    }

    fun shutdownRuntime() {
        cronService.onJob = null
        messageTool?.clearSendCallback()
        runtimeGetTool?.clearGetCallback()
        runtimeSetTool?.clearSetCallback()
        heartbeatGetTool?.clearGetCallback()
        heartbeatSetTool?.clearSetCallback()
        heartbeatTriggerTool?.clearTriggerCallback()
        channelsGetTool?.clearGetCallback()
        channelsSetTool?.clearSetCallback()
        sessionsListTool?.clearListCallback()
        sessionsSendTool?.clearSendCallback()
        subagentManager?.close()
        subagentManager = null
        mcpRuntimes.forEach { runCatching { it.close() } }
        mcpRuntimes.clear()
        gatewayOrchestrator?.stop()
        gatewayOrchestrator = null
        pendingGatewayConfig = null
        updateState(gatewayRunning = false, activeAdapterCount = 0)
        gatewayBus.close()
        agentLoop.close()
        heartbeatService.close()
        cronService.close()
        runtimeScope.cancel()
    }

    private fun configureMessageTool() {
        val tool = messageTool ?: return
        tool.setSendCallback { outbound ->
            if (!outbound.channel.equals("local", ignoreCase = true)) {
                requireRemoteAttachmentDeliverySupported(outbound)
            }
            val transcriptSessionId = if (outbound.channel.equals("local", ignoreCase = true)) {
                normalizeSessionId(outbound.chatId)
            } else {
                normalizeSessionId(AgentLoop.currentSessionId() ?: ambientSessionId)
            }
            val transcriptSession = sessionRepository.getSession(transcriptSessionId)
                ?: SessionEntity(
                    id = transcriptSessionId,
                    title = if (transcriptSessionId == AppSession.LOCAL_SESSION_ID) {
                        AppSession.LOCAL_SESSION_TITLE
                    } else {
                        transcriptSessionId
                    },
                    createdAt = System.currentTimeMillis(),
                    updatedAt = System.currentTimeMillis()
                )
            val preparedAttachments = attachmentTransferService.prepareAssistantAttachments(
                sessionId = transcriptSession.id,
                sessionTitle = transcriptSession.title,
                messageId = System.currentTimeMillis(),
                attachments = outbound.normalizedAttachments
            )
            messageRepository.appendAssistantMessage(
                sessionId = transcriptSession.id,
                content = outbound.content,
                attachments = preparedAttachments
            )
            sessionRepository.touch(transcriptSession.id)
            if (!outbound.channel.equals("local", ignoreCase = true)) {
                deliverOutboundViaOwnedGateway(outbound.copy(attachments = preparedAttachments))
            }
        }
    }

    private fun configureRuntimeSettingsTools() {
        val getTool = RuntimeGetTool()
        getTool.setGetCallback {
            buildRuntimeSettingsSnapshot(configStore.getConfig())
        }
        runtimeGetTool = getTool
        syncManagedTool(getTool)

        val setTool = RuntimeSetTool()
        setTool.setSetCallback { request ->
            persistRuntimeSettings(request)
        }
        runtimeSetTool = setTool
        syncManagedTool(setTool)
    }

    private fun configureHeartbeatTools() {
        val getTool = com.palmclaw.tools.HeartbeatGetTool()
        getTool.setGetCallback {
            buildHeartbeatSettingsSnapshot(configStore.getHeartbeatConfig())
        }
        heartbeatGetTool = getTool
        syncManagedTool(getTool)

        val setTool = com.palmclaw.tools.HeartbeatSetTool()
        setTool.setSetCallback { request ->
            persistHeartbeatSettings(request)
        }
        heartbeatSetTool = setTool
        syncManagedTool(setTool)

        val triggerTool = com.palmclaw.tools.HeartbeatTriggerTool()
        triggerTool.setTriggerCallback {
            triggerHeartbeatNowFromTool()
        }
        heartbeatTriggerTool = triggerTool
        syncManagedTool(triggerTool)
    }
    private fun configureChannelsTools() {
        val getTool = ChannelsGetTool()
        getTool.setGetCallback {
            buildChannelBindingsSnapshotForTool()
        }
        channelsGetTool = getTool
        syncManagedTool(getTool)

        val setTool = ChannelsSetTool()
        setTool.setSetCallback { request ->
            setSessionChannelEnabledInternal(
                sessionId = request.sessionId,
                sessionTitle = request.sessionTitle,
                enabled = request.enabled
            )
        }
        channelsSetTool = setTool
        syncManagedTool(setTool)
    }

    private fun configureMcpStatusTool() {
        val tool = McpStatusTool()
        tool.setGetCallback {
            buildMcpStatusSnapshot()
        }
        mcpStatusTool = tool
        syncManagedTool(tool)
    }

    private fun ensureMcpStatusToolRegistered() {
        val tool = mcpStatusTool ?: return
        syncManagedTool(tool)
    }

    private fun configureSessionsSendTool() {
        val tool = SessionsSendTool()
        tool.setSendCallback { request ->
            deliverMessageToSessionFromTool(request)
        }
        sessionsSendTool = tool
        syncManagedTool(tool)
    }

    private fun configureSessionsListTool() {
        val tool = SessionsListTool()
        tool.setListCallback {
            buildSessionsSnapshotForTool()
        }
        sessionsListTool = tool
        syncManagedTool(tool)
    }

    private fun configureSpawnTool() {
        val manager = SubagentManager(
            agentLoop = agentLoop,
            messageRepository = messageRepository,
            sessionRepository = sessionRepository,
            workspaceManager = workspaceManager,
            publishOutbound = { outbound ->
                deliverOutboundViaOwnedGateway(outbound)
            }
        )
        val tool = SpawnTool(manager)
        subagentManager = manager
        spawnTool = tool
        syncManagedTool(tool)
    }

    private suspend fun deliverMessageToSessionFromTool(
        request: SessionsSendTool.Request
    ): SessionsSendTool.DeliveryResult {
        val target = resolveSessionForToolTarget(
            sessionId = request.sessionId,
            sessionTitle = request.sessionTitle
        ) ?: throw IllegalArgumentException("target session not found")

        val normalizedAttachments = normalizeMessageAttachments(
            attachments = request.attachments,
            legacyMedia = request.media
        )
        val preparedAttachments = attachmentTransferService.prepareAssistantAttachments(
            sessionId = target.id,
            sessionTitle = target.title,
            messageId = System.currentTimeMillis(),
            attachments = normalizedAttachments
        )
        messageRepository.appendAssistantMessage(
            sessionId = target.id,
            content = request.content,
            attachments = preparedAttachments
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
        var deliveryNote: String? = null
        if (binding != null) {
            val outbound = OutboundMessage(
                channel = binding.channel,
                chatId = binding.chatId,
                content = request.content,
                attachments = preparedAttachments,
                media = request.media,
                metadata = buildAdapterMetadata(adapterKeyForBinding(binding))
            )
            if (isRemoteAttachmentDeliverySupported(outbound)) {
                deliverOutboundViaOwnedGateway(outbound)
                remoteDelivered = true
            } else {
                deliveryNote =
                    "${binding.channel} remote attachment delivery is not supported in the current adapter mode. The local session message was kept."
            }
        }
        if (deliveryNote == null && request.deliverRemote && rawBinding?.channel?.trim()?.equals("wecom", ignoreCase = true) == true) {
            deliveryNote =
                "WeCom remote delivery is reply-context based. It only works after that WeCom chat has sent a recent inbound message; local context is kept until app restart and up to 7 days."
        }

        return SessionsSendTool.DeliveryResult(
            sessionId = target.id,
            sessionTitle = target.title,
            remoteDelivered = remoteDelivered,
            note = deliveryNote
        )
    }

    private fun isRemoteAttachmentDeliverySupported(outbound: OutboundMessage): Boolean {
        if (outbound.normalizedAttachments.isEmpty()) return true
        val capability = gatewayOrchestrator?.resolveOutboundAttachmentCapability(outbound)
        return capability?.supportsOutboundFiles != false
    }

    private fun requireRemoteAttachmentDeliverySupported(outbound: OutboundMessage) {
        if (!isRemoteAttachmentDeliverySupported(outbound)) {
            throw IllegalStateException(
                "${outbound.channel} does not support remote file delivery in the current adapter mode"
            )
        }
    }

    private fun buildRuntimeSettingsSnapshot(config: AppConfig): RuntimeGetTool.Snapshot {
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

    private suspend fun buildHeartbeatSettingsSnapshot(config: HeartbeatConfig): com.palmclaw.tools.HeartbeatGetTool.Snapshot {
        return com.palmclaw.tools.HeartbeatGetTool.Snapshot(
            enabled = config.enabled,
            intervalSeconds = config.intervalSeconds,
            documentContent = withContext(Dispatchers.IO) { readHeartbeatDoc() },
            lastTriggeredAtMs = configStore.getHeartbeatLastTriggeredAtMs(),
            nextTriggerAtMs = configStore.getHeartbeatNextTriggerAtMs()
        )
    }

    private suspend fun persistHeartbeatSettings(
        request: com.palmclaw.tools.HeartbeatSetTool.Request
    ): com.palmclaw.tools.HeartbeatGetTool.Snapshot {
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
        applyHeartbeatRuntimeConfig(updated)
        request.nextTriggerAtMs?.let { requested ->
            if (!updated.enabled) {
                throw IllegalStateException("Cannot set next heartbeat trigger while heartbeat is disabled")
            }
            heartbeatService.armNextAlarm(requested)
        }
        return buildHeartbeatSettingsSnapshot(updated)
    }

    private suspend fun triggerHeartbeatNowFromTool(): String {
        val cfg = configStore.getHeartbeatConfig()
        if (!cfg.enabled) {
            throw IllegalStateException("Heartbeat is disabled")
        }
        return processHeartbeatTick() ?: "Heartbeat completed with no action."
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
        return buildRuntimeSettingsSnapshot(updated)
    }

    private fun validateIntSetting(label: String, value: Int, min: Int, max: Int): Int {
        if (value !in min..max) {
            throw IllegalArgumentException("$label must be between $min and $max")
        }
        return value
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

    private fun adapterKeyForBinding(binding: SessionChannelBinding): String? {
        val channel = binding.channel.trim().lowercase(Locale.US)
        return when (channel) {
            "telegram" -> binding.telegramBotToken.trim().takeIf { it.isNotBlank() }?.let { buildAdapterKey(channel, it) }
            "discord" -> binding.discordBotToken.trim().takeIf { it.isNotBlank() }?.let { buildAdapterKey(channel, it) }
            "slack" -> {
                val botToken = binding.slackBotToken.trim()
                val appToken = binding.slackAppToken.trim()
                if (botToken.isBlank() || appToken.isBlank()) null else buildAdapterKey(channel, "$botToken|$appToken")
            }
            "feishu" -> {
                val appId = binding.feishuAppId.trim()
                val appSecret = binding.feishuAppSecret.trim()
                if (appId.isBlank() || appSecret.isBlank()) null else buildAdapterKey(
                    channel,
                    "$appId|$appSecret|${binding.feishuEncryptKey.trim()}|${binding.feishuVerificationToken.trim()}"
                )
            }
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
                ) null else buildAdapterKey(
                    channel,
                    "$imapHost|${binding.emailImapPort}|$imapUsername|$smtpHost|${binding.emailSmtpPort}|$smtpUsername|${binding.emailFromAddress.trim()}"
                )
            }
            "wecom" -> {
                val botId = binding.wecomBotId.trim()
                val secret = binding.wecomSecret.trim()
                if (botId.isBlank() || secret.isBlank()) null else buildAdapterKey(channel, "$botId|$secret")
            }
            else -> null
        }
    }
    private suspend fun resolveSessionForToolTarget(
        sessionId: String?,
        sessionTitle: String?
    ): SessionTarget? {
        val sessions = sessionRepository.listSessions().map { SessionTarget(id = it.id, title = it.title) }
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
        val bindingsBySession = configStore.getSessionChannelBindings().associateBy { it.sessionId.trim() }
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
        val activeId = ambientSessionId.trim().ifBlank { AppSession.LOCAL_SESSION_ID }
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
        return SessionsListTool.Snapshot(currentSessionId = activeId, sessions = entries)
    }

    private suspend fun buildChannelBindingsSnapshotForTool(): ChannelsGetTool.Snapshot {
        val gatewayEnabled = configStore.getChannelsConfig().enabled
        val bindingsBySession = configStore.getSessionChannelBindings().associateBy { it.sessionId.trim() }
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
                ChannelsGetTool.Entry(
                    sessionId = session.id,
                    title = session.title,
                    bindingEnabled = binding?.enabled ?: false,
                    channel = channel,
                    target = normalizedBindingTarget(binding),
                    status = resolveBindingRuntimeStatus(binding, gatewayEnabled)
                )
            }
        return ChannelsGetTool.Snapshot(
            gatewayEnabled = gatewayEnabled,
            sessions = entries
        )
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
                if (binding.slackBotToken.trim().isBlank() || binding.slackAppToken.trim().isBlank()) return "Missing bot/app token"
                if (!isSlackChannelId(normalizeSlackChannelId(target))) return "Missing channel id"
            }
            "feishu" -> {
                if (binding.feishuAppId.trim().isBlank() || binding.feishuAppSecret.trim().isBlank()) return "Missing app credentials"
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
                if (binding.wecomBotId.trim().isBlank() || binding.wecomSecret.trim().isBlank()) return "Missing bot credentials"
                if (target.isBlank()) return "Waiting for chat detection"
            }
            else -> return "Configured"
        }
        if (!gatewayEnabled) return "Gateway idle"
        val adapterKey = adapterKeyForBinding(binding) ?: return "Configured"
        val snapshot = ChannelRuntimeDiagnostics.getSnapshot(channel, adapterKey)
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
                RuntimeMcpServerStatus(status = "Not connected")
            } else {
                RuntimeMcpServerStatus(status = "Disabled")
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
        requestGatewayRuntimeConfig(runtimeConfig)
        val status = buildChannelBindingsSnapshotForTool()
            .sessions
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

    private fun wireCronCallback() {
        cronService.onJob = { job ->
            val target = resolveCronTargetSession(job.payload.sessionId)
            val targetSessionId = target.id
            val targetTitle = target.title
            val execution = executeAgentTurn(
                AgentTurnRequest(
                    sessionId = targetSessionId,
                    sessionTitle = targetTitle,
                    inputText = CronExecutionPromptBuilder.build(job),
                    inputRole = "internal_user",
                    deliveryMode = if (job.payload.deliver) {
                        AgentTurnDeliveryMode.UseSessionBinding
                    } else {
                        AgentTurnDeliveryMode.LocalOnly
                    }
                )
            )
            val runFailure = execution.failure
            if (runFailure != null) {
                Log.w(TAG, "cron onJob agent run failed", runFailure)
            }
            var response: String? = execution.latestAssistantContentIfNew()

            if (response.isNullOrBlank()) {
                val fallback = buildString {
                    append("Scheduled reminder: ")
                    append(job.payload.message.trim())
                    runFailure?.message?.takeIf { it.isNotBlank() }?.let {
                        append("\n\nAgent error: ")
                        append(it)
                    }
                }
                messageRepository.appendAssistantMessage(targetSessionId, fallback)
                response = fallback
            }

            if (job.payload.deliver) {
                runCatching {
                    mirrorLatestAssistantToBoundChannel(
                        sessionId = execution.sessionId,
                        beforeAssistantId = execution.beforeLatestAssistantId,
                        binding = execution.binding
                    )
                }.onFailure { t ->
                    Log.w(TAG, "cron remote mirror failed", t)
                }
            }
            response
        }
    }

    private suspend fun resolveCronTargetSession(requestedSessionId: String?): SessionTarget {
        val requestedId = requestedSessionId?.trim().orEmpty()
        val sessions = sessionRepository.listSessions()
        val existing = sessions.firstOrNull { it.id == requestedId }
        if (existing != null) {
            return SessionTarget(id = existing.id, title = existing.title)
        }
        if (requestedId.isNotBlank() && requestedId != AppSession.LOCAL_SESSION_ID) {
            Log.w(TAG, "Cron target session missing; falling back to local session requested=$requestedId")
        }
        val local = sessions.firstOrNull { it.id == AppSession.LOCAL_SESSION_ID }
        return SessionTarget(
            id = local?.id ?: AppSession.LOCAL_SESSION_ID,
            title = local?.title ?: AppSession.LOCAL_SESSION_TITLE
        )
    }

    private fun wireCronLogging() {
        cronService.onLog = { line ->
            cronLogStore.append(line)
        }
    }

    private suspend fun prepareLocalMessageToolTurn(sessionId: String) {
        ambientSessionId = sessionId.trim().ifBlank { AppSession.LOCAL_SESSION_ID }
        messageTool?.setContext(channel = "local", chatId = ambientSessionId)
        messageTool?.startTurn()
        spawnTool?.setContext(channel = "local", chatId = ambientSessionId, sessionKey = sessionId)
        spawnTool?.startTurn()
    }

    private suspend fun prepareMessageToolTurnForSession(sessionId: String): SessionChannelBinding? {
        ambientSessionId = sessionId.trim().ifBlank { AppSession.LOCAL_SESSION_ID }
        val binding = findSessionChannelBinding(sessionId)
        if (binding == null) {
            prepareLocalMessageToolTurn(sessionId)
            return null
        }
        messageTool?.setContext(
            channel = binding.channel,
            chatId = binding.chatId,
            adapterKey = adapterKeyForBinding(binding)
        )
        messageTool?.startTurn()
        spawnTool?.setContext(
            channel = binding.channel,
            chatId = binding.chatId,
            sessionKey = sessionId,
            adapterKey = adapterKeyForBinding(binding)
        )
        spawnTool?.startTurn()
        return binding
    }

    private suspend fun mirrorLatestAssistantToBoundChannel(
        sessionId: String,
        beforeAssistantId: Long,
        binding: SessionChannelBinding?
    ) {
        if (binding == null) return
        if (messageTool?.wasSentInCurrentTurn() == true) return
        val latest = messageRepository.getLatestAssistantMessage(sessionId) ?: return
        if (latest.id <= beforeAssistantId) return
        val text = latest.content.trim()
        val attachments = MessageAttachmentJsonCodec.decode(latest.attachmentsJson)
        if ((text.isBlank() || text == "[tool call]") && attachments.isEmpty()) return
        val outbound = OutboundMessage(
            channel = binding.channel,
            chatId = binding.chatId,
            content = text,
            attachments = attachments,
            metadata = buildAdapterMetadata(adapterKeyForBinding(binding))
        )
        if (!isRemoteAttachmentDeliverySupported(outbound)) {
            Log.w(
                TAG,
                "Skip remote assistant attachment mirror for channel=${binding.channel}: outbound files are not supported in the current adapter mode"
            )
            return
        }
        deliverOutboundViaOwnedGateway(outbound)
    }

    private suspend fun finishMessageToolTurn() {
        runCatching { messageTool?.finishTurn() }
        runCatching { spawnTool?.finishTurn() }
    }

    /**
     * Centralizes agent-turn setup and teardown so user turns, heartbeat runs,
     * and cron executions all follow the same session lifecycle rules.
     */
    private suspend fun executeAgentTurn(request: AgentTurnRequest): AgentTurnExecution {
        val normalizedSessionId = normalizeSessionId(request.sessionId)
        val normalizedTitle = normalizedSessionTitle(normalizedSessionId, request.sessionTitle)
        val normalizedInput = request.inputText.trim()
        val normalizedAttachments = request.inputAttachments
        require(normalizedInput.isNotBlank() || normalizedAttachments.isNotEmpty()) {
            "message text and attachments are both blank"
        }

        ensureSessionAvailable(
            sessionId = normalizedSessionId,
            sessionTitle = normalizedTitle,
            requireExistingSession = request.requireExistingSession
        )
        sessionRepository.touch(normalizedSessionId)

        val beforeLatestAssistantId =
            messageRepository.getLatestAssistantMessage(normalizedSessionId)?.id ?: 0L
        var binding: SessionChannelBinding? = null
        var failure: Throwable? = null
        try {
            binding = when (request.deliveryMode) {
                AgentTurnDeliveryMode.UseSessionBinding -> prepareMessageToolTurnForSession(normalizedSessionId)
                AgentTurnDeliveryMode.LocalOnly -> {
                    prepareLocalMessageToolTurn(normalizedSessionId)
                    null
                }
            }
            ambientSessionId = normalizedSessionId
            agentLoop.run(
                sessionId = normalizedSessionId,
                newUserText = normalizedInput,
                inputAttachments = normalizedAttachments,
                inputRole = request.inputRole
            )
            sessionRepository.touch(normalizedSessionId)
        } catch (t: Throwable) {
            failure = t
        } finally {
            finishMessageToolTurn()
            ambientSessionId = AppSession.LOCAL_SESSION_ID
        }

        val latestAssistant = messageRepository.getLatestAssistantMessage(normalizedSessionId)
        return AgentTurnExecution(
            sessionId = normalizedSessionId,
            beforeLatestAssistantId = beforeLatestAssistantId,
            latestAssistantId = latestAssistant?.id ?: 0L,
            latestAssistantContent = latestAssistant?.content,
            latestAssistantAttachments = MessageAttachmentJsonCodec.decode(latestAssistant?.attachmentsJson),
            binding = binding,
            failure = failure
        )
    }

    private suspend fun ensureSessionAvailable(
        sessionId: String,
        sessionTitle: String,
        requireExistingSession: Boolean
    ) {
        if (requireExistingSession) {
            require(sessionRepository.getSession(sessionId) != null) { "session not found" }
            withContext(Dispatchers.IO) {
                workspaceManager.ensureWorkspace(sessionId, sessionTitle)
            }
            return
        }
        sessionRepository.ensureSessionExists(sessionId, sessionTitle)
        withContext(Dispatchers.IO) {
            workspaceManager.ensureWorkspace(sessionId, sessionTitle)
        }
    }

    private suspend fun ensureLocalSessionWorkspace() {
        sessionRepository.ensureSessionExists(
            AppSession.LOCAL_SESSION_ID,
            AppSession.LOCAL_SESSION_TITLE
        )
        sessionRepository.touch(AppSession.LOCAL_SESSION_ID)
        withContext(Dispatchers.IO) {
            workspaceManager.ensureWorkspace(
                AppSession.LOCAL_SESSION_ID,
                AppSession.LOCAL_SESSION_TITLE
            )
        }
    }

    private fun buildWorkspaceContext(sessionId: String): ContextBuilder.WorkspaceContext? {
        val normalizedSessionId = normalizeSessionId(sessionId)
        val snapshot = workspaceManager.getSnapshot(normalizedSessionId)
            ?: workspaceManager.ensureWorkspace(
                normalizedSessionId,
                normalizedSessionTitle(normalizedSessionId, normalizedSessionId)
            )
        return ContextBuilder.WorkspaceContext(
            workspaceRoot = snapshot.workspaceRoot,
            docsDir = snapshot.docsDir,
            scratchDir = snapshot.scratchDir,
            artifactsDir = snapshot.artifactsDir,
            sharedWorkspaceRoot = workspaceManager.sharedWorkspaceRoot().absolutePath
        )
    }

    private fun normalizeSessionId(raw: String): String {
        return raw.trim().ifBlank { AppSession.LOCAL_SESSION_ID }
    }

    private fun normalizedSessionTitle(sessionId: String, rawTitle: String): String {
        return rawTitle.trim().ifBlank {
            if (sessionId == AppSession.LOCAL_SESSION_ID) {
                AppSession.LOCAL_SESSION_TITLE
            } else {
                sessionId
            }
        }
    }

    private fun findSessionChannelBinding(sessionId: String): SessionChannelBinding? {
        val sid = sessionId.trim()
        if (sid.isBlank()) return null
        val raw = configStore.getSessionChannelBindings().firstOrNull { it.sessionId.trim() == sid } ?: return null
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
                    discordAllowedUserIds = raw.discordAllowedUserIds.map { it.trim() }.filter { it.isNotBlank() }
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
                    slackAllowedUserIds = raw.slackAllowedUserIds.map { it.trim() }.filter { it.isNotBlank() }
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
                    feishuAllowedOpenIds = raw.feishuAllowedOpenIds.map { it.trim() }.filter { it.isNotBlank() }
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
                    wecomAllowedUserIds = raw.wecomAllowedUserIds.map { it.trim() }.filter { it.isNotBlank() }
                )
            }
            else -> null
        }
    }

    private fun onGatewaySessionProcessingChanged(sessionId: String, processing: Boolean) {
        val sid = sessionId.trim().ifBlank { AppSession.LOCAL_SESSION_ID }
        var deferredConfig: ChannelsConfig? = null
        synchronized(gatewayProcessingSessions) {
            if (processing) {
                gatewayProcessingSessions.add(sid)
                ambientSessionId = sid
            } else {
                gatewayProcessingSessions.remove(sid)
                if (gatewayProcessingSessions.isEmpty()) {
                    ambientSessionId = AppSession.LOCAL_SESSION_ID
                    deferredConfig = pendingGatewayConfig
                    pendingGatewayConfig = null
                }
            }
            Unit
        }
        updateState()
        if (deferredConfig != null) {
            runtimeScope.launch {
                applyGatewayRuntimeConfig(deferredConfig!!)
            }
        }
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

    private fun hasActiveGatewayBinding(bindings: List<SessionChannelBinding>): Boolean {
        return bindings.any { raw ->
            if (!raw.enabled) return@any false
            val channel = raw.channel.trim().lowercase(Locale.US)
            val chatId = raw.chatId.trim()
            if (channel.isBlank()) return@any false
            when (channel) {
                "telegram" -> raw.telegramBotToken.trim().isNotBlank() && chatId.isNotBlank()
                "discord" -> raw.discordBotToken.trim().isNotBlank() && isDiscordSnowflake(chatId)
                "slack" -> raw.slackBotToken.trim().isNotBlank() && raw.slackAppToken.trim().isNotBlank() && isSlackChannelId(normalizeSlackChannelId(chatId))
                "feishu" -> raw.feishuAppId.trim().isNotBlank() && raw.feishuAppSecret.trim().isNotBlank()
                "email" -> raw.emailConsentGranted && raw.emailImapHost.trim().isNotBlank() && raw.emailImapUsername.trim().isNotBlank() && raw.emailImapPassword.isNotBlank() && raw.emailSmtpHost.trim().isNotBlank() && raw.emailSmtpUsername.trim().isNotBlank() && raw.emailSmtpPassword.isNotBlank()
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
            "feishu" -> com.palmclaw.channels.buildFeishuTargetAliases(
                primaryTargetId = message.chatId,
                sourceChatId = message.metadata["source_chat_id"].orEmpty(),
                senderOpenId = message.metadata["sender_open_id"].orEmpty()
            )
            "email" -> listOf(normalizeEmailAddress(message.chatId))
            "wecom" -> listOf(normalizeWeComTargetId(message.chatId))
            else -> listOf(message.chatId.trim())
        }.filter { it.isNotBlank() }
        if (c.isBlank() || targetIds.isEmpty()) return null
        val adapterKey = message.metadata[GatewayOrchestrator.KEY_ADAPTER_KEY]?.trim()?.ifBlank { null }
        val bindings = configStore.getSessionChannelBindings()
        val exact = bindings.firstOrNull {
            val channelMatches = it.enabled && it.channel.trim().lowercase(Locale.US) == c
            if (!channelMatches) return@firstOrNull false
            if (it.chatId.trim() !in targetIds) return@firstOrNull false
            if (adapterKey == null) return@firstOrNull false
            adapterKeyForBinding(it) == adapterKey
        }
        if (exact != null) return exact.sessionId.trim().ifBlank { null }
        return bindings.firstOrNull {
            it.enabled && it.channel.trim().lowercase(Locale.US) == c && it.chatId.trim() in targetIds
        }?.sessionId?.trim()?.ifBlank { null }
    }

    private fun applyCronRuntimeConfig(config: CronConfig) {
        cronService.updatePolicy(
            minEveryMs = config.minEveryMs,
            maxJobs = config.maxJobs,
            logEnabled = config.enabled
        )
        if (config.enabled) cronService.start() else cronService.stop()
    }

    private suspend fun persistCronSettings(update: CronConfigUpdate): CronConfig {
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
        applyCronRuntimeConfig(config)
        return config
    }

    private suspend fun setCronEnabledFromTool(enabled: Boolean) {
        persistCronSettings(CronConfigUpdate(enabled = enabled))
    }

    private fun applyHeartbeatRuntimeConfig(config: HeartbeatConfig) {
        heartbeatService.updateConfig(enabled = config.enabled, intervalSeconds = config.intervalSeconds)
        if (config.enabled) heartbeatService.start() else heartbeatService.stop()
    }

    private fun applyGatewayRuntimeConfig(config: ChannelsConfig) {
        val sessionBindings = configStore.getSessionChannelBindings()
        val shouldEnableGateway = hasActiveGatewayBinding(sessionBindings)
        val effectiveConfig = if (config.enabled == shouldEnableGateway) {
            config
        } else {
            config.copy(enabled = shouldEnableGateway).also { configStore.saveChannelsConfig(it) }
        }
        if (!effectiveConfig.enabled) {
            gatewayOrchestrator?.stop()
            gatewayOrchestrator = null
            synchronized(gatewayProcessingSessions) {
                gatewayProcessingSessions.clear()
            }
            updateState(gatewayRunning = false, activeAdapterCount = 0, lastError = "")
            return
        }

        val adapters = buildAdapters(sessionBindings)
        if (adapters.isEmpty()) {
            gatewayOrchestrator?.stop()
            gatewayOrchestrator = null
            synchronized(gatewayProcessingSessions) {
                gatewayProcessingSessions.clear()
            }
            val lastError = if (sessionBindings.any { it.enabled && it.channel.trim().isNotBlank() }) {
                "No active adapter could start. Check credentials and target IDs."
            } else {
                ""
            }
            updateState(gatewayRunning = false, activeAdapterCount = 0, lastError = lastError)
            return
        }

        val existing = gatewayOrchestrator
        if (existing != null) {
            existing.reconfigure(adapters)
            updateState(gatewayRunning = true, activeAdapterCount = existing.adapterCount, lastError = "")
            return
        }

        gatewayOrchestrator = GatewayOrchestrator(
            bus = gatewayBus,
            agentLoop = agentLoop,
            messageRepository = messageRepository,
            sessionRepository = sessionRepository,
            attachmentTransferService = attachmentTransferService,
            sessionResolver = { inbound -> resolveGatewaySessionBinding(inbound) },
            onSessionProcessingChanged = { sessionId, processing -> onGatewaySessionProcessingChanged(sessionId, processing) },
            messageTool = messageTool,
            spawnTool = spawnTool,
            adapters = adapters
        ).also {
            it.start()
            updateState(gatewayRunning = true, activeAdapterCount = it.adapterCount, lastError = "")
        }
    }

    private fun buildAdapters(bindings: List<SessionChannelBinding>): List<ChannelAdapter> {
        val activeBindings = bindings.filter { it.enabled }
        val telegramBindings = activeBindings.filter { it.channel.trim().equals("telegram", ignoreCase = true) }.mapNotNull { binding ->
            val token = binding.telegramBotToken.trim()
            val chatId = binding.chatId.trim()
            if (token.isBlank() || chatId.isBlank()) null else binding.copy(
                channel = "telegram",
                chatId = chatId,
                telegramBotToken = token,
                telegramAllowedChatId = binding.telegramAllowedChatId?.trim()?.ifBlank { null }
            )
        }
        val discordBindings = activeBindings.filter { it.channel.trim().equals("discord", ignoreCase = true) }.mapNotNull { binding ->
            val token = binding.discordBotToken.trim()
            val chatId = binding.chatId.trim()
            if (token.isBlank() || chatId.isBlank() || !isDiscordSnowflake(chatId)) null else binding.copy(
                channel = "discord",
                chatId = chatId,
                discordBotToken = token,
                discordResponseMode = normalizeDiscordResponseMode(binding.discordResponseMode),
                discordAllowedUserIds = binding.discordAllowedUserIds.map { it.trim() }.filter { it.isNotBlank() }
            )
        }
        val slackBindings = activeBindings.filter { it.channel.trim().equals("slack", ignoreCase = true) }.mapNotNull { binding ->
            val botToken = binding.slackBotToken.trim()
            val appToken = binding.slackAppToken.trim()
            val chatId = normalizeSlackChannelId(binding.chatId)
            if (botToken.isBlank() || appToken.isBlank() || chatId.isBlank() || !isSlackChannelId(chatId)) null else binding.copy(
                channel = "slack",
                chatId = chatId,
                slackBotToken = botToken,
                slackAppToken = appToken,
                slackResponseMode = normalizeSlackResponseMode(binding.slackResponseMode),
                slackAllowedUserIds = binding.slackAllowedUserIds.map { it.trim() }.filter { it.isNotBlank() }
            )
        }
        val feishuBindings = activeBindings.filter { it.channel.trim().equals("feishu", ignoreCase = true) }.mapNotNull { binding ->
            val appId = binding.feishuAppId.trim()
            val appSecret = binding.feishuAppSecret.trim()
            val chatId = normalizeFeishuTargetId(binding.chatId)
            if (appId.isBlank() || appSecret.isBlank()) null else binding.copy(
                channel = "feishu",
                chatId = chatId,
                feishuAppId = appId,
                feishuAppSecret = appSecret,
                feishuEncryptKey = binding.feishuEncryptKey.trim(),
                feishuVerificationToken = binding.feishuVerificationToken.trim(),
                feishuAllowedOpenIds = binding.feishuAllowedOpenIds.map { it.trim() }.filter { it.isNotBlank() }
            )
        }
        val emailBindings = activeBindings.filter { it.channel.trim().equals("email", ignoreCase = true) }.mapNotNull { binding ->
            val imapHost = binding.emailImapHost.trim()
            val imapUsername = binding.emailImapUsername.trim()
            val imapPassword = binding.emailImapPassword
            val smtpHost = binding.emailSmtpHost.trim()
            val smtpUsername = binding.emailSmtpUsername.trim()
            val smtpPassword = binding.emailSmtpPassword
            if (!binding.emailConsentGranted || imapHost.isBlank() || imapUsername.isBlank() || imapPassword.isBlank() || smtpHost.isBlank() || smtpUsername.isBlank() || smtpPassword.isBlank()) null else binding.copy(
                channel = "email",
                chatId = normalizeEmailAddress(binding.chatId),
                emailImapHost = imapHost,
                emailImapPort = binding.emailImapPort.coerceIn(1, 65535),
                emailImapUsername = imapUsername,
                emailImapPassword = imapPassword,
                emailSmtpHost = smtpHost,
                emailSmtpPort = binding.emailSmtpPort.coerceIn(1, 65535),
                emailSmtpUsername = smtpUsername,
                emailSmtpPassword = smtpPassword,
                emailFromAddress = normalizeEmailAddress(binding.emailFromAddress)
            )
        }
        val wecomBindings = activeBindings.filter { it.channel.trim().equals("wecom", ignoreCase = true) }.mapNotNull { binding ->
            val botId = binding.wecomBotId.trim()
            val secret = binding.wecomSecret.trim()
            val chatId = normalizeWeComTargetId(binding.chatId)
            if (botId.isBlank() || secret.isBlank()) null else binding.copy(
                channel = "wecom",
                chatId = chatId,
                wecomBotId = botId,
                wecomSecret = secret,
                wecomAllowedUserIds = binding.wecomAllowedUserIds.map { it.trim() }.filter { it.isNotBlank() }
            )
        }

        return buildList {
            telegramBindings.groupBy { it.telegramBotToken }.forEach { (token, grouped) ->
                val allowed = buildSet {
                    grouped.map { it.chatId.trim() }.filter { it.isNotBlank() }.forEach { add(it) }
                    grouped.mapNotNull { it.telegramAllowedChatId?.trim()?.ifBlank { null } }.forEach { add(it) }
                }
                add(TelegramChannelAdapter(adapterKey = buildAdapterKey("telegram", token), botToken = token, allowedChatIds = allowed))
            }
            discordBindings.groupBy { it.discordBotToken }.forEach { (token, grouped) ->
                val allowedChannels = grouped.map { it.chatId }.distinct().toSet()
                val routeRules = grouped.associate { binding ->
                    binding.chatId to DiscordRouteRule(
                        responseMode = normalizeDiscordResponseMode(binding.discordResponseMode),
                        allowedUserIds = binding.discordAllowedUserIds.asSequence().map { it.trim() }.filter { it.isNotBlank() }.toSet()
                    )
                }
                add(DiscordChannelAdapter(adapterKey = buildAdapterKey("discord", token), botToken = token, allowedChannelIds = allowedChannels, routeRules = routeRules))
            }
            slackBindings.groupBy { it.slackBotToken to it.slackAppToken }.forEach { (pair, grouped) ->
                val (botToken, appToken) = pair
                val allowedChannels = grouped.map { it.chatId }.distinct().toSet()
                val routeRules = grouped.associate { binding ->
                    binding.chatId to SlackRouteRule(
                        responseMode = normalizeSlackResponseMode(binding.slackResponseMode),
                        allowedUserIds = binding.slackAllowedUserIds.asSequence().map { it.trim() }.filter { it.isNotBlank() }.toSet()
                    )
                }
                add(SlackChannelAdapter(adapterKey = buildAdapterKey("slack", "$botToken|$appToken"), botToken = botToken, appToken = appToken, allowedChannelIds = allowedChannels, routeRules = routeRules))
            }
            feishuBindings.groupBy { Quadruple(it.feishuAppId, it.feishuAppSecret, it.feishuEncryptKey, it.feishuVerificationToken) }.forEach { (creds, grouped) ->
                val allowedTargets = grouped.map { it.chatId }.filter { it.isNotBlank() }.distinct().toSet()
                val routeRules = grouped.filter { it.chatId.isNotBlank() }.associate { binding ->
                    binding.chatId to FeishuRouteRule(
                        responseMode = normalizeFeishuResponseMode(binding.feishuResponseMode),
                        allowedOpenIds = binding.feishuAllowedOpenIds.asSequence().map { it.trim() }.filter { it.isNotBlank() }.toSet()
                    )
                }
                add(FeishuChannelAdapter(adapterKey = buildAdapterKey("feishu", "${creds.first}|${creds.second}|${creds.third}|${creds.fourth}"), appId = creds.first, appSecret = creds.second, encryptKey = creds.third, verificationToken = creds.fourth, allowedChatTargets = allowedTargets, routeRules = routeRules))
            }
            emailBindings.groupBy {
                EmailCredentialKey(
                    consentGranted = it.emailConsentGranted,
                    imapHost = it.emailImapHost,
                    imapPort = it.emailImapPort,
                    imapUsername = it.emailImapUsername,
                    imapPassword = it.emailImapPassword,
                    smtpHost = it.emailSmtpHost,
                    smtpPort = it.emailSmtpPort,
                    smtpUsername = it.emailSmtpUsername,
                    smtpPassword = it.emailSmtpPassword,
                    fromAddress = it.emailFromAddress,
                    autoReplyEnabled = it.emailAutoReplyEnabled
                )
            }.forEach { (creds, _) ->
                add(EmailChannelAdapter(context = app, adapterKey = buildAdapterKey("email", "${creds.imapHost}|${creds.imapPort}|${creds.imapUsername}|${creds.smtpHost}|${creds.smtpPort}|${creds.smtpUsername}|${creds.fromAddress}"), config = EmailAccountConfig(consentGranted = creds.consentGranted, imapHost = creds.imapHost, imapPort = creds.imapPort, imapUsername = creds.imapUsername, imapPassword = creds.imapPassword, smtpHost = creds.smtpHost, smtpPort = creds.smtpPort, smtpUsername = creds.smtpUsername, smtpPassword = creds.smtpPassword, fromAddress = creds.fromAddress, autoReplyEnabled = creds.autoReplyEnabled)))
            }
            wecomBindings.groupBy { it.wecomBotId to it.wecomSecret }.forEach { (creds, grouped) ->
                val allowedTargets = grouped.map { it.chatId }.filter { it.isNotBlank() }.distinct().toSet()
                val routeRules = grouped.filter { it.chatId.isNotBlank() }.associate { binding ->
                    binding.chatId to WeComRouteRule(
                        allowedUserIds = binding.wecomAllowedUserIds.asSequence().map { it.trim() }.filter { it.isNotBlank() }.toSet()
                    )
                }
                add(WeComChannelAdapter(context = app, adapterKey = buildAdapterKey("wecom", "${creds.first}|${creds.second}"), botId = creds.first, secret = creds.second, allowedChatTargets = allowedTargets, routeRules = routeRules))
            }
        }
    }

    private fun applyMcpRuntimeConfig(config: McpHttpConfig) {
        runtimeScope.launch {
            runCatching {
                toolRegistry.unregisterByPrefix("mcp_")
                ensureMcpStatusToolRegistered()
                mcpRuntimes.forEach { runCatching { it.close() } }
                mcpRuntimes.clear()
                val servers = config.servers.ifEmpty {
                    if (config.serverUrl.isNotBlank()) listOf(McpHttpServerConfig(id = "mcp_1", serverName = config.serverName, serverUrl = config.serverUrl, authToken = config.authToken, toolTimeoutSeconds = config.toolTimeoutSeconds)) else emptyList()
                }
                if (!config.enabled) {
                    mcpServerStatuses = servers.associate { normalizeMcpRuntimeServerName(it.serverName) to RuntimeMcpServerStatus(status = "Disabled") }
                    return@runCatching
                }
                require(servers.isNotEmpty()) { "Enable MCP requires at least one configured server." }
                val failures = mutableListOf<String>()
                val runtimeStatuses = linkedMapOf<String, RuntimeMcpServerStatus>()
                servers.forEach { server ->
                    val runtimeName = normalizeMcpRuntimeServerName(server.serverName)
                    runCatching {
                        McpHttpRuntime.connect(
                            McpHttpConfig(enabled = true, serverName = server.serverName, serverUrl = server.serverUrl, authToken = server.authToken, toolTimeoutSeconds = server.toolTimeoutSeconds),
                            toolRegistry
                        )
                    }.onSuccess { runtime ->
                        mcpRuntimes += runtime
                        val toolCount = runtime.registeredToolNames.size
                        runtimeStatuses[runtimeName] = RuntimeMcpServerStatus(
                            status = "Connected",
                            usable = toolCount > 0,
                            detail = if (toolCount == 0) "Connected, but no MCP tools were discovered." else "",
                            toolCount = toolCount,
                            toolNames = runtime.registeredToolNames.sorted()
                        )
                    }.onFailure { t ->
                        failures += "${server.serverName}: ${t.message ?: t.javaClass.simpleName}"
                        runtimeStatuses[runtimeName] = RuntimeMcpServerStatus(status = "Error", detail = t.message ?: t.javaClass.simpleName)
                    }
                }
                mcpServerStatuses = runtimeStatuses
                ensureMcpStatusToolRegistered()
                require(mcpRuntimes.isNotEmpty()) { failures.joinToString(" | ").ifBlank { "MCP connect failed." } }
                if (failures.isNotEmpty()) {
                    Log.w(TAG, "MCP partial failures: ${failures.joinToString(" | ")}")
                }
            }.onFailure { t ->
                ensureMcpStatusToolRegistered()
                Log.e(TAG, "MCP connect failed", t)
            }
        }
    }

    private fun updateState(
        gatewayRunning: Boolean = runtimeState.gatewayRunning,
        activeAdapterCount: Int = runtimeState.activeAdapterCount,
        lastError: String = runtimeState.lastError
    ) {
        val next = GatewayRuntimeState(
            gatewayRunning = gatewayRunning,
            activeAdapterCount = activeAdapterCount,
            lastError = lastError,
            processingSessionIds = synchronized(gatewayProcessingSessions) {
                gatewayProcessingSessions.toSet()
            }
        )
        runtimeState = next
        onStateChanged(next)
    }

    private fun validateMcpEndpointUrl(url: String) {
        if (url.isBlank()) throw IllegalArgumentException("MCP server URL is required when MCP is enabled")
        val parsed = url.toHttpUrlOrNull() ?: throw IllegalArgumentException("MCP server URL is invalid")
        val scheme = parsed.scheme.lowercase(Locale.US)
        if (scheme != "http" && scheme != "https") throw IllegalArgumentException("MCP server URL must use http or https")
        if (scheme == "http" && !isLocalMcpHost(parsed.host)) {
            throw IllegalArgumentException("Use HTTPS for non-local MCP endpoints")
        }
    }

    private suspend fun decideHeartbeat(content: String): HeartbeatDecision {
        val provider = providerFactory.create(configStore.getConfig())
        val response = provider.chat(
            messages = listOf(
                ChatMessage(
                    role = "system",
                    content = "You are a heartbeat agent. Call heartbeat tool with action=skip or run."
                ),
                ChatMessage(
                    role = "user",
                    content = "Review HEARTBEAT.md and decide if there are active tasks.\n\n$content"
                )
            ),
            toolsSpec = listOf(heartbeatToolSpec())
        )
        val call = response.assistant.toolCalls.firstOrNull { it.name == HEARTBEAT_TOOL_NAME }
            ?: return HeartbeatDecision(action = HEARTBEAT_ACTION_SKIP, tasks = "")
        return runCatching {
            val args = JSONObject(call.argumentsJson)
            val action = args.optString("action").lowercase(Locale.US)
            val tasks = args.optString("tasks")
            when (action) {
                HEARTBEAT_ACTION_RUN -> HeartbeatDecision(action = HEARTBEAT_ACTION_RUN, tasks = tasks)
                else -> HeartbeatDecision(action = HEARTBEAT_ACTION_SKIP, tasks = "")
            }
        }.getOrElse {
            HeartbeatDecision(action = HEARTBEAT_ACTION_SKIP, tasks = "")
        }
    }

    private fun heartbeatToolSpec(): ToolSpec {
        return ToolSpec(
            name = HEARTBEAT_TOOL_NAME,
            description = "Report heartbeat decision after reviewing HEARTBEAT.md.",
            parameters = buildJsonObject {
                put("type", "object")
                put("required", buildJsonArray { add(JsonPrimitive("action")) })
                put("properties", buildJsonObject {
                    put("action", buildJsonObject {
                        put("type", "string")
                        put("enum", buildJsonArray {
                            add(JsonPrimitive(HEARTBEAT_ACTION_SKIP))
                            add(JsonPrimitive(HEARTBEAT_ACTION_RUN))
                        })
                    })
                    put("tasks", buildJsonObject { put("type", "string") })
                })
            }
        )
    }

    private fun parseHeartbeatTasks(content: String): ParsedHeartbeatTasks {
        val lines = content.replace("\r\n", "\n").lines()
        val activeIndex = lines.indexOfFirst { it.trim().equals("## Active Tasks", ignoreCase = true) }
        if (activeIndex < 0) {
            return ParsedHeartbeatTasks(hasActiveSection = false, tasks = "")
        }
        val sectionLines = lines.drop(activeIndex + 1).takeWhile { !it.trim().startsWith("## ") }
        val cleaned = cleanHeartbeatTaskLines(sectionLines)
        return ParsedHeartbeatTasks(hasActiveSection = true, tasks = cleaned.joinToString("\n").trim())
    }

    private fun cleanHeartbeatTaskLines(lines: List<String>): List<String> {
        val result = mutableListOf<String>()
        var inComment = false
        for (raw in lines) {
            var line = raw
            if (inComment) {
                val end = line.indexOf("-->")
                if (end < 0) continue
                line = line.substring(end + 3)
                inComment = false
            }
            while (true) {
                val start = line.indexOf("<!--")
                if (start < 0) break
                val end = line.indexOf("-->", start + 4)
                line = if (end >= 0) {
                    line.removeRange(start, end + 3)
                } else {
                    inComment = true
                    line.substring(0, start)
                }
            }
            val text = line.trim()
            if (text.isBlank()) continue
            if (text.startsWith("#")) continue
            if (text == "---" || text == "***" || text == "___") continue
            result += text
        }
        return result
    }
    private fun readHeartbeatDoc(): String {
        heartbeatDocFile.parentFile?.mkdirs()
        if (!heartbeatDocFile.exists()) {
            heartbeatDocFile.writeText(templateStore.loadTemplate(HeartbeatDoc.FILE_NAME).orEmpty(), Charsets.UTF_8)
        }
        return runCatching { heartbeatDocFile.readText(Charsets.UTF_8) }
            .getOrDefault(templateStore.loadTemplate(HeartbeatDoc.FILE_NAME).orEmpty())
    }

    private fun normalizeDiscordChannelId(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isBlank()) return ""
        val mentionMatch = Regex("^<#(\\d+)>$").matchEntire(trimmed)
        if (mentionMatch != null) return mentionMatch.groupValues.getOrNull(1).orEmpty()
        val digits = trimmed.filter { it.isDigit() }
        return if (digits.length in 15..30) digits else trimmed
    }

    private fun normalizeDiscordResponseMode(raw: String): String = if (raw.trim().lowercase(Locale.US) == "open") "open" else "mention"

    private fun normalizeSlackChannelId(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isBlank()) return ""
        val mentionMatch = Regex("^<#([A-Za-z0-9]+)(?:\\|[^>]+)?>$").matchEntire(trimmed)
        if (mentionMatch != null) return mentionMatch.groupValues.getOrNull(1).orEmpty().uppercase(Locale.US)
        val detected = Regex("([CDG][A-Za-z0-9]{8,})").find(trimmed)?.groupValues?.getOrNull(1)
        return (detected ?: trimmed).trim().uppercase(Locale.US)
    }

    private fun normalizeSlackResponseMode(raw: String): String = if (raw.trim().lowercase(Locale.US) == "open") "open" else "mention"
    private fun normalizeFeishuResponseMode(raw: String): String = if (raw.trim().lowercase(Locale.US) == "open") "open" else "mention"
    private fun normalizeFeishuTargetId(raw: String): String = (Regex("((?:ou|oc)_[A-Za-z0-9_-]+)").find(raw.trim())?.groupValues?.getOrNull(1) ?: raw.trim()).trim()
    private fun normalizeWeComTargetId(raw: String): String = raw.trim()
    private fun normalizeEmailAddress(raw: String): String = raw.trim().lowercase(Locale.US)
    private fun isDiscordSnowflake(value: String): Boolean = value.length in 15..30 && value.all { it.isDigit() }
    private fun isSlackChannelId(value: String): Boolean {
        val normalized = value.trim().uppercase(Locale.US)
        if (normalized.length !in 9..30) return false
        if (!(normalized.startsWith("C") || normalized.startsWith("D") || normalized.startsWith("G"))) return false
        return normalized.all { it.isLetterOrDigit() }
    }
    private fun isFeishuTargetId(value: String): Boolean {
        val normalized = value.trim()
        return normalized.startsWith("ou_") || normalized.startsWith("oc_")
    }
    private fun isEmailAddress(value: String): Boolean = value.trim().isNotBlank() && android.util.Patterns.EMAIL_ADDRESS.matcher(value.trim()).matches()
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

    private data class SessionTarget(val id: String, val title: String)
    private enum class AgentTurnDeliveryMode { UseSessionBinding, LocalOnly }
    private data class AgentTurnRequest(
        val sessionId: String,
        val sessionTitle: String,
        val inputText: String,
        val inputAttachments: List<MessageAttachment> = emptyList(),
        val inputRole: String = "user",
        val deliveryMode: AgentTurnDeliveryMode,
        val requireExistingSession: Boolean = false
    )
    private data class AgentTurnExecution(
        val sessionId: String,
        val beforeLatestAssistantId: Long,
        val latestAssistantId: Long,
        val latestAssistantContent: String?,
        val latestAssistantAttachments: List<MessageAttachment> = emptyList(),
        val binding: SessionChannelBinding?,
        val failure: Throwable? = null
    ) {
        fun latestAssistantContentIfNew(): String? {
            return if (latestAssistantId > beforeLatestAssistantId) latestAssistantContent else null
        }

        fun throwIfFailed() {
            failure?.let { throw it }
        }
    }
    private data class RuntimeMcpServerStatus(
        val status: String,
        val usable: Boolean = status.equals("Connected", ignoreCase = true),
        val detail: String = "",
        val toolCount: Int = 0,
        val toolNames: List<String> = emptyList()
    )
    private data class Quadruple<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)
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

    private data class HeartbeatDecision(val action: String, val tasks: String)
    private data class ParsedHeartbeatTasks(val hasActiveSection: Boolean, val tasks: String)
    companion object {
        private const val TAG = "GatewayRuntime"
        private const val HEARTBEAT_TOOL_NAME = "heartbeat"
        private const val HEARTBEAT_ACTION_SKIP = "skip"
        private const val HEARTBEAT_ACTION_RUN = "run"
    }
}























