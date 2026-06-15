package com.palmclaw.skills

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClawHubDownloadPolicyTest {

    @Test
    fun `clawhub skill downloads require https`() {
        assertTrue(ClawHubDownloadPolicy.isAllowed("https://example.com/skill.zip"))
        assertFalse(ClawHubDownloadPolicy.isAllowed("http://example.com/skill.zip"))
        assertFalse(ClawHubDownloadPolicy.isAllowed("file:///sdcard/Download/skill.zip"))
        assertFalse(ClawHubDownloadPolicy.isAllowed("content://downloads/skill.zip"))
    }
}
