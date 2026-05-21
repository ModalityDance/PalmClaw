package com.palmclaw.runtime

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AlwaysOnForegroundServiceStartPolicyTest {

    @Test
    fun `foreground service start denial is not retried by background workers`() {
        assertTrue(
            AlwaysOnForegroundServiceStartPolicy.isForegroundServiceStartDenied(
                className = "android.app.ForegroundServiceStartNotAllowedException",
                message = "startForegroundService not allowed"
            )
        )
        assertTrue(
            AlwaysOnForegroundServiceStartPolicy.isForegroundServiceStartDenied(
                className = "java.lang.IllegalStateException",
                message = "Not allowed to start service Intent"
            )
        )
        assertFalse(
            AlwaysOnForegroundServiceStartPolicy.isForegroundServiceStartDenied(
                className = "java.lang.IllegalStateException",
                message = "database unavailable"
            )
        )
    }
}
