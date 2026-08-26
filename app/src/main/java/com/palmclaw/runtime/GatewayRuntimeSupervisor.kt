package com.palmclaw.runtime

import android.app.Application
import android.content.Context
import com.palmclaw.AppContainer
import com.palmclaw.bus.MessageAttachment
import com.palmclaw.bus.OutboundMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
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
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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

    fun stopGateway()

    fun shutdownRuntime()
}

internal interface GatewayRuntimeFactory {
    fun create(
        app: Application,
        onStateChanged: (GatewayRuntimeState) -> Unit
    ): GatewayRuntimeHandle
}

internal enum class GatewayRuntimeOwner {
    NORMAL_PROCESS,
    ALWAYS_ON,
    AUTOMATION
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
                onStateChanged = onStateChanged,
                dependencies = AppContainer.from(app).gatewayRuntimeDependencies
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

    override fun stopGateway() = runtime.stopGateway()

    override fun shutdownRuntime() = runtime.shutdownRuntime()
}

object GatewayRuntimeSupervisor {
    private val lock = Any()
    private val supervisorScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _status = MutableStateFlow(RuntimeControllerStatus())
    private val listeners = mutableSetOf<(RuntimeControllerStatus) -> Unit>()
    private val operationJobs = mutableSetOf<Job>()
    private val gatewayOwnershipMutex = Mutex()
    private val gatewayOwners = mutableSetOf<GatewayRuntimeOwner>()
    private var automationOwnerCount = 0

    val status: StateFlow<RuntimeControllerStatus> = _status.asStateFlow()

    @Volatile
    private var runtime: GatewayRuntimeHandle? = null

    @Volatile
    private var startJob: Deferred<GatewayRuntimeHandle>? = null

    @Volatile
    private var startGeneration: Long = 0L

    private var pendingStartState: Pair<Long, GatewayRuntimeState>? = null

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
            gatewayOwnershipMutex.withLock {
                if (hasGatewayOwner()) {
                    runtime.reloadGatewayFromStoredConfig()
                }
            }
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
            gatewayOwnershipMutex.withLock {
                if (hasGatewayOwner()) {
                    runtime.reloadAllFromStoredConfig()
                } else {
                    runtime.reloadAutomationFromStoredConfig()
                    runtime.reloadMcpFromStoredConfig()
                }
            }
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
        return withGatewayOwnership(context, GatewayRuntimeOwner.AUTOMATION) { runtime ->
            runtime.triggerHeartbeatNow()
        }
    }

    suspend fun processHeartbeatTick(context: Context): String? {
        return withGatewayOwnership(context, GatewayRuntimeOwner.AUTOMATION) { runtime ->
            runtime.processHeartbeatTick()
        }
    }

    suspend fun processDueCronJobs(context: Context, resync: Boolean) {
        withGatewayOwnership(context, GatewayRuntimeOwner.AUTOMATION) { runtime ->
            runtime.processDueCronJobs(resync = resync)
        }
    }

    internal suspend fun acquireGateway(context: Context, owner: GatewayRuntimeOwner) {
        gatewayOwnershipMutex.withLock {
            val newlyAcquired = synchronized(lock) {
                if (owner == GatewayRuntimeOwner.AUTOMATION) {
                    automationOwnerCount += 1
                }
                gatewayOwners.add(owner)
            }
            try {
                val ownedRuntime = ensureStartedAndWait(context)
                val isOnlyOwner = synchronized(lock) {
                    gatewayOwners.size == 1
                }
                if (!_status.value.gatewayRunning || (newlyAcquired && isOnlyOwner)) {
                    ownedRuntime.reloadGatewayFromStoredConfig()
                }
            } catch (failure: Throwable) {
                synchronized(lock) {
                    if (owner == GatewayRuntimeOwner.AUTOMATION) {
                        automationOwnerCount = (automationOwnerCount - 1).coerceAtLeast(0)
                        if (automationOwnerCount == 0) {
                            gatewayOwners.remove(owner)
                        }
                    } else if (newlyAcquired) {
                        gatewayOwners.remove(owner)
                    }
                }
                throw failure
            }
        }
    }

    internal suspend fun releaseGateway(owner: GatewayRuntimeOwner) {
        gatewayOwnershipMutex.withLock {
            val runtimeToStop: GatewayRuntimeHandle? = synchronized(lock) {
                when {
                    owner !in gatewayOwners -> null
                    owner == GatewayRuntimeOwner.AUTOMATION && automationOwnerCount > 1 -> {
                        automationOwnerCount -= 1
                        null
                    }
                    gatewayOwners.size > 1 || runtime == null -> {
                        removeGatewayOwner(owner)
                        null
                    }
                    else -> runtime
                }
            }
            if (runtimeToStop != null) {
                var stopCompleted = false
                try {
                    runtimeToStop.stopGateway()
                    stopCompleted = true
                } finally {
                    // An automation lease belongs to one scoped invocation; its caller
                    // retries the entire scope. Persistent owners retain failed releases
                    // so NORMAL_PROCESS and ALWAYS_ON can retry explicitly.
                    if (stopCompleted || owner == GatewayRuntimeOwner.AUTOMATION) {
                        synchronized(lock) {
                            removeGatewayOwner(owner)
                        }
                    }
                }
            }
        }
    }

    private suspend fun <T> withGatewayOwnership(
        context: Context,
        owner: GatewayRuntimeOwner,
        operation: suspend (GatewayRuntimeHandle) -> T
    ): T {
        acquireGateway(context, owner)
        var operationFailure: Throwable? = null
        try {
            return operation(ensureStartedAndWait(context))
        } catch (failure: Throwable) {
            operationFailure = failure
            throw failure
        } finally {
            try {
                withContext(NonCancellable) {
                    releaseGateway(owner)
                }
            } catch (releaseFailure: Throwable) {
                operationFailure?.addSuppressed(releaseFailure) ?: throw releaseFailure
            }
        }
    }

    private fun hasGatewayOwner(): Boolean = synchronized(lock) {
        gatewayOwners.isNotEmpty()
    }

    private fun removeGatewayOwner(owner: GatewayRuntimeOwner) {
        gatewayOwners.remove(owner)
        if (owner == GatewayRuntimeOwner.AUTOMATION) {
            automationOwnerCount = 0
        }
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
            gatewayOwners.clear()
            automationOwnerCount = 0
            jobToCancel = startJob
            startJob = null
            pendingStartState = null
            jobsToCancel = operationJobs.toList()
            operationJobs.clear()
        }
        jobToCancel?.cancel()
        jobsToCancel.forEach { it.cancel() }
        stoppedRuntime?.shutdownRuntime()
        _status.value = RuntimeControllerStatus()
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
                val created = factory.create(app) { state ->
                    handleRuntimeState(generation, state)
                }
                try {
                    created.start()
                    val accepted = synchronized(lock) {
                        if (startGeneration == generation) {
                            runtime = created
                            startJob = null
                            val pending = pendingStartState
                                ?.takeIf { (pendingGeneration, _) -> pendingGeneration == generation }
                                ?.second
                            pendingStartState = null
                            _status.value = pending?.toControllerStatus(running = true)
                                ?: _status.value.copy(running = true, lastError = "")
                            true
                        } else {
                            false
                        }
                    }
                    if (!accepted) {
                        created.shutdownRuntime()
                        throw IllegalStateException("Gateway runtime start was superseded")
                    }
                    notifyStatusListeners()
                    created
                } catch (t: Throwable) {
                    val currentStart = synchronized(lock) {
                        if (startGeneration == generation) {
                            runtime = null
                            startJob = null
                            pendingStartState = null
                            _status.value = _status.value.copy(
                                running = false,
                                lastError = t.message ?: t.javaClass.simpleName
                            )
                            true
                        } else {
                            false
                        }
                    }
                    if (currentStart) {
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
            try {
                block(ensureStartedAsync(app).await())
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                _status.update {
                    it.copy(lastError = "Runtime operation failed")
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

    private fun handleRuntimeState(generation: Long, state: GatewayRuntimeState) {
        val accepted = synchronized(lock) {
            if (startGeneration != generation) {
                false
            } else if (runtime == null) {
                pendingStartState = generation to state
                false
            } else {
                _status.value = state.toControllerStatus(running = true)
                true
            }
        }
        if (accepted) notifyStatusListeners()
    }

    private fun GatewayRuntimeState.toControllerStatus(running: Boolean) = RuntimeControllerStatus(
        running = running,
        gatewayRunning = gatewayRunning,
        activeAdapterCount = activeAdapterCount,
        lastError = lastError,
        processingSessionIds = processingSessionIds,
        mcpSnapshot = mcpSnapshot
    )

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
