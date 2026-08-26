package com.palmclaw.runtime.alwayson

import android.app.Application
import com.palmclaw.channels.ChannelAdapterIdentity
import com.palmclaw.channels.ChannelBindingHealthState
import com.palmclaw.channels.ChannelBindingRuntimeProjector
import com.palmclaw.channels.ChannelRuntimeSnapshot
import com.palmclaw.channels.ChannelRuntimeSnapshotSource
import com.palmclaw.config.SessionChannelBinding
import com.palmclaw.runtime.GatewayRuntimeOwner
import com.palmclaw.runtime.GatewayRuntimeSupervisor
import com.palmclaw.runtime.RuntimeControllerStatus
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.StateFlow

internal interface AlwaysOnGatewayRuntimePort {
    val status: StateFlow<RuntimeControllerStatus>

    suspend fun acquireOwnership()

    suspend fun releaseOwnership()
}

internal class SupervisorAlwaysOnGatewayRuntimePort(
    private val appProvider: () -> Application
) : AlwaysOnGatewayRuntimePort {
    override val status: StateFlow<RuntimeControllerStatus>
        get() = GatewayRuntimeSupervisor.status

    override suspend fun acquireOwnership() {
        GatewayRuntimeSupervisor.acquireGateway(
            context = appProvider(),
            owner = GatewayRuntimeOwner.ALWAYS_ON
        )
    }

    override suspend fun releaseOwnership() {
        GatewayRuntimeSupervisor.releaseGateway(
            owner = GatewayRuntimeOwner.ALWAYS_ON
        )
    }
}

/**
 * Projects process runtime state and per-adapter channel health into the small
 * availability model consumed by [AlwaysOnCoordinator].
 */
internal class GatewayAvailabilityAdapter(
    private val bindingsProvider: () -> List<SessionChannelBinding>,
    private val bindingProjector: ChannelBindingRuntimeProjector,
    private val snapshotSource: ChannelRuntimeSnapshotSource,
    private val runtime: AlwaysOnGatewayRuntimePort
) : GatewayAvailability {
    override suspend fun snapshot(): GatewayAvailabilitySnapshot {
        val runtimeStatus = runtime.status.value
        return GatewayAvailabilitySnapshot(
            runtime = if (runtimeStatus.running) {
                AlwaysOnRuntimeState.RUNNING
            } else {
                AlwaysOnRuntimeState.STOPPED
            },
            gateway = if (runtimeStatus.gatewayRunning) {
                AlwaysOnGatewayState.RUNNING
            } else {
                AlwaysOnGatewayState.STOPPED
            },
            channels = channelCounts()
        )
    }

    override suspend fun ensureAvailable(): GatewayEnsureResult {
        if (configuredAdapters().isEmpty()) {
            return GatewayEnsureResult.Blocked("Configure at least one remote channel")
        }
        return try {
            runtime.acquireOwnership()
            val current = runtime.status.value
            when {
                !current.running -> GatewayEnsureResult.Starting
                !current.gatewayRunning -> GatewayEnsureResult.Starting
                else -> GatewayEnsureResult.Available
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            GatewayEnsureResult.Failed("Gateway runtime could not start")
        }
    }

    override suspend fun releaseOwnership(): GatewayReleaseResult {
        return try {
            runtime.releaseOwnership()
            GatewayReleaseResult.Released
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            GatewayReleaseResult.Failed("Gateway ownership could not be released")
        }
    }

    private fun channelCounts(): AlwaysOnChannelCounts {
        val adapters = configuredAdapters()
        var ready = 0
        var reconnecting = 0
        var blocked = 0
        adapters.forEach { adapter ->
            when (adapter.health(snapshotSource)) {
                ChannelBindingHealthState.READY -> ready += 1
                ChannelBindingHealthState.RECONNECTING -> reconnecting += 1
                ChannelBindingHealthState.BLOCKED -> blocked += 1
                ChannelBindingHealthState.STARTING,
                ChannelBindingHealthState.STOPPED -> Unit
            }
        }
        return AlwaysOnChannelCounts(
            configured = adapters.size,
            ready = ready,
            reconnecting = reconnecting,
            blocked = blocked
        )
    }

    private fun configuredAdapters(): List<ConfiguredAdapter> = bindingsProvider()
        .asSequence()
        .filter(bindingProjector::canStartAdapter)
        .mapNotNull { binding ->
            val channel = binding.channel.trim().lowercase(Locale.US)
            val primaryKey = ChannelAdapterIdentity.primaryKeyForBinding(binding)
                ?: return@mapNotNull null
            ConfiguredAdapter(
                channel = channel,
                primaryKey = primaryKey,
                diagnosticKeys = ChannelAdapterIdentity.keysForBinding(binding).toSet()
            )
        }
        .groupBy { adapter -> adapter.channel to adapter.primaryKey }
        .values
        .map { grouped ->
            grouped.first().copy(
                diagnosticKeys = grouped.flatMap { it.diagnosticKeys }.toSet()
            )
        }

    private data class ConfiguredAdapter(
        val channel: String,
        val primaryKey: String,
        val diagnosticKeys: Set<String>
    ) {
        fun health(source: ChannelRuntimeSnapshotSource): ChannelBindingHealthState {
            val snapshots = diagnosticKeys.map { key -> source.getSnapshot(channel, key) }
            return snapshots.highestPriorityHealth()
        }
    }
}

private fun List<ChannelRuntimeSnapshot>.highestPriorityHealth(): ChannelBindingHealthState = when {
    any { it.state == ChannelBindingHealthState.READY } -> ChannelBindingHealthState.READY
    any { it.state == ChannelBindingHealthState.RECONNECTING } -> ChannelBindingHealthState.RECONNECTING
    any { it.state == ChannelBindingHealthState.BLOCKED } -> ChannelBindingHealthState.BLOCKED
    any { it.state == ChannelBindingHealthState.STARTING } -> ChannelBindingHealthState.STARTING
    else -> ChannelBindingHealthState.STOPPED
}
