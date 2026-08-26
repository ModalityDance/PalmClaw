package com.palmclaw.runtime.alwayson

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.PowerManager

/**
 * Holds a bounded partial wake lease only while an agent turn is active.
 * Each lease has a system timeout and is renewed before expiry while work
 * remains active.
 */
internal class AndroidProcessingPowerLease(
    context: Context,
    private val leaseDurationMillis: Long = DEFAULT_LEASE_DURATION_MILLIS,
    private val renewalIntervalMillis: Long = DEFAULT_RENEWAL_INTERVAL_MILLIS
) : AutoCloseable {
    private val handler = Handler(Looper.getMainLooper())
    private val wakeLock = (
        context.applicationContext.getSystemService(Context.POWER_SERVICE) as? PowerManager
        )?.newWakeLock(
        PowerManager.PARTIAL_WAKE_LOCK,
        "${context.packageName}:agent_processing"
    )?.apply {
        setReferenceCounted(false)
    }

    private var processing = false
    private val renewal = object : Runnable {
        override fun run() {
            synchronized(this@AndroidProcessingPowerLease) {
                if (!processing) {
                    return
                }
                renewLocked()
                handler.postDelayed(this, renewalIntervalMillis)
            }
        }
    }

    @Synchronized
    fun update(processing: Boolean) {
        if (this.processing == processing) {
            return
        }
        this.processing = processing
        handler.removeCallbacks(renewal)
        if (processing) {
            renewLocked()
            handler.postDelayed(renewal, renewalIntervalMillis)
        } else {
            releaseLocked()
        }
    }

    @Synchronized
    override fun close() {
        processing = false
        handler.removeCallbacks(renewal)
        releaseLocked()
    }

    private fun renewLocked() {
        releaseLocked()
        runCatching {
            wakeLock?.acquire(leaseDurationMillis)
        }
    }

    private fun releaseLocked() {
        val lock = wakeLock ?: return
        if (lock.isHeld) {
            runCatching { lock.release() }
        }
    }

    private companion object {
        const val DEFAULT_LEASE_DURATION_MILLIS = 2 * 60_000L
        const val DEFAULT_RENEWAL_INTERVAL_MILLIS = 60_000L
    }
}
