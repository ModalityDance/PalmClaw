# Bluetooth Tool Contract

Last reviewed: 2026-07-27

PalmClaw exposes Bluetooth as one cohesive tool with atomic state, discovery, and BLE GATT actions. It is a bounded BLE client interface, not a wrapper for every Android Bluetooth API.

## Public Actions

The `bluetooth` tool supports:

- `status`, `set_power`, and `open_settings` for adapter state and Android-mediated recovery.
- `paired_list` and `ble_scan` for device discovery.
- `ble_connect` and `ble_disconnect` for one active GATT connection.
- `ble_inspect` for discovered service and characteristic UUIDs and characteristic properties.
- `ble_read` and `ble_write` for one explicit service and characteristic pair.

The tool accepts typed arguments and returns structured state or structured errors. Completing a settings or pairing flow never counts as a connection; `ble_connect` succeeds only after Android reports a connected GATT session and service discovery completes.

## Module Boundary

`BluetoothControlTool` owns the public schema, input validation, permission policy, user confirmation, and result projection. It depends only on `BleClientGateway` and `BluetoothUserInteraction`.

`AndroidBleClientGateway` owns Android adapter access, scan callbacks, the single GATT session, service discovery, best-effort MTU negotiation, operation serialization, timeouts, callback correlation, GATT status mapping, and resource cleanup. `AndroidBluetoothRuntime` shares that gateway across tool registries in the application process, so runtime reconstruction cannot create a second generic connection. Android Bluetooth types do not cross the gateway interface.

`AndroidBluetoothUserInteraction` owns runtime permission prompts and system UI transitions. Unit tests replace both boundaries with in-memory implementations.

## Connection and Operation Rules

- At most one GATT connection is active. A second connect returns `active_connection_exists`.
- Service discovery is part of connection setup and cannot be disabled.
- Reads and writes are serialized for the active connection.
- Reads require the `read` property.
- `write_type=auto` prefers a write with response, then a write without response.
- A write with response completes only after its GATT callback succeeds.
- A write without response reports `device_acknowledged=false`.
- Values are bounded to 512 bytes by the public codec and further bounded by the negotiated ATT payload size.
- Reads always return lowercase hex and return UTF-8 only after strict full-value decoding.
- A physical disconnect, connection or operation timeout, cancellation, explicit disconnect, or failed setup closes owned GATT resources. An operation timeout requires reconnecting because Android does not provide a safe generic cancellation for an in-flight characteristic operation.

Every write requires user confirmation before the gateway receives the bytes. The agent must use user-provided bytes or a trusted protocol/profile description; the generic tool does not infer device commands.

## Deliberate Exclusions

The current agent use case does not justify:

- Classic Bluetooth discovery, RFCOMM sockets, or data transfer.
- Multiple concurrent GATT connections or background reconnect.
- Notification or indication subscriptions, which require a cross-turn event ownership model.
- Descriptor actions, bonding control, RSSI polling, or connection-priority controls.
- Device-profile decoding or command generation in the generic transport.

These should be added only with a user-facing workflow, clear lifecycle ownership, and focused tests. Device-specific semantics belong in a profile module or skill above the transport.

## Verification

Automated checks cover strict value encoding, the public action set, connection truthfulness, structured inspect/read results, and write confirmation with zero mutation on cancellation.

Real-device acceptance requires a known disposable BLE peripheral:

1. Scan, connect, and inspect its service and characteristic UUIDs.
2. Read a documented readable characteristic and compare the returned bytes.
3. Cancel a write and verify the peripheral state is unchanged.
4. Confirm a documented write with response and verify the returned acknowledgement.
5. Test a write without response and verify the result does not claim device acknowledgement.
6. Attempt a second connection and verify it is rejected until disconnect.
7. Move the peripheral out of range during read and verify an explicit disconnect or timeout result.
8. Deny and later grant Bluetooth permissions and verify both paths.

Android Studio compilation, focused tests, and this device checklist remain pending until recorded in [Testing and QA](testing.md).
