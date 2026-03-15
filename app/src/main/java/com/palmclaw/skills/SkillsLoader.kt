package com.palmclaw.skills

import android.content.Context
import android.util.Log
import com.palmclaw.config.AppStoragePaths
import org.json.JSONObject
import java.io.File
import java.util.Locale

private const val BUILTIN_SKILLS_ASSET_DIR = "skills"

class SkillsLoader(
    private val context: Context
) {
    private val workspaceSkills = AppStoragePaths.skillsDir(context)

    init {
        syncBuiltinSkillsToWorkspace()
    }

    data class SkillInfo(
        val name: String,
        val path: String,
        val source: String
    )

    fun listSkills(filterUnavailable: Boolean = true): List<SkillInfo> {
        val result = mutableListOf<SkillInfo>()

        if (workspaceSkills.exists()) {
            workspaceSkills.listFiles()?.forEach { dir ->
                if (dir.isDirectory) {
                    val skillFile = File(dir, "SKILL.md")
                    if (skillFile.exists()) {
                        result += SkillInfo(
                            name = dir.name,
                            path = skillFile.absolutePath,
                            source = "workspace"
                        )
                    }
                }
            }
        }

        val builtinDirs = context.assets.list(BUILTIN_SKILLS_ASSET_DIR).orEmpty()
        for (name in builtinDirs) {
            if (result.any { it.name == name }) continue
            val exists = runCatching {
                context.assets.open("$BUILTIN_SKILLS_ASSET_DIR/$name/SKILL.md").close()
                true
            }.getOrElse { false }
            if (exists) {
                result += SkillInfo(
                    name = name,
                    path = "asset://$BUILTIN_SKILLS_ASSET_DIR/$name/SKILL.md",
                    source = "builtin"
                )
            }
        }
        return if (!filterUnavailable) result else result.filter {
            checkRequirements(getSkillMeta(it.name))
        }
    }

    fun loadSkill(name: String): String? {
        val workspaceFile = File(workspaceSkills, "$name/SKILL.md")
        if (workspaceFile.exists()) {
            return workspaceFile.readText(Charsets.UTF_8)
        }

        return runCatching {
            context.assets.open("$BUILTIN_SKILLS_ASSET_DIR/$name/SKILL.md")
                .bufferedReader(Charsets.UTF_8)
                .use { it.readText() }
        }.getOrNull()
    }

    fun loadSkillsForContext(skillNames: List<String>): String {
        val sections = skillNames.mapNotNull { name ->
            val content = loadSkill(name) ?: return@mapNotNull null
            "### Skill: $name\n\n${stripFrontmatter(content)}"
        }
        return sections.joinToString("\n\n---\n\n")
    }

    fun buildSkillsSummary(): String {
        val allSkills = listSkills(filterUnavailable = false)
        if (allSkills.isEmpty()) return ""

        val lines = mutableListOf("<skills>")
        for (skill in allSkills) {
            val desc = getSkillDescription(skill.name)
            val meta = getSkillMeta(skill.name)
            val available = checkRequirements(meta)
            lines += "  <skill available=\"${available.toString().lowercase()}\">"
            lines += "    <name>${escapeXml(skill.name)}</name>"
            lines += "    <description>${escapeXml(desc)}</description>"
            lines += "    <location>${escapeXml(skill.path)}</location>"
            if (!available) {
                val missing = getMissingRequirements(meta)
                if (missing.isNotBlank()) {
                    lines += "    <requires>${escapeXml(missing)}</requires>"
                }
            }
            lines += "  </skill>"
        }
        lines += "</skills>"
        return lines.joinToString("\n")
    }

    fun getAlwaysSkills(): List<String> {
        return listSkills(filterUnavailable = true).mapNotNull { info ->
            val metadata = getSkillMetadata(info.name)
            val skillMeta = parseAgentMetadata(metadata["metadata"])
            if (skillMeta.optBoolean("always") || metadata["always"] == "true") info.name else null
        }
    }

    fun selectSkillsForInput(userText: String, maxSkills: Int = 3): List<String> {
        val normalizedInput = normalizeForMatch(userText)
        if (normalizedInput.isBlank()) return emptyList()
        val maxTake = maxSkills.coerceIn(1, 8)

        val scored = listSkills(filterUnavailable = true).mapNotNull { info ->
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

            val desc = normalizeForMatch(getSkillDescription(name))
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

    private fun getSkillDescription(name: String): String {
        val metadata = getSkillMetadata(name)
        return metadata["description"]?.takeIf { it.isNotBlank() } ?: name
    }

    private fun getSkillMeta(name: String): JSONObject {
        val metadata = getSkillMetadata(name)
        return parseAgentMetadata(metadata["metadata"])
    }

    private fun getSkillMetadata(name: String): Map<String, String> {
        val content = loadSkill(name) ?: return emptyMap()
        if (!content.startsWith("---")) return emptyMap()

        val end = content.indexOf("\n---", startIndex = 3)
        if (end <= 0) return emptyMap()

        val frontmatter = content.substring(3, end).trim()
        val map = mutableMapOf<String, String>()
        frontmatter.lineSequence().forEach { line ->
            val idx = line.indexOf(':')
            if (idx <= 0) return@forEach
            val key = line.substring(0, idx).trim()
            val value = line.substring(idx + 1).trim().trim('"', '\'')
            map[key] = value
        }
        return map
    }

    private fun parseAgentMetadata(raw: String?): JSONObject {
        if (raw.isNullOrBlank()) return JSONObject()
        return try {
            val parsed = JSONObject(raw)
            when {
                parsed.has("palmclaw") -> parsed.optJSONObject("palmclaw") ?: JSONObject()
                parsed.length() == 1 -> {
                    val firstKey = parsed.keys().asSequence().firstOrNull()
                    firstKey?.let { key ->
                        parsed.optJSONObject(key) ?: parsed
                    } ?: JSONObject()
                }
                else -> parsed
            }
        } catch (t: Throwable) {
            Log.w("SkillsLoader", "Failed to parse skill metadata JSON: ${t.message}")
            JSONObject()
        }
    }

    private fun checkRequirements(meta: JSONObject): Boolean {
        val requires = meta.optJSONObject("requires") ?: return true
        val bins = requires.optJSONArray("bins")
        if (bins != null) {
            for (i in 0 until bins.length()) {
                val bin = bins.optString(i)
                if (bin.isNotBlank() && !hasCommand(bin)) return false
            }
        }
        val env = requires.optJSONArray("env")
        if (env != null) {
            for (i in 0 until env.length()) {
                val key = env.optString(i)
                if (key.isNotBlank() && System.getenv(key).isNullOrBlank()) return false
            }
        }
        return true
    }

    private fun getMissingRequirements(meta: JSONObject): String {
        val missing = mutableListOf<String>()
        val requires = meta.optJSONObject("requires") ?: return ""

        val bins = requires.optJSONArray("bins")
        if (bins != null) {
            for (i in 0 until bins.length()) {
                val bin = bins.optString(i)
                if (bin.isNotBlank() && !hasCommand(bin)) missing += "CLI: $bin"
            }
        }
        val env = requires.optJSONArray("env")
        if (env != null) {
            for (i in 0 until env.length()) {
                val key = env.optString(i)
                if (key.isNotBlank() && System.getenv(key).isNullOrBlank()) missing += "ENV: $key"
            }
        }
        return missing.joinToString(", ")
    }

    private fun hasCommand(bin: String): Boolean {
        val path = System.getenv("PATH").orEmpty()
        val ext = if (File.separatorChar == '\\') listOf(".exe", ".bat", ".cmd", "") else listOf("")
        return path.split(File.pathSeparator).any { dir ->
            ext.any { suffix -> File(dir, bin + suffix).exists() }
        }
    }

    private fun stripFrontmatter(content: String): String {
        if (!content.startsWith("---")) return content
        val end = content.indexOf("\n---", startIndex = 3)
        return if (end > 0) content.substring(end + 4).trim() else content
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

    private fun syncBuiltinSkillsToWorkspace() {
        runCatching {
            workspaceSkills.mkdirs()
            val names = context.assets.list(BUILTIN_SKILLS_ASSET_DIR).orEmpty()
            for (name in names) {
                val targetDir = File(workspaceSkills, name)
                val targetFile = File(targetDir, "SKILL.md")
                if (targetFile.exists()) continue
                val content = runCatching {
                    context.assets.open("$BUILTIN_SKILLS_ASSET_DIR/$name/SKILL.md")
                        .bufferedReader(Charsets.UTF_8)
                        .use { it.readText() }
                }.getOrNull() ?: continue
                targetDir.mkdirs()
                targetFile.writeText(content, Charsets.UTF_8)
            }
        }.onFailure {
            Log.w("SkillsLoader", "sync builtin skills failed: ${it.message}")
        }
    }

    companion object {
        private val SKILL_KEYWORDS: Map<String, List<String>> = mapOf(
            "android-bluetooth" to listOf(
                "bluetooth", "ble", "blue tooth", "gatt",
                "\u84dd\u7259", "\u914d\u5bf9", "\u8033\u673a"
            ),
            "android-device" to listOf(
                "device", "permission", "location", "notification", "settings",
                "\u6743\u9650", "\u5b9a\u4f4d", "\u901a\u77e5", "\u8bbe\u7f6e"
            ),
            "android-file" to listOf(
                "file", "read", "write", "edit", "grep",
                "\u6587\u4ef6", "\u8bfb\u6587\u4ef6", "\u5199\u6587\u4ef6"
            ),
            "android-media" to listOf(
                "media", "photo", "video", "audio", "record", "image",
                "\u76f8\u518c", "\u89c6\u9891", "\u97f3\u9891", "\u5f55\u97f3"
            ),
            "android-personal" to listOf(
                "calendar", "contact", "event", "schedule",
                "\u65e5\u5386", "\u8054\u7cfb\u4eba", "\u65e5\u7a0b"
            ),
            "channels" to listOf(
                "channel", "channels", "telegram", "discord", "gateway", "bind session", "chat id", "bot token",
                "\u9891\u9053", "\u6e20\u9053", "telegram\u8fde\u63a5", "\u7ed1\u5b9a", "\u4f1a\u8bdd\u7ed1\u5b9a"
            ),
            "cron" to listOf("cron", "schedule", "timer", "reminder", "\u5b9a\u65f6", "\u63d0\u9192"),
            "memory" to listOf("memory", "remember", "history", "\u8bb0\u5fc6", "\u8bb0\u4f4f"),
            "summarize" to listOf("summarize", "summary", "tl;dr", "\u603b\u7ed3", "\u6458\u8981"),
            "weather" to listOf("weather", "forecast", "temperature", "\u5929\u6c14", "\u6c14\u6e29")
        )
    }
}
