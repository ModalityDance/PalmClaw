package com.palmclaw.ui

import com.palmclaw.AppContainer

/**
 * Thin compatibility layer for [ChatViewModel] while dependency construction moves
 * to the process-level [AppContainer].
 */
internal class ChatViewModelEnvironment(
    private val container: AppContainer
) {
    private val dependencies = container.chatViewModelDependencies

    val storageMigration = container.storageMigration
    val chatRepository = dependencies.chatRepository
    val cronService = container.cronService
    val cronLogStore = container.cronLogStore
    val agentLogStore = container.agentLogStore
    val configStore = container.configStore
    val providerResolutionStore = container.providerResolutionStore
    val memoryStore = container.memoryStore
    val templateStore = container.templateStore
    val runtimeStatusSource = dependencies.runtimeStatusSource
    val runtimeExecutionGateway = dependencies.runtimeExecutionGateway
    val runtimeRefreshGateway = dependencies.runtimeRefreshGateway
    val runtimeControlService = container.runtimeControlService
    val channelBindingRuntimeProjector = container.channelBindingRuntimeProjector
    val channelRuntimeSnapshotSource = container.channelRuntimeSnapshotSource
    val gatewayStatusOverviewAssembler = container.gatewayStatusOverviewAssembler
    val channelDiscoveryService = container.channelDiscoveryService
    val emailAddressValidator = container.emailAddressValidator
    val heartbeatRuntimePort = container.uiHeartbeatRuntimePort
    val channelBindingService = dependencies.channelBindingService
    val attachmentTransferService = container.attachmentTransferService
    val skillRepository = dependencies.skillRepository
    val heartbeatDocFile = container.heartbeatDocFile
    val uiJson = container.uiJson
    val updateCheckClient = container.updateCheckClient
}
