package com.palmclaw.workspace

class SessionUiLifecycleService(
    private val createSessionAction: suspend (String, String) -> Unit,
    private val renameSessionAction: suspend (String, String) -> Unit,
    private val deleteSessionAction: suspend (String) -> Unit,
    private val ensureLocalSessionAction: suspend () -> Unit,
    private val refreshGatewayRuntimeConfig: () -> Unit,
    private val sessionIdGenerator: () -> String = { "session:${System.currentTimeMillis()}" }
) {
    constructor(
        sessionLifecycleService: SessionLifecycleService,
        refreshGatewayRuntimeConfig: () -> Unit,
        sessionIdGenerator: () -> String = { "session:${System.currentTimeMillis()}" }
    ) : this(
        createSessionAction = sessionLifecycleService::createSession,
        renameSessionAction = sessionLifecycleService::renameSession,
        deleteSessionAction = sessionLifecycleService::deleteSession,
        ensureLocalSessionAction = sessionLifecycleService::ensureLocalSession,
        refreshGatewayRuntimeConfig = refreshGatewayRuntimeConfig,
        sessionIdGenerator = sessionIdGenerator
    )

    suspend fun ensureLocalSessionExists() {
        ensureLocalSessionAction()
    }

    suspend fun createSession(sessionTitle: String): String {
        val normalizedTitle = normalizeTitle(sessionTitle)
        val sessionId = normalizeSessionId(sessionIdGenerator())
        createSessionAction(sessionId, normalizedTitle)
        return sessionId
    }

    suspend fun renameSession(sessionId: String, sessionTitle: String) {
        renameSessionAction(normalizeSessionId(sessionId), normalizeTitle(sessionTitle))
    }

    suspend fun deleteSession(sessionId: String) {
        deleteSessionAction(normalizeSessionId(sessionId))
        refreshGatewayRuntimeConfig()
    }

    private fun normalizeSessionId(sessionId: String): String {
        return sessionId.trim().ifBlank {
            throw IllegalArgumentException("sessionId is required")
        }
    }

    private fun normalizeTitle(sessionTitle: String): String {
        return sessionTitle.trim().ifBlank {
            throw IllegalArgumentException("session title is required")
        }
    }
}
