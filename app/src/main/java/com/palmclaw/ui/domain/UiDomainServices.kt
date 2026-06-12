package com.palmclaw.ui.domain

import android.net.Uri
import com.palmclaw.bus.MessageAttachment
import com.palmclaw.bus.OutboundMessage
import com.palmclaw.config.AlwaysOnConfig
import com.palmclaw.config.ChannelsConfig
import com.palmclaw.config.ConfigStore
import com.palmclaw.config.SessionChannelBinding
import com.palmclaw.runtime.AlwaysOnRuntimeStatus
import com.palmclaw.runtime.RuntimeApplicationService
import com.palmclaw.runtime.RuntimeControllerStatus
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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

data class ChatViewModelDependencies(
    val chatRepository: ChatRepository,
    val runtimeGateway: RuntimeGateway,
    val skillRepository: SkillRepository,
    val channelBindingService: ChannelBindingService
)

interface ChatRepository {
    fun observeSessions(): Flow<List<SessionEntity>>

    fun observeRecentMessages(sessionId: String, limit: Int): Flow<List<MessageEntity>>

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

interface RuntimeGateway {
    val runtimeStatus: StateFlow<RuntimeControllerStatus>
    val alwaysOnStatus: StateFlow<AlwaysOnRuntimeStatus>

    fun currentAlwaysOnStatus(): AlwaysOnRuntimeStatus

    fun startGatewayIfEnabled()

    fun refreshGatewayRuntimeConfig()

    fun refreshToolRuntimeConfig()

    fun applyAlwaysOnConfig(config: AlwaysOnConfig)

    suspend fun publishOutbound(outbound: OutboundMessage)

    suspend fun runUserMessage(
        sessionId: String,
        sessionTitle: String,
        text: String,
        attachments: List<MessageAttachment> = emptyList()
    )

    suspend fun triggerHeartbeatNow(): String

    fun reloadAutomation()

    fun reloadMcp()

    fun reloadAll()
}

class RuntimeApplicationGateway(
    private val service: RuntimeApplicationService
) : RuntimeGateway {
    override val runtimeStatus: StateFlow<RuntimeControllerStatus>
        get() = service.runtimeStatus

    override val alwaysOnStatus: StateFlow<AlwaysOnRuntimeStatus>
        get() = service.alwaysOnStatus

    override fun currentAlwaysOnStatus(): AlwaysOnRuntimeStatus = service.currentAlwaysOnStatus()

    override fun startGatewayIfEnabled() = service.startGatewayIfEnabled()

    override fun refreshGatewayRuntimeConfig() = service.refreshGatewayRuntimeConfig()

    override fun refreshToolRuntimeConfig() = service.refreshToolRuntimeConfig()

    override fun applyAlwaysOnConfig(config: AlwaysOnConfig) {
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
        service.runUserMessage(sessionId, sessionTitle, text, attachments)
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
