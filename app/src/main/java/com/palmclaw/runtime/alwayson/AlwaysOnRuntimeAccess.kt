package com.palmclaw.runtime.alwayson

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Process bridge for Android lifecycle entry points that cannot receive
 * constructor-injected dependencies. The composition root must install the
 * coordinator before a service, receiver, or worker needs it.
 */
internal object AlwaysOnRuntimeAccess {
    private val lock = Any()
    private val processScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile
    private var control: AlwaysOnControl? = null

    fun install(control: AlwaysOnControl) {
        synchronized(lock) {
            this.control = control
        }
    }

    fun statusOrNull(): StateFlow<AlwaysOnStatus>? = control?.status

    suspend fun setEnabled(enabled: Boolean): Boolean {
        val current = control ?: return false
        current.setEnabled(enabled)
        return true
    }

    suspend fun reconcile(trigger: AlwaysOnTrigger): Boolean {
        val current = control ?: return false
        current.reconcile(trigger)
        return true
    }

    fun requestReconcile(
        trigger: AlwaysOnTrigger,
        onComplete: (Boolean) -> Unit = {}
    ) {
        processScope.launch {
            val completed = try {
                reconcile(trigger)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                false
            }
            onComplete(completed)
        }
    }

    fun requestSetEnabled(
        enabled: Boolean,
        onComplete: (Boolean) -> Unit = {}
    ) {
        processScope.launch {
            val completed = try {
                setEnabled(enabled)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                false
            }
            onComplete(completed)
        }
    }

    internal fun clearForTest() {
        synchronized(lock) {
            control = null
        }
    }
}
