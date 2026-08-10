# PalmClaw Engineering Roadmap

Last reviewed: 2026-08-09

This roadmap contains current product engineering work. Detailed module behavior belongs in the architecture or a focused tool contract, not in this file.

## Current Priorities

| Priority | Area | Current state | Next outcome |
| --- | --- | --- | --- |
| P0 | Integrated runtime refactor | Source-verified | Android Studio compilation, unit tests, and focused functional verification passed on 2026-08-09. Repeat affected checks when these modules change. |
| P0 | Native tool verification | In progress | Finish the real-device checklists for Calendar, Contacts, workspace files, Bluetooth, and notifications. |
| P1 | Cron job management | In progress | Structured paged reads and exact get/update are implemented; verify alarm recomputation, state preservation, and restart behavior in Android Studio and on device. |
| P1 | `GatewayRuntime` boundary | Planned | Extract MCP lifecycle only if one service can own setup, refresh, callbacks, shutdown, and tests without duplicating runtime state. |
| P1 | UI boundary | In progress | Move stable settings or workflow ownership out of `ChatViewModel`; preserve UI-only parsing, state, and refresh deferral in the view model. |
| P2 | Secondary tool coverage | Planned | Review media import and consent-based mutation, explicit confirmed memory clear, device status detail, and portable web-search filters. |
| P2 | Long-task capabilities | Deferred | Reconsider progress, compact trace, retry, recovery, pause, and resume after the current runtime and tool boundaries are verified. |

## Verification Queue

The source boundaries are implemented. Remaining acceptance work is intentionally kept small here; device cases live in [Testing and QA](testing.md).

| Area | Verified | Remaining |
| --- | --- | --- |
| Workspace text codec | Focused tests and Android Studio compilation passed; UTF-8, BOM, explicit legacy encoding, ICU detection, mutation guards, and byte preservation are covered. | Record a comparable ICU4J APK-size delta when available. |
| Calendar | Focused tests and Android Studio compilation passed. | Local and synced provider checks for recurrence, occurrence mutation, reminders, attendees, RSVP, and deletion confirmation. |
| Contacts | Planner, schema, fake-gateway, batch-shape tests, and Android Studio compilation passed. | Default-account, multi-RawContact, conflict, deletion, and cloud-sync checks. |
| Workspace files | Nine focused tools, NIO deep module, and focused tests are implemented. | Android Studio regression, API 24/25, external storage, failure recovery, and NIO APK-size checks. |
| Bluetooth | Bounded BLE client, focused codec/tool tests, and permission and confirmation boundaries are implemented. | Known-peripheral scan, connect, inspect, read, write, disconnect, timeout, and permission checks. |
| Notifications | Namespaced lifecycle and focused key/tool tests are implemented. | Android 13 permission, restart, timeout, dismissal, settings, and namespace-isolation checks. |
| Runtime and channels | Shared runtime tools, channel projection/discovery, UI observation, adapter lifecycle, and automation lifecycle are implemented. Android Studio compilation, unit tests, and focused functional verification passed on 2026-08-09. | Repeat the affected foreground, Always-on, callback-cleanup, deferred-refresh, and restart checks when these modules change. |

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
- Runtime settings, heartbeat, session, channel, and MCP status tools share `RuntimeControlService` across foreground and Always-on execution.
- Channel identity, projection, discovery, adapter construction, gateway lifecycle, runtime UI observation, and automation callback ownership have focused module boundaries.
- Agent execution supports cancellation, bounded tool results, per-session serialization, and bounded cross-session concurrency.

## Engineering Rules

- Expose reusable atomic capabilities, not evaluation-specific shortcuts.
- Use a cohesive tool when actions share one data and permission model; keep separate tools when their mutation or safety semantics differ.
- Preserve typed schemas, structured errors, explicit permissions, confirmation, timeouts, workspace bounds, and post-mutation verification.
- Do not add a platform capability only because an API exists. Require a clear agent use case and lifecycle owner.
- Record transient experiments and implementation plans in issues or Git history, not as permanent parallel documentation.

Evaluation runners, generated trajectories, benchmark cleanup, and private traces are not product capabilities and should not be represented as app features.
