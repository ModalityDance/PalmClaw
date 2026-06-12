package com.palmclaw.ui

import com.palmclaw.config.AppLimits

internal class RuntimeCoordinator(
    private val stateStore: ChatStateStore,
    private val actions: Actions
) {
    data class Actions(
        val loadSettingsIntoState: () -> Unit,
        val observeRuntimeStatus: () -> Unit,
        val observeAlwaysOnStatus: () -> Unit,
        val startGatewayIfEnabled: () -> Unit,
        val refreshAlwaysOnDiagnostics: () -> Unit,
        val refreshCronJobs: () -> Unit,
        val setCronJobEnabled: (String, Boolean) -> Unit,
        val runCronJobNow: (String) -> Unit,
        val removeCronJob: (String) -> Unit,
        val triggerHeartbeatNow: () -> Unit,
        val loadHeartbeatDocument: () -> Unit,
        val saveHeartbeatDocument: (Boolean, Boolean) -> Unit,
        val refreshCronLogs: () -> Unit,
        val clearCronLogs: () -> Unit,
        val refreshAgentLogs: () -> Unit,
        val clearAgentLogs: () -> Unit,
        val saveCronSettings: (Boolean, Boolean) -> Unit,
        val saveHeartbeatSettings: (Boolean, Boolean) -> Unit,
        val saveAlwaysOnSettings: (Boolean, Boolean) -> Unit,
        val saveChannelsSettings: (Boolean, Boolean) -> Unit,
        val saveMcpSettings: (Boolean, Boolean) -> Unit
    )

    fun loadSettingsIntoState() = actions.loadSettingsIntoState()

    fun observeRuntimeStatus() = actions.observeRuntimeStatus()

    fun observeAlwaysOnStatus() = actions.observeAlwaysOnStatus()

    fun startGatewayIfEnabled() = actions.startGatewayIfEnabled()

    fun refreshAlwaysOnDiagnostics() = actions.refreshAlwaysOnDiagnostics()

    fun onSettingsCronEnabledChanged(value: Boolean) {
        stateStore.updateAutomationState { it.copy(cronEnabled = value) }
    }

    fun onSettingsCronMinEveryMsChanged(value: String) {
        stateStore.updateAutomationState { it.copy(cronMinEveryMs = value) }
    }

    fun onSettingsCronMaxJobsChanged(value: String) {
        stateStore.updateAutomationState { it.copy(cronMaxJobs = value) }
    }

    fun onSettingsHeartbeatEnabledChanged(value: Boolean) {
        stateStore.updateAutomationState { it.copy(heartbeatEnabled = value) }
    }

    fun onSettingsHeartbeatIntervalSecondsChanged(value: String) {
        stateStore.updateAutomationState { it.copy(heartbeatIntervalSeconds = value) }
    }

    fun onSettingsGatewayEnabledChanged(value: Boolean) {
        stateStore.updateChannelsSettingsState { it.copy(gatewayEnabled = value) }
    }

    fun onSettingsTelegramBotTokenChanged(value: String) {
        stateStore.updateChannelsSettingsState { it.copy(telegramBotToken = value) }
    }

    fun onSettingsTelegramAllowedChatIdChanged(value: String) {
        stateStore.updateChannelsSettingsState { it.copy(telegramAllowedChatId = value) }
    }

    fun onSettingsDiscordWebhookUrlChanged(value: String) {
        stateStore.updateChannelsSettingsState { it.copy(discordWebhookUrl = value) }
    }

    fun onSettingsMcpEnabledChanged(value: Boolean) {
        stateStore.updateMcpSettingsState { it.copy(enabled = value) }
    }

    fun onSettingsMcpServerNameChanged(value: String) {
        stateStore.updateMcpSettingsState {
            it.copy(
                serverName = value,
                servers = it.servers.updateServerField(
                    index = 0,
                    update = { server -> server.copy(serverName = value) }
                )
            )
        }
    }

    fun onSettingsMcpServerUrlChanged(value: String) {
        stateStore.updateMcpSettingsState {
            it.copy(
                serverUrl = value,
                servers = it.servers.updateServerField(
                    index = 0,
                    update = { server -> server.copy(serverUrl = value) }
                )
            )
        }
    }

    fun onSettingsMcpAuthTokenChanged(value: String) {
        stateStore.updateMcpSettingsState {
            it.copy(
                authToken = value,
                servers = it.servers.updateServerField(
                    index = 0,
                    update = { server -> server.copy(authToken = value) }
                )
            )
        }
    }

    fun onSettingsMcpToolTimeoutSecondsChanged(value: String) {
        stateStore.updateMcpSettingsState {
            it.copy(
                toolTimeoutSeconds = value,
                servers = it.servers.updateServerField(
                    index = 0,
                    update = { server -> server.copy(toolTimeoutSeconds = value) }
                )
            )
        }
    }

    fun addSettingsMcpServer() {
        stateStore.updateMcpSettingsState {
            it.copy(
                servers = it.servers + UiMcpServerConfig(
                    id = "mcp_${System.currentTimeMillis()}_${it.servers.size + 1}"
                )
            )
        }
    }

    fun removeSettingsMcpServer(serverId: String) {
        stateStore.updateMcpSettingsState { state ->
            val next = state.servers.filterNot { it.id == serverId }
            val first = next.firstOrNull()
            state.copy(
                servers = next,
                serverName = first?.serverName ?: AppLimits.DEFAULT_MCP_HTTP_SERVER_NAME,
                serverUrl = first?.serverUrl.orEmpty(),
                authToken = first?.authToken.orEmpty(),
                toolTimeoutSeconds = first?.toolTimeoutSeconds
                    ?: AppLimits.DEFAULT_MCP_HTTP_TOOL_TIMEOUT_SECONDS.toString()
            )
        }
    }

    fun updateSettingsMcpServerName(serverId: String, value: String) {
        updateSettingsMcpServer(serverId) { it.copy(serverName = value) }
    }

    fun updateSettingsMcpServerUrl(serverId: String, value: String) {
        updateSettingsMcpServer(serverId) { it.copy(serverUrl = value) }
    }

    fun updateSettingsMcpServerAuthToken(serverId: String, value: String) {
        updateSettingsMcpServer(serverId) { it.copy(authToken = value) }
    }

    fun updateSettingsMcpServerTimeout(serverId: String, value: String) {
        updateSettingsMcpServer(serverId) { it.copy(toolTimeoutSeconds = value) }
    }

    fun refreshCronJobs() = actions.refreshCronJobs()

    fun setCronJobEnabled(jobId: String, enabled: Boolean) =
        actions.setCronJobEnabled(jobId, enabled)

    fun runCronJobNow(jobId: String) = actions.runCronJobNow(jobId)

    fun removeCronJob(jobId: String) = actions.removeCronJob(jobId)

    fun triggerHeartbeatNow() = actions.triggerHeartbeatNow()

    fun loadHeartbeatDocument() = actions.loadHeartbeatDocument()

    fun onSettingsHeartbeatDocChanged(value: String) {
        stateStore.updateAutomationState { it.copy(heartbeatDoc = value) }
    }

    fun saveHeartbeatDocument(showSuccessMessage: Boolean, showErrorMessage: Boolean) =
        actions.saveHeartbeatDocument(showSuccessMessage, showErrorMessage)

    fun refreshCronLogs() = actions.refreshCronLogs()

    fun clearCronLogs() = actions.clearCronLogs()

    fun refreshAgentLogs() = actions.refreshAgentLogs()

    fun clearAgentLogs() = actions.clearAgentLogs()

    fun saveCronSettings(showSuccessMessage: Boolean, showErrorMessage: Boolean) =
        actions.saveCronSettings(showSuccessMessage, showErrorMessage)

    fun saveHeartbeatSettings(showSuccessMessage: Boolean, showErrorMessage: Boolean) =
        actions.saveHeartbeatSettings(showSuccessMessage, showErrorMessage)

    fun onAlwaysOnEnabledChanged(value: Boolean) {
        stateStore.updateAlwaysOnState { it.copy(enabled = value) }
    }

    fun onAlwaysOnKeepScreenAwakeChanged(value: Boolean) {
        stateStore.updateAlwaysOnState { it.copy(keepScreenAwake = value) }
    }

    fun saveAlwaysOnSettings(showSuccessMessage: Boolean, showErrorMessage: Boolean) =
        actions.saveAlwaysOnSettings(showSuccessMessage, showErrorMessage)

    fun saveChannelsSettings(showSuccessMessage: Boolean, showErrorMessage: Boolean) =
        actions.saveChannelsSettings(showSuccessMessage, showErrorMessage)

    fun saveMcpSettings(showSuccessMessage: Boolean, showErrorMessage: Boolean) =
        actions.saveMcpSettings(showSuccessMessage, showErrorMessage)

    private fun updateSettingsMcpServer(
        serverId: String,
        update: (UiMcpServerConfig) -> UiMcpServerConfig
    ) {
        stateStore.updateMcpSettingsState { state ->
            val updatedServers = state.servers.map { current ->
                if (current.id == serverId) {
                    update(current).copy(
                        status = "Unsaved changes",
                        detail = "",
                        toolCount = 0
                    )
                } else {
                    current
                }
            }
            val first = updatedServers.firstOrNull()
            state.copy(
                servers = updatedServers,
                serverName = first?.serverName ?: state.serverName,
                serverUrl = first?.serverUrl ?: state.serverUrl,
                authToken = first?.authToken ?: state.authToken,
                toolTimeoutSeconds = first?.toolTimeoutSeconds
                    ?: state.toolTimeoutSeconds
            )
        }
    }
}

private fun List<UiMcpServerConfig>.updateServerField(
    index: Int,
    update: (UiMcpServerConfig) -> UiMcpServerConfig
): List<UiMcpServerConfig> {
    if (isEmpty()) return emptyList()
    return mapIndexed { currentIndex, value ->
        if (currentIndex == index) update(value) else value
    }
}
