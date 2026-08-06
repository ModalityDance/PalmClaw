# Automation Runtime Lifecycle Design

Date: 2026-08-07

Status: Design approved; implementation and unified verification pending.

## Goal

Move cron and heartbeat lifecycle wiring out of `GatewayRuntime` without moving automation agent-turn
execution, changing persisted configuration, or changing foreground and Always-on behavior.

The extracted boundary owns the current runtime's callback registration, configuration application,
reload delegation, alarm delegation, due-job dispatch, and cleanup. `GatewayRuntime` continues to own
session selection, prompt construction, agent turns, fallback content, and remote delivery.

This phase follows configured channel adapter and gateway lifecycle extraction. MCP lifecycle remains the
next independent `GatewayRuntime` boundary after this work.

## Current Problems

`GatewayRuntime` currently mixes automation lifecycle wiring with automation business execution:

- it registers and clears `CronService.onJob` and registers `CronService.onLog`;
- it converts `CronConfig` and `HeartbeatConfig` into scheduler policy and start or stop calls;
- it delegates heartbeat alarm scheduling and cron due-job or resync processing directly;
- it closes both scheduler services during runtime shutdown;
- it also implements the cron job and heartbeat agent-turn workflows.

The first four responsibilities form a testable lifecycle boundary. The final responsibility depends on
the agent loop, session repository, message tool, channel binding, and delivery state, so moving it would
create another large runtime coordinator.

There is also an ownership mismatch. `AppContainer` creates one process-level `CronService` and
`HeartbeatService`, but `GatewayRuntime.shutdownRuntime()` closes them. In particular,
`CronService.close()` cancels its coroutine scope permanently, while a later runtime created in the same
process receives the same instance. This makes callback cleanup and process-local runtime restart
semantics unreliable.

## Scope

This phase includes:

- a focused `AutomationRuntimeLifecycle` under `com.palmclaw.runtime.automation`;
- narrow cron and heartbeat scheduler ports with production adapters over the existing services;
- runtime callback registration, identity-safe cleanup, and configuration application;
- delegation of heartbeat alarm scheduling and cron due-job or resync processing;
- migration of `GatewayRuntime` automation wiring to the lifecycle;
- focused lifecycle tests and a structural ownership guard;
- architecture, roadmap, and testing documentation updates.

This phase excludes:

- moving cron job agent turns, target-session resolution, fallback messages, or remote delivery;
- moving heartbeat document reading, task parsing, decision prompts, or agent turns;
- changing cron or heartbeat tools, schemas, result text, validation, or persistence order;
- changing AlarmManager, WorkManager, receiver, boot, or Always-on dispatch behavior;
- changing `CronService.sharedOnJob` into a callback registry;
- changing database entities, configuration formats, or scheduling calculations;
- extracting MCP, subagent, attachment-transfer, or remote-delivery lifecycle.

## Scheduler Ports

Add internal scheduler contracts that expose only operations required by runtime lifecycle wiring.
Production adapters delegate to the existing process-level services; unit tests use in-memory fakes.

The cron port supports:

- reading and replacing the job and log callbacks;
- applying policy through `updatePolicy()`;
- `start()` and `stop()`;
- `processDueJobs()` and `onSystemResync()`.

The heartbeat port supports:

- applying enabled state and interval through `updateConfig()`;
- `start()` and `stop()`;
- scheduling an explicit next alarm.

Neither port exposes a permanent `close()` operation. The ports adapt process-owned schedulers; the
runtime lifecycle owns only its binding to them.

`CronService` remains available to the existing cron tool factory. This phase does not broaden the
scheduler port into all cron repository and tool operations merely to eliminate that stable construction
dependency.

## Automation Runtime Lifecycle

Create one `AutomationRuntimeLifecycle` for each automation-enabled `GatewayRuntime`. It receives the two
scheduler ports plus runtime-specific cron job and log callbacks. It stores wrapper function instances so
callback ownership can be checked by identity during cleanup.

Its operations are:

```kotlin
internal class AutomationRuntimeLifecycle {
    fun start(cronConfig: CronConfig, heartbeatConfig: HeartbeatConfig)
    fun reload(cronConfig: CronConfig, heartbeatConfig: HeartbeatConfig)
    fun applyCronConfig(config: CronConfig)
    fun applyHeartbeatConfig(config: HeartbeatConfig)
    fun armNextHeartbeatAlarm(timestampMs: Long)
    suspend fun processDueCronJobs(resync: Boolean)
    fun close()
}
```

`start()` is one-shot for one runtime lifecycle. It registers the cron job callback and log callback before
applying configuration, ensuring a started cron scheduler never observes an unbound runtime callback.
It then applies cron configuration followed by heartbeat configuration, matching the current order.

`reload()` does not register callbacks again. It applies cron configuration and then heartbeat
configuration. The individual apply operations preserve existing flows where a cron tool update or
heartbeat runtime tool update refreshes only its own scheduler.

Cron configuration continues to call `updatePolicy()` first and then `start()` or `stop()` according to
`enabled`. Heartbeat configuration continues to call `updateConfig()` first and then `start()` or
`stop()`. Range coercion, configuration reads performed inside `CronService`, alarm fallback, and service
logging remain in the current concrete services.

`processDueCronJobs(false)` delegates to `processDueJobs()`. The resync form delegates to
`onSystemResync()`. `armNextHeartbeatAlarm()` forwards the timestamp unchanged.

`close()` is terminal and idempotent. It clears each cron callback only when the scheduler still contains
the exact wrapper instance owned by that lifecycle. Therefore delayed cleanup from an old runtime cannot
erase callbacks registered by a newer runtime. Both job and log callbacks are handled; the current log
callback leak is removed.

`close()` does not stop or permanently close the shared schedulers. Enabled OS alarms remain able to wake
a Worker, which starts or obtains the process-wide runtime through `GatewayRuntimeSupervisor` before
processing. Android process death naturally discards the in-process service objects while scheduled OS
alarms remain governed by the existing receiver and Worker paths.

## GatewayRuntime Boundary

`GatewayRuntime` creates an optional lifecycle only when `enableAutomation` is true. Existing disabled
runtime checks and their exact exception messages remain in `triggerHeartbeatNow()`,
`processHeartbeatTick()`, and `processDueCronJobs()`.

Initialization changes from direct wiring and configuration calls to:

```text
construct runtime dependencies and tools
    -> configure agent-loop collaborators
    -> automationLifecycle.start(stored CronConfig, stored HeartbeatConfig)
```

The following direct lifecycle helpers leave `GatewayRuntime`:

- `wireCronCallback()`;
- `wireCronLogging()`;
- `applyCronRuntimeConfig()`;
- `applyHeartbeatRuntimeConfig()`.

The cron callback body remains in `GatewayRuntime` as a clearly named operation such as
`executeCronJob(job)`. It still resolves the target session, builds `CronExecutionPromptBuilder` input,
runs the agent turn, appends fallback content, and mirrors the latest response when delivery is enabled.

The heartbeat agent-turn path remains unchanged. `RuntimeToolIntegration` heartbeat refresh and alarm
ports delegate to the lifecycle, while `triggerNow()` still calls `processHeartbeatTick()`.

Configuration persistence remains in `GatewayRuntime` for the existing cron tool callback. It validates
and saves first, then calls `automationLifecycle.applyCronConfig()`. Heartbeat persistence remains in
`RuntimeControlService`; its refresh port calls `automationLifecycle.applyHeartbeatConfig()` after the
save succeeds.

`reloadAutomationFromStoredConfig()` continues to synchronize built-in tools before delegating both
stored configs to `automationLifecycle.reload()`. Shutdown calls `automationLifecycle.close()` before
the message tool, runtime tool integration, agent loop, and runtime scope are torn down.

Direct cron service access remains only where the unchanged cron tool factory requires it. No scheduler
start, stop, callback, config, alarm, due-job, resync, or close operation remains in `GatewayRuntime`.

## Data Flow

Runtime startup follows:

```text
GatewayRuntime initialization
    -> AutomationRuntimeLifecycle.start
    -> register runtime-owned cron job and log callbacks
    -> apply stored CronConfig
    -> apply stored HeartbeatConfig
```

Settings and runtime-tool refresh follows:

```text
validate and persist config
    -> GatewayRuntime refresh callback
    -> AutomationRuntimeLifecycle apply or reload
    -> scheduler update policy or config
    -> scheduler start or stop
```

Cron alarm execution follows:

```text
CronDispatchWorker
    -> GatewayRuntimeSupervisor ensures one runtime
    -> GatewayRuntime.processDueCronJobs
    -> AutomationRuntimeLifecycle.processDueCronJobs
    -> CronService invokes the registered callback
    -> GatewayRuntime cron agent-turn workflow
```

Heartbeat alarm execution follows the existing path:

```text
HeartbeatDispatchWorker
    -> GatewayRuntimeSupervisor ensures one runtime
    -> GatewayRuntime.processHeartbeatTick
    -> existing HEARTBEAT.md decision and agent-turn workflow
    -> Worker applies latest config and schedules the next alarm
```

Runtime teardown follows:

```text
GatewayRuntime.shutdownRuntime
    -> AutomationRuntimeLifecycle.close
    -> identity-safe cron callback cleanup
    -> remaining runtime-owned callback and resource cleanup
```

## Error Handling and Compatibility

- Cron and heartbeat configuration application retains the current operation order.
- Scheduler validation, coercion, exact-alarm fallback, logging, and exceptions remain unchanged.
- Cron due-job and resync exceptions continue to reach `CronDispatchWorker`, which retains its retry
  policy.
- Cron agent failure, fallback assistant content, notification fallback, and remote-delivery handling keep
  their current behavior and text.
- Heartbeat disabled errors, automation-disabled errors, empty-document behavior, skip decisions, and
  success text remain unchanged.
- Closing a lifecycle more than once has no effect after its callbacks are detached.
- Closing an older lifecycle cannot clear a newer lifecycle's callbacks.
- `CronService.sharedOnJob` remains as the existing process-level compatibility mechanism. Replacing it
  with multi-owner registration is deferred because the supervisor already enforces one active runtime.
- No tool schema, configuration model, database record, scheduling expression, Worker input, receiver
  action, or public runtime gateway changes.

## Test Strategy

Add `AutomationRuntimeLifecycleTest` with fake scheduler ports covering:

- callback registration occurs before initial configuration and scheduler start;
- startup and reload apply cron before heartbeat;
- enabled configuration starts and disabled configuration stops each scheduler;
- reload changes config without registering a second callback;
- cron due processing selects normal or resync delegation correctly;
- heartbeat alarm timestamps are forwarded unchanged;
- cron job and log callbacks forward to the runtime-provided handlers;
- close clears both callbacks and is idempotent;
- an old lifecycle close does not clear callbacks owned by a newer lifecycle;
- close performs no permanent scheduler close.

Add `GatewayRuntimeAutomationOwnershipTest` as a structural guard. It prohibits direct scheduler callback
assignment and direct cron or heartbeat start, stop, configuration, alarm, due-job, resync, or close calls
inside `GatewayRuntime`. It requires lifecycle construction and delegation while also requiring cron job
agent execution, heartbeat document parsing, and heartbeat agent execution to remain in the runtime.

Extend existing runtime or supervisor characterization tests only where needed to confirm public reload,
heartbeat, and cron entry points retain their current behavior. Existing cron repository, prompt-builder,
runtime-tool, and supervisor tests remain authoritative for their current boundaries.

Implementation follows the deferred-validation workflow. During source implementation, run static
searches and `git diff --check` only. Later unified verification runs `:app:testDebugUnitTest`,
`:app:assembleDebug`, and foreground or Always-on device checks covering cron and heartbeat configuration,
manual triggering, alarm dispatch, runtime stop and restart, single callback ownership, fallback behavior,
and next-alarm persistence.

## Completion Criteria

- `AutomationRuntimeLifecycle` is the only runtime owner of cron and heartbeat scheduler wiring.
- `GatewayRuntime` performs no direct scheduler lifecycle, callback, alarm, or due-job operations.
- Cron job and heartbeat agent-turn workflows remain in `GatewayRuntime`.
- Config persistence order, tool contracts, Worker behavior, status text, fallback behavior, and delivery
  semantics remain unchanged.
- Runtime shutdown detaches both cron callbacks without permanently closing process-owned schedulers.
- A new runtime in the same process can register fresh callbacks and operate the existing schedulers.
- Focused lifecycle tests and structural guards cover the new ownership boundary.
- MCP lifecycle remains untouched for the next independent phase.
