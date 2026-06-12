package com.palmclaw.runtime

import com.palmclaw.config.AppSession
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock

/**
 * Serializes agent turns per session while allowing bounded cross-session concurrency.
 */
internal class SessionTurnCoordinator(
    maxConcurrentTurns: Int = DEFAULT_MAX_CONCURRENT_TURNS
) {
    private val globalLimit = Semaphore(maxConcurrentTurns.coerceAtLeast(1))
    private val entriesLock = Mutex()
    private val entries = mutableMapOf<String, SessionEntry>()

    suspend fun <T> withSessionTurn(sessionId: String, block: suspend () -> T): T {
        val key = normalizeSessionId(sessionId)
        val entry = acquireEntry(key)
        try {
            return entry.mutex.withLock {
                globalLimit.acquire()
                try {
                    block()
                } finally {
                    globalLimit.release()
                }
            }
        } finally {
            releaseEntry(key, entry)
        }
    }

    private suspend fun acquireEntry(sessionId: String): SessionEntry {
        return entriesLock.withLock {
            entries.getOrPut(sessionId) { SessionEntry() }
                .also { it.references += 1 }
        }
    }

    private suspend fun releaseEntry(sessionId: String, entry: SessionEntry) {
        entriesLock.withLock {
            entry.references -= 1
            if (entry.references <= 0) {
                entries.remove(sessionId)
            }
        }
    }

    private fun normalizeSessionId(sessionId: String): String {
        return sessionId.trim().ifBlank { AppSession.LOCAL_SESSION_ID }
    }

    private class SessionEntry {
        val mutex = Mutex()
        var references: Int = 0
    }

    companion object {
        const val DEFAULT_MAX_CONCURRENT_TURNS = 2
    }
}
