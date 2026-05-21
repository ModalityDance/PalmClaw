package com.palmclaw.attachments

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AttachmentDownloadPolicyTest {

    @Test
    fun `remote attachment downloads allow only http and https`() {
        assertTrue(AttachmentDownloadPolicy.isAllowed("https://example.com/file.png"))
        assertTrue(AttachmentDownloadPolicy.isAllowed("http://example.com/file.png"))
        assertFalse(AttachmentDownloadPolicy.isAllowed("file:///sdcard/Download/file.png"))
        assertFalse(AttachmentDownloadPolicy.isAllowed("content://downloads/file.png"))
        assertFalse(AttachmentDownloadPolicy.isAllowed("ftp://example.com/file.png"))
    }
}
