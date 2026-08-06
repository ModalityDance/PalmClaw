package com.palmclaw.runtime

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GatewayRuntimeAutomationOwnershipTest {
    @Test
    fun `gateway runtime delegates automation scheduler lifecycle`() {
        val source = gatewayRuntimeSource()

        listOf(
            "cronService.onJob =",
            "cronService.onLog =",
            "cronService.updatePolicy(",
            "cronService.start()",
            "cronService.stop()",
            "cronService.processDueJobs()",
            "cronService.onSystemResync()",
            "cronService.close()",
            "heartbeatService.updateConfig(",
            "heartbeatService.start()",
            "heartbeatService.stop()",
            "heartbeatService.armNextAlarm(",
            "heartbeatService.close()",
            "private fun wireCronCallback",
            "private fun wireCronLogging",
            "private fun applyCronRuntimeConfig",
            "private fun applyHeartbeatRuntimeConfig"
        ).forEach { forbidden ->
            assertFalse("GatewayRuntime should not contain $forbidden", source.contains(forbidden))
        }

        listOf(
            "AutomationRuntimeLifecycle(",
            "automationRuntimeLifecycle?.start(",
            "checkNotNull(automationRuntimeLifecycle).reload(",
            "checkNotNull(automationRuntimeLifecycle).applyCronConfig(",
            "automationRuntimeLifecycle?.applyHeartbeatConfig(",
            "automationRuntimeLifecycle?.armNextHeartbeatAlarm(",
            "checkNotNull(automationRuntimeLifecycle).processDueCronJobs(",
            "automationRuntimeLifecycle?.close()"
        ).forEach { required ->
            assertTrue("GatewayRuntime should contain $required", source.contains(required))
        }
    }

    @Test
    fun `gateway runtime retains automation agent turn behavior`() {
        val source = gatewayRuntimeSource()

        listOf(
            "private suspend fun executeCronJob(job: CronJob)",
            "CronExecutionPromptBuilder.build(job)",
            "private suspend fun resolveCronTargetSession",
            "private suspend fun decideHeartbeat",
            "private fun parseHeartbeatTasks",
            "private fun readHeartbeatDoc",
            "processHeartbeatTick()"
        ).forEach { required ->
            assertTrue("GatewayRuntime should retain $required", source.contains(required))
        }

        val lifecycleSource = sourceFile(
            "src/main/java/com/palmclaw/runtime/automation/AutomationRuntimeLifecycle.kt",
            "app/src/main/java/com/palmclaw/runtime/automation/AutomationRuntimeLifecycle.kt"
        ).readText()
        assertFalse(lifecycleSource.contains("executeAgentTurn("))
        assertFalse(lifecycleSource.contains("CronExecutionPromptBuilder"))
        assertFalse(lifecycleSource.contains("HEARTBEAT.md"))
        assertFalse(lifecycleSource.contains("SessionRepository"))
    }

    @Test
    fun `app container retains process owned automation schedulers`() {
        val source = sourceFile(
            "src/main/java/com/palmclaw/AppContainer.kt",
            "app/src/main/java/com/palmclaw/AppContainer.kt"
        ).readText()

        assertTrue(source.contains("val cronService: CronService = CronService(app, cronRepository)"))
        assertTrue(source.contains("val heartbeatService: HeartbeatService = HeartbeatService(app)"))
    }

    private fun gatewayRuntimeSource(): String = sourceFile(
        "src/main/java/com/palmclaw/runtime/GatewayRuntime.kt",
        "app/src/main/java/com/palmclaw/runtime/GatewayRuntime.kt"
    ).readText()

    private fun sourceFile(vararg candidates: String): File =
        candidates.asSequence()
            .map(::File)
            .firstOrNull(File::exists)
            ?: error("Source file not found: ${candidates.joinToString()}")
}
