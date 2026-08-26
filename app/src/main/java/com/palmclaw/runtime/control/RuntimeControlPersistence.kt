package com.palmclaw.runtime.control

import com.palmclaw.bus.MessageAttachment
import com.palmclaw.config.AppConfig
import com.palmclaw.config.ChannelsConfig
import com.palmclaw.config.ConfigStore
import com.palmclaw.config.HeartbeatConfig
import com.palmclaw.config.McpHttpConfig
import com.palmclaw.config.SessionChannelBinding
import com.palmclaw.storage.MessageRepository
import com.palmclaw.storage.SessionRepository
import com.palmclaw.storage.entities.SessionEntity
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal interface RuntimeControlPersistence {
    fun getAppConfig(): AppConfig
    fun saveAppConfig(config: AppConfig)
    fun getHeartbeatConfig(): HeartbeatConfig
    fun saveHeartbeatConfig(config: HeartbeatConfig)
    fun getHeartbeatLastTriggeredAtMs(): Long
    fun getHeartbeatNextTriggerAtMs(): Long
    suspend fun readHeartbeatDocument(): String
    suspend fun writeHeartbeatDocument(content: String)
    fun getChannelsConfig(): ChannelsConfig
    fun saveChannelsConfig(config: ChannelsConfig)
    fun getSessionChannelBindings(): List<SessionChannelBinding>
    fun saveSessionChannelBinding(binding: SessionChannelBinding)
    fun getMcpHttpConfig(): McpHttpConfig
    suspend fun listSessions(): List<SessionEntity>
    suspend fun appendAssistantMessage(
        sessionId: String,
        content: String,
        attachments: List<MessageAttachment>
    )
    suspend fun touchSession(sessionId: String)
}

internal class AppRuntimeControlPersistence(
    private val configStore: ConfigStore,
    private val messageRepository: MessageRepository,
    private val sessionRepository: SessionRepository,
    private val heartbeatDocument: File
) : RuntimeControlPersistence {
    override fun getAppConfig(): AppConfig = configStore.getConfig()
    override fun saveAppConfig(config: AppConfig) = configStore.saveConfig(config)
    override fun getHeartbeatConfig(): HeartbeatConfig = configStore.getHeartbeatConfig()
    override fun saveHeartbeatConfig(config: HeartbeatConfig) = configStore.saveHeartbeatConfig(config)
    override fun getHeartbeatLastTriggeredAtMs(): Long = configStore.getHeartbeatLastTriggeredAtMs()
    override fun getHeartbeatNextTriggerAtMs(): Long = configStore.getHeartbeatNextTriggerAtMs()

    override suspend fun readHeartbeatDocument(): String = withContext(Dispatchers.IO) {
        if (heartbeatDocument.exists()) heartbeatDocument.readText(Charsets.UTF_8) else ""
    }

    override suspend fun writeHeartbeatDocument(content: String) = withContext(Dispatchers.IO) {
        heartbeatDocument.parentFile?.mkdirs()
        heartbeatDocument.writeText(content, Charsets.UTF_8)
    }

    override fun getChannelsConfig(): ChannelsConfig = configStore.getChannelsConfig()
    override fun saveChannelsConfig(config: ChannelsConfig) = configStore.saveChannelsConfig(config)
    override fun getSessionChannelBindings(): List<SessionChannelBinding> = configStore.getSessionChannelBindings()
    override fun saveSessionChannelBinding(binding: SessionChannelBinding) = configStore.saveSessionChannelBinding(binding)
    override fun getMcpHttpConfig(): McpHttpConfig = configStore.getMcpHttpConfig()
    override suspend fun listSessions(): List<SessionEntity> = sessionRepository.listSessions()

    override suspend fun appendAssistantMessage(
        sessionId: String,
        content: String,
        attachments: List<MessageAttachment>
    ) {
        messageRepository.appendAssistantMessage(
            sessionId = sessionId,
            content = content,
            attachments = attachments
        )
    }

    override suspend fun touchSession(sessionId: String) {
        sessionRepository.touch(sessionId)
    }
}
