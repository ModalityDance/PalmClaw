# Runtime UI Observation And Refresh Boundary Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Remove process diagnostic reads, runtime status collection, and broad refresh dependencies from `ChatViewModel` while preserving every existing runtime and UI behavior.

**Architecture:** A process diagnostic source returns one immutable typed snapshot, and a UI assembler passes it through the existing formatter. A scoped coordinator collects foreground and Always-on status flows. The current runtime application adapter implements separate status, execution, and refresh interfaces backed by the same `RuntimeApplicationService`.

**Tech Stack:** Kotlin, Android lifecycle `viewModelScope`, Kotlin coroutines and `StateFlow`, JUnit 4, existing PalmClaw runtime and UI state APIs.

**Verification constraint:** Add test sources before production changes, but do not run Gradle during implementation. The user will later run `:app:testDebugUnitTest` and `:app:assembleDebug` as one unified validation batch. Run only `git diff --check`, `rg`, source inspections, and Git status during this phase.

---

### Task 1: Add The Process Gateway Diagnostic Snapshot Contract

**Files:**
- Create: `app/src/main/java/com/palmclaw/channels/ChannelGatewayDiagnosticsSource.kt`
- Create: `app/src/test/java/com/palmclaw/channels/ChannelGatewayDiagnosticsSourceTest.kt`

- [ ] **Step 1: Write the snapshot-source test**

Add a test that uses unique adapter keys, seeds one entry in `ChannelRuntimeDiagnostics` and each gateway
diagnostic singleton, calls the process source, and verifies all six collections contain those entries.
Use each singleton's existing per-key reset API before seeding. Do not assert global collection sizes,
because process diagnostics intentionally retain other adapter-key snapshots.

The contract under test is:

```kotlin
data class ChannelGatewayDiagnosticsSnapshot(
    val runtimeSnapshotsByChannel: Map<String, List<ChannelRuntimeSnapshot>>,
    val discordSnapshots: List<DiscordGatewaySnapshot>,
    val slackSnapshots: List<SlackGatewaySnapshot>,
    val feishuSnapshots: List<FeishuGatewaySnapshot>,
    val emailSnapshots: List<EmailGatewaySnapshot>,
    val weComSnapshots: List<WeComGatewaySnapshot>
) {
    fun runtimeSnapshots(channel: String): List<ChannelRuntimeSnapshot> =
        runtimeSnapshotsByChannel[channel.trim().lowercase(Locale.US)].orEmpty()
}

fun interface ChannelGatewayDiagnosticsSource {
    fun snapshot(): ChannelGatewayDiagnosticsSnapshot
}
```

- [ ] **Step 2: Record deferred RED command**

Later unified verification command:

```bash
./gradlew :app:testDebugUnitTest --tests com.palmclaw.channels.ChannelGatewayDiagnosticsSourceTest
```

Expected before implementation: compilation failure because the source types do not exist.

- [ ] **Step 3: Implement the source**

Create the contract above and `ProcessChannelGatewayDiagnosticsSource`. Read runtime snapshots for
`discord`, `slack`, `feishu`, `email`, and `wecom`, and copy every singleton map value with `toList()`.
Do not return mutable singleton maps and do not format status text.

- [ ] **Step 4: Run static checks**

```bash
git diff --check
rg -n "ProcessChannelGatewayDiagnosticsSource|runtimeSnapshotsByChannel" app/src/main app/src/test
```

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/palmclaw/channels/ChannelGatewayDiagnosticsSource.kt app/src/test/java/com/palmclaw/channels/ChannelGatewayDiagnosticsSourceTest.kt
git commit -m "refactor: centralize gateway diagnostic snapshots"
```

### Task 2: Assemble Settings Gateway Status Outside ChatViewModel

**Files:**
- Create: `app/src/main/java/com/palmclaw/ui/chat/GatewayStatusOverviewAssembler.kt`
- Create: `app/src/test/java/com/palmclaw/ui/GatewayStatusOverviewAssemblerTest.kt`
- Modify: `app/src/main/java/com/palmclaw/AppContainer.kt`
- Modify: `app/src/main/java/com/palmclaw/ui/chat/ChatViewModelEnvironment.kt`
- Modify: `app/src/main/java/com/palmclaw/ui/chat/ChatViewModel.kt`

- [ ] **Step 1: Write fixed-vector assembler tests**

Use a fake `ChannelGatewayDiagnosticsSource` and verify the assembler returns all five fields. Include
at least one exact multiline assertion so status labels remain unchanged:

```kotlin
val result = GatewayStatusOverviewAssembler(source).build()

assertEquals(
    "Adapters: 1\nRunning: 1\nConnected: 1\nReady: 1\n" +
        "Inbound seen: 2\nInbound forwarded: 1\nOutbound sent: 3",
    result.feishu
)
```

Also verify one source read per `build()` call.

- [ ] **Step 2: Record deferred RED command**

```bash
./gradlew :app:testDebugUnitTest --tests com.palmclaw.ui.GatewayStatusOverviewAssemblerTest
```

Expected before implementation: compilation failure because the assembler does not exist.

- [ ] **Step 3: Implement the assembler**

```kotlin
internal class GatewayStatusOverviewAssembler(
    private val diagnosticsSource: ChannelGatewayDiagnosticsSource
) {
    fun build(): SettingsStateAssembler.GatewayStatuses {
        val snapshot = diagnosticsSource.snapshot()
        return SettingsStateAssembler.GatewayStatuses(
            discord = GatewayStatusFormatter.buildDiscordStatus(
                snapshot.runtimeSnapshots("discord"), snapshot.discordSnapshots
            ),
            slack = GatewayStatusFormatter.buildSlackStatus(
                snapshot.runtimeSnapshots("slack"), snapshot.slackSnapshots
            ),
            feishu = GatewayStatusFormatter.buildFeishuStatus(
                snapshot.runtimeSnapshots("feishu"), snapshot.feishuSnapshots
            ),
            email = GatewayStatusFormatter.buildEmailStatus(
                snapshot.runtimeSnapshots("email"), snapshot.emailSnapshots
            ),
            wecom = GatewayStatusFormatter.buildWeComStatus(
                snapshot.runtimeSnapshots("wecom"), snapshot.weComSnapshots
            )
        )
    }
}
```

- [ ] **Step 4: Wire and migrate**

Construct one process source and assembler in `AppContainer`, expose the assembler through
`ChatViewModelEnvironment`, and change `loadSettingsIntoState()` to call `gatewayStatusOverviewAssembler.build()`.
Delete the five local status-building functions and their diagnostic imports from `ChatViewModel`.

- [ ] **Step 5: Run static checks**

```bash
git diff --check
rg -n "GatewayDiagnostics|ChannelRuntimeDiagnostics" app/src/main/java/com/palmclaw/ui/chat/ChatViewModel.kt
```

Expected: no matches.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/palmclaw/AppContainer.kt app/src/main/java/com/palmclaw/ui/chat/ChatViewModelEnvironment.kt app/src/main/java/com/palmclaw/ui/chat/ChatViewModel.kt app/src/main/java/com/palmclaw/ui/chat/GatewayStatusOverviewAssembler.kt app/src/test/java/com/palmclaw/ui/GatewayStatusOverviewAssemblerTest.kt
git commit -m "refactor: assemble gateway status outside view model"
```

### Task 3: Split The UI Runtime Gateway Interfaces

**Files:**
- Modify: `app/src/main/java/com/palmclaw/ui/domain/UiDomainServices.kt`
- Modify: `app/src/main/java/com/palmclaw/AppContainer.kt`
- Modify: `app/src/main/java/com/palmclaw/ui/chat/ChatViewModelEnvironment.kt`
- Create: `app/src/test/java/com/palmclaw/ui/RuntimeGatewayContractTest.kt`
- Modify: `app/src/test/java/com/palmclaw/AppContainerCompositionRootTest.kt`

- [ ] **Step 1: Write delegation and identity tests**

Add a contract test that verifies `RuntimeApplicationGateway` implements all three interfaces with
`Class.isAssignableFrom`, and add a compile-time helper showing that one instance is assignable to:

```kotlin
val statusSource: RuntimeStatusSource = gateway
val executionGateway: RuntimeExecutionGateway = gateway
val refreshGateway: RuntimeRefreshGateway = gateway
```

The existing `RuntimeApplicationServiceTest` retains behavioral command-delegation coverage. Extend the
composition-root structural test to assert `ChatViewModelDependencies` receives the same container adapter
for all three typed properties.

- [ ] **Step 2: Record deferred RED command**

```bash
./gradlew :app:testDebugUnitTest --tests com.palmclaw.ui.RuntimeGatewayContractTest --tests com.palmclaw.AppContainerCompositionRootTest
```

Expected before implementation: compilation failure because the narrow interfaces do not exist.

- [ ] **Step 3: Define the interfaces**

Move the current members without changing signatures:

```kotlin
interface RuntimeStatusSource {
    val runtimeStatus: StateFlow<RuntimeControllerStatus>
    val alwaysOnStatus: StateFlow<AlwaysOnRuntimeStatus>
    fun currentAlwaysOnStatus(): AlwaysOnRuntimeStatus
}

interface RuntimeExecutionGateway {
    fun startGatewayIfEnabled()
    fun applyAlwaysOnConfig(config: AlwaysOnConfig)
    suspend fun publishOutbound(outbound: OutboundMessage)
    suspend fun runUserMessage(
        sessionId: String,
        sessionTitle: String,
        text: String,
        attachments: List<MessageAttachment> = emptyList()
    )
    suspend fun triggerHeartbeatNow(): String
}

interface RuntimeRefreshGateway {
    fun refreshGatewayRuntimeConfig()
    fun refreshToolRuntimeConfig()
    fun reloadAutomation()
    fun reloadMcp()
    fun reloadAll()
}
```

Make `RuntimeApplicationGateway` implement all three. Remove the old `RuntimeGateway` interface after all
call sites compile conceptually.

- [ ] **Step 4: Expose one adapter through three typed properties**

`AppContainer` owns one `RuntimeApplicationGateway` instance. Change `ChatViewModelDependencies` to carry
`runtimeStatusSource`, `runtimeExecutionGateway`, and `runtimeRefreshGateway`, passing the same adapter for
all three. `ChatViewModelEnvironment` exposes those typed properties; do not construct three adapters.

- [ ] **Step 5: Run static checks and commit**

```bash
git diff --check
rg -n "interface RuntimeGateway|val runtimeGateway" app/src/main app/src/test
```

Update remaining tests or fakes to use the narrow interface appropriate to each consumer, then commit:

```bash
git add app/src/main/java/com/palmclaw/ui/domain/UiDomainServices.kt app/src/main/java/com/palmclaw/AppContainer.kt app/src/main/java/com/palmclaw/ui/chat/ChatViewModelEnvironment.kt app/src/test/java/com/palmclaw/ui/RuntimeGatewayContractTest.kt app/src/test/java/com/palmclaw/AppContainerCompositionRootTest.kt
git commit -m "refactor: split ui runtime gateway interfaces"
```

### Task 4: Move Runtime Flow Collection Into A Scoped Coordinator

**Files:**
- Create: `app/src/main/java/com/palmclaw/ui/settings/RuntimeStatusCoordinator.kt`
- Create: `app/src/test/java/com/palmclaw/ui/RuntimeStatusCoordinatorTest.kt`
- Modify: `app/src/main/java/com/palmclaw/ui/chat/ChatViewModel.kt`
- Modify: `app/src/main/java/com/palmclaw/ui/settings/RuntimeCoordinator.kt`
- Modify: `app/src/test/java/com/palmclaw/ui/CoordinatorDelegationTest.kt`

- [ ] **Step 1: Write coordinator behavior tests**

Use `MutableStateFlow`, a test-owned `CoroutineScope`, `ChatStateStore`, and a real
`GatewayProcessingCoordinator`. Cover:

```kotlin
coordinator.start()
coordinator.start() // idempotent

runtimeStatus.value = RuntimeControllerStatus(processingSessionIds = setOf("foreground"))
alwaysOnStatus.value = AlwaysOnRuntimeStatus(
    serviceRunning = true,
    notificationActive = true,
    gatewayRunning = true,
    activeAdapterCount = 2,
    startedAtMs = 42L,
    lastError = "",
    processingSessionIds = setOf("always-on")
)
```

Assert Always-on UI mapping, merged processing state, one refresh after a deferred request becomes idle,
no duplicate refresh from duplicate `start()`, and no updates after scope cancellation.

- [ ] **Step 2: Record deferred RED command**

```bash
./gradlew :app:testDebugUnitTest --tests com.palmclaw.ui.RuntimeStatusCoordinatorTest
```

Expected before implementation: compilation failure because the coordinator does not exist.

- [ ] **Step 3: Implement the coordinator**

Use an idempotent `started` flag and two `scope.launch` collectors. Map the exact current fields into
`stateStore.updateAlwaysOnState`. For both flows, pass processing IDs to `GatewayProcessingCoordinator`;
invoke `runtimeRefreshGateway.refreshGatewayRuntimeConfig()` only when `shouldRefreshGateway` is true.

- [ ] **Step 4: Migrate ChatViewModel**

Construct `RuntimeStatusCoordinator` beside the existing coordinators, start it from the same initialization
path, and remove `observeRuntimeStatus()`, `observeAlwaysOnStatus()`, and `onGatewayProcessingUpdate()`.
Change `RuntimeCoordinator.Actions` so it starts the new coordinator through one action instead of two
observer actions.

- [ ] **Step 5: Run static checks and commit**

```bash
git diff --check
rg -n "runtimeStatus\.collectLatest|alwaysOnStatus\.collectLatest|fun observeRuntimeStatus|fun observeAlwaysOnStatus" app/src/main/java/com/palmclaw/ui/chat/ChatViewModel.kt
```

Expected: no matches.

```bash
git add app/src/main/java/com/palmclaw/ui/settings/RuntimeStatusCoordinator.kt app/src/main/java/com/palmclaw/ui/settings/RuntimeCoordinator.kt app/src/main/java/com/palmclaw/ui/chat/ChatViewModel.kt app/src/test/java/com/palmclaw/ui/RuntimeStatusCoordinatorTest.kt app/src/test/java/com/palmclaw/ui/CoordinatorDelegationTest.kt
git commit -m "refactor: coordinate runtime status observation"
```

### Task 5: Migrate Execution And Refresh Calls To Narrow Dependencies

**Files:**
- Modify: `app/src/main/java/com/palmclaw/ui/chat/ChatViewModel.kt`
- Modify: affected tests under `app/src/test/java/com/palmclaw/ui/`

- [ ] **Step 1: Add structural expectations**

Before production edits, extend structural tests to require these properties in `ChatViewModel`:

```text
runtimeExecutionGateway
runtimeRefreshGateway
runtimeStatusCoordinator
```

Forbid `private val runtimeGateway`, direct status collection, and the old local reload wrappers.

- [ ] **Step 2: Migrate execution calls**

Use `runtimeExecutionGateway` for gateway start, Always-on apply, outbound publish, user-message execution,
and heartbeat trigger. Preserve argument order and coroutine context.

- [ ] **Step 3: Migrate refresh calls**

Use `runtimeRefreshGateway` for gateway, tool, automation, MCP, and full reload operations. Keep
`requestGatewayRuntimeRefresh()` and `GatewayProcessingCoordinator.requestGatewayRefresh()` semantics;
only replace the final command target.

The UI-owned runtime-control adapter remains:

```kotlin
private val runtimeControlRefreshPort = object : RuntimeRefreshPort {
    override fun applyHeartbeatConfig(config: HeartbeatConfig) {
        runtimeRefreshGateway.reloadAutomation()
    }

    override fun applyChannelsConfig(config: ChannelsConfig) {
        refreshSessionBindingsInState()
        requestGatewayRuntimeRefresh()
        _uiState.updateChannelsSettingsState { it.copy(gatewayEnabled = config.enabled) }
    }
}
```

- [ ] **Step 4: Remove wrappers and imports**

Delete `refreshGatewayRuntimeConfig()`, `reloadAutomationViaActiveRuntime()`,
`reloadMcpViaActiveRuntime()`, and `reloadAllViaActiveRuntime()` once no call sites remain. Remove obsolete
`StateFlow` collection imports only when unused elsewhere.

- [ ] **Step 5: Run static checks and commit**

```bash
git diff --check
rg -n "runtimeGateway|ViaActiveRuntime|private fun refreshGatewayRuntimeConfig" app/src/main/java/com/palmclaw/ui/chat/ChatViewModel.kt
```

Expected: no matches.

```bash
git add app/src/main/java/com/palmclaw/ui/chat/ChatViewModel.kt app/src/test/java/com/palmclaw/ui
git commit -m "refactor: narrow runtime ui dependencies"
```

### Task 6: Add Structural Guards And Documentation

**Files:**
- Modify: `app/src/test/java/com/palmclaw/ui/UiStructuralGuardTest.kt`
- Modify: `app/src/test/java/com/palmclaw/AppContainerCompositionRootTest.kt`
- Modify: `docs/engineering/architecture.md`
- Modify: `docs/engineering/roadmap.md`
- Modify: `docs/engineering/testing.md`

- [ ] **Step 1: Add final structural guards**

Assert that `ChatViewModel` does not contain:

```text
ChannelRuntimeDiagnostics
DiscordGatewayDiagnostics
SlackGatewayDiagnostics
FeishuGatewayDiagnostics
EmailGatewayDiagnostics
WeComGatewayDiagnostics
runtimeStatus.collectLatest
alwaysOnStatus.collectLatest
private val runtimeGateway
```

Assert that `AppContainer` constructs one `RuntimeApplicationGateway` and one
`ProcessChannelGatewayDiagnosticsSource`, and that the environment exposes narrow typed properties.

- [ ] **Step 2: Update documentation**

Record that runtime status observation and refresh dependencies have moved out of `ChatViewModel`, but
compilation and device verification remain pending. Set the next boundary to gateway adapter lifecycle
ownership, then automation wiring, then MCP lifecycle.

- [ ] **Step 3: Run approved verification**

```bash
git diff --check
rg -n "GatewayDiagnostics|ChannelRuntimeDiagnostics|runtimeStatus\.collectLatest|alwaysOnStatus\.collectLatest" app/src/main/java/com/palmclaw/ui/chat/ChatViewModel.kt
git status --short --branch
```

Expected: clean diff check, no forbidden matches, and only intended files modified.

- [ ] **Step 4: Record deferred unified verification**

Do not run these during implementation:

```bash
./gradlew :app:testDebugUnitTest
./gradlew :app:assembleDebug
```

Device checks must compare settings gateway status, foreground processing, Always-on processing,
overlapping sessions, channel or skill or tool or MCP refreshes, and the preceding channel discovery
workflows.

- [ ] **Step 5: Commit**

```bash
git add app/src/test/java/com/palmclaw/ui/UiStructuralGuardTest.kt app/src/test/java/com/palmclaw/AppContainerCompositionRootTest.kt docs/engineering/architecture.md docs/engineering/roadmap.md docs/engineering/testing.md
git commit -m "docs: record runtime ui boundary cleanup"
```
