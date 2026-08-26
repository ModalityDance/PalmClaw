package com.palmclaw.channels

import java.util.concurrent.atomic.AtomicInteger

internal class WeComHeartbeatTracker {
    private val pending = AtomicInteger(0)

    fun reset() {
        pending.set(0)
    }

    fun recordSent(): Int = pending.incrementAndGet()

    fun acknowledge(): Int {
        while (true) {
            val current = pending.get()
            if (current == 0) return 0
            if (pending.compareAndSet(current, current - 1)) return current - 1
        }
    }

    fun pendingCount(): Int = pending.get()

    fun hasTimedOut(maxPending: Int): Boolean {
        require(maxPending >= 0) { "maxPending must not be negative" }
        return pending.get() > maxPending
    }
}
