package com.palmclaw.tools

import com.palmclaw.workspace.SessionWorkspaceManager
import com.palmclaw.workspace.WorkspacePathResolver
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assume.assumeNoException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FileToolsContractTest {

    @Test
    fun `file tool set exposes the nine focused tools`() {
        val fixture = createFixture()

        assertEquals(
            setOf("find", "grep", "read", "write", "edit", "mkdir", "copy", "move", "delete"),
            fixture.tools.mapTo(linkedSetOf()) { it.name }
        )
    }

    @Test
    fun `find inspects one file and searches directory entries`() = runBlocking {
        val fixture = createFixture()
        File(fixture.workspaceRoot, "src/main").mkdirs()
        File(fixture.workspaceRoot, "src/Root.kt").writeText("class Root")
        File(fixture.workspaceRoot, "src/main/App.kt").writeText("class App")
        File(fixture.workspaceRoot, "src/main/notes.txt").writeText("notes")

        val exact = fixture.tool("find").run("""{"path":"src/main/App.kt","max_depth":0}""")
        val matching = fixture.tool("find").run(
            """{"path":"src","pattern":"**/*.kt","max_depth":4,"kind":"file"}"""
        )

        assertFalse(exact.content, exact.isError)
        assertEquals("file", exact.contentObject()["base"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertFalse(matching.content, matching.isError)
        val entries = matching.contentObject()["entries"] as JsonArray
        assertEquals(
            listOf("src/Root.kt", "src/main/App.kt"),
            entries.map { it.jsonObject["path"]!!.jsonPrimitive.content }
        )
    }

    @Test
    fun `find reports but never follows a symbolic link`() = runBlocking {
        val fixture = createFixture()
        val outside = createTempDirectory("palmclaw-find-outside-")
        outside.resolve("outside.txt").toFile().writeText("outside")
        val link = File(fixture.workspaceRoot, "linked").toPath()
        try {
            Files.createSymbolicLink(link, outside)
        } catch (failure: Throwable) {
            assumeNoException("Symbolic links are unavailable on this test host", failure)
        }

        val result = fixture.tool("find").run("""{"path":".","max_depth":4}""")
        val exact = fixture.tool("find").run("""{"path":"linked","max_depth":0}""")

        assertFalse(result.content, result.isError)
        val entries = result.contentObject()["entries"] as JsonArray
        assertTrue(entries.any {
            it.jsonObject["path"]!!.jsonPrimitive.content == "linked" &&
                it.jsonObject["type"]!!.jsonPrimitive.content == "symlink"
        })
        assertFalse(entries.any { it.jsonObject["path"]!!.jsonPrimitive.content.contains("outside.txt") })
        assertFalse(exact.content, exact.isError)
        assertEquals("symlink", exact.contentObject()["base"]!!.jsonObject["type"]!!.jsonPrimitive.content)
    }

    @Test
    fun `mkdir is idempotent and copy preserves the source`() = runBlocking {
        val fixture = createFixture()
        val source = File(fixture.workspaceRoot, "source.txt").apply { writeText("content") }

        val firstMkdir = fixture.tool("mkdir").run("""{"path":"archive/nested"}""")
        val secondMkdir = fixture.tool("mkdir").run("""{"path":"archive/nested"}""")
        val copied = fixture.tool("copy").run(
            """{"source":"source.txt","destination":"archive/nested/copied.txt"}"""
        )

        assertFalse(firstMkdir.content, firstMkdir.isError)
        assertTrue(firstMkdir.contentObject()["created"]!!.jsonPrimitive.content.toBoolean())
        assertFalse(secondMkdir.content, secondMkdir.isError)
        assertFalse(secondMkdir.contentObject()["created"]!!.jsonPrimitive.content.toBoolean())
        assertFalse(copied.content, copied.isError)
        assertTrue(source.exists())
        assertEquals("content", File(fixture.workspaceRoot, "archive/nested/copied.txt").readText())
        assertTrue(copied.contentObject()["verified"]!!.jsonPrimitive.content.toBoolean())
    }

    @Test
    fun `copy does not partially replace an existing target after a failed copy`() = runBlocking {
        val fixture = createFixture(
            fileCopier = { _, destination ->
                destination.writeText("partial")
                throw IllegalStateException("simulated copy failure")
            }
        )
        File(fixture.workspaceRoot, "source.txt").writeText("new")
        val destination = File(fixture.workspaceRoot, "destination.txt").apply { writeText("old") }

        val result = fixture.tool("copy").run(
            """{"source":"source.txt","destination":"destination.txt","overwrite":true}"""
        )

        assertTrue(result.isError)
        assertEquals("old", destination.readText())
        assertEquals("false", result.contentObject()["partial"]!!.jsonPrimitive.content)
    }

    @Test
    fun `copy rejects a destination that appears while staging`() = runBlocking {
        lateinit var racedDestination: File
        val fixture = createFixture(
            fileCopier = { source, stage ->
                stage.writeBytes(source.readBytes())
                racedDestination.writeText("appeared")
            }
        )
        File(fixture.workspaceRoot, "source.txt").writeText("source")
        racedDestination = File(fixture.workspaceRoot, "destination.txt")

        val result = fixture.tool("copy").run(
            """{"source":"source.txt","destination":"destination.txt"}"""
        )

        assertTrue(result.isError)
        assertEquals("target_exists", result.contentObject()["code"]!!.jsonPrimitive.content)
        assertEquals("appeared", racedDestination.readText())
        assertEquals("source", File(fixture.workspaceRoot, "source.txt").readText())
    }

    @Test
    fun `copy refuses to replace a destination changed during staging`() = runBlocking {
        lateinit var racedDestination: File
        val fixture = createFixture(
            fileCopier = { source, stage ->
                stage.writeBytes(source.readBytes())
                racedDestination.writeText("changed while copying")
            }
        )
        File(fixture.workspaceRoot, "source.txt").writeText("source")
        racedDestination = File(fixture.workspaceRoot, "destination.txt").apply { writeText("old") }

        val result = fixture.tool("copy").run(
            """{"source":"source.txt","destination":"destination.txt","overwrite":true}"""
        )

        assertTrue(result.isError)
        assertEquals("file_changed", result.contentObject()["code"]!!.jsonPrimitive.content)
        assertEquals("changed while copying", racedDestination.readText())
        assertEquals("source", File(fixture.workspaceRoot, "source.txt").readText())
    }

    @Test
    fun `move refuses source and destination changes made during confirmation`() = runBlocking {
        lateinit var source: File
        lateinit var destination: File
        val fixture = createFixture(
            confirmationRequester = { _, _, _ ->
                source.writeText("changed source")
                destination.writeText("changed destination")
                true
            }
        )
        source = File(fixture.workspaceRoot, "source.txt").apply { writeText("source") }
        destination = File(fixture.workspaceRoot, "destination.txt").apply { writeText("old") }

        val result = fixture.tool("move").run(
            """{"source":"source.txt","destination":"destination.txt","overwrite":true}"""
        )

        assertTrue(result.isError)
        assertEquals("file_changed", result.contentObject()["code"]!!.jsonPrimitive.content)
        assertEquals("changed source", source.readText())
        assertEquals("changed destination", destination.readText())
    }

    @Test
    fun `directory copy is recursive only after explicit opt in`() = runBlocking {
        val fixture = createFixture()
        File(fixture.workspaceRoot, "source/nested").mkdirs()
        File(fixture.workspaceRoot, "source/nested/data.txt").writeText("data")

        val rejected = fixture.tool("copy").run(
            """{"source":"source","destination":"copy"}"""
        )
        val copied = fixture.tool("copy").run(
            """{"source":"source","destination":"copy","recursive":true}"""
        )

        assertTrue(rejected.isError)
        assertEquals("recursive_required", rejected.contentObject()["code"]!!.jsonPrimitive.content)
        assertFalse(copied.content, copied.isError)
        assertEquals("data", File(fixture.workspaceRoot, "copy/nested/data.txt").readText())
    }

    @Test
    fun `copy and move cannot replace protected workspace roots`() = runBlocking {
        val fixture = createFixture()
        File(fixture.workspaceRoot, "source.txt").writeText("data")

        val copy = fixture.tool("copy").run(
            """{"source":"source.txt","destination":".","overwrite":true}"""
        )
        val move = fixture.tool("move").run(
            """{"source":".","destination":"moved-workspace"}"""
        )

        assertTrue(copy.isError)
        assertEquals("protected_path", copy.contentObject()["code"]!!.jsonPrimitive.content)
        assertTrue(move.isError)
        assertEquals("protected_path", move.contentObject()["code"]!!.jsonPrimitive.content)
        assertEquals("data", File(fixture.workspaceRoot, "source.txt").readText())
    }

    @Test
    fun `write create and expected revision guards preserve newer bytes`() = runBlocking {
        val fixture = createFixture()
        val file = File(fixture.workspaceRoot, "notes.txt").apply { writeText("first") }
        val read = fixture.tool("read").run("""{"path":"notes.txt"}""")
        val revision = read.contentObject()["revision"]!!.jsonPrimitive.content

        val create = fixture.tool("write").run(
            """{"path":"notes.txt","text":"replacement","mode":"create"}"""
        )
        file.writeText("newer")
        val staleEdit = fixture.tool("edit").run(
            """{"path":"notes.txt","find":"first","replace":"changed","expected_revision":${jsonString(revision)}}"""
        )

        assertTrue(create.isError)
        assertEquals("target_exists", create.contentObject()["code"]!!.jsonPrimitive.content)
        assertTrue(staleEdit.isError)
        assertEquals("file_changed", staleEdit.contentObject()["code"]!!.jsonPrimitive.content)
        assertEquals("newer", file.readText())
    }

    @Test
    fun `read resumes a line after character truncation without skipping text`() = runBlocking {
        val fixture = createFixture()
        val firstLine = "a".repeat(300)
        File(fixture.workspaceRoot, "long.txt").writeText("$firstLine\nnext")

        val first = fixture.tool("read").run(
            """{"path":"long.txt","max_chars":128}"""
        )
        val firstBody = first.contentObject()
        val second = fixture.tool("read").run(
            """{"path":"long.txt","start_line":${firstBody["next_start_line"]!!.jsonPrimitive.content},"start_column":${firstBody["next_start_column"]!!.jsonPrimitive.content},"max_chars":500}"""
        )

        assertFalse(first.content, first.isError)
        assertEquals(128, firstBody["text"]!!.jsonPrimitive.content.length)
        assertEquals("1", firstBody["next_start_line"]!!.jsonPrimitive.content)
        assertEquals("129", firstBody["next_start_column"]!!.jsonPrimitive.content)
        assertFalse(second.content, second.isError)
        assertEquals("${"a".repeat(172)}\nnext", second.contentObject()["text"]!!.jsonPrimitive.content)
    }

    @Test
    fun `read keeps structured output within the default runtime result budget`() = runBlocking {
        val fixture = createFixture()
        File(fixture.workspaceRoot, "large-read.txt").writeText("\"\\".repeat(5_000))

        val result = fixture.tool("read").run("""{"path":"large-read.txt"}""")

        assertFalse(result.content, result.isError)
        assertTrue(result.contentObject()["truncated"]!!.jsonPrimitive.content.toBoolean())
        assertTrue(result.content.length < 5_000)
        assertEquals(1_800, result.contentObject()["text"]!!.jsonPrimitive.content.length)
    }

    @Test
    fun `read preserves empty lines across pagination`() = runBlocking {
        val fixture = createFixture()
        File(fixture.workspaceRoot, "empty-lines.txt").writeText("\nsecond")

        val result = fixture.tool("read").run(
            """{"path":"empty-lines.txt","max_chars":128}"""
        )

        assertFalse(result.content, result.isError)
        assertEquals("\nsecond", result.contentObject()["text"]!!.jsonPrimitive.content)
    }

    @Test
    fun `append rejects oversized input without reading it into memory`() = runBlocking {
        val fixture = createFixture()
        val file = File(fixture.workspaceRoot, "large.txt")
        RandomAccessFile(file, "rw").use { it.setLength(5_000_001L) }

        val result = fixture.tool("write").run(
            """{"path":"large.txt","text":"x","mode":"append","encoding":"UTF-8"}"""
        )

        assertTrue(result.isError)
        assertEquals("file_too_large", result.contentObject()["code"]!!.jsonPrimitive.content)
        assertEquals(5_000_001L, file.length())
    }

    @Test
    fun `recursive delete refuses a target changed during confirmation`() = runBlocking {
        var mutateDuringConfirmation: () -> Unit = {}
        val fixture = createFixture(
            confirmationRequester = { _, _, _ ->
                mutateDuringConfirmation()
                true
            }
        )
        val directory = File(fixture.workspaceRoot, "delete-me").apply { mkdirs() }
        File(directory, "original.txt").writeText("original")
        mutateDuringConfirmation = {
            File(directory, "appeared-later.txt").writeText("new")
        }

        val result = fixture.tool("delete").run(
            """{"path":"delete-me","recursive":true}"""
        )

        assertTrue(result.isError)
        assertEquals("file_changed", result.contentObject()["code"]!!.jsonPrimitive.content)
        assertTrue(File(directory, "original.txt").exists())
        assertTrue(File(directory, "appeared-later.txt").exists())
    }

    @Test
    fun `recursive delete performs zero writes when the depth limit is exceeded`() = runBlocking {
        val fixture = createFixture()
        val root = File(fixture.workspaceRoot, "too-deep").apply { mkdirs() }
        var current = root
        repeat(22) { depth ->
            current = File(current, "d$depth").apply { mkdir() }
        }
        File(current, "keep.txt").writeText("keep")

        val result = fixture.tool("delete").run(
            """{"path":"too-deep","recursive":true}"""
        )

        assertTrue(result.isError)
        assertEquals(
            "operation_limit_exceeded",
            result.contentObject()["code"]!!.jsonPrimitive.content
        )
        assertTrue(File(current, "keep.txt").exists())
        assertTrue(root.exists())
    }

    private fun createFixture(
        fileCopier: ((File, File) -> Unit)? = null,
        confirmationRequester: suspend (String, String, String) -> Boolean? = { _, _, _ -> true }
    ): Fixture {
        val sharedRoot = createTempDirectory("palmclaw-file-contract-").toFile()
        val sessionId = "session:file-contract"
        val manager = SessionWorkspaceManager(sharedRoot)
        val snapshot = manager.ensureWorkspace(sessionId, "File Contract")
        val resolver = WorkspacePathResolver(
            currentSessionIdProvider = { sessionId },
            workspaceManager = manager
        )
        return Fixture(
            workspaceRoot = File(snapshot.workspaceRoot),
            tools = createFileToolSet(
                pathResolver = resolver,
                confirmationRequester = confirmationRequester,
                fileCopier = fileCopier
            )
        )
    }

    private data class Fixture(
        val workspaceRoot: File,
        val tools: List<Tool>
    ) {
        fun tool(name: String): Tool = tools.first { it.name == name }
    }

    private fun ToolResult.contentObject(): JsonObject =
        Json.parseToJsonElement(content).jsonObject

    private fun jsonString(value: String): String =
        kotlinx.serialization.json.JsonPrimitive(value).toString()
}
