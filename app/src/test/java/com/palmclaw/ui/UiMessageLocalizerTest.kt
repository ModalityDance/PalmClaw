package com.palmclaw.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UiMessageLocalizerTest {

    @Test
    fun localizedUiMessage_keepsEnglishWhenChineseDisabled() {
        val raw = "Provider test passed."

        assertEquals(raw, localizedUiMessage(raw, useChinese = false))
    }

    @Test
    fun localizedUiMessage_translatesExactMessage() {
        assertEquals(
            "提供方测试通过。",
            localizedUiMessage("Provider test passed.", useChinese = true)
        )
    }

    @Test
    fun localizedUiMessage_translatesProviderHttpFailures() {
        assertEquals(
            "OpenAI API 请求失败（HTTP 401，认证失败，请检查 API Key）：API Key 不正确",
            localizedUiMessage("OpenAI HTTP 401: Incorrect API key provided", useChinese = true)
        )
    }

    @Test
    fun localizedUiMessage_translatesPrefixedMessages() {
        assertEquals(
            "保存失败：API Key 无效",
            localizedUiMessage("Save failed: Invalid API key", useChinese = true)
        )
    }

    @Test
    fun shouldLocalizeUiMessage_detectsStructuredMessages() {
        assertTrue(shouldLocalizeUiMessage("Unsupported channel: discord"))
        assertTrue(shouldLocalizeUiMessage("OpenAI HTTP 401: Incorrect API key provided"))
        assertFalse(shouldLocalizeUiMessage("hello world"))
    }
}
