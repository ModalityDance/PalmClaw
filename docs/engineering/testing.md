# Testing and QA

Last reviewed: 2026-08-13

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
| Runtime ownership and concurrency | Supervisor, application service, and foreground coordinator tests for release/reacquire, late-reload suppression, final-owner stop, session turns, and lifecycle ownership. |
| Always-on availability | Coordinator and recovery-policy tests through platform and gateway fakes; ownership release, deferred-stop cancellation by a new owner, cleanup retry, hidden-notification behavior, channel-liveness projection, UI status mapping, and lifecycle guards. |
| Runtime tools | Runtime control/integration, composition root, tool catalog/schema, and UI structural guards. |
| Channels | Adapter identity, binding projection, discovery, adapter factory, gateway lifecycle, diagnostics, and runtime status tests. |
| Automation | Lifecycle and Cron/heartbeat tests for cold scoped cleanup, overlapping ref-counted owners, cancellation, stop-failure retry, callback ownership, and restart cleanup. |
| Cron job management | Cron update planner and tool tests for paged structured results, exact get/update, schedule recomputation, state preservation, nullable target clearing, and structured errors. |
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
- Cron: verify add/list/get/update, paged results, message-only state preservation, schedule replacement and alarm recomputation, explicit delivery-target clearing, pause/resume, run-now, removal, and process restart.
- Runtime and channels: compare foreground and Always-on behavior; restart the runtime in one process; verify one active callback owner, deferred refresh during processing, stable channel projection, discovery cleanup, and continued Cron/heartbeat scheduling.
- Runtime ownership: with Always-on disabled, verify foreground acquire, background release, and foreground reacquire. After release, late reload callbacks must not reopen inbound channels.
- Automation ownership: from a cold process, run Cron and heartbeat separately and with overlap. Each scoped owner must clean up; a forced final-stop failure and retry must not leave an inbound gateway.
- Always-on: verify desired enablement separately from foreground shell, runtime, gateway, and channel readiness. With real channel bindings, cover initial connection, one-channel degradation, full recovery, airplane-mode loss and restoration, notification Stop, task removal, ordinary process death, reboot, package replacement, Doze, battery-restricted and battery-unrestricted operation, and Android 15 foreground-service start and fallback behavior. After notification Stop, watchdog execution must not re-enable Always-on; no screen may report `Online` until diagnostics report a ready channel. Disable Always-on while the app is foregrounded and confirm the normal gateway still receives and sends messages. Run a plugged-in 24-hour check with timestamped channel probes and recovery events. Android force-stop is an explicit platform limit and is not expected to self-recover until the user opens the app again.
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
| Always-on says Online while a channel cannot receive messages | `AlwaysOnCoordinator`, `ChannelRuntimeDiagnostics`, UI status projection |
| Duplicate channel or automation callbacks after restart | Channel or automation lifecycle owner |
| External mutation reports false success | Tool gateway verification and platform reload |

Add a regression case only when it protects reusable behavior beyond one bug or device.
