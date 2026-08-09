# PalmClaw Architecture

Last reviewed: 2026-08-09

## System Overview

PalmClaw is a single-module Android application written in Kotlin. It uses Jetpack Compose for UI, Room for structured local data, Kotlin coroutines for concurrency, OkHttp for provider and channel networking, and Android services and workers for background execution.

The app keeps the agent framework on the Android device. User messages, remote channel messages, scheduled jobs, and heartbeat events enter a shared runtime. The runtime builds an agent turn, calls the configured language model provider, executes registered bounded tools, stores the resulting messages, and publishes updates to the UI or bound channel.

## Main Layers

### Application composition

`PalmClawApplication` owns a lazily created `AppContainer`. `AppContainer` is the composition root for databases, repositories, configuration, memory, skills, workspaces, runtime services, and UI-facing gateways.

New process-wide dependencies should be constructed in `AppContainer` or behind an interface provided by it. UI classes should not construct repositories, databases, or runtime owners directly.

### UI

`MainActivity` hosts the Compose application. `ChatScreen` is the main UI shell, while feature-level components under `ui/chat`, `ui/settings`, and `ui/onboarding` own focused screens and workflows.

`ChatViewModel` exposes UI state and delegates part of its work to state stores, coordinators, mappers, and domain services. Runtime settings, heartbeat mutations, and session channel enablement enter `RuntimeControlService` through domain commands; the view model retains text parsing, presentation state, and UI-specific refresh deferral. It remains larger than intended, so further extraction should follow workflow boundaries rather than mechanical file splitting.

### Runtime ownership

`RuntimeApplicationService` selects normal or Always-on execution without requiring the UI to know which runtime owns the turn.

`GatewayRuntimeSupervisor` is the process-wide owner of the active `GatewayRuntime`. `AlwaysOnGatewayService` is a foreground-service shell; it should not create a second independent agent runtime.

`GatewayRuntime` connects channels, scheduled execution, heartbeat processing, session state, tools, and agent turns. `RuntimeToolIntegration` owns the runtime settings, heartbeat, session, channel-binding, and MCP status tool instances and adapts their DTOs to `RuntimeControlService`. The integration is scoped to one runtime and clears every callback during shutdown. `SessionTurnCoordinator` serializes turns within one session while allowing bounded concurrency across different sessions.

`ConfiguredChannelAdapterFactory` owns configured binding normalization, credential grouping, and construction of the six concrete channel adapters. `ChannelGatewayLifecycle` owns the optional `GatewayOrchestrator` and its create, reconfigure, stop, outbound delivery, and attachment-capability operations. `GatewayRuntime` supplies agent-loop and delivery callbacks while retaining processing-aware config deferral, binding-to-session resolution, per-session turn locking, and top-level runtime coordination.

`AutomationRuntimeLifecycle` owns one runtime's cron callback registration, cron and heartbeat config application, heartbeat alarm delegation, cron due-job delegation, and identity-safe callback cleanup. `AppContainer` retains the process-owned `CronService` and `HeartbeatService`, so runtime teardown detaches callbacks without permanently closing schedulers needed by a later foreground or Always-on runtime. `GatewayRuntime` retains cron target resolution, cron and heartbeat agent turns, fallback content, and remote delivery.

`RuntimeControlService` is constructed once in `AppContainer` and shared with normal and Always-on runtime instances. It owns validation, persistence order, session lookup and delivery, channel-binding mutation, and typed runtime snapshots. Channel snapshots use the process-level `ChannelRuntimeSnapshotSource` and the same `ChannelBindingRuntimeProjector` as the settings UI. Runtime-only effects enter through narrow refresh, heartbeat, delivery, snapshot, active-session, and MCP-status ports; the service does not depend on UI state or concrete `Tool` classes.

### Agent turn

`AgentLoop` performs the model/tool loop:

1. Persist the incoming message when required.
2. Select active skills from recent user context.
3. Build model messages from policy templates, session history, memory, and skills.
4. Send model messages and tool specifications to the configured provider.
5. Persist assistant content, reasoning content, and structured tool calls.
6. Validate and execute tool calls through `ToolRegistry`.
7. Bound and persist tool results, then continue until no tool call remains, a terminal tool completes, cancellation occurs, or the maximum round count is reached.

LLM messages and tool specifications are separate inputs to the provider. Changes to prompt construction must preserve this boundary.

### Providers

Provider implementations under `providers` normalize OpenAI-compatible, OpenAI Responses, and Anthropic-compatible protocols. `AdaptiveLlmProvider` and provider resolution state select a working protocol or endpoint configuration.

Provider-specific request and response handling should remain behind `LlmProvider`. Agent code should consume normalized messages, tool calls, usage, and errors.

### Tools

`ToolRegistry` owns registration, schema validation, timeout enforcement, execution, and structured failure conversion. Built-in tools are grouped by capability family and may expose related operations through a typed `action` field.

Tool changes must preserve:

- JSON schema validation and typed arguments.
- Android permission boundaries.
- User confirmation for risky or user-mediated operations.
- Workspace and file-size bounds.
- Explicit timeout and structured error behavior.

Unrelated capability families should not be merged only to reduce the number of tool names.

Android Calendar access follows a two-layer boundary. The unified `calendar` tool defines typed user-facing actions, validates provider capabilities, applies confirmation policy, and returns structured event data. `CalendarProviderGateway` owns `CalendarContract` rows, instance expansion, relation queries, recurrence exceptions, and batched writes. Recurrence translation remains in `CalendarRecurrenceCodec`, so provider rule parsing and construction do not spread through action handlers. See the [calendar tool contract](calendar-tools.md).

Android Contacts follows the same deep-module rule. The unified `contacts` tool owns structured arguments, permission and confirmation policy, stable selectors, and result projection. `ContactsProviderGateway` hides Contact, RawContact, Data, account, MIME mapping, optimistic version checks, batched writes, aggregate re-resolution, and post-mutation verification. Production and test adapters use the same seam. See the [contacts tool contract](contacts-tools.md).

Workspace files expose nine focused tools instead of one broad action schema. `WorkspaceFileSystem`
is their shared deep module and owns lexical workspace resolution, Java NIO operations, bounded
no-follow traversal, atomic publication, copy verification, and move recovery. Text encoding and
document extraction remain in their existing focused modules. See the
[workspace file tool contract](file-tools.md).

Bluetooth uses a typed transport boundary. The unified `bluetooth` tool owns public actions,
permission policy, write confirmation, and structured results. `BleClientGateway` hides Android
callbacks and exposes one active GATT session with service inspection and characteristic
read/write operations. `AndroidBleClientGateway` owns scan and connection lifecycle, serialized
operations, timeouts, best-effort MTU negotiation, callback correlation, and cleanup. System UI
prompts remain behind `BluetoothUserInteraction`. A process-wide `AndroidBluetoothRuntime` shares
the gateway when more than one tool registry exists, preserving the single-connection contract.
See the
[Bluetooth tool contract](bluetooth-tools.md).

Immediate agent notifications use a separate typed module rather than the broad `device` tool.
The `notification` tool owns schema, permission policy, and structured results, while
`NotificationGateway` hides Android `(tag, id)` identity, channel handling, PendingIntents,
namespace filtering, and post-mutation verification. Only `palmclaw.agent.*` notifications are
visible through this interface. Cron and Always-on retain their own notification lifecycles. See
the [notification tool contract](notification-tools.md).

### Storage and workspace

Room stores sessions, messages, attachments, and cron jobs. File-backed stores hold configuration, secure values, memory, templates, logs, and session workspaces.

Session deletion or application reset must coordinate database state, channel bindings, scheduled jobs, attachments, workspace files, and relevant caches. File tools must resolve paths through the workspace boundary rather than accepting unrestricted filesystem access.

PalmClaw-created and overwritten workspace text uses canonical UTF-8. `read`, existing-file `append`, `edit`, and `grep` share one codec with a fixed order: BOM, explicit `encoding`, strict UTF-8, then ICU4J statistical detection. Statistical detection is read-only and needs confidence of at least 50; `append` and `edit` require an explicit encoding for statistically detected files. Encoding and representability checks finish before any file bytes are written. OOXML and ODT XML use only BOM, XML declarations, and strict UTF-8, so document parsing does not enter the workspace legacy-detection policy.

### Channels and background execution

Channel adapters translate external messages into the shared message bus and runtime. Cron and heartbeat receivers or workers trigger the same runtime path rather than maintaining separate agent implementations.

`ChannelAdapterIdentity` is the sole owner of credential-derived adapter keys, including Feishu canonical and legacy compatibility keys. `ChannelBindingRuntimeProjector` owns target normalization, binding completeness, gateway-idle handling, and live adapter status labels. `AppContainer` shares the projector, Android email validator, and diagnostic snapshot source across UI, normal runtime, and Always-on runtime paths.

`ChannelDiscoveryService` owns Telegram, Feishu, Email, and WeCom discovery normalization, network or diagnostic access, bounded polling, and typed outcomes. Feishu and WeCom may use a process-shared, capture-only temporary adapter for at most 15 seconds when no compatible formal runtime adapter is active. Temporary discovery never persists credentials, joins `GatewayOrchestrator`, or publishes inbound messages.

`ChannelGatewayDiagnosticsSource` is the process diagnostic read boundary used by settings status. `GatewayStatusOverviewAssembler` keeps the existing formatter rules in the UI layer without exposing diagnostic singletons to `ChatViewModel`. `RuntimeStatusCoordinator` owns foreground and Always-on status collection in the UI scope. The UI sees the shared `RuntimeApplicationGateway` through separate status, execution, and refresh interfaces; this interface split does not create another runtime lifecycle owner.

Remote delivery state is scoped to the active turn. A failure in channel delivery should not silently change the local session result.

## Current Architectural Direction

- Complete focused and device verification for the merged runtime, channel, automation, and native-tool boundaries before expanding their scope.
- Continue reducing `ChatViewModel` only along stable workflow boundaries. File size alone is not a reason to create another class.
- Keep `GatewayRuntime` as the top-level coordinator while moving a responsibility out only when one module can own its state, callbacks, cleanup, and tests.
- Review secondary tool gaps after the current file, Calendar, Contacts, Bluetooth, and notification contracts are verified.
- Keep long-task progress, trace, pause, resume, and recovery outside the current optimization stage.

These are tracked in the [engineering roadmap](roadmap.md).
