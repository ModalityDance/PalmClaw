package com.palmclaw.ui

import com.palmclaw.config.AppLimits
import com.palmclaw.config.TokenUsageStats
import com.palmclaw.providers.ProviderCatalog
import com.palmclaw.providers.ProviderProtocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProviderSettingsCoordinatorTest {

    @Test
    fun `startNewProviderDraft resets editing state to app defaults`() {
        val stateStore = ChatStateStore(
            ChatUiState(
                settingsEditingProviderConfigId = "cfg-1",
                settingsProvider = "custom",
                settingsProviderCustomName = "Primary",
                settingsInfo = "error"
            )
        )
        val coordinator = coordinator(stateStore)

        coordinator.startNewProviderDraft()

        assertEquals("", stateStore.value.settingsEditingProviderConfigId)
        assertEquals(AppLimits.DEFAULT_PROVIDER, stateStore.value.settingsProvider)
        assertEquals("", stateStore.value.settingsProviderCustomName)
        assertEquals(
            ProviderCatalog.defaultBaseUrl(
                AppLimits.DEFAULT_PROVIDER,
                ProviderCatalog.defaultProtocol(AppLimits.DEFAULT_PROVIDER)
            ),
            stateStore.value.settingsBaseUrl
        )
        assertEquals(
            ProviderCatalog.defaultModel(
                AppLimits.DEFAULT_PROVIDER,
                ProviderCatalog.defaultProtocol(AppLimits.DEFAULT_PROVIDER)
            ),
            stateStore.value.settingsModel
        )
        assertNull(stateStore.value.settingsInfo)
    }

    @Test
    fun `onSettingsProviderChanged resolves aliases and resets provider-specific draft fields`() {
        var persistCalls = 0
        val stateStore = ChatStateStore(
            ChatUiState(
                settingsProvider = "custom",
                settingsProviderCustomName = "My Gateway",
                settingsApiKey = "secret"
            )
        )
        val coordinator = coordinator(
            stateStore = stateStore,
            persistDraft = { persistCalls += 1 }
        )

        coordinator.onSettingsProviderChanged(" claude ")

        assertEquals("anthropic", stateStore.value.settingsProvider)
        assertEquals("", stateStore.value.settingsProviderCustomName)
        assertEquals(ProviderProtocol.Anthropic, stateStore.value.settingsProviderProtocol)
        assertEquals(
            ProviderCatalog.defaultBaseUrl("anthropic", ProviderProtocol.Anthropic),
            stateStore.value.settingsBaseUrl
        )
        assertEquals(
            ProviderCatalog.defaultModel("anthropic", ProviderProtocol.Anthropic),
            stateStore.value.settingsModel
        )
        assertEquals("", stateStore.value.settingsApiKey)
        assertEquals(1, persistCalls)
    }

    @Test
    fun `onSettingsBaseUrlChanged infers provider protocol from endpoint and persists onboarding draft`() {
        var persistCalls = 0
        val stateStore = ChatStateStore(
            ChatUiState(
                settingsProvider = "custom",
                settingsProviderProtocol = ProviderProtocol.OpenAi
            )
        )
        val coordinator = coordinator(
            stateStore = stateStore,
            persistDraft = { persistCalls += 1 }
        )

        coordinator.onSettingsBaseUrlChanged("https://proxy.example.com/v1/messages")

        assertEquals("https://proxy.example.com/v1/messages", stateStore.value.settingsBaseUrl)
        assertEquals(ProviderProtocol.Anthropic, stateStore.value.settingsProviderProtocol)
        assertEquals(1, persistCalls)
    }

    @Test
    fun `selectProviderConfigForEditing backfills missing model and base url from provider defaults`() {
        val stateStore = ChatStateStore(
            ChatUiState(
                settingsProviderConfigs = listOf(
                    UiProviderConfig(
                        id = "cfg-1",
                        providerName = "openai",
                        providerProtocol = ProviderProtocol.OpenAi,
                        model = "",
                        baseUrl = "",
                        apiKey = "secret"
                    )
                )
            )
        )
        val coordinator = coordinator(stateStore)

        coordinator.selectProviderConfigForEditing(" cfg-1 ")

        assertEquals("cfg-1", stateStore.value.settingsEditingProviderConfigId)
        assertEquals("openai", stateStore.value.settingsProvider)
        assertEquals(
            ProviderCatalog.defaultBaseUrl("openai", ProviderProtocol.OpenAi),
            stateStore.value.settingsBaseUrl
        )
        assertEquals(
            ProviderCatalog.defaultModel("openai", ProviderProtocol.OpenAi),
            stateStore.value.settingsModel
        )
        assertEquals("secret", stateStore.value.settingsApiKey)
    }

    @Test
    fun `clearProviderTokenUsageStats maps cleared counters back into state`() {
        val stateStore = ChatStateStore(ChatUiState())
        val coordinator = ProviderSettingsCoordinator(
            stateStore = stateStore,
            clearTokenUsageStats = {
                TokenUsageStats(
                    inputTokens = 11,
                    outputTokens = 22,
                    totalTokens = 33,
                    cachedInputTokens = 44,
                    requests = 55
                )
            },
            persistOnboardingProviderDraftIfNeeded = {},
            actions = ProviderSettingsCoordinator.Actions(
                setActiveProviderConfig = {},
                deleteProviderConfig = {},
                saveProviderSettings = { _, _ -> },
                saveAgentRuntimeSettings = { _, _ -> },
                testProviderSettings = {}
            )
        )

        coordinator.clearProviderTokenUsageStats()

        assertEquals(11, stateStore.value.settingsTokenInput)
        assertEquals(22, stateStore.value.settingsTokenOutput)
        assertEquals(33, stateStore.value.settingsTokenTotal)
        assertEquals(44, stateStore.value.settingsTokenCachedInput)
        assertEquals(55, stateStore.value.settingsTokenRequests)
        assertEquals("Provider token stats cleared.", stateStore.value.settingsInfo)
    }

    private fun coordinator(
        stateStore: ChatStateStore,
        persistDraft: () -> Unit = {}
    ): ProviderSettingsCoordinator {
        return ProviderSettingsCoordinator(
            stateStore = stateStore,
            clearTokenUsageStats = { TokenUsageStats() },
            persistOnboardingProviderDraftIfNeeded = persistDraft,
            actions = ProviderSettingsCoordinator.Actions(
                setActiveProviderConfig = {},
                deleteProviderConfig = {},
                saveProviderSettings = { _, _ -> },
                saveAgentRuntimeSettings = { _, _ -> },
                testProviderSettings = {}
            )
        )
    }
}
