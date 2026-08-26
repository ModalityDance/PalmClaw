package com.palmclaw.runtime.alwayson

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Owns the desired Always-on state and serializes every recovery decision.
 * Android and gateway details stay behind the two injected seams.
 */
internal class AlwaysOnCoordinator(
    private val platform: AlwaysOnPlatform,
    private val gateway: GatewayAvailability,
    private val configStore: AlwaysOnConfigStore,
    private val clock: AlwaysOnClock = SystemAlwaysOnClock
) : AlwaysOnControl {
    private val reconciliationMutex = Mutex()
    private val recoveryPolicy = AlwaysOnRecoveryPolicy()
    private val mutableStatus = MutableStateFlow(AlwaysOnStatus())

    override val status: StateFlow<AlwaysOnStatus> = mutableStatus.asStateFlow()

    override suspend fun setEnabled(enabled: Boolean) {
        reconciliationMutex.withLock {
            // Persist the user's stop intent before any cancellable side effect.
            configStore.setEnabled(enabled)
            reconcileLocked(
                desired = enabled,
                trigger = if (enabled) {
                    AlwaysOnTrigger.USER_ENABLED
                } else {
                    AlwaysOnTrigger.USER_DISABLED
                }
            )
        }
    }

    override suspend fun reconcile(trigger: AlwaysOnTrigger) {
        reconciliationMutex.withLock {
            reconcileLocked(
                desired = configStore.isEnabled(),
                trigger = trigger
            )
        }
    }

    private suspend fun reconcileLocked(desired: Boolean, trigger: AlwaysOnTrigger) {
        val initialObservation = observe()
        val plan = recoveryPolicy.plan(desired, initialObservation)
        publishStatus(desired, trigger, initialObservation, plan)

        val executionResult = execute(desired, plan)

        val finalObservation = observe()
        val finalPlan = executionResult ?: recoveryPolicy.plan(desired, finalObservation)
        updateActionRequiredSafely(finalPlan.actionRequired)
        publishStatus(desired, trigger, finalObservation, finalPlan)
    }

    private suspend fun observe(): AlwaysOnObservation {
        return AlwaysOnObservation(
            platform = platform.snapshot(),
            gateway = gateway.snapshot()
        )
    }

    private suspend fun execute(desired: Boolean, plan: RecoveryPlan): RecoveryPlan? {
        var terminalPlan: RecoveryPlan? = null
        var releaseFailure: String? = null
        for (action in plan.actions) {
            if (terminalPlan != null && action !is RecoveryAction.MaintainRecovery) {
                continue
            }
            when (action) {
                RecoveryAction.CancelRecovery -> platform.cancelRecovery()
                RecoveryAction.CancelWatchdog -> platform.cancelWatchdog()
                RecoveryAction.EnsureWatchdog -> platform.ensureWatchdog()
                RecoveryAction.ReleaseGatewayOwnership -> {
                    val result = gateway.releaseOwnership()
                    if (result is GatewayReleaseResult.Failed) {
                        releaseFailure = result.message
                    }
                }
                RecoveryAction.StopShell -> platform.stopShell()
                RecoveryAction.StartShell -> {
                    when (val result = platform.startShell()) {
                        ShellStartResult.Started,
                        ShellStartResult.AlreadyRunning -> Unit
                        is ShellStartResult.Rejected -> {
                            releaseFailure = stopForUserAction() ?: releaseFailure
                            terminalPlan = RecoveryPlan(
                                phase = AlwaysOnPhase.ACTION_REQUIRED,
                                waitingFor = AlwaysOnWaitingReason.USER_FOREGROUND,
                                actionRequired = AlwaysOnActionRequired(
                                    reason = result.reason,
                                    message = result.message
                                )
                            )
                        }
                        is ShellStartResult.Failed -> {
                            terminalPlan = RecoveryPlan(
                                phase = AlwaysOnPhase.RECOVERING,
                                waitingFor = AlwaysOnWaitingReason.SHELL,
                                lastError = result.message
                            )
                        }
                    }
                }
                RecoveryAction.EnsureGateway -> {
                    when (val result = gateway.ensureAvailable()) {
                        GatewayEnsureResult.Available -> Unit
                        GatewayEnsureResult.Starting -> {
                            terminalPlan = RecoveryPlan(
                                phase = AlwaysOnPhase.STARTING,
                                waitingFor = AlwaysOnWaitingReason.RUNTIME
                            )
                        }
                        is GatewayEnsureResult.Blocked -> {
                            releaseFailure = stopForUserAction() ?: releaseFailure
                            terminalPlan = RecoveryPlan(
                                phase = AlwaysOnPhase.ACTION_REQUIRED,
                                actionRequired = AlwaysOnActionRequired(
                                    reason = AlwaysOnActionRequiredReason.GATEWAY_BLOCKED,
                                    message = result.message
                                )
                            )
                        }
                        is GatewayEnsureResult.Failed -> {
                            terminalPlan = RecoveryPlan(
                                phase = AlwaysOnPhase.RECOVERING,
                                waitingFor = AlwaysOnWaitingReason.RUNTIME,
                                lastError = result.message
                            )
                        }
                    }
                }
                is RecoveryAction.MaintainRecovery -> {
                    val effectivePlan = terminalPlan ?: recoveryPolicy.plan(desired, observe())
                    if (effectivePlan.phase == AlwaysOnPhase.STARTING ||
                        effectivePlan.phase == AlwaysOnPhase.RECOVERING
                    ) {
                        platform.scheduleRecovery(action.delayMillis)
                    }
                }
            }
        }
        if (releaseFailure != null) {
            if (!platform.snapshot().transientRecoveryScheduled) {
                platform.scheduleRecovery(CLEANUP_RETRY_DELAY_MILLIS)
            }
            return (terminalPlan ?: plan).copy(lastError = releaseFailure)
        }
        return terminalPlan
    }

    private suspend fun stopForUserAction(): String? {
        val observation = observe()
        if (observation.platform.transientRecoveryScheduled) {
            platform.cancelRecovery()
        }
        if (observation.platform.watchdogScheduled) {
            platform.cancelWatchdog()
        }
        val releaseFailure = when (val result = gateway.releaseOwnership()) {
            GatewayReleaseResult.Released -> null
            is GatewayReleaseResult.Failed -> result.message
        }
        if (observation.platform.shell != AlwaysOnShellState.STOPPED) {
            platform.stopShell()
        }
        return releaseFailure
    }

    private suspend fun updateActionRequiredSafely(action: AlwaysOnActionRequired?) {
        try {
            platform.updateActionRequired(action)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Notification projection is best effort and must not stop reconciliation.
        }
    }

    private fun publishStatus(
        desired: Boolean,
        trigger: AlwaysOnTrigger,
        observation: AlwaysOnObservation,
        plan: RecoveryPlan
    ) {
        mutableStatus.value = AlwaysOnStatus(
            desired = desired,
            phase = plan.phase,
            shell = observation.platform.shell,
            notificationVisible = observation.platform.notificationVisible,
            runtime = observation.gateway.runtime,
            gateway = observation.gateway.gateway,
            network = observation.platform.network,
            channels = observation.gateway.channels,
            waitingFor = plan.waitingFor,
            actionRequired = plan.actionRequired,
            lastError = plan.lastError,
            lastTrigger = trigger,
            updatedAtEpochMillis = clock.nowEpochMillis()
        )
    }

    private companion object {
        const val CLEANUP_RETRY_DELAY_MILLIS = 15_000L
    }
}
