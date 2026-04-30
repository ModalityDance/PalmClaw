package com.palmclaw.skills

import android.content.Context
import android.util.Log
import com.palmclaw.config.AppStoragePaths
import com.palmclaw.config.SkillUserState
import java.io.File
import java.util.Locale
import kotlinx.serialization.decodeFromString

private const val BUILTIN_SKILLS_ASSET_DIR = "skills"
private const val SKILL_MANIFEST_FILE_NAME = ".palmclaw-skill.json"

class SkillsLoader(
    private val context: Context,
    private val skillStatesProvider: () -> Map<String, SkillUserState> = { emptyMap() },
    private val packageInspector: SkillPackageInspector = SkillPackageInspector()
) {
    private val workspaceSkills = AppStoragePaths.skillsDir(context)

    fun listSkills(): List<SkillCatalogEntry> {
        val skillStates = skillStatesProvider()
        val builtins = linkedMapOf<String, SkillCatalogEntry>()
        builtinSkillNames().forEach { name ->
            buildBuiltinEntry(name, skillStates[name]).let { entry ->
                if (entry != null) {
                    builtins[name] = entry
                }
            }
        }

        val workspaceEntries = linkedMapOf<String, SkillCatalogEntry>()
        workspaceSkills.listFiles()
            .orEmpty()
            .filter { it.isDirectory }
            .filterNot { it.name.startsWith('.') }
            .sortedBy { it.name.lowercase(Locale.US) }
            .forEach { dir ->
                val manifest = loadInstalledManifest(dir)
                if (shouldIgnoreLegacyBuiltinMirror(dir, builtins.keys)) return@forEach
                val source = manifest?.resolvedSource() ?: SkillSource.Local
                packageInspector.inspectDirectory(
                    rootDir = dir,
                    source = source,
                    enabled = true,
                    allowIncompatible = false,
                    manifest = manifest
                )?.let { entry ->
                    val resolvedState = skillStates[entry.name] ?: skillStates[dir.name]
                    workspaceEntries[entry.name] = entry.copy(
                        enabled = resolvedState?.enabled ?: entry.enabled,
                        allowIncompatible = resolvedState?.allowIncompatible ?: entry.allowIncompatible
                    )
                }
            }

        val combined = linkedMapOf<String, SkillCatalogEntry>()
        combined.putAll(builtins)
        workspaceEntries.forEach { (name, entry) ->
            combined[name] = entry
        }
        return combined.values.sortedWith(compareBy({ it.source.wireValue }, { it.displayName.lowercase(Locale.US) }))
    }

    fun getSkill(name: String): SkillCatalogEntry? {
        val normalized = name.trim()
        if (normalized.isBlank()) return null
        return listSkills().firstOrNull { it.name == normalized }
    }

    fun loadSkill(name: String): String? {
        val normalized = name.trim()
        if (normalized.isBlank()) return null

        val workspaceFile = File(workspaceSkills, "$normalized/SKILL.md")
        if (workspaceFile.exists() && !shouldIgnoreLegacyBuiltinMirror(workspaceFile.parentFile, builtinSkillNames().toSet())) {
            return runCatching { workspaceFile.readText(Charsets.UTF_8) }.getOrNull()
        }

        return readAssetSkill(normalized)
    }

    fun loadSkillsForContext(skillNames: List<String>): String {
        val sections = skillNames.mapNotNull { name ->
            val content = loadSkill(name) ?: return@mapNotNull null
            "### Skill: $name\n\n${packageInspector.stripFrontmatter(content)}"
        }
        return sections.joinToString("\n\n---\n\n")
    }

    fun buildSkillsSummary(): String {
        val allSkills = listSkills()
        if (allSkills.isEmpty()) return ""

        val lines = mutableListOf("<skills>")
        for (skill in allSkills) {
            val availableToAgent = isAvailableToAgent(skill)
            lines += "  <skill available=\"${availableToAgent.toString().lowercase()}\">"
            lines += "    <name>${escapeXml(skill.name)}</name>"
            lines += "    <description>${escapeXml(skill.description)}</description>"
            lines += "    <location>${escapeXml(skill.path)}</location>"
            lines += "    <source>${escapeXml(skill.source.wireValue)}</source>"
            lines += "    <enabled>${skill.enabled}</enabled>"
            lines += "    <forced>${skill.forceEnabled}</forced>"
            lines += "    <compatibility>${escapeXml(skill.compatibilityStatus.wireValue)}</compatibility>"
            if (skill.compatibilityReasons.isNotEmpty()) {
                lines += "    <compatibility_reason>${escapeXml(skill.compatibilityReasons.joinToString(" | "))}</compatibility_reason>"
            }
            if (skill.requirementsStatus.message.isNotBlank()) {
                lines += "    <requirements>${escapeXml(skill.requirementsStatus.message)}</requirements>"
            }
            lines += "  </skill>"
        }
        lines += "</skills>"
        return lines.joinToString("\n")
    }

    fun getAlwaysSkills(): List<String> {
        return listSkills()
            .filter { isAvailableToAgent(it) && it.always }
            .map { it.name }
    }

    fun selectSkillsForInput(userText: String, maxSkills: Int = 3): List<String> {
        val normalizedInput = normalizeForMatch(userText)
        if (normalizedInput.isBlank()) return emptyList()
        val maxTake = maxSkills.coerceIn(1, 8)

        val scored = listSkills()
            .filter(::isAvailableToAgent)
            .mapNotNull { info ->
                val name = info.name
                var score = 0

                val normalizedName = normalizeForMatch(name)
                if (normalizedName.isNotBlank() && normalizedInput.contains(normalizedName)) {
                    score += 8
                }
                name.split('-', '_', ' ')
                    .map { normalizeForMatch(it) }
                    .filter { it.length >= 3 }
                    .forEach { token ->
                        if (normalizedInput.contains(token)) score += 2
                    }

                val desc = normalizeForMatch(info.description)
                desc.split(Regex("[^\\p{L}\\p{N}]+"))
                    .map { it.trim() }
                    .filter { it.length >= 4 }
                    .take(24)
                    .forEach { token ->
                        if (normalizedInput.contains(token)) score += 1
                    }

                SKILL_KEYWORDS[name].orEmpty().forEach { keyword ->
                    val normalizedKeyword = normalizeForMatch(keyword)
                    if (normalizedKeyword.isNotBlank() && normalizedInput.contains(normalizedKeyword)) {
                        score += 3
                    }
                }

                if (score > 0) name to score else null
            }

        return scored
            .sortedByDescending { it.second }
            .map { it.first }
            .distinct()
            .take(maxTake)
    }

    fun readSkillFilePreview(name: String, relativePath: String, maxChars: Int = 8_000): String? {
        val normalizedName = name.trim()
        val normalizedRelativePath = relativePath.trim().trimStart('/', '\\')
        if (normalizedName.isBlank() || normalizedRelativePath.isBlank()) return null

        val workspaceFile = File(workspaceSkills, normalizedName).resolve(normalizedRelativePath)
        if (workspaceFile.exists() && workspaceFile.isFile) {
            return runCatching {
                workspaceFile.readText(Charsets.UTF_8)
                    .replace("\r\n", "\n")
                    .take(maxChars)
            }.getOrNull()
        }

        return readAssetText("$BUILTIN_SKILLS_ASSET_DIR/$normalizedName/$normalizedRelativePath")
            ?.replace("\r\n", "\n")
            ?.take(maxChars)
    }

    private fun buildBuiltinEntry(
        name: String,
        state: SkillUserState?
    ): SkillCatalogEntry? {
        val content = readAssetSkill(name) ?: return null
        val frontmatter = packageInspector.parseFrontmatter(content)
        val metadataJson = packageInspector.parseMetadataJson(frontmatter["metadata"])
        val displayName = frontmatter["name"]?.takeIf { it.isNotBlank() } ?: name
        val description = frontmatter["description"].orEmpty().ifBlank { displayName }
        val always = frontmatter["always"]?.trim()?.equals("true", ignoreCase = true) == true ||
            metadataJson.optBoolean("always")
        val fileEntries = listAssetFiles(name)
        val compatibility = SkillCompatibilityEvaluator().evaluate(
            hasSkillFile = true,
            frontmatter = frontmatter,
            metadataJson = metadataJson,
            relativePaths = fileEntries.filterNot { it.isDirectory }.map { it.relativePath }
        )

        return SkillCatalogEntry(
            name = name,
            displayName = displayName,
            description = description,
            path = "asset://$BUILTIN_SKILLS_ASSET_DIR/$name/SKILL.md",
            source = SkillSource.Builtin,
            enabled = state?.enabled ?: true,
            allowIncompatible = state?.allowIncompatible ?: false,
            always = always,
            compatibilityStatus = compatibility.status,
            compatibilityReasons = compatibility.reasons,
            requirementsStatus = buildRequirementsStatus(metadataJson),
            files = fileEntries,
            metadata = SkillMetadata(
                name = name,
                displayName = displayName,
                description = description,
                always = always,
                frontmatter = frontmatter,
                metadataJson = metadataJson
            ),
            manifest = null
        )
    }

    private fun listAssetFiles(skillName: String): List<SkillFileEntry> {
        val result = mutableListOf<SkillFileEntry>()
        fun walk(relativeDir: String) {
            val basePath = buildString {
                append(BUILTIN_SKILLS_ASSET_DIR)
                append('/')
                append(skillName)
                if (relativeDir.isNotBlank()) {
                    append('/')
                    append(relativeDir)
                }
            }
            val children = context.assets.list(basePath).orEmpty().sorted()
            children.forEach { child ->
                val nextRelative = listOf(relativeDir, child).filter { it.isNotBlank() }.joinToString("/")
                val nextPath = "$BUILTIN_SKILLS_ASSET_DIR/$skillName/$nextRelative"
                val nested = context.assets.list(nextPath).orEmpty()
                if (nested.isNotEmpty()) {
                    result += SkillFileEntry(
                        relativePath = nextRelative,
                        isDirectory = true,
                        sizeBytes = 0L
                    )
                    walk(nextRelative)
                } else {
                    val previewable = isTextPreviewable(nextRelative)
                    val previewText = if (previewable) {
                        readAssetText(nextPath)?.replace("\r\n", "\n")?.take(4_000)
                    } else {
                        null
                    }
                    val sizeBytes = readAssetBytes(nextPath)?.size?.toLong() ?: 0L
                    result += SkillFileEntry(
                        relativePath = nextRelative,
                        isDirectory = false,
                        sizeBytes = sizeBytes,
                        previewText = previewText,
                        previewable = previewable
                    )
                }
            }
        }

        result += SkillFileEntry(relativePath = ".", isDirectory = true, sizeBytes = 0L)
        walk("")
        return result
    }

    private fun loadInstalledManifest(dir: File): InstalledSkillManifest? {
        val manifestFile = File(dir, SKILL_MANIFEST_FILE_NAME)
        if (!manifestFile.exists()) return null
        return runCatching {
            kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
                .decodeFromString<InstalledSkillManifest>(manifestFile.readText(Charsets.UTF_8))
        }.onFailure {
            Log.w("SkillsLoader", "Failed to read skill manifest for ${dir.name}: ${it.message}")
        }.getOrNull()
    }

    private fun shouldIgnoreLegacyBuiltinMirror(dir: File?, builtinNames: Set<String>): Boolean {
        if (dir == null || !dir.exists() || !dir.isDirectory) return false
        if (!builtinNames.contains(dir.name)) return false
        val manifestFile = File(dir, SKILL_MANIFEST_FILE_NAME)
        if (manifestFile.exists()) return false
        val workspaceSkill = File(dir, "SKILL.md")
        if (!workspaceSkill.exists()) return false
        val assetSkill = readAssetSkill(dir.name) ?: return false
        val workspaceContent = runCatching { workspaceSkill.readText(Charsets.UTF_8) }.getOrNull() ?: return false
        return workspaceContent == assetSkill
    }

    private fun builtinSkillNames(): List<String> {
        return context.assets.list(BUILTIN_SKILLS_ASSET_DIR)
            .orEmpty()
            .filter { name ->
                runCatching {
                    context.assets.open("$BUILTIN_SKILLS_ASSET_DIR/$name/SKILL.md").close()
                    true
                }.getOrDefault(false)
            }
            .sorted()
    }

    private fun isAvailableToAgent(skill: SkillCatalogEntry): Boolean {
        if (!skill.enabled) return false
        return when (skill.compatibilityStatus) {
            SkillCompatibilityStatus.Compatible,
            SkillCompatibilityStatus.LikelyCompatible -> true
            SkillCompatibilityStatus.Unknown,
            SkillCompatibilityStatus.DesktopRequired -> skill.allowIncompatible
            SkillCompatibilityStatus.Invalid -> false
        }
    }

    private fun buildRequirementsStatus(metadataJson: org.json.JSONObject): SkillRequirementsStatus {
        val requires = metadataJson.optJSONObject("requires") ?: return SkillRequirementsStatus(
            satisfied = true,
            message = "No declared runtime requirements."
        )
        val parts = mutableListOf<String>()
        requires.optJSONArray("bins")?.let { bins ->
            val values = buildList {
                for (index in 0 until bins.length()) {
                    val value = bins.optString(index).trim()
                    if (value.isNotBlank()) add(value)
                }
            }
            if (values.isNotEmpty()) parts += "CLI: ${values.joinToString(", ")}"
        }
        requires.optJSONArray("env")?.let { env ->
            val values = buildList {
                for (index in 0 until env.length()) {
                    val value = env.optString(index).trim()
                    if (value.isNotBlank()) add(value)
                }
            }
            if (values.isNotEmpty()) parts += "ENV: ${values.joinToString(", ")}"
        }
        return if (parts.isEmpty()) {
            SkillRequirementsStatus(true, "No declared runtime requirements.")
        } else {
            SkillRequirementsStatus(false, parts.joinToString(" | "))
        }
    }

    private fun readAssetSkill(name: String): String? {
        return readAssetText("$BUILTIN_SKILLS_ASSET_DIR/$name/SKILL.md")
    }

    private fun readAssetText(path: String): String? {
        return runCatching {
            context.assets.open(path)
                .bufferedReader(Charsets.UTF_8)
                .use { it.readText() }
        }.getOrNull()
    }

    private fun readAssetBytes(path: String): ByteArray? {
        return runCatching {
            context.assets.open(path).use { it.readBytes() }
        }.getOrNull()
    }

    private fun normalizeForMatch(value: String): String {
        return value.trim().lowercase(Locale.US)
    }

    private fun escapeXml(value: String): String {
        return value
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
    }

    private fun isTextPreviewable(relativePath: String): Boolean {
        return relativePath.endsWith(".md", ignoreCase = true) ||
            relativePath.endsWith(".txt", ignoreCase = true) ||
            relativePath.endsWith(".json", ignoreCase = true) ||
            relativePath.endsWith(".yaml", ignoreCase = true) ||
            relativePath.endsWith(".yml", ignoreCase = true) ||
            relativePath.endsWith(".xml", ignoreCase = true) ||
            relativePath.endsWith(".kt", ignoreCase = true) ||
            relativePath.endsWith(".py", ignoreCase = true) ||
            relativePath.endsWith(".js", ignoreCase = true) ||
            relativePath.endsWith(".ts", ignoreCase = true) ||
            relativePath.endsWith(".sh", ignoreCase = true) ||
            relativePath.endsWith(".ps1", ignoreCase = true) ||
            relativePath.equals("SKILL.md", ignoreCase = true)
    }

    companion object {
        private val SKILL_KEYWORDS: Map<String, List<String>> = mapOf(
            "android-bluetooth" to listOf("bluetooth", "ble", "blue tooth", "gatt", "蓝牙", "配对", "耳机"),
            "android-device" to listOf("device", "permission", "location", "notification", "settings", "权限", "定位", "通知", "设置"),
            "android-file" to listOf("file", "read", "write", "edit", "grep", "文件", "读文件", "写文件"),
            "android-media" to listOf("media", "photo", "video", "audio", "record", "image", "相册", "视频", "音频", "录音"),
            "android-personal" to listOf("calendar", "contact", "event", "schedule", "日历", "联系人", "日程"),
            "channels" to listOf("channel", "channels", "telegram", "discord", "gateway", "bind session", "chat id", "bot token", "频道", "渠道", "绑定", "会话绑定"),
            "cron" to listOf("cron", "schedule", "timer", "reminder", "定时", "提醒"),
            "memory" to listOf("memory", "remember", "history", "记忆", "记住"),
            "skill-creator" to listOf("skill", "skills", "skill creator", "create skill", "技能", "创建技能"),
            "summarize" to listOf("summarize", "summary", "tl;dr", "总结", "摘要"),
            "text-encoding" to listOf("encoding", "utf-8", "乱码", "编码"),
            "weather" to listOf("weather", "forecast", "temperature", "天气", "气温")
        )
    }
}
