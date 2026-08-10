---
name: cron
description: Schedule reminders and recurring tasks.
---

# Cron

Use the `cron` tool to schedule reminders or recurring tasks.

## Three Modes

1. Reminder - message is sent directly to user.
2. Task - message is a task description, agent executes and sends result.
3. One-time - runs once at a specific time, then auto-deletes.

## Examples

Fixed reminder:
`cron(action="add", message="Time to take a break!", every_seconds=1200)`

Dynamic task:
`cron(action="add", message="Check project status and report", every_seconds=600)`

One-time task:
`cron(action="add", message="Remind me about the meeting", at="<ISO datetime>")`

Timezone-aware cron:
`cron(action="add", message="Morning standup", cron_expr="0 9 * * 1-5", tz="America/Vancouver")`

Inspect, update, and remove:
`cron(action="list")`
`cron(action="get", job_id="abc123")`
`cron(action="update", job_id="abc123", every_seconds=1800)`
`cron(action="remove", job_id="abc123")`

Use `list` to find a job and `get` to inspect its full schedule, payload, delivery target, and last execution state. `update` changes only supplied fields. A schedule change replaces the schedule as one unit, so provide exactly one of `every_seconds`, `cron_expr` (with optional `tz`), or `at`. Use an explicit JSON `null` to clear `channel`, `to`, or `session_id`.

Remove a job only when the user asks to cancel that scheduled work. Use `enable_job` when the user wants to pause or resume it without deleting the definition.
