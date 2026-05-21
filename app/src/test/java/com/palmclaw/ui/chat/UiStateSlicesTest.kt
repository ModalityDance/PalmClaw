package com.palmclaw.ui

import com.palmclaw.skills.SkillCompatibilityStatus
import com.palmclaw.skills.SkillSource
import com.palmclaw.ui.settings.UiSkillConfig
import com.palmclaw.ui.settings.UiSkillDownloadStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UiStateSlicesTest {
    @Test
    fun `skills discovery state excludes provider channel and mcp secrets`() {
        val state = ChatUiState(
            settingsApiKey = "provider-secret",
            settingsTelegramBotToken = "telegram-secret",
            settingsMcpAuthToken = "mcp-secret",
            settingsClawHubSearchQuery = "calendar",
            settingsSkillDownloadStatus = UiSkillDownloadStatus(
                key = "calendar",
                title = "Calendar",
                detailUrl = "https://example.com/calendar",
                status = "Downloading...",
                inProgress = true
            ),
            settingsSkillsLoading = true
        )

        val skillState = state.toSkillsDiscoveryState()
        val rendered = skillState.toString()

        assertEquals("calendar", skillState.clawHubSearchQuery)
        assertEquals("Calendar", skillState.downloadStatus?.title)
        assertTrue(skillState.skillsLoading)
        assertFalse(rendered.contains("provider-secret"))
        assertFalse(rendered.contains("telegram-secret"))
        assertFalse(rendered.contains("mcp-secret"))
    }

    @Test
    fun `channels state keeps route summaries without channel credentials`() {
        val state = ChatUiState(
            sessions = listOf(
                UiSessionSummary(id = "local", title = "Local", isLocal = true),
                UiSessionSummary(
                    id = "session-1",
                    title = "Session",
                    isLocal = false,
                    boundChannel = "telegram",
                    boundTelegramBotToken = "telegram-secret",
                    boundChatId = ""
                )
            ),
            settingsTelegramBotToken = "global-telegram-secret"
        )

        val channelsState = state.toChannelsSettingsState()
        val route = channelsState.sessions.single()
        val rendered = channelsState.toString()

        assertEquals("session-1", route.id)
        assertEquals("telegram", route.boundChannel)
        assertTrue(route.pendingDetection)
        assertFalse(rendered.contains("telegram-secret"))
        assertFalse(rendered.contains("global-telegram-secret"))
    }

    @Test
    fun `provider state isolates provider editor draft`() {
        val state = ChatUiState(
            settingsApiKey = "provider-secret",
            settingsMcpAuthToken = "mcp-secret",
            settingsTokenTotal = 42
        )

        val providerState = state.toProviderSettingsState()

        assertEquals("provider-secret", providerState.apiKeyDraft)
        assertEquals(42L, providerState.tokenTotal)
        assertFalse(providerState.toString().contains("mcp-secret"))
    }

    @Test
    fun `skills discovery groups built in local and clawhub installed skills`() {
        val state = ChatUiState(
            settingsInstalledSkills = listOf(
                skill("channels", SkillSource.Builtin),
                skill("local-note", SkillSource.Local),
                skill("claw-calendar", SkillSource.ClawHub)
            )
        )

        val skillState = state.toSkillsDiscoveryState()

        assertEquals(listOf("channels", "local-note"), skillState.builtInLocalSkills.map { it.name })
        assertEquals(listOf("claw-calendar"), skillState.clawHubInstalledSkills.map { it.name })
    }

    private fun skill(name: String, source: SkillSource): UiSkillConfig {
        return UiSkillConfig(
            name = name,
            displayName = name,
            description = "",
            source = source,
            enabled = true,
            allowIncompatible = false,
            always = false,
            compatibilityStatus = SkillCompatibilityStatus.Compatible,
            compatibilityReasons = emptyList(),
            requirementsStatus = ""
        )
    }
}
