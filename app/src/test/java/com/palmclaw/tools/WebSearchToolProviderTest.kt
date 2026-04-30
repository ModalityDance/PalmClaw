package com.palmclaw.tools

import com.palmclaw.config.SearchProviderConfigs
import com.palmclaw.config.SearchProviderId
import okhttp3.OkHttpClient
import org.junit.Assert.assertTrue
import org.junit.Test

class WebSearchToolProviderTest {

    private val client = OkHttpClient.Builder().build()

    @Test
    fun `brave provider returns explicit missing key error`() {
        assertMissingKeyError(SearchProviderId.Brave, "brave")
    }

    @Test
    fun `tavily provider returns explicit missing key error`() {
        assertMissingKeyError(SearchProviderId.Tavily, "tavily")
    }

    @Test
    fun `jina provider returns explicit missing key error`() {
        assertMissingKeyError(SearchProviderId.Jina, "jina")
    }

    @Test
    fun `kagi provider returns explicit missing key error`() {
        assertMissingKeyError(SearchProviderId.Kagi, "kagi")
    }

    private fun assertMissingKeyError(providerId: SearchProviderId, expectedLabel: String) {
        val tool = createWebToolSet(
            client = client,
            searchSettingsProvider = {
                SearchProviderRuntimeConfig(
                    providerId = providerId,
                    configs = SearchProviderConfigs()
                )
            }
        ).first { it.name == "web_search" }

        val result = kotlinx.coroutines.runBlocking {
            tool.run("""{"query":"test","count":1}""")
        }

        assertTrue(result.isError)
        assertTrue(result.content.contains(expectedLabel, ignoreCase = true))
        assertTrue(result.content.contains("API key", ignoreCase = true))
    }
}
