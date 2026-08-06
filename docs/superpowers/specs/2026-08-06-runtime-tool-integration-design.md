# Runtime Tool Integration Boundary Design

Date: 2026-08-06

## Status

Implemented in source; compilation, automated checks, and manual runtime verification remain pending. This design is limited to a behavior-preserving ownership refactor.

## Context

PalmClaw currently implements runtime-owned agent tool behavior in both `ChatViewModel` and
`GatewayRuntime`. The duplicated surface covers runtime settings, heartbeat control, session
listing and delivery, channel binding state, and MCP status. Both classes build tool snapshots,
perform related mutations, resolve sessions, and coordinate runtime refreshes.

This duplication creates two risks:

- Foreground UI behavior and Always-on runtime behavior can diverge.
- Validation, persistence order, status projection, and error text can drift as either copy changes.

The first refactor must preserve all user-visible and agent-visible behavior. It is not an
opportunity to redesign tools, UI state, persistence, channel behavior, or concurrency.

## Goals

- Establish one implementation owner for runtime settings, heartbeat, sessions, channel bindings,
  and MCP status.
- Ensure foreground and Always-on execution use the same query and mutation rules.
- Remove runtime tool construction, snapshots, and agent-tool behavior from `ChatViewModel`.
- Remove runtime tool callback wiring and duplicate query or mutation logic from `GatewayRuntime`.
- Keep each runtime side effect behind a narrow, testable interface.
- Preserve existing tool contracts, UI behavior, persistence formats, and refresh order.

## Non-Goals

- Changing tool names, JSON schemas, response fields, or error messages.
- Changing the database schema, configuration formats, or workspace layout.
- Refactoring Cron tools, Android tools, channel discovery UI, `AgentLoop`, or general tool
  registration.
- Adding caches, retries, flows, locks, concurrency rules, or new runtime states.
- Redesigning MCP dynamic tool registration.
- Completing the broader `ChatViewModel` or `GatewayRuntime` decomposition in the same change.

## Scope

The integration owns these ten tools:

- `RuntimeGetTool` and `RuntimeSetTool`
- `HeartbeatGetTool`, `HeartbeatSetTool`, and `HeartbeatTriggerTool`
- `SessionsListTool` and `SessionsSendTool`
- `ChannelsGetTool` and `ChannelsSetTool`
- `McpStatusTool`

MCP server-provided tools remain owned by the existing MCP runtime. Cron and Android capability
tools remain outside this boundary.

## Architecture

```text
ChatViewModel
    |  UI commands and presentation only
    v
RuntimeControlService
    |  one owner for queries, validation, persistence, and side effects
    v
Config, session, channel, heartbeat, and MCP dependencies
    ^
RuntimeToolIntegration
    |  existing Tool request/response adaptation
    v
GatewayRuntime -> ToolRegistry -> AgentLoop
```

### RuntimeControlService

`RuntimeControlService` is the shared application-level behavior owner. It does not depend on
Compose, `ChatViewModel`, `ToolRegistry`, or concrete Tool classes.

It owns:

- Runtime settings snapshots and validated updates.
- Heartbeat snapshots, document updates, schedule refresh, and immediate triggering.
- Session lookup, ordered session snapshots, and cross-session delivery.
- Channel binding snapshots, status projection, and enabled-state mutation.
- MCP status snapshots built from stored configuration and live runtime status.

The service uses domain-level commands and results. Tool DTO conversion belongs to
`RuntimeToolIntegration`; UI text parsing and presentation belong to `ChatViewModel`.

### RuntimeToolIntegration

`RuntimeToolIntegration` is a runtime-scoped adapter. It creates and owns the ten concrete tool
instances, binds their callbacks to `RuntimeControlService`, and exposes a read-only `List<Tool>`
for registration by `GatewayRuntime`.

Its lifecycle follows one `GatewayRuntime` instance. Shutdown releases callbacks and references so
an obsolete runtime cannot retain repositories or runtime ports.

The integration contains no duplicated validation, persistence, session resolution, channel
status rules, or MCP status rules. It only maps existing tool request and response models to the
service API.

### Runtime Ports

Runtime-only side effects are injected through narrow interfaces instead of passing
`GatewayRuntime` into the service:

- `RuntimeRefreshPort`: applies stored runtime or gateway configuration through the current
  runtime owner.
- `HeartbeatTriggerPort`: starts an immediate heartbeat through the shared runtime path.
- `ActiveSessionSource`: reports the active agent session used in session snapshots.
- `SessionDeliveryPort`: performs optional remote delivery after the local session message is
  persisted.
- `ChannelRuntimeStatusSource`: provides live adapter status used by channel binding snapshots.
- `McpRuntimeStatusSource`: provides current MCP server and registered-tool status.

Existing concrete services may implement these interfaces directly. Adapter classes should be
introduced only where an existing owner cannot implement a port without taking on unrelated
responsibilities.

## Data Flow

### Agent Tool Query

1. `AgentLoop` dispatches an existing tool call through `ToolRegistry`.
2. `RuntimeToolIntegration` maps the tool request to a service call.
3. `RuntimeControlService` reads stored and live state through its dependencies.
4. The integration maps the domain result to the existing tool snapshot.
5. The tool returns the same structured result as before.

### Agent Tool Mutation

1. The integration maps the existing tool request to a domain command.
2. `RuntimeControlService` runs the current validation rules.
3. It persists changes in the current order.
4. It invokes the same runtime refresh or trigger side effect in the current order.
5. It reloads the current result where the existing implementation does so.
6. The integration returns the existing tool result shape.

### UI Mutation

1. `ChatViewModel` parses UI text fields and manages `saving`, `info`, and other presentation state.
2. It sends a domain command to `RuntimeControlService` rather than constructing a Tool request.
3. The service executes the same rules used by the agent tool path.
4. `ChatViewModel` reloads existing UI state and presents success or failure using the current
   strings and timing.

## Behavior Preservation

The refactor preserves:

- Numeric limits and validation messages.
- Session ID and title matching, including ambiguous-title failures.
- Local-session insertion and session ordering.
- Channel target normalization and runtime status labels.
- Binding enablement rules and automatic gateway enabled-state calculation.
- Local persistence before optional remote session delivery.
- WeCom delivery notes and current remote-delivery failure behavior.
- Runtime configuration, heartbeat scheduling, and gateway refresh order.
- MCP fallback configuration and status aggregation.
- Tool timeout and structured error conversion in `ToolRegistry`.

No new exception translation is added to the service. Agent calls continue through the existing
tool and registry conversion path. UI calls continue to map failures to existing presentation
messages in `ChatViewModel`.

## Lifecycle

`RuntimeToolIntegration` is created with the active `GatewayRuntime`, not as an independent global
runtime owner. `GatewayRuntimeSupervisor` remains the process-level owner that prevents multiple
active runtimes.

Initialization order remains:

1. Construct core runtime dependencies.
2. Construct `RuntimeControlService` with repositories, stores, and runtime ports.
3. Construct `RuntimeToolIntegration`.
4. Register its enabled tools in the existing `ToolRegistry` flow.
5. Start channel, automation, and MCP lifecycle through existing owners.

Shutdown detaches integration callbacks before runtime-owned dependencies are released. Dynamic MCP
tool cleanup remains in the existing MCP lifecycle.

## Migration Sequence

Each capability is migrated vertically so the UI path and agent path switch to the shared service
together. The old implementation for that capability is removed in the same step.

1. Add characterization coverage for snapshots, validation, errors, session matching, and refresh
   ordering.
2. Introduce the service, domain models, and runtime ports without changing callers.
3. Migrate runtime settings.
4. Migrate heartbeat settings, document mutation, and immediate triggering.
5. Migrate session listing, resolution, local persistence, and optional remote delivery.
6. Migrate channel binding snapshots, status projection, and enabled-state mutation.
7. Migrate MCP status projection.
8. Move all ten tool instances and callbacks into `RuntimeToolIntegration`.
9. Remove obsolete methods and imports from `ChatViewModel` and `GatewayRuntime`.
10. Add structural guards that prevent runtime Tool classes from returning to `ChatViewModel`.

Runtime settings and heartbeat are migrated first because their dependency surfaces are smaller.
Sessions and channels follow because they share session resolution and delivery state. MCP is last
because it combines stored configuration with live runtime-owned status.

## Verification Strategy

### Characterization Tests

Before moving behavior, tests record the current output and side-effect order for:

- Complete and partial runtime setting updates, including every numeric boundary.
- Heartbeat preservation, document replacement, schedule refresh, and manual trigger.
- Session ordering, local-session fallback, exact and ambiguous title matching, and remote delivery.
- Channel status projection for each supported channel and gateway state.
- Channel enablement mutations and resulting gateway configuration.
- MCP disabled, disconnected, partially connected, and connected snapshots.

### Focused Unit Tests

- `RuntimeControlServiceTest` uses fake repositories, stores, clocks where already required, and
  fake runtime ports.
- `RuntimeToolIntegrationTest` verifies all ten callbacks delegate once and preserve request and
  response values.
- Existing tool schema and argument validation tests remain unchanged.
- Runtime ownership tests verify normal and Always-on entry points use the shared behavior owner.
- Structural tests reject imports of the ten runtime Tool classes from `ChatViewModel`.

### Manual Verification

The maintainer performs project compilation, the full unit suite, and relevant device checks. This
design does not change the repository's existing commands or manual QA requirements.

## Acceptance Criteria

- The ten tools retain identical names, schemas, structured results, and error semantics.
- Foreground and Always-on execution use the same `RuntimeControlService` behavior.
- `ChatViewModel` does not import or construct any of the ten runtime Tool classes.
- `ChatViewModel` does not build agent tool snapshots or implement agent session delivery and
  channel binding mutations.
- `GatewayRuntime` does not construct the ten tools, bind their callbacks, build their snapshots,
  or duplicate their validation and persistence logic.
- `GatewayRuntime` remains the agent-turn and top-level runtime lifecycle coordinator.
- Existing configuration formats, database schema, UI state, and channel behavior are unchanged.
- Each migrated mutation has focused coverage for persistence and side-effect ordering.
- Structural guards make ownership regressions visible during local verification.

Moving approximately 700 to 1,000 lines out of `ChatViewModel` and `GatewayRuntime` is an expected
effect, not an acceptance criterion.

## Risks and Controls

- **Hidden behavior differences between the two copies:** Characterization tests establish the
  chosen current contract before removal. Any genuine difference is documented and resolved as a
  separate behavior change rather than silently selected during this refactor.
- **A new oversized service:** Keep tool adaptation and presentation outside the service, group
  internal operations by capability, and split only when a capability has an independently useful
  lifecycle or dependency set.
- **Runtime reference leaks:** Keep the integration runtime-scoped and detach callbacks during
  shutdown.
- **Incorrect refresh ordering:** Test persistence and port invocation order for every mutation.
- **MCP lifecycle coupling:** Move only status projection; leave dynamic MCP registration and
  cleanup with the existing MCP owner.
- **Scope expansion:** Defer Cron, Android tools, channel discovery, new error handling, and broader
  shell-class decomposition.
