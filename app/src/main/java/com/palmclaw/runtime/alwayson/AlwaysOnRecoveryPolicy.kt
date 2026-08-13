package com.palmclaw.runtime.alwayson

internal data class AlwaysOnObservation(
    val platform: AlwaysOnPlatformSnapshot,
    val gateway: GatewayAvailabilitySnapshot
)

internal data class RecoveryPlan(
    val phase: AlwaysOnPhase,
    val actions: List<RecoveryAction> = emptyList(),
    val waitingFor: AlwaysOnWaitingReason? = null,
    val actionRequired: AlwaysOnActionRequired? = null,
    val lastError: String? = null
)

internal sealed interface RecoveryAction {
    data object CancelRecovery : RecoveryAction

    data object CancelWatchdog : RecoveryAction

    data object EnsureWatchdog : RecoveryAction

    data object ReleaseGatewayOwnership : RecoveryAction

    data object StopShell : RecoveryAction

    data object StartShell : RecoveryAction

    data object EnsureGateway : RecoveryAction

    data class MaintainRecovery(val delayMillis: Long) : RecoveryAction
}

/**
 * Produces one declarative plan for each observed state. The coordinator is the
 * only place that executes these actions.
 */
internal class AlwaysOnRecoveryPolicy {
    fun plan(desired: Boolean, observation: AlwaysOnObservation): RecoveryPlan {
        if (!desired) {
            return RecoveryPlan(
                phase = AlwaysOnPhase.DISABLED,
                actions = stopAlwaysOnResources(observation, cancelWatchdog = true)
            )
        }

        if (observation.gateway.channels.configured == 0) {
            return RecoveryPlan(
                phase = AlwaysOnPhase.ACTION_REQUIRED,
                actions = stopAlwaysOnResources(observation, cancelWatchdog = true),
                actionRequired = AlwaysOnActionRequired(
                    reason = AlwaysOnActionRequiredReason.NO_CHANNEL_CONFIGURED
                )
            )
        }

        val constraintReason = when (observation.platform.startConstraint) {
            AlwaysOnStartConstraint.ALLOWED -> null
            AlwaysOnStartConstraint.SYSTEM_RESTRICTED ->
                AlwaysOnActionRequiredReason.SYSTEM_RESTRICTED
        }
        if (constraintReason != null) {
            return RecoveryPlan(
                phase = AlwaysOnPhase.ACTION_REQUIRED,
                actions = stopAlwaysOnResources(observation, cancelWatchdog = true),
                actionRequired = AlwaysOnActionRequired(
                    reason = constraintReason
                )
            )
        }

        val startupActions = buildList {
            if (!observation.platform.watchdogScheduled) {
                add(RecoveryAction.EnsureWatchdog)
            }
            if (observation.platform.transientRecoveryScheduled &&
                observation.platform.network == AlwaysOnNetworkState.OFFLINE
            ) {
                add(RecoveryAction.CancelRecovery)
            }
            if (observation.platform.shell == AlwaysOnShellState.STOPPED) {
                add(RecoveryAction.StartShell)
            }
            if (observation.gateway.runtime == AlwaysOnRuntimeState.STOPPED ||
                observation.gateway.gateway == AlwaysOnGatewayState.STOPPED
            ) {
                add(RecoveryAction.EnsureGateway)
            }
        }

        if (observation.platform.network == AlwaysOnNetworkState.OFFLINE) {
            return RecoveryPlan(
                phase = AlwaysOnPhase.RECOVERING,
                actions = startupActions,
                waitingFor = AlwaysOnWaitingReason.NETWORK
            )
        }

        if (startupActions.isNotEmpty() ||
            observation.platform.shell == AlwaysOnShellState.STARTING ||
            observation.gateway.runtime == AlwaysOnRuntimeState.STARTING ||
            observation.gateway.gateway == AlwaysOnGatewayState.STARTING
        ) {
            val actions = buildList {
                addAll(startupActions)
                if (!observation.platform.transientRecoveryScheduled) {
                    add(RecoveryAction.MaintainRecovery(RETRY_DELAY_MILLIS))
                }
            }
            return RecoveryPlan(
                phase = AlwaysOnPhase.STARTING,
                actions = actions,
                waitingFor = when {
                    observation.platform.shell != AlwaysOnShellState.RUNNING ->
                        AlwaysOnWaitingReason.SHELL
                    observation.gateway.runtime != AlwaysOnRuntimeState.RUNNING ->
                        AlwaysOnWaitingReason.RUNTIME
                    else -> AlwaysOnWaitingReason.GATEWAY
                }
            )
        }

        val channels = observation.gateway.channels
        return when {
            channels.ready == channels.configured ->
                RecoveryPlan(
                    phase = AlwaysOnPhase.ONLINE,
                    actions = cancelStaleRecovery(observation)
                )
            channels.ready > 0 ->
                RecoveryPlan(
                    phase = AlwaysOnPhase.DEGRADED,
                    actions = cancelStaleRecovery(observation)
                )
            channels.blocked == channels.configured ->
                RecoveryPlan(
                    phase = AlwaysOnPhase.ACTION_REQUIRED,
                    actions = stopAlwaysOnResources(observation, cancelWatchdog = true),
                    actionRequired = AlwaysOnActionRequired(
                        reason = AlwaysOnActionRequiredReason.ALL_CHANNELS_BLOCKED
                    )
                )
            else -> RecoveryPlan(
                phase = AlwaysOnPhase.RECOVERING,
                actions = if (observation.platform.transientRecoveryScheduled) {
                    emptyList()
                } else {
                    listOf(RecoveryAction.MaintainRecovery(RETRY_DELAY_MILLIS))
                },
                waitingFor = AlwaysOnWaitingReason.CHANNELS
            )
        }
    }

    private fun cancelStaleRecovery(observation: AlwaysOnObservation): List<RecoveryAction> {
        return if (observation.platform.transientRecoveryScheduled) {
            listOf(RecoveryAction.CancelRecovery)
        } else {
            emptyList()
        }
    }

    private fun stopAlwaysOnResources(
        observation: AlwaysOnObservation,
        cancelWatchdog: Boolean
    ): List<RecoveryAction> = buildList {
        if (observation.platform.transientRecoveryScheduled) {
            add(RecoveryAction.CancelRecovery)
        }
        if (cancelWatchdog && observation.platform.watchdogScheduled) {
            add(RecoveryAction.CancelWatchdog)
        }
        add(RecoveryAction.ReleaseGatewayOwnership)
        if (observation.platform.shell != AlwaysOnShellState.STOPPED) {
            add(RecoveryAction.StopShell)
        }
    }

    private companion object {
        const val RETRY_DELAY_MILLIS = 15_000L
    }
}
