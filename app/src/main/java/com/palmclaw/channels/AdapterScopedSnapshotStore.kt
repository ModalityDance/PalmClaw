package com.palmclaw.channels

class AdapterScopedSnapshotStore<T>(
    private val emptySnapshot: () -> T
) {
    private val lock = Any()
    private var snapshots: Map<String, T> = emptyMap()

    fun reset(adapterKey: String) = synchronized(lock) {
        snapshots = snapshots + (normalizeKey(adapterKey) to emptySnapshot())
    }

    fun update(adapterKey: String, transform: (T) -> T) = synchronized(lock) {
        val key = normalizeKey(adapterKey)
        val current = snapshots[key] ?: emptySnapshot()
        snapshots = snapshots + (key to transform(current))
    }

    fun getSnapshot(adapterKey: String? = null): T = synchronized(lock) {
        val key = adapterKey?.trim().orEmpty()
        when {
            key.isNotBlank() -> snapshots[normalizeKey(key)] ?: emptySnapshot()
            snapshots.size == 1 -> snapshots.values.first()
            else -> emptySnapshot()
        }
    }

    fun getSnapshots(): Map<String, T> = synchronized(lock) { snapshots }

    private fun normalizeKey(adapterKey: String): String = adapterKey.trim()
}
