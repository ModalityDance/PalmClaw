# Tool Capability Coverage Audit

Last reviewed: 2026-07-27

This audit checks whether PalmClaw's built-in tools expose the reusable user-facing operations already supported by their implementation or Android provider. It does not treat every Android API as an agent capability. Raw intents, unrestricted settings changes, account internals, and operations that bypass Android consent remain outside the interface unless there is a clear product use case and a safe permission model.

## Audit Rules

A capability module is considered sufficiently exposed when its tool interface:

- Covers the main atomic operations implied by the capability name.
- Preserves the native data model, including identifiers, cardinality, types, account ownership, and provider-specific values.
- Returns structured state that lets the agent verify a read or mutation.
- Makes permission, confirmation, user-mediated, and unsupported paths explicit.
- Keeps Android classes behind an adapter instead of leaking provider columns into the tool schema.

The target is a deep module: a small typed interface that hides provider and lifecycle complexity. The target is not a one-to-one wrapper for every platform method.

## Findings

| Capability family | Current exposure | Finding | Priority |
| --- | --- | --- | --- |
| Calendar | Event CRUD, calendar listing, recurrence, reminders, attendees, RSVP, and series or occurrence mutations are present in the current implementation. | Broad interface; Android Studio and device verification are still pending. Deliberate exclusions are documented in the calendar contract. | Complete current verification first |
| Contacts | Aggregate identity, account-owned RawContacts, stable typed Data rows, exact Data mutations, default-account creation, verified batch deletion, and system UI fallbacks are present in the current source. | Broad interface implemented; Android Studio and real-device verification are pending. Photos, groups, IM, SIP, Profile, SIM, aggregation control, and custom MIME data remain deliberate exclusions. | Complete current verification first |
| Bluetooth | Power state, paired devices, BLE scan, one verified GATT connection, profile inspection, characteristic read/write, and disconnect are present in the current source. | The bounded BLE client scope is implemented with Android Studio and device verification pending. Subscriptions need cross-turn event ownership and remain deferred. Classic transfer, multi-connect, background reconnect, descriptors, and device-specific decoding are deliberate exclusions. | Complete current verification |
| Device status | Basic build, battery percentage, charging state, active network transport, permission checks, and one-shot location are present. The default permission inventory includes both contact permissions. | The broad `info` action omits useful structured battery, network, storage, and location-age fields. Sensors and continuous location are separate capability families and should not be added to `info`. | P2 expansion |
| Device actions and notifications | `device` owns URL, text-share, and app-settings handoff. The separate `notification` tool owns namespaced status, active list, stable-key post/update, exact cancel, timeout, app tap, and settings recovery. | Notification lifecycle is source-implemented with Android Studio and real-device verification pending. Progress, arbitrary channels or intents, cancel-all, custom layouts, and cross-owner notification management remain deliberate exclusions. Share still cannot send workspace attachments. | Complete current verification |
| Media | User-mediated photo and video capture, recent media listing, workspace audio recording, and basic audio playback are present. | MediaStore access is read-mostly: no item detail, filters beyond kind/count, open/import-to-workspace, share, favorite, trash, or delete flow. Capture placeholders are not cleaned after cancellation or failed capture. Prefer system consent and the photo picker over broad library access where possible. | P2 |
| Workspace files | Nine focused tools expose path discovery and metadata, text read/search/mutation, directory creation, copy, move/rename, and delete. `WorkspaceFileSystem` centralizes NIO operations, bounded no-follow traversal, atomic publication, verification, and recovery. | Source implementation and focused tests are present. Android Studio, API 24/25, APK-size, and real-device external-storage verification remain pending. SAF document URIs remain a deliberate separate adapter. | Complete current verification |
| Cron | Add, list, remove, enable/disable, run now, service status, and policy settings are present. | There is no get-one or update action, and list returns human-readable lines plus IDs instead of complete structured job records. The repository owns `getJob`/`upsert`, but the service and tool do not expose an update contract. | P2 |
| Memory | Long-term read, replace/append, and per-session history read/search match the current store. | Mostly sufficient. An explicit confirmed clear operation is missing; history mutation should remain runtime-owned rather than agent-editable. | P2 |
| Sessions and channel binding | List, cross-session delivery, subagent spawn, binding status, and binding enable/disable are present. | Sufficient for the current agent contract. Creating, renaming, deleting, or arbitrarily rebinding user sessions is a product decision, not an automatic exposure gap. | No immediate change |
| Runtime, heartbeat, and MCP | Adjustable runtime fields have paired get/set tools; heartbeat has get/set/trigger; MCP tools retain server-provided schemas and status. | No clear capability omission. The known problem is duplicated callback and snapshot ownership, which is an architecture issue rather than tool coverage. | Follow existing runtime plan |
| Web search and fetch | Search supports query/count across configured providers; fetch supports bounded HTTP(S) text and document extraction. | The common search interface hides useful provider features such as language, freshness, site/domain, and result-type filters. These need a portable contract and capability reporting; provider-specific arguments should not leak into the top-level schema. Non-GET arbitrary requests remain excluded. | P2 |
| Summarize and weather | These adapters expose the main operations owned by their current implementations. | No source-backed capability gap comparable to calendar. Their quality and provider coverage should be reviewed separately from Android system integration. | No immediate change |

## Recommended Sequence

1. Finish Calendar verification without adding more scope.
2. Finish Contacts verification without adding the deliberately excluded provider families.
3. Complete Android Studio and device verification for the nine-tool workspace file contract.
4. Complete Android Studio and known-peripheral verification for the bounded BLE client.
5. Complete Android Studio and device verification for the agent notification lifecycle.
6. Review safe media import and consent-based mutation.
7. Address Cron and web-search interface gaps after the native capability modules are stable.

## Contacts Acceptance Direction

The source now uses typed arrays and stable Data identifiers instead of scalar replacement fields. Mutations select writable RawContacts deliberately, preserve unsupported MIME rows, run in provider batches with RawContact version assertions, reload the aggregate contact, and verify requested state. Remaining work is Android Studio compilation plus default-account, multi-account, sync, primary-state, cancellation, and confirmed-deletion checks on a real device. The exact boundary is recorded in the [contacts tool contract](contacts-tools.md).

## Bluetooth Acceptance Direction

The source now keeps `BluetoothGatt` objects and callbacks behind `BleClientGateway`. It exposes typed adapter state, discovery, a single verified connection, profile inspection, characteristic read/write, and disconnect. The Android adapter owns operation serialization, timeouts, best-effort MTU negotiation, callback correlation, and cleanup. A user-confirmed setup attempt remains distinct from a verified connection, and every write requires confirmation.

Notification subscription is not a missing single-call action: it needs ownership and delivery for events that arrive after the initiating turn. It remains deferred until that lifecycle is designed. RSSI polling, Classic transfer, background reconnect, multiple connections, descriptor access, and device-profile decoding are outside the current agent use case. The exact boundary and device checklist are recorded in the [Bluetooth tool contract](bluetooth-tools.md).

## Sources

- Android `ContactsContract` defines an aggregate `Contact`, account-owned `RawContact` rows, and extensible typed `Data` rows: <https://developer.android.com/reference/android/provider/ContactsContract>
- Android BLE data transfer occurs through discovered GATT services and characteristic read, write, and notification operations: <https://developer.android.com/develop/connectivity/bluetooth/ble/transfer-ble-data>
- Android Classic Bluetooth discovery and connection are separate from paired-device listing and BLE scanning: <https://developer.android.com/develop/connectivity/bluetooth/find-bluetooth-devices>
- Android notifications support actions, stable-ID updates, and explicit cancellation: <https://developer.android.com/develop/ui/compose/notifications/create-notification>
- Android MediaStore supports structured queries and user-consented update, trash, and delete requests; the photo picker is preferred for user-selected media: <https://developer.android.com/training/data-storage/shared/media>
