---
name: android-bluetooth
description: Operate Android Bluetooth workflows with the bluetooth tool, including power, pairing handoff, BLE scan/connect/disconnect, and permission/settings recovery. Use for Bluetooth and BLE device tasks.
---

# Android Bluetooth

Use `bluetooth` for all Bluetooth and BLE actions.

## Tool Map

- `action=status`
- `action=set_power`
- `action=open_settings`
- `action=paired_list`
- `action=ble_scan`
- `action=ble_connect`
- `action=ble_disconnect`

## Default Automation Policy

- Auto-attempt permission request first.
- Keep `open_settings_if_failed=true` for permission fallback.
- Keep `wait_user_confirmation=true` only for manual system steps.
- Use `allow_manual_success=true` only when manual setup is acceptable for upstream task flow.

## Connect Playbook

1. `bluetooth(action="status")`.
2. If needed: `bluetooth(action="set_power", enabled=true)`.
3. Discover: `bluetooth(action="ble_scan", seconds=5, max_results=20)`.
4. Connect: `bluetooth(action="ble_connect", address="...", timeout_sec=20, discover_services=true, open_settings_if_failed=true, wait_user_confirmation=true)`.

## Disconnect Playbook

- Single: `bluetooth(action="ble_disconnect", address="...")`
- All: `bluetooth(action="ble_disconnect", all=true)`

## Failure Recovery

- Permission failures: grant in settings and continue same run.
- Power-off failure: hand off to system Bluetooth settings, then continue.
- Connect timeout: retry once, then return recoverable error with next step.