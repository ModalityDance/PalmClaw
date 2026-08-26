package com.palmclaw.mcp

import com.palmclaw.config.McpHttpConfig
import com.palmclaw.config.McpHttpConfigNormalizer
import com.palmclaw.config.McpHttpServerConfig
import com.palmclaw.mcp.transport.McpClientSession
import com.palmclaw.mcp.transport.McpPage
import com.palmclaw.mcp.transport.McpRemotePrompt
import com.palmclaw.mcp.transport.McpRemoteResource
import com.palmclaw.mcp.transport.McpRemoteResourceTemplate
import com.palmclaw.mcp.transport.McpRemoteTool
import com.palmclaw.mcp.transport.McpServerEvent
import com.palmclaw.mcp.transport.McpTransportClientFactory
import com.palmclaw.mcp.transport.McpTransportConnectRequest
import com.palmclaw.mcp.transport.McpTransportKind
import com.palmclaw.tools.OwnedToolReplaceResult
import com.palmclaw.tools.ToolRegistry
import com.palmclaw.tools.ToolRegistryOwner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

interface McpRuntimeLifecycle {
    val snapshot: StateFlow<McpRuntimeSnapshot>

    suspend fun reconcile(config: McpHttpConfig): McpApplyResult

    /** Immediately revokes published tools and cancels lifecycle work. Idempotent. */
    fun close()
}

data class McpRuntimeSnapshot(
    val enabled: Boolean = false,
    val generation: Long = 0L,
    val servers: List<McpServerSnapshot> = emptyList(),
    val issues: List<McpRuntimeIssue> = emptyList()
)

data class McpRuntimeIssue(
    val code: String,
    val detail: String
)

enum class McpServerPhase {
    DISABLED,
    ACTION_REQUIRED,
    CONNECTING,
    READY,
    DEGRADED,
    ERROR
}

data class McpServerSnapshot(
    val serverId: String,
    val serverName: String,
    val endpoint: String,
    val configFingerprint: String? = null,
    val phase: McpServerPhase,
    val usable: Boolean,
    val detail: String? = null,
    val toolNames: List<String> = emptyList(),
    val toolCount: Int = toolNames.size,
    val resourceCount: Int = 0,
    val resourceTemplateCount: Int = 0,
    val promptCount: Int = 0,
    val completionSupported: Boolean = false,
    val transport: McpTransportKind? = null,
    val protocolVersion: String? = null,
    val endpointSecurity: McpEndpointSecurity? = null,
    val insecureWarning: String? = null,
    val generation: Long = 0L
)

data class McpApplyResult(
    val applied: Boolean,
    val snapshot: McpRuntimeSnapshot,
    val connectedServerIds: List<String> = emptyList(),
    val reusedServerIds: List<String> = emptyList(),
    val failedServerIds: List<String> = emptyList()
)

internal class DefaultMcpRuntimeLifecycle(
    private val transportFactory: McpTransportClientFactory,
    private val toolRegistry: ToolRegistry,
    parentScope: CoroutineScope,
    private val networkAvailability: McpNetworkAvailability = AlwaysOnlineMcpNetworkAvailability,
    private val retryPolicy: McpConnectionRetryPolicy = McpConnectionRetryPolicy(),
    private val retryDelay: McpRetryDelay = CoroutineMcpRetryDelay,
    private val retryJitter: McpRetryJitter = RandomMcpRetryJitter,
    private val recoveryPolicy: McpBackgroundRecoveryPolicy = McpBackgroundRecoveryPolicy(),
    private val recoveryDelay: McpRetryDelay = CoroutineMcpRetryDelay
) : McpRuntimeLifecycle {
    private val lifecycleJob = SupervisorJob(parentScope.coroutineContext[Job])
    private val lifecycleScope = CoroutineScope(parentScope.coroutineContext + lifecycleJob)
    private val cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val reconcileMutex = Mutex()
    private val lifecyclePublicationLock = Any()
    private val closed = AtomicBoolean(false)
    private val generationCounter = AtomicLong(0L)
    private val sessionEpochCounter = AtomicLong(0L)
    private val recoveryEpochCounter = AtomicLong(0L)
    private val _snapshot = MutableStateFlow(McpRuntimeSnapshot())

    @Volatile
    private var activeServers: Map<String, ActiveServer> = emptyMap()

    @Volatile
    private var desiredConfig: McpHttpConfig = McpHttpConfig()

    @Volatile
    private var recoveryJob: Job? = null

    /** Owners published during discovery but not yet committed into [activeServers]. */
    private val pendingPublishedServerIds = mutableSetOf<String>()

    override val snapshot: StateFlow<McpRuntimeSnapshot> = _snapshot.asStateFlow()

    override suspend fun reconcile(config: McpHttpConfig): McpApplyResult {
        // Cancel outside the mutex so an explicit configuration apply can interrupt a
        // background recovery that is currently waiting or connecting.
        cancelRecovery()
        return reconcileMutex.withLock {
            cancelRecovery()
            val outcome = reconcileLocked(config)
            scheduleRecovery(config, outcome)
            outcome.applyResult
        }
    }

    private suspend fun reconcileLocked(
        config: McpHttpConfig,
        recoveryTargetServerIds: Set<String>? = null
    ): ReconcileOutcome {
        check(!closed.get()) { "MCP runtime lifecycle is closed" }
        desiredConfig = config
        val generation = generationCounter.incrementAndGet()
        val desired = normalizedServers(config)
        val previousSnapshot = _snapshot.value

        if (!config.enabled) {
            val previous = activeServers
            activeServers = emptyMap()
            previous.values.forEach { revokeAndCloseNow(it) }
            toolRegistry.removeOwned(CONTENT_OWNER)
            if (closed.get()) throw CancellationException("MCP runtime lifecycle closed")
            val disabled = desired.map { server ->
                val endpoint = McpEndpointPolicy.evaluate(
                    rawUrl = server.serverUrl,
                    authToken = server.authToken,
                    insecureHttpAllowedOrigin = server.insecureHttpAllowedOrigin
                )
                McpServerSnapshot(
                    serverId = server.id,
                    serverName = normalizedServerName(server.serverName),
                    endpoint = McpEndpointPolicy.safeDisplayUrl(server.serverUrl, endpoint.canonicalUrl),
                    configFingerprint = server.configurationFingerprint(endpoint.canonicalUrl),
                    phase = McpServerPhase.DISABLED,
                    usable = false,
                    detail = "MCP is disabled",
                    generation = generation
                )
            }
            return ReconcileOutcome(
                applyResult = publishResult(
                    snapshot = McpRuntimeSnapshot(false, generation, disabled),
                    connected = emptyList(),
                    reused = emptyList(),
                    failed = emptyList()
                )
            )
        }

        val duplicateIds = desired.groupingBy { it.id }.eachCount().filterValues { it > 1 }.keys
        val duplicateNames = desired
            .groupingBy { normalizedServerName(it.serverName) }
            .eachCount()
            .filterValues { it > 1 }
            .keys
        val desiredIds = desired.map { it.id }.toSet()
        val previous = activeServers
        activeServers = previous.filterKeys { it in desiredIds }
        previous.filterKeys { it !in desiredIds }.values.forEach { revokeAndCloseNow(it) }

        val connectingStatuses = desired.map { server ->
            val duplicate = server.id in duplicateIds || normalizedServerName(server.serverName) in duplicateNames
            previousSnapshot.servers.firstOrNull { status ->
                recoveryTargetServerIds != null &&
                    server.id !in recoveryTargetServerIds &&
                    status.serverId == server.id
            }?.copy(generation = generation) ?: baseStatus(
                    server = server,
                    generation = generation,
                    phase = if (duplicate) McpServerPhase.ERROR else McpServerPhase.CONNECTING,
                    detail = if (duplicate) "Server id and server name must both be unique" else "Connecting"
                )
        }
        publishLiveSnapshotOrThrow(McpRuntimeSnapshot(true, generation, connectingStatuses))

        val nextActive = linkedMapOf<String, ActiveServer>()
        val statuses = mutableListOf<McpServerSnapshot>()
        val connected = mutableListOf<String>()
        val reused = mutableListOf<String>()
        val failed = mutableListOf<String>()
        val runtimeIssues = mutableListOf<McpRuntimeIssue>()
        val retryableServerIds = linkedSetOf<String>()
        val waitingForNetworkServerIds = linkedSetOf<String>()

        try {
            for (server in desired) {
                if (closed.get()) throw CancellationException("MCP runtime lifecycle closed")
                val serverName = normalizedServerName(server.serverName)

                if (recoveryTargetServerIds != null && server.id !in recoveryTargetServerIds) {
                    val existing = previous[server.id]
                    if (existing != null) {
                        nextActive[server.id] = existing
                        reused += server.id
                        statuses += existing.status.copy(generation = generation)
                    } else {
                        val priorStatus = previousSnapshot.servers
                            .firstOrNull { status -> status.serverId == server.id }
                            ?: baseStatus(
                                server = server,
                                generation = generation,
                                phase = McpServerPhase.ERROR,
                                detail = "MCP server remains unavailable"
                            )
                        statuses += priorStatus.copy(generation = generation)
                        failed += server.id
                    }
                    continue
                }

                if (server.id in duplicateIds || serverName in duplicateNames) {
                    activeServers = activeServers - server.id
                    previous[server.id]?.let { revokeAndCloseNow(it) }
                    revokeOwnedTools(server.id)
                    failed += server.id
                    statuses += baseStatus(
                        server,
                        generation,
                        McpServerPhase.ERROR,
                        "Server id and server name must both be unique"
                    )
                    continue
                }

                val endpoint = McpEndpointPolicy.evaluate(
                    rawUrl = server.serverUrl,
                    authToken = server.authToken,
                    insecureHttpAllowedOrigin = server.insecureHttpAllowedOrigin
                )
                if (!endpoint.canConnect) {
                    activeServers = activeServers - server.id
                    previous[server.id]?.let { revokeAndCloseNow(it) }
                    revokeOwnedTools(server.id)
                    failed += server.id
                    statuses += baseStatus(
                        server = server,
                        generation = generation,
                        phase = if (endpoint.requiresAction) McpServerPhase.ACTION_REQUIRED else McpServerPhase.ERROR,
                        detail = endpoint.message,
                        endpoint = endpoint
                    )
                    continue
                }

                val fingerprint = checkNotNull(server.configurationFingerprint(endpoint.canonicalUrl))
                val existing = previous[server.id]
                if (existing != null && existing.fingerprint == fingerprint) {
                    nextActive[server.id] = existing
                    reused += server.id
                    statuses += existing.status.copy(generation = generation)
                    continue
                }

                if (existing != null) {
                    activeServers = activeServers - server.id
                    revokeAndCloseNow(existing)
                } else {
                    revokeOwnedTools(server.id)
                }

                val networkScope = checkNotNull(endpoint.networkScope)
                if (!networkAvailability.isAvailable(networkScope)) {
                    failed += server.id
                    retryableServerIds += server.id
                    waitingForNetworkServerIds += server.id
                    statuses += baseStatus(
                        server = server,
                        generation = generation,
                        phase = McpServerPhase.CONNECTING,
                        detail = "Waiting for network",
                        endpoint = endpoint
                    )
                    continue
                }

                var candidateSession: McpClientSession? = null
                var connectionEstablished = false
                try {
                    val session = connectWithRetry(
                        request = McpTransportConnectRequest(
                            serverId = server.id,
                            endpoint = checkNotNull(endpoint.canonicalUrl),
                            bearerToken = server.authToken.ifBlank { null },
                            requestTimeoutMillis = server.toolTimeoutSeconds.coerceIn(5, 300) * 1_000L
                        )
                    )
                    candidateSession = session
                    connectionEstablished = true
                    val tools = if (session.capabilities.tools) {
                        allPages(maxItems = MAX_DISCOVERED_TOOLS) { cursor -> session.listTools(cursor) }
                    } else {
                        emptyList()
                    }
                    val wrappers = tools.map { remote ->
                        McpRemoteToolAdapter(
                            serverId = server.id,
                            serverName = serverName,
                            remote = remote,
                            session = session,
                            timeoutSeconds = server.toolTimeoutSeconds
                        )
                    }
                    val resources = if (session.capabilities.resources) allPages { cursor -> session.listResources(cursor) } else emptyList()
                    val templates = if (session.capabilities.resources) allPages { cursor -> session.listResourceTemplates(cursor) } else emptyList()
                    val prompts = if (session.capabilities.prompts) allPages { cursor -> session.listPrompts(cursor) } else emptyList()
                    when (val publication = publishCandidateTools(server.id, wrappers)) {
                        is OwnedToolReplaceResult.Rejected -> {
                            session.closeForCleanup()
                            candidateSession = null
                            failed += server.id
                            runtimeIssues += McpRuntimeIssue(
                                code = "remote_tool_name_conflict",
                                detail = "Server $serverName has conflicting published tool names: " +
                                    publication.toolNames.joinToString()
                            )
                            statuses += baseStatus(
                                server,
                                generation,
                                McpServerPhase.ERROR,
                                "Remote tool name conflict: ${publication.toolNames.joinToString()}",
                                endpoint
                            )
                            continue
                        }
                        is OwnedToolReplaceResult.Applied -> Unit
                    }
                    val status = readyStatus(
                        server = server,
                        generation = generation,
                        endpoint = endpoint,
                        session = session,
                        wrappers = wrappers,
                        resources = resources,
                        templates = templates,
                        prompts = prompts
                    )
                    val epoch = sessionEpochCounter.incrementAndGet()
                    val eventJob = lifecycleScope.launch(start = CoroutineStart.LAZY) {
                        collectEvents(server.id, epoch, session)
                    }
                    nextActive[server.id] = ActiveServer(
                        config = server,
                        fingerprint = fingerprint,
                        session = session,
                        status = status,
                        epoch = epoch,
                        eventJob = eventJob
                    )
                    candidateSession = null
                    connected += server.id
                    statuses += status
                } catch (cancelled: CancellationException) {
                    candidateSession?.closeForCleanup()
                    throw cancelled
                } catch (failure: Throwable) {
                    candidateSession?.closeForCleanup()
                    revokeOwnedTools(server.id)
                    failed += server.id
                    if (!connectionEstablished && retryPolicy.isRetryable(failure)) {
                        retryableServerIds += server.id
                    }
                    statuses += baseStatus(
                        server,
                        generation,
                        McpServerPhase.ERROR,
                        safeConnectionFailure(failure),
                        endpoint
                    )
                }
            }
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) {
                nextActive.values
                    .filterNot { active -> previous[active.config.id] === active }
                    .forEach { active -> revokeAndCloseNow(active) }
            }
            throw cancelled
        } catch (failure: Throwable) {
            withContext(NonCancellable) {
                nextActive.values
                    .filterNot { active -> previous[active.config.id] === active }
                    .forEach { active -> revokeAndCloseNow(active) }
            }
            throw failure
        }

        val committed = synchronized(lifecyclePublicationLock) {
            if (closed.get()) {
                null
            } else {
                activeServers = nextActive.toMap()
                pendingPublishedServerIds.removeAll(nextActive.keys)
                nextActive.values.forEach { active ->
                    if (!active.eventJob.isActive && !active.eventJob.isCompleted) active.eventJob.start()
                }
                val carriedIssues = if (recoveryTargetServerIds == null) {
                    emptyList()
                } else {
                    previousSnapshot.issues
                }
                val issues = (carriedIssues + runtimeIssues + publishContentToolLocked()).distinct()
                val finalSnapshot = McpRuntimeSnapshot(
                    enabled = true,
                    generation = generation,
                    servers = statuses.sortedBy { it.serverName },
                    issues = issues
                )
                _snapshot.value = finalSnapshot
                ReconcileOutcome(
                    applyResult = applyResult(finalSnapshot, connected, reused, failed),
                    retryableServerIds = retryableServerIds,
                    waitingForNetworkServerIds = waitingForNetworkServerIds
                )
            }
        }
        if (committed != null) return committed

        withContext(NonCancellable) {
            nextActive.values
                .filterNot { active -> previous[active.config.id] === active }
                .forEach { active -> revokeAndCloseNow(active) }
        }
        throw CancellationException("MCP runtime lifecycle closed")
    }

    override fun close() {
        val previous = synchronized(lifecyclePublicationLock) {
            if (!closed.compareAndSet(false, true)) return
            cancelRecovery()
            lifecycleJob.cancel()
            val owned = activeServers
            activeServers = emptyMap()
            owned.values.forEach { active ->
                active.eventJob.cancel()
                revokeOwnedToolsLocked(active.config.id)
            }
            pendingPublishedServerIds.toList().forEach(::revokeOwnedToolsLocked)
            toolRegistry.removeOwned(CONTENT_OWNER)
            _snapshot.value = McpRuntimeSnapshot(
                enabled = false,
                generation = generationCounter.incrementAndGet(),
                servers = owned.values.map { active ->
                    active.status.copy(
                        phase = McpServerPhase.DISABLED,
                        usable = false,
                        detail = "MCP runtime closed"
                    )
                }
            )
            owned
        }
        cleanupScope.launch {
            try {
                withContext(NonCancellable) {
                    previous.values.forEach { active ->
                        try {
                            active.session.closeForCleanup()
                        } catch (_: Throwable) {
                            // Continue closing the remaining sessions. Authority is already revoked.
                        }
                    }
                }
            } finally {
                cleanupScope.cancel()
            }
        }
    }

    private fun publishResult(
        snapshot: McpRuntimeSnapshot,
        connected: List<String>,
        reused: List<String>,
        failed: List<String>
    ): McpApplyResult {
        publishLiveSnapshotOrThrow(snapshot)
        return applyResult(snapshot, connected, reused, failed)
    }

    private fun applyResult(
        snapshot: McpRuntimeSnapshot,
        connected: List<String>,
        reused: List<String>,
        failed: List<String>
    ) = McpApplyResult(
            applied = failed.isEmpty(),
            snapshot = snapshot,
            connectedServerIds = connected.sorted(),
            reusedServerIds = reused.sorted(),
            failedServerIds = failed.distinct().sorted()
        )

    private fun publishLiveSnapshotOrThrow(snapshot: McpRuntimeSnapshot) {
        if (!publishLiveSnapshot(snapshot)) {
            throw CancellationException("MCP runtime lifecycle closed")
        }
    }

    private fun publishLiveSnapshot(snapshot: McpRuntimeSnapshot): Boolean =
        synchronized(lifecyclePublicationLock) {
            if (closed.get()) {
                false
            } else {
                _snapshot.value = snapshot
                true
            }
        }

    private fun scheduleRecovery(config: McpHttpConfig, initial: ReconcileOutcome) {
        if (closed.get() || !config.enabled || !initial.needsRecovery) return
        val sourceGeneration = initial.applyResult.snapshot.generation
        if (generationCounter.get() != sourceGeneration || desiredConfig != config) return

        val recoveryEpoch = recoveryEpochCounter.incrementAndGet()
        val job = lifecycleScope.launch(start = CoroutineStart.LAZY) {
            var expectedGeneration = sourceGeneration
            var pending = initial
            var attempt = 1
            try {
                while (pending.needsRecovery) {
                    if (pending.waitingOnlyForExternalNetwork) {
                        networkAvailability.awaitAvailable(McpEndpointNetworkScope.EXTERNAL)
                    } else {
                        recoveryDelay.wait(
                            recoveryPolicy.delayBeforeAttempt(
                                attempt = attempt,
                                jitterUnit = retryJitter.unitSample()
                            )
                        )
                    }
                    currentCoroutineContext().ensureActive()

                    val next = reconcileMutex.withLock {
                        if (closed.get() ||
                            recoveryEpochCounter.get() != recoveryEpoch ||
                            generationCounter.get() != expectedGeneration ||
                            desiredConfig != config
                        ) {
                            return@withLock null
                        }
                        reconcileLocked(
                            config = config,
                            recoveryTargetServerIds = pending.retryableServerIds
                        )
                    } ?: return@launch

                    pending = next
                    expectedGeneration = next.applyResult.snapshot.generation
                    attempt = (attempt + 1).coerceAtMost(MAX_RECOVERY_ATTEMPT_EXPONENT)
                }
            } finally {
                if (recoveryEpochCounter.get() == recoveryEpoch) {
                    recoveryJob = null
                }
            }
        }
        recoveryJob = job
        job.start()
    }

    private fun cancelRecovery() {
        recoveryEpochCounter.incrementAndGet()
        recoveryJob?.cancel()
        recoveryJob = null
    }

    private data class ReconcileOutcome(
        val applyResult: McpApplyResult,
        val retryableServerIds: Set<String> = emptySet(),
        val waitingForNetworkServerIds: Set<String> = emptySet()
    ) {
        val needsRecovery: Boolean
            get() = retryableServerIds.isNotEmpty()

        val waitingOnlyForExternalNetwork: Boolean
            get() = retryableServerIds.isNotEmpty() &&
                retryableServerIds == waitingForNetworkServerIds
    }

    /** Must be called while holding [lifecyclePublicationLock]. */
    private fun publishContentToolLocked(): List<McpRuntimeIssue> {
        val capable = activeServers.values.any { active ->
            val capabilities = active.session.capabilities
            capabilities.resources || capabilities.prompts || capabilities.completions
        }
        if (!capable) {
            toolRegistry.removeOwned(CONTENT_OWNER)
            return emptyList()
        }
        val result = toolRegistry.replaceOwned(
            CONTENT_OWNER,
            listOf(
                McpContentTool {
                    activeServers.values.map { active ->
                        McpContentTarget(
                            serverId = active.config.id,
                            serverName = normalizedServerName(active.config.serverName),
                            session = active.session
                        )
                    }
                }
            )
        )
        return if (result is OwnedToolReplaceResult.Rejected) {
            listOf(
                McpRuntimeIssue(
                    code = "content_tool_name_conflict",
                    detail = "Could not publish mcp_content: ${result.toolNames.joinToString()}"
                )
            )
        } else {
            emptyList()
        }
    }

    private suspend fun collectEvents(serverId: String, epoch: Long, session: McpClientSession) {
        var reconnectConfig: McpHttpConfig? = null
        var reconnectGeneration: Long? = null
        var capabilityRefreshJob: Job? = null
        try {
            session.events.takeWhile { event ->
                if (event == McpServerEvent.Disconnected) {
                    capabilityRefreshJob?.cancel()
                    reconcileMutex.withLock {
                        val committed = synchronized(lifecyclePublicationLock) {
                            val active = activeServers[serverId]
                            if (closed.get() || active?.epoch != epoch || active.session !== session) {
                                false
                            } else {
                                activeServers = activeServers - serverId
                                revokeOwnedToolsLocked(serverId)
                                val currentSnapshot = _snapshot.value
                                val contentIssues = publishContentToolLocked()
                                _snapshot.value = currentSnapshot.copy(
                                    servers = currentSnapshot.servers.map { status ->
                                        if (status.serverId == serverId) {
                                            status.copy(
                                                phase = McpServerPhase.CONNECTING,
                                                usable = false,
                                                detail = "Connection closed; reconnecting"
                                            )
                                        } else {
                                            status
                                        }
                                    },
                                    issues = contentIssues
                                )
                                reconnectConfig = desiredConfig
                                reconnectGeneration = generationCounter.get()
                                true
                            }
                        }
                        if (!committed) {
                            return@withLock false
                        }
                        false
                    }
                } else {
                    val isCurrent = reconcileMutex.withLock {
                        val active = activeServers[serverId]
                        !closed.get() && active?.epoch == epoch && active.session === session
                    }
                    if (isCurrent) {
                        capabilityRefreshJob?.cancel()
                        capabilityRefreshJob = lifecycleScope.launch {
                            delay(EVENT_DEBOUNCE_MILLIS)
                            reconcileMutex.withLock {
                                val active = activeServers[serverId]
                                if (!closed.get() && active?.epoch == epoch && active.session === session) {
                                    refreshChangedCapabilities(active)
                                }
                            }
                        }
                    }
                    isCurrent
                }
            }.collect()
        } finally {
            capabilityRefreshJob?.cancel()
        }
        reconnectConfig?.let { config ->
            session.closeForCleanup()
            reconcileFromDisconnected(
                config = config,
                expectedGeneration = checkNotNull(reconnectGeneration)
            )
        }
    }

    private suspend fun reconcileFromDisconnected(
        config: McpHttpConfig,
        expectedGeneration: Long
    ) {
        reconcileMutex.withLock {
            if (closed.get() ||
                generationCounter.get() != expectedGeneration ||
                desiredConfig != config
            ) {
                return@withLock
            }
            cancelRecovery()
            val outcome = reconcileLocked(config)
            scheduleRecovery(config, outcome)
        }
    }

    private suspend fun refreshChangedCapabilities(active: ActiveServer) {
        try {
            var status = active.status
            var refreshedWrappers: List<McpRemoteToolAdapter>? = null
            if (active.session.capabilities.tools) {
                val tools = allPages(maxItems = MAX_DISCOVERED_TOOLS) { cursor -> active.session.listTools(cursor) }
                refreshedWrappers = tools.map { remote ->
                    McpRemoteToolAdapter(
                        serverId = active.config.id,
                        serverName = normalizedServerName(active.config.serverName),
                        remote = remote,
                        session = active.session,
                        timeoutSeconds = active.config.toolTimeoutSeconds
                    )
                }
            }
            if (active.session.capabilities.resources) {
                val resources = allPages { cursor -> active.session.listResources(cursor) }
                val templates = allPages { cursor -> active.session.listResourceTemplates(cursor) }
                status = status.copy(
                    resourceCount = resources.size,
                    resourceTemplateCount = templates.size
                )
            }
            if (active.session.capabilities.prompts) {
                val prompts = allPages { cursor -> active.session.listPrompts(cursor) }
                status = status.copy(promptCount = prompts.size)
            }
            commitCapabilityRefresh(active, status, refreshedWrappers)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            commitCapabilityRefresh(
                active,
                active.status.copy(
                    phase = McpServerPhase.DEGRADED,
                    detail = "MCP capability refresh failed"
                ),
                refreshedWrappers = null
            )
        }
    }

    private fun commitCapabilityRefresh(
        expected: ActiveServer,
        discoveredStatus: McpServerSnapshot,
        refreshedWrappers: List<McpRemoteToolAdapter>?
    ) {
        synchronized(lifecyclePublicationLock) {
            if (closed.get()) return
            val current = activeServers[expected.config.id]
            if (current?.epoch != expected.epoch || current.session !== expected.session) return

            val status = if (refreshedWrappers == null) {
                discoveredStatus
            } else {
                when (val result = toolRegistry.replaceOwned(owner(expected.config.id), refreshedWrappers)) {
                    is OwnedToolReplaceResult.Applied -> discoveredStatus.copy(
                        phase = McpServerPhase.READY,
                        usable = result.publishedToolNames.isNotEmpty() ||
                            expected.session.capabilities.resources ||
                            expected.session.capabilities.prompts ||
                            expected.session.capabilities.completions,
                        detail = null,
                        toolNames = result.publishedToolNames,
                        toolCount = result.publishedToolNames.size
                    )
                    is OwnedToolReplaceResult.Rejected -> discoveredStatus.copy(
                        phase = McpServerPhase.DEGRADED,
                        detail = "Remote tool refresh conflict: ${result.toolNames.joinToString()}"
                    )
                }
            }
            activeServers = activeServers + (expected.config.id to current.copy(status = status))
            _snapshot.value = _snapshot.value.copy(
                servers = _snapshot.value.servers.map { existing ->
                    if (existing.serverId == expected.config.id) {
                        status.copy(generation = _snapshot.value.generation)
                    } else {
                        existing
                    }
                }
            )
        }
    }

    private suspend fun revokeAndCloseNow(active: ActiveServer) {
        active.eventJob.cancel()
        revokeOwnedTools(active.config.id)
        active.session.closeForCleanup()
    }

    private fun publishCandidateTools(
        serverId: String,
        wrappers: List<McpRemoteToolAdapter>
    ): OwnedToolReplaceResult = synchronized(lifecyclePublicationLock) {
        if (closed.get()) throw CancellationException("MCP runtime lifecycle closed")
        toolRegistry.replaceOwned(owner(serverId), wrappers).also { result ->
            if (result is OwnedToolReplaceResult.Applied) {
                pendingPublishedServerIds += serverId
            } else {
                pendingPublishedServerIds -= serverId
            }
        }
    }

    private fun revokeOwnedTools(serverId: String) {
        synchronized(lifecyclePublicationLock) {
            revokeOwnedToolsLocked(serverId)
        }
    }

    /** Must be called while holding [lifecyclePublicationLock]. */
    private fun revokeOwnedToolsLocked(serverId: String) {
        pendingPublishedServerIds -= serverId
        toolRegistry.removeOwned(owner(serverId))
    }

    private suspend fun McpClientSession.closeForCleanup() {
        var closeCancellation: CancellationException? = null
        withContext(NonCancellable) {
            try {
                close()
            } catch (cancelled: CancellationException) {
                closeCancellation = cancelled
            } catch (_: Throwable) {
                // A remote close failure must not restore an already-revoked capability.
            }
        }
        currentCoroutineContext().ensureActive()
        closeCancellation?.let { throw it }
    }

    private suspend fun connectWithRetry(request: McpTransportConnectRequest): McpClientSession {
        var failedAttempt = 0
        while (true) {
            try {
                return transportFactory.connect(request)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                failedAttempt += 1
                val retryAfter = retryPolicy.delayBeforeNextAttempt(
                    failedAttempt = failedAttempt,
                    failure = failure,
                    jitterUnit = retryJitter.unitSample()
                ) ?: throw failure
                retryDelay.wait(retryAfter)
            }
        }
    }

    private fun normalizedServers(config: McpHttpConfig): List<McpHttpServerConfig> {
        val explicit = McpHttpConfigNormalizer.normalizeServers(config.servers)
        if (explicit.isNotEmpty()) return explicit
        if (config.serverUrl.isBlank()) return emptyList()
        return listOf(
            McpHttpServerConfig(
                id = "mcp_1",
                serverName = config.serverName,
                serverUrl = config.serverUrl,
                authToken = config.authToken,
                toolTimeoutSeconds = config.toolTimeoutSeconds,
                insecureHttpAllowedOrigin = config.insecureHttpAllowedOrigin
            )
        )
    }

    private fun baseStatus(
        server: McpHttpServerConfig,
        generation: Long,
        phase: McpServerPhase,
        detail: String?,
        endpoint: McpEndpointDecision? = null
    ) = McpServerSnapshot(
        serverId = server.id,
        serverName = normalizedServerName(server.serverName),
        endpoint = McpEndpointPolicy.safeDisplayUrl(server.serverUrl, endpoint?.canonicalUrl),
        configFingerprint = server.configurationFingerprint(
            endpoint?.canonicalUrl ?: McpEndpointPolicy.evaluate(
                rawUrl = server.serverUrl,
                authToken = server.authToken,
                insecureHttpAllowedOrigin = server.insecureHttpAllowedOrigin
            ).canonicalUrl
        ),
        phase = phase,
        usable = phase == McpServerPhase.READY || phase == McpServerPhase.DEGRADED,
        detail = detail,
        endpointSecurity = endpoint?.security,
        insecureWarning = endpoint?.warning,
        generation = generation
    )

    private fun readyStatus(
        server: McpHttpServerConfig,
        generation: Long,
        endpoint: McpEndpointDecision,
        session: McpClientSession,
        wrappers: List<McpRemoteToolAdapter>,
        resources: List<McpRemoteResource>,
        templates: List<McpRemoteResourceTemplate>,
        prompts: List<McpRemotePrompt>
    ) = McpServerSnapshot(
        serverId = server.id,
        serverName = normalizedServerName(server.serverName),
        endpoint = McpEndpointPolicy.safeDisplayUrl(server.serverUrl, endpoint.canonicalUrl),
        configFingerprint = server.configurationFingerprint(endpoint.canonicalUrl),
        phase = McpServerPhase.READY,
        usable = wrappers.isNotEmpty() ||
            session.capabilities.resources ||
            session.capabilities.prompts ||
            session.capabilities.completions,
        toolNames = wrappers.map { it.name }.sorted(),
        resourceCount = resources.size,
        resourceTemplateCount = templates.size,
        promptCount = prompts.size,
        completionSupported = session.capabilities.completions,
        transport = session.negotiated.transport,
        protocolVersion = session.negotiated.protocolVersion,
        endpointSecurity = endpoint.security,
        insecureWarning = endpoint.warning,
        generation = generation
    )

    private fun safeConnectionFailure(failure: Throwable): String =
        when (failure) {
            is com.palmclaw.mcp.transport.McpTransportException -> when (failure.code) {
                com.palmclaw.mcp.transport.McpTransportErrorCode.AUTHENTICATION_REQUIRED -> "MCP authentication was rejected"
                com.palmclaw.mcp.transport.McpTransportErrorCode.PERMISSION_DENIED -> "MCP access was denied"
                com.palmclaw.mcp.transport.McpTransportErrorCode.NOT_FOUND -> "MCP endpoint was not found"
                com.palmclaw.mcp.transport.McpTransportErrorCode.RATE_LIMITED -> "MCP server rate limited the connection"
                com.palmclaw.mcp.transport.McpTransportErrorCode.SERVER_ERROR -> "MCP server is unavailable"
                com.palmclaw.mcp.transport.McpTransportErrorCode.TIMEOUT -> "MCP connection timed out"
                com.palmclaw.mcp.transport.McpTransportErrorCode.NETWORK -> "MCP network connection failed"
                com.palmclaw.mcp.transport.McpTransportErrorCode.PROTOCOL -> "MCP protocol negotiation failed"
                com.palmclaw.mcp.transport.McpTransportErrorCode.RESPONSE_TOO_LARGE -> "MCP response exceeded the configured limit"
                com.palmclaw.mcp.transport.McpTransportErrorCode.UNSUPPORTED -> "MCP transport is not supported"
            }
            else -> "MCP connection failed (${failure.javaClass.simpleName})"
        }

    private fun McpHttpServerConfig.configurationFingerprint(canonicalUrl: String?): String? =
        McpEndpointPolicy.configurationFingerprint(
            server = this,
            canonicalUrl = canonicalUrl
        )

    private data class ActiveServer(
        val config: McpHttpServerConfig,
        val fingerprint: String,
        val session: McpClientSession,
        val status: McpServerSnapshot,
        val epoch: Long,
        val eventJob: Job
    )

    companion object {
        private val CONTENT_OWNER = ToolRegistryOwner("mcp:content")
        private const val EVENT_DEBOUNCE_MILLIS = 250L

        internal fun owner(serverId: String) = ToolRegistryOwner("mcp:$serverId")

        internal fun normalizedServerName(input: String): String = input.trim()
            .lowercase(Locale.US)
            .replace(Regex("[^a-z0-9_\\-]+"), "_")
            .trim('_')
            .take(40)
            .ifBlank { "default" }

        internal suspend fun <T> allPages(
            maxItems: Int = MAX_DISCOVERY_ITEMS,
            fetch: suspend (String?) -> McpPage<T>
        ): List<T> {
            val items = mutableListOf<T>()
            val seenCursors = mutableSetOf<String>()
            var cursor: String? = null
            var pageCount = 0
            do {
                pageCount += 1
                if (pageCount > MAX_DISCOVERY_PAGES) {
                    throw IllegalStateException("MCP pagination exceeded $MAX_DISCOVERY_PAGES pages")
                }
                val page = fetch(cursor)
                items += page.items
                if (items.size > maxItems) {
                    throw IllegalStateException("MCP discovery exceeded $maxItems items")
                }
                val next = page.nextCursor?.takeIf { it.isNotBlank() }
                if (next != null && !seenCursors.add(next)) {
                    throw IllegalStateException("MCP pagination cursor repeated")
                }
                cursor = next
            } while (cursor != null)
            return items
        }

        private const val MAX_DISCOVERY_PAGES = 100
        private const val MAX_DISCOVERY_ITEMS = 1_000
        private const val MAX_DISCOVERED_TOOLS = 200
        private const val MAX_RECOVERY_ATTEMPT_EXPONENT = 31
    }
}
