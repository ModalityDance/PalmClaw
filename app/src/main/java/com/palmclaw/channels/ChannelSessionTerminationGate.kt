package com.palmclaw.channels

/**
 * Gives one websocket session exclusive ownership of its terminal transition.
 * Expected shutdown claims the gate without publishing a failure, so callbacks
 * arriving after cancellation cannot affect a newer session.
 */
internal class ChannelSessionTerminationGate {
    private val lock = Any()
    private var claimed = false

    fun claim(): Boolean = synchronized(lock) {
        if (claimed) return@synchronized false
        claimed = true
        true
    }

    fun isClaimed(): Boolean = synchronized(lock) { claimed }
}
