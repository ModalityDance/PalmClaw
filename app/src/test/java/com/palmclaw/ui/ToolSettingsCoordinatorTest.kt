package com.palmclaw.ui

import com.palmclaw.ui.settings.ToolSettingsCoordinator
import com.palmclaw.ui.settings.UiBuiltInToolConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ToolSettingsCoordinatorTest {
    @Test
    fun `tool toggle updates state and saves immediately`() {
        val stateStore = ChatStateStore(
            ChatUiState(
                settingsBuiltInTools = listOf(
                    UiBuiltInToolConfig(
                        toolName = "web_search",
                        displayName = "Web Search",
                        description = "Search the web.",
                        category = "Web",
                        enabled = true,
                        enabledByDefault = true
                    )
                )
            )
        )
        var saveCalls = 0
        var successMessage = true
        var errorMessage = true
        val coordinator = ToolSettingsCoordinator(
            stateStore = stateStore,
            actions = ToolSettingsCoordinator.Actions(
                saveToolSettings = { showSuccessMessage, showErrorMessage ->
                    saveCalls += 1
                    successMessage = showSuccessMessage
                    errorMessage = showErrorMessage
                }
            )
        )

        coordinator.onToolEnabledChanged("web_search", false)

        assertFalse(stateStore.toolSettingsState.value.builtInTools.single().enabled)
        assertEquals(1, saveCalls)
        assertFalse(successMessage)
        assertFalse(errorMessage)
    }
}
