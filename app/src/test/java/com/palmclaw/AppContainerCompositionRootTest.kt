package com.palmclaw

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppContainerCompositionRootTest {

    @Test
    fun `manifest declares palm claw application`() {
        val manifest = sourceFile("src/main/AndroidManifest.xml", "app/src/main/AndroidManifest.xml")
            .readText()

        assertTrue(manifest.contains("""android:name=".PalmClawApplication""""))
    }

    @Test
    fun `chat view model environment delegates construction to app container`() {
        val source = sourceFile(
            "src/main/java/com/palmclaw/ui/chat/ChatViewModelEnvironment.kt",
            "app/src/main/java/com/palmclaw/ui/chat/ChatViewModelEnvironment.kt"
        ).readText()

        assertTrue(source.contains("AppContainer"))
        assertFalse(source.contains("AppDatabase.getInstance"))
        assertFalse(source.contains("MessageRepository("))
        assertFalse(source.contains("SessionRepository("))
        assertFalse(source.contains("RuntimeApplicationService("))
        assertFalse(source.contains("OkHttpClient.Builder"))
    }

    @Test
    fun `chat view model factory uses app container`() {
        val source = sourceFile(
            "src/main/java/com/palmclaw/ui/chat/ChatViewModel.kt",
            "app/src/main/java/com/palmclaw/ui/chat/ChatViewModel.kt"
        ).readText()

        assertTrue(source.contains("AppContainer.from(application)"))
        assertTrue(source.contains("ChatViewModelEnvironment(container)"))
    }

    @Test
    fun `gateway runtime uses app container dependencies`() {
        val runtimeSource = sourceFile(
            "src/main/java/com/palmclaw/runtime/GatewayRuntime.kt",
            "app/src/main/java/com/palmclaw/runtime/GatewayRuntime.kt"
        ).readText()
        val supervisorSource = sourceFile(
            "src/main/java/com/palmclaw/runtime/GatewayRuntimeSupervisor.kt",
            "app/src/main/java/com/palmclaw/runtime/GatewayRuntimeSupervisor.kt"
        ).readText()

        assertTrue(runtimeSource.contains("GatewayRuntimeDependencies"))
        assertFalse(runtimeSource.contains("AppDatabase.getInstance"))
        assertFalse(runtimeSource.contains("MessageRepository("))
        assertFalse(runtimeSource.contains("SessionRepository("))
        assertFalse(runtimeSource.contains("ConfigStore(app)"))
        assertTrue(supervisorSource.contains("AppContainer.from(app)"))
    }

    @Test
    fun `chat view model depends on domain services for chat runtime and skills`() {
        val source = sourceFile(
            "src/main/java/com/palmclaw/ui/chat/ChatViewModel.kt",
            "app/src/main/java/com/palmclaw/ui/chat/ChatViewModel.kt"
        ).readText()

        assertTrue(source.contains("private val chatRepository = environment.chatRepository"))
        assertTrue(source.contains("private val runtimeGateway = environment.runtimeGateway"))
        assertTrue(source.contains("private val skillRepository = environment.skillRepository"))
        assertFalse(source.contains("private val messageRepository = environment.messageRepository"))
        assertFalse(source.contains("private val sessionRepository = environment.sessionRepository"))
        assertFalse(source.contains("private val runtimeApplicationService = environment.runtimeApplicationService"))
        assertFalse(source.contains("private val skillsLoader = environment.skillsLoader"))
        assertFalse(source.contains("private val skillInstallService = environment.skillInstallService"))
        assertFalse(source.contains("private val clawHubClient = environment.clawHubClient"))
    }

    private fun sourceFile(vararg paths: String): File {
        return paths.map(::File).first { it.exists() }
    }
}
