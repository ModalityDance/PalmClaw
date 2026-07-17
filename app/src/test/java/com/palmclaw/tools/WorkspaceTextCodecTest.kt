package com.palmclaw.tools

import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceTextCodecTest {

    @Test
    fun `bom decoding reports format and source for every supported unicode bom`() {
        val codec = WorkspaceTextCodec(LegacyCharsetDetector { emptyList() })
        val expected = "PalmClaw 中文 🐾"
        val fixtures = listOf(
            BomFixture(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()), "UTF-8"),
            BomFixture(byteArrayOf(0xFF.toByte(), 0xFE.toByte()), "UTF-16LE"),
            BomFixture(byteArrayOf(0xFE.toByte(), 0xFF.toByte()), "UTF-16BE"),
            BomFixture(byteArrayOf(0xFF.toByte(), 0xFE.toByte(), 0x00, 0x00), "UTF-32LE"),
            BomFixture(byteArrayOf(0x00, 0x00, 0xFE.toByte(), 0xFF.toByte()), "UTF-32BE")
        )

        fixtures.forEach { fixture ->
            val charset = Charset.forName(fixture.charset)
            val result = codec.decode(
                fixture.bom + expected.toByteArray(charset),
                accessMode = WorkspaceTextAccessMode.READ_ONLY
            )

            require(result is WorkspaceTextDecodeResult.Success)
            assertEquals(expected, result.text)
            assertEquals(charset.name(), result.format.charsetName)
            assertEquals(WorkspaceTextEncodingSource.BOM, result.source)
        }
    }

    @Test
    fun `explicit encoding takes precedence over utf8 when no bom exists`() {
        val codec = WorkspaceTextCodec(LegacyCharsetDetector { emptyList() })

        val result = codec.decode(
            "PalmClaw ASCII".toByteArray(StandardCharsets.UTF_8),
            encodingHint = "windows-1252",
            accessMode = WorkspaceTextAccessMode.READ_ONLY
        )

        require(result is WorkspaceTextDecodeResult.Success)
        assertEquals("windows-1252", result.format.charsetName)
        assertEquals(WorkspaceTextEncodingSource.EXPLICIT, result.source)
    }

    @Test
    fun `bom conflict returns an explicit error`() {
        val codec = WorkspaceTextCodec(LegacyCharsetDetector { emptyList() })
        val bytes = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) +
            "Hello".toByteArray(StandardCharsets.UTF_16LE)

        val result = codec.decode(
            bytes,
            encodingHint = "UTF-8",
            accessMode = WorkspaceTextAccessMode.READ_ONLY
        )

        require(result is WorkspaceTextDecodeResult.Unsupported)
        assertEquals("encoding_bom_conflict", result.code)
    }

    @Test
    fun `strict utf8 succeeds without invoking statistical detection`() {
        var detectorCalls = 0
        val codec = WorkspaceTextCodec(
            LegacyCharsetDetector {
                detectorCalls += 1
                listOf(WorkspaceTextEncodingCandidate("Big5", 100))
            }
        )
        val expected = "简体中文 繁體中文 日本語 café 🐾"

        val result = codec.decode(
            expected.toByteArray(StandardCharsets.UTF_8),
            accessMode = WorkspaceTextAccessMode.READ_ONLY
        )

        require(result is WorkspaceTextDecodeResult.Success)
        assertEquals(expected, result.text)
        assertEquals("UTF-8", result.format.charsetName)
        assertEquals(WorkspaceTextEncodingSource.UTF8, result.source)
        assertEquals(0, detectorCalls)
    }

    @Test
    fun `confidence below fifty is uncertain and fifty is readable`() {
        val bytes = "繁體中文內容".toByteArray(Charset.forName("Big5"))
        val low = WorkspaceTextCodec(
            LegacyCharsetDetector { listOf(WorkspaceTextEncodingCandidate("Big5", 49)) }
        ).decode(bytes, accessMode = WorkspaceTextAccessMode.READ_ONLY)
        val accepted = WorkspaceTextCodec(
            LegacyCharsetDetector { listOf(WorkspaceTextEncodingCandidate("Big5", 50)) }
        ).decode(bytes, accessMode = WorkspaceTextAccessMode.READ_ONLY)

        require(low is WorkspaceTextDecodeResult.Unsupported)
        assertEquals("encoding_detection_uncertain", low.code)
        require(accepted is WorkspaceTextDecodeResult.Success)
        assertEquals("繁體中文內容", accepted.text)
        assertEquals(WorkspaceTextEncodingSource.DETECTED, accepted.source)
        assertEquals(50, accepted.confidence)
    }

    @Test
    fun `uncertain detection reports only the top three canonical candidates`() {
        val bytes = "繁體中文內容".toByteArray(Charset.forName("Big5"))
        val codec = WorkspaceTextCodec(
            LegacyCharsetDetector {
                listOf(
                    WorkspaceTextEncodingCandidate("GB18030", 10),
                    WorkspaceTextEncodingCandidate("Big5", 49),
                    WorkspaceTextEncodingCandidate("windows-1252", 20),
                    WorkspaceTextEncodingCandidate("Shift_JIS", 30)
                )
            }
        )

        val result = codec.decode(bytes, accessMode = WorkspaceTextAccessMode.READ_ONLY)

        require(result is WorkspaceTextDecodeResult.Unsupported)
        assertEquals("encoding_detection_uncertain", result.code)
        assertEquals(
            listOf("Big5", "Shift_JIS", "windows-1252"),
            result.candidates.map { it.charset }
        )
    }

    @Test
    fun `utf8 controls can fall through to a supported unicode detector`() {
        val expected = "PalmClaw UTF16 text"
        val bytes = expected.toByteArray(StandardCharsets.UTF_16LE)
        val codec = WorkspaceTextCodec(
            LegacyCharsetDetector {
                listOf(WorkspaceTextEncodingCandidate("UTF-16LE", 100))
            }
        )

        val result = codec.decode(bytes, accessMode = WorkspaceTextAccessMode.READ_ONLY)

        require(result is WorkspaceTextDecodeResult.Success)
        assertEquals(expected, result.text)
        assertEquals("UTF-16LE", result.format.charsetName)
        assertEquals(WorkspaceTextEncodingSource.DETECTED, result.source)
    }

    @Test
    fun `statistical detection never authorizes mutation`() {
        val bytes = "繁體中文內容".toByteArray(Charset.forName("Big5"))
        val codec = WorkspaceTextCodec(
            LegacyCharsetDetector { listOf(WorkspaceTextEncodingCandidate("Big5", 100)) }
        )

        val result = codec.decode(bytes, accessMode = WorkspaceTextAccessMode.MUTATION)

        require(result is WorkspaceTextDecodeResult.Unsupported)
        assertEquals("encoding_required_for_mutation", result.code)
        assertEquals("Big5", result.candidates.single().charset)
        assertEquals(100, result.candidates.single().confidence)
    }

    @Test
    fun `low confidence statistical detection still requires explicit mutation encoding`() {
        val bytes = "繁體中文內容".toByteArray(Charset.forName("Big5"))
        val codec = WorkspaceTextCodec(
            LegacyCharsetDetector { listOf(WorkspaceTextEncodingCandidate("Big5", 49)) }
        )

        val result = codec.decode(bytes, accessMode = WorkspaceTextAccessMode.MUTATION)

        require(result is WorkspaceTextDecodeResult.Unsupported)
        assertEquals("encoding_required_for_mutation", result.code)
        assertEquals(49, result.confidence)
    }

    @Test
    fun `detector candidate aliases are mapped to tool charset names`() {
        val bytes = "“PalmClaw café” costs €20.".toByteArray(Charset.forName("windows-1252"))
        val codec = WorkspaceTextCodec(
            LegacyCharsetDetector {
                listOf(WorkspaceTextEncodingCandidate("ISO_8859-1", 80))
            }
        )

        val result = codec.decode(bytes, accessMode = WorkspaceTextAccessMode.READ_ONLY)

        require(result is WorkspaceTextDecodeResult.Success)
        assertEquals("windows-1252", result.format.charsetName)
        assertEquals("windows-1252", result.candidates.single().charset)
    }

    @Test
    fun `binary controls are rejected after strict decoding`() {
        val codec = WorkspaceTextCodec(LegacyCharsetDetector { emptyList() })

        val result = codec.decode(
            byteArrayOf(0x41, 0x00, 0x42),
            encodingHint = "UTF-8",
            accessMode = WorkspaceTextAccessMode.READ_ONLY
        )

        require(result is WorkspaceTextDecodeResult.Unsupported)
        assertEquals("unsupported_binary_or_encoding", result.code)
    }

    @Test
    fun `encoding rejects characters that legacy format cannot represent`() {
        val big5 = Charset.forName("Big5")
        val codec = WorkspaceTextCodec(LegacyCharsetDetector { emptyList() })
        val decoded = codec.decode(
            "繁體中文".toByteArray(big5),
            encodingHint = "Big5",
            accessMode = WorkspaceTextAccessMode.MUTATION
        )
        require(decoded is WorkspaceTextDecodeResult.Success)

        val result = codec.encode("繁體中文 🐾", decoded.format)

        require(result is WorkspaceTextEncodeResult.Unsupported)
        assertEquals("text_not_representable", result.code)
    }

    @Test
    fun `icu adapter returns canonical candidates for representative legacy text`() {
        val detector = IcuLegacyCharsetDetector()
        val fixtures = listOf(
            "Big5" to "繁體中文內容與檔案編碼測試，這是一段足夠長的繁體中文。".repeat(20),
            "GB18030" to "简体中文内容与文件编码测试，这是一段足够长的简体中文。𠀀".repeat(20),
            "Shift_JIS" to "日本語の文字コード検出を確認するための十分に長い文章です。".repeat(20),
            "windows-1252" to "“PalmClaw café” costs €20 — résumé and naïve text. ".repeat(20)
        )

        fixtures.forEach { (encoding, text) ->
            val candidates = detector.detect(text.toByteArray(Charset.forName(encoding)))

            assertTrue(
                "$encoding not found in $candidates",
                candidates.any { it.charset == encoding && it.confidence in 1..100 }
            )
        }
    }

    private data class BomFixture(val bom: ByteArray, val charset: String)
}
