package com.palmclaw.ui

import com.palmclaw.ui.domain.RuntimeRefreshGateway
import com.palmclaw.ui.domain.RuntimeStatusSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

internal class RuntimeStatusCoordinator(
    private val scope: CoroutineScope,
    private val stateStore: ChatStateStore,
    private val statusSource: RuntimeStatusSource,
    private val gatewayProcessingCoordinator: GatewayProcessingCoordinator,
    private val refreshGateway: RuntimeRefreshGateway,
    private val onProcessingChanged: () -> Unit
) {
    private var started = false

    fun start() {
        if (started) return
        started = true
        scope.launch {
            statusSource.runtimeStatus.collectLatest { status ->
                handleProcessingUpdate(
                    gatewayProcessingCoordinator.updateRuntimeProcessingSessions(
                        status.processingSessionIds
                    )
                )
            }
        }
        scope.launch {
            statusSource.alwaysOnStatus.collectLatest { status ->
                stateStore.updateAlwaysOnState { it.withRuntimeStatus(status) }
                handleProcessingUpdate(
                    gatewayProcessingCoordinator.updateAlwaysOnProcessingSessions(
                        status.processingSessionIds
                    )
                )
            }
        }
    }

    private suspend fun handleProcessingUpdate(result: GatewayProcessingCoordinator.UpdateResult) {
        if (result.shouldRefreshGateway) {
            refreshGateway.refreshGatewayRuntimeConfig()
        }
        onProcessingChanged()
    }
}
