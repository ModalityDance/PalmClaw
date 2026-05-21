package com.palmclaw.runtime

import android.app.Application
import com.palmclaw.bus.MessageAttachment
import com.palmclaw.bus.OutboundMessage
import com.palmclaw.config.AlwaysOnConfig
import com.palmclaw.config.ConfigStore
import kotlinx.coroutines.flow.StateFlow

interface RuntimeModeConfigGateway {
    fun getAlwaysOnConfig(): AlwaysOnConfig

    fun saveAlwaysOnConfig(config: AlwaysOnConfig)
}

class ConfigStoreRuntimeModeConfigGateway(
    private val configStore: ConfigStore
) : RuntimeModeConfigGateway {
    override fun getAlwaysOnConfig(): AlwaysOnConfig = configStore.getAlwaysOnConfig()

    override fun saveAlwaysOnConfig(config: AlwaysOnConfig) {
        configStore.saveAlwaysOnConfig(config)
    }
}

interface NormalRuntimeGateway {
    val status: StateFlow<RuntimeControllerStatus>

    fun start()

    fun stop()

    fun reloadGateway()

    fun reloadAutomation()

    fun reloadMcp()

    fun reloadAll()

    suspend fun publishOutbound(outbound: OutboundMessage)

    suspend fun runUserMessage(
        sessionId: String,
        sessionTitle: String,
        text: String,
        attachments: List<MessageAttachment> = emptyList()
    )

    suspend fun triggerHeartbeatNow(): String
}

class RuntimeControllerGateway(
    private val appProvider: () -> Application
) : NormalRuntimeGateway {
    override val status: StateFlow<RuntimeControllerStatus>
        get() = RuntimeController.status

    override fun start() = RuntimeController.start(appProvider())

    override fun stop() = RuntimeController.stop()

    override fun reloadGateway() = RuntimeController.reloadGateway(appProvider())

    override fun reloadAutomation() = RuntimeController.reloadAutomation(appProvider())

    override fun reloadMcp() = RuntimeController.reloadMcp(appProvider())

    override fun reloadAll() = RuntimeController.reloadAll(appProvider())

    override suspend fun publishOutbound(outbound: OutboundMessage) {
        RuntimeController.publishOutbound(appProvider(), outbound)
    }

    override suspend fun runUserMessage(
        sessionId: String,
        sessionTitle: String,
        text: String,
        attachments: List<MessageAttachment>
    ) {
        RuntimeController.runUserMessage(
            context = appProvider(),
            sessionId = sessionId,
            sessionTitle = sessionTitle,
            text = text,
            attachments = attachments
        )
    }

    override suspend fun triggerHeartbeatNow(): String {
        return RuntimeController.triggerHeartbeatNow(appProvider())
    }
}

interface AlwaysOnRuntimeGateway {
    val status: StateFlow<AlwaysOnRuntimeStatus>

    fun startService()

    fun stopService()

    fun reloadGateway()

    fun reloadAutomation()

    fun reloadMcp()

    fun reloadAll()

    suspend fun publishOutbound(outbound: OutboundMessage)

    suspend fun runUserMessage(
        sessionId: String,
        sessionTitle: String,
        text: String,
        attachments: List<MessageAttachment> = emptyList()
    )

    suspend fun triggerHeartbeatNow(): String
}

class AlwaysOnModeGateway(
    private val appProvider: () -> Application
) : AlwaysOnRuntimeGateway {
    override val status: StateFlow<AlwaysOnRuntimeStatus>
        get() = AlwaysOnModeController.status

    override fun startService() {
        AlwaysOnModeController.startService(appProvider())
    }

    override fun stopService() = AlwaysOnModeController.stopService(appProvider())

    override fun reloadGateway() = GatewayRuntimeSupervisor.reloadGateway(appProvider())

    override fun reloadAutomation() = GatewayRuntimeSupervisor.reloadAutomation(appProvider())

    override fun reloadMcp() = GatewayRuntimeSupervisor.reloadMcp(appProvider())

    override fun reloadAll() = GatewayRuntimeSupervisor.reloadAll(appProvider())

    override suspend fun publishOutbound(outbound: OutboundMessage) {
        GatewayRuntimeSupervisor.publishOutbound(appProvider(), outbound)
    }

    override suspend fun runUserMessage(
        sessionId: String,
        sessionTitle: String,
        text: String,
        attachments: List<MessageAttachment>
    ) {
        GatewayRuntimeSupervisor.runUserMessage(
            context = appProvider(),
            sessionId = sessionId,
            sessionTitle = sessionTitle,
            text = text,
            attachments = attachments
        )
    }

    override suspend fun triggerHeartbeatNow(): String = GatewayRuntimeSupervisor.triggerHeartbeatNow(appProvider())
}

interface AlwaysOnHealthCheckScheduler {
    fun ensureScheduled()

    fun cancel()
}

class AlwaysOnHealthCheckWorkScheduler(
    private val appProvider: () -> Application
) : AlwaysOnHealthCheckScheduler {
    override fun ensureScheduled() {
        AlwaysOnHealthCheckWorker.ensureScheduled(appProvider())
    }

    override fun cancel() {
        AlwaysOnHealthCheckWorker.cancel(appProvider())
    }
}

class RuntimeApplicationService(
    appProvider: () -> Application,
    private val modeConfigGateway: RuntimeModeConfigGateway,
    private val normalRuntimeGateway: NormalRuntimeGateway = RuntimeControllerGateway(appProvider),
    private val alwaysOnRuntimeGateway: AlwaysOnRuntimeGateway = AlwaysOnModeGateway(appProvider),
    private val healthCheckScheduler: AlwaysOnHealthCheckScheduler = AlwaysOnHealthCheckWorkScheduler(appProvider)
) {
    val runtimeStatus: StateFlow<RuntimeControllerStatus>
        get() = normalRuntimeGateway.status

    val alwaysOnStatus: StateFlow<AlwaysOnRuntimeStatus>
        get() = alwaysOnRuntimeGateway.status

    fun currentAlwaysOnStatus(): AlwaysOnRuntimeStatus = alwaysOnRuntimeGateway.status.value

    fun isAlwaysOnEnabled(): Boolean = modeConfigGateway.getAlwaysOnConfig().enabled

    fun startGatewayIfEnabled() {
        normalRuntimeGateway.start()
        if (isAlwaysOnEnabled()) {
            startAlwaysOnShell()
            normalRuntimeGateway.reloadAll()
        }
    }

    fun applyAlwaysOnConfig(next: AlwaysOnConfig) {
        modeConfigGateway.saveAlwaysOnConfig(next)
        normalRuntimeGateway.start()
        if (next.enabled) {
            startAlwaysOnShell()
        } else {
            stopAlwaysOnShell()
        }
        normalRuntimeGateway.reloadAll()
    }

    fun refreshGatewayRuntimeConfig() {
        normalRuntimeGateway.start()
        syncAlwaysOnShellForConfig()
        normalRuntimeGateway.reloadGateway()
    }

    fun refreshToolRuntimeConfig() {
        normalRuntimeGateway.start()
        syncAlwaysOnShellForConfig()
        normalRuntimeGateway.reloadAll()
    }

    suspend fun publishOutbound(outbound: OutboundMessage) {
        normalRuntimeGateway.publishOutbound(outbound)
    }

    suspend fun runUserMessage(
        sessionId: String,
        sessionTitle: String,
        text: String,
        attachments: List<MessageAttachment> = emptyList()
    ) {
        normalRuntimeGateway.runUserMessage(
            sessionId = sessionId,
            sessionTitle = sessionTitle,
            text = text,
            attachments = attachments
        )
    }

    suspend fun triggerHeartbeatNow(): String {
        return normalRuntimeGateway.triggerHeartbeatNow()
    }

    fun reloadAutomation() {
        normalRuntimeGateway.reloadAutomation()
    }

    fun reloadMcp() {
        normalRuntimeGateway.reloadMcp()
    }

    fun reloadAll() {
        normalRuntimeGateway.reloadAll()
    }

    private fun syncAlwaysOnShellForConfig() {
        if (isAlwaysOnEnabled()) {
            startAlwaysOnShell()
        } else {
            stopAlwaysOnShell()
        }
    }

    private fun startAlwaysOnShell() {
        healthCheckScheduler.ensureScheduled()
        alwaysOnRuntimeGateway.startService()
    }

    private fun stopAlwaysOnShell() {
        healthCheckScheduler.cancel()
        alwaysOnRuntimeGateway.stopService()
    }
}
