package com.palmclaw.tools

import com.palmclaw.config.AppConfig
import com.palmclaw.config.AppLimits
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
}
