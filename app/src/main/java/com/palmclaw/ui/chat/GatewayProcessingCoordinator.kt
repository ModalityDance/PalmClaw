package com.palmclaw.ui

import com.palmclaw.config.AppSession
import java.util.LinkedHashSet

/**
 * Tracks gateway-driven processing state across local callbacks and observed runtime status.
 *
 * The coordinator keeps delayed gateway refresh requests separate from UI-facing "is generating"
 * state so [ChatViewModel] does not need to manage multiple synchronized collections directly.
 */
internal class GatewayProcessingCoordinator(
    private val localSessionId: String = AppSession.LOCAL_SESSION_ID
) {
    private val lock = Any()
    private val localProcessingSessions = linkedSetOf<String>()
    private var runtimeProcessingSessions: Set<String> = emptySet()
    private var alwaysOnProcessingSessions: Set<String> = emptySet()
    private var pendingGatewayRefresh = false

    fun updateRuntimeProcessingSessions(sessionIds: Collection<String>): UpdateResult =
        synchronized(lock) {
            runtimeProcessingSessions = normalizeSessionIds(sessionIds)
            buildUpdateResultLocked()
        }

    fun updateAlwaysOnProcessingSessions(sessionIds: Collection<String>): UpdateResult =
        synchronized(lock) {
            alwaysOnProcessingSessions = normalizeSessionIds(sessionIds)
            buildUpdateResultLocked()
        }

    fun updateLocalProcessingSession(sessionId: String, processing: Boolean): UpdateResult =
        synchronized(lock) {
            val sid = sessionId.trim()
            if (sid.isBlank()) return UpdateResult(currentProcessingSessionsLocked(), false)
            if (processing) {
                localProcessingSessions.add(sid)
            } else {
                localProcessingSessions.remove(sid)
            }
            buildUpdateResultLocked()
        }

    fun requestGatewayRefresh(): Boolean = synchronized(lock) {
        if (currentProcessingSessionsLocked().isEmpty()) {
            pendingGatewayRefresh = false
            true
        } else {
            pendingGatewayRefresh = true
            false
        }
    }

    fun isSessionProcessing(sessionId: String): Boolean = synchronized(lock) {
        val sid = sessionId.trim().ifBlank { localSessionId }
        currentProcessingSessionsLocked().contains(sid)
    }

    private fun buildUpdateResultLocked(): UpdateResult {
        val activeSessionIds = currentProcessingSessionsLocked()
        val shouldRefreshGateway = pendingGatewayRefresh && activeSessionIds.isEmpty()
        if (shouldRefreshGateway) {
            pendingGatewayRefresh = false
        }
        return UpdateResult(
            processingSessionIds = activeSessionIds,
            shouldRefreshGateway = shouldRefreshGateway
        )
    }

    private fun currentProcessingSessionsLocked(): Set<String> {
        val sessionIds = LinkedHashSet<String>()
        sessionIds.addAll(localProcessingSessions)
        sessionIds.addAll(runtimeProcessingSessions)
        sessionIds.addAll(alwaysOnProcessingSessions)
        return sessionIds
    }

    private fun normalizeSessionIds(sessionIds: Collection<String>): Set<String> {
        return sessionIds
            .asSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .toCollection(LinkedHashSet())
    }

    data class UpdateResult(
        val processingSessionIds: Set<String>,
        val shouldRefreshGateway: Boolean
    )
}
