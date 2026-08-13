package com.palmclaw.runtime.alwayson

import com.palmclaw.config.ConfigStore

internal class ConfigStoreAlwaysOnConfigStore(
    private val configStore: ConfigStore
) : AlwaysOnConfigStore {
    override suspend fun isEnabled(): Boolean {
        return configStore.getAlwaysOnConfig().enabled
    }

    override suspend fun setEnabled(enabled: Boolean) {
        val current = configStore.getAlwaysOnConfig()
        if (current.enabled == enabled) {
            return
        }
        configStore.saveAlwaysOnConfig(
            current.copy(enabled = enabled)
        )
    }
}
