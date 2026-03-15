package com.palmclaw.templates

import android.content.Context
import com.palmclaw.config.AppStoragePaths
import java.io.File

private const val TEMPLATE_ASSET_DIR = "templates"
private const val LEGACY_SYSTEM_PROMPT_TEMPLATE = "system_prompt.md"
private const val AGENT_TEMPLATE = "AGENT.md"

class TemplateStore(
    private val context: Context
) {
    private val userTemplateDir: File = AppStoragePaths.templatesDir(context)

    init {
        migrateLegacyTemplateName(
            legacyName = LEGACY_SYSTEM_PROMPT_TEMPLATE,
            newName = AGENT_TEMPLATE
        )
        syncBuiltinTemplatesToUserDir()
    }

    fun loadTemplate(name: String): String? {
        val safeName = name.trim().ifBlank { return null }
        val userFile = File(userTemplateDir, safeName)
        if (userFile.exists() && userFile.isFile) {
            return userFile.readText(Charsets.UTF_8)
        }

        return runCatching {
            context.assets.open("$TEMPLATE_ASSET_DIR/$safeName")
                .bufferedReader(Charsets.UTF_8)
                .use { it.readText() }
        }.getOrNull()
    }

    private fun syncBuiltinTemplatesToUserDir() {
        userTemplateDir.mkdirs()
        val names = context.assets.list(TEMPLATE_ASSET_DIR).orEmpty()
        for (name in names) {
            val target = File(userTemplateDir, name)
            if (target.exists()) continue
            val content = runCatching {
                context.assets.open("$TEMPLATE_ASSET_DIR/$name")
                    .bufferedReader(Charsets.UTF_8)
                    .use { it.readText() }
            }.getOrNull() ?: continue
            target.parentFile?.mkdirs()
            target.writeText(content, Charsets.UTF_8)
        }
    }

    private fun migrateLegacyTemplateName(legacyName: String, newName: String) {
        userTemplateDir.mkdirs()
        val legacyFile = File(userTemplateDir, legacyName)
        val newFile = File(userTemplateDir, newName)
        if (!legacyFile.exists() || !legacyFile.isFile || newFile.exists()) return

        if (!legacyFile.renameTo(newFile)) {
            val copied = runCatching {
                newFile.writeText(legacyFile.readText(Charsets.UTF_8), Charsets.UTF_8)
            }.isSuccess
            if (copied) {
                runCatching { legacyFile.delete() }
            }
        }
    }
}
