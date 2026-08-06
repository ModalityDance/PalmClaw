# PalmClaw Architecture

Last reviewed: 2026-08-06

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

`RuntimeControlService` is constructed once in `AppContainer` and shared with normal and Always-on runtime instances. It owns validation, persistence order, session lookup and delivery, channel-binding mutation, and typed runtime snapshots. Runtime-only effects enter through narrow refresh, heartbeat, delivery, channel-status, active-session, and MCP-status ports; the service does not depend on UI state or concrete `Tool` classes.

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

Remote delivery state is scoped to the active turn. A failure in channel delivery should not silently change the local session result.

## Current Architectural Pressure Points

- Calendar capability coverage is source-implemented; focused Android Studio and real-provider verification remain pending.
- Contacts typed-data coverage is source-implemented; focused Android Studio and multi-account device verification remain pending.
- Workspace file tools use the new NIO-backed deep module; Android Studio tests, API 24/25 compatibility, APK size, and device verification remain pending.
- The bounded BLE client is source-implemented; Android Studio compilation, focused tests, and known-peripheral device verification remain pending.
- The agent notification lifecycle is source-implemented; Android Studio compilation and device permission, restart, timeout, and namespace-isolation checks remain pending.
- Runtime tool integration is source-implemented; focused tests, the full unit suite, compilation, and foreground or Always-on manual checks remain pending.
- `ChatViewModel` still owns too many channel discovery, runtime status, settings, and projection helpers.
- `GatewayRuntime` is the central integration point and still owns channel adapter, cron, heartbeat, MCP lifecycle, attachment delivery, and remote delivery logic that should move behind focused services when a stable boundary exists.
- Long-task progress, trace, and recovery remain deferred capability extensions while the core boundaries are cleaned up.

These are tracked in the [engineering roadmap](roadmap.md).
