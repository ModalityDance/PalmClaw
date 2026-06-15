package com.palmclaw.ui

import com.palmclaw.config.AppConfig
import com.palmclaw.config.ProviderConnectionConfig
import com.palmclaw.providers.ProviderCatalog
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.util.Locale

internal object ProviderSettingsMapper {
    fun buildProviderTestConfig(
        current: AppConfig,
        state: ProviderSettingsState
    ): AppConfig {
        val provider = ProviderCatalog.resolve(state.provider).id
        val protocol = ProviderCatalog.resolveProtocol(provider, state.providerProtocol, state.baseUrl)
        val model = state.model.trim().ifBlank { ProviderCatalog.defaultModel(provider, protocol) }
        val apiKey = state.apiKeyDraft.trim()
        val baseUrl = validateEndpointUrl(state.baseUrl)
        return current.copy(
            providerName = provider,
            providerProtocol = protocol,
            apiKey = apiKey,
            model = model,
            baseUrl = baseUrl,
            activeProviderConfigId = state.editingProviderConfigId.trim()
        )
    }

    fun buildStateWithSavedDraft(state: ChatUiState): ChatUiState {
        return state.withProviderSettingsState(
            buildStateWithSavedDraft(state.toProviderSettingsState())
        )
    }

    fun buildStateWithSavedDraft(state: ProviderSettingsState): ProviderSettingsState {
        val savedConfig = buildValidatedProviderDraft(state)
        val currentConfigs = state.providerConfigs
        val existing = currentConfigs.firstOrNull { it.id == savedConfig.id }
        val shouldEnable = existing?.enabled ?: true
        val updatedConfigs = normalizeActiveProviderConfigs(
            currentConfigs.filterNot { it.id == savedConfig.id } + savedConfig.copy(enabled = shouldEnable)
        )
        val selected = updatedConfigs.firstOrNull { it.id == savedConfig.id } ?: updatedConfigs.firstOrNull()
        return state.copy(
            providerConfigs = updatedConfigs,
            editingProviderConfigId = selected?.id.orEmpty(),
            provider = selected?.providerName ?: state.provider,
            providerCustomName = selected?.customName ?: state.providerCustomName,
            providerProtocol = selected?.providerProtocol ?: state.providerProtocol,
            baseUrl = selected?.let { config ->
                config.baseUrl.ifBlank {
                    ProviderCatalog.defaultBaseUrl(config.providerName, config.providerProtocol)
                }
            } ?: state.baseUrl,
            model = selected?.model ?: state.model,
            apiKeyDraft = selected?.apiKey ?: state.apiKeyDraft
        )
    }

    fun buildSettingsConfig(current: AppConfig, state: ChatUiState): AppConfig {
        return buildSettingsConfig(current, state.toProviderSettingsState())
    }

    fun buildSettingsConfig(current: AppConfig, state: ProviderSettingsState): AppConfig {
        val normalizedConfigs = normalizeActiveProviderConfigs(state.providerConfigs)
        val activeConfig = normalizedConfigs.firstOrNull { it.enabled } ?: normalizedConfigs.firstOrNull()
        return current.copy(
            providerName = activeConfig?.providerName ?: ProviderCatalog.resolve(state.provider).id,
            providerProtocol = activeConfig?.providerProtocol ?: state.providerProtocol,
            apiKey = activeConfig?.apiKey ?: state.apiKeyDraft.trim(),
            model = activeConfig?.model ?: state.model.trim().ifBlank {
                ProviderCatalog.defaultModel(state.provider, state.providerProtocol)
            },
            baseUrl = activeConfig?.baseUrl ?: state.baseUrl.trim(),
            providerConfigs = normalizedConfigs.map { config ->
                ProviderConnectionConfig(
                    id = config.id,
                    providerName = config.providerName,
                    customName = config.customName,
                    providerProtocol = config.providerProtocol,
                    apiKey = config.apiKey,
                    model = config.model,
                    baseUrl = config.baseUrl
                )
            },
            activeProviderConfigId = activeConfig?.id.orEmpty()
        )
    }

    fun buildUiProviderConfigs(config: AppConfig): List<UiProviderConfig> {
        val activeId = config.activeProviderConfigId.trim()
        val mapped = config.providerConfigs.map { item ->
            val resolvedProvider = ProviderCatalog.resolve(item.providerName)
            val resolvedProtocol = ProviderCatalog.resolveProtocol(
                rawProvider = resolvedProvider.id,
                requested = item.providerProtocol,
                baseUrl = item.baseUrl
            )
            UiProviderConfig(
                id = item.id.trim().ifBlank {
                    "provider_${resolvedProvider.id}_${item.model.hashCode()}"
                },
                providerName = resolvedProvider.id,
                customName = item.customName,
                providerProtocol = resolvedProtocol,
                apiKey = item.apiKey,
                model = item.model.ifBlank {
                    ProviderCatalog.defaultModel(resolvedProvider.id, resolvedProtocol)
                },
                baseUrl = item.baseUrl.ifBlank {
                    ProviderCatalog.defaultBaseUrl(resolvedProvider.id, resolvedProtocol)
                },
                enabled = item.id.trim() == activeId
            )
        }
        return normalizeActiveProviderConfigs(mapped)
    }

    fun normalizeActiveProviderConfigs(configs: List<UiProviderConfig>): List<UiProviderConfig> {
        if (configs.isEmpty()) return emptyList()
        val activeId = configs.firstOrNull { it.enabled }?.id ?: configs.first().id
        return configs.map { it.copy(enabled = it.id == activeId) }
    }

    private fun buildValidatedProviderDraft(state: ProviderSettingsState): UiProviderConfig {
        val provider = ProviderCatalog.resolve(state.provider).id
        val baseUrl = validateEndpointUrl(state.baseUrl)
        val protocol = ProviderCatalog.resolveProtocol(provider, state.providerProtocol, baseUrl)
        val model = state.model.trim().ifBlank { ProviderCatalog.defaultModel(provider, protocol) }
        val apiKey = state.apiKeyDraft.trim()
        val id = state.editingProviderConfigId.trim()
            .ifBlank { "provider_${System.currentTimeMillis()}_${state.providerConfigs.size + 1}" }
        val enabled = state.providerConfigs.firstOrNull { it.id == id }?.enabled
            ?: state.providerConfigs.isEmpty()
        return UiProviderConfig(
            id = id,
            providerName = provider,
            customName = if (provider == "custom") state.providerCustomName.trim() else "",
            providerProtocol = protocol,
            apiKey = apiKey,
            model = model,
            baseUrl = baseUrl,
            enabled = enabled
        )
    }

    private fun validateEndpointUrl(rawUrl: String): String {
        val baseUrl = rawUrl.trim()
        if (baseUrl.isBlank()) {
            throw IllegalArgumentException("Endpoint URL is required")
        }
        val parsedBaseUrl = baseUrl.toHttpUrlOrNull()
            ?: throw IllegalArgumentException("Endpoint URL is invalid")
        val scheme = parsedBaseUrl.scheme.lowercase(Locale.US)
        if (scheme != "http" && scheme != "https") {
            throw IllegalArgumentException("Endpoint URL must start with http:// or https://")
        }
        return baseUrl
    }
}
