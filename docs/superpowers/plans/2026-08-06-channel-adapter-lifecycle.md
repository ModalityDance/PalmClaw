# Channel Adapter Lifecycle Ownership Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Move configured channel-adapter assembly and the owned `GatewayOrchestrator` lifecycle out of `GatewayRuntime` while preserving configuration, routing, processing deferral, and runtime behavior.

**Architecture:** `ConfiguredChannelAdapterFactory` converts stored session bindings into the same six concrete adapter types. `ChannelGatewayLifecycle` owns the optional orchestrator and all create, reconfigure, stop, delivery, and capability operations. `GatewayRuntime` remains the composition root and retains processing-aware config deferral, binding resolution, and agent-turn callbacks.

**Tech Stack:** Kotlin, Android `Application`, Kotlin coroutines, existing PalmClaw channel adapters and `GatewayOrchestrator`, JUnit 4, source-based structural tests.

**Verification constraint:** Add test sources before production changes, but do not run Gradle during implementation. The user will later run `:app:testDebugUnitTest` and `:app:assembleDebug` in one unified validation batch. During this phase run only `git diff --check`, `rg`, source inspections, and Git status.

**Execution precondition:** Start from the clean `refactor/runtime-ui-observation` head containing the approved design and plan, then create `refactor/channel-adapter-lifecycle`. Do not rewrite or squash the preceding runtime UI observation commits.

---

## File Map

- Create `app/src/main/java/com/palmclaw/channels/ConfiguredChannelAdapterFactory.kt`: configured binding validation, normalization, grouping, and concrete adapter construction.
- Create `app/src/main/java/com/palmclaw/channels/ChannelGatewayLifecycle.kt`: orchestrator control contract, factory contract, lifecycle snapshot, and stateful lifecycle owner.
- Modify `app/src/main/java/com/palmclaw/channels/GatewayOrchestrator.kt`: implement the narrow lifecycle control contract without changing behavior.
- Modify `app/src/main/java/com/palmclaw/runtime/GatewayRuntime.kt`: compose and delegate to the factory and lifecycle while retaining processing-aware config policy.
- Create `app/src/test/java/com/palmclaw/channels/ConfiguredChannelAdapterFactoryTest.kt`: fixed vectors for six channels, invalid bindings, grouping, and routing targets.
- Create `app/src/test/java/com/palmclaw/channels/ChannelGatewayLifecycleTest.kt`: lifecycle transition, delivery, error, and capability tests using fakes.
- Create `app/src/test/java/com/palmclaw/runtime/GatewayRuntimeChannelOwnershipTest.kt`: structural ownership and retained-deferral guards.
- Modify `docs/engineering/architecture.md`: record the extracted adapter and orchestrator lifecycle boundary.
- Modify `docs/engineering/roadmap.md`: mark this phase implemented and identify automation wiring as the next extraction.
- Modify `docs/engineering/testing.md`: add the focused test classes and deferred device scenarios.

### Task 1: Characterize Configured Adapter Assembly

**Files:**
- Create: `app/src/test/java/com/palmclaw/channels/ConfiguredChannelAdapterFactoryTest.kt`

- [ ] **Step 1: Create the feature branch**

```bash
git switch -c refactor/channel-adapter-lifecycle
```

Expected: Git reports the new branch and `git status --short --branch` shows a clean worktree on
`refactor/channel-adapter-lifecycle`.

- [ ] **Step 2: Write the six-channel fixed-vector test**

Create bindings with stable credentials and assert the factory returns one adapter per channel with the
key produced by the shared identity module:

```kotlin
@Test
fun `creates all configured adapter types with shared identity keys`() {
    val bindings = listOf(
        SessionChannelBinding(
            sessionId = "telegram-session",
            channel = " telegram ",
            chatId = "1001",
            telegramBotToken = " telegram-token "
        ),
        SessionChannelBinding(
            sessionId = "discord-session",
            channel = "DISCORD",
            chatId = "123456789012345678",
            discordBotToken = "discord-token"
        ),
        SessionChannelBinding(
            sessionId = "slack-session",
            channel = "slack",
            chatId = " C12345678 ",
            slackBotToken = "xoxb-token",
            slackAppToken = "xapp-token"
        ),
        SessionChannelBinding(
            sessionId = "feishu-session",
            channel = "feishu",
            chatId = " oc_chat ",
            feishuAppId = "cli_app",
            feishuAppSecret = "secret"
        ),
        SessionChannelBinding(
            sessionId = "email-session",
            channel = "email",
            chatId = " Recipient@Example.com ",
            emailConsentGranted = true,
            emailImapHost = "imap.example.com",
            emailImapUsername = "mailbox@example.com",
            emailImapPassword = "imap-password",
            emailSmtpHost = "smtp.example.com",
            emailSmtpUsername = "mailbox@example.com",
            emailSmtpPassword = "smtp-password",
            emailFromAddress = " Mailbox@Example.com "
        ),
        SessionChannelBinding(
            sessionId = "wecom-session",
            channel = "wecom",
            chatId = " wr_chat ",
            wecomBotId = "bot-id",
            wecomSecret = "secret"
        )
    )

    val adapters = ConfiguredChannelAdapterFactory(TestApplication()).create(bindings)

    assertEquals(
        setOf("telegram", "discord", "slack", "feishu", "email", "wecom"),
        adapters.map { it.channelName }.toSet()
    )
    bindings.forEach { binding ->
        val expectedKey = ChannelAdapterIdentity.primaryKeyForBinding(binding)
        assertTrue(adapters.any { it.channelName == binding.channel.trim().lowercase() && it.adapterKey == expectedKey })
    }
}

private class TestApplication : Application() {
    override fun getApplicationContext(): Context = this
}
```

Use `Locale.US` in the actual test assertion instead of locale-sensitive lowercase.

- [ ] **Step 3: Write invalid-binding and credential-grouping tests**

Add these cases:

```kotlin
@Test
fun `omits disabled unsupported and incomplete bindings`() {
    val bindings = listOf(
        SessionChannelBinding(sessionId = "disabled", enabled = false, channel = "telegram", chatId = "1", telegramBotToken = "token"),
        SessionChannelBinding(sessionId = "unsupported", channel = "matrix", chatId = "room"),
        SessionChannelBinding(sessionId = "telegram-missing", channel = "telegram", chatId = "1"),
        SessionChannelBinding(sessionId = "discord-invalid", channel = "discord", chatId = "not-a-snowflake", discordBotToken = "token"),
        SessionChannelBinding(sessionId = "slack-invalid", channel = "slack", chatId = "general", slackBotToken = "xoxb", slackAppToken = "xapp"),
        SessionChannelBinding(sessionId = "email-no-consent", channel = "email", chatId = "to@example.com")
    )

    assertTrue(ConfiguredChannelAdapterFactory(TestApplication()).create(bindings).isEmpty())
}

@Test
fun `groups bindings by adapter credentials and keeps distinct credentials separate`() {
    val bindings = listOf(
        telegramBinding("one", "100", "shared-token"),
        telegramBinding("two", "200", "shared-token"),
        telegramBinding("three", "300", "other-token")
    )

    val adapters = ConfiguredChannelAdapterFactory(TestApplication()).create(bindings)
        .filter { it.channelName == "telegram" }

    assertEquals(2, adapters.size)
    assertTrue(adapters.any { it.canHandleOutbound(OutboundMessage("telegram", "100", "message")) })
    assertTrue(adapters.any { it.canHandleOutbound(OutboundMessage("telegram", "200", "message")) })
    assertTrue(adapters.any { it.canHandleOutbound(OutboundMessage("telegram", "300", "message")) })
}

private fun telegramBinding(sessionId: String, chatId: String, token: String) =
    SessionChannelBinding(
        sessionId = sessionId,
        channel = "telegram",
        chatId = chatId,
        telegramBotToken = token
    )
```

Also add one same-credentials grouping assertion for Discord, Slack, Email, and WeCom, plus an assertion
that Feishu bindings continue to use `groupFeishuBindingsByAdapterIdentity()` by checking the expected
single primary adapter key for compatible bindings.

Add a normalization vector using Slack `<#C12345678|general>`, Email `Recipient@Example.com`, a trimmed
WeCom target, and a Feishu string containing an `oc_` target. Assert the returned adapters handle the
normalized outbound targets `C12345678`, `recipient@example.com`, the trimmed WeCom ID, and the extracted
Feishu ID. This locks the current factory boundary without inspecting private adapter fields.

- [ ] **Step 4: Record the deferred RED command**

```bash
./gradlew :app:testDebugUnitTest --tests com.palmclaw.channels.ConfiguredChannelAdapterFactoryTest
```

Expected before implementation: compilation failure because `ConfiguredChannelAdapterFactory` does not
exist. Do not execute this command during the current implementation phase.

- [ ] **Step 5: Run static checks and commit the test source**

```bash
git diff --check
rg -n "creates all configured adapter types|omits disabled|groups bindings" app/src/test/java/com/palmclaw/channels/ConfiguredChannelAdapterFactoryTest.kt
git add app/src/test/java/com/palmclaw/channels/ConfiguredChannelAdapterFactoryTest.kt
git commit -m "test: characterize configured channel adapters"
```

### Task 2: Extract ConfiguredChannelAdapterFactory

**Files:**
- Create: `app/src/main/java/com/palmclaw/channels/ConfiguredChannelAdapterFactory.kt`
- Modify: `app/src/main/java/com/palmclaw/runtime/GatewayRuntime.kt:1197-1381`

- [ ] **Step 1: Define the factory contract**

Start the new file with the contract and imports needed by the moved implementation:

```kotlin
package com.palmclaw.channels

import android.app.Application
import android.util.Log
import com.palmclaw.config.SessionChannelBinding

internal fun interface ChannelAdapterFactory {
    fun create(bindings: List<SessionChannelBinding>): List<ChannelAdapter>
}
```

Add the production class in Step 2 with the complete moved implementation; do not create an intermediate
stub body.

- [ ] **Step 2: Move the complete adapter assembly implementation**

Add `ConfiguredChannelAdapterFactory(private val app: Application) : ChannelAdapterFactory`. Its
`create(bindings)` implementation is the complete current `GatewayRuntime.buildAdapters()` body, moved
without changing branch order or grouping keys. Add a private companion object with
`const val TAG = "ConfiguredAdapterFactory"` for the existing Feishu conflict log.

The moved body must retain `activeBindings = bindings.filter { it.enabled }` followed by the existing six
`filter { channel equals ... }.mapNotNull { binding -> ... }` collections for Telegram, Discord, Slack,
Feishu, Email, and WeCom. Keep those blocks inline; do not introduce another normalization abstraction in
this phase.

The actual implementation may keep the existing inline `filter/mapNotNull` blocks instead of introducing
the helper names shown above. Import `SessionChannelBindingRules` and use these shared equivalents rather
than copying private `GatewayRuntime` helpers:

```text
SessionChannelBindingRules.isDiscordSnowflake
SessionChannelBindingRules.normalizeDiscordResponseMode
SessionChannelBindingRules.normalizeSlackChannelId
SessionChannelBindingRules.isSlackChannelId
SessionChannelBindingRules.normalizeSlackResponseMode
com.palmclaw.channels.normalizeFeishuTargetId
SessionChannelBindingRules.normalizeFeishuResponseMode
SessionChannelBindingRules.normalizeEmailAddress
SessionChannelBindingRules.normalizeWeComTargetId
```

Keep Telegram credentials as `binding.telegramBotToken.trim()` and Discord targets as
`binding.chatId.trim()`, exactly matching the existing assembly code. Do not replace the whole branch with
`SessionChannelBindingRules.normalize(binding)`, because that would broaden raw token and Discord mention
handling during this behavior-preserving extraction.

Build the same adapters in the same order. Every non-Feishu group must use:

```kotlin
val adapterKey = checkNotNull(
    ChannelAdapterIdentity.primaryKeyForBinding(grouped.first())
)
```

Feishu must continue through `groupFeishuBindingsByAdapterIdentity(feishuBindings)` and retain the exact
conflict log message. Do not add new validation beyond the moved code.

- [ ] **Step 3: Move EmailCredentialKey with its only consumer**

Move the private data class from the bottom of `GatewayRuntime.kt` into
`ConfiguredChannelAdapterFactory.kt`:

```kotlin
private data class EmailCredentialKey(
    val consentGranted: Boolean,
    val imapHost: String,
    val imapPort: Int,
    val imapUsername: String,
    val imapPassword: String,
    val smtpHost: String,
    val smtpPort: Int,
    val smtpUsername: String,
    val smtpPassword: String,
    val fromAddress: String,
    val autoReplyEnabled: Boolean
)
```

- [ ] **Step 4: Temporarily delegate GatewayRuntime assembly to the factory**

Add one field near `gatewayBus`:

```kotlin
private val channelAdapterFactory: ChannelAdapterFactory = ConfiguredChannelAdapterFactory(app)
```

Replace the old method body with a single-line delegate so this commit changes assembly ownership before
lifecycle ownership:

```kotlin
private fun buildAdapters(bindings: List<SessionChannelBinding>): List<ChannelAdapter> =
    channelAdapterFactory.create(bindings)
```

Delete concrete adapter, route-rule, Feishu grouping, and `EmailCredentialKey` imports from
`GatewayRuntime.kt`.

- [ ] **Step 5: Run static checks**

```bash
git diff --check
rg -n "TelegramChannelAdapter|DiscordChannelAdapter|SlackChannelAdapter|FeishuChannelAdapter|EmailChannelAdapter|WeComChannelAdapter|EmailCredentialKey" app/src/main/java/com/palmclaw/runtime/GatewayRuntime.kt
rg -n "class ConfiguredChannelAdapterFactory|groupFeishuBindingsByAdapterIdentity|ChannelAdapterIdentity.primaryKeyForBinding" app/src/main/java/com/palmclaw/channels/ConfiguredChannelAdapterFactory.kt
```

Expected: the first search has no matches; the second search finds the factory and shared identity calls.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/palmclaw/channels/ConfiguredChannelAdapterFactory.kt app/src/main/java/com/palmclaw/runtime/GatewayRuntime.kt
git commit -m "refactor: extract configured channel adapter factory"
```

### Task 3: Specify And Implement ChannelGatewayLifecycle

**Files:**
- Create: `app/src/main/java/com/palmclaw/channels/ChannelGatewayLifecycle.kt`
- Create: `app/src/test/java/com/palmclaw/channels/ChannelGatewayLifecycleTest.kt`
- Modify: `app/src/main/java/com/palmclaw/channels/GatewayOrchestrator.kt:25-113,369-405`

- [ ] **Step 1: Write lifecycle transition tests with a fake orchestrator**

Use a recording adapter factory, factory-created fake controls, and snapshots captured through the
callback. The core fake must implement the exact planned contract:

```kotlin
private class FakeOrchestrator(
    override var adapterCount: Int
) : GatewayOrchestratorControl {
    var startCount = 0
    var stopCount = 0
    val reconfigurations = mutableListOf<List<ChannelAdapter>>()
    val delivered = mutableListOf<OutboundMessage>()
    var deliveryFailure: Throwable? = null
    var capability: ChannelAttachmentCapability? = null

    override fun start() { startCount += 1 }
    override fun reconfigure(adapters: List<ChannelAdapter>) {
        reconfigurations += adapters
        adapterCount = adapters.size
    }
    override fun stop() { stopCount += 1 }
    override suspend fun deliverOutboundNow(outbound: OutboundMessage) {
        deliveryFailure?.let { throw it }
        delivered += outbound
    }
    override fun resolveOutboundAttachmentCapability(
        outbound: OutboundMessage
    ): ChannelAttachmentCapability? = capability
}
```

Add tests with exact assertions for:

```kotlin
@Test fun `disabled apply stops current gateway and clears error`()
@Test fun `enabled apply with no adapters reports existing compatibility error`()
@Test fun `first enabled apply creates and starts one gateway`()
@Test fun `subsequent enabled apply reconfigures existing gateway`()
@Test fun `stop is idempotent and clears the owned gateway`()
@Test fun `delivery without gateway keeps existing exception text`()
@Test fun `successful delivery publishes running snapshot with cleared error`()
@Test fun `failed delivery publishes error and rethrows`()
@Test fun `attachment capability delegates without changing state`()
```

For the empty-adapter compatibility case, use an enabled non-blank binding and assert:

```kotlin
assertEquals(
    "No active adapter could start. Check credentials and target IDs.",
    snapshot.lastError
)
```

For missing delivery, assert:

```kotlin
assertEquals(
    "Gateway is not running; cannot deliver outbound message",
    failure.message
)
```

- [ ] **Step 2: Record the deferred RED command**

```bash
./gradlew :app:testDebugUnitTest --tests com.palmclaw.channels.ChannelGatewayLifecycleTest
```

Expected before implementation: compilation failure because the lifecycle contracts do not exist. Do not
execute this command during the current implementation phase.

- [ ] **Step 3: Add the orchestrator control contract**

Create `ChannelGatewayLifecycle.kt` with these declarations:

```kotlin
package com.palmclaw.channels

import com.palmclaw.bus.OutboundMessage
import com.palmclaw.config.SessionChannelBinding

interface GatewayOrchestratorControl {
    val adapterCount: Int
    fun start()
    fun reconfigure(adapters: List<ChannelAdapter>)
    fun stop()
    suspend fun deliverOutboundNow(outbound: OutboundMessage)
    fun resolveOutboundAttachmentCapability(
        outbound: OutboundMessage
    ): ChannelAttachmentCapability?
}

internal fun interface GatewayOrchestratorFactory {
    fun create(adapters: List<ChannelAdapter>): GatewayOrchestratorControl
}

internal data class ChannelGatewayLifecycleSnapshot(
    val running: Boolean = false,
    val adapterCount: Int = 0,
    val lastError: String = ""
)
```

Change only the class declaration in `GatewayOrchestrator.kt` by appending
`: GatewayOrchestratorControl` after the existing constructor parameter list.

Add `override` to `adapterCount`, `start`, `reconfigure`, `stop`, `deliverOutboundNow`, and
`resolveOutboundAttachmentCapability`. Do not modify their bodies.

- [ ] **Step 4: Implement the lifecycle owner**

Add this class after the contracts and preserve the exact compatibility strings:

```kotlin
internal class ChannelGatewayLifecycle(
    private val adapterFactory: ChannelAdapterFactory,
    private val orchestratorFactory: GatewayOrchestratorFactory,
    private val onStateChanged: (ChannelGatewayLifecycleSnapshot) -> Unit = {}
) {
    private var orchestrator: GatewayOrchestratorControl? = null

    fun apply(
        enabled: Boolean,
        bindings: List<SessionChannelBinding>
    ): ChannelGatewayLifecycleSnapshot {
        if (!enabled) return stop()

        val adapters = adapterFactory.create(bindings)
        if (adapters.isEmpty()) {
            stopOwnedOrchestrator()
            val error = if (bindings.any { it.enabled && it.channel.trim().isNotBlank() }) {
                "No active adapter could start. Check credentials and target IDs."
            } else {
                ""
            }
            return publish(running = false, adapterCount = 0, lastError = error)
        }

        val current = orchestrator
        if (current != null) {
            current.reconfigure(adapters)
            return publish(running = true, adapterCount = current.adapterCount, lastError = "")
        }

        val created = orchestratorFactory.create(adapters)
        created.start()
        orchestrator = created
        return publish(running = true, adapterCount = created.adapterCount, lastError = "")
    }

    fun stop(): ChannelGatewayLifecycleSnapshot {
        stopOwnedOrchestrator()
        return publish(running = false, adapterCount = 0, lastError = "")
    }

    suspend fun deliverOutbound(outbound: OutboundMessage) {
        val current = orchestrator
            ?: throw IllegalStateException("Gateway is not running; cannot deliver outbound message")
        try {
            current.deliverOutboundNow(outbound)
            publish(running = true, adapterCount = current.adapterCount, lastError = "")
        } catch (failure: Throwable) {
            publish(
                running = true,
                adapterCount = current.adapterCount,
                lastError = failure.message ?: failure.javaClass.simpleName
            )
            throw failure
        }
    }

    fun resolveOutboundAttachmentCapability(
        outbound: OutboundMessage
    ): ChannelAttachmentCapability? =
        orchestrator?.resolveOutboundAttachmentCapability(outbound)

    private fun stopOwnedOrchestrator() {
        orchestrator?.stop()
        orchestrator = null
    }

    private fun publish(
        running: Boolean,
        adapterCount: Int,
        lastError: String
    ): ChannelGatewayLifecycleSnapshot =
        ChannelGatewayLifecycleSnapshot(running, adapterCount, lastError)
            .also(onStateChanged)
}
```

- [ ] **Step 5: Run static checks and commit**

```bash
git diff --check
rg -n "GatewayOrchestratorControl|ChannelGatewayLifecycleSnapshot|class ChannelGatewayLifecycle" app/src/main app/src/test
rg -n "override (val adapterCount|fun start|fun reconfigure|fun stop|suspend fun deliverOutboundNow|fun resolveOutboundAttachmentCapability)" app/src/main/java/com/palmclaw/channels/GatewayOrchestrator.kt
git add app/src/main/java/com/palmclaw/channels/ChannelGatewayLifecycle.kt app/src/main/java/com/palmclaw/channels/GatewayOrchestrator.kt app/src/test/java/com/palmclaw/channels/ChannelGatewayLifecycleTest.kt
git commit -m "refactor: own channel gateway lifecycle"
```

### Task 4: Migrate GatewayRuntime To The Lifecycle Owner

**Files:**
- Modify: `app/src/main/java/com/palmclaw/runtime/GatewayRuntime.kt:12-35,229-236,360-383,480-497,575-587,1022-1059,1133-1199,1421-1438,1633-1647`

- [ ] **Step 1: Compose the lifecycle with existing runtime callbacks**

Replace `gatewayOrchestrator` and the factory field introduced in Task 2 with one lifecycle field after
`gatewayBus`:

```kotlin
private val channelGatewayLifecycle = ChannelGatewayLifecycle(
    adapterFactory = ConfiguredChannelAdapterFactory(app),
    orchestratorFactory = GatewayOrchestratorFactory { adapters ->
        GatewayOrchestrator(
            bus = gatewayBus,
            agentLoop = agentLoop,
            messageRepository = messageRepository,
            sessionRepository = sessionRepository,
            attachmentTransferService = attachmentTransferService,
            sessionResolver = { inbound -> resolveGatewaySessionBinding(inbound) },
            onSessionProcessingChanged = { sessionId, processing ->
                onGatewaySessionProcessingChanged(sessionId, processing)
            },
            onRemoteDeliveryTurnStarted = ::startRemoteDeliveryTurn,
            onRemoteDeliveryTurnFinished = ::finishRemoteDeliveryTurn,
            wasRemoteDeliverySentInTurn = ::wasRemoteDeliverySentInTurn,
            messageTool = messageTool,
            spawnTool = spawnTool,
            withAgentTurnLock = { sessionId, block ->
                sessionTurnCoordinator.withSessionTurn(normalizeSessionId(sessionId)) { block() }
            },
            adapters = adapters
        )
    },
    onStateChanged = { snapshot ->
        updateState(
            gatewayRunning = snapshot.running,
            activeAdapterCount = snapshot.adapterCount,
            lastError = snapshot.lastError
        )
    }
)
```

The lambda must read mutable `spawnTool` when an orchestrator is created; do not capture a separate early
snapshot of it.

- [ ] **Step 2: Delegate apply while retaining config policy**

Keep the first part of `applyGatewayRuntimeConfig()` unchanged through enabled reconciliation and
persistence. Replace all orchestrator branches and the Task 2 `buildAdapters()` delegate with:

```kotlin
val lifecycleSnapshot = channelGatewayLifecycle.apply(
    enabled = effectiveConfig.enabled,
    bindings = sessionBindings
)
if (!lifecycleSnapshot.running) {
    synchronized(gatewayProcessingSessions) {
        gatewayProcessingSessions.clear()
    }
    updateState()
}
```

The explicit `updateState()` after clearing is required so `processingSessionIds` reflects the cleared set;
the lifecycle callback occurs before that runtime-owned collection is cleared.

Delete `buildAdapters()` entirely. Do not modify `requestGatewayRuntimeConfig()` or
`onGatewaySessionProcessingChanged()`.

- [ ] **Step 3: Delegate delivery and attachment capability**

Replace the owned delivery method with:

```kotlin
suspend fun deliverOutboundViaOwnedGateway(outbound: OutboundMessage) {
    channelGatewayLifecycle.deliverOutbound(outbound)
}
```

Replace the capability lookup with:

```kotlin
private fun isRemoteAttachmentDeliverySupported(outbound: OutboundMessage): Boolean {
    if (outbound.normalizedAttachments.isEmpty()) return true
    val capability = channelGatewayLifecycle.resolveOutboundAttachmentCapability(outbound)
    return capability?.supportsOutboundFiles != false
}
```

- [ ] **Step 4: Delegate shutdown**

In `shutdownRuntime()`, replace direct orchestrator stop and null assignment with:

```kotlin
channelGatewayLifecycle.stop()
pendingGatewayConfig = null
updateState(gatewayRunning = false, activeAdapterCount = 0)
```

Keep the surrounding shutdown order unchanged: MCP runtimes close before the channel lifecycle; message
bus, agent loop, automation services, and runtime scope close afterward.

- [ ] **Step 5: Remove obsolete imports and local types**

`GatewayRuntime.kt` may retain imports for:

```text
ChannelAdapter
ChannelGatewayLifecycle
ConfiguredChannelAdapterFactory
GatewayOrchestrator
GatewayOrchestratorFactory
```

`ChannelAdapter` remains necessary for the orchestrator-factory lambda parameter inference only if Kotlin
requires it; remove it if unused. Remove every concrete adapter, route-rule, Feishu grouping, and
`EmailCredentialKey` reference.

- [ ] **Step 6: Run static checks and commit**

```bash
git diff --check
rg -n "private var gatewayOrchestrator|private fun buildAdapters|private data class EmailCredentialKey" app/src/main/java/com/palmclaw/runtime/GatewayRuntime.kt
rg -n "pendingGatewayConfig|gatewayProcessingSessions|requestGatewayRuntimeConfig|onGatewaySessionProcessingChanged" app/src/main/java/com/palmclaw/runtime/GatewayRuntime.kt
rg -n "channelGatewayLifecycle\.(apply|stop|deliverOutbound|resolveOutboundAttachmentCapability)" app/src/main/java/com/palmclaw/runtime/GatewayRuntime.kt
```

Expected: the first search has no matches. The second and third searches find the retained runtime policy
and new lifecycle delegation.

```bash
git add app/src/main/java/com/palmclaw/runtime/GatewayRuntime.kt
git commit -m "refactor: delegate gateway adapter lifecycle"
```

### Task 5: Add Structural Ownership Guards

**Files:**
- Create: `app/src/test/java/com/palmclaw/runtime/GatewayRuntimeChannelOwnershipTest.kt`

- [ ] **Step 1: Add a repository source helper and ownership test**

Use the same dual-root lookup pattern as `UiStructuralGuardTest`:

```kotlin
private fun sourceFile(vararg candidates: String): File =
    candidates.asSequence().map(::File).firstOrNull(File::exists)
        ?: error("Source file not found: ${candidates.joinToString()}")
```

Add the main ownership assertion:

```kotlin
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
```

- [ ] **Step 2: Guard the retained processing boundary**

Add a separate assertion so future cleanup does not accidentally move agent-turn policy into the channel
lifecycle:

```kotlin
@Test
fun `gateway runtime retains processing aware config deferral`() {
    val source = sourceFile(
        "src/main/java/com/palmclaw/runtime/GatewayRuntime.kt",
        "app/src/main/java/com/palmclaw/runtime/GatewayRuntime.kt"
    ).readText()

    listOf(
        "private val gatewayProcessingSessions",
        "private var pendingGatewayConfig",
        "private fun onGatewaySessionProcessingChanged",
        "private fun requestGatewayRuntimeConfig"
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
```

- [ ] **Step 3: Record the deferred test command**

```bash
./gradlew :app:testDebugUnitTest --tests com.palmclaw.runtime.GatewayRuntimeChannelOwnershipTest
```

Expected after implementation: all structural ownership assertions pass. Do not execute this command
during the current implementation phase.

- [ ] **Step 4: Run static checks and commit**

```bash
git diff --check
rg -n "delegates configured adapter lifecycle|retains processing aware config deferral" app/src/test/java/com/palmclaw/runtime/GatewayRuntimeChannelOwnershipTest.kt
git add app/src/test/java/com/palmclaw/runtime/GatewayRuntimeChannelOwnershipTest.kt
git commit -m "test: guard channel gateway lifecycle ownership"
```

### Task 6: Record The Architecture Boundary And Verification Handoff

**Files:**
- Modify: `docs/engineering/architecture.md:29-31,127`
- Modify: `docs/engineering/roadmap.md:17-18,226-236`
- Modify: `docs/engineering/testing.md:31,243`
- Modify: `docs/superpowers/specs/2026-08-06-channel-adapter-lifecycle-design.md`

- [ ] **Step 1: Update architecture ownership text**

Document these exact responsibilities:

```text
ConfiguredChannelAdapterFactory owns configured binding normalization, credential grouping, and the six
concrete adapter constructors. ChannelGatewayLifecycle owns the optional GatewayOrchestrator and its
create, reconfigure, stop, outbound delivery, and attachment-capability operations. GatewayRuntime keeps
processing-aware config deferral, binding-to-session resolution, agent-turn locking, and top-level runtime
coordination.
```

Replace the statement that `GatewayRuntime` still owns channel adapter lifecycle. Continue to list cron,
heartbeat, MCP, attachment delivery, and remote delivery as remaining focused-service candidates.

- [ ] **Step 2: Advance the roadmap**

Mark adapter lifecycle ownership as implemented pending unified verification. Set the next source phase to
automation wiring, keeping cron and heartbeat together only where they share runtime reload and shutdown
coordination. Keep MCP lifecycle as the following independent phase.

- [ ] **Step 3: Extend the testing matrix**

Add:

```markdown
| Configured channel adapter assembly | `ConfiguredChannelAdapterFactoryTest`, `ChannelAdapterIdentityTest` |
| Channel gateway lifecycle | `ChannelGatewayLifecycleTest`, `GatewayRuntimeChannelOwnershipTest` |
```

Record these deferred device checks:

- change a binding while its session is processing and verify only the last pending config applies after
  processing completes;
- verify the same binding starts in normal and Always-on runtime modes;
- verify outbound text and attachment capability selection remains unchanged;
- verify settings and `session_status` still report the same target and status.

- [ ] **Step 4: Run the complete static verification set**

```bash
git diff --check
rg -n "ConfiguredChannelAdapterFactory|ChannelGatewayLifecycle" app/src/main docs/engineering docs/superpowers/specs
rg -n "private var gatewayOrchestrator|private fun buildAdapters|private data class EmailCredentialKey" app/src/main/java/com/palmclaw/runtime/GatewayRuntime.kt
rg -n "pendingGatewayConfig|gatewayProcessingSessions|requestGatewayRuntimeConfig" app/src/main/java/com/palmclaw/runtime/GatewayRuntime.kt
git status --short
```

Expected: no whitespace errors; the lifecycle types appear in their focused files and documentation; the
forbidden runtime search has no matches; retained deferral symbols remain; status lists only intended
documentation changes for this task.

- [ ] **Step 5: Commit documentation**

```bash
git add docs/engineering/architecture.md docs/engineering/roadmap.md docs/engineering/testing.md docs/superpowers/specs/2026-08-06-channel-adapter-lifecycle-design.md
git commit -m "docs: record channel gateway lifecycle boundary"
```

### Task 7: Prepare Unified Verification Without Running It

**Files:**
- Inspect only: all files changed on `refactor/channel-adapter-lifecycle`

- [ ] **Step 1: Review the stage diff**

```bash
git diff --stat refactor/runtime-ui-observation...HEAD
git diff --check refactor/runtime-ui-observation...HEAD
git log --oneline refactor/runtime-ui-observation..HEAD
git status --short --branch
```

Expected: only channel factory, lifecycle, runtime integration, focused tests, and engineering documentation
are changed; the worktree is clean after all commits.

- [ ] **Step 2: Confirm excluded subsystems were not edited**

```bash
git diff --name-only refactor/runtime-ui-observation...HEAD
```

Expected: no cron, heartbeat, MCP runtime, channel discovery, database schema, or tool schema source files.

- [ ] **Step 3: Record the user's unified JVM/build commands**

Run after all deferred stages are ready for validation:

```bash
./gradlew :app:testDebugUnitTest
./gradlew :app:assembleDebug
```

Expected: `BUILD SUCCESSFUL` for both commands. These commands are intentionally not executed by the
implementation agent during this stage.

- [ ] **Step 4: Record the device matrix**

Verify on device after the build succeeds:

1. Enable one binding for each channel and compare target/status in settings and `session_status`.
2. Start the same configured binding under normal Runtime and Always-on Runtime and compare adapter count
   and connection state.
3. Change channel config during an active inbound turn; confirm the current turn completes and only the
   final pending config is applied.
4. Send outbound text through a uniquely matched adapter and through explicit `adapter_key` metadata.
5. Exercise an attachment-capable and a non-capable channel and confirm the existing rejection behavior.
6. Disable the final active binding and confirm the gateway stops with zero adapters and no stale error.

Do not begin automation wiring until static review of this stage is clean; Gradle and device results may be
reported later as part of the user's unified verification batch.
