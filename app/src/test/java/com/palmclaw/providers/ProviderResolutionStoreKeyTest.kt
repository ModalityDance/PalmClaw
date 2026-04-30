package com.palmclaw.providers

import org.junit.Assert.assertEquals
import org.junit.Test

class ProviderResolutionStoreKeyTest {

    @Test
    fun `cacheKeyFor uses config prefix and trims all parts`() {
        assertEquals(
            "cfg:cfg-1|openai|https://api.example.com/v1/chat/completions|gpt-5.4-mini",
            ProviderResolutionStore.cacheKeyFor(
                configId = " cfg-1 ",
                providerName = " openai ",
                baseUrl = " https://api.example.com/v1/chat/completions ",
                model = " gpt-5.4-mini "
            )
        )
    }

    @Test
    fun `cacheKeyFor falls back to adhoc prefix when config id is blank`() {
        assertEquals(
            "adhoc:custom|https://gateway.example.com|model-a",
            ProviderResolutionStore.cacheKeyFor(
                configId = "   ",
                providerName = "custom",
                baseUrl = "https://gateway.example.com",
                model = "model-a"
            )
        )
    }

    @Test
    fun `cachePrefixForProviderConfig trims the config id`() {
        assertEquals(
            "cfg:cfg-2|",
            ProviderResolutionStore.cachePrefixForProviderConfig(" cfg-2 ")
        )
    }
}
