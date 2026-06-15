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
        assertEquals(
            "检测会话失败：Telegram API 返回 404。请检查 Bot Token，并只粘贴 BotFather 给出的 token，不要粘贴完整 API URL。",
            localizedUiMessage(
                "Discover chats failed: Telegram API returned 404. Check the Bot Token and paste only the token from BotFather, not the full API URL.",
                useChinese = true
            )
        )
    }

    @Test
    fun localizedUiMessage_translatesSkillDownloadMessages() {
        assertEquals(
            "待审查",
            localizedUiMessage("Ready for review", useChinese = true)
        )
        assertEquals(
            "正在下载技能：calendar",
            localizedUiMessage("Downloading skill: calendar", useChinese = true)
        )
        assertEquals(
            "技能已下载，请审查后再安装。",
            localizedUiMessage("Skill downloaded. Review before installing.", useChinese = true)
        )
    }

    @Test
    fun shouldLocalizeUiMessage_detectsStructuredMessages() {
        assertTrue(shouldLocalizeUiMessage("Unsupported channel: discord"))
        assertTrue(shouldLocalizeUiMessage("OpenAI HTTP 401: Incorrect API key provided"))
        assertTrue(shouldLocalizeUiMessage("Ready for review"))
        assertTrue(shouldLocalizeUiMessage("Downloading skill: calendar"))
        assertFalse(shouldLocalizeUiMessage("hello world"))
    }
}
