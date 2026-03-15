---
name: android-media
description: Operate Android media workflows with the media tool, including photo/video capture, media listing, audio recording/playback, and permission recovery. Use for camera, gallery, microphone, and playback tasks.
---

# Android Media

Use `media` for all media actions.

## Tool Map

- `action=capture_photo`
- `action=record_video`
- `action=list_recent`
- `action=audio_record` with `mode=start|stop|status`
- `action=audio_playback` with `mode=start|stop|status`
- `action=open_app_settings`

## Default Automation Policy

- Start with direct tool execution.
- Use `request_if_missing=true` and `open_settings_if_failed=true` for permission recovery.
- Keep `wait_user_confirmation=true` only for camera/system flows that require user completion.

## Capture Playbook

1. Launch `capture_photo` or `record_video`.
2. Wait for user to complete camera UI and return.
3. Verify output URI when available.
4. Return machine-readable output URI metadata.

## Audio Playbook

1. Start recording with `audio_record(mode="start")`.
2. Stop with `audio_record(mode="stop")`.
3. Replay with `audio_playback(mode="start", path="...")`.

## Common Calls

- `media(action="list_recent", kind="images", count=10, request_if_missing=true, open_settings_if_failed=true, wait_user_confirmation=true)`
- `media(action="capture_photo", wait_user_confirmation=true, check_output_after_confirm=true)`
- `media(action="audio_record", mode="start", filename="note.m4a")`

## Failure Recovery

- `permissions_missing`: run with permission recovery flags.
- `capture_not_found` or `record_not_found`: retake and ensure save is completed.
- `ui_unavailable`: keep app foreground and retry.