package com.palmclaw.workspace

import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class WorkspacePathResolverTest {
    private lateinit var sharedRoot: File
    private lateinit var externalRoot: File
    private lateinit var manager: SessionWorkspaceManager
    private val currentSessionId = "session:current"
    private val otherSessionId = "session:other"

    @Before
    fun setUp() {
        sharedRoot = Files.createTempDirectory("workspace-path-resolver-shared").toFile()
        externalRoot = Files.createTempDirectory("workspace-path-resolver-external").toFile()
        manager = SessionWorkspaceManager(sharedRoot)
        manager.ensureWorkspace(currentSessionId, "Current")
        manager.ensureWorkspace(otherSessionId, "Other")
    }

    @After
    fun tearDown() {
        sharedRoot.deleteRecursively()
        externalRoot.deleteRecursively()
    }

    @Test
    fun `relative and session scheme paths resolve inside current session workspace`() {
        val resolver = createResolver()
        val currentRoot = File(manager.getSnapshot(currentSessionId)!!.workspaceRoot)

        val relative = resolver.resolveForWrite("notes/todo.md")
        val explicitSession = resolver.resolveForWrite("session://docs/plan.md")

        assertEquals(File(currentRoot, "notes/todo.md").canonicalFile, relative)
        assertEquals(File(currentRoot, "docs/plan.md").canonicalFile, explicitSession)
    }

    @Test
    fun `shared scheme resolves inside shared root and displayPath keeps schemes readable`() {
        val resolver = createResolver()
        val sharedDoc = File(sharedRoot, "docs/HEARTBEAT.md").apply {
            parentFile!!.mkdirs()
            writeText("heartbeat", Charsets.UTF_8)
        }
        val currentFile = resolver.resolveForWrite("scratch/work.txt").apply {
            parentFile!!.mkdirs()
            writeText("work", Charsets.UTF_8)
        }

        val resolved = resolver.resolveExisting("shared://docs/HEARTBEAT.md")

        assertEquals(sharedDoc.canonicalFile, resolved)
        assertEquals("scratch/work.txt", resolver.displayPath(currentFile))
        assertEquals("shared://docs/HEARTBEAT.md", resolver.displayPath(sharedDoc))
    }

    @Test(expected = SecurityException::class)
    fun `absolute path into another session workspace is rejected`() {
        val resolver = createResolver()
        val otherFile = File(manager.getSnapshot(otherSessionId)!!.workspaceRoot, "secret.txt").apply {
            writeText("secret", Charsets.UTF_8)
        }

        resolver.resolveExisting(otherFile.absolutePath)
    }

    @Test(expected = SecurityException::class)
    fun `shared scheme cannot reach another session workspace`() {
        val resolver = createResolver()
        val otherRoot = File(manager.getSnapshot(otherSessionId)!!.workspaceRoot)
        File(otherRoot, "secret.txt").writeText("secret", Charsets.UTF_8)

        resolver.resolveExisting("shared://sessions/${otherRoot.name}/secret.txt")
    }

    @Test(expected = SecurityException::class)
    fun `path traversal escaping current workspace is rejected`() {
        val resolver = createResolver()

        resolver.resolveForWrite("../outside.txt")
    }

    @Test
    fun `external shared storage path requires explicit access grant`() {
        val externalFile = File(externalRoot, "shared.txt").apply {
            writeText("shared", Charsets.UTF_8)
        }
        val allowed = createResolver(hasSharedStorageAccess = { true })
        val denied = createResolver(hasSharedStorageAccess = { false })

        assertEquals(externalFile.canonicalFile, allowed.resolveExisting(externalFile.absolutePath))
        assertTrue(
            runCatching { denied.resolveExisting(externalFile.absolutePath) }
                .exceptionOrNull() is SecurityException
        )
    }

    @Test
    fun `displayPath hides another session workspace location`() {
        val resolver = createResolver()
        val otherFile = File(manager.getSnapshot(otherSessionId)!!.workspaceRoot, "secret.txt").apply {
            writeText("secret", Charsets.UTF_8)
        }

        assertEquals("(another session workspace)", resolver.displayPath(otherFile))
    }

    private fun createResolver(
        hasSharedStorageAccess: () -> Boolean = { true }
    ): WorkspacePathResolver {
        return WorkspacePathResolver(
            currentSessionIdProvider = { currentSessionId },
            workspaceManager = manager,
            sharedExternalRoot = externalRoot,
            hasSharedStorageAccess = hasSharedStorageAccess
        )
    }
}
