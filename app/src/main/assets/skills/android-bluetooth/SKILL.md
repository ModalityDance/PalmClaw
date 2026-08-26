---
name: android-bluetooth
description: Inspect Android Bluetooth state and perform bounded BLE client operations through GATT services and characteristics. Use for Bluetooth power, paired devices, BLE discovery, connection, inspection, read, write, and disconnect tasks.
---

# Android Bluetooth

Use `bluetooth` for Bluetooth state and BLE client tasks.

## Actions

- `status`: inspect adapter state and the single active BLE connection.
- `set_power`: request and verify the Bluetooth power state.
- `open_settings`: open Android Bluetooth settings.
- `paired_list`: list bonded Bluetooth devices.
- `ble_scan`: discover nearby BLE devices.
- `ble_connect`: connect one device and discover its GATT services.
- `ble_inspect`: list service UUIDs, characteristic UUIDs, and supported properties.
- `ble_read`: read one characteristic.
- `ble_write`: write one characteristic after user confirmation.
- `ble_disconnect`: close the active GATT connection.

## BLE Workflow

1. Check `status`; enable Bluetooth if needed.
2. Use `ble_scan`, or select a known device from `paired_list`.
3. Use `ble_connect` with the returned address.
4. Use `ble_inspect` before reading or writing.
5. Use only service and characteristic UUIDs returned by `ble_inspect`.
6. Disconnect when the task is complete.

Only one BLE GATT connection can be active. Disconnect it before connecting another device.

## Value Rules

- `ble_read` always returns `value_hex`. It also returns `value_utf8` only when the complete byte sequence is valid UTF-8.
- `ble_write` requires `encoding=hex|utf8`. Hex must contain complete byte pairs.
- Prefer `write_type=auto`. Select another write type only when the device documentation requires it.
- A write without response reports `device_acknowledged=false`; this means Android started the write but the device did not acknowledge it through GATT.

Never invent BLE command bytes, register values, or device protocol meanings. Write only when the user supplies the value or a trusted device protocol/profile skill defines it. Generic characteristic names or UUIDs are not enough evidence for a device command.

## Safety and Result Truth

- Every `ble_write` requires user confirmation before bytes are sent.
- A settings or pairing flow is not a successful connection. Success requires a verified active GATT connection.
- `connection_not_verified` means setup may have completed, but the device is not connected.
- Permission, cancellation, timeout, unsupported property, and GATT status failures are explicit results. Do not describe them as success.

## Scope

The generic tool does not provide Classic Bluetooth sockets, multiple simultaneous connections, background reconnect, notification subscriptions, descriptor operations, bonding control, or device-specific value decoding. Use Android settings for pairing and device management.
