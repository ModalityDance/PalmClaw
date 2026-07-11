package com.palmclaw.tools

import com.palmclaw.workspace.SessionWorkspaceManager
import com.palmclaw.workspace.WorkspacePathResolver
import java.io.File
import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assume.assumeNoException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FileToolsTest {

    @Test
    fun `delete removes a file inside the current workspace`() = runBlocking {
        val fixture = createFixture()
        val target = File(fixture.workspaceRoot, "notes.txt").apply { writeText("temporary") }
        val tool = fixture.tools.firstOrNull { it.name == "delete" }

        assertNotNull("delete tool must be registered", tool)
        val result = tool!!.run("""{"path":"notes.txt"}""")

        assertFalse(result.content, result.isError)
        assertFalse(target.exists())
        assertEquals("delete", (result.metadata?.get("action") as? JsonPrimitive)?.content)
    }

    @Test
    fun `delete requires recursive opt in for a non empty directory`() = runBlocking {
        val fixture = createFixture()
        val directory = File(fixture.workspaceRoot, "drafts").apply { mkdirs() }
        File(directory, "keep.txt").writeText("keep")
        val tool = fixture.tools.first { it.name == "delete" }

        val result = tool.run("""{"path":"drafts"}""")

        assertTrue(result.isError)
        assertTrue(directory.exists())
        assertEquals("directory_not_empty", (result.metadata?.get("error") as? JsonPrimitive)?.content)
    }

    @Test
    fun `delete recursively removes a directory after explicit opt in`() = runBlocking {
        val fixture = createFixture()
        val directory = File(fixture.workspaceRoot, "drafts").apply { mkdirs() }
        File(directory, "remove.txt").writeText("remove")
        val tool = fixture.tools.first { it.name == "delete" }

        val result = tool.run("""{"path":"drafts","recursive":true}""")

        assertFalse(result.content, result.isError)
        assertFalse(directory.exists())
    }

    @Test
    fun `delete rejects the current workspace root`() = runBlocking {
        val fixture = createFixture()
        val tool = fixture.tools.first { it.name == "delete" }

        val result = tool.run("""{"path":"."}""")

        assertTrue(result.isError)
        assertTrue(fixture.workspaceRoot.exists())
        assertEquals("protected_path", (result.metadata?.get("error") as? JsonPrimitive)?.content)
    }

    @Test
    fun `move relocates and renames a file inside the current workspace`() = runBlocking {
        val fixture = createFixture()
        val source = File(fixture.workspaceRoot, "draft.txt").apply { writeText("content") }
        val destination = File(fixture.workspaceRoot, "archive/final.txt")
        val tool = fixture.tools.firstOrNull { it.name == "move" }

        assertNotNull("move tool must be registered", tool)
        val result = tool!!.run(
            """{"source":"draft.txt","destination":"archive/final.txt","create_parent":true}"""
        )

        assertFalse(result.content, result.isError)
        assertFalse(source.exists())
        assertEquals("content", destination.readText())
        assertEquals("move", (result.metadata?.get("action") as? JsonPrimitive)?.content)
    }

    @Test
    fun `move does not overwrite an existing target by default`() = runBlocking {
        val fixture = createFixture()
        val source = File(fixture.workspaceRoot, "source.txt").apply { writeText("source") }
        val destination = File(fixture.workspaceRoot, "destination.txt").apply { writeText("destination") }
        val tool = fixture.tools.first { it.name == "move" }

        val result = tool.run("""{"source":"source.txt","destination":"destination.txt"}""")

        assertTrue(result.isError)
        assertEquals("source", source.readText())
        assertEquals("destination", destination.readText())
        assertEquals("target_exists", (result.metadata?.get("error") as? JsonPrimitive)?.content)
    }

    @Test
    fun `move overwrites an existing file only after explicit opt in`() = runBlocking {
        val fixture = createFixture()
        val source = File(fixture.workspaceRoot, "source.txt").apply { writeText("source") }
        val destination = File(fixture.workspaceRoot, "destination.txt").apply { writeText("destination") }
        val tool = fixture.tools.first { it.name == "move" }

        val result = tool.run(
            """{"source":"source.txt","destination":"destination.txt","overwrite":true}"""
        )

        assertFalse(result.content, result.isError)
        assertFalse(source.exists())
        assertEquals("source", destination.readText())
    }

    @Test
    fun `move rejects placing a directory inside itself`() = runBlocking {
        val fixture = createFixture()
        val source = File(fixture.workspaceRoot, "source").apply { mkdirs() }
        File(source, "keep.txt").writeText("keep")
        val tool = fixture.tools.first { it.name == "move" }

        val result = tool.run(
            """{"source":"source","destination":"source/nested","create_parent":true}"""
        )

        assertTrue(result.isError)
        assertTrue(source.exists())
        assertEquals("destination_inside_source", (result.metadata?.get("error") as? JsonPrimitive)?.content)
    }

    @Test
    fun `recursive delete requires user confirmation`() = runBlocking {
        val fixture = createFixture(confirmationRequester = { _, _, _ -> false })
        val directory = File(fixture.workspaceRoot, "drafts").apply { mkdirs() }
        File(directory, "keep.txt").writeText("keep")
        val tool = fixture.tools.first { it.name == "delete" }

        val result = tool.run("""{"path":"drafts","recursive":true}""")

        assertTrue(result.isError)
        assertTrue(directory.exists())
        assertEquals("user_cancelled", (result.metadata?.get("error") as? JsonPrimitive)?.content)
    }

    @Test
    fun `move overwrite requires user confirmation`() = runBlocking {
        val fixture = createFixture(confirmationRequester = { _, _, _ -> false })
        val source = File(fixture.workspaceRoot, "source.txt").apply { writeText("source") }
        val destination = File(fixture.workspaceRoot, "destination.txt").apply { writeText("destination") }
        val tool = fixture.tools.first { it.name == "move" }

        val result = tool.run(
            """{"source":"source.txt","destination":"destination.txt","overwrite":true}"""
        )

        assertTrue(result.isError)
        assertEquals("source", source.readText())
        assertEquals("destination", destination.readText())
        assertEquals("user_cancelled", (result.metadata?.get("error") as? JsonPrimitive)?.content)
    }

    @Test
    fun `delete rejects the shared workspace root`() = runBlocking {
        val fixture = createFixture()
        val tool = fixture.tools.first { it.name == "delete" }

        val result = tool.run("""{"path":"shared://","recursive":true}""")

        assertTrue(result.isError)
        assertEquals("protected_path", (result.metadata?.get("error") as? JsonPrimitive)?.content)
    }

    @Test
    fun `delete rejects paths that escape the current workspace`() = runBlocking {
        val fixture = createFixture()
        val tool = fixture.tools.first { it.name == "delete" }

        val result = tool.run("""{"path":"../outside.txt"}""")

        assertTrue(result.isError)
        assertEquals("path_outside_workspace", (result.metadata?.get("error") as? JsonPrimitive)?.content)
    }

    @Test
    fun `recursive delete rejects a symbolic link before removing any content`() = runBlocking {
        val fixture = createFixture()
        val outside = createTempDirectory("palmclaw-file-tools-outside-").toFile()
        val outsideFile = File(outside, "keep.txt").apply { writeText("keep") }
        val directory = File(fixture.workspaceRoot, "drafts").apply { mkdirs() }
        File(directory, "local.txt").writeText("local")
        try {
            Files.createSymbolicLink(File(directory, "outside-link").toPath(), outside.toPath())
        } catch (failure: Throwable) {
            assumeNoException("Symbolic links are unavailable on this test host", failure)
        }
        val tool = fixture.tools.first { it.name == "delete" }

        val result = tool.run("""{"path":"drafts","recursive":true}""")

        assertTrue(result.isError)
        assertTrue(directory.exists())
        assertTrue(File(directory, "local.txt").exists())
        assertTrue(outsideFile.exists())
        assertEquals("symbolic_link_not_allowed", (result.metadata?.get("error") as? JsonPrimitive)?.content)
    }

    @Test
    fun `delete rejects the configured external storage root`() = runBlocking {
        val externalRoot = createTempDirectory("palmclaw-external-root-").toFile()
        val fixture = createFixture(sharedExternalRoot = externalRoot)
        val tool = fixture.tools.first { it.name == "delete" }

        val result = tool.run(
            """{"path":${jsonString(externalRoot.absolutePath)},"recursive":true}"""
        )

        assertTrue(result.isError)
        assertTrue(externalRoot.exists())
        assertEquals("protected_path", (result.metadata?.get("error") as? JsonPrimitive)?.content)
    }

    @Test
    fun `delete requires confirmation before modifying external storage`() = runBlocking {
        val externalRoot = createTempDirectory("palmclaw-external-root-").toFile()
        val externalFile = File(externalRoot, "keep.txt").apply { writeText("keep") }
        val fixture = createFixture(
            sharedExternalRoot = externalRoot,
            confirmationRequester = { _, _, _ -> false }
        )
        val tool = fixture.tools.first { it.name == "delete" }

        val result = tool.run("""{"path":${jsonString(externalFile.absolutePath)}}""")

        assertTrue(result.isError)
        assertTrue(externalFile.exists())
        assertEquals("user_cancelled", (result.metadata?.get("error") as? JsonPrimitive)?.content)
    }

    @Test
    fun `move falls back to verified copy when direct rename fails`() = runBlocking {
        val fixture = createFixture(
            fileRenamer = { source, destination ->
                if (source.name == "source.txt") false else source.renameTo(destination)
            }
        )
        val source = File(fixture.workspaceRoot, "source.txt").apply { writeText("copied content") }
        val destination = File(fixture.workspaceRoot, "destination.txt")
        val tool = fixture.tools.first { it.name == "move" }

        val result = tool.run("""{"source":"source.txt","destination":"destination.txt"}""")

        assertFalse(result.content, result.isError)
        assertFalse(source.exists())
        assertEquals("copied content", destination.readText())
    }

    @Test
    fun `move copy fallback replaces target without leaving a backup`() = runBlocking {
        val fixture = createFixture(
            fileRenamer = { source, destination ->
                if (source.name == "source.txt") false else source.renameTo(destination)
            }
        )
        val source = File(fixture.workspaceRoot, "source.txt").apply { writeText("new content") }
        val destination = File(fixture.workspaceRoot, "destination.txt").apply { writeText("old content") }
        val tool = fixture.tools.first { it.name == "move" }

        val result = tool.run(
            """{"source":"source.txt","destination":"destination.txt","overwrite":true}"""
        )

        assertFalse(result.content, result.isError)
        assertFalse(source.exists())
        assertEquals("new content", destination.readText())
        assertTrue(
            fixture.workspaceRoot.listFiles().orEmpty().none { it.name.contains(".palmclaw-move-") }
        )
    }

    @Test
    fun `move restores existing target after copy fallback fails`() = runBlocking {
        val fixture = createFixture(
            fileRenamer = { source, destination ->
                if (source.name == "source.txt") false else source.renameTo(destination)
            },
            fileCopier = { _, destination ->
                destination.writeText("partial")
                throw IllegalStateException("simulated copy failure")
            }
        )
        val source = File(fixture.workspaceRoot, "source.txt").apply { writeText("new content") }
        val destination = File(fixture.workspaceRoot, "destination.txt").apply { writeText("old content") }
        val tool = fixture.tools.first { it.name == "move" }

        val result = tool.run(
            """{"source":"source.txt","destination":"destination.txt","overwrite":true}"""
        )

        assertTrue(result.isError)
        assertEquals("new content", source.readText())
        assertEquals("old content", destination.readText())
        assertTrue(
            fixture.workspaceRoot.listFiles().orEmpty().none { it.name.contains(".palmclaw-move-") }
        )
    }

    @Test
    fun `move reports surviving backup when automatic restoration fails`() = runBlocking {
        val fixture = createFixture(
            fileRenamer = { source, destination ->
                when {
                    source.name == "source-dir" -> false
                    source.name.contains(".palmclaw-move-") -> false
                    else -> source.renameTo(destination)
                }
            }
        )
        val source = File(fixture.workspaceRoot, "source-dir").apply { mkdirs() }
        File(source, "new.txt").writeText("new")
        val destination = File(fixture.workspaceRoot, "destination.txt").apply { writeText("old") }
        val tool = fixture.tools.first { it.name == "move" }

        val result = tool.run(
            """{"source":"source-dir","destination":"destination.txt","overwrite":true}"""
        )

        assertTrue(result.isError)
        assertEquals("move_recovery_required", (result.metadata?.get("error") as? JsonPrimitive)?.content)
        assertTrue(source.exists())
        assertFalse(destination.exists())
        val backupPath = (result.metadata?.get("backup_path") as? JsonPrimitive)?.content
        assertNotNull("surviving backup path must be reported", backupPath)
        assertEquals("old", File(fixture.workspaceRoot, backupPath!!).readText())
    }

    private fun createFixture(
        sharedExternalRoot: File? = null,
        confirmationRequester: suspend (String, String, String) -> Boolean? = { _, _, _ -> true },
        fileRenamer: (File, File) -> Boolean = { source, destination -> source.renameTo(destination) },
        fileCopier: (File, File) -> Unit = { source, destination -> source.copyTo(destination) }
    ): Fixture {
        val sharedRoot = createTempDirectory("palmclaw-file-tools-").toFile()
        val sessionId = "session:file-tools"
        val manager = SessionWorkspaceManager(sharedRoot)
        val snapshot = manager.ensureWorkspace(sessionId, "File Tools")
        val resolver = WorkspacePathResolver(
            currentSessionIdProvider = { sessionId },
            workspaceManager = manager,
            sharedExternalRoot = sharedExternalRoot
        )
        return Fixture(
            workspaceRoot = File(snapshot.workspaceRoot),
            tools = createFileToolSet(resolver, confirmationRequester, fileRenamer, fileCopier)
        )
    }

    private data class Fixture(
        val workspaceRoot: File,
        val tools: List<Tool>
    )

    private fun jsonString(value: String): String {
        return JsonPrimitive(value).toString()
    }
}
