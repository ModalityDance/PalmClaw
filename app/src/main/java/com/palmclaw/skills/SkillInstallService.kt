package com.palmclaw.skills

import android.content.Context
import com.palmclaw.config.AppStoragePaths
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private const val SKILL_MANIFEST_FILE_NAME = ".palmclaw-skill.json"

class SkillInstallService(
    private val context: Context,
    private val clawHubClient: ClawHubClient,
    private val packageInspector: SkillPackageInspector = SkillPackageInspector()
) {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private val skillsRoot = AppStoragePaths.skillsDir(context)
    private val stagingRoot = AppStoragePaths.skillsStagingDir(context)

    suspend fun stageClawHubSkill(detail: ClawHubSkillDetail): StagedSkillReview {
        val stagingId = UUID.randomUUID().toString()
        val stagingDir = File(stagingRoot, stagingId).apply { mkdirs() }
        val zipFile = File(stagingDir, "skill.zip")
        clawHubClient.downloadSkillZip(detail.downloadUrl, zipFile)
        val extractedRoot = extractSingleSkillRoot(zipFile, File(stagingDir, "unzipped"))
        val entry = packageInspector.inspectDirectory(
            rootDir = extractedRoot,
            source = SkillSource.ClawHub,
            enabled = false,
            allowIncompatible = false
        ) ?: throw IOException("Downloaded package does not contain a valid SKILL.md.")
        return StagedSkillReview(
            stagingId = stagingId,
            suggestedName = entry.name,
            detail = detail,
            compatibilityStatus = entry.compatibilityStatus,
            compatibilityReasons = entry.compatibilityReasons,
            files = entry.files,
            previewText = packageInspector.readPreview(File(extractedRoot, "SKILL.md")).orEmpty(),
            stagingDirPath = extractedRoot.absolutePath
        )
    }

    fun installStagedSkill(
        review: StagedSkillReview,
        enable: Boolean,
        allowIncompatible: Boolean
    ): InstalledSkillManifest {
        val stagingDir = File(review.stagingDirPath)
        if (!stagingDir.exists() || !File(stagingDir, "SKILL.md").exists()) {
            throw IllegalStateException("Staged skill files are missing.")
        }

        val targetDir = File(skillsRoot, review.suggestedName)
        val existingManifest = loadManifest(targetDir)
        if (targetDir.exists()) {
            val sameSlug = existingManifest?.slug?.trim().equals(review.detail.slug.trim(), ignoreCase = true)
            if (!sameSlug) {
                throw IllegalStateException("A different skill with the same name is already installed. Delete it first.")
            }
            targetDir.deleteRecursively()
        }

        copyDirectory(stagingDir, targetDir)
        val manifest = InstalledSkillManifest(
            source = SkillSource.ClawHub.wireValue,
            sourceUrl = review.detail.detailUrl,
            slug = review.detail.slug,
            version = review.detail.version,
            author = review.detail.author,
            installedAtMs = System.currentTimeMillis(),
            compatibilityStatus = review.compatibilityStatus.wireValue,
            compatibilityReasons = review.compatibilityReasons,
            securitySignals = review.detail.securitySignals
        )
        File(targetDir, SKILL_MANIFEST_FILE_NAME).writeText(
            json.encodeToString(manifest),
            Charsets.UTF_8
        )
        cleanupStaging(review.stagingId)
        return manifest.copy(
            compatibilityReasons = if (enable && allowIncompatible) {
                manifest.compatibilityReasons + "Installed with force enable."
            } else {
                manifest.compatibilityReasons
            }
        )
    }

    fun deleteInstalledSkill(skillName: String) {
        val normalizedName = skillName.trim()
        if (normalizedName.isBlank()) return
        val targetDir = File(skillsRoot, normalizedName)
        if (!targetDir.exists()) return
        targetDir.deleteRecursively()
    }

    fun cleanupStaging(stagingId: String) {
        val normalizedId = stagingId.trim()
        if (normalizedId.isBlank()) return
        File(stagingRoot, normalizedId).deleteRecursively()
    }

    private fun extractSingleSkillRoot(zipFile: File, outputDir: File): File {
        outputDir.mkdirs()
        ZipInputStream(FileInputStream(zipFile)).use { zip ->
            var entry: ZipEntry? = zip.nextEntry
            while (entry != null) {
                val target = safeResolveZipEntry(outputDir, entry)
                if (entry.isDirectory) {
                    target.mkdirs()
                } else {
                    target.parentFile?.mkdirs()
                    target.outputStream().use { output -> zip.copyTo(output) }
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }

        val skillRoots = outputDir.walkTopDown()
            .filter { it.isDirectory }
            .filter { File(it, "SKILL.md").exists() }
            .toList()
        return when {
            skillRoots.isEmpty() -> throw IOException("Downloaded ZIP does not contain a skill root with SKILL.md.")
            skillRoots.size == 1 -> skillRoots.first()
            else -> {
                val topLevel = skillRoots.firstOrNull { it.parentFile == outputDir }
                topLevel ?: throw IOException("Downloaded ZIP contains multiple skill roots.")
            }
        }
    }

    private fun safeResolveZipEntry(outputDir: File, entry: ZipEntry): File {
        val target = File(outputDir, entry.name)
        val outputCanonical = outputDir.canonicalFile
        val targetCanonical = target.canonicalFile
        if (!targetCanonical.path.startsWith(outputCanonical.path + File.separator) &&
            targetCanonical != outputCanonical
        ) {
            throw IOException("ZIP contains an unsafe path: ${entry.name}")
        }
        return targetCanonical
    }

    private fun copyDirectory(from: File, to: File) {
        from.walkTopDown().forEach { source ->
            val relative = source.relativeTo(from)
            val target = File(to, relative.path)
            if (source.isDirectory) {
                target.mkdirs()
            } else {
                target.parentFile?.mkdirs()
                source.inputStream().use { input ->
                    target.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
            }
        }
    }

    private fun loadManifest(dir: File): InstalledSkillManifest? {
        val manifestFile = File(dir, SKILL_MANIFEST_FILE_NAME)
        if (!manifestFile.exists()) return null
        return runCatching {
            json.decodeFromString<InstalledSkillManifest>(manifestFile.readText(Charsets.UTF_8))
        }.getOrNull()
    }
}
