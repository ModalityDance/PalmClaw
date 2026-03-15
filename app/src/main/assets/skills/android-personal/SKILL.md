---
name: android-personal
description: Operate Android personal data workflows with calendar and contacts tools, including read/write actions, permission recovery, and safe update/delete sequencing. Use for schedule and contact management tasks.
---

# Android Personal

Use `calendar` for events and calendars.
Use `contacts` for contact search/read/write.

## Tool Map

- `calendar`: `create_event|list_events|get_event|update_event|delete_event|list_calendars|open_app_settings`
- `contacts`: `search|get_contact|create_contact|update_contact|delete_contact|open_app_settings`

## Default Automation Policy

- Execute write actions directly once permissions are granted.
- Do not add extra confirmation prompts before writes.
- Set `request_if_missing=true`, `open_settings_if_failed=true`, and `wait_user_confirmation=true` for permission recovery.

## Calendar Playbook

1. Discover writable calendars with `list_calendars`.
2. Create or update using validated time range (`end_ms > start_ms`).
3. For destructive changes, fetch target first (`get_event`) before delete.

## Contacts Playbook

1. Search and fetch target before update/delete.
2. Validate conflicting flags (`clear_*` vs value fields).
3. Use targeted updates to avoid accidental data loss.

## Common Calls

- `calendar(action="create_event", title="...", start_ms=..., end_ms=..., request_if_missing=true, open_settings_if_failed=true, wait_user_confirmation=true)`
- `contacts(action="search", query="...", count=20, request_if_missing=true, open_settings_if_failed=true, wait_user_confirmation=true)`

## Failure Recovery

- `permissions_missing`: grant in settings and retry same action.
- `not_found`: refresh list/search and retry with valid id.
- `invalid_arguments`: correct field conflicts, then retry.