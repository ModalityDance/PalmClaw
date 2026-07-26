# Tool Capability Coverage Audit

Last reviewed: 2026-07-18

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
| Bluetooth | Power state, paired devices, BLE scan, connect, service count, and disconnect are present. | A BLE connection cannot list service or characteristic details, read, write, subscribe, request MTU, or read RSSI. Classic discovery and data transfer are also absent. A manually confirmed setup can currently return a successful tool result with `ble_connected=false`, so result truthfulness must be fixed before adding more actions. | P1 after scope decision |
| Device status | Basic build, battery percentage, charging state, active network transport, permission checks, and one-shot location are present. The default permission inventory includes both contact permissions. | The broad `info` action omits useful structured battery, network, storage, and location-age fields. Sensors and continuous location are separate capability families and should not be added to `info`. | P2 expansion |
| Device actions and notifications | Post a basic notification, open an HTTP(S) URL, share text, and open app settings. | Notifications cannot use a stable caller ID, update, cancel, add a tap target, set a timeout, or represent ongoing progress. Share cannot send workspace attachments. Arbitrary intents and silent system-setting changes should remain excluded. | P1 notification lifecycle |
| Media | User-mediated photo and video capture, recent media listing, workspace audio recording, and basic audio playback are present. | MediaStore access is read-mostly: no item detail, filters beyond kind/count, open/import-to-workspace, share, favorite, trash, or delete flow. Capture placeholders are not cleaned after cancellation or failed capture. Prefer system consent and the photo picker over broad library access where possible. | P2 |
| Workspace files | Bounded list, glob, read, write/append, edit, grep, delete, move, and rename are present, with the shared text codec and mutation checks. | The main reusable atomic gaps are `stat`, `copy`, and explicit `mkdir`. Write can create parent directories, but the agent cannot create an empty directory or copy without removing the source. Workspace roots, confirmations, and encoding rules remain deliberate constraints. | P1 |
| Cron | Add, list, remove, enable/disable, run now, service status, and policy settings are present. | There is no get-one or update action, and list returns human-readable lines plus IDs instead of complete structured job records. The repository owns `getJob`/`upsert`, but the service and tool do not expose an update contract. | P2 |
| Memory | Long-term read, replace/append, and per-session history read/search match the current store. | Mostly sufficient. An explicit confirmed clear operation is missing; history mutation should remain runtime-owned rather than agent-editable. | P2 |
| Sessions and channel binding | List, cross-session delivery, subagent spawn, binding status, and binding enable/disable are present. | Sufficient for the current agent contract. Creating, renaming, deleting, or arbitrarily rebinding user sessions is a product decision, not an automatic exposure gap. | No immediate change |
| Runtime, heartbeat, and MCP | Adjustable runtime fields have paired get/set tools; heartbeat has get/set/trigger; MCP tools retain server-provided schemas and status. | No clear capability omission. The known problem is duplicated callback and snapshot ownership, which is an architecture issue rather than tool coverage. | Follow existing runtime plan |
| Web search and fetch | Search supports query/count across configured providers; fetch supports bounded HTTP(S) text and document extraction. | The common search interface hides useful provider features such as language, freshness, site/domain, and result-type filters. These need a portable contract and capability reporting; provider-specific arguments should not leak into the top-level schema. Non-GET arbitrary requests remain excluded. | P2 |
| Summarize and weather | These adapters expose the main operations owned by their current implementations. | No source-backed capability gap comparable to calendar. Their quality and provider coverage should be reviewed separately from Android system integration. | No immediate change |

## Recommended Sequence

1. Finish Calendar verification without adding more scope.
2. Finish Contacts verification without adding the deliberately excluded provider families.
3. Add the small file primitives `stat`, `copy`, and `mkdir`.
4. Decide whether Bluetooth is a BLE client tool or a broader Bluetooth tool. If it remains BLE-first, complete the GATT client lifecycle before adding Classic Bluetooth.
5. Add notification lifecycle operations, then review safe media import and consent-based mutation.
6. Address Cron and web-search interface gaps after the native capability modules are stable.

## Contacts Acceptance Direction

The source now uses typed arrays and stable Data identifiers instead of scalar replacement fields. Mutations select writable RawContacts deliberately, preserve unsupported MIME rows, run in provider batches with RawContact version assertions, reload the aggregate contact, and verify requested state. Remaining work is Android Studio compilation plus default-account, multi-account, sync, primary-state, cancellation, and confirmed-deletion checks on a real device. The exact boundary is recorded in the [contacts tool contract](contacts-tools.md).

## Bluetooth Acceptance Direction

Do not add raw `BluetoothGatt` objects or platform callbacks to the tool interface. A BLE adapter should own asynchronous operation serialization and expose typed actions for connection status, service discovery, characteristic read/write, notification subscription, RSSI, and disconnect. Results must distinguish a user-confirmed setup attempt from a verified active connection. Device-specific profile decoding belongs in a later profile module or skill, not in the generic Bluetooth transport tool.

## Sources

- Android `ContactsContract` defines an aggregate `Contact`, account-owned `RawContact` rows, and extensible typed `Data` rows: <https://developer.android.com/reference/android/provider/ContactsContract>
- Android BLE data transfer occurs through discovered GATT services and characteristic read, write, and notification operations: <https://developer.android.com/develop/connectivity/bluetooth/ble/transfer-ble-data>
- Android Classic Bluetooth discovery and connection are separate from paired-device listing and BLE scanning: <https://developer.android.com/develop/connectivity/bluetooth/find-bluetooth-devices>
- Android notifications support actions, stable-ID updates, and explicit cancellation: <https://developer.android.com/develop/ui/compose/notifications/create-notification>
- Android MediaStore supports structured queries and user-consented update, trash, and delete requests; the photo picker is preferred for user-selected media: <https://developer.android.com/training/data-storage/shared/media>
