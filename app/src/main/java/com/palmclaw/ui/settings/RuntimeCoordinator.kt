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
        stateStore.updateRuntime { it.copy(settingsCronEnabled = value) }
    }

    fun onSettingsCronMinEveryMsChanged(value: String) {
        stateStore.updateRuntime { it.copy(settingsCronMinEveryMs = value) }
    }

    fun onSettingsCronMaxJobsChanged(value: String) {
        stateStore.updateRuntime { it.copy(settingsCronMaxJobs = value) }
    }

    fun onSettingsHeartbeatEnabledChanged(value: Boolean) {
        stateStore.updateRuntime { it.copy(settingsHeartbeatEnabled = value) }
    }

    fun onSettingsHeartbeatIntervalSecondsChanged(value: String) {
        stateStore.updateRuntime { it.copy(settingsHeartbeatIntervalSeconds = value) }
    }

    fun onSettingsGatewayEnabledChanged(value: Boolean) {
        stateStore.updateRuntime { it.copy(settingsGatewayEnabled = value) }
    }

    fun onSettingsTelegramBotTokenChanged(value: String) {
        stateStore.updateRuntime { it.copy(settingsTelegramBotToken = value) }
    }

    fun onSettingsTelegramAllowedChatIdChanged(value: String) {
        stateStore.updateRuntime { it.copy(settingsTelegramAllowedChatId = value) }
    }

    fun onSettingsDiscordWebhookUrlChanged(value: String) {
        stateStore.updateRuntime { it.copy(settingsDiscordWebhookUrl = value) }
    }

    fun onSettingsMcpEnabledChanged(value: Boolean) {
        stateStore.updateRuntime { it.copy(settingsMcpEnabled = value) }
    }

    fun onSettingsMcpServerNameChanged(value: String) {
        stateStore.updateRuntime {
            it.copy(
                settingsMcpServerName = value,
                settingsMcpServers = it.settingsMcpServers.updateServerField(
                    index = 0,
                    update = { server -> server.copy(serverName = value) }
                )
            )
        }
    }

    fun onSettingsMcpServerUrlChanged(value: String) {
        stateStore.updateRuntime {
            it.copy(
                settingsMcpServerUrl = value,
                settingsMcpServers = it.settingsMcpServers.updateServerField(
                    index = 0,
                    update = { server -> server.copy(serverUrl = value) }
                )
            )
        }
    }

    fun onSettingsMcpAuthTokenChanged(value: String) {
        stateStore.updateRuntime {
            it.copy(
                settingsMcpAuthToken = value,
                settingsMcpServers = it.settingsMcpServers.updateServerField(
                    index = 0,
                    update = { server -> server.copy(authToken = value) }
                )
            )
        }
    }

    fun onSettingsMcpToolTimeoutSecondsChanged(value: String) {
        stateStore.updateRuntime {
            it.copy(
                settingsMcpToolTimeoutSeconds = value,
                settingsMcpServers = it.settingsMcpServers.updateServerField(
                    index = 0,
                    update = { server -> server.copy(toolTimeoutSeconds = value) }
                )
            )
        }
    }

    fun addSettingsMcpServer() {
        stateStore.updateRuntime {
            it.copy(
                settingsMcpServers = it.settingsMcpServers + UiMcpServerConfig(
                    id = "mcp_${System.currentTimeMillis()}_${it.settingsMcpServers.size + 1}"
                )
            )
        }
    }

    fun removeSettingsMcpServer(serverId: String) {
        stateStore.updateRuntime { state ->
            val next = state.settingsMcpServers.filterNot { it.id == serverId }
            val first = next.firstOrNull()
            state.copy(
                settingsMcpServers = next,
                settingsMcpServerName = first?.serverName ?: AppLimits.DEFAULT_MCP_HTTP_SERVER_NAME,
                settingsMcpServerUrl = first?.serverUrl.orEmpty(),
                settingsMcpAuthToken = first?.authToken.orEmpty(),
                settingsMcpToolTimeoutSeconds = first?.toolTimeoutSeconds
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
        stateStore.updateRuntime { it.copy(settingsHeartbeatDoc = value) }
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
        stateStore.updateRuntime { it.copy(alwaysOnEnabled = value) }
    }

    fun onAlwaysOnKeepScreenAwakeChanged(value: Boolean) {
        stateStore.updateRuntime { it.copy(alwaysOnKeepScreenAwake = value) }
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
        stateStore.updateRuntime { state ->
            val updatedServers = state.settingsMcpServers.map { current ->
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
                settingsMcpServers = updatedServers,
                settingsMcpServerName = first?.serverName ?: state.settingsMcpServerName,
                settingsMcpServerUrl = first?.serverUrl ?: state.settingsMcpServerUrl,
                settingsMcpAuthToken = first?.authToken ?: state.settingsMcpAuthToken,
                settingsMcpToolTimeoutSeconds = first?.toolTimeoutSeconds
                    ?: state.settingsMcpToolTimeoutSeconds
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
