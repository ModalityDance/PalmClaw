# PalmClaw Architecture

Last reviewed: 2026-08-26

## Overview

PalmClaw is a single-module Android application written in Kotlin. It uses Jetpack Compose, Room, coroutines, OkHttp, Android services, and workers.

The agent framework runs on the device. UI messages, remote channels, Cron jobs, and heartbeat events enter one runtime that calls a model, executes bounded tools, stores messages, and returns results.

## Main Boundaries

| Layer | Responsibility | Main owners |
| --- | --- | --- |
| Composition | Construct process-wide dependencies and expose narrow interfaces. | `PalmClawApplication`, `AppContainer` |
| UI | Render state and collect user input without owning databases or runtime lifecycles. | `MainActivity`, `ChatScreen`, `ChatViewModel`, UI coordinators |
| Runtime | Coordinate channels, schedules, sessions, tools, and agent turns. | `GatewayRuntime`, `GatewayRuntimeSupervisor`, `RuntimeControlService` |
| Agent | Build context and run the model/tool loop. | `ContextBuilder`, `AgentLoop`, `SessionTurnCoordinator` |
| Providers | Normalize supported model protocols behind one interface. | `LlmProvider`, provider adapters |
| Tools | Validate schemas, enforce bounds, execute capabilities, and return structured results. | `ToolRegistry`, built-in tools, platform gateways |
| Storage | Persist sessions, messages, schedules, settings, memory, and workspaces. | Room repositories and file-backed stores |
| Channels | Connect external messaging systems to the shared runtime. | Channel adapters and `ChannelGatewayLifecycle` |

New process-wide dependencies belong in `AppContainer` or behind an interface provided by it. UI code must not construct repositories, databases, or runtime owners directly.

## Runtime Model

One process owns one `GatewayRuntime`. Foreground use, Always-on mode, and scoped automation acquire the same runtime through `GatewayRuntimeSupervisor`; only the final release stops it.

`AlwaysOnCoordinator` owns the desired Always-on state and recovery policy. `AlwaysOnGatewayService` is the Android foreground-service shell, not a second runtime.

Channel readiness comes from shared diagnostics. A constructed adapter is not necessarily online, and the UI must keep runtime, network, gateway, and channel states separate.

Android force-stop remains a platform limit. PalmClaw cannot recover until the user opens the app again.

Cron and heartbeat callbacks are owned by `AutomationRuntimeLifecycle`. Process-owned schedulers survive runtime replacement, while callbacks are detached during shutdown.

MCP connection, capability discovery, dynamic tool publication, content access, retries, and shutdown are owned by `McpRuntimeLifecycle`. Streamable HTTP is preferred and legacy HTTP+SSE remains supported.

MCP uses HTTPS by default. Local HTTP is allowed; private-LAN HTTP requires explicit origin approval and cannot carry a Bearer token. Public HTTP and unsafe URL forms are rejected.

## Agent Turn

`AgentLoop` persists the incoming message, selects skills, builds model context, calls the provider, executes validated tool calls, stores bounded results, and repeats until completion or cancellation.

Turns are serialized within one session and may run concurrently across different sessions. Provider-specific requests and responses remain behind `LlmProvider`.

`ProviderCatalog` owns verified defaults. Endpoint planning preserves catalog-declared complete endpoints, keeps custom paths first, derives only same-protocol alternatives, and limits credential-bearing fallback to approved origins.

## Tool Model

Tools expose reusable agent capabilities rather than evaluation shortcuts. Related actions may share one cohesive tool when they have the same data and permission model; unrelated capability families remain separate.

Every tool must preserve typed schemas, Android permissions, confirmation for risky operations, explicit timeouts, structured errors, operation bounds, and post-mutation verification where applicable.

Platform details stay behind testable gateways. Calendar, Contacts, Bluetooth, notifications, MCP, and other Android integrations should not expose provider rows, callbacks, or SDK types to the agent-facing schema.

Workspace access uses nine focused file tools over `WorkspaceFileSystem`. Paths remain within approved workspace roots, traversal does not follow links by default, and writes use bounded or atomic operations where possible.

Workspace text is UTF-8 by default. Existing text is decoded by BOM, explicit encoding, strict UTF-8, then ICU4J detection. Statistical detection is read-only unless the agent supplies an explicit encoding for mutation.

## Change Rules

- Keep one clear lifecycle owner for state, callbacks, cleanup, and recovery.
- Move responsibilities out of a coordinator only when the new module owns a complete workflow.
- Keep SDK and Android provider types behind internal adapters.
- Preserve permission, confirmation, workspace, and security boundaries when expanding capabilities.
- Keep long-task progress, pause, resume, and trace work outside the current stage.

Current work is tracked in the [engineering roadmap](roadmap.md).
