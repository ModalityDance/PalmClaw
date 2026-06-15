package com.palmclaw.workspace

import android.content.Context
import com.palmclaw.config.AppSession
import com.palmclaw.config.AppStoragePaths
import java.io.File
import java.security.MessageDigest
import java.util.Locale
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class SessionWorkspaceSnapshot(
    val version: Int,
    val sessionId: String,
    val sessionTitle: String,
    val workspaceRoot: String,
    val docsDir: String,
    val scratchDir: String,
    val artifactsDir: String,
    val createdAtMs: Long,
    val updatedAtMs: Long
)

data class SessionWorkspaceTrashHandle(
    val sessionId: String,
    val workspaceDir: File,
    val trashDir: File
)

class SessionWorkspaceManager(
    private val sharedWorkspaceRoot: File,
    private val sessionsRoot: File = File(sharedWorkspaceRoot, "sessions"),
    private val trashRoot: File = File(sessionsRoot, ".trash")
) {
    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
        prettyPrintIndent = "  "
    }

    constructor(context: Context) : this(
        sharedWorkspaceRoot = AppStoragePaths.sharedWorkspaceRoot(context),
        sessionsRoot = AppStoragePaths.sessionsRoot(context),
        trashRoot = AppStoragePaths.sessionWorkspaceTrashRoot(context)
    )

    init {
        ensureDirectory(sharedWorkspaceRoot)
        ensureDirectory(sessionsRoot)
        ensureDirectory(trashRoot)
    }

    fun sharedWorkspaceRoot(): File = sharedWorkspaceRoot.canonicalFile

    fun sessionsRoot(): File = sessionsRoot.canonicalFile

    fun trashRoot(): File = trashRoot.canonicalFile

    fun workspaceRootForSession(sessionId: String): File {
        val normalizedSessionId = normalizeSessionId(sessionId)
        return File(sessionsRoot(), stableDirectoryName(normalizedSessionId)).canonicalFile
    }

    fun getSnapshot(sessionId: String): SessionWorkspaceSnapshot? {
        val metadataFile = metadataFileForSession(sessionId)
        if (!metadataFile.exists() || !metadataFile.isFile) return null
        return runCatching {
            json.decodeFromString(SessionWorkspaceSnapshot.serializer(), metadataFile.readText(Charsets.UTF_8))
        }.getOrNull()
    }

    fun ensureWorkspace(
        sessionId: String,
        sessionTitle: String
    ): SessionWorkspaceSnapshot {
        val normalizedSessionId = normalizeSessionId(sessionId)
        val workspaceRoot = workspaceRootForSession(normalizedSessionId)
        val docsDir = File(workspaceRoot, DOCS_DIR_NAME).canonicalFile
        val scratchDir = File(workspaceRoot, SCRATCH_DIR_NAME).canonicalFile
        val artifactsDir = File(workspaceRoot, ARTIFACTS_DIR_NAME).canonicalFile
        ensureDirectory(workspaceRoot)
        ensureDirectory(docsDir)
        ensureDirectory(scratchDir)
        ensureDirectory(artifactsDir)

        val existing = getSnapshot(normalizedSessionId)
        val now = System.currentTimeMillis()
        val resolvedTitle = sessionTitle.trim().ifBlank {
            existing?.sessionTitle?.takeIf { it.isNotBlank() } ?: defaultSessionTitle(normalizedSessionId)
        }
        val snapshot = SessionWorkspaceSnapshot(
            version = WORKSPACE_VERSION,
            sessionId = normalizedSessionId,
            sessionTitle = resolvedTitle,
            workspaceRoot = workspaceRoot.absolutePath,
            docsDir = docsDir.absolutePath,
            scratchDir = scratchDir.absolutePath,
            artifactsDir = artifactsDir.absolutePath,
            createdAtMs = existing?.createdAtMs ?: now,
            updatedAtMs = when {
                existing == null -> now
                existing.sessionTitle != resolvedTitle -> now
                existing.workspaceRoot != workspaceRoot.absolutePath -> now
                existing.docsDir != docsDir.absolutePath -> now
                existing.scratchDir != scratchDir.absolutePath -> now
                existing.artifactsDir != artifactsDir.absolutePath -> now
                else -> existing.updatedAtMs
            }
        )
        if (existing != snapshot || !metadataFileForSession(normalizedSessionId).exists()) {
            writeSnapshot(snapshot)
        }
        return snapshot
    }

    fun renameWorkspace(
        sessionId: String,
        sessionTitle: String
    ): SessionWorkspaceSnapshot {
        val normalizedSessionId = normalizeSessionId(sessionId)
        val existing = ensureWorkspace(normalizedSessionId, sessionTitle)
        val updated = existing.copy(
            sessionTitle = sessionTitle.trim().ifBlank { defaultSessionTitle(normalizedSessionId) },
            updatedAtMs = System.currentTimeMillis()
        )
        writeSnapshot(updated)
        return updated
    }

    fun moveWorkspaceToTrash(sessionId: String): SessionWorkspaceTrashHandle? {
        val normalizedSessionId = normalizeSessionId(sessionId)
        val workspaceDir = workspaceRootForSession(normalizedSessionId)
        if (!workspaceDir.exists()) return null
        ensureDirectory(trashRoot())
        val trashDir = File(
            trashRoot(),
            "${workspaceDir.name}-${System.currentTimeMillis()}"
        ).canonicalFile
        if (!workspaceDir.renameTo(trashDir)) {
            throw IllegalStateException("Failed to move workspace to trash for session $normalizedSessionId")
        }
        return SessionWorkspaceTrashHandle(
            sessionId = normalizedSessionId,
            workspaceDir = workspaceDir,
            trashDir = trashDir
        )
    }

    fun restoreWorkspaceFromTrash(handle: SessionWorkspaceTrashHandle) {
        if (!handle.trashDir.exists()) return
        ensureDirectory(handle.workspaceDir.parentFile ?: sessionsRoot())
        if (handle.workspaceDir.exists()) {
            throw IllegalStateException("Cannot restore workspace because target already exists for session ${handle.sessionId}")
        }
        if (!handle.trashDir.renameTo(handle.workspaceDir)) {
            throw IllegalStateException("Failed to restore workspace for session ${handle.sessionId}")
        }
    }

    fun commitWorkspaceDeletion(handle: SessionWorkspaceTrashHandle) {
        if (!handle.trashDir.exists()) return
        if (!handle.trashDir.deleteRecursively() && handle.trashDir.exists()) {
            throw IllegalStateException("Failed to delete workspace trash for session ${handle.sessionId}")
        }
    }

    fun deleteWorkspace(sessionId: String) {
        val handle = moveWorkspaceToTrash(sessionId) ?: return
        commitWorkspaceDeletion(handle)
    }

    private fun metadataFileForSession(sessionId: String): File {
        return File(workspaceRootForSession(sessionId), METADATA_FILE_NAME).canonicalFile
    }

    private fun writeSnapshot(snapshot: SessionWorkspaceSnapshot) {
        val file = metadataFileForSession(snapshot.sessionId)
        ensureDirectory(file.parentFile ?: workspaceRootForSession(snapshot.sessionId))
        file.writeText(
            json.encodeToString(SessionWorkspaceSnapshot.serializer(), snapshot) + "\n",
            Charsets.UTF_8
        )
    }

    private fun stableDirectoryName(sessionId: String): String {
        if (sessionId == AppSession.LOCAL_SESSION_ID) {
            return AppSession.LOCAL_SESSION_ID
        }
        val slug = sessionId.trim()
            .lowercase(Locale.US)
            .replace(Regex("[^a-z0-9._-]+"), "_")
            .trim('_', '.', '-')
            .take(MAX_SLUG_LENGTH)
            .ifBlank { "session" }
        return "$slug-${shortHash(sessionId)}"
    }

    private fun shortHash(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }.take(HASH_LENGTH)
    }

    private fun normalizeSessionId(sessionId: String): String {
        return sessionId.trim().ifBlank { AppSession.LOCAL_SESSION_ID }
    }

    private fun defaultSessionTitle(sessionId: String): String {
        return if (sessionId == AppSession.LOCAL_SESSION_ID) {
            AppSession.LOCAL_SESSION_TITLE
        } else {
            sessionId
        }
    }

    private fun ensureDirectory(dir: File) {
        if (dir.exists()) {
            require(dir.isDirectory) { "Expected directory: ${dir.absolutePath}" }
            return
        }
        if (!dir.mkdirs() && !dir.isDirectory) {
            error("Failed to create directory: ${dir.absolutePath}")
        }
    }

    companion object {
        private const val WORKSPACE_VERSION = 1
        private const val DOCS_DIR_NAME = "docs"
        private const val SCRATCH_DIR_NAME = "scratch"
        private const val ARTIFACTS_DIR_NAME = "artifacts"
        private const val METADATA_FILE_NAME = "WORKSPACE.json"
        private const val MAX_SLUG_LENGTH = 32
        private const val HASH_LENGTH = 10
    }
}
