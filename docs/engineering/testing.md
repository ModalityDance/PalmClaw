# Testing and QA

Last reviewed: 2026-08-09

Verify a change at the smallest useful level while editing, then run the full unit suite and an Android Studio build before integration. Changes involving Android providers, permissions, storage, background execution, or UI behavior also need a focused device or emulator check.

The repository build runs `verifyTextEncoding` through `preBuild`; it rejects invalid UTF-8 source text and common mojibake markers. Keep JDK, SDK, device, account, and credential details outside public documentation.

## Verification Levels

1. Focused tests cover the changed module and its public contract.
2. Structural or composition tests verify ownership when a refactor moves callbacks, state, or construction.
3. The complete `testDebugUnitTest` suite checks cross-module regressions.
4. An Android Studio debug build checks Android compilation and packaging.
5. Device or emulator QA checks platform behavior that local tests cannot prove.

Connected tests are required when a change depends on real Room migrations, Android document readers, provider behavior, or other platform APIs that local fakes cannot represent.

## Focused Test Index

| Change area | Main focused coverage |
| --- | --- |
| Agent context and providers | Context builder plus the affected provider protocol tests. |
| Runtime ownership and concurrency | Runtime supervisor/application service, session turn coordination, and lifecycle-owner tests. |
| Runtime tools | Runtime control/integration, composition root, tool catalog/schema, and UI structural guards. |
| Channels | Adapter identity, binding projection, discovery, adapter factory, gateway lifecycle, diagnostics, and runtime status tests. |
| Automation | Automation lifecycle, Cron/heartbeat behavior, callback ownership, and restart cleanup tests. |
| Tool interface | Tool argument validation, built-in catalog, and the affected tool tests. |
| Workspace text | Codec and file-action tests for precedence, BOM, ICU confidence, explicit legacy mutation, byte preservation, and newlines. |
| Workspace paths | File contract, path resolver, NIO traversal, atomic publication, copy verification, move recovery, and delete confirmation tests. |
| Calendar | Recurrence codec, relation planners, fake-gateway actions, and Android provider tests. |
| Contacts | Mutation planner, schema, MIME mapping, batch operations, fake-gateway actions, and Android provider tests. |
| Bluetooth | Value codec and control-tool tests, followed by a known-peripheral check. |
| Notifications | Key mapping, control-tool, catalog/schema, and namespace behavior tests. |
| Chat and settings | Message projection, render state, session/history coordination, affected coordinators, and structural guards. |
| Storage | Room migration and integrity tests, including connected tests when required. |

## Device Verification

Use disposable data and accounts where possible. A failed or cancelled mutation must leave the external state unchanged, and a successful mutation must be read back from the platform source of truth.

- Calendar: on a local and a synced calendar when available, verify timed and all-day creation, recurrence, one-occurrence and full-series mutation, reminder and attendee replacement, RSVP, opening, and cancelled/confirmed deletion.
- Contacts: verify default-account creation with multiple typed values, exact `data_id` update/removal, explicit multi-RawContact ownership, cancelled/confirmed deletion, and cloud-sync round trip.
- Workspace files: on API 24/25 and a current Android version, verify the nine actions in the session workspace, `shared://`, and approved external storage; include encoding preservation, confirmation, operation bounds, and copy/move failure recovery.
- Bluetooth: with a documented disposable BLE peripheral, verify permission denial/recovery, scan, one connection, profile inspection, read, cancelled/confirmed write, disconnect, timeout, and second-connection rejection.
- Notifications: on Android 13 or later, verify permission denial/recovery, stable-key post/list/update/cancel, duplicate rejection, dismissal, timeout, process restart, disabled settings, and isolation from Cron and Always-on notifications.
- Runtime and channels: compare foreground and Always-on behavior; restart the runtime in one process; verify one active callback owner, deferred refresh during processing, stable channel projection, discovery cleanup, and continued Cron/heartbeat scheduling.
- Chat: verify session switching, immediate user-message display, processing continuity, stop behavior, keyboard and composer insets, stable history prepend, and opt-in tool details.

## Build-Size Checks

When a dependency or desugaring change can materially affect the APK, compare the same build variant before and after with Android Studio APK Analyzer. Record the APK and estimated download-size delta in the related change or release note; the engineering guide does not maintain an open-ended measurement table.

## Regression Ownership

| Symptom | First boundary to inspect |
| --- | --- |
| Stale messages after session switch | `ChatSessionCoordinator`, message projection cache |
| Delayed user bubble | Chat state store and send coordination |
| Wrong or flickering processing state | Runtime status flows and processing coordinator |
| History jumps during prepend | Chat scroll state and history restore effects |
| Tool summary differs from stored result | `MessageUiProjector` |
| Foreground and Always-on behavior differs | `RuntimeApplicationService`, `GatewayRuntimeSupervisor` |
| Duplicate channel or automation callbacks after restart | Channel or automation lifecycle owner |
| External mutation reports false success | Tool gateway verification and platform reload |

Add a regression case only when it protects reusable behavior beyond one bug or device.
