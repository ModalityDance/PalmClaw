package com.palmclaw.workspace

import com.palmclaw.config.AppSession
import java.io.File
import java.util.Locale

class WorkspacePathResolver(
    private val currentSessionIdProvider: () -> String,
    private val workspaceManager: SessionWorkspaceManager,
    private val sharedExternalRoot: File? = null,
    private val hasSharedStorageAccess: () -> Boolean = { true }
) {
    private val ignoreCase = System.getProperty("os.name")
        .orEmpty()
        .lowercase(Locale.US)
        .contains("win")

    fun currentWorkspaceSnapshot(): SessionWorkspaceSnapshot {
        val sessionId = normalizeSessionId(currentSessionIdProvider())
        return workspaceManager.getSnapshot(sessionId)
            ?: workspaceManager.ensureWorkspace(sessionId, defaultSessionTitle(sessionId))
    }

    fun currentWorkspaceRoot(): File = File(currentWorkspaceSnapshot().workspaceRoot).canonicalFile

    fun sharedWorkspaceRoot(): File = workspaceManager.sharedWorkspaceRoot()

    fun resolveExisting(rawPath: String): File {
        val resolved = resolve(rawPath)
        if (!resolved.exists()) throw IllegalArgumentException("Path does not exist: $rawPath")
        return resolved
    }

    fun resolveForWrite(rawPath: String): File = resolve(rawPath)

    fun displayPath(file: File): String {
        val canonical = file.canonicalFile
        val currentRoot = currentWorkspaceRoot()
        val sessionsRoot = workspaceManager.sessionsRoot()
        return when {
            isUnderRoot(canonical, currentRoot) -> {
                val relative = currentRoot.toPath()
                    .relativize(canonical.toPath())
                    .toString()
                    .replace('\\', '/')
                relative.ifBlank { "." }
            }

            isUnderRoot(canonical, sessionsRoot) -> {
                "(another session workspace)"
            }

            isUnderRoot(canonical, sharedWorkspaceRoot()) -> {
                val relative = sharedWorkspaceRoot().toPath()
                    .relativize(canonical.toPath())
                    .toString()
                    .replace('\\', '/')
                if (relative.isBlank()) "shared://" else "shared://$relative"
            }

            sharedExternalRoot != null && isUnderRoot(canonical, sharedExternalRoot) -> {
                canonical.path.replace('\\', '/')
            }

            else -> canonical.path.replace('\\', '/')
        }
    }

    private fun resolve(rawPath: String): File {
        val input = rawPath.trim().ifBlank { "." }
        val currentRoot = currentWorkspaceRoot()
        val sharedRoot = sharedWorkspaceRoot()
        val sessionsRoot = workspaceManager.sessionsRoot()
        val candidate = when {
            input.startsWith(SESSION_SCHEME, ignoreCase = true) -> {
                File(currentRoot, stripScheme(input, SESSION_SCHEME)).canonicalFile
            }

            input.startsWith(SHARED_SCHEME, ignoreCase = true) -> {
                val relative = stripScheme(input, SHARED_SCHEME)
                val candidate = File(sharedRoot, relative).canonicalFile
                if (isUnderRoot(candidate, sessionsRoot) && !isUnderRoot(candidate, currentRoot)) {
                    throw SecurityException("Shared path cannot access another session workspace: $rawPath")
                }
                candidate
            }

            File(input).isAbsolute -> File(input).canonicalFile
            else -> File(currentRoot, input).canonicalFile
        }

        if (isUnderRoot(candidate, currentRoot)) return candidate
        if (isUnderRoot(candidate, sessionsRoot)) {
            throw SecurityException("Path points to another session workspace: $rawPath")
        }
        if (isUnderRoot(candidate, sharedRoot)) return candidate
        if (sharedExternalRoot != null && isUnderRoot(candidate, sharedExternalRoot)) {
            if (!hasSharedStorageAccess()) {
                throw SecurityException("All files access is required for shared storage path: $rawPath")
            }
            return candidate
        }
        throw SecurityException("Path escapes workspace isolation: $rawPath")
    }

    private fun stripScheme(input: String, scheme: String): String {
        return input.substring(scheme.length).trimStart('/', '\\')
    }

    private fun isUnderRoot(file: File, root: File): Boolean {
        val path = file.path
        val rootPath = root.path
        val rootPrefix = root.path + File.separator
        if (path.equals(rootPath, ignoreCase = ignoreCase)) return true
        return path.startsWith(rootPrefix, ignoreCase = ignoreCase)
    }

    private fun normalizeSessionId(raw: String): String {
        return raw.trim().ifBlank { AppSession.LOCAL_SESSION_ID }
    }

    private fun defaultSessionTitle(sessionId: String): String {
        return if (sessionId == AppSession.LOCAL_SESSION_ID) {
            AppSession.LOCAL_SESSION_TITLE
        } else {
            sessionId
        }
    }

    companion object {
        const val SESSION_SCHEME: String = "session://"
        const val SHARED_SCHEME: String = "shared://"
    }
}
