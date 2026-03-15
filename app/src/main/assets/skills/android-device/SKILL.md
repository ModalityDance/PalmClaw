---
name: android-device
description: Operate Android device capabilities with device_status and device tools, including permissions, location, notifications, URL open, share, and settings recovery. Use for phone status, location, permissions, notifications, links, and Android system handoff tasks.
---

# Android Device

Use `device_status` for state and permission checks.
Use `device` for system actions.

## Tool Map

- `device_status` with `action=info|permissions|location`
- `device` with `action=notify|open_url|share|open_app_settings`

## Default Automation Policy

- Set `request_if_missing=true` when permissions may be missing.
- Set `open_settings_if_failed=true` for automatic recovery.
- Set `wait_user_confirmation=true` only when the app cannot complete the system step itself.
- Avoid pre-confirmation for normal operations.

## Location Playbook

1. Call `device_status(action="location", provider="auto", prefer_fine=true)`.
2. If no fix, retry with `provider="network"`.
3. If location service is disabled, allow settings handoff and continue.
4. If still unavailable, return recoverable error with next step.

## Common Calls

- `device_status(action="permissions", permissions=[...], request_if_missing=true, open_settings_if_failed=true, wait_user_confirmation=true)`
- `device_status(action="location", provider="auto", prefer_fine=true, open_settings_if_failed=true, wait_user_confirmation=true)`
- `device(action="notify", title="...", text="...")`
- `device(action="open_url", url="https://...")`
- `device(action="share", text="...", subject="...")`

## Failure Recovery

- `permissions_missing` or `permissions_denied`: grant permissions and retry.
- `location_disabled`: enable location service in settings, then continue.
- `ui_unavailable`: foreground app and retry same call.