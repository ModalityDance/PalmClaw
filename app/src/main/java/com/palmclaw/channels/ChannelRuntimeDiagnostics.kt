package com.palmclaw.channels

import java.util.Locale

data class ChannelRuntimeSnapshot(
    val running: Boolean = false,
    val connected: Boolean = false,
    val ready: Boolean = false,
    val lastError: String = ""
)

object ChannelRuntimeDiagnostics {
    private val lock = Any()
    private var snapshots: Map<String, ChannelRuntimeSnapshot> = emptyMap()

    fun reset(channel: String, adapterKey: String) = synchronized(lock) {
        snapshots = snapshots + (key(channel, adapterKey) to ChannelRuntimeSnapshot())
    }

    fun markRunning(channel: String, adapterKey: String, running: Boolean) = synchronized(lock) {
        val current = snapshots[key(channel, adapterKey)] ?: ChannelRuntimeSnapshot()
        snapshots = snapshots + (
            key(channel, adapterKey) to current.copy(
                running = running,
                connected = if (running) current.connected else false,
                ready = if (running) current.ready else false
            )
        )
    }

    fun markConnected(channel: String, adapterKey: String, connected: Boolean) = synchronized(lock) {
        val current = snapshots[key(channel, adapterKey)] ?: ChannelRuntimeSnapshot()
        snapshots = snapshots + (
            key(channel, adapterKey) to current.copy(
                connected = connected,
                ready = if (connected) current.ready else false
            )
        )
    }

    fun markReady(channel: String, adapterKey: String) = synchronized(lock) {
        val current = snapshots[key(channel, adapterKey)] ?: ChannelRuntimeSnapshot()
        snapshots = snapshots + (
            key(channel, adapterKey) to current.copy(
                running = true,
                connected = true,
                ready = true,
                lastError = ""
            )
        )
    }

    fun markError(channel: String, adapterKey: String, message: String) = synchronized(lock) {
        val current = snapshots[key(channel, adapterKey)] ?: ChannelRuntimeSnapshot()
        snapshots = snapshots + (
            key(channel, adapterKey) to current.copy(
                lastError = message.take(300)
            )
        )
    }

    fun getSnapshot(channel: String, adapterKey: String): ChannelRuntimeSnapshot = synchronized(lock) {
        snapshots[key(channel, adapterKey)] ?: ChannelRuntimeSnapshot()
    }

    fun getSnapshots(channel: String): Map<String, ChannelRuntimeSnapshot> = synchronized(lock) {
        val prefix = "${channel.trim().lowercase(Locale.US)}|"
        snapshots
            .filterKeys { it.startsWith(prefix) }
            .mapKeys { it.key.removePrefix(prefix) }
    }

    private fun key(channel: String, adapterKey: String): String {
        return "${channel.trim().lowercase(Locale.US)}|${adapterKey.trim()}"
    }
}
