package com.palmclaw.ui

import com.palmclaw.config.AppConfig
import com.palmclaw.config.AppSession
import com.palmclaw.config.ConfigStore
import com.palmclaw.config.OnboardingConfig
import com.palmclaw.memory.MemoryStore
import com.palmclaw.providers.ProviderCatalog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

private data class IdentityDisplayNames(
    val userDisplayName: String,
    val agentDisplayName: String
)

internal class OnboardingCoordinator(
    private val scope: CoroutineScope,
    private val stateStore: ChatStateStore,
    private val configStore: ConfigStore,
    private val memoryStore: MemoryStore,
    private val buildProviderStateWithSavedDraft: (ChatUiState) -> ChatUiState,
    private val buildProviderSettingsConfig: (ChatUiState) -> AppConfig,
    private val selectLocalSession: () -> Unit,
    private val loadSettingsIntoState: () -> Unit,
    private val maybeTriggerFirstRunAutoIntro: () -> Unit
) {
    fun onUserDisplayNameChanged(value: String) {
        stateStore.updateOnboarding { it.copy(onboardingUserDisplayName = value) }
        persistOnboardingDraft { it.copy(userDisplayName = value) }
    }

    fun onAgentDisplayNameChanged(value: String) {
        stateStore.updateOnboarding { it.copy(onboardingAgentDisplayName = value) }
        persistOnboardingDraft { it.copy(agentDisplayName = value) }
    }

    fun completeOnboarding() {
        if (stateStore.value.settingsSaving) return
        scope.launch {
            stateStore.updateShell { it.copy(settingsSaving = true, settingsInfo = null) }
            runCatching {
                val state = stateStore.value
                val useChinese = state.settingsUseChinese
                val userDisplayName = state.onboardingUserDisplayName.trim()
                    .ifBlank { if (useChinese) "你" else "You" }
                val agentDisplayName = state.onboardingAgentDisplayName.trim()
                    .ifBlank { "PalmClaw" }
                val updatedState = buildProviderStateWithSavedDraft(state)
                configStore.saveConfig(buildProviderSettingsConfig(updatedState))
                configStore.saveOnboardingConfig(
                    OnboardingConfig(
                        completed = true,
                        userDisplayName = userDisplayName,
                        agentDisplayName = agentDisplayName
                    )
                )
                syncIdentityPreferencesToMemory(
                    userDisplayName = userDisplayName,
                    agentDisplayName = agentDisplayName
                )
            }.onSuccess {
                selectLocalSession()
                loadSettingsIntoState()
                stateStore.updateOnboarding {
                    it.copy(
                        settingsSaving = false,
                        onboardingCompleted = true,
                        settingsInfo = null
                    )
                }
                maybeTriggerFirstRunAutoIntro()
            }.onFailure { error ->
                stateStore.updateShell {
                    it.copy(
                        settingsSaving = false,
                        settingsInfo = "Setup failed: ${error.message ?: error.javaClass.simpleName}"
                    )
                }
            }
        }
    }

    fun persistProviderDraftIfNeeded() {
        val state = stateStore.value
        if (state.onboardingCompleted) return
        val resolvedProvider = ProviderCatalog.resolve(state.settingsProvider)
        val protocol = ProviderCatalog.resolveProtocol(
            rawProvider = resolvedProvider.id,
            requested = state.settingsProviderProtocol,
            baseUrl = state.settingsBaseUrl
        )
        val current = configStore.getConfig()
        configStore.saveConfig(
            current.copy(
                providerName = resolvedProvider.id,
                providerProtocol = protocol,
                apiKey = state.settingsApiKey.trim(),
                model = state.settingsModel.trim().ifBlank {
                    ProviderCatalog.defaultModel(resolvedProvider.id, protocol)
                },
                baseUrl = state.settingsBaseUrl.trim().ifBlank {
                    ProviderCatalog.defaultBaseUrl(resolvedProvider.id, protocol)
                }
            )
        )
    }

    fun resolveSyncedOnboardingConfig(
        baseConfig: OnboardingConfig = configStore.getOnboardingConfig()
    ): OnboardingConfig = Companion.resolveSyncedOnboardingConfig(
        configStore = configStore,
        memoryStore = memoryStore,
        baseConfig = baseConfig
    )

    private fun persistOnboardingDraft(
        transform: (OnboardingConfig) -> OnboardingConfig
    ) {
        val current = normalizeOnboardingConfig(configStore.getOnboardingConfig())
        val next = normalizeOnboardingConfig(transform(current))
        if (next != current) {
            configStore.saveOnboardingConfig(next)
        }
    }

    private fun syncIdentityPreferencesToMemory(
        userDisplayName: String,
        agentDisplayName: String
    ) {
        val existing = memoryStore.readLongTerm().trim()
        val legacySectionRegex = Regex("(?ms)^## Identity Preferences\\s.*?(?=^##\\s|\\z)")
        val withoutLegacySection = existing.replace(legacySectionRegex, "").trim()
        val userInformationRegex = Regex("(?ms)^## User Information\\s*$.*?(?=^##\\s|\\z)")
        val updated = when {
            withoutLegacySection.isBlank() -> buildUserInformationMemory(
                base = "## User Information",
                userDisplayName = userDisplayName,
                agentDisplayName = agentDisplayName
            )

            userInformationRegex.containsMatchIn(withoutLegacySection) -> {
                userInformationRegex.replace(withoutLegacySection) { match ->
                    buildUserInformationMemory(
                        base = match.value.trim(),
                        userDisplayName = userDisplayName,
                        agentDisplayName = agentDisplayName
                    )
                }
            }

            else -> withoutLegacySection + "\n\n" + buildUserInformationMemory(
                base = "## User Information",
                userDisplayName = userDisplayName,
                agentDisplayName = agentDisplayName
            )
        }
        memoryStore.writeLongTerm(updated.trimEnd() + "\n")
    }

    private fun buildUserInformationMemory(
        base: String,
        userDisplayName: String,
        agentDisplayName: String
    ): String {
        val placeholderRegex = Regex("(?m)^\\(Important facts about the user\\)\\s*$")
        val preferredUserRegex = Regex("(?im)^[-*]\\s*User preferred name\\s*:\\s*.+?\\s*$")
        val preferredAgentRegex = Regex("(?im)^[-*]\\s*Agent preferred name\\s*:\\s*.+?\\s*$")

        val cleaned = base
            .replace(placeholderRegex, "")
            .replace(preferredUserRegex, "")
            .replace(preferredAgentRegex, "")
            .replace(Regex("\n{3,}"), "\n\n")
            .trimEnd()

        val identityLines = """
- User preferred name: $userDisplayName
- Agent preferred name: $agentDisplayName
        """.trim()

        return if (cleaned.equals("## User Information", ignoreCase = true)) {
            cleaned + "\n\n" + identityLines
        } else {
            cleaned + "\n" + identityLines
        }
    }

    companion object {
        private val identityPreferencesSectionRegex = Regex(
            "(?ms)^## Identity Preferences\\s.*?(?=^##\\s|\\z)"
        )
        private val identityPreferredUserRegex = Regex(
            "(?im)^[-*]\\s*User preferred name\\s*:\\s*(.+?)\\s*$"
        )
        private val identityPreferredAgentRegex = Regex(
            "(?im)^[-*]\\s*Agent preferred name\\s*:\\s*(.+?)\\s*$"
        )

        fun resolveSyncedOnboardingConfig(
            configStore: ConfigStore,
            memoryStore: MemoryStore,
            baseConfig: OnboardingConfig = configStore.getOnboardingConfig()
        ): OnboardingConfig {
            val normalizedBase = normalizeOnboardingConfig(baseConfig)
            val identity = readIdentityPreferencesFromMemory(memoryStore) ?: return normalizedBase
            val synced = normalizeOnboardingConfig(
                normalizedBase.copy(
                    userDisplayName = identity.userDisplayName.ifBlank { normalizedBase.userDisplayName },
                    agentDisplayName = identity.agentDisplayName.ifBlank { normalizedBase.agentDisplayName }
                )
            )
            if (synced != normalizedBase) {
                configStore.saveOnboardingConfig(synced)
            }
            return synced
        }

        private fun readIdentityPreferencesFromMemory(memoryStore: MemoryStore): IdentityDisplayNames? {
            val memory = memoryStore.readLongTerm()
            if (memory.isBlank()) return null
            val section = identityPreferencesSectionRegex.find(memory)?.value ?: return null
            val userDisplayName = identityPreferredUserRegex.find(section)
                ?.groupValues
                ?.getOrNull(1)
                .orEmpty()
                .trim()
            val agentDisplayName = identityPreferredAgentRegex.find(section)
                ?.groupValues
                ?.getOrNull(1)
                .orEmpty()
                .trim()
                .ifBlank { "PalmClaw" }
            if (userDisplayName.isBlank() && agentDisplayName == "PalmClaw") return null
            return IdentityDisplayNames(
                userDisplayName = userDisplayName,
                agentDisplayName = agentDisplayName
            )
        }

        private fun normalizeOnboardingConfig(config: OnboardingConfig): OnboardingConfig {
            return config.copy(
                userDisplayName = config.userDisplayName.trim(),
                agentDisplayName = config.agentDisplayName.trim().ifBlank { "PalmClaw" }
            )
        }
    }
}
