package com.palmclaw.runtime

import android.app.Application
import android.content.Context
import com.palmclaw.bus.MessageAttachment
import com.palmclaw.bus.OutboundMessage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch

internal interface GatewayRuntimeHandle {
    fun start()

    fun reloadGatewayFromStoredConfig()

    fun reloadAutomationFromStoredConfig()

    fun reloadMcpFromStoredConfig()

    fun reloadAllFromStoredConfig()

    suspend fun deliverOutboundViaOwnedGateway(outbound: OutboundMessage)

    suspend fun runUserMessage(
        sessionId: String,
        sessionTitle: String,
        text: String,
        attachments: List<MessageAttachment> = emptyList()
    )

    suspend fun triggerHeartbeatNow(): String

    suspend fun processHeartbeatTick(): String?

    suspend fun processDueCronJobs(resync: Boolean)

    fun shutdownRuntime()
}

internal interface GatewayRuntimeFactory {
    fun create(
        app: Application,
        onStateChanged: (GatewayRuntimeState) -> Unit
    ): GatewayRuntimeHandle
}

private object RealGatewayRuntimeFactory : GatewayRuntimeFactory {
    override fun create(
        app: Application,
        onStateChanged: (GatewayRuntimeState) -> Unit
    ): GatewayRuntimeHandle {
        return RealGatewayRuntimeHandle(
            GatewayRuntime(
                app = app,
                enableAutomation = true,
                enableMcp = true,
                onStateChanged = onStateChanged
            )
        )
    }
}

private class RealGatewayRuntimeHandle(
    private val runtime: GatewayRuntime
) : GatewayRuntimeHandle {
    override fun start() = runtime.start()

    override fun reloadGatewayFromStoredConfig() = runtime.reloadGatewayFromStoredConfig()

    override fun reloadAutomationFromStoredConfig() = runtime.reloadAutomationFromStoredConfig()

    override fun reloadMcpFromStoredConfig() = runtime.reloadMcpFromStoredConfig()

    override fun reloadAllFromStoredConfig() = runtime.reloadAllFromStoredConfig()

    override suspend fun deliverOutboundViaOwnedGateway(outbound: OutboundMessage) {
        runtime.deliverOutboundViaOwnedGateway(outbound)
    }

    override suspend fun runUserMessage(
        sessionId: String,
        sessionTitle: String,
        text: String,
        attachments: List<MessageAttachment>
    ) {
        runtime.runUserMessage(
            sessionId = sessionId,
            sessionTitle = sessionTitle,
            text = text,
            attachments = attachments
        )
    }

    override suspend fun triggerHeartbeatNow(): String = runtime.triggerHeartbeatNow()

    override suspend fun processHeartbeatTick(): String? = runtime.processHeartbeatTick()

    override suspend fun processDueCronJobs(resync: Boolean) {
        runtime.processDueCronJobs(resync = resync)
    }

    override fun shutdownRuntime() = runtime.shutdownRuntime()
}

object GatewayRuntimeSupervisor {
    private val lock = Any()
    private val supervisorScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _status = MutableStateFlow(RuntimeControllerStatus())
    private val listeners = mutableSetOf<(RuntimeControllerStatus) -> Unit>()
    private val operationJobs = mutableSetOf<Job>()

    val status: StateFlow<RuntimeControllerStatus> = _status.asStateFlow()

    @Volatile
    private var runtime: GatewayRuntimeHandle? = null

    @Volatile
    private var startJob: Deferred<GatewayRuntimeHandle>? = null

    @Volatile
    private var startGeneration: Long = 0L

    @Volatile
    private var factory: GatewayRuntimeFactory = RealGatewayRuntimeFactory

    fun ensureStarted(context: Context) {
        ensureStarted(context.applicationContext as Application)
    }

    fun ensureStarted(app: Application) {
        ensureStartedAsync(app)
    }

    fun reloadGateway(context: Context) {
        launchOperation(context) { runtime ->
            runtime.reloadGatewayFromStoredConfig()
        }
    }

    fun reloadAutomation(context: Context) {
        launchOperation(context) { runtime ->
            runtime.reloadAutomationFromStoredConfig()
        }
    }

    fun reloadMcp(context: Context) {
        launchOperation(context) { runtime ->
            runtime.reloadMcpFromStoredConfig()
        }
    }

    fun reloadAll(context: Context) {
        launchOperation(context) { runtime ->
            runtime.reloadAllFromStoredConfig()
        }
    }

    suspend fun publishOutbound(context: Context, outbound: OutboundMessage) {
        ensureStartedAndWait(context).deliverOutboundViaOwnedGateway(outbound)
    }

    suspend fun runUserMessage(
        context: Context,
        sessionId: String,
        sessionTitle: String,
        text: String,
        attachments: List<MessageAttachment> = emptyList()
    ) {
        ensureStartedAndWait(context).runUserMessage(
            sessionId = sessionId,
            sessionTitle = sessionTitle,
            text = text,
            attachments = attachments
        )
    }

    suspend fun triggerHeartbeatNow(context: Context): String {
        return ensureStartedAndWait(context).triggerHeartbeatNow()
    }

    suspend fun processHeartbeatTick(context: Context): String? {
        return ensureStartedAndWait(context).processHeartbeatTick()
    }

    suspend fun processDueCronJobs(context: Context, resync: Boolean) {
        ensureStartedAndWait(context).processDueCronJobs(resync = resync)
    }

    fun addStatusListener(listener: (RuntimeControllerStatus) -> Unit): () -> Unit {
        synchronized(lock) {
            listeners += listener
        }
        listener(_status.value)
        return {
            synchronized(lock) {
                listeners -= listener
            }
        }
    }

    fun shutdownForProcessExit() {
        val stoppedRuntime: GatewayRuntimeHandle?
        val jobToCancel: Deferred<GatewayRuntimeHandle>?
        val jobsToCancel: List<Job>
        synchronized(lock) {
            startGeneration += 1
            stoppedRuntime = runtime
            runtime = null
            jobToCancel = startJob
            startJob = null
            jobsToCancel = operationJobs.toList()
            operationJobs.clear()
        }
        jobToCancel?.cancel()
        jobsToCancel.forEach { it.cancel() }
        stoppedRuntime?.shutdownRuntime()
        _status.value = RuntimeControllerStatus()
        AlwaysOnModeController.clearRuntimeState()
        notifyStatusListeners()
    }

    internal fun currentRuntimeForTest(): GatewayRuntimeHandle? = runtime

    internal fun installFactoryForTest(factory: GatewayRuntimeFactory) {
        shutdownForProcessExit()
        this.factory = factory
    }

    internal fun resetForTest() {
        shutdownForProcessExit()
        factory = RealGatewayRuntimeFactory
    }

    internal fun currentRuntimeOrNull(): GatewayRuntimeHandle? = runtime

    internal suspend fun awaitIdleForTest() {
        synchronized(lock) { startJob }?.await()
        while (true) {
            val jobs = synchronized(lock) { operationJobs.toList() }
            if (jobs.isEmpty()) return
            jobs.joinAll()
        }
    }

    private fun ensureStartedAsync(app: Application): Deferred<GatewayRuntimeHandle> {
        runtime?.let { return CompletableDeferred(it) }
        synchronized(lock) {
            runtime?.let { return CompletableDeferred(it) }
            startJob?.let { return it }
            val generation = startGeneration + 1
            startGeneration = generation
            val deferred = supervisorScope.async(start = CoroutineStart.LAZY) {
                val created = factory.create(app, ::handleRuntimeState)
                try {
                    created.start()
                    val accepted = synchronized(lock) {
                        if (startGeneration == generation) {
                            runtime = created
                            startJob = null
                            true
                        } else {
                            false
                        }
                    }
                    if (!accepted) {
                        created.shutdownRuntime()
                        throw IllegalStateException("Gateway runtime start was superseded")
                    }
                    _status.update { it.copy(running = true, lastError = "") }
                    notifyStatusListeners()
                    created
                } catch (t: Throwable) {
                    val currentStart = synchronized(lock) {
                        if (startGeneration == generation) {
                            runtime = null
                            startJob = null
                            true
                        } else {
                            false
                        }
                    }
                    if (currentStart) {
                        _status.update {
                            it.copy(
                                running = false,
                                lastError = t.message ?: t.javaClass.simpleName
                            )
                        }
                        notifyStatusListeners()
                    }
                    throw t
                }
            }
            startJob = deferred
            deferred.start()
            return deferred
        }
    }

    private suspend fun ensureStartedAndWait(context: Context): GatewayRuntimeHandle {
        return ensureStartedAsync(context.applicationContext as Application).await()
    }

    private fun launchOperation(
        context: Context,
        block: suspend (GatewayRuntimeHandle) -> Unit
    ) {
        val app = context.applicationContext as Application
        val job = supervisorScope.launch(start = CoroutineStart.LAZY) {
            runCatching {
                block(ensureStartedAsync(app).await())
            }.onFailure { t ->
                _status.update {
                    it.copy(lastError = t.message ?: t.javaClass.simpleName)
                }
                notifyStatusListeners()
            }
        }
        synchronized(lock) {
            operationJobs += job
        }
        job.invokeOnCompletion {
            synchronized(lock) {
                operationJobs -= job
            }
        }
        job.start()
    }

    private fun handleRuntimeState(state: GatewayRuntimeState) {
        _status.value = RuntimeControllerStatus(
            running = true,
            gatewayRunning = state.gatewayRunning,
            activeAdapterCount = state.activeAdapterCount,
            lastError = state.lastError,
            processingSessionIds = state.processingSessionIds
        )
        AlwaysOnModeController.updateRuntimeState(
            gatewayRunning = state.gatewayRunning,
            activeAdapterCount = state.activeAdapterCount,
            lastError = state.lastError,
            processingSessionIds = state.processingSessionIds
        )
        notifyStatusListeners()
    }

    private fun notifyStatusListeners() {
        val status = _status.value
        val snapshot = synchronized(lock) {
            listeners.toList()
        }
        snapshot.forEach { listener ->
            listener(status)
        }
    }
}
