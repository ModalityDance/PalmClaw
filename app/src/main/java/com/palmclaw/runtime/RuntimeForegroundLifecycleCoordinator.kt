package com.palmclaw.runtime

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal class RuntimeForegroundLifecycleCoordinator(
    private val scope: CoroutineScope,
    private val enterForeground: suspend () -> Unit,
    private val leaveForeground: suspend () -> Unit
) {
    private val desiredLock = Any()
    private val transitionMutex = Mutex()
    private var desiredForeground = false
    private var appliedForeground = false

    fun requestForegrounded(foregrounded: Boolean): Job {
        synchronized(desiredLock) {
            desiredForeground = foregrounded
        }
        return scope.launch {
            reconcileLatestState()
        }
    }

    private suspend fun reconcileLatestState() {
        transitionMutex.withLock {
            while (true) {
                val desired = synchronized(desiredLock) {
                    desiredForeground
                }
                if (desired == appliedForeground) {
                    return@withLock
                }

                if (desired) {
                    enterForeground()
                } else {
                    leaveForeground()
                }
                appliedForeground = desired
            }
        }
    }
}
