package com.palmclaw.cron

import android.content.Context
import com.palmclaw.config.AppStoragePaths
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class CronLogStore(context: Context) {
    private val logFile = AppStoragePaths.cronLogFile(context).apply {
        parentFile?.mkdirs()
    }
    private val timeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    @Synchronized
    fun append(raw: String) {
        val line = "[${timeFormat.format(Date())}] $raw"
        logFile.appendText(line + "\n", Charsets.UTF_8)
        trimIfNeeded()
    }

    @Synchronized
    fun readRecent(maxLines: Int = 300): String {
        if (!logFile.exists()) return ""
        val lines = logFile.readLines(Charsets.UTF_8)
        return lines.takeLast(maxLines).joinToString("\n")
    }

    @Synchronized
    fun clear() {
        if (logFile.exists()) {
            logFile.writeText("", Charsets.UTF_8)
        }
    }

    private fun trimIfNeeded() {
        if (!logFile.exists()) return
        if (logFile.length() <= MAX_LOG_BYTES) return
        val lines = logFile.readLines(Charsets.UTF_8)
        val kept = lines.takeLast(MAX_KEEP_LINES)
        logFile.writeText(kept.joinToString("\n") + "\n", Charsets.UTF_8)
    }

    companion object {
        private const val MAX_LOG_BYTES = 1_000_000L
        private const val MAX_KEEP_LINES = 4000
    }
}
