package com.palmclaw.skills

import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ClawHubClientTest {

    private val client = ClawHubClient(OkHttpClient())

    @Test
    fun `parse registry payload supports featured and popular fields`() {
        val cards = client.parseRegistryPayload(
            """
            {
              "skills": [
                {
                  "slug": "alpha-skill",
                  "title": "Alpha Skill",
                  "summary": "Featured skill",
                  "author": "Palm",
                  "version": "1.2.3",
                  "license": "MIT",
                  "downloads": "12k",
                  "featured": true,
                  "url": "https://clawhub.ai/palm/alpha-skill"
                }
              ]
            }
            """.trimIndent()
        )

        assertEquals(1, cards.size)
        assertEquals("alpha-skill", cards.first().slug)
        assertEquals("Alpha Skill", cards.first().title)
        assertEquals("https://clawhub.ai/palm/alpha-skill", cards.first().detailUrl)
    }

    @Test
    fun `parse detail html extracts download url and metadata`() {
        val detail = client.parseDetailHtml(
            detailUrl = "https://clawhub.ai/palm/alpha-skill",
            html = """
                <html>
                  <head>
                    <meta name="description" content="A useful skill for mobile workflows.">
                  </head>
                  <body>
                    <h1>Alpha Skill v1.2.3</h1>
                    <a href="https://clawhub.ai/palm">@palm</a>
                    <a href="https://downloads.example.com/alpha.zip">Download zip</a>
                    <div>MIT-0</div>
                    <div>VirusTotal clean OpenClaw approved Current version</div>
                  </body>
                </html>
            """.trimIndent()
        )

        assertEquals("alpha-skill", detail.slug)
        assertEquals("Alpha Skill v1.2.3", detail.title)
        assertEquals("palm", detail.author)
        assertEquals("1.2.3", detail.version)
        assertEquals("https://downloads.example.com/alpha.zip", detail.downloadUrl)
        assertTrue(detail.securitySignals.isNotEmpty())
    }
}
