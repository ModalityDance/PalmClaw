package com.palmclaw.ui

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UiStructuralGuardTest {

    @Test
    fun `large ui entry files stay below regression thresholds`() {
        assertLineCountAtMost(
            path = "src/main/java/com/palmclaw/ui/ChatScreen.kt",
            fallbackPath = "app/src/main/java/com/palmclaw/ui/ChatScreen.kt",
            maxLines = 1_400
        )
        assertLineCountAtMost(
            path = "src/main/java/com/palmclaw/ui/settings/SettingsContent.kt",
            fallbackPath = "app/src/main/java/com/palmclaw/ui/settings/SettingsContent.kt",
            maxLines = 1_800
        )
        assertLineCountAtMost(
            path = "src/main/java/com/palmclaw/ui/chat/ChatViewModel.kt",
            fallbackPath = "app/src/main/java/com/palmclaw/ui/chat/ChatViewModel.kt",
            maxLines = 3_600
        )
    }

    @Test
    fun `chat screen remains a shell over extracted chat components`() {
        val source = sourceFile(
            "src/main/java/com/palmclaw/ui/ChatScreen.kt",
            "app/src/main/java/com/palmclaw/ui/ChatScreen.kt"
        ).readText()

        assertTrue(source.contains("ChatConversationPane("))
        assertTrue(source.contains("SessionSettingsSheet("))
        assertFalse(source.contains("vm.uiState"))
        assertFalse(source.contains("ChatUiState"))
        assertFalse(source.contains("val uiState"))
        assertFalse(source.contains("messages = state.messages"))
        assertFalse(source.contains("LazyColumn("))
        assertFalse(source.contains("ChatMessageListPane("))
        assertFalse(source.contains("ChatComposerBar("))
        assertFalse(source.contains("inputBarSurfaceHeightPx"))
        assertFalse(source.contains("previewAudioPlayer"))
        assertFalse(source.contains("MediaPlayer"))
    }

    @Test
    fun `settings content delegates feature pages to extracted page components`() {
        val source = sourceFile(
            "src/main/java/com/palmclaw/ui/settings/SettingsContent.kt",
            "app/src/main/java/com/palmclaw/ui/settings/SettingsContent.kt"
        ).readText()

        assertTrue(source.contains("ProviderSettingsPage("))
        assertTrue(source.contains("ToolSettingsPage("))
        assertTrue(source.contains("ChannelSettingsPage("))
        assertTrue(source.contains("McpSettingsPage("))
        assertFalse(source.contains("ProviderEditorDialog("))
        assertFalse(source.contains("SearchProviderSettingsCard("))
        assertFalse(source.contains("UiMcpServerConfig("))
    }

    @Test
    fun `ui labels have Chinese translations`() {
        val preferences = sourceFile(
            "src/main/java/com/palmclaw/ui/UiPreferences.kt",
            "app/src/main/java/com/palmclaw/ui/UiPreferences.kt"
        ).readText()
        val toolSettingsPage = sourceFile(
            "src/main/java/com/palmclaw/ui/settings/ToolSettingsPage.kt",
            "app/src/main/java/com/palmclaw/ui/settings/ToolSettingsPage.kt"
        ).readText()
        val uiRoot = sourceFile(
            "src/main/java/com/palmclaw/ui",
            "app/src/main/java/com/palmclaw/ui"
        )
        val uiLabelPattern = Regex("""uiLabel\("([^"]+)"\)""")

        uiRoot.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .forEach { file ->
                uiLabelPattern.findAll(file.readText()).forEach { match ->
                    val label = match.groupValues[1]
                    assertTrue(
                        "UiPreferences.kt should translate $label from ${file.path}",
                        preferences.contains("\"$label\" to ")
                    )
                }
            }

        listOf(
            "Add attachment",
            "Author",
            "Collapse",
            "Compatibility",
            "Downloads",
            "License",
            "Remove attachment",
            "Requirements",
            "Search Provider",
            "Source",
            "Source URL",
            "Version"
        ).forEach { label ->
            assertTrue("UiPreferences.kt should translate $label", preferences.contains("\"$label\" to "))
        }
        assertFalse(toolSettingsPage.contains("uiLabel(\"\${option.displayName} API Key\")"))
    }

    @Test
    fun `skill install review is shown as a dialog with top actions`() {
        val source = sourceFile(
            "src/main/java/com/palmclaw/ui/settings/SkillsSettingsSection.kt",
            "app/src/main/java/com/palmclaw/ui/settings/SkillsSettingsSection.kt"
        ).readText()

        assertTrue(source.contains("InstallReviewDialog("))
        assertTrue(source.contains("private fun InstallReviewDialog("))
        assertTrue(source.contains("AlertDialog("))
        assertTrue(source.contains("ReviewActionsRow("))
        assertTrue(source.indexOf("ReviewActionsRow(") < source.indexOf("review.previewText"))
        assertFalse(source.contains("InstallReviewCard("))
        assertFalse(source.contains("private fun InstallReviewCard("))
    }

    @Test
    fun `sensitive drafts are not persisted with remember saveable`() {
        val uiFiles = listOf(
            sourceFile("src/main/java/com/palmclaw/ui/ChatScreen.kt", "app/src/main/java/com/palmclaw/ui/ChatScreen.kt"),
            sourceFile("src/main/java/com/palmclaw/ui/settings/ProviderSettingsPage.kt", "app/src/main/java/com/palmclaw/ui/settings/ProviderSettingsPage.kt"),
            sourceFile("src/main/java/com/palmclaw/ui/settings/ToolSettingsPage.kt", "app/src/main/java/com/palmclaw/ui/settings/ToolSettingsPage.kt"),
            sourceFile("src/main/java/com/palmclaw/ui/settings/McpSettingsPage.kt", "app/src/main/java/com/palmclaw/ui/settings/McpSettingsPage.kt"),
            sourceFile("src/main/java/com/palmclaw/ui/settings/SkillsSettingsSection.kt", "app/src/main/java/com/palmclaw/ui/settings/SkillsSettingsSection.kt")
        )
        val sensitiveNamePattern = Regex(
            pattern = """(?i)(apiKeyDraft|authToken|token|password|secret|credential).*rememberSaveable|rememberSaveable.*(apiKeyDraft|authToken|token|password|secret|credential)"""
        )
        val allowedUiFlags = setOf(
            "revealApiKey",
            "clearApiKeyOnNextFocus"
        )

        uiFiles.forEach { file ->
            file.readLines().forEachIndexed { index, line ->
                if (allowedUiFlags.any(line::contains)) return@forEachIndexed
                assertFalse(
                    "${file.path}:${index + 1} persists a sensitive draft with rememberSaveable",
                    sensitiveNamePattern.containsMatchIn(line)
                )
            }
        }
    }

    @Test
    fun `chat view model does not recreate composition root dependencies`() {
        val source = sourceFile(
            "src/main/java/com/palmclaw/ui/chat/ChatViewModel.kt",
            "app/src/main/java/com/palmclaw/ui/chat/ChatViewModel.kt"
        ).readText()

        listOf(
            "AppDatabase.getInstance",
            "MessageRepository(",
            "SessionRepository(",
            "RuntimeApplicationService(",
            "OkHttpClient.Builder",
            "SkillsLoader(",
            "SkillInstallService(",
            "ClawHubClient("
        ).forEach { forbidden ->
            assertFalse("ChatViewModel.kt should not contain $forbidden", source.contains(forbidden))
        }
    }

    @Test
    fun `chat view model does not regain extracted settings helper implementations`() {
        val source = sourceFile(
            "src/main/java/com/palmclaw/ui/chat/ChatViewModel.kt",
            "app/src/main/java/com/palmclaw/ui/chat/ChatViewModel.kt"
        ).readText()

        listOf(
            "private fun buildProviderTestConfig",
            "private fun buildProviderStateWithSavedDraft",
            "private fun buildProviderSettingsConfig",
            "private fun buildNormalizedMcpServers",
            "private fun validateMcpEndpointUrl",
            "private fun saveSessionChannelBindingInternal",
            "private fun getSessionChannelDraftInternal",
            "private fun toUiStagedSkillReview",
            "private fun toStagedSkillReview"
        ).forEach { forbidden ->
            assertFalse("ChatViewModel.kt should not contain $forbidden", source.contains(forbidden))
        }
    }

    private fun assertLineCountAtMost(path: String, fallbackPath: String, maxLines: Int) {
        val file = sourceFile(path, fallbackPath)
        val lineCount = file.readLines().size
        assertTrue(
            "${file.path} has $lineCount lines; expected at most $maxLines",
            lineCount <= maxLines
        )
    }

    private fun sourceFile(vararg paths: String): File {
        return paths.map(::File).first { it.exists() }
    }
}
