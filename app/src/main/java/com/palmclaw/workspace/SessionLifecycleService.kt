package com.palmclaw.workspace

import com.palmclaw.config.AppSession
import com.palmclaw.storage.SessionRepository

class SessionLifecycleService(
    private val sessionRepository: SessionRepository,
    private val workspaceManager: SessionWorkspaceManager,
    private val clearSessionChannelBinding: suspend (String) -> Unit = {},
    private val listCronJobIdsForSession: suspend (String) -> List<String> = { emptyList() },
    private val removeCronJob: suspend (String) -> Unit = {}
) {
    suspend fun ensureLocalSession() {
        sessionRepository.ensureSessionExists(AppSession.LOCAL_SESSION_ID, AppSession.LOCAL_SESSION_TITLE)
        sessionRepository.touch(AppSession.LOCAL_SESSION_ID)
        workspaceManager.ensureWorkspace(AppSession.LOCAL_SESSION_ID, AppSession.LOCAL_SESSION_TITLE)
    }

    suspend fun createSession(sessionId: String, sessionTitle: String) {
        val normalizedSessionId = normalizeManagedSessionId(sessionId)
        val normalizedSessionTitle = normalizeManagedSessionTitle(sessionTitle)
        sessionRepository.createSession(normalizedSessionId, normalizedSessionTitle)
        try {
            workspaceManager.ensureWorkspace(normalizedSessionId, normalizedSessionTitle)
            sessionRepository.touch(normalizedSessionId)
        } catch (t: Throwable) {
            runCatching { sessionRepository.deleteSession(normalizedSessionId) }
            throw t
        }
    }

    suspend fun renameSession(sessionId: String, sessionTitle: String) {
        val normalizedSessionId = normalizeManagedSessionId(sessionId)
        val normalizedSessionTitle = normalizeManagedSessionTitle(sessionTitle)
        val previousTitle = sessionRepository.getSession(normalizedSessionId)?.title
        sessionRepository.renameSession(normalizedSessionId, normalizedSessionTitle)
        try {
            workspaceManager.renameWorkspace(normalizedSessionId, normalizedSessionTitle)
            sessionRepository.touch(normalizedSessionId)
        } catch (t: Throwable) {
            if (!previousTitle.isNullOrBlank()) {
                runCatching { sessionRepository.renameSession(normalizedSessionId, previousTitle) }
            }
            throw t
        }
    }

    suspend fun deleteSession(sessionId: String) {
        val normalizedSessionId = normalizeManagedSessionId(sessionId)
        val trashHandle = workspaceManager.moveWorkspaceToTrash(normalizedSessionId)
        try {
            val cronJobIds = listCronJobIdsForSession(normalizedSessionId)
            clearSessionChannelBinding(normalizedSessionId)
            cronJobIds.forEach { removeCronJob(it) }
            sessionRepository.deleteSession(normalizedSessionId)
            if (trashHandle != null) {
                workspaceManager.commitWorkspaceDeletion(trashHandle)
            }
        } catch (t: Throwable) {
            if (trashHandle != null) {
                runCatching { workspaceManager.restoreWorkspaceFromTrash(trashHandle) }
            }
            throw t
        }
    }

    private fun normalizeManagedSessionId(sessionId: String): String {
        val normalized = sessionId.trim().ifBlank {
            throw IllegalArgumentException("sessionId is required")
        }
        require(normalized != AppSession.LOCAL_SESSION_ID) {
            "Local session must be managed through ensureLocalSession only"
        }
        return normalized
    }

    private fun normalizeManagedSessionTitle(sessionTitle: String): String {
        return sessionTitle.trim().ifBlank {
            throw IllegalArgumentException("session title is required")
        }
    }
}
