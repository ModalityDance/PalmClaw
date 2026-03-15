package com.palmclaw.memory

import android.content.Context
import com.palmclaw.config.AppSession
import com.palmclaw.config.AppStoragePaths
import com.palmclaw.templates.TemplateStore
import java.io.File
import java.util.Locale

class MemoryStore(context: Context) {
    private val appContext = context.applicationContext
    private val memoryDir: File = AppStoragePaths.memoryDir(context).apply { mkdirs() }
    private val templateStore = TemplateStore(appContext)
    private val prefs = context.getSharedPreferences("palmclaw_memory", Context.MODE_PRIVATE)
    private val longTermFile: File = File(memoryDir, GLOBAL_MEMORY_FILE_NAME)
    private val historyFile: File = File(memoryDir, GLOBAL_HISTORY_FILE_NAME)

    init {
        migrateLegacyLongTermIfNeeded()
        migrateLegacyHistoryIfNeeded()
        ensureLongTermFileExists()
    }

    fun readLongTerm(): String {
        return if (longTermFile.exists()) longTermFile.readText(Charsets.UTF_8) else ""
    }

    fun writeLongTerm(content: String) {
        longTermFile.writeText(content, Charsets.UTF_8)
    }

    fun appendHistory(sessionId: String, entry: String) {
        val target = historyFileForSession(sessionId)
        target.parentFile?.mkdirs()
        target.appendText(entry.trimEnd() + "\n\n", Charsets.UTF_8)
    }

    fun readHistory(sessionId: String, maxLines: Int = 300): String {
        val target = historyFileForSession(sessionId)
        if (!target.exists()) return ""
        val safeMax = maxLines.coerceIn(1, 2000)
        return target.readLines(Charsets.UTF_8).takeLast(safeMax).joinToString("\n")
    }

    fun searchHistory(
        sessionId: String,
        query: String,
        ignoreCase: Boolean = true,
        regex: Boolean = false,
        limit: Int = 50
    ): List<String> {
        val target = historyFileForSession(sessionId)
        if (!target.exists() || query.isBlank()) return emptyList()
        val safeLimit = limit.coerceIn(1, 500)
        val matcher: (String) -> Boolean
        if (regex) {
            val options: Set<RegexOption> = if (ignoreCase) {
                setOf(RegexOption.IGNORE_CASE)
            } else {
                emptySet()
            }
            val compiled = Regex(query, options)
            matcher = { line -> compiled.containsMatchIn(line) }
        } else {
            matcher = { line -> line.contains(query, ignoreCase = ignoreCase) }
        }

        val results = mutableListOf<String>()
        target.useLines { lines ->
            var lineNo = 0
            for (line in lines) {
                lineNo += 1
                if (matcher(line)) {
                    results += "[$lineNo] $line"
                    if (results.size >= safeLimit) break
                }
            }
        }
        return results
    }

    fun getLastConsolidatedIndex(sessionId: String): Int {
        val key = lastConsolidatedKeyForSession(sessionId)
        if (prefs.contains(key)) {
            return prefs.getInt(key, 0)
        }
        return prefs.getInt(LEGACY_LAST_CONSOLIDATED_KEY, 0)
    }

    fun setLastConsolidatedIndex(sessionId: String, index: Int) {
        prefs.edit().putInt(lastConsolidatedKeyForSession(sessionId), index).apply()
    }

    private fun lastConsolidatedKeyForSession(sessionId: String): String {
        return "last_consolidated_${sessionId.trim().ifBlank { "default" }}"
    }

    private fun migrateLegacyLongTermIfNeeded() {
        if (longTermFile.exists()) return
        val legacy = memoryDir.listFiles()
            .orEmpty()
            .filter { it.isFile && it.name.startsWith("MEMORY_") && it.extension.lowercase() == "md" }
            .sortedBy { it.name }
        if (legacy.isEmpty()) return

        val merged = legacy.mapNotNull { file ->
            val text = runCatching { file.readText(Charsets.UTF_8).trim() }.getOrDefault("")
            if (text.isBlank()) return@mapNotNull null
            "## ${file.name}\n$text"
        }.joinToString("\n\n")

        if (merged.isNotBlank()) {
            longTermFile.writeText(merged + "\n", Charsets.UTF_8)
        }
    }

    private fun migrateLegacyHistoryIfNeeded() {
        if (historyFile.exists()) return
        val legacy = memoryDir.listFiles()
            .orEmpty()
            .filter { it.isFile && it.name.startsWith("HISTORY_") && it.extension.lowercase() == "md" }
            .sortedBy { it.name }
        if (legacy.isEmpty()) return

        val merged = legacy.mapNotNull { file ->
            val text = runCatching { file.readText(Charsets.UTF_8).trim() }.getOrDefault("")
            if (text.isBlank()) return@mapNotNull null
            "## ${file.name}\n$text"
        }.joinToString("\n\n")

        if (merged.isNotBlank()) {
            historyFile.writeText(merged + "\n", Charsets.UTF_8)
        }
    }

    private fun ensureLongTermFileExists() {
        if (longTermFile.exists()) return
        longTermFile.parentFile?.mkdirs()
        val template = templateStore.loadTemplate(GLOBAL_MEMORY_FILE_NAME).orEmpty().trim()
        if (template.isBlank()) {
            longTermFile.writeText("", Charsets.UTF_8)
            return
        }
        longTermFile.writeText(template + "\n", Charsets.UTF_8)
    }

    private fun historyFileForSession(sessionId: String): File {
        val normalized = sessionId.trim().ifBlank { AppSession.LOCAL_SESSION_ID }
        if (normalized == AppSession.LOCAL_SESSION_ID) {
            return historyFile
        }
        return File(memoryDir, "HISTORY_${sanitizeSessionId(normalized)}.md")
    }

    private fun sanitizeSessionId(sessionId: String): String {
        return sessionId.trim()
            .lowercase(Locale.US)
            .replace(Regex("[^a-z0-9._-]+"), "_")
            .trim('_')
            .ifBlank { "session" }
    }

    companion object {
        private const val GLOBAL_MEMORY_FILE_NAME = "MEMORY.md"
        private const val GLOBAL_HISTORY_FILE_NAME = "HISTORY.md"
        private const val LEGACY_LAST_CONSOLIDATED_KEY = "last_consolidated_global"
    }
}
