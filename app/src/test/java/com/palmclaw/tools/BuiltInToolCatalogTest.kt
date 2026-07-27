package com.palmclaw.tools

import com.palmclaw.config.AppConfig
import com.palmclaw.config.AppLimits
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BuiltInToolCatalogTest {

    @Test
    fun `user-manageable tools default to enabled`() {
        val config = AppConfig(
            providerName = AppLimits.DEFAULT_PROVIDER,
            apiKey = "",
            model = AppLimits.DEFAULT_MODEL
        )

        assertTrue(BuiltInToolCatalog.isEnabled(config, "web_search"))
        assertTrue(BuiltInToolCatalog.isEnabled(config, "read"))
    }

    @Test
    fun `forced-enabled tools ignore false toggle`() {
        val config = AppConfig(
            providerName = AppLimits.DEFAULT_PROVIDER,
            apiKey = "",
            model = AppLimits.DEFAULT_MODEL,
            toolToggles = mapOf(
                "message" to false,
                "sessions_send" to false,
                "workspace_get" to false,
                "web_search" to false
            )
        )

        assertTrue(BuiltInToolCatalog.isEnabled(config, "message"))
        assertTrue(BuiltInToolCatalog.isEnabled(config, "sessions_send"))
        assertTrue(BuiltInToolCatalog.isEnabled(config, "workspace_get"))
        assertFalse(BuiltInToolCatalog.isEnabled(config, "web_search"))
    }

    @Test
    fun `file catalog exposes the nine focused tools`() {
        val fileTools = BuiltInToolCatalog.all()
            .filter { it.category == "Files" }
            .map { it.toolName }

        assertEquals(
            listOf("find", "grep", "read", "write", "edit", "mkdir", "copy", "move", "delete"),
            fileTools
        )
    }

    @Test
    fun `find respects disabled legacy discovery toggles`() {
        val config = AppConfig(
            providerName = AppLimits.DEFAULT_PROVIDER,
            apiKey = "",
            model = AppLimits.DEFAULT_MODEL,
            toolToggles = mapOf("glob" to false)
        )

        assertFalse(BuiltInToolCatalog.isEnabled(config, "find"))
    }

    @Test
    fun `notification inherits a disabled legacy device toggle until explicitly configured`() {
        val legacyDisabled = AppConfig(
            providerName = AppLimits.DEFAULT_PROVIDER,
            apiKey = "",
            model = AppLimits.DEFAULT_MODEL,
            toolToggles = mapOf("device" to false)
        )
        val explicitlyEnabled = legacyDisabled.copy(
            toolToggles = mapOf("device" to false, "notification" to true)
        )

        assertFalse(BuiltInToolCatalog.isEnabled(legacyDisabled, "notification"))
        assertTrue(BuiltInToolCatalog.isEnabled(explicitlyEnabled, "notification"))
    }
}
