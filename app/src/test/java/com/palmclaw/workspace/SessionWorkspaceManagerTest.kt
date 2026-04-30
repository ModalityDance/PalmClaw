package com.palmclaw.workspace

import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SessionWorkspaceManagerTest {
    private lateinit var rootDir: File
    private lateinit var manager: SessionWorkspaceManager

    @Before
    fun setUp() {
        rootDir = Files.createTempDirectory("session-workspace-manager-test").toFile()
        manager = SessionWorkspaceManager(rootDir)
    }

    @After
    fun tearDown() {
        rootDir.deleteRecursively()
    }

    @Test
    fun `ensureWorkspace creates scaffold and metadata with stable directory name`() {
        val snapshot = manager.ensureWorkspace("session:alpha/beta", "Alpha Beta")

        val workspaceRoot = File(snapshot.workspaceRoot)
        assertTrue(workspaceRoot.isDirectory)
        assertTrue(File(snapshot.docsDir).isDirectory)
        assertTrue(File(snapshot.scratchDir).isDirectory)
        assertTrue(File(snapshot.artifactsDir).isDirectory)
        assertTrue(File(workspaceRoot, "WORKSPACE.json").isFile)
        assertFalse(workspaceRoot.name.contains(":"))
        assertFalse(workspaceRoot.name.contains("/"))
        assertTrue(workspaceRoot.parentFile!!.canonicalFile == manager.sessionsRoot())

        val loaded = manager.getSnapshot("session:alpha/beta")
        assertEquals(snapshot, loaded)
        assertEquals("Alpha Beta", loaded?.sessionTitle)
    }

    @Test
    fun `workspaceRootForSession is deterministic and hashes unsafe session ids`() {
        val first = manager.workspaceRootForSession("internal:subagent:abc")
        val second = manager.workspaceRootForSession("internal:subagent:abc")
        val other = manager.workspaceRootForSession("internal:subagent:def")

        assertEquals(first, second)
        assertNotEquals(first, other)
        assertFalse(first.name.contains(":"))
        assertTrue(first.name.contains("-"))
    }

    @Test
    fun `renameWorkspace updates metadata without moving directory`() {
        val original = manager.ensureWorkspace("session:rename", "Old Title")
        Thread.sleep(5)

        val renamed = manager.renameWorkspace("session:rename", "New Title")

        assertEquals(original.workspaceRoot, renamed.workspaceRoot)
        assertEquals("New Title", renamed.sessionTitle)
        assertTrue(renamed.updatedAtMs >= original.updatedAtMs)
    }

    @Test
    fun `moveWorkspaceToTrash and restoreWorkspaceFromTrash preserve workspace contents`() {
        val snapshot = manager.ensureWorkspace("session:trash", "Trash Test")
        val marker = File(snapshot.docsDir, "note.txt").apply { writeText("hello", Charsets.UTF_8) }

        val handle = manager.moveWorkspaceToTrash("session:trash")

        assertNotNull(handle)
        assertFalse(File(snapshot.workspaceRoot).exists())
        assertTrue(handle!!.trashDir.exists())

        manager.restoreWorkspaceFromTrash(handle)

        assertTrue(File(snapshot.workspaceRoot).exists())
        assertEquals("hello", marker.readText(Charsets.UTF_8))
    }

    @Test
    fun `deleteWorkspace removes workspace directory`() {
        val snapshot = manager.ensureWorkspace("session:delete", "Delete Test")

        manager.deleteWorkspace("session:delete")

        assertFalse(File(snapshot.workspaceRoot).exists())
        assertEquals(null, manager.getSnapshot("session:delete"))
    }
}
