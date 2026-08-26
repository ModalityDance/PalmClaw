package com.palmclaw.mcp.transport

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/** Guards the version set verified against the official MCP Kotlin SDK 0.10.0 source tree. */
class McpBuildCompatibilityTest {

    @Test
    fun `MCP SDK uses its supported Kotlin Android and Ktor toolchain`() {
        val rootBuild = readProjectFile("../build.gradle.kts", "build.gradle.kts")
        val appBuild = readProjectFile("app/build.gradle.kts", "build.gradle.kts")
        val wrapper = readProjectFile(
            "gradle/wrapper/gradle-wrapper.properties",
            "../gradle/wrapper/gradle-wrapper.properties",
        )

        assertTrue(rootBuild.contains("com.android.application\") version \"8.10.1\""))
        assertTrue(rootBuild.contains("org.jetbrains.kotlin.android\") version \"2.2.21\""))
        assertTrue(appBuild.contains("io.modelcontextprotocol:kotlin-sdk-client:0.10.0"))
        assertTrue(appBuild.contains("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0"))
        assertTrue(appBuild.contains("io.ktor:ktor-client-okhttp:3.2.3"))
        assertTrue(appBuild.contains("minSdk = 24"))
        assertTrue(appBuild.contains("JavaVersion.VERSION_17"))
        assertTrue(wrapper.contains("gradle-8.11.1-bin.zip"))
    }

    private fun readProjectFile(vararg candidates: String): String {
        var current = File(System.getProperty("user.dir")).canonicalFile
        while (true) {
            candidates
                .map { File(current, it) }
                .firstOrNull(File::isFile)
                ?.let { return it.readText(Charsets.UTF_8) }
            current = current.parentFile ?: break
        }
        error("Could not find project file. Tried: ${candidates.joinToString()}")
    }
}
