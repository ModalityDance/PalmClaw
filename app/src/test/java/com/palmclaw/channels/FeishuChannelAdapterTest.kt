package com.palmclaw.channels

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FeishuChannelAdapterTest {

    private val adapter = FeishuChannelAdapter(
        adapterKey = "test-feishu",
        appId = "app-id",
        appSecret = "app-secret"
    )

    @Test
    fun `mention mode still requires bot mention`() {
        assertTrue(
            invokeShouldRequireBotMention(
                chatType = "group",
                routeRule = FeishuRouteRule(responseMode = "mention")
            )
        )
    }

    @Test
    fun `open mode disables bot mention requirement`() {
        assertFalse(
            invokeShouldRequireBotMention(
                chatType = "group",
                routeRule = FeishuRouteRule(responseMode = "open")
            )
        )
    }

    @Test
    fun `attachment-only inbound messages bypass mention requirement`() {
        assertTrue(invokeShouldBypassMentionRequirement("file"))
        assertTrue(invokeShouldBypassMentionRequirement("image"))
        assertTrue(invokeShouldBypassMentionRequirement("audio"))
        assertTrue(invokeShouldBypassMentionRequirement("media"))
        assertFalse(invokeShouldBypassMentionRequirement("text"))
    }

    private fun invokeShouldRequireBotMention(
        chatType: String,
        routeRule: FeishuRouteRule
    ): Boolean {
        val method = adapter.javaClass.getDeclaredMethod(
            "shouldRequireBotMention",
            String::class.java,
            FeishuRouteRule::class.java
        )
        method.isAccessible = true
        return method.invoke(adapter, chatType, routeRule) as Boolean
    }

    private fun invokeShouldBypassMentionRequirement(messageType: String): Boolean {
        val method = adapter.javaClass.getDeclaredMethod(
            "shouldBypassMentionRequirement",
            String::class.java
        )
        method.isAccessible = true
        return method.invoke(adapter, messageType) as Boolean
    }
}
