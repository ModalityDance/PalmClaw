# PalmClaw Architecture

Last reviewed: 2026-07-17

## System Overview

PalmClaw is a single-module Android application written in Kotlin. It uses Jetpack Compose for UI, Room for structured local data, Kotlin coroutines for concurrency, OkHttp for provider and channel networking, and Android services and workers for background execution.

The app keeps the agent framework on the Android device. User messages, remote channel messages, scheduled jobs, and heartbeat events enter a shared runtime. The runtime builds an agent turn, calls the configured language model provider, executes registered bounded tools, stores the resulting messages, and publishes updates to the UI or bound channel.

## Main Layers

### Application composition

`PalmClawApplication` owns a lazily created `AppContainer`. `AppContainer` is the composition root for databases, repositories, configuration, memory, skills, workspaces, runtime services, and UI-facing gateways.

New process-wide dependencies should be constructed in `AppContainer` or behind an interface provided by it. UI classes should not construct repositories, databases, or runtime owners directly.

### UI

`MainActivity` hosts the Compose application. `ChatScreen` is the main UI shell, while feature-level components under `ui/chat`, `ui/settings`, and `ui/onboarding` own focused screens and workflows.

`ChatViewModel` exposes UI state and delegates part of its work to state stores, coordinators, mappers, and domain services. It remains larger than intended and still contains runtime and tool orchestration helpers. Runtime-owned tool callbacks and typed runtime snapshots should not be implemented in the UI layer. Further extraction should follow workflow boundaries rather than mechanical file splitting.

### Runtime ownership

`RuntimeApplicationService` selects normal or Always-on execution without requiring the UI to know which runtime owns the turn.

`GatewayRuntimeSupervisor` is the process-wide owner of the active `GatewayRuntime`. `AlwaysOnGatewayService` is a foreground-service shell; it should not create a second independent agent runtime.

`GatewayRuntime` connects channels, scheduled execution, heartbeat processing, session state, tool construction, and agent turns. `SessionTurnCoordinator` serializes turns within one session while allowing bounded concurrency across different sessions.

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

### Storage and workspace

Room stores sessions, messages, attachments, and cron jobs. File-backed stores hold configuration, secure values, memory, templates, logs, and session workspaces.

Session deletion or application reset must coordinate database state, channel bindings, scheduled jobs, attachments, workspace files, and relevant caches. File tools must resolve paths through the workspace boundary rather than accepting unrestricted filesystem access.

PalmClaw-created and overwritten workspace text uses canonical UTF-8. `read`, existing-file `append`, `edit`, and `grep` share one codec with a fixed order: BOM, explicit `encoding`, strict UTF-8, then ICU4J statistical detection. Statistical detection is read-only and needs confidence of at least 50; `append` and `edit` require an explicit encoding for statistically detected files. Encoding and representability checks finish before any file bytes are written. OOXML and ODT XML use only BOM, XML declarations, and strict UTF-8, so document parsing does not enter the workspace legacy-detection policy.

### Channels and background execution

Channel adapters translate external messages into the shared message bus and runtime. Cron and heartbeat receivers or workers trigger the same runtime path rather than maintaining separate agent implementations.

Remote delivery state is scoped to the active turn. A failure in channel delivery should not silently change the local session result.

## Current Architectural Pressure Points

- The workspace text codec is source-implemented; focused unit-test execution and Android Studio compilation remain pending.
- `ChatViewModel` and `GatewayRuntime` duplicate parts of runtime-tool callback wiring and snapshot construction.
- `ChatViewModel` still owns too many runtime, channel, settings, and tool coordination helpers.
- `GatewayRuntime` is the central integration point and still owns capability-specific tool, channel, cron, heartbeat, MCP, and delivery logic that should move behind focused services when a stable boundary exists.
- Long-task progress, trace, and recovery remain deferred capability extensions while the core boundaries are cleaned up.

These are tracked in the [engineering roadmap](roadmap.md).
