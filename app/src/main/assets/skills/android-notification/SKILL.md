---
name: android-notification
description: Manage immediate PalmClaw agent notifications with stable keys, including status, active listing, post, update, cancel, and settings recovery. Use when the user explicitly asks for an Android system notification or asks to inspect or change a notification previously created by the agent.
---

# Android Notification

Use `notification` only when the user explicitly asks for a system notification or asks to manage
an existing agent notification. Do not send a notification for an ordinary chat result.

## Actions

- `status`: inspect permission, global settings, channel state, and active agent count.
- `list_active`: list notifications created through this tool.
- `post`: publish an immediate notification.
- `update`: completely replace one active notification.
- `cancel`: cancel one exact active notification.
- `open_settings`: open PalmClaw notification settings.

## Workflow

1. For an immediate notification, call `post` with a short title and useful text.
2. Keep the returned `notification_key`.
3. Use the same key for `update` or `cancel`.
4. Use `list_active` when the key is unknown.

`post` can accept a stable caller-provided key or generate one. Duplicate post is rejected.
`update` never recreates a notification that the user already dismissed.

## Scheduling

`notification` posts immediately. For a future time or recurring reminder, use `cron` instead.
Do not wait inside a tool call or use notification updates as a substitute for scheduling.

## Safety

- Never use notifications to add noise to a normal response.
- Never invent a notification request from unrelated task context.
- Cancel only the exact `notification_key` requested by the user.
- Do not treat this tool as a long-task progress channel.
- The tool manages only namespaced agent notifications; Cron, Always-on, and system-owned
  notifications are outside its scope.

Notification taps open PalmClaw. Arbitrary URLs, intents, actions, channels, sounds, importance,
ongoing state, and custom layouts are not supported.
