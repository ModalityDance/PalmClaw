package com.palmclaw.ui

import com.palmclaw.tools.BuiltInToolCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class UiPreferencesTest {

    @Test
    fun localizedText_usesExplicitChineseWhenProvided() {
        assertEquals(
            "手动文本",
            localizedText("Manual text", "手动文本", useChinese = true)
        )
    }

    @Test
    fun localizedText_usesCleanOverrideBeforeLegacyFallback() {
        assertEquals(
            "取消",
            localizedText("Cancel", useChinese = true)
        )
        assertEquals(
            "设置",
            localizedText("Settings", useChinese = true)
        )
    }

    @Test
    fun localizedText_fallsBackToEnglishWhenNoChineseExists() {
        assertEquals(
            "Brand new text",
            localizedText("Brand new text", useChinese = true)
        )
    }

    @Test
    fun localizedText_translatesBuiltInToolSettingsLabels() {
        BuiltInToolCatalog.all().forEach { descriptor ->
            assertNotEquals(
                descriptor.displayName,
                localizedText(descriptor.displayName, useChinese = true)
            )
            assertNotEquals(
                descriptor.description,
                localizedText(descriptor.description, useChinese = true)
            )
            assertNotEquals(
                descriptor.category,
                localizedText(descriptor.category, useChinese = true)
            )
        }
    }
}
