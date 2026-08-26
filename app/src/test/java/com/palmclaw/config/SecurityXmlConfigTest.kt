package com.palmclaw.config

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SecurityXmlConfigTest {

    @Test
    fun `backup rules exclude plain and secure config preferences`() {
        val backupRules = readProjectFile("app/src/main/res/xml/backup_rules.xml", "src/main/res/xml/backup_rules.xml")
        val extractionRules = readProjectFile(
            "app/src/main/res/xml/data_extraction_rules.xml",
            "src/main/res/xml/data_extraction_rules.xml"
        )

        listOf(backupRules, extractionRules).forEach { xml ->
            assertTrue(xml.contains("""domain="sharedpref""""))
            assertTrue(xml.contains("""path="palmclaw_config.xml""""))
            assertTrue(xml.contains("""path="palmclaw_secure_config.xml""""))
        }
    }

    @Test
    fun `network config keeps dynamic local cleartext without duplicate manifest opt in`() {
        val manifest = readProjectFile(
            "app/src/main/AndroidManifest.xml",
            "src/main/AndroidManifest.xml"
        )
        val networkSecurity = readProjectFile(
            "app/src/main/res/xml/network_security_config.xml",
            "src/main/res/xml/network_security_config.xml"
        )

        assertTrue(manifest.contains("android:networkSecurityConfig"))
        assertFalse(manifest.contains("android:usesCleartextTraffic"))
        assertTrue(networkSecurity.contains("cleartextTrafficPermitted=\"true\""))
        assertTrue(networkSecurity.contains("McpEndpointPolicy"))
    }

    private fun readProjectFile(vararg candidates: String): String {
        return readProjectFileCandidate(candidates).readText(Charsets.UTF_8)
    }

    private fun readProjectFileCandidate(candidates: Array<out String>): File {
        var current = File(System.getProperty("user.dir")).canonicalFile
        while (true) {
            candidates
                .map { File(current, it) }
                .firstOrNull { it.isFile }
                ?.let { return it }
            current = current.parentFile ?: break
        }
        error("Could not find project file. Tried: ${candidates.joinToString()}")
    }
}
