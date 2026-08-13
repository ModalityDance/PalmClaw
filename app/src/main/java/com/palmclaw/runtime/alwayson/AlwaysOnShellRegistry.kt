package com.palmclaw.runtime.alwayson

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal data class AlwaysOnShellSnapshot(val running: Boolean = false)

/** Process-local fact source for the Android foreground-service shell. */
internal object AlwaysOnShellRegistry {
    private val lock = Any()
    private val mutableState = MutableStateFlow(AlwaysOnShellSnapshot())
    private var expectedStop = false

    val state: StateFlow<AlwaysOnShellSnapshot> = mutableState.asStateFlow()

    fun markRunning() {
        synchronized(lock) {
            expectedStop = false
            mutableState.value = AlwaysOnShellSnapshot(running = true)
        }
    }

    fun expectStop() {
        synchronized(lock) {
            expectedStop = true
            mutableState.value = AlwaysOnShellSnapshot()
        }
    }

    /** Returns true when this destruction was requested by the coordinator. */
    fun markStopped(): Boolean = synchronized(lock) {
        val wasExpected = expectedStop
        expectedStop = false
        mutableState.value = AlwaysOnShellSnapshot()
        wasExpected
    }

    internal fun resetForTest() {
        synchronized(lock) {
            expectedStop = false
            mutableState.value = AlwaysOnShellSnapshot()
        }
    }
}

/** Owns the foreground-service instance's stop classification. */
internal class AlwaysOnServiceLifecycle(
    private val onRunning: () -> Unit = AlwaysOnShellRegistry::markRunning,
    private val onStopped: () -> Boolean = AlwaysOnShellRegistry::markStopped
) {
    private var destructionTrigger: AlwaysOnTrigger? = null
    private var destructionHandled = false

    /**
     * Reasserts that the current service instance is live. A new valid start
     * supersedes pending expected-stop and timeout classifications.
     */
    fun markRunning() {
        if (!destructionHandled) {
            onRunning()
            destructionTrigger = null
        }
    }

    fun markTimedOut() {
        if (!destructionHandled) {
            destructionTrigger = AlwaysOnTrigger.SERVICE_TIMEOUT
        }
    }

    /**
     * Performs the single stopped transition and returns the recovery trigger,
     * if this destruction needs one.
     */
    fun markDestroyed(): AlwaysOnTrigger? {
        if (destructionHandled) return null
        destructionHandled = true
        val expectedStop = onStopped()
        return destructionTrigger
            ?: if (expectedStop) null else AlwaysOnTrigger.SERVICE_STATE_CHANGED
    }
}
