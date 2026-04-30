package com.palmclaw.skills

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SkillCompatibilityEvaluatorTest {

    private val evaluator = SkillCompatibilityEvaluator()

    @Test
    fun `missing skill file is invalid`() {
        val result = evaluator.evaluate(
            hasSkillFile = false,
            frontmatter = emptyMap(),
            metadataJson = JSONObject(),
            relativePaths = emptyList()
        )

        assertEquals(SkillCompatibilityStatus.Invalid, result.status)
        assertTrue(result.reasons.first().contains("SKILL.md"))
    }

    @Test
    fun `instruction only skill is likely compatible`() {
        val result = evaluator.evaluate(
            hasSkillFile = true,
            frontmatter = mapOf("name" to "docs-only"),
            metadataJson = JSONObject(),
            relativePaths = listOf(
                "SKILL.md",
                "references/guide.md",
                "notes.txt"
            )
        )

        assertEquals(SkillCompatibilityStatus.LikelyCompatible, result.status)
    }

    @Test
    fun `requires bins and scripts marks skill desktop required`() {
        val result = evaluator.evaluate(
            hasSkillFile = true,
            frontmatter = mapOf("name" to "desktop-ish"),
            metadataJson = JSONObject(
                """{"requires":{"bins":["git","python"]}}"""
            ),
            relativePaths = listOf(
                "SKILL.md",
                "scripts/bootstrap.sh"
            )
        )

        assertEquals(SkillCompatibilityStatus.DesktopRequired, result.status)
        assertTrue(result.reasons.any { it.contains("git") })
        assertTrue(result.reasons.any { it.contains("scripts/") })
    }

    @Test
    fun `missing frontmatter with non documentation files is unknown`() {
        val result = evaluator.evaluate(
            hasSkillFile = true,
            frontmatter = emptyMap(),
            metadataJson = JSONObject(),
            relativePaths = listOf(
                "SKILL.md",
                "examples/sample.pdf"
            )
        )

        assertEquals(SkillCompatibilityStatus.Unknown, result.status)
    }
}
