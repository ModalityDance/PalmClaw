package com.palmclaw.runtime

import android.app.Application
import com.palmclaw.bus.MessageAttachment
import com.palmclaw.bus.OutboundMessage
import com.palmclaw.config.AlwaysOnConfig
import com.palmclaw.config.ConfigStore
import com.palmclaw.runtime.alwayson.AlwaysOnControl
import com.palmclaw.runtime.alwayson.AlwaysOnStatus
import com.palmclaw.runtime.alwayson.AlwaysOnTrigger
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext

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

    suspend fun acquireGatewayOwnership()

    suspend fun releaseGatewayOwnership()

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

    override suspend fun acquireGatewayOwnership() {
        GatewayRuntimeSupervisor.acquireGateway(
            context = appProvider(),
            owner = GatewayRuntimeOwner.NORMAL_PROCESS
        )
    }

    override suspend fun releaseGatewayOwnership() {
        GatewayRuntimeSupervisor.releaseGateway(GatewayRuntimeOwner.NORMAL_PROCESS)
    }

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

class RuntimeApplicationService internal constructor(
    appProvider: () -> Application,
    private val modeConfigGateway: RuntimeModeConfigGateway,
    private val alwaysOnControl: AlwaysOnControl,
    private val normalRuntimeGateway: NormalRuntimeGateway = RuntimeControllerGateway(appProvider)
) {
    val runtimeStatus: StateFlow<RuntimeControllerStatus>
        get() = normalRuntimeGateway.status

    internal val alwaysOnStatus: StateFlow<AlwaysOnStatus>
        get() = alwaysOnControl.status

    internal fun currentAlwaysOnStatus(): AlwaysOnStatus = alwaysOnControl.status.value

    fun isAlwaysOnEnabled(): Boolean = modeConfigGateway.getAlwaysOnConfig().enabled

    suspend fun onAppForegrounded() {
        normalRuntimeGateway.acquireGatewayOwnership()
        try {
            alwaysOnControl.reconcile(AlwaysOnTrigger.APP_FOREGROUND)
            normalRuntimeGateway.reloadAll()
        } catch (failure: Throwable) {
            try {
                withContext(NonCancellable) {
                    normalRuntimeGateway.releaseGatewayOwnership()
                }
            } catch (releaseFailure: Throwable) {
                if (releaseFailure !== failure) {
                    failure.addSuppressed(releaseFailure)
                }
            }
            throw failure
        }
    }

    suspend fun onAppBackgrounded() {
        normalRuntimeGateway.releaseGatewayOwnership()
    }

    suspend fun startGatewayIfEnabled() {
        alwaysOnControl.reconcile(AlwaysOnTrigger.APP_FOREGROUND)
        normalRuntimeGateway.reloadAll()
    }

    suspend fun applyAlwaysOnConfig(next: AlwaysOnConfig) {
        modeConfigGateway.saveAlwaysOnConfig(next)
        alwaysOnControl.setEnabled(next.enabled)
        normalRuntimeGateway.reloadAll()
    }

    suspend fun refreshGatewayRuntimeConfig() {
        alwaysOnControl.reconcile(AlwaysOnTrigger.GATEWAY_STATE_CHANGED)
        normalRuntimeGateway.reloadGateway()
    }

    suspend fun refreshToolRuntimeConfig() {
        alwaysOnControl.reconcile(AlwaysOnTrigger.APP_FOREGROUND)
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
}
