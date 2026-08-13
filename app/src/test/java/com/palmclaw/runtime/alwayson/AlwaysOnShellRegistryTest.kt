package com.palmclaw.runtime.alwayson

import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AlwaysOnShellRegistryTest {
    @After
    fun tearDown() {
        AlwaysOnShellRegistry.resetForTest()
    }

    @Test
    fun `coordinator requested stop is visible immediately and consumed on destroy`() {
        AlwaysOnShellRegistry.markRunning()

        AlwaysOnShellRegistry.expectStop()

        assertEquals(AlwaysOnShellSnapshot(), AlwaysOnShellRegistry.state.value)
        assertTrue(AlwaysOnShellRegistry.markStopped())
        assertFalse(AlwaysOnShellRegistry.markStopped())
    }

    @Test
    fun `unexpected service destruction is not classified as a requested stop`() {
        AlwaysOnShellRegistry.markRunning()

        assertFalse(AlwaysOnShellRegistry.markStopped())
        assertEquals(AlwaysOnShellSnapshot(), AlwaysOnShellRegistry.state.value)
    }

    @Test
    fun `service timeout stays running until destruction and emits one timeout trigger`() {
        var stopTransitions = 0
        val lifecycle = AlwaysOnServiceLifecycle(
            onRunning = AlwaysOnShellRegistry::markRunning,
            onStopped = {
                stopTransitions += 1
                AlwaysOnShellRegistry.markStopped()
            }
        )
        lifecycle.markRunning()

        lifecycle.markTimedOut()

        assertTrue(AlwaysOnShellRegistry.state.value.running)
        assertEquals(AlwaysOnTrigger.SERVICE_TIMEOUT, lifecycle.markDestroyed())
        assertFalse(AlwaysOnShellRegistry.state.value.running)
        assertEquals(1, stopTransitions)
        assertNull(lifecycle.markDestroyed())
        assertEquals(1, stopTransitions)
    }

    @Test
    fun `non stop restart after timeout clears the timeout classification`() {
        val lifecycle = AlwaysOnServiceLifecycle()
        lifecycle.markRunning()
        lifecycle.markTimedOut()

        lifecycle.markRunning()

        assertTrue(AlwaysOnShellRegistry.state.value.running)
        assertEquals(AlwaysOnTrigger.SERVICE_STATE_CHANGED, lifecycle.markDestroyed())
    }

    @Test
    fun `non stop restart clears a pending expected stop`() {
        val lifecycle = AlwaysOnServiceLifecycle()
        lifecycle.markRunning()
        AlwaysOnShellRegistry.expectStop()

        lifecycle.markRunning()

        assertTrue(AlwaysOnShellRegistry.state.value.running)
        assertEquals(AlwaysOnTrigger.SERVICE_STATE_CHANGED, lifecycle.markDestroyed())
    }

    @Test
    fun `requested stop does not request recovery on destruction`() {
        val lifecycle = AlwaysOnServiceLifecycle()
        lifecycle.markRunning()
        AlwaysOnShellRegistry.expectStop()

        assertNull(lifecycle.markDestroyed())
        assertFalse(AlwaysOnShellRegistry.state.value.running)
    }

    @Test
    fun `service routes non stop starts and timeouts through the lifecycle`() {
        val source = sequenceOf(
            File("src/main/java/com/palmclaw/runtime/AlwaysOnGatewayService.kt"),
            File("app/src/main/java/com/palmclaw/runtime/AlwaysOnGatewayService.kt")
        ).first(File::exists).readText()
        val startBody = source
            .substringAfter("override fun onStartCommand")
            .substringBefore("override fun onTimeout")
        val timeoutBody = source
            .substringAfter("override fun onTimeout")
            .substringBefore("override fun onDestroy")
        val destroyBody = source
            .substringAfter("override fun onDestroy")
            .substringBefore("private fun startSpecialUseForeground")

        val stopReturn = startBody.indexOf("return START_NOT_STICKY")
        val reassertRunning = startBody.indexOf("shellLifecycle.markRunning()")
        val reconcile = startBody.indexOf("AlwaysOnRuntimeAccess.requestReconcile")
        assertTrue(stopReturn >= 0)
        assertTrue(reassertRunning > stopReturn)
        assertTrue(reconcile > reassertRunning)
        assertTrue(timeoutBody.contains("shellLifecycle.markTimedOut()"))
        assertTrue(timeoutBody.contains("stopSelf(startId)"))
        assertFalse(timeoutBody.contains("markStopped"))
        assertFalse(timeoutBody.contains("requestReconcile"))
        assertTrue(destroyBody.contains("shellLifecycle.markDestroyed()"))
    }

    @Test
    fun `health worker preserves coroutine cancellation`() {
        val source = sequenceOf(
            File("src/main/java/com/palmclaw/runtime/AlwaysOnHealthCheckWorker.kt"),
            File("app/src/main/java/com/palmclaw/runtime/AlwaysOnHealthCheckWorker.kt")
        ).first(File::exists).readText()

        assertTrue(source.contains("catch (cancelled: CancellationException)"))
        assertTrue(source.contains("throw cancelled"))
    }
}
