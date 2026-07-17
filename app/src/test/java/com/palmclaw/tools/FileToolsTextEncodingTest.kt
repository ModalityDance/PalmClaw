package com.palmclaw.tools

import com.palmclaw.workspace.SessionWorkspaceManager
import com.palmclaw.workspace.WorkspacePathResolver
import java.io.File
import java.nio.charset.Charset
import kotlin.io.path.createTempDirectory
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FileToolsTextEncodingTest {

    @Test
    fun `utf8 text round trips through all file actions`() = runBlocking {
        val fixture = createFixture()
        val write = fixture.tool("write")
        val read = fixture.tool("read")
        val edit = fixture.tool("edit")
        val grep = fixture.tool("grep")
        val file = File(fixture.workspaceRoot, "roundtrip.txt")

        val initial = "简体中文\r\n繁體中文\r\n日本語 🐾"
        assertFalse(write.run(json("roundtrip.txt", initial, mode = "overwrite")).isError)
        val readResult = read.run("""{"path":"roundtrip.txt"}""")
        assertFalse(readResult.content, readResult.isError)
        assertEquals("UTF-8", metadata(readResult, "charset"))
        assertEquals("utf8", metadata(readResult, "encoding_source"))
        assertEquals("简体中文\n繁體中文\n日本語 🐾", readResult.content)

        assertFalse(write.run(json("roundtrip.txt", "\r\nappend café", mode = "append")).isError)
        val editResult = edit.run(
            """{"path":"roundtrip.txt","find":"繁體中文","replace":"繁體內容"}"""
        )
        assertFalse(editResult.content, editResult.isError)
        assertEquals("UTF-8", metadata(editResult, "charset"))

        val grepResult = grep.run("""{"path":"roundtrip.txt","query":"日本語"}""")
        assertFalse(grepResult.content, grepResult.isError)
        assertTrue(grepResult.content.contains("roundtrip.txt:3"))
        assertEquals("UTF-8", metadata(grepResult, "charsets"))
        assertEquals("utf8", metadata(grepResult, "encoding_source"))
        assertEquals(
            "简体中文\r\n繁體內容\r\n日本語 🐾\r\nappend café",
            file.readBytes().toString(Charsets.UTF_8)
        )
    }

    @Test
    fun `append preserves an explicitly selected legacy encoding`() = runBlocking {
        val fixture = createFixture()
        val big5 = Charset.forName("Big5")
        val file = File(fixture.workspaceRoot, "notes.txt")
        file.writeBytes("繁體中文".toByteArray(big5))
        val write = fixture.tool("write")

        val result = write.run(
            """{"path":"notes.txt","text":"內容","mode":"append","encoding":"Big5"}"""
        )

        assertFalse(result.content, result.isError)
        assertEquals("Big5", (result.metadata?.get("charset") as? JsonPrimitive)?.content)
        assertEquals("繁體中文內容", file.readBytes().toString(big5))
    }

    @Test
    fun `append and edit preserve a single utf16 bom`() = runBlocking {
        val fixture = createFixture()
        val file = File(fixture.workspaceRoot, "utf16.txt")
        val bom = byteArrayOf(0xFF.toByte(), 0xFE.toByte())
        file.writeBytes(bom + "Hello".toByteArray(Charsets.UTF_16LE))

        val appendResult = fixture.tool("write").run(
            """{"path":"utf16.txt","text":" 世界","mode":"append"}"""
        )
        assertFalse(appendResult.content, appendResult.isError)
        assertEquals("UTF-16LE", metadata(appendResult, "charset"))

        val editResult = fixture.tool("edit").run(
            """{"path":"utf16.txt","find":"Hello","replace":"PalmClaw"}"""
        )
        assertFalse(editResult.content, editResult.isError)
        val bytes = file.readBytes()
        assertTrue(bytes.copyOfRange(0, bom.size).contentEquals(bom))
        assertFalse(bytes.copyOfRange(bom.size, bytes.size).startsWith(bom))
        assertEquals("PalmClaw 世界", bytes.copyOfRange(bom.size, bytes.size).toString(Charsets.UTF_16LE))
    }

    @Test
    fun `edit and grep preserve an explicitly selected legacy encoding`() = runBlocking {
        val fixture = createFixture()
        val big5 = Charset.forName("Big5")
        val file = File(fixture.workspaceRoot, "legacy.txt")
        file.writeBytes("第一行\r\n繁體中文\r\n第三行".toByteArray(big5))

        val editResult = fixture.tool("edit").run(
            """{"path":"legacy.txt","find":"繁體中文","replace":"繁體內容","encoding":"Big5"}"""
        )
        assertFalse(editResult.content, editResult.isError)
        assertEquals("Big5", metadata(editResult, "charset"))
        assertEquals("第一行\r\n繁體內容\r\n第三行", file.readBytes().toString(big5))

        val grepResult = fixture.tool("grep").run(
            """{"path":"legacy.txt","query":"繁體內容","encoding":"Big5"}"""
        )
        assertFalse(grepResult.content, grepResult.isError)
        assertTrue(grepResult.content.contains("legacy.txt:2"))
        assertEquals("Big5", metadata(grepResult, "charsets"))
    }

    @Test
    fun `legacy edit rejects unrepresentable text without changing bytes`() = runBlocking {
        val fixture = createFixture()
        val big5 = Charset.forName("Big5")
        val file = File(fixture.workspaceRoot, "legacy.txt")
        file.writeBytes("繁體中文".toByteArray(big5))
        val original = file.readBytes()

        val result = fixture.tool("edit").run(
            """{"path":"legacy.txt","find":"中文","replace":"🐾","encoding":"Big5"}"""
        )

        assertTrue(result.isError)
        assertEquals("text_not_representable", metadata(result, "error"))
        assertTrue(original.contentEquals(file.readBytes()))
    }

    @Test
    fun `legacy append rejects unrepresentable text without changing bytes`() = runBlocking {
        val fixture = createFixture()
        val file = File(fixture.workspaceRoot, "legacy.txt")
        file.writeBytes("繁體中文".toByteArray(Charset.forName("Big5")))
        val original = file.readBytes()

        val result = fixture.tool("write").run(
            """{"path":"legacy.txt","text":"🐾","mode":"append","encoding":"Big5"}"""
        )

        assertTrue(result.isError)
        assertEquals("text_not_representable", metadata(result, "error"))
        assertTrue(original.contentEquals(file.readBytes()))
    }

    @Test
    fun `read and grep expose high confidence legacy detection metadata`() = runBlocking {
        val fixture = createFixture()
        val file = File(fixture.workspaceRoot, "ambiguous.txt")
        val expected = "繁體中文內容與檔案編碼測試，這是一段足夠長的繁體中文。".repeat(20)
        file.writeBytes(expected.toByteArray(Charset.forName("Big5")))

        val readResult = fixture.tool("read").run("""{"path":"ambiguous.txt"}""")
        assertFalse(readResult.content, readResult.isError)
        assertEquals("Big5", metadata(readResult, "charset"))
        assertEquals("detected", metadata(readResult, "encoding_source"))
        assertTrue(metadata(readResult, "encoding_confidence")!!.toInt() >= 50)
        assertTrue(candidateCharsets(readResult).contains("Big5"))

        val grepResult = fixture.tool("grep").run(
            """{"path":"ambiguous.txt","query":"檔案編碼"}"""
        )
        assertFalse(grepResult.content, grepResult.isError)
        assertEquals("detected", metadata(grepResult, "encoding_source"))
        assertTrue(metadata(grepResult, "encoding_confidence")!!.toInt() >= 50)
        assertTrue(candidateCharsets(grepResult).contains("Big5"))
    }

    @Test
    fun `legacy append requires explicit encoding and leaves bytes unchanged`() = runBlocking {
        val fixture = createFixture()
        val file = File(fixture.workspaceRoot, "legacy.txt")
        val text = "繁體中文內容與檔案編碼測試，這是一段足夠長的繁體中文。".repeat(20)
        file.writeBytes(text.toByteArray(Charset.forName("Big5")))
        val original = file.readBytes()

        val result = fixture.tool("write").run(
            """{"path":"legacy.txt","text":"新增內容","mode":"append"}"""
        )

        assertTrue(result.isError)
        assertEquals("encoding_required_for_mutation", metadata(result, "error"))
        assertEquals("detected", metadata(result, "encoding_source"))
        assertTrue(metadata(result, "encoding_confidence")!!.toInt() >= 50)
        assertTrue(candidateCharsets(result).contains("Big5"))
        assertTrue(original.contentEquals(file.readBytes()))
    }

    @Test
    fun `legacy edit requires explicit encoding and leaves bytes unchanged`() = runBlocking {
        val fixture = createFixture()
        val file = File(fixture.workspaceRoot, "legacy.txt")
        val text = "繁體中文內容與檔案編碼測試，這是一段足夠長的繁體中文。".repeat(20)
        file.writeBytes(text.toByteArray(Charset.forName("Big5")))
        val original = file.readBytes()

        val result = fixture.tool("edit").run(
            """{"path":"legacy.txt","find":"檔案","replace":"文件"}"""
        )

        assertTrue(result.isError)
        assertEquals("encoding_required_for_mutation", metadata(result, "error"))
        assertEquals("detected", metadata(result, "encoding_source"))
        assertTrue(metadata(result, "encoding_confidence")!!.toInt() >= 50)
        assertTrue(candidateCharsets(result).contains("Big5"))
        assertTrue(original.contentEquals(file.readBytes()))
    }

    @Test
    fun `wrong bom hint leaves file bytes unchanged`() = runBlocking {
        val fixture = createFixture()
        val file = File(fixture.workspaceRoot, "utf16.txt")
        val bom = byteArrayOf(0xFF.toByte(), 0xFE.toByte())
        file.writeBytes(bom + "Hello".toByteArray(Charsets.UTF_16LE))
        val original = file.readBytes()

        val result = fixture.tool("edit").run(
            """{"path":"utf16.txt","find":"Hello","replace":"World","encoding":"UTF-8"}"""
        )

        assertTrue(result.isError)
        assertEquals("encoding_bom_conflict", metadata(result, "error"))
        assertTrue(original.contentEquals(file.readBytes()))
    }

    @Test
    fun `uncertain legacy bytes do not enter edit path`() = runBlocking {
        val fixture = createFixture()
        val file = File(fixture.workspaceRoot, "uncertain.txt")
        file.writeBytes(byteArrayOf(0x81.toByte()))
        val original = file.readBytes()

        val result = fixture.tool("edit").run(
            """{"path":"uncertain.txt","find":"x","replace":"y"}"""
        )

        assertTrue(result.isError)
        assertEquals("encoding_detection_uncertain", metadata(result, "error"))
        assertTrue(original.contentEquals(file.readBytes()))
    }

    @Test
    fun `uncertain legacy bytes return explicit errors for read and file grep`() = runBlocking {
        val fixture = createFixture()
        File(fixture.workspaceRoot, "uncertain.txt").writeBytes(byteArrayOf(0x81.toByte()))

        val readResult = fixture.tool("read").run("""{"path":"uncertain.txt"}""")
        val grepResult = fixture.tool("grep").run(
            """{"path":"uncertain.txt","query":"anything"}"""
        )

        assertTrue(readResult.isError)
        assertEquals("encoding_detection_uncertain", metadata(readResult, "error"))
        assertEquals("detected", metadata(readResult, "encoding_source"))
        assertTrue(grepResult.isError)
        assertEquals("encoding_detection_uncertain", metadata(grepResult, "error"))
        assertEquals("detected", metadata(grepResult, "encoding_source"))
    }

    @Test
    fun `directory grep detects each file independently`() = runBlocking {
        val fixture = createFixture()
        val legacyText = "繁體中文內容與檔案編碼測試，這是一段足夠長的繁體中文。".repeat(20)
        File(fixture.workspaceRoot, "utf8.txt").writeText("UTF-8 中文", Charsets.UTF_8)
        File(fixture.workspaceRoot, "big5.txt").writeBytes(
            legacyText.toByteArray(Charset.forName("Big5"))
        )

        val result = fixture.tool("grep").run(
            """{"path":".","query":"中文"}"""
        )

        assertFalse(result.content, result.isError)
        assertTrue(result.content.contains("utf8.txt:1"))
        assertTrue(result.content.contains("big5.txt:1"))
        assertTrue(metadata(result, "charsets").orEmpty().contains("UTF-8"))
        assertTrue(metadata(result, "charsets").orEmpty().contains("Big5"))
    }

    @Test
    fun `directory grep rejects a shared legacy encoding hint`() = runBlocking {
        val fixture = createFixture()
        File(fixture.workspaceRoot, "utf8.txt").writeText("中文", Charsets.UTF_8)

        val result = fixture.tool("grep").run(
            """{"path":".","query":"中文","encoding":"Big5"}"""
        )

        assertTrue(result.isError)
        assertEquals("encoding_hint_requires_file", metadata(result, "error"))
    }

    private fun createFixture(): Fixture {
        val sharedRoot = createTempDirectory("palmclaw-file-text-").toFile()
        val sessionId = "session:file-text"
        val manager = SessionWorkspaceManager(sharedRoot)
        val snapshot = manager.ensureWorkspace(sessionId, "File Text")
        val resolver = WorkspacePathResolver(
            currentSessionIdProvider = { sessionId },
            workspaceManager = manager
        )
        return Fixture(
            workspaceRoot = File(snapshot.workspaceRoot),
            tools = createFileToolSet(resolver)
        )
    }

    private data class Fixture(
        val workspaceRoot: File,
        val tools: List<Tool>
    ) {
        fun tool(name: String): Tool = tools.first { it.name == name }
    }

    private fun metadata(result: ToolResult, key: String): String? {
        return (result.metadata?.get(key) as? JsonPrimitive)?.content
    }

    private fun candidateCharsets(result: ToolResult): List<String> {
        val candidates = result.metadata?.get("encoding_candidates") as? JsonArray ?: return emptyList()
        return candidates.mapNotNull { candidate ->
            ((candidate as? JsonObject)?.get("charset") as? JsonPrimitive)?.content
        }
    }

    private fun json(path: String, text: String, mode: String): String {
        return """{"path":${JsonPrimitive(path)},"text":${JsonPrimitive(text)},"mode":${JsonPrimitive(mode)}}"""
    }

    private fun ByteArray.startsWith(prefix: ByteArray): Boolean {
        if (size < prefix.size) return false
        return prefix.indices.all { index -> this[index] == prefix[index] }
    }
}
