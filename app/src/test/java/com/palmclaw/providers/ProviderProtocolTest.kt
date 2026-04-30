package com.palmclaw.providers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProviderProtocolTest {

    @Test
    fun `fromRaw accepts legacy aliases and trims whitespace`() {
        assertEquals(ProviderProtocol.OpenAi, ProviderProtocol.fromRaw(" openai-chat "))
        assertEquals(ProviderProtocol.OpenAiResponses, ProviderProtocol.fromRaw("responses"))
        assertEquals(ProviderProtocol.Anthropic, ProviderProtocol.fromRaw(" anthropic "))
    }

    @Test
    fun `fromRaw returns null for unknown protocol`() {
        assertNull(ProviderProtocol.fromRaw("custom"))
    }
}
