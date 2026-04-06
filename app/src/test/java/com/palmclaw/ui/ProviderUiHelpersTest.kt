package com.palmclaw.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

class ProviderUiHelpersTest {

    @Test
    fun providerApiPortalUrl_returnsExpectedUrls() {
        assertEquals("https://platform.openai.com/api-keys", providerApiPortalUrl("openai"))
        assertEquals("https://openrouter.ai/keys", providerApiPortalUrl(" OpenRouter "))
        assertNull(providerApiPortalUrl("unknown"))
    }

    @Test
    fun providerPortalButtonText_returnsLocalizedStatefulCopy() {
        assertEquals(
            "Open OpenAI API page",
            providerPortalButtonText(useChinese = false, providerTitle = "OpenAI", enabled = true)
        )
        assertEquals(
            "前往 OpenAI 官方 API 页面",
            providerPortalButtonText(useChinese = true, providerTitle = "OpenAI", enabled = true)
        )
        assertEquals(
            "OpenAI 没有官方 API 页面",
            providerPortalButtonText(useChinese = true, providerTitle = "OpenAI", enabled = false)
        )
    }

    @Test
    fun isCustomProvider_matchesCatalogResolution() {
        assertTrue(isCustomProvider("custom"))
        assertFalse(isCustomProvider("openai"))
    }
}
