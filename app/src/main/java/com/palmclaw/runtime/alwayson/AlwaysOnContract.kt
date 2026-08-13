package com.palmclaw.runtime.alwayson

import kotlinx.coroutines.flow.StateFlow

/** The single interface used by Always-on callers. */
internal interface AlwaysOnControl {
    val status: StateFlow<AlwaysOnStatus>

    suspend fun setEnabled(enabled: Boolean)

    suspend fun reconcile(trigger: AlwaysOnTrigger)
}

internal data class AlwaysOnStatus(
    val desired: Boolean = false,
    val phase: AlwaysOnPhase = AlwaysOnPhase.DISABLED,
    val shell: AlwaysOnShellState = AlwaysOnShellState.STOPPED,
    val notificationVisible: Boolean = false,
    val runtime: AlwaysOnRuntimeState = AlwaysOnRuntimeState.STOPPED,
    val gateway: AlwaysOnGatewayState = AlwaysOnGatewayState.STOPPED,
    val network: AlwaysOnNetworkState = AlwaysOnNetworkState.UNKNOWN,
    val channels: AlwaysOnChannelCounts = AlwaysOnChannelCounts(),
    val waitingFor: AlwaysOnWaitingReason? = null,
    val actionRequired: AlwaysOnActionRequired? = null,
    val lastError: String? = null,
    val lastTrigger: AlwaysOnTrigger? = null,
    val updatedAtEpochMillis: Long = 0L
)

internal enum class AlwaysOnPhase {
    DISABLED,
    STARTING,
    ONLINE,
    DEGRADED,
    RECOVERING,
    ACTION_REQUIRED
}

internal enum class AlwaysOnShellState {
    STOPPED,
    STARTING,
    RUNNING
}

internal enum class AlwaysOnRuntimeState {
    STOPPED,
    STARTING,
    RUNNING
}

internal enum class AlwaysOnGatewayState {
    STOPPED,
    STARTING,
    RUNNING
}

internal enum class AlwaysOnNetworkState {
    UNKNOWN,
    OFFLINE,
    ONLINE
}

internal data class AlwaysOnChannelCounts(
    val configured: Int = 0,
    val ready: Int = 0,
    val reconnecting: Int = 0,
    val blocked: Int = 0
) {
    init {
        require(configured >= 0) { "configured channel count must be non-negative" }
        require(ready >= 0) { "ready channel count must be non-negative" }
        require(reconnecting >= 0) { "reconnecting channel count must be non-negative" }
        require(blocked >= 0) { "blocked channel count must be non-negative" }
        require(ready + reconnecting + blocked <= configured) {
            "channel state counts cannot exceed the configured channel count"
        }
    }
}

internal data class AlwaysOnActionRequired(
    val reason: AlwaysOnActionRequiredReason,
    val message: String? = null
)

internal enum class AlwaysOnActionRequiredReason {
    NO_CHANNEL_CONFIGURED,
    SYSTEM_RESTRICTED,
    BACKGROUND_START_RESTRICTED,
    ALL_CHANNELS_BLOCKED,
    GATEWAY_BLOCKED
}

internal enum class AlwaysOnWaitingReason {
    NETWORK,
    USER_FOREGROUND,
    SHELL,
    RUNTIME,
    GATEWAY,
    CHANNELS
}

internal enum class AlwaysOnTrigger {
    INITIALIZE,
    USER_ENABLED,
    USER_DISABLED,
    APP_FOREGROUND,
    SERVICE_STATE_CHANGED,
    GATEWAY_STATE_CHANGED,
    NETWORK_CHANGED,
    BOOT_COMPLETED,
    PACKAGE_REPLACED,
    WATCHDOG,
    RECOVERY_DUE,
    SERVICE_TIMEOUT
}

internal enum class AlwaysOnStartConstraint {
    ALLOWED,
    SYSTEM_RESTRICTED
}

internal data class AlwaysOnPlatformSnapshot(
    val shell: AlwaysOnShellState,
    val notificationVisible: Boolean = false,
    val network: AlwaysOnNetworkState,
    val startConstraint: AlwaysOnStartConstraint = AlwaysOnStartConstraint.ALLOWED,
    val transientRecoveryScheduled: Boolean = false,
    val watchdogScheduled: Boolean = false
)

internal data class GatewayAvailabilitySnapshot(
    val runtime: AlwaysOnRuntimeState,
    val gateway: AlwaysOnGatewayState = AlwaysOnGatewayState.STOPPED,
    val channels: AlwaysOnChannelCounts
)

internal sealed interface ShellStartResult {
    data object Started : ShellStartResult

    data object AlreadyRunning : ShellStartResult

    data class Rejected(
        val reason: AlwaysOnActionRequiredReason,
        val message: String? = null
    ) : ShellStartResult

    data class Failed(val message: String) : ShellStartResult
}

internal sealed interface GatewayEnsureResult {
    data object Available : GatewayEnsureResult

    data object Starting : GatewayEnsureResult

    data class Blocked(val message: String? = null) : GatewayEnsureResult

    data class Failed(val message: String) : GatewayEnsureResult
}

internal sealed interface GatewayReleaseResult {
    data object Released : GatewayReleaseResult

    data class Failed(
        val message: String
    ) : GatewayReleaseResult
}

/** Android operations are hidden behind this seam. */
internal interface AlwaysOnPlatform {
    suspend fun snapshot(): AlwaysOnPlatformSnapshot

    suspend fun startShell(): ShellStartResult

    suspend fun stopShell()

    suspend fun scheduleRecovery(delayMillis: Long)

    suspend fun cancelRecovery()

    suspend fun ensureWatchdog()

    suspend fun cancelWatchdog()

    suspend fun updateActionRequired(action: AlwaysOnActionRequired?) = Unit
}

/** Runtime and channel availability are hidden behind this seam. */
internal interface GatewayAvailability {
    suspend fun snapshot(): GatewayAvailabilitySnapshot

    suspend fun ensureAvailable(): GatewayEnsureResult

    suspend fun releaseOwnership(): GatewayReleaseResult
}

internal interface AlwaysOnConfigStore {
    suspend fun isEnabled(): Boolean

    suspend fun setEnabled(enabled: Boolean)
}

internal fun interface AlwaysOnClock {
    fun nowEpochMillis(): Long
}

internal object SystemAlwaysOnClock : AlwaysOnClock {
    override fun nowEpochMillis(): Long = System.currentTimeMillis()
}
