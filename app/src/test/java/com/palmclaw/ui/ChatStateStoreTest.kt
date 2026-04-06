package com.palmclaw.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ChatStateStoreTest {

    @Test
    fun updateSession_keepsNonSessionFieldsUntouched() {
        val store = ChatStateStore(
            ChatUiState(
                input = "draft",
                settingsInfo = "info",
                settingsDarkTheme = true
            )
        )

        store.updateSession { it.copy(input = "updated", currentSessionTitle = "Renamed") }

        assertEquals("updated", store.value.input)
        assertEquals("Renamed", store.value.currentSessionTitle)
        assertEquals("info", store.value.settingsInfo)
        assertEquals(true, store.value.settingsDarkTheme)
    }

    @Test
    fun updateAppUpdate_keepsChatDraftUntouched() {
        val store = ChatStateStore(
            ChatUiState(
                input = "keep me",
                settingsUpdateChecking = false
            )
        )

        store.updateAppUpdate { it.copy(settingsUpdateChecking = true, settingsLatestVersion = "1.2.3") }

        assertEquals("keep me", store.value.input)
        assertEquals("1.2.3", store.value.settingsLatestVersion)
        assertEquals(true, store.value.settingsUpdateChecking)
    }

    @Test
    fun updateRuntime_canToggleRuntimeFlagsWithoutChangingOnboardingFields() {
        val store = ChatStateStore(
            ChatUiState(
                onboardingCompleted = true,
                userDisplayName = "User",
                settingsGatewayEnabled = false
            )
        )

        store.updateRuntime { it.copy(settingsGatewayEnabled = true, alwaysOnEnabled = true) }

        assertEquals("User", store.value.userDisplayName)
        assertEquals(true, store.value.onboardingCompleted)
        assertEquals(true, store.value.settingsGatewayEnabled)
        assertEquals(true, store.value.alwaysOnEnabled)
        assertFalse(store.value.settingsUpdateChecking)
    }
}
