package com.palmclaw.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NotificationKeyCodecTest {

    @Test
    fun `keys are canonical and round trip through the Android tag namespace`() {
        val key = NotificationKeyCodec.normalize("Paper.Ready")

        assertEquals("paper.ready", key)
        assertEquals("palmclaw.agent.paper.ready", NotificationKeyCodec.toTag(key!!))
        assertEquals("paper.ready", NotificationKeyCodec.fromTag("palmclaw.agent.paper.ready"))
        assertEquals(
            "paper.ready",
            NotificationKeyCodec.fromAndroidIdentity(
                "palmclaw.agent.paper.ready",
                NotificationKeyCodec.ANDROID_NOTIFICATION_ID
            )
        )
    }

    @Test
    fun `invalid and foreign keys are rejected`() {
        assertNull(NotificationKeyCodec.normalize(""))
        assertNull(NotificationKeyCodec.normalize("contains spaces"))
        assertNull(NotificationKeyCodec.normalize(".leading-dot"))
        assertNull(NotificationKeyCodec.normalize("a".repeat(65)))
        assertNull(NotificationKeyCodec.fromTag("cron.reminder"))
        assertNull(
            NotificationKeyCodec.fromAndroidIdentity(
                "palmclaw.agent.paper.ready",
                NotificationKeyCodec.ANDROID_NOTIFICATION_ID + 1
            )
        )
    }
}
