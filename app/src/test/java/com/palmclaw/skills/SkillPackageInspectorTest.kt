package com.palmclaw.skills

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SkillPackageInspectorTest {

    private val inspector = SkillPackageInspector()

    @Test
    fun `inspect directory parses multiline metadata frontmatter`() {
        val tempDir = Files.createTempDirectory("skill-inspector-test").toFile()
        try {
            tempDir.resolve("SKILL.md").writeText(
                """
                ---
                name: mobile-helper
                description: Mobile helper skill
                metadata: |
                  {"requires":{"bins":["python"]},"palmclaw":{"platforms":["android"]}}
                ---

                # Mobile Helper

                Use this skill to help with mobile tasks.
                """.trimIndent(),
                Charsets.UTF_8
            )
            tempDir.resolve("notes.md").writeText("hello", Charsets.UTF_8)

            val entry = inspector.inspectDirectory(
                rootDir = tempDir,
                source = SkillSource.Local,
                enabled = true,
                allowIncompatible = false
            ) ?: error("Expected skill entry")

            assertEquals("mobile-helper", entry.name)
            assertEquals("Mobile helper skill", entry.description)
            assertEquals(SkillCompatibilityStatus.DesktopRequired, entry.compatibilityStatus)
            assertTrue(entry.requirementsStatus.message.contains("python"))
            assertTrue(entry.files.any { it.relativePath == "notes.md" && it.previewText == "hello" })
        } finally {
            tempDir.deleteRecursively()
        }
    }
}
