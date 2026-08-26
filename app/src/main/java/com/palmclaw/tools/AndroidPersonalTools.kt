package com.palmclaw.tools

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.CalendarContract
import android.provider.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.util.Locale
import java.util.TimeZone

fun createAndroidPersonalToolSet(context: Context): List<Tool> {
    return listOf(
        CalendarControlTool(context),
        ContactsControlTool(context)
    )
}

internal class CalendarControlTool(
    private val context: Context,
    private val gateway: CalendarProviderGateway = AndroidCalendarProviderGateway(context),
    private val permissionRequester: suspend (String, List<String>, Boolean, Boolean, Boolean) -> ToolResult? =
        { action, required, requestIfMissing, openSettingsIfFailed, waitUserConfirmation ->
            ensurePersonalPermissionsInteractive(
                context = context,
                toolName = "calendar",
                action = action,
                required = required,
                requestIfMissing = requestIfMissing,
                openSettingsIfFailed = openSettingsIfFailed,
                waitUserConfirmation = waitUserConfirmation
            )
        },
    private val confirmationRequester: suspend (String, String, String, String) -> Boolean? =
        { title, message, confirmLabel, cancelLabel ->
            AndroidUserActionBridge.requestUserConfirmation(title, message, confirmLabel, cancelLabel)
        },
    private val eventLauncher: (Intent) -> ToolResult = { intent -> launchIntent(context, intent) }
) : Tool, TimedTool {

    override val name: String = "calendar"
    override val description: String =
        "Manage Android calendars and events with structured recurrence, reminders, attendees, RSVP, and system UI fallback. " +
            "Use scope=series or scope=occurrence with the list_events instance_start_ms. On series update, omitted reminder/attendee arrays are preserved; present arrays replace reminders or managed guests and [] clears them. Deletions require user confirmation."
    override val timeoutMs: Long = 120_000L
    override val jsonSchema: JsonObject = buildJsonObject {
        put("type", "object")
        put("additionalProperties", false)
        put("required", Json.parseToJsonElement("[\"action\"]"))
        put(
            "properties",
            Json.parseToJsonElement(
                """
                {
                  "action":{"type":"string","enum":["create_event","list_events","get_event","update_event","delete_event","respond_to_event","list_calendars","open_event","open_app_settings"]},
                  "event_id":{"type":"integer"},
                  "calendar_id":{"type":"integer"},
                  "scope":{"type":"string","enum":["series","occurrence"]},
                  "instance_start_ms":{"type":"integer"},
                  "title":{"type":"string"},
                  "start_ms":{"type":"integer"},
                  "end_ms":{"type":"integer"},
                  "all_day":{"type":"boolean"},
                  "description":{"type":"string"},
                  "location":{"type":"string"},
                  "timezone":{"type":"string"},
                  "end_timezone":{"type":"string"},
                  "availability":{"type":"string","enum":["busy","free","tentative"]},
                  "access_level":{"type":"string","enum":["default","private","public"]},
                  "guests_can_modify":{"type":"boolean"},
                  "guests_can_invite_others":{"type":"boolean"},
                  "guests_can_see_guests":{"type":"boolean"},
                  "recurrence":{
                    "type":"object",
                    "additionalProperties":false,
                    "required":["frequency"],
                    "properties":{
                      "frequency":{"type":"string","enum":["daily","weekly","monthly","yearly"]},
                      "interval":{"type":"integer","minimum":1,"maximum":999},
                      "count":{"type":"integer","minimum":1,"maximum":9999},
                      "until_ms":{"type":"integer"},
                      "by_weekdays":{"type":"array","maxItems":7,"items":{"type":"string","enum":["monday","tuesday","wednesday","thursday","friday","saturday","sunday"]}},
                      "by_month_days":{"type":"array","maxItems":62,"items":{"type":"integer","minimum":-31,"maximum":31}},
                      "by_months":{"type":"array","maxItems":12,"items":{"type":"integer","minimum":1,"maximum":12}},
                      "by_set_positions":{"type":"array","maxItems":32,"items":{"type":"integer","minimum":-366,"maximum":366}},
                      "week_start":{"type":"string","enum":["monday","tuesday","wednesday","thursday","friday","saturday","sunday"]},
                      "additional_dates_ms":{"type":"array","maxItems":100,"items":{"type":"integer"}},
                      "excluded_dates_ms":{"type":"array","maxItems":100,"items":{"type":"integer"}}
                    }
                  },
                  "clear_recurrence":{"type":"boolean"},
                  "reminders":{
                    "type":"array","maxItems":20,
                    "items":{"type":"object","additionalProperties":false,"required":["minutes_before"],"properties":{"minutes_before":{"type":"integer","minimum":-1,"maximum":40320},"method":{"type":"string","enum":["default","alert"]}}}
                  },
                  "attendees":{
                    "type":"array","maxItems":100,
                    "items":{"type":"object","additionalProperties":false,"required":["email"],"properties":{"email":{"type":"string","minLength":3},"name":{"type":"string"},"type":{"type":"string","enum":["required","optional","resource"]}}}
                  },
                  "response":{"type":"string","enum":["accepted","declined","tentative","none"]},
                  "from_ms":{"type":"integer"},
                  "to_ms":{"type":"integer"},
                  "count":{"type":"integer","minimum":1,"maximum":100},
                  "request_if_missing":{"type":"boolean"},
                  "open_settings_if_failed":{"type":"boolean"},
                  "wait_user_confirmation":{"type":"boolean"}
                }
                """.trimIndent()
            )
        )
    }

    override suspend fun run(argumentsJson: String): ToolResult = withContext(Dispatchers.IO) {
        val args = Json.decodeFromString<CalendarArgs>(argumentsJson)
        val action = args.action.trim().lowercase(Locale.US)
        return@withContext when (action) {
            "create_event" -> actionCreateEvent(args)
            "list_events" -> actionListEvents(args)
            "get_event" -> actionGetEvent(args)
            "update_event" -> actionUpdateEvent(args)
            "delete_event" -> actionDeleteEvent(args)
            "respond_to_event" -> actionRespondToEvent(args)
            "list_calendars" -> actionListCalendars(args)
            "open_event" -> actionOpenEvent(args)
            "open_app_settings" -> actionOpenAppSettings(action)
            else -> personalError(
                toolName = name,
                action = action,
                code = "unsupported_action",
                message = "Unsupported action '${args.action}'.",
                nextStep = "Use a supported calendar action from the tool schema."
            )
        }
    }

    private suspend fun actionCreateEvent(args: CalendarArgs): ToolResult {
        val action = "create_event"
        val title = args.title?.trim().orEmpty()
        if (title.isBlank()) {
            return personalError(
                toolName = name,
                action = action,
                code = "invalid_arguments",
                message = "title is required.",
                nextStep = "Pass a non-empty title."
            )
        }

        val permissionsError = requestCalendarPermissions(
            action,
            listOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR),
            args
        )
        if (permissionsError != null) return permissionsError

        val start = args.startMs ?: (System.currentTimeMillis() + 5 * 60_000L)
        val end = args.endMs ?: (start + 60 * 60_000L)
        if (end <= start) {
            return personalError(
                toolName = name,
                action = action,
                code = "invalid_arguments",
                message = "end_ms must be > start_ms.",
                nextStep = "Provide end_ms greater than start_ms."
            )
        }

        val calendar = findWritableCalendar(args.calendarId)
            ?: return personalError(
                toolName = name,
                action = action,
                code = "no_writable_calendar",
                message = if (args.calendarId != null) {
                    "Calendar id=${args.calendarId} is not writable or not found."
                } else {
                    "No writable calendar found."
                },
                nextStep = if (args.calendarId != null) {
                    "Use list_calendars to pick a writable calendar_id, then retry."
                } else {
                    "Add/enable a calendar account in system Calendar settings, then retry."
                }
            )

        val draftResult = buildCreateDraft(args, calendar, title, start, end)
        if (draftResult is CalendarDraftResult.Invalid) return draftResult.toToolResult(action)
        val draft = (draftResult as CalendarDraftResult.Valid).draft
        val reminders = validateReminders(args.reminders, calendar)
        if (reminders is CalendarReminderResult.Invalid) return reminders.toToolResult(action)
        val attendees = validateAttendees(args.attendees, calendar)
        if (attendees is CalendarAttendeeResult.Invalid) return attendees.toToolResult(action)
        val reminderDrafts = (reminders as CalendarReminderResult.Valid).values
        val attendeeDrafts = (attendees as CalendarAttendeeResult.Valid).values
        val mutation = gateway.mutate(
            CalendarMutation.Create(
                event = draft,
                reminders = reminderDrafts,
                attendees = attendeeDrafts
            )
        )
        val eventId = mutation.eventId
            ?: return personalError(
                toolName = name,
                action = action,
                code = "insert_failed",
                message = "Calendar provider did not return the new event id.",
                nextStep = "Check the calendar account state and retry."
            )
        val reloaded = gateway.getEvent(eventId)
            ?: return personalError(
                toolName = name,
                action = action,
                code = "verification_failed",
                message = "The provider reported success, but the new event could not be reloaded.",
                nextStep = "Use list_events to inspect the calendar before retrying."
            )
        if (!reloaded.matchesDraft(draft, reminderDrafts, attendeeDrafts, calendar.accountIdentity)) return verificationFailed(action)
        return personalOk(
            toolName = name,
            action = action,
            message = "calendar event created: id=$eventId start=${nowText(start)} end=${nowText(end)}"
        ) {
            putAggregate(reloaded)
            put("affected_rows", mutation.affectedRows)
        }
    }

    private suspend fun actionListEvents(args: CalendarArgs): ToolResult {
        val action = "list_events"
        val permissionsError = requestCalendarPermissions(action, listOf(Manifest.permission.READ_CALENDAR), args)
        if (permissionsError != null) return permissionsError

        val from = args.fromMs ?: System.currentTimeMillis()
        val to = args.toMs ?: (from + 7L * 24L * 60L * 60L * 1000L)
        if (to <= from) {
            return personalError(
                toolName = name,
                action = action,
                code = "invalid_arguments",
                message = "to_ms must be > from_ms.",
                nextStep = "Provide a valid time range."
            )
        }
        val count = (args.count ?: 20).coerceIn(1, 100)

        val rows = gateway.listInstances(from, to, count)

        val text = if (rows.isEmpty()) {
            "No events found."
        } else {
            rows.joinToString("\n") { row ->
                "id=${row.seriesEventId} | instance=${row.instanceEventId} | cal=${row.calendarId} | ${row.title} | ${nowText(row.beginMs)} -> ${nowText(row.endMs)} | all_day=${row.allDay}${if (row.location.isBlank()) "" else " | ${row.location}"}"
            }
        }

        return personalOk(
            toolName = name,
            action = action,
            message = text
        ) {
            put("from_ms", from)
            put("to_ms", to)
            put("count", rows.size)
            putJsonArray("events") {
                rows.forEach { row -> add(row.toJson()) }
            }
        }
    }

    private suspend fun actionGetEvent(args: CalendarArgs): ToolResult {
        val action = "get_event"
        val eventId = args.eventId
            ?: return personalError(
                toolName = name,
                action = action,
                code = "invalid_arguments",
                message = "event_id is required.",
                nextStep = "Pass event_id."
            )

        val permissionsError = requestCalendarPermissions(action, listOf(Manifest.permission.READ_CALENDAR), args)
        if (permissionsError != null) return permissionsError

        val event = gateway.getEvent(eventId)
            ?: return personalError(
                toolName = name,
                action = action,
                code = "not_found",
                message = "Event id=$eventId not found.",
                nextStep = "Use list_events to find a valid event_id."
            )
        val occurrence = args.instanceStartMs?.let { start ->
            resolveOccurrence(eventId, start)
                ?: return occurrenceNotFound(action, eventId, start)
        }

        return personalOk(
            toolName = name,
            action = action,
            message = occurrence?.let {
                "${eventSummary(event.event)} | occurrence ${nowText(it.beginMs)} -> ${nowText(it.endMs)}"
            } ?: eventSummary(event.event)
        ) {
            putAggregate(event)
            occurrence?.let { put("occurrence", it.toJson()) }
        }
    }

    private suspend fun actionUpdateEvent(args: CalendarArgs): ToolResult {
        val action = "update_event"
        val eventId = args.eventId ?: return invalidCalendarArguments(action, "event_id is required.", "Pass event_id.")
        val permissionsError = requestCalendarPermissions(
            action,
            listOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR),
            args
        )
        if (permissionsError != null) return permissionsError

        val current = gateway.getEvent(eventId)
            ?: return calendarNotFound(action, eventId)
        if (current.event.originalId != null) return exceptionIdNotSupported(action, current.event)
        val calendar = gateway.listCalendars().firstOrNull { it.id == current.event.calendarId && it.writable }
            ?: return personalError(
                toolName = name,
                action = action,
                code = "calendar_not_writable",
                message = "The event calendar is not writable.",
                nextStep = "Choose an event in a writable calendar."
            )
        if (args.calendarId != null && args.calendarId != current.event.calendarId) {
            return personalError(
                toolName = name,
                action = action,
                code = "cross_calendar_move_not_supported",
                message = "Updating calendar_id would move the event between calendars.",
                nextStep = "Create a copy in the target calendar and delete the original separately."
            )
        }
        if (!args.hasEventChanges()) {
            return invalidCalendarArguments(action, "No update fields provided.", "Provide at least one event, recurrence, reminder, or attendee field.")
        }

        return when (args.scopeValue) {
            "series" -> updateSeries(action, args, current, calendar)
            "occurrence" -> updateOccurrence(action, args, current, calendar)
            else -> invalidCalendarArguments(action, "scope must be series or occurrence.", "Use scope=series or scope=occurrence.")
        }
    }

    private fun updateSeries(
        action: String,
        args: CalendarArgs,
        current: CalendarEventAggregate,
        calendar: CalendarInfo
    ): ToolResult {
        if ((args.guestsCanModify != null || args.guestsCanInviteOthers != null || args.guestsCanSeeGuests != null) &&
            !current.event.isOrganizer
        ) {
            return personalError(
                name,
                action,
                "guest_policy_update_not_allowed",
                "Only the event organizer can change guest policy fields.",
                "Omit guest policy fields or edit an event organized by the current account."
            )
        }
        if (args.attendees != null && !current.event.canInviteOthers && !current.event.isOrganizer) {
            return personalError(
                name,
                action,
                "attendee_update_not_allowed",
                "The current account cannot modify attendees for this event.",
                "Open the event in the system Calendar app to inspect organizer controls."
            )
        }
        if (args.attendees != null && calendar.accountIdentity.isBlank()) {
            return personalError(
                name,
                action,
                "calendar_identity_unresolved",
                "The calendar provider did not expose the current account identity, so guest replacement cannot safely preserve the self attendee row.",
                "Open the event in the system Calendar app to manage guests."
            )
        }
        val draftResult = buildSeriesUpdateDraft(args, current.event, calendar)
        if (draftResult is CalendarDraftResult.Invalid) return draftResult.toToolResult(action)
        val reminders = args.reminders?.let { validateReminders(it, calendar) }
        if (reminders is CalendarReminderResult.Invalid) return reminders.toToolResult(action)
        val attendees = args.attendees?.let { validateAttendees(it, calendar) }
        if (attendees is CalendarAttendeeResult.Invalid) return attendees.toToolResult(action)
        val mutation = gateway.mutate(
            CalendarMutation.UpdateSeries(
                eventId = current.event.id,
                event = (draftResult as CalendarDraftResult.Valid).draft,
                reminders = (reminders as? CalendarReminderResult.Valid)?.values,
                attendees = (attendees as? CalendarAttendeeResult.Valid)?.values,
                accountIdentity = calendar.accountIdentity
            )
        )
        if (mutation.affectedRows <= 0) return mutationFailed(action, "Calendar update affected 0 rows.")
        val reloaded = gateway.getEvent(current.event.id)
            ?: return verificationFailed(action)
        if (!reloaded.matchesDraft(
                (draftResult as CalendarDraftResult.Valid).draft,
                (reminders as? CalendarReminderResult.Valid)?.values,
                (attendees as? CalendarAttendeeResult.Valid)?.values,
                calendar.accountIdentity
            )
        ) return verificationFailed(action)
        return personalOk(name, action, "calendar event series updated: id=${current.event.id}") {
            putAggregate(reloaded)
            put("scope", "series")
            put("affected_rows", mutation.affectedRows)
        }
    }

    private fun updateOccurrence(
        action: String,
        args: CalendarArgs,
        current: CalendarEventAggregate,
        calendar: CalendarInfo
    ): ToolResult {
        if (!current.event.recurring) {
            return personalError(name, action, "not_recurring", "scope=occurrence requires a recurring event.", "Use scope=series for this event.")
        }
        if ((args.guestsCanModify != null || args.guestsCanInviteOthers != null || args.guestsCanSeeGuests != null) &&
            !current.event.isOrganizer
        ) {
            return personalError(name, action, "guest_policy_update_not_allowed", "Only the event organizer can change guest policy fields.", "Omit guest policy fields.")
        }
        if (args.recurrence != null || args.clearRecurrence == true || args.calendarId != null ||
            args.reminders != null || args.attendees != null
        ) {
            return personalError(
                name,
                action,
                "occurrence_field_not_supported",
                "Occurrence updates cannot change recurrence, calendar, reminders, or attendees.",
                "Update the series for those fields."
            )
        }
        val instance = resolveOccurrence(current.event.id, args.instanceStartMs)
            ?: return occurrenceNotFound(action, current.event.id, args.instanceStartMs)
        val draftResult = buildOccurrenceDraft(args, current.event, instance, calendar)
        if (draftResult is CalendarDraftResult.Invalid) return draftResult.toToolResult(action)
        val mutation = gateway.mutate(
            CalendarMutation.UpsertException(
                seriesEventId = current.event.id,
                originalInstanceStartMs = instance.stableInstanceStartMs,
                event = (draftResult as CalendarDraftResult.Valid).draft
            )
        )
        if (mutation.affectedRows <= 0) return mutationFailed(action, "Calendar occurrence update affected 0 rows.")
        val exceptionId = mutation.eventId ?: return verificationFailed(action)
        val reloaded = gateway.getEvent(exceptionId) ?: return verificationFailed(action)
        if (!reloaded.matchesDraft((draftResult as CalendarDraftResult.Valid).draft, null, null, calendar.accountIdentity) ||
            !reloaded.event.isExceptionFor(current.event.id, instance.stableInstanceStartMs)
        ) {
            return verificationFailed(action)
        }
        return personalOk(name, action, "calendar occurrence updated: series=${current.event.id} instance=${instance.beginMs}") {
            putAggregate(reloaded)
            put("series_event_id", current.event.id)
            put("exception_event_id", exceptionId)
            put("scope", "occurrence")
            put("instance_start_ms", instance.stableInstanceStartMs)
            put("affected_rows", mutation.affectedRows)
        }
    }

    private suspend fun actionDeleteEvent(args: CalendarArgs): ToolResult {
        val action = "delete_event"
        val eventId = args.eventId ?: return invalidCalendarArguments(action, "event_id is required.", "Pass event_id.")
        val permissionsError = requestCalendarPermissions(
            action,
            listOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR),
            args
        )
        if (permissionsError != null) return permissionsError
        val current = gateway.getEvent(eventId) ?: return calendarNotFound(action, eventId)
        if (current.event.originalId != null) return exceptionIdNotSupported(action, current.event)
        val calendar = gateway.listCalendars().firstOrNull { it.id == current.event.calendarId && it.writable }
            ?: return personalError(name, action, "calendar_not_writable", "The event calendar is not writable.", "Choose an event in a writable calendar.")
        val instance = if (args.scopeValue == "occurrence") {
            if (!current.event.recurring) {
                return personalError(name, action, "not_recurring", "scope=occurrence requires a recurring event.", "Use scope=series.")
            }
            resolveOccurrence(eventId, args.instanceStartMs)
                ?: return occurrenceNotFound(action, eventId, args.instanceStartMs)
        } else if (args.scopeValue == "series") {
            null
        } else {
            return invalidCalendarArguments(action, "scope must be series or occurrence.", "Use scope=series or scope=occurrence.")
        }
        when (confirmationRequester(
            "Delete Calendar Event",
            if (instance == null) {
                "Delete the complete event series '${current.event.title}'?"
            } else {
                "Delete this occurrence of '${current.event.title}' at ${nowText(instance.beginMs)}?"
            },
            "Delete",
            "Cancel"
        )) {
            false -> return personalError(name, action, "user_cancelled", "Calendar deletion was cancelled.", "No event was changed.")
            null -> return personalError(name, action, "ui_unavailable", "Confirmation UI is unavailable.", "Open PalmClaw and retry the deletion.")
            true -> Unit
        }
        val mutation = if (instance == null) {
            CalendarMutation.DeleteSeries(eventId)
        } else {
            val draftResult = buildOccurrenceDraft(args, current.event, instance, calendar, cancelled = true)
            if (draftResult is CalendarDraftResult.Invalid) return draftResult.toToolResult(action)
            CalendarMutation.UpsertException(
                seriesEventId = eventId,
                originalInstanceStartMs = instance.stableInstanceStartMs,
                event = (draftResult as CalendarDraftResult.Valid).draft
            )
        }
        val result = gateway.mutate(mutation)
        if (result.affectedRows <= 0) return mutationFailed(action, "Calendar deletion affected 0 rows.")
        val deletedVerified = if (instance == null) {
            gateway.getEvent(eventId) == null
        } else {
            result.eventId?.let(gateway::getEvent)?.event?.let { event ->
                event.status == CalendarDomainValues.cancelledEventStatus &&
                    event.isExceptionFor(eventId, instance.stableInstanceStartMs)
            } == true
        }
        if (!deletedVerified) return verificationFailed(action)
        return personalOk(name, action, if (instance == null) "calendar event series deleted: id=$eventId" else "calendar occurrence deleted: id=$eventId") {
            put("event_id", eventId)
            put("scope", if (instance == null) "series" else "occurrence")
            instance?.let { put("instance_start_ms", it.stableInstanceStartMs) }
            put("affected_rows", result.affectedRows)
        }
    }

    private suspend fun actionRespondToEvent(args: CalendarArgs): ToolResult {
        val action = "respond_to_event"
        val eventId = args.eventId ?: return invalidCalendarArguments(action, "event_id is required.", "Pass event_id.")
        val response = args.response?.trim()?.lowercase(Locale.US)
            ?: return invalidCalendarArguments(action, "response is required.", "Use accepted, declined, tentative, or none.")
        if (args.scopeValue != "series") {
            return personalError(name, action, "occurrence_rsvp_not_supported", "RSVP applies to the event series only.", "Use scope=series.")
        }
        val status = CalendarDomainValues.responseStatus(response)
            ?: return invalidCalendarArguments(action, "Unsupported response '$response'.", "Use accepted, declined, tentative, or none.")
        val permissionsError = requestCalendarPermissions(
            action,
            listOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR),
            args
        )
        if (permissionsError != null) return permissionsError
        val aggregate = gateway.getEvent(eventId) ?: return calendarNotFound(action, eventId)
        if (aggregate.event.originalId != null) return exceptionIdNotSupported(action, aggregate.event)
        val calendar = gateway.listCalendars().firstOrNull { it.id == aggregate.event.calendarId }
        val accountIdentity = calendar?.accountIdentity.orEmpty()
        val selfRows = aggregate.attendees.filter {
            CalendarDomainValues.isAttendeeRelationship(it.relationship) &&
                accountIdentity.isNotBlank() && it.email.equals(accountIdentity, ignoreCase = true)
        }.distinctBy { it.id }
        if (selfRows.size != 1) {
            return personalError(
                name,
                action,
                "rsvp_identity_unresolved",
                "PalmClaw could not uniquely resolve the current account attendee row.",
                "Open the event in the system Calendar app to respond."
            )
        }
        val result = gateway.mutate(CalendarMutation.Respond(selfRows.single().id, status))
        if (result.affectedRows <= 0) return mutationFailed(action, "RSVP update affected 0 rows.")
        val reloaded = gateway.getEvent(eventId) ?: return verificationFailed(action)
        val verifiedSelf = reloaded.attendees.singleOrNull { it.id == selfRows.single().id }
        if (verifiedSelf?.status != status) return verificationFailed(action)
        return personalOk(name, action, "calendar response updated: id=$eventId response=$response") {
            putAggregate(reloaded)
            put("response", response)
            put("affected_rows", result.affectedRows)
        }
    }

    private suspend fun actionOpenEvent(args: CalendarArgs): ToolResult {
        val action = "open_event"
        val eventId = args.eventId ?: return invalidCalendarArguments(action, "event_id is required.", "Pass event_id.")
        if (args.instanceStartMs != null) {
            val permissionsError = requestCalendarPermissions(action, listOf(Manifest.permission.READ_CALENDAR), args)
            if (permissionsError != null) return permissionsError
        }
        val event = runCatching { gateway.getEvent(eventId)?.event }.getOrNull()
        val occurrence = args.instanceStartMs?.let { start ->
            gateway.findOccurrence(eventId, start) ?: return occurrenceNotFound(action, eventId, start)
        }
        val intent = Intent(Intent.ACTION_VIEW).apply {
            data = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId)
            if (occurrence != null) {
                putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, occurrence.beginMs)
                putExtra(CalendarContract.EXTRA_EVENT_END_TIME, occurrence.endMs)
            } else {
                event?.let {
                    putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, it.startMs)
                    it.resolvedEndMs?.let { end -> putExtra(CalendarContract.EXTRA_EVENT_END_TIME, end) }
                }
            }
        }
        val launch = eventLauncher(intent)
        return if (launch.isError) {
            personalError(name, action, "open_event_failed", launch.content, "Open the system Calendar app manually.")
        } else {
            personalOk(name, action, "calendar event opened: id=$eventId") {
                put("event_id", eventId)
                occurrence?.let { put("instance_start_ms", it.stableInstanceStartMs) }
            }
        }
    }

    private suspend fun actionListCalendars(args: CalendarArgs): ToolResult {
        val action = "list_calendars"
        val permissionsError = requestCalendarPermissions(action, listOf(Manifest.permission.READ_CALENDAR), args)
        if (permissionsError != null) return permissionsError
        val rows = gateway.listCalendars()
        val message = if (rows.isEmpty()) "No calendars found." else rows.joinToString("\n") {
            "id=${it.id} | ${it.name} | owner=${it.owner.ifBlank { "(local)" }} | writable=${it.writable} | visible=${it.visible}"
        }
        return personalOk(name, action, message) {
            put("count", rows.size)
            putJsonArray("calendars") { rows.forEach { add(it.toJson()) } }
        }
    }

    private fun findWritableCalendar(preferredCalendarId: Long?): CalendarInfo? {
        val writable = gateway.listCalendars().filter { it.writable }
        return if (preferredCalendarId != null) {
            writable.firstOrNull { it.id == preferredCalendarId }
        } else {
            writable.sortedWith(
                compareByDescending<CalendarInfo> { it.primary }
                    .thenByDescending { it.owner.isNotBlank() }
                    .thenBy { it.id }
            ).firstOrNull()
        }
    }

    private suspend fun requestCalendarPermissions(
        action: String,
        required: List<String>,
        args: CalendarArgs
    ): ToolResult? = permissionRequester(
        action,
        required,
        args.requestIfMissing ?: true,
        args.openSettingsIfFailed ?: true,
        args.waitUserConfirmation ?: true
    )

    private fun buildCreateDraft(
        args: CalendarArgs,
        calendar: CalendarInfo,
        title: String,
        startMs: Long,
        endMs: Long
    ): CalendarDraftResult {
        if (args.clearRecurrence == true) {
            return CalendarDraftResult.Invalid("invalid_arguments", "clear_recurrence is only valid when updating an existing event.")
        }
        val allDay = args.allDay == true
        val timeError = validateEventTimes(startMs, endMs, allDay, args.timezone, args.endTimezone)
        if (timeError != null) return timeError
        val timezone = if (allDay) "UTC" else args.timezone?.trim().orEmpty().ifBlank {
            calendar.timezone.ifBlank { TimeZone.getDefault().id }
        }
        val endTimezone = if (allDay) "UTC" else args.endTimezone?.trim()?.ifBlank { null } ?: timezone
        val recurrence = encodeRecurrence(args.recurrence, startMs, endMs, allDay)
        if (recurrence is CalendarRecurrenceResult.Invalid) {
            return CalendarDraftResult.Invalid(recurrence.code, recurrence.message)
        }
        val encoded = (recurrence as CalendarRecurrenceResult.Valid).value
        val availability = availabilityValue(args.availability, CalendarDomainValues.defaultAvailability)
            ?: return CalendarDraftResult.Invalid("invalid_availability", "Unsupported availability value.")
        if (calendar.allowedAvailability.isNotEmpty() && availability !in calendar.allowedAvailability) {
            return CalendarDraftResult.Invalid("availability_not_supported", "The selected calendar does not support this availability value.")
        }
        val access = accessLevelValue(args.accessLevel, CalendarDomainValues.defaultAccessLevel)
            ?: return CalendarDraftResult.Invalid("invalid_access_level", "Unsupported access_level value.")
        return CalendarDraftResult.Valid(
            CalendarEventDraft(
                calendarId = calendar.id,
                title = title,
                startMs = startMs,
                endMs = if (encoded == null) endMs else null,
                duration = encoded?.duration,
                description = args.description.orEmpty(),
                location = args.location.orEmpty(),
                allDay = allDay,
                timezone = timezone,
                endTimezone = endTimezone,
                availability = availability,
                accessLevel = access,
                guestsCanModify = args.guestsCanModify ?: false,
                guestsCanInviteOthers = args.guestsCanInviteOthers ?: true,
                guestsCanSeeGuests = args.guestsCanSeeGuests ?: true,
                rrule = encoded?.rrule,
                rdate = encoded?.rdate,
                exdate = encoded?.exdate
            )
        )
    }

    private fun buildSeriesUpdateDraft(
        args: CalendarArgs,
        current: CalendarEventRecord,
        calendar: CalendarInfo
    ): CalendarDraftResult {
        if (args.recurrence != null && args.clearRecurrence == true) {
            return CalendarDraftResult.Invalid("recurrence_update_conflict", "recurrence and clear_recurrence cannot be used together.")
        }
        val title = args.title?.trim() ?: current.title
        if (title.isBlank()) return CalendarDraftResult.Invalid("invalid_arguments", "title cannot be blank.")
        val start = args.startMs ?: current.startMs
        val end = args.endMs ?: current.resolvedEndMs
            ?: return CalendarDraftResult.Invalid("invalid_event_duration", "The provider event has no usable end or duration.")
        val allDay = args.allDay ?: current.allDay
        val timezoneHint = args.timezone ?: current.timezone
        val endTimezoneHint = args.endTimezone ?: current.endTimezone
        validateEventTimes(start, end, allDay, timezoneHint, endTimezoneHint)?.let { return it }
        val timezone = if (allDay) "UTC" else timezoneHint.ifBlank { TimeZone.getDefault().id }
        val endTimezone = if (allDay) "UTC" else endTimezoneHint?.ifBlank { null } ?: timezone

        val recurrenceValues = when {
            args.clearRecurrence == true -> null
            args.recurrence != null -> {
                when (val result = encodeRecurrence(args.recurrence, start, end, allDay)) {
                    is CalendarRecurrenceResult.Invalid -> return CalendarDraftResult.Invalid(result.code, result.message)
                    is CalendarRecurrenceResult.Valid -> result.value
                }
            }
            current.recurring -> {
                val duration = CalendarRecurrenceCodec.durationFor(start, end, allDay)
                    ?: return CalendarDraftResult.Invalid("invalid_recurrence_duration", "The updated recurring event duration is invalid.")
                CalendarEncodedRecurrence(current.rrule, current.rdate, current.exdate, duration)
            }
            else -> null
        }
        val availability = availabilityValue(args.availability, current.availability)
            ?: return CalendarDraftResult.Invalid("invalid_availability", "Unsupported availability value.")
        if (calendar.allowedAvailability.isNotEmpty() && availability !in calendar.allowedAvailability) {
            return CalendarDraftResult.Invalid("availability_not_supported", "The event calendar does not support this availability value.")
        }
        val access = accessLevelValue(args.accessLevel, current.accessLevel)
            ?: return CalendarDraftResult.Invalid("invalid_access_level", "Unsupported access_level value.")
        return CalendarDraftResult.Valid(
            CalendarEventDraft(
                calendarId = current.calendarId,
                title = title,
                startMs = start,
                endMs = if (recurrenceValues == null) end else null,
                duration = recurrenceValues?.duration,
                description = args.description ?: current.description,
                location = args.location ?: current.location,
                allDay = allDay,
                timezone = timezone,
                endTimezone = endTimezone,
                availability = availability,
                accessLevel = access,
                guestsCanModify = args.guestsCanModify ?: current.guestsCanModify,
                guestsCanInviteOthers = args.guestsCanInviteOthers ?: current.guestsCanInviteOthers,
                guestsCanSeeGuests = args.guestsCanSeeGuests ?: current.guestsCanSeeGuests,
                rrule = recurrenceValues?.rrule,
                rdate = recurrenceValues?.rdate,
                exdate = recurrenceValues?.exdate,
                status = current.status
            )
        )
    }

    private fun buildOccurrenceDraft(
        args: CalendarArgs,
        current: CalendarEventRecord,
        instance: CalendarInstanceRecord,
        calendar: CalendarInfo,
        cancelled: Boolean = false
    ): CalendarDraftResult {
        val title = args.title?.trim() ?: current.title
        if (title.isBlank()) return CalendarDraftResult.Invalid("invalid_arguments", "title cannot be blank.")
        val start = args.startMs ?: instance.beginMs
        val end = args.endMs ?: if (args.startMs != null && args.endMs == null) {
            start + (instance.endMs - instance.beginMs)
        } else {
            instance.endMs
        }
        val allDay = args.allDay ?: instance.allDay
        val timezoneHint = args.timezone ?: current.timezone.ifBlank { calendar.timezone }
        val endTimezoneHint = args.endTimezone ?: current.endTimezone
        validateEventTimes(start, end, allDay, timezoneHint, endTimezoneHint)?.let { return it }
        val timezone = if (allDay) "UTC" else timezoneHint.ifBlank { TimeZone.getDefault().id }
        val endTimezone = if (allDay) "UTC" else endTimezoneHint?.ifBlank { null } ?: timezone
        val availability = availabilityValue(args.availability, current.availability)
            ?: return CalendarDraftResult.Invalid("invalid_availability", "Unsupported availability value.")
        if (calendar.allowedAvailability.isNotEmpty() && availability !in calendar.allowedAvailability) {
            return CalendarDraftResult.Invalid("availability_not_supported", "The event calendar does not support this availability value.")
        }
        val access = accessLevelValue(args.accessLevel, current.accessLevel)
            ?: return CalendarDraftResult.Invalid("invalid_access_level", "Unsupported access_level value.")
        return CalendarDraftResult.Valid(
            CalendarEventDraft(
                calendarId = current.calendarId,
                title = title,
                startMs = start,
                endMs = end,
                duration = null,
                description = args.description ?: current.description,
                location = args.location ?: current.location,
                allDay = allDay,
                timezone = timezone,
                endTimezone = endTimezone,
                availability = availability,
                accessLevel = access,
                guestsCanModify = args.guestsCanModify ?: current.guestsCanModify,
                guestsCanInviteOthers = args.guestsCanInviteOthers ?: current.guestsCanInviteOthers,
                guestsCanSeeGuests = args.guestsCanSeeGuests ?: current.guestsCanSeeGuests,
                rrule = null,
                rdate = null,
                exdate = null,
                status = if (cancelled) CalendarDomainValues.cancelledEventStatus else CalendarDomainValues.confirmedEventStatus
            )
        )
    }

    private fun encodeRecurrence(
        spec: CalendarRecurrenceSpec?,
        startMs: Long,
        endMs: Long,
        allDay: Boolean
    ): CalendarRecurrenceResult {
        if (spec == null) return CalendarRecurrenceResult.Valid(null)
        return when (val encoded = CalendarRecurrenceCodec.encode(spec, startMs, endMs, allDay)) {
            is CalendarRecurrenceEncodeResult.Success -> CalendarRecurrenceResult.Valid(
                CalendarEncodedRecurrence(encoded.rrule, encoded.rdate, encoded.exdate, encoded.duration)
            )
            is CalendarRecurrenceEncodeResult.Invalid -> CalendarRecurrenceResult.Invalid(encoded.code, encoded.message)
        }
    }

    private fun validateEventTimes(
        startMs: Long,
        endMs: Long,
        allDay: Boolean,
        timezone: String?,
        endTimezone: String?
    ): CalendarDraftResult.Invalid? {
        if (endMs <= startMs) return CalendarDraftResult.Invalid("invalid_arguments", "end_ms must be greater than start_ms.")
        if (allDay) {
            if (startMs % CalendarRecurrenceCodec.DAY_MS != 0L || endMs % CalendarRecurrenceCodec.DAY_MS != 0L) {
                return CalendarDraftResult.Invalid("invalid_all_day_time", "All-day start_ms and end_ms must be UTC midnight boundaries.")
            }
            if ((!timezone.isNullOrBlank() && !timezone.equals("UTC", true)) ||
                (!endTimezone.isNullOrBlank() && !endTimezone.equals("UTC", true))
            ) {
                return CalendarDraftResult.Invalid("invalid_all_day_timezone", "All-day events use UTC timezone values.")
            }
        } else {
            listOfNotNull(timezone?.takeIf { it.isNotBlank() }, endTimezone?.takeIf { it.isNotBlank() }).forEach {
                if (!isKnownTimezone(it)) return CalendarDraftResult.Invalid("invalid_timezone", "Unknown timezone '$it'.")
            }
        }
        return null
    }

    private fun validateReminders(inputs: List<CalendarReminderInput>?, calendar: CalendarInfo): CalendarReminderResult {
        val values = inputs.orEmpty()
        if (values.size > calendar.maxReminders) {
            return CalendarReminderResult.Invalid("too_many_reminders", "This calendar allows at most ${calendar.maxReminders} reminders.")
        }
        val drafts = mutableListOf<CalendarReminderDraft>()
        for (input in values) {
            val method = CalendarDomainValues.reminderMethod(input.method.trim().lowercase(Locale.US))
                ?: return CalendarReminderResult.Invalid("invalid_reminder_method", "Unsupported reminder method '${input.method}'.")
            if (input.minutes_before !in -1..40_320) {
                return CalendarReminderResult.Invalid("invalid_reminder_minutes", "minutes_before must be between -1 and 40320.")
            }
            if (input.minutes_before == -1 && method != CalendarDomainValues.defaultReminderMethod) {
                return CalendarReminderResult.Invalid("invalid_reminder_minutes", "minutes_before=-1 requires method=default.")
            }
            if (calendar.allowedReminderMethods.isNotEmpty() && method !in calendar.allowedReminderMethods) {
                return CalendarReminderResult.Invalid("reminder_method_not_supported", "The selected calendar does not support reminder method '${input.method}'.")
            }
            drafts += CalendarReminderDraft(input.minutes_before, method)
        }
        return CalendarReminderResult.Valid(drafts)
    }

    private fun validateAttendees(inputs: List<CalendarAttendeeInput>?, calendar: CalendarInfo): CalendarAttendeeResult {
        val values = inputs.orEmpty()
        val emails = mutableSetOf<String>()
        val drafts = mutableListOf<CalendarAttendeeDraft>()
        for (input in values) {
            val email = input.email.trim()
            if (!email.contains('@') || email.startsWith('@') || email.endsWith('@')) {
                return CalendarAttendeeResult.Invalid("invalid_attendee_email", "Invalid attendee email '$email'.")
            }
            if (!emails.add(email.lowercase(Locale.US))) {
                return CalendarAttendeeResult.Invalid("duplicate_attendee", "Attendee emails must be unique.")
            }
            if (calendar.accountIdentity.isNotBlank() && email.equals(calendar.accountIdentity, ignoreCase = true)) {
                return CalendarAttendeeResult.Invalid("self_attendee_not_allowed", "The current calendar account cannot be added as a managed guest attendee.")
            }
            val type = CalendarDomainValues.attendeeType(input.type.trim().lowercase(Locale.US))
                ?: return CalendarAttendeeResult.Invalid("invalid_attendee_type", "Unsupported attendee type '${input.type}'.")
            if (calendar.allowedAttendeeTypes.isNotEmpty() && type !in calendar.allowedAttendeeTypes) {
                return CalendarAttendeeResult.Invalid("attendee_type_not_supported", "The selected calendar does not support attendee type '${input.type}'.")
            }
            drafts += CalendarAttendeeDraft(input.name?.trim().orEmpty(), email, type)
        }
        return CalendarAttendeeResult.Valid(drafts)
    }

    private fun resolveOccurrence(seriesEventId: Long, instanceStartMs: Long?): CalendarInstanceRecord? {
        val start = instanceStartMs ?: return null
        return gateway.findOccurrence(seriesEventId, start)
    }

    private fun availabilityValue(value: String?, fallback: Int): Int? =
        value?.trim()?.lowercase(Locale.US)?.let(CalendarDomainValues::availability) ?: fallback.takeIf { value == null }

    private fun accessLevelValue(value: String?, fallback: Int): Int? =
        value?.trim()?.lowercase(Locale.US)?.let(CalendarDomainValues::accessLevel) ?: fallback.takeIf { value == null }

    private fun isKnownTimezone(value: String): Boolean = TimeZone.getAvailableIDs().contains(value)

    private fun CalendarArgs.hasEventChanges(): Boolean = title != null || startMs != null || endMs != null ||
        allDay != null || description != null || location != null || timezone != null || endTimezone != null ||
        availability != null || accessLevel != null || guestsCanModify != null || guestsCanInviteOthers != null ||
        guestsCanSeeGuests != null || recurrence != null || clearRecurrence == true || reminders != null || attendees != null

    private val CalendarInstanceRecord.stableInstanceStartMs: Long
        get() = originalInstanceStartMs ?: beginMs

    private fun CalendarInstanceRecord.toJson(): JsonObject = buildJsonObject {
        put("event_id", seriesEventId)
        put("instance_event_id", instanceEventId)
        put("calendar_id", calendarId)
        put("title", title)
        put("start_ms", beginMs)
        put("end_ms", endMs)
        put("instance_start_ms", stableInstanceStartMs)
        put("location", location)
        put("all_day", allDay)
        put("recurring", recurring)
        originalInstanceStartMs?.let { put("original_instance_start_ms", it) }
    }

    private fun CalendarInfo.toJson(): JsonObject = buildJsonObject {
        put("calendar_id", id)
        put("name", name)
        put("account_name", accountName)
        put("owner", owner)
        put("timezone", timezone)
        put("visible", visible)
        put("writable", writable)
        put("primary", primary)
        put("access_level", CalendarDomainValues.calendarAccessName(accessLevel))
        put("max_reminders", maxReminders)
        putJsonArray("allowed_reminder_methods") { allowedReminderMethods.sorted().forEach { add(reminderMethodName(it)) } }
        putJsonArray("allowed_attendee_types") { allowedAttendeeTypes.sorted().forEach { add(attendeeTypeName(it)) } }
        putJsonArray("allowed_availability") { allowedAvailability.sorted().forEach { add(availabilityName(it)) } }
    }

    private fun JsonObjectBuilder.putAggregate(aggregate: CalendarEventAggregate) {
        val event = aggregate.event
        put("event_id", event.id)
        put("calendar_id", event.calendarId)
        put("title", event.title)
        put("start_ms", event.startMs)
        event.resolvedEndMs?.let { put("end_ms", it) }
        put("all_day", event.allDay)
        put("description", event.description)
        put("location", event.location)
        put("timezone", event.timezone)
        event.endTimezone?.let { put("end_timezone", it) }
        put("availability", availabilityName(event.availability))
        put("access_level", accessLevelName(event.accessLevel))
        put("guests_can_modify", event.guestsCanModify)
        put("guests_can_invite_others", event.guestsCanInviteOthers)
        put("guests_can_see_guests", event.guestsCanSeeGuests)
        put("recurring", event.recurring)
        if (event.recurring) {
            val recurrence = CalendarRecurrenceCodec.decode(event.rrule, event.rdate, event.exdate)
            putJsonObject("recurrence") {
                put("structured_supported", recurrence.supported)
                recurrence.spec?.let { putRecurrenceSpec(it) }
                recurrence.rawRrule?.let { put("raw_rrule", it) }
                recurrence.rawRdate?.let { put("raw_rdate", it) }
                recurrence.rawExdate?.let { put("raw_exdate", it) }
            }
        }
        putJsonArray("reminders") {
            aggregate.reminders.forEach { reminder ->
                add(buildJsonObject {
                    put("minutes_before", reminder.minutesBefore)
                    put("method", reminderMethodName(reminder.method))
                })
            }
        }
        putJsonArray("attendees") {
            aggregate.attendees.forEach { attendee ->
                add(buildJsonObject {
                    put("email", attendee.email)
                    put("name", attendee.name)
                    put("type", attendeeTypeName(attendee.type))
                    put("relationship", attendeeRelationshipName(attendee.relationship))
                    put("status", attendeeStatusName(attendee.status))
                })
            }
        }
        put("organizer", event.organizer)
        put("self_attendee_status", attendeeStatusName(event.selfAttendeeStatus))
        put("is_organizer", event.isOrganizer)
        put("can_invite_others", event.canInviteOthers)
    }

    private fun JsonObjectBuilder.putRecurrenceSpec(spec: CalendarRecurrenceSpec) {
        put("frequency", spec.frequency)
        spec.interval?.let { put("interval", it) }
        spec.count?.let { put("count", it) }
        spec.until_ms?.let { put("until_ms", it) }
        putJsonArray("by_weekdays") { spec.by_weekdays.forEach { add(it) } }
        putJsonArray("by_month_days") { spec.by_month_days.forEach { add(it) } }
        putJsonArray("by_months") { spec.by_months.forEach { add(it) } }
        putJsonArray("by_set_positions") { spec.by_set_positions.forEach { add(it) } }
        spec.week_start?.let { put("week_start", it) }
        putJsonArray("additional_dates_ms") { spec.additional_dates_ms.forEach { add(it) } }
        putJsonArray("excluded_dates_ms") { spec.excluded_dates_ms.forEach { add(it) } }
    }

    private fun eventSummary(event: CalendarEventRecord): String {
        val end = event.resolvedEndMs?.let(::nowText) ?: "unknown end"
        return "id=${event.id} | cal=${event.calendarId} | ${event.title} | ${nowText(event.startMs)} -> $end | all_day=${event.allDay} | recurring=${event.recurring}${if (event.location.isBlank()) "" else " | ${event.location}"}"
    }

    private fun CalendarEventAggregate.matchesDraft(
        draft: CalendarEventDraft,
        expectedReminders: List<CalendarReminderDraft>?,
        expectedAttendees: List<CalendarAttendeeDraft>?,
        accountIdentity: String
    ): Boolean {
        val eventMatches = event.calendarId == draft.calendarId &&
            event.title == draft.title &&
            event.startMs == draft.startMs &&
            event.endMs == draft.endMs &&
            event.duration == draft.duration &&
            event.description == draft.description &&
            event.location == draft.location &&
            event.allDay == draft.allDay &&
            event.timezone == draft.timezone &&
            event.endTimezone == draft.endTimezone &&
            event.availability == draft.availability &&
            event.accessLevel == draft.accessLevel &&
            event.guestsCanModify == draft.guestsCanModify &&
            event.guestsCanInviteOthers == draft.guestsCanInviteOthers &&
            event.guestsCanSeeGuests == draft.guestsCanSeeGuests &&
            event.rrule == draft.rrule && event.rdate == draft.rdate && event.exdate == draft.exdate &&
            (draft.status == null || event.status == draft.status)
        if (!eventMatches) return false
        if (expectedReminders != null) {
            val expected = expectedReminders.map { it.minutesBefore to it.method }.sortedWith(compareBy({ it.first }, { it.second }))
            val actual = reminders.map { it.minutesBefore to it.method }.sortedWith(compareBy({ it.first }, { it.second }))
            if (actual != expected) return false
        }
        if (expectedAttendees != null) {
            val expected = expectedAttendees.map {
                Triple(it.email.lowercase(Locale.US), it.name, it.type)
            }.filterNot { it.first.equals(accountIdentity, ignoreCase = true) }.sortedBy { it.first }
            val actual = attendees
                .filter {
                    CalendarDomainValues.isManagedAttendeeRelationship(it.relationship) &&
                        !it.email.equals(accountIdentity, ignoreCase = true)
                }
                .map { Triple(it.email.lowercase(Locale.US), it.name, it.type) }
                .sortedBy { it.first }
            if (actual != expected) return false
        }
        return true
    }

    private fun CalendarEventRecord.isExceptionFor(seriesEventId: Long, instanceStartMs: Long): Boolean =
        originalId == seriesEventId && originalInstanceStartMs == instanceStartMs

    private fun invalidCalendarArguments(action: String, message: String, nextStep: String): ToolResult =
        personalError(name, action, "invalid_arguments", message, nextStep)

    private fun calendarNotFound(action: String, eventId: Long): ToolResult =
        personalError(name, action, "not_found", "Event id=$eventId was not found.", "Use list_events to find a valid event_id.")

    private fun occurrenceNotFound(action: String, eventId: Long, startMs: Long?): ToolResult =
        personalError(
            name,
            action,
            if (startMs == null) "instance_start_required" else "occurrence_not_found",
            if (startMs == null) "instance_start_ms is required for scope=occurrence." else "No occurrence starts at instance_start_ms=$startMs for event id=$eventId.",
            "Use list_events to obtain the exact occurrence start_ms."
        )

    private fun exceptionIdNotSupported(action: String, event: CalendarEventRecord): ToolResult =
        personalError(
            name,
            action,
            "exception_event_id_not_supported",
            "event_id=${event.id} is a recurrence exception row, not the series id.",
            "Use event_id=${event.originalId} with scope=occurrence and instance_start_ms=${event.originalInstanceStartMs}."
        )

    private fun mutationFailed(action: String, message: String): ToolResult =
        personalError(name, action, "mutation_failed", message, "Refresh the event and retry.")

    private fun verificationFailed(action: String): ToolResult =
        personalError(name, action, "verification_failed", "The provider mutation succeeded, but the event could not be reloaded.", "Use list_events before retrying.")

    private fun CalendarDraftResult.Invalid.toToolResult(action: String): ToolResult =
        personalError(name, action, code, message, "Correct the calendar fields and retry.")

    private fun CalendarReminderResult.Invalid.toToolResult(action: String): ToolResult =
        personalError(name, action, code, message, "Correct the reminder values and retry.")

    private fun CalendarAttendeeResult.Invalid.toToolResult(action: String): ToolResult =
        personalError(name, action, code, message, "Correct the list values and retry.")

    private fun reminderMethodName(value: Int): String = CalendarDomainValues.reminderMethodName(value)

    private fun attendeeTypeName(value: Int): String = CalendarDomainValues.attendeeTypeName(value)

    private fun availabilityName(value: Int): String = CalendarDomainValues.availabilityName(value)

    private fun accessLevelName(value: Int): String = CalendarDomainValues.accessLevelName(value)

    private fun attendeeRelationshipName(value: Int): String = CalendarDomainValues.attendeeRelationshipName(value)

    private fun attendeeStatusName(value: Int): String = CalendarDomainValues.attendeeStatusName(value)

    private sealed interface CalendarDraftResult {
        data class Valid(val draft: CalendarEventDraft) : CalendarDraftResult
        data class Invalid(val code: String, val message: String) : CalendarDraftResult
    }

    private sealed interface CalendarReminderResult {
        data class Valid(val values: List<CalendarReminderDraft>) : CalendarReminderResult
        data class Invalid(val code: String, val message: String) : CalendarReminderResult
    }

    private sealed interface CalendarAttendeeResult {
        data class Valid(val values: List<CalendarAttendeeDraft>) : CalendarAttendeeResult
        data class Invalid(val code: String, val message: String) : CalendarAttendeeResult
    }

    private data class CalendarEncodedRecurrence(
        val rrule: String?,
        val rdate: String?,
        val exdate: String?,
        val duration: String
    )

    private sealed interface CalendarRecurrenceResult {
        data class Valid(val value: CalendarEncodedRecurrence?) : CalendarRecurrenceResult
        data class Invalid(val code: String, val message: String) : CalendarRecurrenceResult
    }

    private fun actionOpenAppSettings(action: String): ToolResult {
        return openPersonalAppSettings(context).let { launch ->
            if (launch.isError) {
                personalError(
                    toolName = name,
                    action = action,
                    code = "open_settings_failed",
                    message = launch.content,
                    nextStep = "Open app settings manually from Android settings."
                )
            } else {
                personalOk(toolName = name, action = action, message = "app settings opened")
            }
        }
    }

    @Serializable
    private data class CalendarArgs(
        val action: String,
        val event_id: Long? = null,
        val calendar_id: Long? = null,
        val scope: String? = null,
        val instance_start_ms: Long? = null,
        val title: String? = null,
        val start_ms: Long? = null,
        val end_ms: Long? = null,
        val all_day: Boolean? = null,
        val description: String? = null,
        val location: String? = null,
        val timezone: String? = null,
        val end_timezone: String? = null,
        val availability: String? = null,
        val access_level: String? = null,
        val guests_can_modify: Boolean? = null,
        val guests_can_invite_others: Boolean? = null,
        val guests_can_see_guests: Boolean? = null,
        val recurrence: CalendarRecurrenceSpec? = null,
        val clear_recurrence: Boolean? = null,
        val reminders: List<CalendarReminderInput>? = null,
        val attendees: List<CalendarAttendeeInput>? = null,
        val response: String? = null,
        val from_ms: Long? = null,
        val to_ms: Long? = null,
        val count: Int? = null,
        val request_if_missing: Boolean? = null,
        val open_settings_if_failed: Boolean? = null,
        val wait_user_confirmation: Boolean? = null
    ) {
        val eventId: Long? get() = event_id
        val calendarId: Long? get() = calendar_id
        val scopeValue: String get() = scope?.trim()?.lowercase(Locale.US) ?: "series"
        val instanceStartMs: Long? get() = instance_start_ms
        val startMs: Long? get() = start_ms
        val endMs: Long? get() = end_ms
        val allDay: Boolean? get() = all_day
        val endTimezone: String? get() = end_timezone
        val accessLevel: String? get() = access_level
        val guestsCanModify: Boolean? get() = guests_can_modify
        val guestsCanInviteOthers: Boolean? get() = guests_can_invite_others
        val guestsCanSeeGuests: Boolean? get() = guests_can_see_guests
        val clearRecurrence: Boolean? get() = clear_recurrence
        val fromMs: Long? get() = from_ms
        val toMs: Long? get() = to_ms
        val requestIfMissing: Boolean? get() = request_if_missing
        val openSettingsIfFailed: Boolean? get() = open_settings_if_failed
        val waitUserConfirmation: Boolean? get() = wait_user_confirmation
    }

}

internal suspend fun ensurePersonalPermissionsInteractive(
    context: Context,
    toolName: String,
    action: String,
    required: List<String>,
    requestIfMissing: Boolean,
    openSettingsIfFailed: Boolean,
    waitUserConfirmation: Boolean
): ToolResult? {
    val needed = required.distinct().filter { it.isNotBlank() }
    if (needed.isEmpty()) return null

    var missing = missingPermissions(context, needed)
    if (missing.isEmpty()) return null

    if (!requestIfMissing) {
        return personalError(
            toolName = toolName,
            action = action,
            code = "permissions_missing",
            message = "Missing required permissions: ${missing.joinToString(", ")}.",
            nextStep = "Set request_if_missing=true or grant permissions in app settings, then retry."
        )
    }

    when (AndroidUserActionBridge.requestPermissions(missing)) {
        true -> {
            missing = missingPermissions(context, needed)
            if (missing.isEmpty()) return null
        }

        false -> {
            if (!openSettingsIfFailed) {
                return personalError(
                    toolName = toolName,
                    action = action,
                    code = "permissions_denied",
                    message = "User denied required permissions: ${missing.joinToString(", ")}.",
                    nextStep = "Grant permissions and retry."
                )
            }
        }

        null -> {
            if (!openSettingsIfFailed) {
                return personalError(
                    toolName = toolName,
                    action = action,
                    code = "ui_unavailable",
                    message = "Permission prompt unavailable. Missing: ${missing.joinToString(", ")}.",
                    nextStep = "Grant permissions from app settings and retry."
                )
            }
        }
    }

    if (!openSettingsIfFailed) {
        return personalError(
            toolName = toolName,
            action = action,
            code = "permissions_missing",
            message = "Missing required permissions: ${missing.joinToString(", ")}.",
            nextStep = "Grant permissions and retry."
        )
    }

    val openResult = openPersonalAppSettings(context)
    if (openResult.isError) {
        return personalError(
            toolName = toolName,
            action = action,
            code = "open_settings_failed",
            message = openResult.content,
            nextStep = "Open app settings manually, grant permissions, then retry."
        )
    }

    if (waitUserConfirmation) {
        when (AndroidUserActionBridge.requestUserConfirmation(
            title = "Permission Required",
            message = "Grant required permission(s) in app settings, then return and tap Continue.",
            confirmLabel = "Continue",
            cancelLabel = "Cancel"
        )) {
            true -> Unit
            false -> {
                return personalError(
                    toolName = toolName,
                    action = action,
                    code = "user_cancelled",
                    message = "User cancelled permission flow.",
                    nextStep = "Run again after granting permissions."
                )
            }

            null -> {
                return personalError(
                    toolName = toolName,
                    action = action,
                    code = "ui_unavailable",
                    message = "Confirmation UI unavailable.",
                    nextStep = "Grant permissions manually, then retry."
                )
            }
        }
    }

    missing = missingPermissions(context, needed)
    if (missing.isNotEmpty()) {
        return personalError(
            toolName = toolName,
            action = action,
            code = "permissions_missing",
            message = "Permissions still missing: ${missing.joinToString(", ")}.",
            nextStep = "Grant permissions in app settings, then retry."
        )
    }
    return null
}

internal fun openPersonalAppSettings(context: Context): ToolResult {
    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
        data = Uri.parse("package:${context.packageName}")
    }
    return launchIntent(context, intent)
}

internal fun personalOk(
    toolName: String,
    action: String,
    message: String,
    extra: (kotlinx.serialization.json.JsonObjectBuilder.() -> Unit)? = null
): ToolResult {
    return ToolResult(
        toolCallId = "",
        content = message,
        isError = false,
        metadata = buildJsonObject {
            put("tool", toolName)
            put("action", action)
            put("status", "ok")
            extra?.invoke(this)
        }
    )
}

internal fun personalError(
    toolName: String,
    action: String,
    code: String,
    message: String,
    nextStep: String? = null
): ToolResult {
    val text = buildString {
        append("$toolName/$action failed: $message")
        if (!nextStep.isNullOrBlank()) {
            append(" Next: ")
            append(nextStep)
        }
    }
    return ToolResult(
        toolCallId = "",
        content = text,
        isError = true,
        metadata = buildJsonObject {
            put("tool", toolName)
            put("action", action)
            put("status", "error")
            put("error", code)
            put("recoverable", !nextStep.isNullOrBlank())
            if (!nextStep.isNullOrBlank()) {
                put("next_step", nextStep)
            }
        }
    )
}
