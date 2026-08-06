# Automation Runtime Lifecycle Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Extract cron and heartbeat scheduler wiring from `GatewayRuntime` into one runtime-scoped lifecycle while preserving automation agent-turn behavior and keeping the shared schedulers restartable.

**Architecture:** `AutomationRuntimeLifecycle` owns one `GatewayRuntime` instance's cron callbacks, config application, alarm delegation, due-job delegation, and identity-safe cleanup. Narrow scheduler ports adapt the process-owned `CronService` and `HeartbeatService` so lifecycle behavior is testable without Android services. `GatewayRuntime` retains cron and heartbeat agent turns, configuration persistence, session selection, fallback content, and delivery.

**Tech Stack:** Kotlin, Android AlarmManager-backed services, Kotlin coroutines, existing `GatewayRuntimeSupervisor`, JUnit 4, source-based structural tests.

**Verification constraint:** Add test sources before production changes, but do not run Gradle during implementation. The user will later run `:app:testDebugUnitTest` and `:app:assembleDebug` in one unified validation batch. During this phase run only `rtk git diff --check`, `rtk rg`, source inspections, and Git status.

**Execution precondition:** Use `superpowers:using-git-worktrees` to create an isolated `refactor/automation-runtime-lifecycle` worktree from commit `f074a79`. Preserve the preceding channel lifecycle commits and do not rewrite or squash them.

---

## File Map

- Create `app/src/main/java/com/palmclaw/runtime/automation/AutomationRuntimeSchedulers.kt`: narrow cron and heartbeat scheduler ports plus production adapters over the process-owned services.
- Create `app/src/main/java/com/palmclaw/runtime/automation/AutomationRuntimeLifecycle.kt`: callback ownership, config application, delegation, and idempotent cleanup.
- Create `app/src/test/java/com/palmclaw/runtime/automation/AutomationRuntimeLifecycleTest.kt`: lifecycle ordering, delegation, callback ownership, and cleanup tests using fakes.
- Create `app/src/test/java/com/palmclaw/runtime/GatewayRuntimeAutomationOwnershipTest.kt`: source-level guard for the extracted boundary and retained agent-turn behavior.
- Modify `app/src/main/java/com/palmclaw/runtime/GatewayRuntime.kt`: compose the lifecycle and delegate scheduler operations while retaining automation execution.
- Modify `docs/engineering/architecture.md`: document process-owned schedulers and runtime-scoped automation binding.
- Modify `docs/engineering/roadmap.md`: record source implementation and identify MCP lifecycle as the next extraction.
- Modify `docs/engineering/testing.md`: register focused tests and deferred foreground or Always-on restart checks.

### Task 1: Specify AutomationRuntimeLifecycle Behavior

**Files:**
- Create: `app/src/test/java/com/palmclaw/runtime/automation/AutomationRuntimeLifecycleTest.kt`

- [ ] **Step 1: Verify the execution branch and clean worktree**

```bash
rtk git branch --show-current
rtk git status --short
```

Expected: the branch is `refactor/automation-runtime-lifecycle` and status has no entries.

- [ ] **Step 2: Write the lifecycle test source**

Create the complete test file below. It intentionally references production types that do not exist yet.

```kotlin
package com.palmclaw.runtime.automation

import com.palmclaw.config.CronConfig
import com.palmclaw.config.HeartbeatConfig
import com.palmclaw.cron.CronJob
import com.palmclaw.cron.CronKinds
import com.palmclaw.cron.CronPayload
import com.palmclaw.cron.CronSchedule
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class AutomationRuntimeLifecycleTest {
    @Test
    fun `start registers callbacks before cron and heartbeat configuration`() {
        val events = mutableListOf<String>()
        val fixture = fixture(events)

        fixture.lifecycle.start(enabledCron(), enabledHeartbeat())

        assertEquals(
            listOf(
                "cron:job:set",
                "cron:log:set",
                "cron:update:60000:50:true",
                "cron:start",
                "heartbeat:update:true:900",
                "heartbeat:start"
            ),
            events
        )
    }

    @Test
    fun `disabled config stops both schedulers in stable order`() {
        val events = mutableListOf<String>()
        val fixture = fixture(events)

        fixture.lifecycle.start(disabledCron(), disabledHeartbeat())

        assertEquals(
            listOf(
                "cron:job:set",
                "cron:log:set",
                "cron:update:120000:25:false",
                "cron:stop",
                "heartbeat:update:false:1800",
                "heartbeat:stop"
            ),
            events
        )
    }

    @Test
    fun `reload reapplies configs without registering callbacks again`() {
        val events = mutableListOf<String>()
        val fixture = fixture(events)
        fixture.lifecycle.start(enabledCron(), enabledHeartbeat())
        events.clear()

        fixture.lifecycle.reload(disabledCron(), disabledHeartbeat())

        assertEquals(
            listOf(
                "cron:update:120000:25:false",
                "cron:stop",
                "heartbeat:update:false:1800",
                "heartbeat:stop"
            ),
            events
        )
    }

    @Test
    fun `individual config operations preserve service call order`() {
        val events = mutableListOf<String>()
        val fixture = fixture(events)
        fixture.lifecycle.start(enabledCron(), enabledHeartbeat())
        events.clear()

        fixture.lifecycle.applyCronConfig(disabledCron())
        fixture.lifecycle.applyHeartbeatConfig(disabledHeartbeat())

        assertEquals(
            listOf(
                "cron:update:120000:25:false",
                "cron:stop",
                "heartbeat:update:false:1800",
                "heartbeat:stop"
            ),
            events
        )
    }

    @Test
    fun `due processing selects normal or resync operation and forwards heartbeat alarm`() = runBlocking {
        val events = mutableListOf<String>()
        val fixture = fixture(events)
        fixture.lifecycle.start(enabledCron(), enabledHeartbeat())
        events.clear()

        fixture.lifecycle.processDueCronJobs(resync = false)
        fixture.lifecycle.processDueCronJobs(resync = true)
        fixture.lifecycle.armNextHeartbeatAlarm(123456L)

        assertEquals(
            listOf("cron:due", "cron:resync", "heartbeat:arm:123456"),
            events
        )
    }

    @Test
    fun `cron callbacks forward to runtime handlers`() = runBlocking {
        val handledJobs = mutableListOf<String>()
        val handledLogs = mutableListOf<String>()
        val cron = FakeCronRuntimeScheduler(mutableListOf())
        val heartbeat = FakeHeartbeatRuntimeScheduler(mutableListOf())
        val lifecycle = AutomationRuntimeLifecycle(
            cronScheduler = cron,
            heartbeatScheduler = heartbeat,
            onCronJob = { job ->
                handledJobs += job.id
                "handled:${job.id}"
            },
            onCronLog = { line -> handledLogs += line }
        )
        lifecycle.start(enabledCron(), enabledHeartbeat())

        val result = cron.onJob?.invoke(job("job-1"))
        cron.onLog?.invoke("line-1")

        assertEquals("handled:job-1", result)
        assertEquals(listOf("job-1"), handledJobs)
        assertEquals(listOf("line-1"), handledLogs)
    }

    @Test
    fun `close clears owned callbacks once without stopping schedulers`() {
        val events = mutableListOf<String>()
        val fixture = fixture(events)
        fixture.lifecycle.start(enabledCron(), enabledHeartbeat())
        events.clear()

        fixture.lifecycle.close()
        fixture.lifecycle.close()

        assertEquals(listOf("cron:job:clear", "cron:log:clear"), events)
        assertEquals(null, fixture.cron.onJob)
        assertEquals(null, fixture.cron.onLog)
        assertTrue(events.none { it == "cron:stop" || it == "heartbeat:stop" })
    }

    @Test
    fun `old lifecycle close does not clear newer lifecycle callbacks`() = runBlocking {
        val events = mutableListOf<String>()
        val cron = FakeCronRuntimeScheduler(events)
        val heartbeat = FakeHeartbeatRuntimeScheduler(events)
        val oldCalls = mutableListOf<String>()
        val newCalls = mutableListOf<String>()
        val oldLifecycle = AutomationRuntimeLifecycle(
            cronScheduler = cron,
            heartbeatScheduler = heartbeat,
            onCronJob = { job -> oldCalls += job.id; "old" },
            onCronLog = {}
        )
        val newLifecycle = AutomationRuntimeLifecycle(
            cronScheduler = cron,
            heartbeatScheduler = heartbeat,
            onCronJob = { job -> newCalls += job.id; "new" },
            onCronLog = {}
        )
        oldLifecycle.start(enabledCron(), enabledHeartbeat())
        newLifecycle.start(enabledCron(), enabledHeartbeat())
        val currentJobCallback = cron.onJob
        val currentLogCallback = cron.onLog

        oldLifecycle.close()

        assertSame(currentJobCallback, cron.onJob)
        assertSame(currentLogCallback, cron.onLog)
        assertEquals("new", cron.onJob?.invoke(job("latest")))
        assertTrue(oldCalls.isEmpty())
        assertEquals(listOf("latest"), newCalls)
    }

    private fun fixture(events: MutableList<String>): Fixture {
        val cron = FakeCronRuntimeScheduler(events)
        val heartbeat = FakeHeartbeatRuntimeScheduler(events)
        return Fixture(
            lifecycle = AutomationRuntimeLifecycle(
                cronScheduler = cron,
                heartbeatScheduler = heartbeat,
                onCronJob = { job -> "handled:${job.id}" },
                onCronLog = {}
            ),
            cron = cron
        )
    }

    private fun enabledCron() = CronConfig(enabled = true, minEveryMs = 60_000L, maxJobs = 50)
    private fun disabledCron() = CronConfig(enabled = false, minEveryMs = 120_000L, maxJobs = 25)
    private fun enabledHeartbeat() = HeartbeatConfig(enabled = true, intervalSeconds = 900L)
    private fun disabledHeartbeat() = HeartbeatConfig(enabled = false, intervalSeconds = 1_800L)

    private fun job(id: String) = CronJob(
        id = id,
        name = id,
        schedule = CronSchedule(kind = CronKinds.EVERY, everyMs = 60_000L),
        payload = CronPayload(message = "run"),
        createdAtMs = 1L,
        updatedAtMs = 1L
    )

    private data class Fixture(
        val lifecycle: AutomationRuntimeLifecycle,
        val cron: FakeCronRuntimeScheduler
    )

    private class FakeCronRuntimeScheduler(
        private val events: MutableList<String>
    ) : CronRuntimeScheduler {
        override var onJob: (suspend (CronJob) -> String?)? = null
            set(value) {
                field = value
                events += "cron:job:${if (value == null) "clear" else "set"}"
            }

        override var onLog: ((String) -> Unit)? = null
            set(value) {
                field = value
                events += "cron:log:${if (value == null) "clear" else "set"}"
            }

        override fun updatePolicy(minEveryMs: Long, maxJobs: Int, logEnabled: Boolean) {
            events += "cron:update:$minEveryMs:$maxJobs:$logEnabled"
        }

        override fun start() { events += "cron:start" }
        override fun stop() { events += "cron:stop" }
        override suspend fun processDueJobs() { events += "cron:due" }
        override suspend fun onSystemResync() { events += "cron:resync" }
    }

    private class FakeHeartbeatRuntimeScheduler(
        private val events: MutableList<String>
    ) : HeartbeatRuntimeScheduler {
        override fun updateConfig(enabled: Boolean, intervalSeconds: Long) {
            events += "heartbeat:update:$enabled:$intervalSeconds"
        }

        override fun start() { events += "heartbeat:start" }
        override fun stop() { events += "heartbeat:stop" }
        override fun armNextAlarm(timestampMs: Long) { events += "heartbeat:arm:$timestampMs" }
    }
}
```

- [ ] **Step 3: Record the deferred RED command**

```bash
rtk ./gradlew :app:testDebugUnitTest --tests com.palmclaw.runtime.automation.AutomationRuntimeLifecycleTest
```

Expected before implementation: compilation failure because the scheduler ports and lifecycle do not
exist. Do not execute this command during this implementation phase.

- [ ] **Step 4: Run static checks and commit the test source**

```bash
rtk git diff --check
rtk rg -n 'start registers callbacks|old lifecycle close|close clears owned' app/src/test/java/com/palmclaw/runtime/automation/AutomationRuntimeLifecycleTest.kt
rtk git add app/src/test/java/com/palmclaw/runtime/automation/AutomationRuntimeLifecycleTest.kt
rtk git commit -m 'test: specify automation runtime lifecycle'
```

### Task 2: Implement Scheduler Ports And Lifecycle

**Files:**
- Create: `app/src/main/java/com/palmclaw/runtime/automation/AutomationRuntimeSchedulers.kt`
- Create: `app/src/main/java/com/palmclaw/runtime/automation/AutomationRuntimeLifecycle.kt`
- Test: `app/src/test/java/com/palmclaw/runtime/automation/AutomationRuntimeLifecycleTest.kt`

- [ ] **Step 1: Add narrow scheduler ports and production adapters**

Create `AutomationRuntimeSchedulers.kt` with the complete implementation:

```kotlin
package com.palmclaw.runtime.automation

import com.palmclaw.cron.CronJob
import com.palmclaw.cron.CronService
import com.palmclaw.heartbeat.HeartbeatService

internal interface CronRuntimeScheduler {
    var onJob: (suspend (CronJob) -> String?)?
    var onLog: ((String) -> Unit)?

    fun updatePolicy(minEveryMs: Long, maxJobs: Int, logEnabled: Boolean)
    fun start()
    fun stop()
    suspend fun processDueJobs()
    suspend fun onSystemResync()
}

internal interface HeartbeatRuntimeScheduler {
    fun updateConfig(enabled: Boolean, intervalSeconds: Long)
    fun start()
    fun stop()
    fun armNextAlarm(timestampMs: Long)
}

internal class CronServiceRuntimeScheduler(
    private val service: CronService
) : CronRuntimeScheduler {
    override var onJob: (suspend (CronJob) -> String?)?
        get() = service.onJob
        set(value) {
            service.onJob = value
        }

    override var onLog: ((String) -> Unit)?
        get() = service.onLog
        set(value) {
            service.onLog = value
        }

    override fun updatePolicy(minEveryMs: Long, maxJobs: Int, logEnabled: Boolean) {
        service.updatePolicy(minEveryMs, maxJobs, logEnabled)
    }

    override fun start() = service.start()
    override fun stop() = service.stop()
    override suspend fun processDueJobs() = service.processDueJobs()
    override suspend fun onSystemResync() = service.onSystemResync()
}

internal class HeartbeatServiceRuntimeScheduler(
    private val service: HeartbeatService
) : HeartbeatRuntimeScheduler {
    override fun updateConfig(enabled: Boolean, intervalSeconds: Long) {
        service.updateConfig(enabled, intervalSeconds)
    }

    override fun start() = service.start()
    override fun stop() = service.stop()
    override fun armNextAlarm(timestampMs: Long) = service.armNextAlarm(timestampMs)
}
```

Do not add `close()` to either port. The services remain process-owned by `AppContainer`.

- [ ] **Step 2: Add the runtime-scoped lifecycle**

Create `AutomationRuntimeLifecycle.kt` with the complete implementation:

```kotlin
package com.palmclaw.runtime.automation

import com.palmclaw.config.CronConfig
import com.palmclaw.config.HeartbeatConfig
import com.palmclaw.cron.CronJob
import com.palmclaw.cron.CronService
import com.palmclaw.heartbeat.HeartbeatService

internal class AutomationRuntimeLifecycle(
    private val cronScheduler: CronRuntimeScheduler,
    private val heartbeatScheduler: HeartbeatRuntimeScheduler,
    onCronJob: suspend (CronJob) -> String?,
    onCronLog: (String) -> Unit
) {
    constructor(
        cronService: CronService,
        heartbeatService: HeartbeatService,
        onCronJob: suspend (CronJob) -> String?,
        onCronLog: (String) -> Unit
    ) : this(
        cronScheduler = CronServiceRuntimeScheduler(cronService),
        heartbeatScheduler = HeartbeatServiceRuntimeScheduler(heartbeatService),
        onCronJob = onCronJob,
        onCronLog = onCronLog
    )

    private val ownedCronJobCallback: suspend (CronJob) -> String? = { job -> onCronJob(job) }
    private val ownedCronLogCallback: (String) -> Unit = { line -> onCronLog(line) }
    private var started = false
    private var closed = false

    fun start(cronConfig: CronConfig, heartbeatConfig: HeartbeatConfig) {
        check(!closed) { "Automation runtime lifecycle is closed" }
        check(!started) { "Automation runtime lifecycle is already started" }
        cronScheduler.onJob = ownedCronJobCallback
        cronScheduler.onLog = ownedCronLogCallback
        started = true
        reload(cronConfig, heartbeatConfig)
    }

    fun reload(cronConfig: CronConfig, heartbeatConfig: HeartbeatConfig) {
        requireActive()
        applyCronConfig(cronConfig)
        applyHeartbeatConfig(heartbeatConfig)
    }

    fun applyCronConfig(config: CronConfig) {
        requireActive()
        cronScheduler.updatePolicy(
            minEveryMs = config.minEveryMs,
            maxJobs = config.maxJobs,
            logEnabled = config.enabled
        )
        if (config.enabled) cronScheduler.start() else cronScheduler.stop()
    }

    fun applyHeartbeatConfig(config: HeartbeatConfig) {
        requireActive()
        heartbeatScheduler.updateConfig(
            enabled = config.enabled,
            intervalSeconds = config.intervalSeconds
        )
        if (config.enabled) heartbeatScheduler.start() else heartbeatScheduler.stop()
    }

    fun armNextHeartbeatAlarm(timestampMs: Long) {
        requireActive()
        heartbeatScheduler.armNextAlarm(timestampMs)
    }

    suspend fun processDueCronJobs(resync: Boolean) {
        requireActive()
        if (resync) {
            cronScheduler.onSystemResync()
        } else {
            cronScheduler.processDueJobs()
        }
    }

    fun close() {
        if (closed) return
        closed = true
        if (cronScheduler.onJob === ownedCronJobCallback) {
            cronScheduler.onJob = null
        }
        if (cronScheduler.onLog === ownedCronLogCallback) {
            cronScheduler.onLog = null
        }
        started = false
    }

    private fun requireActive() {
        check(started && !closed) { "Automation runtime lifecycle is not active" }
    }
}
```

- [ ] **Step 3: Record the deferred GREEN command**

```bash
rtk ./gradlew :app:testDebugUnitTest --tests com.palmclaw.runtime.automation.AutomationRuntimeLifecycleTest
```

Expected after implementation: all lifecycle tests pass. Do not execute this command during this phase.

- [ ] **Step 4: Run static contract checks**

```bash
rtk git diff --check
rtk rg -n 'interface CronRuntimeScheduler|interface HeartbeatRuntimeScheduler|class AutomationRuntimeLifecycle|=== ownedCron' app/src/main/java/com/palmclaw/runtime/automation
rtk rg -n 'close\(' app/src/main/java/com/palmclaw/runtime/automation/AutomationRuntimeSchedulers.kt
```

Expected: the first search finds both ports, the lifecycle, and identity checks. The `close(` search has no
matches.

- [ ] **Step 5: Commit the lifecycle boundary**

```bash
rtk git add app/src/main/java/com/palmclaw/runtime/automation app/src/test/java/com/palmclaw/runtime/automation/AutomationRuntimeLifecycleTest.kt
rtk git commit -m 'refactor: add automation runtime lifecycle'
```

### Task 3: Add GatewayRuntime Ownership Guards

**Files:**
- Create: `app/src/test/java/com/palmclaw/runtime/GatewayRuntimeAutomationOwnershipTest.kt`

- [ ] **Step 1: Write the structural ownership test**

Create the complete source-based test:

```kotlin
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
```

- [ ] **Step 2: Record the deferred RED command**

```bash
rtk ./gradlew :app:testDebugUnitTest --tests com.palmclaw.runtime.GatewayRuntimeAutomationOwnershipTest
```

Expected before migration: failure because `GatewayRuntime` still contains every direct scheduler call
and does not construct `AutomationRuntimeLifecycle`. Do not execute this command during this phase.

- [ ] **Step 3: Run static checks and commit the guard**

```bash
rtk git diff --check
rtk rg -n 'delegates automation scheduler lifecycle|retains automation agent turn|process owned automation' app/src/test/java/com/palmclaw/runtime/GatewayRuntimeAutomationOwnershipTest.kt
rtk git add app/src/test/java/com/palmclaw/runtime/GatewayRuntimeAutomationOwnershipTest.kt
rtk git commit -m 'test: guard automation runtime ownership'
```

### Task 4: Delegate GatewayRuntime Automation Wiring

**Files:**
- Modify: `app/src/main/java/com/palmclaw/runtime/GatewayRuntime.kt:1-175,258-340,395-505,616-688,1099-1138`
- Test: `app/src/test/java/com/palmclaw/runtime/GatewayRuntimeAutomationOwnershipTest.kt`

- [ ] **Step 1: Import and compose the optional lifecycle**

Add these imports and remove no longer used direct wiring imports only after all migration steps are
complete:

```kotlin
import com.palmclaw.cron.CronJob
import com.palmclaw.runtime.automation.AutomationRuntimeLifecycle
```

After the shared cron and heartbeat dependency fields, construct one lifecycle for an
automation-enabled runtime:

```kotlin
private val automationRuntimeLifecycle = if (enableAutomation) {
    AutomationRuntimeLifecycle(
        cronService = cronService,
        heartbeatService = heartbeatService,
        onCronJob = ::executeCronJob,
        onCronLog = cronLogStore::append
    )
} else {
    null
}
```

Keep `cronService` available to `createCronToolSet()`. Do not move cron tool construction in this phase.

- [ ] **Step 2: Delegate RuntimeToolIntegration heartbeat wiring**

Replace the heartbeat parts of the refresh and runtime ports with lifecycle delegation:

```kotlin
override fun applyHeartbeatConfig(config: HeartbeatConfig) {
    automationRuntimeLifecycle?.applyHeartbeatConfig(config)
}

override fun armNextAlarm(config: HeartbeatConfig, timestampMs: Long) {
    automationRuntimeLifecycle?.armNextHeartbeatAlarm(timestampMs)
}
```

Keep `triggerNow()` unchanged:

```kotlin
override suspend fun triggerNow(): String =
    processHeartbeatTick() ?: "Heartbeat completed with no action."
```

- [ ] **Step 3: Replace initialization and reload wiring**

Delete `wireCronCallback()` and `wireCronLogging()` calls from `init`. After message-tool, runtime-tool,
and spawn-tool setup, start the lifecycle once:

```kotlin
automationRuntimeLifecycle?.start(
    cronConfig = configStore.getCronConfig(),
    heartbeatConfig = configStore.getHeartbeatConfig()
)
```

Keep MCP initialization after this block. Replace `reloadAutomationFromStoredConfig()` with:

```kotlin
fun reloadAutomationFromStoredConfig() {
    if (!enableAutomation) return
    syncBuiltInToolsFromStoredConfig()
    checkNotNull(automationRuntimeLifecycle).reload(
        cronConfig = configStore.getCronConfig(),
        heartbeatConfig = configStore.getHeartbeatConfig()
    )
}
```

- [ ] **Step 4: Delegate cron processing and runtime cleanup**

Preserve the existing public automation-disabled error, then delegate:

```kotlin
suspend fun processDueCronJobs(resync: Boolean = false) {
    if (!enableAutomation) {
        throw IllegalStateException("Cron automation is not enabled in this runtime")
    }
    checkNotNull(automationRuntimeLifecycle).processDueCronJobs(resync)
}
```

At the beginning of `shutdownRuntime()`, replace direct callback clearing with:

```kotlin
automationRuntimeLifecycle?.close()
```

Delete `heartbeatService.close()` and `cronService.close()` from shutdown. Do not add scheduler `stop()`
calls: enabled alarms must remain capable of waking a Worker after runtime teardown.

- [ ] **Step 5: Convert the cron callback body into a retained runtime method**

Replace the outer `wireCronCallback()` assignment with this signature and keep its existing body exactly
inside the method:

```kotlin
private suspend fun executeCronJob(job: CronJob): String? {
    val target = resolveCronTargetSession(job.payload.sessionId)
    val targetSessionId = target.id
    val targetTitle = target.title
    val execution = executeAgentTurn(
        AgentTurnRequest(
            sessionId = targetSessionId,
            sessionTitle = targetTitle,
            inputText = CronExecutionPromptBuilder.build(job),
            inputRole = "internal_user",
            deliveryMode = if (job.payload.deliver) {
                AgentTurnDeliveryMode.UseSessionBinding
            } else {
                AgentTurnDeliveryMode.LocalOnly
            }
        )
    )
    val runFailure = execution.failure
    if (runFailure != null) {
        Log.w(TAG, "cron onJob agent run failed", runFailure)
    }
    var response: String? = execution.latestAssistantContentIfNew()

    if (response.isNullOrBlank()) {
        val fallback = buildString {
            append("Scheduled reminder: ")
            append(job.payload.message.trim())
            runFailure?.message?.takeIf { it.isNotBlank() }?.let {
                append("\n\nAgent error: ")
                append(it)
            }
        }
        messageRepository.appendAssistantMessage(targetSessionId, fallback)
        response = fallback
    }

    if (job.payload.deliver) {
        runCatching {
            mirrorLatestAssistantToBoundChannel(
                sessionId = execution.sessionId,
                beforeAssistantId = execution.beforeLatestAssistantId,
                binding = execution.binding,
                messageSentInTurn = execution.messageSentInTurn
            )
        }.onFailure { t ->
            Log.w(TAG, "cron remote mirror failed", t)
        }
    }
    return response
}
```

Delete `wireCronLogging()` entirely; `cronLogStore::append` is already passed to the lifecycle.

- [ ] **Step 6: Delegate config application after persistence**

Delete `applyCronRuntimeConfig()` and `applyHeartbeatRuntimeConfig()`. In `persistCronSettings()`, keep
validation and `configStore.saveCronConfig(config)` unchanged, then apply through the lifecycle:

```kotlin
configStore.saveCronConfig(config)
checkNotNull(automationRuntimeLifecycle).applyCronConfig(config)
return config
```

Do not move validation or persistence into the lifecycle.

- [ ] **Step 7: Remove dead imports and run structural checks**

```bash
rtk git diff --check
rtk rg -n 'cronService\.(onJob|onLog|updatePolicy|start|stop|processDueJobs|onSystemResync|close)|heartbeatService\.(updateConfig|start|stop|armNextAlarm|close)' app/src/main/java/com/palmclaw/runtime/GatewayRuntime.kt
rtk rg -n 'wireCronCallback|wireCronLogging|applyCronRuntimeConfig|applyHeartbeatRuntimeConfig' app/src/main/java/com/palmclaw/runtime/GatewayRuntime.kt
rtk rg -n 'AutomationRuntimeLifecycle|executeCronJob|processHeartbeatTick|CronExecutionPromptBuilder' app/src/main/java/com/palmclaw/runtime/GatewayRuntime.kt
```

Expected: the first two searches have no matches. The final search finds lifecycle construction and the
retained cron and heartbeat execution paths.

- [ ] **Step 8: Record the deferred GREEN commands**

```bash
rtk ./gradlew :app:testDebugUnitTest --tests com.palmclaw.runtime.automation.AutomationRuntimeLifecycleTest
rtk ./gradlew :app:testDebugUnitTest --tests com.palmclaw.runtime.GatewayRuntimeAutomationOwnershipTest
```

Expected after migration: both test classes pass. Do not execute these commands during this phase.

- [ ] **Step 9: Commit runtime delegation**

```bash
rtk git add app/src/main/java/com/palmclaw/runtime/GatewayRuntime.kt app/src/test/java/com/palmclaw/runtime/GatewayRuntimeAutomationOwnershipTest.kt
rtk git commit -m 'refactor: delegate automation runtime lifecycle'
```

### Task 5: Document The Automation Lifecycle Boundary

**Files:**
- Modify: `docs/engineering/architecture.md:25-35,118-131`
- Modify: `docs/engineering/roadmap.md:16-19,226-237`
- Modify: `docs/engineering/testing.md:26-36,216-242`

- [ ] **Step 1: Update architecture ownership**

After the channel lifecycle paragraph in `architecture.md`, add:

```markdown
`AutomationRuntimeLifecycle` owns one runtime's cron callback registration, cron and heartbeat config
application, heartbeat alarm delegation, cron due-job delegation, and identity-safe callback cleanup.
`AppContainer` retains the process-owned `CronService` and `HeartbeatService`, so runtime teardown detaches
callbacks without permanently closing schedulers needed by a later foreground or Always-on runtime.
`GatewayRuntime` retains cron target resolution, cron and heartbeat agent turns, fallback content, and
remote delivery.
```

Change the pressure-point entry so it says automation wiring is source-implemented and that
`GatewayRuntime` still owns MCP, attachment delivery, and remote delivery coordination. Keep the deferred
Gradle and device verification caveat.

- [ ] **Step 2: Advance the roadmap**

Change the P2 next outcome to:

```markdown
| P2 | `GatewayRuntime` boundaries | In progress | Verify automation lifecycle ownership, then isolate MCP lifecycle ownership. |
```

In the boundary-cleanup section, replace “The next source phase is automation wiring” with a paragraph
stating that `AutomationRuntimeLifecycle` now owns scheduler wiring and identity-safe cleanup, while cron
and heartbeat agent turns remain in `GatewayRuntime`. Identify independent MCP lifecycle extraction as
the next source phase.

- [ ] **Step 3: Register tests and manual verification**

Add this row to the automated test selection table in `testing.md`:

```markdown
| Automation runtime lifecycle | `AutomationRuntimeLifecycleTest`, `GatewayRuntimeAutomationOwnershipTest` |
```

Add a focused manual section containing these checks:

```markdown
### Automation runtime lifecycle

- Save enabled and disabled cron and heartbeat settings while the foreground runtime is active; verify
  policy, alarms, and settings state remain unchanged.
- Stop and restart the runtime in the same process, then run one due cron job and one heartbeat tick;
  verify only the new runtime callback executes and cron still schedules work.
- Repeat the restart flow with Always-on enabled and verify Worker dispatch reaches the same supervisor
  runtime path.
- Disable each automation service and verify its alarm is cancelled through config application, while
  runtime teardown alone does not permanently close the shared scheduler.
```

- [ ] **Step 4: Run documentation checks and commit**

```bash
rtk git diff --check
rtk rg -n 'AutomationRuntimeLifecycle|process-owned|MCP lifecycle' docs/engineering/architecture.md docs/engineering/roadmap.md docs/engineering/testing.md
rtk git add docs/engineering/architecture.md docs/engineering/roadmap.md docs/engineering/testing.md
rtk git commit -m 'docs: record automation runtime lifecycle boundary'
```

### Task 6: Final Static Verification

**Files:**
- Inspect all files changed by Tasks 1-5.

- [ ] **Step 1: Verify branch scope and formatting**

```bash
rtk git status --short --branch
rtk git diff --check f074a79..HEAD
rtk git diff --stat f074a79..HEAD
rtk git log --oneline --decorate f074a79..HEAD
```

Expected: only automation lifecycle production code, focused tests, `GatewayRuntime`, and the three
engineering documents changed. The diff check is silent.

- [ ] **Step 2: Verify scheduler ownership mechanically**

```bash
rtk rg -n 'cronService\.(onJob|onLog|updatePolicy|start|stop|processDueJobs|onSystemResync|close)|heartbeatService\.(updateConfig|start|stop|armNextAlarm|close)' app/src/main/java/com/palmclaw/runtime/GatewayRuntime.kt
rtk rg -n 'close\(' app/src/main/java/com/palmclaw/runtime/automation/AutomationRuntimeSchedulers.kt
rtk rg -n 'executeAgentTurn|CronExecutionPromptBuilder|HEARTBEAT.md|SessionRepository' app/src/main/java/com/palmclaw/runtime/automation
rtk rg -n 'executeCronJob|processHeartbeatTick|parseHeartbeatTasks|decideHeartbeat' app/src/main/java/com/palmclaw/runtime/GatewayRuntime.kt
```

Expected: the first three searches have no matches. The last search confirms business execution remains
in `GatewayRuntime`.

- [ ] **Step 3: Verify callback identity and cleanup coverage**

```bash
rtk rg -n '=== ownedCronJobCallback|=== ownedCronLogCallback|cronScheduler\.onJob = null|cronScheduler\.onLog = null' app/src/main/java/com/palmclaw/runtime/automation/AutomationRuntimeLifecycle.kt
rtk rg -n 'old lifecycle close|close clears owned callbacks|registers callbacks before' app/src/test/java/com/palmclaw/runtime/automation/AutomationRuntimeLifecycleTest.kt
```

Expected: both identity checks, both cleanup assignments, and all three lifecycle vectors are present.

- [ ] **Step 4: Record unified verification for the user**

Do not run these commands during source implementation. Report them as pending:

```bash
rtk ./gradlew :app:testDebugUnitTest
rtk ./gradlew :app:assembleDebug
```

Device verification remains pending for foreground and Always-on cron/heartbeat config refresh, manual
triggering, alarm dispatch, runtime stop/restart, callback uniqueness, fallback behavior, and next-alarm
persistence.

- [ ] **Step 5: Confirm the worktree is clean**

```bash
rtk git status --short --branch
```

Expected: the automation branch is clean with no uncommitted files.
