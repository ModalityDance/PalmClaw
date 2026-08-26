package com.palmclaw.ui.domain

import android.net.Uri
import com.palmclaw.bus.MessageAttachment
import com.palmclaw.bus.OutboundMessage
import com.palmclaw.config.AlwaysOnConfig
import com.palmclaw.config.ChannelsConfig
import com.palmclaw.config.ConfigStore
import com.palmclaw.config.SessionChannelBinding
import com.palmclaw.runtime.RuntimeApplicationService
import com.palmclaw.runtime.RuntimeControllerStatus
import com.palmclaw.runtime.alwayson.AlwaysOnActionRequiredReason
import com.palmclaw.runtime.alwayson.AlwaysOnGatewayState
import com.palmclaw.runtime.alwayson.AlwaysOnNetworkState
import com.palmclaw.runtime.alwayson.AlwaysOnPhase
import com.palmclaw.runtime.alwayson.AlwaysOnRuntimeState
import com.palmclaw.runtime.alwayson.AlwaysOnShellState
import com.palmclaw.runtime.alwayson.AlwaysOnStatus
import com.palmclaw.runtime.alwayson.AlwaysOnWaitingReason
import com.palmclaw.skills.ClawHubClient
import com.palmclaw.skills.ClawHubSkillCard
import com.palmclaw.skills.ClawHubSkillDetail
import com.palmclaw.skills.SkillCatalogEntry
import com.palmclaw.skills.SkillInstallService
import com.palmclaw.skills.SkillsLoader
import com.palmclaw.skills.StagedSkillReview
import com.palmclaw.storage.MessageRepository
import com.palmclaw.storage.SessionRepository
import com.palmclaw.storage.entities.MessageEntity
import com.palmclaw.storage.entities.SessionEntity
import com.palmclaw.workspace.SessionUiLifecycleService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.withContext

data class ChatViewModelDependencies(
    val chatRepository: ChatRepository,
    val runtimeStatusSource: RuntimeStatusSource,
    val runtimeExecutionGateway: RuntimeExecutionGateway,
    val runtimeRefreshGateway: RuntimeRefreshGateway,
    val skillRepository: SkillRepository,
    val channelBindingService: ChannelBindingService
)

interface ChatRepository {
    fun observeSessions(): Flow<List<SessionEntity>>

    fun observeRecentMessages(sessionId: String, limit: Int): Flow<List<MessageEntity>>

    suspend fun getRecentMessages(sessionId: String, limit: Int): List<MessageEntity>

    suspend fun getMessagesBefore(
        sessionId: String,
        beforeCreatedAt: Long,
        beforeId: Long,
        limit: Int
    ): List<MessageEntity>

    suspend fun appendAssistantMessage(sessionId: String, content: String): Long

    suspend fun touchSession(sessionId: String)

    suspend fun listSessions(): List<SessionEntity>

    suspend fun ensureLocalSessionExists()

    suspend fun createSession(displayName: String): String

    suspend fun renameSession(sessionId: String, displayName: String)

    suspend fun deleteSession(sessionId: String)
}

class DefaultChatRepository(
    private val messageRepository: MessageRepository,
    private val sessionRepository: SessionRepository,
    private val sessionUiLifecycleService: SessionUiLifecycleService
) : ChatRepository {
    override fun observeSessions(): Flow<List<SessionEntity>> = sessionRepository.observeSessions()

    override fun observeRecentMessages(sessionId: String, limit: Int): Flow<List<MessageEntity>> {
        return messageRepository.observeRecentMessages(sessionId, limit)
    }

    override suspend fun getRecentMessages(sessionId: String, limit: Int): List<MessageEntity> {
        return messageRepository.getRecentMessages(sessionId, limit)
    }

    override suspend fun getMessagesBefore(
        sessionId: String,
        beforeCreatedAt: Long,
        beforeId: Long,
        limit: Int
    ): List<MessageEntity> {
        return messageRepository.getMessagesBefore(sessionId, beforeCreatedAt, beforeId, limit)
    }

    override suspend fun appendAssistantMessage(sessionId: String, content: String): Long {
        return messageRepository.appendAssistantMessage(sessionId, content)
    }

    override suspend fun touchSession(sessionId: String) {
        sessionRepository.touch(sessionId)
    }

    override suspend fun listSessions(): List<SessionEntity> = sessionRepository.listSessions()

    override suspend fun ensureLocalSessionExists() {
        sessionUiLifecycleService.ensureLocalSessionExists()
    }

    override suspend fun createSession(displayName: String): String {
        return sessionUiLifecycleService.createSession(displayName)
    }

    override suspend fun renameSession(sessionId: String, displayName: String) {
        sessionUiLifecycleService.renameSession(sessionId, displayName)
    }

    override suspend fun deleteSession(sessionId: String) {
        sessionUiLifecycleService.deleteSession(sessionId)
    }
}

data class AlwaysOnUiStatus(
    val desired: Boolean = false,
    val phase: Phase = Phase.DISABLED,
    val shell: LifecycleState = LifecycleState.STOPPED,
    val notificationVisible: Boolean = false,
    val runtime: LifecycleState = LifecycleState.STOPPED,
    val gateway: LifecycleState = LifecycleState.STOPPED,
    val network: NetworkState = NetworkState.UNKNOWN,
    val channels: ChannelCounts = ChannelCounts(),
    val waitingFor: WaitingReason? = null,
    val actionRequired: ActionRequired? = null,
    val lastError: String = "",
    val updatedAtEpochMillis: Long = 0L,
    val processingSessionIds: Set<String> = emptySet()
) {
    enum class Phase {
        DISABLED,
        STARTING,
        ONLINE,
        DEGRADED,
        RECOVERING,
        ACTION_REQUIRED
    }

    enum class LifecycleState {
        STOPPED,
        STARTING,
        RUNNING
    }

    enum class NetworkState {
        UNKNOWN,
        OFFLINE,
        ONLINE
    }

    data class ChannelCounts(
        val configured: Int = 0,
        val ready: Int = 0,
        val reconnecting: Int = 0,
        val blocked: Int = 0
    )

    data class ActionRequired(
        val reason: ActionRequiredReason,
        val message: String? = null
    )

    enum class ActionRequiredReason {
        NO_CHANNEL_CONFIGURED,
        SYSTEM_RESTRICTED,
        BACKGROUND_START_RESTRICTED,
        ALL_CHANNELS_BLOCKED,
        GATEWAY_BLOCKED
    }

    enum class WaitingReason {
        NETWORK,
        USER_FOREGROUND,
        SHELL,
        RUNTIME,
        GATEWAY,
        CHANNELS
    }
}

internal fun AlwaysOnStatus.toUiStatus(
    processingSessionIds: Set<String> = emptySet()
): AlwaysOnUiStatus = AlwaysOnUiStatus(
    desired = desired,
    phase = when (phase) {
        AlwaysOnPhase.DISABLED -> AlwaysOnUiStatus.Phase.DISABLED
        AlwaysOnPhase.STARTING -> AlwaysOnUiStatus.Phase.STARTING
        AlwaysOnPhase.ONLINE -> AlwaysOnUiStatus.Phase.ONLINE
        AlwaysOnPhase.DEGRADED -> AlwaysOnUiStatus.Phase.DEGRADED
        AlwaysOnPhase.RECOVERING -> AlwaysOnUiStatus.Phase.RECOVERING
        AlwaysOnPhase.ACTION_REQUIRED -> AlwaysOnUiStatus.Phase.ACTION_REQUIRED
    },
    shell = shell.toUiLifecycleState(),
    notificationVisible = notificationVisible,
    runtime = runtime.toUiLifecycleState(),
    gateway = gateway.toUiLifecycleState(),
    network = when (network) {
        AlwaysOnNetworkState.UNKNOWN -> AlwaysOnUiStatus.NetworkState.UNKNOWN
        AlwaysOnNetworkState.OFFLINE -> AlwaysOnUiStatus.NetworkState.OFFLINE
        AlwaysOnNetworkState.ONLINE -> AlwaysOnUiStatus.NetworkState.ONLINE
    },
    channels = AlwaysOnUiStatus.ChannelCounts(
        configured = channels.configured,
        ready = channels.ready,
        reconnecting = channels.reconnecting,
        blocked = channels.blocked
    ),
    waitingFor = waitingFor?.let { value ->
        when (value) {
            AlwaysOnWaitingReason.NETWORK -> AlwaysOnUiStatus.WaitingReason.NETWORK
            AlwaysOnWaitingReason.USER_FOREGROUND -> AlwaysOnUiStatus.WaitingReason.USER_FOREGROUND
            AlwaysOnWaitingReason.SHELL -> AlwaysOnUiStatus.WaitingReason.SHELL
            AlwaysOnWaitingReason.RUNTIME -> AlwaysOnUiStatus.WaitingReason.RUNTIME
            AlwaysOnWaitingReason.GATEWAY -> AlwaysOnUiStatus.WaitingReason.GATEWAY
            AlwaysOnWaitingReason.CHANNELS -> AlwaysOnUiStatus.WaitingReason.CHANNELS
        }
    },
    actionRequired = actionRequired?.let { value ->
        AlwaysOnUiStatus.ActionRequired(
            reason = when (value.reason) {
                AlwaysOnActionRequiredReason.NO_CHANNEL_CONFIGURED ->
                    AlwaysOnUiStatus.ActionRequiredReason.NO_CHANNEL_CONFIGURED
                AlwaysOnActionRequiredReason.SYSTEM_RESTRICTED ->
                    AlwaysOnUiStatus.ActionRequiredReason.SYSTEM_RESTRICTED
                AlwaysOnActionRequiredReason.BACKGROUND_START_RESTRICTED ->
                    AlwaysOnUiStatus.ActionRequiredReason.BACKGROUND_START_RESTRICTED
                AlwaysOnActionRequiredReason.ALL_CHANNELS_BLOCKED ->
                    AlwaysOnUiStatus.ActionRequiredReason.ALL_CHANNELS_BLOCKED
                AlwaysOnActionRequiredReason.GATEWAY_BLOCKED ->
                    AlwaysOnUiStatus.ActionRequiredReason.GATEWAY_BLOCKED
            },
            message = value.message
        )
    },
    lastError = lastError.orEmpty(),
    updatedAtEpochMillis = updatedAtEpochMillis,
    processingSessionIds = processingSessionIds
)

private fun AlwaysOnShellState.toUiLifecycleState(): AlwaysOnUiStatus.LifecycleState = when (this) {
    AlwaysOnShellState.STOPPED -> AlwaysOnUiStatus.LifecycleState.STOPPED
    AlwaysOnShellState.STARTING -> AlwaysOnUiStatus.LifecycleState.STARTING
    AlwaysOnShellState.RUNNING -> AlwaysOnUiStatus.LifecycleState.RUNNING
}

private fun AlwaysOnRuntimeState.toUiLifecycleState(): AlwaysOnUiStatus.LifecycleState = when (this) {
    AlwaysOnRuntimeState.STOPPED -> AlwaysOnUiStatus.LifecycleState.STOPPED
    AlwaysOnRuntimeState.STARTING -> AlwaysOnUiStatus.LifecycleState.STARTING
    AlwaysOnRuntimeState.RUNNING -> AlwaysOnUiStatus.LifecycleState.RUNNING
}

private fun AlwaysOnGatewayState.toUiLifecycleState(): AlwaysOnUiStatus.LifecycleState = when (this) {
    AlwaysOnGatewayState.STOPPED -> AlwaysOnUiStatus.LifecycleState.STOPPED
    AlwaysOnGatewayState.STARTING -> AlwaysOnUiStatus.LifecycleState.STARTING
    AlwaysOnGatewayState.RUNNING -> AlwaysOnUiStatus.LifecycleState.RUNNING
}

interface RuntimeStatusSource {
    val runtimeStatus: StateFlow<RuntimeControllerStatus>
    val alwaysOnStatus: Flow<AlwaysOnUiStatus>

    fun currentAlwaysOnStatus(): AlwaysOnUiStatus
}

interface RuntimeExecutionGateway {
    suspend fun startGatewayIfEnabled()

    suspend fun applyAlwaysOnConfig(config: AlwaysOnConfig)

    suspend fun publishOutbound(outbound: OutboundMessage)

    suspend fun runUserMessage(
        sessionId: String,
        sessionTitle: String,
        text: String,
        attachments: List<MessageAttachment> = emptyList()
    )

    suspend fun triggerHeartbeatNow(): String
}

interface RuntimeRefreshGateway {
    suspend fun refreshGatewayRuntimeConfig()

    suspend fun refreshToolRuntimeConfig()

    fun reloadAutomation()

    fun reloadMcp()

    fun reloadAll()
}

class RuntimeApplicationGateway internal constructor(
    private val service: RuntimeApplicationService,
    private val alwaysOnStatusSource: StateFlow<AlwaysOnStatus>
) : RuntimeStatusSource, RuntimeExecutionGateway, RuntimeRefreshGateway {
    override val runtimeStatus: StateFlow<RuntimeControllerStatus>
        get() = service.runtimeStatus

    override val alwaysOnStatus: Flow<AlwaysOnUiStatus> =
        combine(alwaysOnStatusSource, service.runtimeStatus) { status, runtimeStatus ->
            status.toUiStatus(runtimeStatus.processingSessionIds)
        }

    override fun currentAlwaysOnStatus(): AlwaysOnUiStatus {
        return alwaysOnStatusSource.value.toUiStatus(
            service.runtimeStatus.value.processingSessionIds
        )
    }

    override suspend fun startGatewayIfEnabled() = service.startGatewayIfEnabled()

    override suspend fun refreshGatewayRuntimeConfig() = service.refreshGatewayRuntimeConfig()

    override suspend fun refreshToolRuntimeConfig() = service.refreshToolRuntimeConfig()

    override suspend fun applyAlwaysOnConfig(config: AlwaysOnConfig) {
        service.applyAlwaysOnConfig(config)
    }

    override suspend fun publishOutbound(outbound: OutboundMessage) {
        service.publishOutbound(outbound)
    }

    override suspend fun runUserMessage(
        sessionId: String,
        sessionTitle: String,
        text: String,
        attachments: List<MessageAttachment>
    ) {
        withContext(Dispatchers.Default) {
            service.runUserMessage(sessionId, sessionTitle, text, attachments)
        }
    }

    override suspend fun triggerHeartbeatNow(): String = service.triggerHeartbeatNow()

    override fun reloadAutomation() = service.reloadAutomation()

    override fun reloadMcp() = service.reloadMcp()

    override fun reloadAll() = service.reloadAll()
}

interface SkillRepository {
    suspend fun fetchBrowseSections(): Pair<List<ClawHubSkillCard>, List<ClawHubSkillCard>>

    suspend fun searchSkills(query: String): List<ClawHubSkillCard>

    suspend fun fetchSkillDetail(detailUrl: String): ClawHubSkillDetail

    suspend fun stageClawHubSkill(detail: ClawHubSkillDetail): StagedSkillReview

    suspend fun stageLocalSkillPackage(uri: Uri): StagedSkillReview

    fun installStagedSkill(
        review: StagedSkillReview,
        enable: Boolean,
        allowIncompatible: Boolean
    )

    fun deleteInstalledSkill(skillName: String)

    fun cleanupStaging(stagingId: String)

    fun listSkills(): List<SkillCatalogEntry>

    fun getSkill(name: String): SkillCatalogEntry?
}

class DefaultSkillRepository(
    private val skillsLoader: SkillsLoader,
    private val clawHubClient: ClawHubClient,
    private val skillInstallService: SkillInstallService
) : SkillRepository {
    override suspend fun fetchBrowseSections(): Pair<List<ClawHubSkillCard>, List<ClawHubSkillCard>> {
        return clawHubClient.fetchBrowseSections()
    }

    override suspend fun searchSkills(query: String): List<ClawHubSkillCard> {
        return clawHubClient.searchSkills(query)
    }

    override suspend fun fetchSkillDetail(detailUrl: String): ClawHubSkillDetail {
        return clawHubClient.fetchSkillDetail(detailUrl)
    }

    override suspend fun stageClawHubSkill(detail: ClawHubSkillDetail): StagedSkillReview {
        return skillInstallService.stageClawHubSkill(detail)
    }

    override suspend fun stageLocalSkillPackage(uri: Uri): StagedSkillReview {
        return skillInstallService.stageLocalSkillPackage(uri)
    }

    override fun installStagedSkill(
        review: StagedSkillReview,
        enable: Boolean,
        allowIncompatible: Boolean
    ) {
        skillInstallService.installStagedSkill(review, enable, allowIncompatible)
    }

    override fun deleteInstalledSkill(skillName: String) {
        skillInstallService.deleteInstalledSkill(skillName)
    }

    override fun cleanupStaging(stagingId: String) {
        skillInstallService.cleanupStaging(stagingId)
    }

    override fun listSkills(): List<SkillCatalogEntry> = skillsLoader.listSkills()

    override fun getSkill(name: String): SkillCatalogEntry? = skillsLoader.getSkill(name)
}

interface ChannelBindingService {
    fun getChannelsConfig(): ChannelsConfig

    fun saveChannelsConfig(config: ChannelsConfig)

    fun getSessionChannelBindings(): List<SessionChannelBinding>

    fun saveSessionChannelBinding(binding: SessionChannelBinding)

    fun clearSessionChannelBinding(sessionId: String)
}

class ConfigStoreChannelBindingService(
    private val configStore: ConfigStore
) : ChannelBindingService {
    override fun getChannelsConfig(): ChannelsConfig = configStore.getChannelsConfig()

    override fun saveChannelsConfig(config: ChannelsConfig) {
        configStore.saveChannelsConfig(config)
    }

    override fun getSessionChannelBindings(): List<SessionChannelBinding> {
        return configStore.getSessionChannelBindings()
    }

    override fun saveSessionChannelBinding(binding: SessionChannelBinding) {
        configStore.saveSessionChannelBinding(binding)
    }

    override fun clearSessionChannelBinding(sessionId: String) {
        configStore.clearSessionChannelBinding(sessionId)
    }
}
