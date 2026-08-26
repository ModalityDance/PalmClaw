package com.palmclaw.runtime

import java.util.Locale

internal object AlwaysOnForegroundServiceStartPolicy {
    fun isForegroundServiceStartDenied(t: Throwable): Boolean {
        return isForegroundServiceStartDenied(
            className = t.javaClass.name,
            message = t.message.orEmpty()
        )
    }

    fun isForegroundServiceStartDenied(className: String, message: String): Boolean {
        val normalized = message.lowercase(Locale.US)
        return className == "android.app.ForegroundServiceStartNotAllowedException" ||
            (
                className.endsWith("IllegalStateException") &&
                    normalized.contains("not allowed") &&
                    normalized.contains("start") &&
                    normalized.contains("service")
                )
    }
}
