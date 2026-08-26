package com.palmclaw.runtime

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GatewayRuntimeChannelOwnershipTest {
    @Test
    fun `gateway runtime delegates configured adapter lifecycle`() {
        val source = sourceFile(
            "src/main/java/com/palmclaw/runtime/GatewayRuntime.kt",
            "app/src/main/java/com/palmclaw/runtime/GatewayRuntime.kt"
        ).readText()

        listOf(
            "TelegramChannelAdapter(",
            "DiscordChannelAdapter(",
            "SlackChannelAdapter(",
            "FeishuChannelAdapter(",
            "EmailChannelAdapter(",
            "WeComChannelAdapter(",
            "private fun buildAdapters",
            "private var gatewayOrchestrator",
            "private data class EmailCredentialKey"
        ).forEach { forbidden ->
            assertFalse("GatewayRuntime should not contain $forbidden", source.contains(forbidden))
        }

        listOf(
            "ChannelGatewayLifecycle(",
            "ConfiguredChannelAdapterFactory(app)",
            "channelGatewayLifecycle.apply(",
            "channelGatewayLifecycle.deliverOutbound(",
            "channelGatewayLifecycle.stop()"
        ).forEach { required ->
            assertTrue("GatewayRuntime should contain $required", source.contains(required))
        }
    }

    @Test
    fun `gateway runtime retains processing aware config deferral`() {
        val source = sourceFile(
            "src/main/java/com/palmclaw/runtime/GatewayRuntime.kt",
            "app/src/main/java/com/palmclaw/runtime/GatewayRuntime.kt"
        ).readText()

        listOf(
            "private val gatewayProcessingSessions",
            "private var pendingGatewayConfig",
            "private var pendingGatewayStop",
            "private fun onGatewaySessionProcessingChanged",
            "private fun requestGatewayRuntimeConfig",
            "requestGatewayRuntimeConfig(configStore.getChannelsConfig())",
            "fun stopGateway()",
            "private fun stopGatewayNow()"
        ).forEach { required ->
            assertTrue("GatewayRuntime should retain $required", source.contains(required))
        }

        val lifecycle = sourceFile(
            "src/main/java/com/palmclaw/channels/ChannelGatewayLifecycle.kt",
            "app/src/main/java/com/palmclaw/channels/ChannelGatewayLifecycle.kt"
        ).readText()
        assertFalse(lifecycle.contains("pendingGatewayConfig"))
        assertFalse(lifecycle.contains("gatewayProcessingSessions"))
    }

    private fun sourceFile(vararg candidates: String): File =
        candidates.asSequence()
            .map(::File)
            .firstOrNull(File::exists)
            ?: error("Source file not found: ${candidates.joinToString()}")
}
