package com.palmclaw.tools

import android.os.Handler
import android.os.Looper
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

interface AndroidUserActionRequester {
    fun requestPermissions(permissions: Array<String>, onResult: (grantedAll: Boolean) -> Unit)
    fun requestEnableBluetooth(onResult: (enabled: Boolean) -> Unit)
    fun openBluetoothSettings(onResult: (opened: Boolean) -> Unit)
    fun requestUserConfirmation(
        title: String,
        message: String,
        confirmLabel: String,
        cancelLabel: String,
        onResult: (confirmed: Boolean) -> Unit
    )
}

object AndroidUserActionBridge {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val requesterRef = AtomicReference<AndroidUserActionRequester?>(null)

    fun register(requester: AndroidUserActionRequester) {
        requesterRef.set(requester)
    }

    fun unregister(requester: AndroidUserActionRequester) {
        requesterRef.compareAndSet(requester, null)
    }

    suspend fun requestPermissions(
        permissions: List<String>,
        timeoutMs: Long = 60_000L
    ): Boolean? {
        val unique = permissions.distinct().filter { it.isNotBlank() }
        if (unique.isEmpty()) return true
        val requester = requesterRef.get() ?: return null
        return withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine { cont ->
                mainHandler.post {
                    if (!cont.isActive) return@post
                    requester.requestPermissions(unique.toTypedArray()) { grantedAll ->
                        if (cont.isActive) cont.resume(grantedAll)
                    }
                }
            }
        }
    }

    suspend fun requestEnableBluetooth(timeoutMs: Long = 60_000L): Boolean? {
        val requester = requesterRef.get() ?: return null
        return withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine { cont ->
                mainHandler.post {
                    if (!cont.isActive) return@post
                    requester.requestEnableBluetooth { enabled ->
                        if (cont.isActive) cont.resume(enabled)
                    }
                }
            }
        }
    }

    suspend fun openBluetoothSettings(timeoutMs: Long = 20_000L): Boolean? {
        val requester = requesterRef.get() ?: return null
        return withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine { cont ->
                mainHandler.post {
                    if (!cont.isActive) return@post
                    requester.openBluetoothSettings { opened ->
                        if (cont.isActive) cont.resume(opened)
                    }
                }
            }
        }
    }

    suspend fun requestUserConfirmation(
        title: String,
        message: String,
        confirmLabel: String = "Continue",
        cancelLabel: String = "Cancel",
        timeoutMs: Long = 120_000L
    ): Boolean? {
        val requester = requesterRef.get() ?: return null
        return withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine { cont ->
                mainHandler.post {
                    if (!cont.isActive) return@post
                    requester.requestUserConfirmation(
                        title = title,
                        message = message,
                        confirmLabel = confirmLabel,
                        cancelLabel = cancelLabel
                    ) { confirmed ->
                        if (cont.isActive) cont.resume(confirmed)
                    }
                }
            }
        }
    }
}
