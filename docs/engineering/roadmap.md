# PalmClaw Engineering Roadmap

Last reviewed: 2026-08-26

This roadmap contains current product engineering work. Detailed module behavior belongs in the architecture or a focused tool contract, not in this file.

## Current Priorities

| Priority | Area | Current state | Next outcome |
| --- | --- | --- | --- |
| P0 | Integrated runtime refactor | Source-verified | Repeat affected device checks after runtime, channel, or automation ownership changes. |
| P0 | Standard-user Always-on reliability | Source-verified | Run focused Android 15 and lifecycle device QA, Play declaration review, and a 24-hour device run. |
| P0 | Native tool foundation | Source-verified | Repeat focused device regression after relevant Android platform or provider changes. |
| P1 | Cron job management | Source-verified | Repeat alarm, restart, and state-preservation regression after scheduler changes. |
| P1 | MCP client lifecycle | Source-verified | Repeat real-server compatibility and APK-size checks after transport or dependency changes. |
| P1 | UI boundary | In progress | Move stable settings or workflow ownership out of `ChatViewModel`; preserve UI-only parsing, state, and refresh deferral in the view model. |
| P2 | Secondary tool coverage | Planned | Review media import and consent-based mutation, explicit confirmed memory clear, device status detail, and portable web-search filters. |
| P2 | Long-task capabilities | Deferred | Reconsider progress, compact trace, retry, recovery, pause, and resume after the current runtime and tool boundaries are verified. |

## Device Regression Matrix

The current source boundaries and automated acceptance checks are complete. Device cases below are regression coverage rather than completion blockers; detailed procedures live in [Testing and QA](testing.md).

When a test device is available, run the affected Always-on, native-tool, Cron, runtime lifecycle, and MCP transport cases as one focused regression pass.

| Area | Verified | Further regression |
| --- | --- | --- |
| Workspace text codec | Focused tests and Android Studio compilation passed; UTF-8, BOM, explicit legacy encoding, ICU detection, mutation guards, and byte preservation are covered. | Record a comparable ICU4J APK-size delta when available. |
| Calendar | Focused tests and Android Studio compilation passed. | Local and synced provider checks for recurrence, occurrence mutation, reminders, attendees, RSVP, and deletion confirmation. |
| Contacts | Planner, schema, fake-gateway, batch-shape tests, and Android Studio compilation passed. | Default-account, multi-RawContact, conflict, deletion, and cloud-sync checks. |
| Workspace files | Nine focused tools, NIO deep module, and focused tests are implemented. | Android Studio regression, API 24/25, external storage, failure recovery, and NIO APK-size checks. |
| Bluetooth | Bounded BLE client, focused codec/tool tests, and permission and confirmation boundaries are implemented. | Known-peripheral scan, connect, inspect, read, write, disconnect, timeout, and permission checks. |
| Notifications | Namespaced lifecycle and focused key/tool tests are implemented. | Android 13 permission, restart, timeout, dismissal, settings, and namespace-isolation checks. |
| Runtime and channels | Shared runtime tools, channel projection/discovery, UI observation, adapter lifecycle, and automation lifecycle are implemented. Focused functional verification passed on 2026-08-09; affected Android Studio compilation and JVM unit tests passed again on 2026-08-13. | Repeat the affected foreground, Always-on, callback-cleanup, deferred-refresh, and restart checks on device. |
| Always-on reliability | Coordinator, ownership, `specialUse` shell, worker and boot recovery, channel health, and UI source wiring are implemented. Android Studio compilation and the complete JVM unit suite passed on 2026-08-13. | Focused Android 15 lifecycle and device checks, Play declaration review, and a 24-hour run. |
| MCP | Central endpoint policy, official-SDK transport adapter, owner-scoped tool publication, lifecycle reconciliation, structured status, and Agent-facing resource/prompt access are implemented. Android Studio compilation and the complete JVM unit suite passed on 2026-08-26. | Repeat HTTPS, local HTTP, approved LAN HTTP, bounded connection recovery, legacy transport, and size checks when relevant code or dependencies change. |

## Secondary Capability Review

These are candidate improvements, not commitments to wrap every Android or provider API.

- Media: add safe item detail, open/import-to-workspace, sharing, and consent-based trash or delete only where the user workflow is clear. Clean capture placeholders after cancellation or failure.
- Memory: consider a separately confirmed clear action. Session history mutation remains runtime-owned.
- Device status: return useful structured battery, network, storage, and location-age fields without mixing in continuous sensors or tracking.
- Web search: define portable language, freshness, domain, and result-type filters with provider capability reporting.

## Source-Verified Foundation

- Workspace text uses BOM, explicit encoding, strict UTF-8, then ICU4J statistical detection. Statistical detection is read-only; legacy mutation requires an explicit encoding.
- Workspace files expose `find`, `grep`, `read`, `write`, `edit`, `mkdir`, `copy`, `move`, and `delete` over one bounded NIO module.
- Calendar and Contacts expose typed native data models, atomic mutations, stable identifiers, explicit permission and confirmation behavior, and structured verification.
- Bluetooth exposes one bounded BLE GATT client; notifications expose a separate PalmClaw-owned lifecycle namespace.
- Runtime settings, heartbeat, session, channel, and MCP status tools share `RuntimeControlService` across foreground and Always-on execution. MCP connection, capability refresh, dynamic tool publication, and shutdown live behind one lifecycle interface.
- Channel identity, projection, discovery, adapter construction, gateway lifecycle, runtime UI observation, and automation callback ownership have focused module boundaries.
- Always-on reliability uses one desired-state and recovery owner. Foreground shell, runtime, gateway, and diagnostic channel liveness remain separate facts; exact-alarm access is not a user-facing prerequisite for Always-on mode.
- Agent execution supports cancellation, bounded tool results, per-session serialization, and bounded cross-session concurrency.

## Engineering Rules

- Expose reusable atomic capabilities, not evaluation-specific shortcuts.
- Use a cohesive tool when actions share one data and permission model; keep separate tools when their mutation or safety semantics differ.
- Preserve typed schemas, structured errors, explicit permissions, confirmation, timeouts, workspace bounds, and post-mutation verification.
- Do not add a platform capability only because an API exists. Require a clear agent use case and lifecycle owner.
- Record transient experiments and implementation plans in issues or Git history, not as permanent parallel documentation.

Evaluation runners, generated trajectories, benchmark cleanup, and private traces are not product capabilities and should not be represented as app features.
