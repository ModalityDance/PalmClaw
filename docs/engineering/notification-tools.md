# Notification Tool Contract

Last reviewed: 2026-07-27

PalmClaw exposes immediate agent notifications through one cohesive `notification` tool. It owns
only notifications created through this interface. Cron reminders, Always-on foreground
notifications, update notifications, and Android system notifications keep their existing owners.

## Public Actions

- `status` returns permission, application setting, channel, and active-count state.
- `list_active` returns bounded active agent notifications.
- `post` publishes a new notification with an optional stable `notification_key`.
- `update` completely replaces one active notification.
- `cancel` removes one exact active notification.
- `open_settings` opens the PalmClaw channel or application notification settings.

`post` generates and returns a key when none is supplied. A supplied key is canonicalized to
lowercase, must begin with a letter or digit, and may then contain letters, digits, dots,
underscores, or hyphens, up to 64 characters total. Duplicate post is rejected. Update requires a
still-active key and never intentionally recreates a notification that the user already dismissed.

Title length is bounded to 120 characters and text to 4,000 characters. Optional
`timeout_sec` is bounded from 5 seconds to 7 days. Notification taps open PalmClaw.

## Module Design

`NotificationControlTool` owns the public schema, argument validation, permission policy, and
structured results. Its test surface is the same interface the agent uses.

`NotificationGateway` is the Android seam. `AndroidNotificationGateway` hides channel creation,
stable Android `(tag, id)` identity, PendingIntent construction, mutation serialization,
namespace filtering, active-state projection, and post-mutation verification. Unit tests use an
in-memory adapter through the same seam.

`AndroidNotificationUserInteraction` owns permission prompts and system settings handoff. A
process-wide gateway instance keeps create/update checks serialized when more than one tool
registry exists.

## Identity and Ownership

Agent notifications use tags under `palmclaw.agent.<notification_key>` and one internal numeric
ID. List, update, and cancel filter both values. This prevents the tool from observing or changing
Cron, Always-on, legacy, or other application notifications.

The Android notification manager is the current-state source. PalmClaw does not persist a second
notification registry. Active notifications therefore survive PalmClaw process reconstruction
while remaining visible, and disappear from the interface after user dismissal or system timeout.

The adapter reuses the existing `palmclaw_default` channel so current user channel preferences are
preserved. Channel importance, sound, and vibration remain controlled by Android settings.

## Agent Policy

- Post only after the user explicitly requests a system notification.
- Do not add a notification to an ordinary chat response.
- Use `cron` for a future or recurring notification.
- Update and cancel only a returned or explicitly supplied `notification_key`.
- Do not use this tool as a long-task progress transport.

## Deliberate Exclusions

The generic tool does not expose cancel-all, arbitrary channels, arbitrary URL or Intent targets,
actions, ongoing state, progress, grouping, conversations, bubbles, images, custom layouts,
full-screen intents, notification listeners, or scheduling.

Cron and Always-on notifications are not refactored into this module because their lifecycle is
owned by scheduled execution and a foreground service respectively.

## Verification

Automated tests cover key/tag mapping, all six actions, generated and stable keys, namespaced
create/list/update/cancel, duplicate and missing-key behavior, validation before permission
requests, permission failure, and legacy tool-toggle migration.

Real-device acceptance:

1. Deny and grant `POST_NOTIFICATIONS` on Android 13 or later.
2. Post, list, update, and cancel a stable key.
3. Confirm a duplicate post is rejected.
4. Dismiss a notification manually and confirm update returns `notification_not_found`.
5. Confirm the tap opens PalmClaw.
6. Confirm timeout removes the notification from `list_active`.
7. Restart PalmClaw and manage a notification posted before restart.
8. Disable the application and channel separately and verify distinct structured failures.
9. Confirm Cron and Always-on notifications remain unaffected.
