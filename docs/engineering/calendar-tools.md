# Calendar Tool Contract

Last reviewed: 2026-07-18

PalmClaw exposes Android Calendar through one `calendar` tool with typed actions. The tool owns user-facing calendar semantics; `CalendarProviderGateway` owns `CalendarContract` queries and mutations. Provider rows and Android constants do not leak into the tool schema.

## Supported actions

The tool supports event creation, instance-range listing, event details, series or occurrence updates, series or occurrence deletion, RSVP, calendar listing, event UI fallback, and app settings fallback.

Event writes expose title, time range, all-day state, start and end timezones, description, location, availability, access level, guest controls, recurrence, reminders, and attendees. Calendar listing returns write access and provider capability metadata so the agent can choose valid reminder, attendee, and availability values.

## Recurrence and occurrence rules

Recurrence input is structured. PalmClaw maps daily, weekly, monthly, and yearly frequency plus interval, count or end time, weekday, month-day, month, set-position, week-start, additional-date, and excluded-date fields to Android recurrence values.

Reads return the structured form when the provider rule is within this supported subset. They always retain the raw provider `RRULE`, `RDATE`, and `EXDATE`. A complex provider rule that PalmClaw cannot structure is reported as unsupported and is preserved during unrelated series updates.

Recurring mutations accept `scope=series` or `scope=occurrence`. Occurrence operations require the stable `instance_start_ms` returned by `list_events`. PalmClaw creates or updates an Android recurrence exception for one occurrence. It does not expose “this and following” because that operation requires splitting a series and has provider-specific edge cases.

## Replacement and safety semantics

For series updates, omitted `reminders` or `attendees` preserve the current rows. A present attendee array is the final managed guest list; organizer and current-account rows are provider-owned and remain intact. A present empty array clears managed guests. Reminder arrays follow the same omitted, replace, and clear rule. Occurrence updates cannot change recurrence, calendar, reminders, or attendees.

Cross-calendar movement is not exposed as an update. The caller must create a new event and separately delete the old event. Every deletion requires interactive confirmation. Other event updates and RSVP do not add an extra confirmation after Android permissions are available.

All fields are validated before the provider mutation. Multi-table create and series-update operations use provider batches. PalmClaw reloads successful writes and checks the stored event and requested relation replacements before returning success.

## Deliberate exclusions

The tool does not expose sync-adapter fields, extended properties, colors, provider UID fields, account or calendar CRUD, managed-profile data, cross-calendar move, “this and following” series splitting, or occurrence-only RSVP.

## Verification

Local recurrence tests cover structured encoding, supported decoding, raw fallback, recurrence termination conflicts, duration handling, and all-day durations. Instrumented fake-gateway tests cover structured creation, preserve-versus-clear relation semantics, occurrence exceptions, series-only field rejection, and deletion confirmation.

Device verification should cover one local calendar and one synced calendar when available. Confirm timed and all-day creation, recurrence expansion, reminder and attendee replacement, one-occurrence edit and deletion, series edit and deletion, RSVP, and system Calendar opening.

Android Studio compilation and the focused automated tests passed on 2026-07-26. The real-provider device checks above remain pending.
