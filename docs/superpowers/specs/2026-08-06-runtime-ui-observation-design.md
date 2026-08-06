# Runtime UI Observation And Refresh Boundary Design

Date: 2026-08-06

## Goal

Remove process diagnostic reads, runtime status-flow collection, and broad runtime refresh calls from
`ChatViewModel` without changing gateway behavior, settings presentation, or foreground and Always-on
execution semantics.

This phase continues the existing UI boundary cleanup after runtime tool integration, shared channel
runtime projection, and channel discovery extraction. It deliberately stops before gateway adapter or
automation lifecycle ownership changes.

## Current Problems

`ChatViewModel` still owns three runtime-facing responsibilities:

- it reads `ChannelRuntimeDiagnostics` and five channel-specific diagnostic singletons to build settings
  status text;
- it directly collects foreground and Always-on runtime status flows and coordinates processing-session
  changes;
- it calls gateway, tool, automation, MCP, and full-runtime refresh operations through one broad
  `RuntimeGateway` interface.

These responsibilities make the UI aware of process singletons and runtime command breadth. They also
make status observation difficult to test without constructing a large portion of `ChatViewModel`.

## Scope

This phase includes:

- one process diagnostic snapshot source for settings gateway status;
- one UI assembler that converts that snapshot through the existing `GatewayStatusFormatter`;
- one coordinator that owns foreground and Always-on status collection;
- interface segregation for runtime status, execution, and refresh responsibilities;
- migration of existing refresh calls to the narrow refresh interface;
- focused behavioral, composition-root, and structural tests;
- architecture, roadmap, and testing documentation updates.

This phase excludes:

- changes to `GatewayOrchestrator` ownership;
- adapter creation, refresh, stop, or lease behavior;
- channel network protocols, discovery, routing, or remote delivery;
- cron, heartbeat, or MCP lifecycle extraction from `GatewayRuntime`;
- configuration, database, tool schema, or user-visible status-string changes;
- changes to processing-session refresh policy.

## Gateway Diagnostic Snapshot Boundary

Add `ChannelGatewayDiagnosticsSource` in `com.palmclaw.channels`. It returns a typed immutable
`ChannelGatewayDiagnosticsSnapshot` containing:

- runtime snapshots grouped by channel;
- Discord gateway snapshots;
- Slack gateway snapshots;
- Feishu gateway snapshots;
- Email gateway snapshots;
- WeCom gateway snapshots.

`ProcessChannelGatewayDiagnosticsSource` is the production implementation and the only new component
that reads the process diagnostic singletons. The source does not format UI strings and does not retain
credentials or mutable singleton collections. Every read returns copied collection values representing
one observation.

`GatewayStatusOverviewAssembler` remains in the UI layer. It accepts the diagnostic source, invokes the
existing channel-specific `GatewayStatusFormatter` functions, and returns
`SettingsStateAssembler.GatewayStatuses`. This preserves the current labels and aggregation rules while
removing singleton access from `ChatViewModel`.

The channel discovery diagnostic source remains separate. Discovery has identity-scoped polling needs,
while settings status needs process-wide summaries; combining them would widen both interfaces.

## Runtime Status Observation

Add `RuntimeStatusCoordinator` under `com.palmclaw.ui.settings`. It owns two collectors scoped to the
provided UI coroutine scope:

- foreground `RuntimeControllerStatus`;
- `AlwaysOnRuntimeStatus`.

The coordinator updates `ChatStateStore` with Always-on fields and forwards processing session IDs to the
existing `GatewayProcessingCoordinator`. When that coordinator reports that a gateway refresh is needed,
`RuntimeStatusCoordinator` requests it through the narrow refresh interface.

`start()` is idempotent so repeated UI initialization cannot add duplicate collectors. Collector
cancellation follows the supplied `viewModelScope`; the coordinator does not own a process scope.

The coordinator does not observe channel diagnostics, save settings, or start a runtime. Those concerns
remain in their existing owners.

## Runtime Interface Segregation

Replace the single UI-facing `RuntimeGateway` dependency with three interfaces:

- `RuntimeStatusSource` exposes the foreground and Always-on status flows plus the current Always-on
  snapshot;
- `RuntimeExecutionGateway` owns runtime start, user-message execution, outbound delivery, heartbeat
  trigger, and Always-on configuration application;
- `RuntimeRefreshGateway` owns gateway refresh, tool refresh, automation reload, MCP reload, and full
  reload commands.

`RuntimeApplicationGateway` continues to delegate to the same process-level `RuntimeApplicationService`
and implements all three interfaces. This is interface segregation, not a new lifecycle owner.

`ChatViewModelEnvironment` exposes the three narrow views backed by the same
`RuntimeApplicationGateway` instance. Existing UI coordinators receive method references from
`RuntimeRefreshGateway`; they do not gain runtime implementation knowledge.

The existing runtime-control `RuntimeRefreshPort` remains the configuration-application adapter used by
`RuntimeControlService`. Its UI state updates stay in the UI layer, while its runtime actions delegate to
`RuntimeRefreshGateway`.

## Data Flow

Settings gateway status follows:

```text
process diagnostics
    -> ProcessChannelGatewayDiagnosticsSource
    -> ChannelGatewayDiagnosticsSnapshot
    -> GatewayStatusOverviewAssembler
    -> existing GatewayStatusFormatter
    -> SettingsStateAssembler.GatewayStatuses
```

Runtime flow observation follows:

```text
RuntimeStatusSource
    -> RuntimeStatusCoordinator
    -> Always-on UI state
    -> GatewayProcessingCoordinator
    -> conditional RuntimeRefreshGateway request
```

Settings and processing refreshes follow:

```text
UI coordinator or RuntimeControlService adapter
    -> RuntimeRefreshGateway
    -> RuntimeApplicationGateway
    -> existing RuntimeApplicationService operation
```

## Error And Lifecycle Handling

Diagnostic snapshot reads are synchronous and best-effort, matching current singleton behavior. No new
exception conversion or retry policy is introduced.

Status collectors run in the supplied UI scope. Cancellation is normal and does not produce visible
errors. A collector failure must not start a replacement loop inside the coordinator; the underlying
`StateFlow` producers remain responsible for their own lifecycle.

Refresh calls preserve the current fire-and-forget behavior and invocation order. This phase does not
coalesce refresh requests, add debounce behavior, or change the existing processing-session refresh
decision.

## Compatibility Boundaries

- All gateway status text remains byte-for-byte compatible with existing formatter tests.
- Normal and Always-on operations continue through one `RuntimeApplicationService` instance.
- Settings save and persistence order do not change.
- Runtime start and reload commands retain their current semantics.
- `ChannelDiscoveryService`, `ChannelBindingRuntimeProjector`, and `RuntimeControlService` APIs do not
  change unless a constructor accepts one of the new narrow interfaces.
- `GatewayRuntime` and `GatewayOrchestrator` are not modified in this phase.

## Test Strategy

Add or extend focused tests for:

- process diagnostic snapshot collection for all five channel families;
- status overview assembly using fixed existing status strings;
- foreground processing-session updates;
- Always-on state mapping and processing-session updates;
- duplicate `start()` calls not creating duplicate collectors;
- foreground and Always-on overlap for the same session;
- refresh requests only when `GatewayProcessingCoordinator` requests one;
- cancellation through the supplied scope;
- `RuntimeApplicationGateway` implementing and delegating all three narrow interfaces;
- `AppContainer` and `ChatViewModelEnvironment` sharing one implementation instance;
- structural guards preventing diagnostic singleton reads and direct status collection from returning to
  `ChatViewModel`.

Per the current batch-verification workflow, implementation work runs static checks only. The later
unified verification must run `:app:testDebugUnitTest`, `:app:assembleDebug`, and device checks for
settings gateway status, foreground processing, Always-on processing, overlapping sessions, and each
settings-triggered refresh path.

## Implementation Order

1. Add fixed-vector tests and the diagnostic snapshot contract.
2. Add the process diagnostic source and status overview assembler.
3. Migrate settings gateway status construction out of `ChatViewModel`.
4. Add `RuntimeStatusCoordinator` tests and implementation.
5. Split the UI runtime gateway interfaces and adapt the composition root.
6. Migrate execution and refresh call sites to narrow dependencies.
7. Remove duplicate helpers and direct diagnostic imports.
8. Add structural guards and update engineering documentation.
9. Run approved static checks and leave Gradle/device validation pending for the unified batch.

## Completion Criteria

- `ChatViewModel` does not import process channel diagnostic singletons.
- `ChatViewModel` does not directly collect foreground or Always-on runtime status flows.
- UI refresh calls use `RuntimeRefreshGateway`.
- Runtime execution calls use `RuntimeExecutionGateway`.
- Status observation uses `RuntimeStatusSource` through `RuntimeStatusCoordinator`.
- Existing status strings, save order, processing continuity, and normal or Always-on behavior remain
  unchanged.
- No gateway adapter or automation lifecycle behavior moves in this phase.
