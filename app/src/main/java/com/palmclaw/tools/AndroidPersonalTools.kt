package com.palmclaw.tools

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.CalendarContract
import android.provider.ContactsContract
import android.provider.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.util.Locale
import java.util.TimeZone

fun createAndroidPersonalToolSet(context: Context): List<Tool> {
    return listOf(
        CalendarControlTool(context),
        ContactsControlTool(context)
    )
}

private class CalendarControlTool(
    private val context: Context
) : Tool, TimedTool {
    override val name: String = "calendar"
    override val description: String =
        "Unified calendar tool. action=create_event|list_events|get_event|update_event|delete_event|list_calendars|open_app_settings"
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
                  "action":{"type":"string","enum":["create_event","list_events","get_event","update_event","delete_event","list_calendars","open_app_settings"]},
                  "event_id":{"type":"integer"},
                  "calendar_id":{"type":"integer"},
                  "title":{"type":"string"},
                  "start_ms":{"type":"integer"},
                  "end_ms":{"type":"integer"},
                  "all_day":{"type":"boolean"},
                  "description":{"type":"string"},
                  "location":{"type":"string"},
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
            "list_calendars" -> actionListCalendars(args)
            "open_app_settings" -> actionOpenAppSettings(action)
            else -> personalError(
                toolName = name,
                action = action,
                code = "unsupported_action",
                message = "Unsupported action '${args.action}'.",
                nextStep = "Use action=create_event|list_events|get_event|update_event|delete_event|list_calendars|open_app_settings."
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

        val permissionsError = ensurePersonalPermissionsInteractive(
            context = context,
            toolName = name,
            action = action,
            required = listOf(Manifest.permission.WRITE_CALENDAR),
            requestIfMissing = args.requestIfMissing ?: true,
            openSettingsIfFailed = args.openSettingsIfFailed ?: true,
            waitUserConfirmation = args.waitUserConfirmation ?: true
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

        val calendarId = findWritableCalendarId(args.calendarId)
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

        val values = ContentValues().apply {
            put(CalendarContract.Events.CALENDAR_ID, calendarId)
            put(CalendarContract.Events.TITLE, title)
            put(CalendarContract.Events.DTSTART, start)
            put(CalendarContract.Events.DTEND, end)
            put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
            put(CalendarContract.Events.ALL_DAY, if (args.allDay == true) 1 else 0)
            if (!args.description.isNullOrBlank()) {
                put(CalendarContract.Events.DESCRIPTION, args.description)
            }
            if (!args.location.isNullOrBlank()) {
                put(CalendarContract.Events.EVENT_LOCATION, args.location)
            }
        }

        val uri = context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)
            ?: return personalError(
                toolName = name,
                action = action,
                code = "insert_failed",
                message = "Calendar insert returned null.",
                nextStep = "Check calendar permissions/account availability and retry."
            )

        val eventId = ContentUris.parseId(uri)
        return personalOk(
            toolName = name,
            action = action,
            message = "calendar event created: id=$eventId start=${nowText(start)} end=${nowText(end)}"
        ) {
            put("event_id", eventId)
            put("calendar_id", calendarId)
            put("start_ms", start)
            put("end_ms", end)
            put("all_day", args.allDay == true)
        }
    }

    private suspend fun actionListEvents(args: CalendarArgs): ToolResult {
        val action = "list_events"
        val permissionsError = ensurePersonalPermissionsInteractive(
            context = context,
            toolName = name,
            action = action,
            required = listOf(Manifest.permission.READ_CALENDAR),
            requestIfMissing = args.requestIfMissing ?: true,
            openSettingsIfFailed = args.openSettingsIfFailed ?: true,
            waitUserConfirmation = args.waitUserConfirmation ?: true
        )
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

        val builder = CalendarContract.Instances.CONTENT_URI.buildUpon()
        ContentUris.appendId(builder, from)
        ContentUris.appendId(builder, to)
        val uri = builder.build()
        val projection = arrayOf(
            CalendarContract.Instances.EVENT_ID,
            CalendarContract.Instances.CALENDAR_ID,
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END,
            CalendarContract.Instances.EVENT_LOCATION,
            CalendarContract.Instances.ALL_DAY
        )

        val rows = mutableListOf<CalendarEventRow>()
        context.contentResolver.query(uri, projection, null, null, "${CalendarContract.Instances.BEGIN} ASC")?.use { c ->
            val idCol = c.getColumnIndexOrThrow(CalendarContract.Instances.EVENT_ID)
            val calCol = c.getColumnIndexOrThrow(CalendarContract.Instances.CALENDAR_ID)
            val titleCol = c.getColumnIndexOrThrow(CalendarContract.Instances.TITLE)
            val beginCol = c.getColumnIndexOrThrow(CalendarContract.Instances.BEGIN)
            val endCol = c.getColumnIndexOrThrow(CalendarContract.Instances.END)
            val locCol = c.getColumnIndexOrThrow(CalendarContract.Instances.EVENT_LOCATION)
            val allDayCol = c.getColumnIndexOrThrow(CalendarContract.Instances.ALL_DAY)
            while (c.moveToNext() && rows.size < count) {
                rows += CalendarEventRow(
                    id = c.getLong(idCol),
                    calendarId = c.getLong(calCol),
                    title = c.getString(titleCol).orEmpty(),
                    beginMs = c.getLong(beginCol),
                    endMs = c.getLong(endCol),
                    location = c.getString(locCol).orEmpty(),
                    allDay = c.getInt(allDayCol) == 1
                )
            }
        }

        val text = if (rows.isEmpty()) {
            "No events found."
        } else {
            rows.joinToString("\n") { row ->
                "id=${row.id} | cal=${row.calendarId} | ${row.title} | ${nowText(row.beginMs)} -> ${nowText(row.endMs)} | all_day=${row.allDay}${if (row.location.isBlank()) "" else " | ${row.location}"}"
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
                rows.forEach { row -> add(row.summary()) }
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

        val permissionsError = ensurePersonalPermissionsInteractive(
            context = context,
            toolName = name,
            action = action,
            required = listOf(Manifest.permission.READ_CALENDAR),
            requestIfMissing = args.requestIfMissing ?: true,
            openSettingsIfFailed = args.openSettingsIfFailed ?: true,
            waitUserConfirmation = args.waitUserConfirmation ?: true
        )
        if (permissionsError != null) return permissionsError

        val event = queryEventById(eventId)
            ?: return personalError(
                toolName = name,
                action = action,
                code = "not_found",
                message = "Event id=$eventId not found.",
                nextStep = "Use list_events to find a valid event_id."
            )

        return personalOk(
            toolName = name,
            action = action,
            message = "id=${event.id} | cal=${event.calendarId} | ${event.title} | ${nowText(event.startMs)} -> ${nowText(event.endMs)} | all_day=${event.allDay}${if (event.location.isBlank()) "" else " | ${event.location}"}"
        ) {
            put("event_id", event.id)
            put("calendar_id", event.calendarId)
            put("title", event.title)
            put("start_ms", event.startMs)
            put("end_ms", event.endMs)
            put("all_day", event.allDay)
            put("description", event.description)
            put("location", event.location)
        }
    }

    private suspend fun actionUpdateEvent(args: CalendarArgs): ToolResult {
        val action = "update_event"
        val eventId = args.eventId
            ?: return personalError(
                toolName = name,
                action = action,
                code = "invalid_arguments",
                message = "event_id is required.",
                nextStep = "Pass event_id."
            )

        val permissionsError = ensurePersonalPermissionsInteractive(
            context = context,
            toolName = name,
            action = action,
            required = listOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR),
            requestIfMissing = args.requestIfMissing ?: true,
            openSettingsIfFailed = args.openSettingsIfFailed ?: true,
            waitUserConfirmation = args.waitUserConfirmation ?: true
        )
        if (permissionsError != null) return permissionsError

        val current = queryEventById(eventId)
            ?: return personalError(
                toolName = name,
                action = action,
                code = "not_found",
                message = "Event id=$eventId not found.",
                nextStep = "Use list_events to find a valid event_id."
            )

        val hasChanges = args.title != null ||
            args.startMs != null ||
            args.endMs != null ||
            args.description != null ||
            args.location != null ||
            args.allDay != null ||
            args.calendarId != null
        if (!hasChanges) {
            return personalError(
                toolName = name,
                action = action,
                code = "invalid_arguments",
                message = "No update fields provided.",
                nextStep = "Provide at least one of title/start_ms/end_ms/description/location/all_day/calendar_id."
            )
        }

        val updatedStart = args.startMs ?: current.startMs
        val updatedEnd = args.endMs ?: current.endMs
        if (updatedEnd <= updatedStart) {
            return personalError(
                toolName = name,
                action = action,
                code = "invalid_arguments",
                message = "end_ms must be > start_ms after update.",
                nextStep = "Adjust start_ms/end_ms and retry."
            )
        }

        if (args.calendarId != null && findWritableCalendarId(args.calendarId) == null) {
            return personalError(
                toolName = name,
                action = action,
                code = "calendar_not_writable",
                message = "Calendar id=${args.calendarId} is not writable or not found.",
                nextStep = "Use list_calendars to choose a writable calendar_id."
            )
        }
        val values = ContentValues().apply {
            if (args.calendarId != null) {
                put(CalendarContract.Events.CALENDAR_ID, args.calendarId)
            }
            if (args.title != null) {
                val updatedTitle = args.title.trim()
                if (updatedTitle.isBlank()) {
                    return personalError(
                        toolName = name,
                        action = action,
                        code = "invalid_arguments",
                        message = "title cannot be blank.",
                        nextStep = "Pass non-empty title or omit title."
                    )
                }
                put(CalendarContract.Events.TITLE, updatedTitle)
            }
            if (args.startMs != null) {
                put(CalendarContract.Events.DTSTART, args.startMs)
            }
            if (args.endMs != null) {
                put(CalendarContract.Events.DTEND, args.endMs)
            }
            args.allDay?.let { allDay ->
                put(CalendarContract.Events.ALL_DAY, if (allDay) 1 else 0)
            }
            if (args.description != null) {
                put(CalendarContract.Events.DESCRIPTION, args.description)
            }
            if (args.location != null) {
                put(CalendarContract.Events.EVENT_LOCATION, args.location)
            }
        }

        if (values.size() == 0) {
            return personalError(
                toolName = name,
                action = action,
                code = "invalid_arguments",
                message = "No valid update fields provided.",
                nextStep = "Provide non-empty update fields."
            )
        }

        val updated = context.contentResolver.update(
            ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId),
            values,
            null,
            null
        )
        if (updated <= 0) {
            return personalError(
                toolName = name,
                action = action,
                code = "update_failed",
                message = "Calendar update affected 0 rows.",
                nextStep = "Verify event_id and retry."
            )
        }

        val reloaded = queryEventById(eventId)
        return personalOk(
            toolName = name,
            action = action,
            message = "calendar event updated: id=$eventId"
        ) {
            put("event_id", eventId)
            put("updated_rows", updated)
            if (reloaded != null) {
                put("calendar_id", reloaded.calendarId)
                put("title", reloaded.title)
                put("start_ms", reloaded.startMs)
                put("end_ms", reloaded.endMs)
                put("all_day", reloaded.allDay)
                put("description", reloaded.description)
                put("location", reloaded.location)
            }
        }
    }

    private suspend fun actionDeleteEvent(args: CalendarArgs): ToolResult {
        val action = "delete_event"
        val eventId = args.eventId
            ?: return personalError(
                toolName = name,
                action = action,
                code = "invalid_arguments",
                message = "event_id is required.",
                nextStep = "Pass event_id."
            )

        val permissionsError = ensurePersonalPermissionsInteractive(
            context = context,
            toolName = name,
            action = action,
            required = listOf(Manifest.permission.WRITE_CALENDAR),
            requestIfMissing = args.requestIfMissing ?: true,
            openSettingsIfFailed = args.openSettingsIfFailed ?: true,
            waitUserConfirmation = args.waitUserConfirmation ?: true
        )
        if (permissionsError != null) return permissionsError

        val deleted = context.contentResolver.delete(
            ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId),
            null,
            null
        )
        if (deleted <= 0) {
            return personalError(
                toolName = name,
                action = action,
                code = "not_found",
                message = "Event id=$eventId not found or already deleted.",
                nextStep = "Use list_events to check valid event_id."
            )
        }

        return personalOk(
            toolName = name,
            action = action,
            message = "calendar event deleted: id=$eventId"
        ) {
            put("event_id", eventId)
            put("deleted_rows", deleted)
        }
    }

    private suspend fun actionListCalendars(args: CalendarArgs): ToolResult {
        val action = "list_calendars"
        val permissionsError = ensurePersonalPermissionsInteractive(
            context = context,
            toolName = name,
            action = action,
            required = listOf(Manifest.permission.READ_CALENDAR),
            requestIfMissing = args.requestIfMissing ?: true,
            openSettingsIfFailed = args.openSettingsIfFailed ?: true,
            waitUserConfirmation = args.waitUserConfirmation ?: true
        )
        if (permissionsError != null) return permissionsError

        val projection = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
            CalendarContract.Calendars.OWNER_ACCOUNT,
            CalendarContract.Calendars.VISIBLE,
            CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL
        )

        val rows = mutableListOf<CalendarInfoRow>()
        context.contentResolver.query(
            CalendarContract.Calendars.CONTENT_URI,
            projection,
            null,
            null,
            "${CalendarContract.Calendars.CALENDAR_DISPLAY_NAME} ASC"
        )?.use { c ->
            val idCol = c.getColumnIndexOrThrow(CalendarContract.Calendars._ID)
            val nameCol = c.getColumnIndexOrThrow(CalendarContract.Calendars.CALENDAR_DISPLAY_NAME)
            val ownerCol = c.getColumnIndexOrThrow(CalendarContract.Calendars.OWNER_ACCOUNT)
            val visibleCol = c.getColumnIndexOrThrow(CalendarContract.Calendars.VISIBLE)
            val accessCol = c.getColumnIndexOrThrow(CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL)
            while (c.moveToNext()) {
                rows += CalendarInfoRow(
                    id = c.getLong(idCol),
                    name = c.getString(nameCol).orEmpty(),
                    owner = c.getString(ownerCol).orEmpty(),
                    visible = c.getInt(visibleCol) == 1,
                    accessLevel = c.getInt(accessCol)
                )
            }
        }

        val text = if (rows.isEmpty()) {
            "No calendars found."
        } else {
            rows.joinToString("\n") { row ->
                "id=${row.id} | ${row.name} | owner=${row.owner.ifBlank { "(local)" }} | visible=${row.visible} | access=${row.accessLevel}"
            }
        }

        return personalOk(
            toolName = name,
            action = action,
            message = text
        ) {
            put("count", rows.size)
            putJsonArray("calendars") {
                rows.forEach { row -> add(row.summary()) }
            }
        }
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

    private fun findWritableCalendarId(preferredCalendarId: Long? = null): Long? {
        val projection = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL,
            CalendarContract.Calendars.VISIBLE
        )
        var firstWritable: Long? = null
        context.contentResolver.query(CalendarContract.Calendars.CONTENT_URI, projection, null, null, null)?.use { c ->
            val idCol = c.getColumnIndexOrThrow(CalendarContract.Calendars._ID)
            val accessCol = c.getColumnIndexOrThrow(CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL)
            val visibleCol = c.getColumnIndexOrThrow(CalendarContract.Calendars.VISIBLE)
            while (c.moveToNext()) {
                val id = c.getLong(idCol)
                val access = c.getInt(accessCol)
                val visible = c.getInt(visibleCol)
                if (visible == 1 && access >= CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR) {
                    if (preferredCalendarId != null && id == preferredCalendarId) {
                        return id
                    }
                    if (firstWritable == null) {
                        firstWritable = id
                    }
                }
            }
        }
        return if (preferredCalendarId != null) null else firstWritable
    }

    private fun queryEventById(eventId: Long): CalendarEventDetail? {
        val projection = arrayOf(
            CalendarContract.Events._ID,
            CalendarContract.Events.CALENDAR_ID,
            CalendarContract.Events.TITLE,
            CalendarContract.Events.DTSTART,
            CalendarContract.Events.DTEND,
            CalendarContract.Events.DESCRIPTION,
            CalendarContract.Events.EVENT_LOCATION,
            CalendarContract.Events.ALL_DAY
        )
        context.contentResolver.query(
            ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId),
            projection,
            null,
            null,
            null
        )?.use { c ->
            if (!c.moveToFirst()) return null
            val idCol = c.getColumnIndexOrThrow(CalendarContract.Events._ID)
            val calIdCol = c.getColumnIndexOrThrow(CalendarContract.Events.CALENDAR_ID)
            val titleCol = c.getColumnIndexOrThrow(CalendarContract.Events.TITLE)
            val startCol = c.getColumnIndexOrThrow(CalendarContract.Events.DTSTART)
            val endCol = c.getColumnIndexOrThrow(CalendarContract.Events.DTEND)
            val descCol = c.getColumnIndexOrThrow(CalendarContract.Events.DESCRIPTION)
            val locCol = c.getColumnIndexOrThrow(CalendarContract.Events.EVENT_LOCATION)
            val allDayCol = c.getColumnIndexOrThrow(CalendarContract.Events.ALL_DAY)
            return CalendarEventDetail(
                id = c.getLong(idCol),
                calendarId = c.getLong(calIdCol),
                title = c.getString(titleCol).orEmpty(),
                startMs = c.getLong(startCol),
                endMs = c.getLong(endCol),
                description = c.getString(descCol).orEmpty(),
                location = c.getString(locCol).orEmpty(),
                allDay = c.getInt(allDayCol) == 1
            )
        }
        return null
    }

    @Serializable
    private data class CalendarArgs(
        val action: String,
        val event_id: Long? = null,
        val calendar_id: Long? = null,
        val title: String? = null,
        val start_ms: Long? = null,
        val end_ms: Long? = null,
        val all_day: Boolean? = null,
        val description: String? = null,
        val location: String? = null,
        val from_ms: Long? = null,
        val to_ms: Long? = null,
        val count: Int? = null,
        val request_if_missing: Boolean? = null,
        val open_settings_if_failed: Boolean? = null,
        val wait_user_confirmation: Boolean? = null
    ) {
        val eventId: Long? get() = event_id
        val calendarId: Long? get() = calendar_id
        val startMs: Long? get() = start_ms
        val endMs: Long? get() = end_ms
        val allDay: Boolean? get() = all_day
        val fromMs: Long? get() = from_ms
        val toMs: Long? get() = to_ms
        val requestIfMissing: Boolean? get() = request_if_missing
        val openSettingsIfFailed: Boolean? get() = open_settings_if_failed
        val waitUserConfirmation: Boolean? get() = wait_user_confirmation
    }

    private data class CalendarEventRow(
        val id: Long,
        val calendarId: Long,
        val title: String,
        val beginMs: Long,
        val endMs: Long,
        val location: String,
        val allDay: Boolean
    ) {
        fun summary(): String {
            return "id=$id calendar_id=$calendarId title=$title begin_ms=$beginMs end_ms=$endMs all_day=$allDay location=$location"
        }
    }

    private data class CalendarEventDetail(
        val id: Long,
        val calendarId: Long,
        val title: String,
        val startMs: Long,
        val endMs: Long,
        val description: String,
        val location: String,
        val allDay: Boolean
    )

    private data class CalendarInfoRow(
        val id: Long,
        val name: String,
        val owner: String,
        val visible: Boolean,
        val accessLevel: Int
    ) {
        fun summary(): String {
            return "id=$id name=$name owner=$owner visible=$visible access_level=$accessLevel"
        }
    }
}

private class ContactsControlTool(
    private val context: Context
) : Tool, TimedTool {
    override val name: String = "contacts"
    override val description: String =
        "Unified contacts tool. action=search|get_contact|create_contact|update_contact|delete_contact|open_app_settings"
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
                  "action":{"type":"string","enum":["search","get_contact","create_contact","update_contact","delete_contact","open_app_settings"]},
                  "contact_id":{"type":"integer"},
                  "query":{"type":"string"},
                  "name":{"type":"string"},
                  "phone":{"type":"string"},
                  "email":{"type":"string"},
                  "note":{"type":"string"},
                  "replace_phones":{"type":"boolean"},
                  "replace_emails":{"type":"boolean"},
                  "clear_phone":{"type":"boolean"},
                  "clear_email":{"type":"boolean"},
                  "clear_note":{"type":"boolean"},
                  "count":{"type":"integer","minimum":1,"maximum":50},
                  "request_if_missing":{"type":"boolean"},
                  "open_settings_if_failed":{"type":"boolean"},
                  "wait_user_confirmation":{"type":"boolean"}
                }
                """.trimIndent()
            )
        )
    }

    override suspend fun run(argumentsJson: String): ToolResult = withContext(Dispatchers.IO) {
        val args = Json.decodeFromString<ContactsArgs>(argumentsJson)
        val action = args.action.trim().lowercase(Locale.US)
        return@withContext when (action) {
            "search" -> actionSearch(args)
            "get_contact" -> actionGetContact(args)
            "create_contact" -> actionCreateContact(args)
            "update_contact" -> actionUpdateContact(args)
            "delete_contact" -> actionDeleteContact(args)
            "open_app_settings" -> actionOpenAppSettings(action)
            else -> personalError(
                toolName = name,
                action = action,
                code = "unsupported_action",
                message = "Unsupported action '${args.action}'.",
                nextStep = "Use action=search|get_contact|create_contact|update_contact|delete_contact|open_app_settings."
            )
        }
    }

    private suspend fun actionSearch(args: ContactsArgs): ToolResult {
        val action = "search"
        val permissionsError = ensurePersonalPermissionsInteractive(
            context = context,
            toolName = name,
            action = action,
            required = listOf(Manifest.permission.READ_CONTACTS),
            requestIfMissing = args.requestIfMissing ?: true,
            openSettingsIfFailed = args.openSettingsIfFailed ?: true,
            waitUserConfirmation = args.waitUserConfirmation ?: true
        )
        if (permissionsError != null) return permissionsError

        val query = args.query?.trim().orEmpty()
        val count = (args.count ?: 20).coerceIn(1, 50)
        val uri = if (query.isBlank()) {
            ContactsContract.Contacts.CONTENT_URI
        } else {
            Uri.withAppendedPath(ContactsContract.Contacts.CONTENT_FILTER_URI, Uri.encode(query))
        }
        val projection = arrayOf(
            ContactsContract.Contacts._ID,
            ContactsContract.Contacts.DISPLAY_NAME_PRIMARY
        )

        val rows = mutableListOf<ContactRow>()
        context.contentResolver.query(
            uri,
            projection,
            null,
            null,
            "${ContactsContract.Contacts.DISPLAY_NAME_PRIMARY} ASC"
        )?.use { c ->
            val idCol = c.getColumnIndexOrThrow(ContactsContract.Contacts._ID)
            val nameCol = c.getColumnIndexOrThrow(ContactsContract.Contacts.DISPLAY_NAME_PRIMARY)
            while (c.moveToNext() && rows.size < count) {
                val contactId = c.getLong(idCol)
                rows += ContactRow(
                    id = contactId,
                    name = c.getString(nameCol).orEmpty(),
                    phone = queryFirstPhone(contactId),
                    email = queryFirstEmail(contactId)
                )
            }
        }

        val text = if (rows.isEmpty()) {
            "No contacts found."
        } else {
            rows.joinToString("\n") { row ->
                "id=${row.id} | ${row.name.ifBlank { "(no name)" }}${if (row.phone.isBlank()) "" else " | phone=${row.phone}"}${if (row.email.isBlank()) "" else " | email=${row.email}"}"
            }
        }

        return personalOk(
            toolName = name,
            action = action,
            message = text
        ) {
            put("query", query)
            put("count", rows.size)
            putJsonArray("contacts") {
                rows.forEach { row -> add(row.summary()) }
            }
        }
    }
    private suspend fun actionGetContact(args: ContactsArgs): ToolResult {
        val action = "get_contact"
        val contactId = args.contactId
            ?: return personalError(
                toolName = name,
                action = action,
                code = "invalid_arguments",
                message = "contact_id is required.",
                nextStep = "Pass contact_id."
            )

        val permissionsError = ensurePersonalPermissionsInteractive(
            context = context,
            toolName = name,
            action = action,
            required = listOf(Manifest.permission.READ_CONTACTS),
            requestIfMissing = args.requestIfMissing ?: true,
            openSettingsIfFailed = args.openSettingsIfFailed ?: true,
            waitUserConfirmation = args.waitUserConfirmation ?: true
        )
        if (permissionsError != null) return permissionsError

        val detail = queryContactDetail(contactId)
            ?: return personalError(
                toolName = name,
                action = action,
                code = "not_found",
                message = "Contact id=$contactId not found.",
                nextStep = "Use search action to find a valid contact_id."
            )

        val text = buildString {
            append("id=${detail.id} | name=${detail.name.ifBlank { "(no name)" }}")
            if (detail.phones.isNotEmpty()) {
                append(" | phones=${detail.phones.joinToString(", ")}")
            }
            if (detail.emails.isNotEmpty()) {
                append(" | emails=${detail.emails.joinToString(", ")}")
            }
            if (detail.note.isNotBlank()) {
                append(" | note=${detail.note}")
            }
        }

        return personalOk(
            toolName = name,
            action = action,
            message = text
        ) {
            put("contact_id", detail.id)
            put("name", detail.name)
            putJsonArray("phones") {
                detail.phones.forEach { add(it) }
            }
            putJsonArray("emails") {
                detail.emails.forEach { add(it) }
            }
            put("note", detail.note)
        }
    }

    private suspend fun actionCreateContact(args: ContactsArgs): ToolResult {
        val action = "create_contact"
        val displayName = args.name?.trim().orEmpty()
        val phone = args.phone?.trim().orEmpty()
        val email = args.email?.trim().orEmpty()
        val note = args.note?.trim().orEmpty()
        if (displayName.isBlank() && phone.isBlank() && email.isBlank() && note.isBlank()) {
            return personalError(
                toolName = name,
                action = action,
                code = "invalid_arguments",
                message = "At least one of name/phone/email/note is required.",
                nextStep = "Provide contact fields and retry."
            )
        }

        val permissionsError = ensurePersonalPermissionsInteractive(
            context = context,
            toolName = name,
            action = action,
            required = listOf(Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS),
            requestIfMissing = args.requestIfMissing ?: true,
            openSettingsIfFailed = args.openSettingsIfFailed ?: true,
            waitUserConfirmation = args.waitUserConfirmation ?: true
        )
        if (permissionsError != null) return permissionsError

        val rawInsert = context.contentResolver.insert(
            ContactsContract.RawContacts.CONTENT_URI,
            ContentValues().apply {
                put(ContactsContract.RawContacts.ACCOUNT_TYPE, null as String?)
                put(ContactsContract.RawContacts.ACCOUNT_NAME, null as String?)
            }
        ) ?: return personalError(
            toolName = name,
            action = action,
            code = "insert_failed",
            message = "Failed to insert raw contact.",
            nextStep = "Check contacts provider/account state and retry."
        )

        val rawId = ContentUris.parseId(rawInsert)
        var changed = 0
        if (displayName.isNotBlank()) {
            changed += insertContactData(
                rawContactId = rawId,
                mimeType = ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE
            ) {
                put(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME, displayName)
            }
        }
        if (phone.isNotBlank()) {
            changed += insertContactData(
                rawContactId = rawId,
                mimeType = ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE
            ) {
                put(ContactsContract.CommonDataKinds.Phone.NUMBER, phone)
                put(ContactsContract.CommonDataKinds.Phone.TYPE, ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE)
            }
        }
        if (email.isNotBlank()) {
            changed += insertContactData(
                rawContactId = rawId,
                mimeType = ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE
            ) {
                put(ContactsContract.CommonDataKinds.Email.ADDRESS, email)
                put(ContactsContract.CommonDataKinds.Email.TYPE, ContactsContract.CommonDataKinds.Email.TYPE_OTHER)
            }
        }
        if (note.isNotBlank()) {
            changed += insertContactData(
                rawContactId = rawId,
                mimeType = ContactsContract.CommonDataKinds.Note.CONTENT_ITEM_TYPE
            ) {
                put(ContactsContract.CommonDataKinds.Note.NOTE, note)
            }
        }

        val contactId = queryContactIdByRawId(rawId)
        return personalOk(
            toolName = name,
            action = action,
            message = "contact created: contact_id=${contactId ?: -1} raw_contact_id=$rawId"
        ) {
            put("contact_id", contactId ?: -1L)
            put("raw_contact_id", rawId)
            put("changed_fields", changed)
        }
    }

    private suspend fun actionUpdateContact(args: ContactsArgs): ToolResult {
        val action = "update_contact"
        val contactId = args.contactId
            ?: return personalError(
                toolName = name,
                action = action,
                code = "invalid_arguments",
                message = "contact_id is required.",
                nextStep = "Pass contact_id."
            )

        val permissionsError = ensurePersonalPermissionsInteractive(
            context = context,
            toolName = name,
            action = action,
            required = listOf(Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS),
            requestIfMissing = args.requestIfMissing ?: true,
            openSettingsIfFailed = args.openSettingsIfFailed ?: true,
            waitUserConfirmation = args.waitUserConfirmation ?: true
        )
        if (permissionsError != null) return permissionsError

        val detail = queryContactDetail(contactId)
            ?: return personalError(
                toolName = name,
                action = action,
                code = "not_found",
                message = "Contact id=$contactId not found.",
                nextStep = "Use search action to find a valid contact_id."
            )
        val rawContactId = queryFirstRawContactId(contactId)
            ?: return personalError(
                toolName = name,
                action = action,
                code = "raw_contact_missing",
                message = "No writable raw contact found for id=$contactId.",
                nextStep = "Try another contact_id or create a new contact."
            )

        if (args.clearNote == true && args.note != null) {
            return personalError(
                toolName = name,
                action = action,
                code = "invalid_arguments",
                message = "clear_note cannot be used together with note.",
                nextStep = "Use either clear_note=true or provide note."
            )
        }
        if (args.clearPhone == true && args.phone != null) {
            return personalError(
                toolName = name,
                action = action,
                code = "invalid_arguments",
                message = "clear_phone cannot be used together with phone.",
                nextStep = "Use either clear_phone=true or provide phone."
            )
        }
        if (args.clearEmail == true && args.email != null) {
            return personalError(
                toolName = name,
                action = action,
                code = "invalid_arguments",
                message = "clear_email cannot be used together with email.",
                nextStep = "Use either clear_email=true or provide email."
            )
        }

        val hasChanges = args.name != null ||
            args.phone != null ||
            args.email != null ||
            args.note != null ||
            args.clearPhone == true ||
            args.clearEmail == true ||
            args.clearNote == true
        if (!hasChanges) {
            return personalError(
                toolName = name,
                action = action,
                code = "invalid_arguments",
                message = "No update fields provided.",
                nextStep = "Provide at least one field to update."
            )
        }

        var changedRows = 0
        val changedFields = mutableListOf<String>()

        if (args.name != null) {
            val value = args.name.trim()
            if (value.isBlank()) {
                return personalError(
                    toolName = name,
                    action = action,
                    code = "invalid_arguments",
                    message = "name cannot be blank.",
                    nextStep = "Pass non-empty name or omit name."
                )
            }
            changedRows += upsertSingleContactData(
                contactId = contactId,
                rawContactId = rawContactId,
                mimeType = ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE,
                valueColumn = ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME,
                value = value
            )
            changedFields += "name"
        }

        if (args.clearPhone == true) {
            changedRows += deleteContactDataByMime(contactId, ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE)
            changedFields += "phone"
        } else if (args.phone != null) {
            val value = args.phone.trim()
            if (value.isBlank()) {
                return personalError(
                    toolName = name,
                    action = action,
                    code = "invalid_arguments",
                    message = "phone cannot be blank.",
                    nextStep = "Pass non-empty phone or use clear_phone=true."
                )
            }
            if (args.replacePhones ?: true) {
                deleteContactDataByMime(contactId, ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE)
                changedRows += insertContactData(
                    rawContactId = rawContactId,
                    mimeType = ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE
                ) {
                    put(ContactsContract.CommonDataKinds.Phone.NUMBER, value)
                    put(ContactsContract.CommonDataKinds.Phone.TYPE, ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE)
                }
            } else {
                changedRows += upsertSingleContactData(
                    contactId = contactId,
                    rawContactId = rawContactId,
                    mimeType = ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE,
                    valueColumn = ContactsContract.CommonDataKinds.Phone.NUMBER,
                    value = value
                )
            }
            changedFields += "phone"
        }

        if (args.clearEmail == true) {
            changedRows += deleteContactDataByMime(contactId, ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE)
            changedFields += "email"
        } else if (args.email != null) {
            val value = args.email.trim()
            if (value.isBlank()) {
                return personalError(
                    toolName = name,
                    action = action,
                    code = "invalid_arguments",
                    message = "email cannot be blank.",
                    nextStep = "Pass non-empty email or use clear_email=true."
                )
            }
            if (args.replaceEmails ?: true) {
                deleteContactDataByMime(contactId, ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE)
                changedRows += insertContactData(
                    rawContactId = rawContactId,
                    mimeType = ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE
                ) {
                    put(ContactsContract.CommonDataKinds.Email.ADDRESS, value)
                    put(ContactsContract.CommonDataKinds.Email.TYPE, ContactsContract.CommonDataKinds.Email.TYPE_OTHER)
                }
            } else {
                changedRows += upsertSingleContactData(
                    contactId = contactId,
                    rawContactId = rawContactId,
                    mimeType = ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE,
                    valueColumn = ContactsContract.CommonDataKinds.Email.ADDRESS,
                    value = value
                )
            }
            changedFields += "email"
        }

        if (args.clearNote == true) {
            changedRows += deleteContactDataByMime(contactId, ContactsContract.CommonDataKinds.Note.CONTENT_ITEM_TYPE)
            changedFields += "note"
        } else if (args.note != null) {
            val value = args.note.trim()
            if (value.isBlank()) {
                changedRows += deleteContactDataByMime(contactId, ContactsContract.CommonDataKinds.Note.CONTENT_ITEM_TYPE)
            } else {
                changedRows += upsertSingleContactData(
                    contactId = contactId,
                    rawContactId = rawContactId,
                    mimeType = ContactsContract.CommonDataKinds.Note.CONTENT_ITEM_TYPE,
                    valueColumn = ContactsContract.CommonDataKinds.Note.NOTE,
                    value = value
                )
            }
            changedFields += "note"
        }

        val reloaded = queryContactDetail(contactId)
        return personalOk(
            toolName = name,
            action = action,
            message = "contact updated: id=$contactId changed=${changedFields.distinct().joinToString(",")}"
        ) {
            put("contact_id", contactId)
            put("changed_rows", changedRows)
            putJsonArray("changed_fields") {
                changedFields.distinct().forEach { add(it) }
            }
            if (reloaded != null) {
                put("name", reloaded.name)
                putJsonArray("phones") {
                    reloaded.phones.forEach { add(it) }
                }
                putJsonArray("emails") {
                    reloaded.emails.forEach { add(it) }
                }
                put("note", reloaded.note)
            }
        }
    }

    private suspend fun actionDeleteContact(args: ContactsArgs): ToolResult {
        val action = "delete_contact"
        val contactId = args.contactId
            ?: return personalError(
                toolName = name,
                action = action,
                code = "invalid_arguments",
                message = "contact_id is required.",
                nextStep = "Pass contact_id."
            )

        val permissionsError = ensurePersonalPermissionsInteractive(
            context = context,
            toolName = name,
            action = action,
            required = listOf(Manifest.permission.WRITE_CONTACTS),
            requestIfMissing = args.requestIfMissing ?: true,
            openSettingsIfFailed = args.openSettingsIfFailed ?: true,
            waitUserConfirmation = args.waitUserConfirmation ?: true
        )
        if (permissionsError != null) return permissionsError

        val deleted = context.contentResolver.delete(
            ContactsContract.RawContacts.CONTENT_URI,
            "${ContactsContract.RawContacts.CONTACT_ID}=?",
            arrayOf(contactId.toString())
        )
        if (deleted <= 0) {
            return personalError(
                toolName = name,
                action = action,
                code = "not_found",
                message = "Contact id=$contactId not found or already deleted.",
                nextStep = "Use search action to check existing contacts."
            )
        }

        return personalOk(
            toolName = name,
            action = action,
            message = "contact deleted: id=$contactId"
        ) {
            put("contact_id", contactId)
            put("deleted_rows", deleted)
        }
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

    private fun queryContactDetail(contactId: Long): ContactDetailRow? {
        val projection = arrayOf(
            ContactsContract.Contacts._ID,
            ContactsContract.Contacts.DISPLAY_NAME_PRIMARY
        )
        context.contentResolver.query(
            ContentUris.withAppendedId(ContactsContract.Contacts.CONTENT_URI, contactId),
            projection,
            null,
            null,
            null
        )?.use { c ->
            if (!c.moveToFirst()) return null
            val idCol = c.getColumnIndexOrThrow(ContactsContract.Contacts._ID)
            val nameCol = c.getColumnIndexOrThrow(ContactsContract.Contacts.DISPLAY_NAME_PRIMARY)
            val id = c.getLong(idCol)
            val displayName = c.getString(nameCol).orEmpty()
            return ContactDetailRow(
                id = id,
                name = displayName,
                phones = queryPhones(contactId),
                emails = queryEmails(contactId),
                note = queryNote(contactId)
            )
        }
        return null
    }

    private fun queryPhones(contactId: Long): List<String> {
        val out = mutableListOf<String>()
        context.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER),
            "${ContactsContract.CommonDataKinds.Phone.CONTACT_ID}=?",
            arrayOf(contactId.toString()),
            "${ContactsContract.CommonDataKinds.Phone.IS_PRIMARY} DESC, ${ContactsContract.CommonDataKinds.Phone._ID} ASC"
        )?.use { c ->
            val col = c.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.NUMBER)
            while (c.moveToNext()) {
                val num = c.getString(col).orEmpty().trim()
                if (num.isNotBlank() && num !in out) {
                    out += num
                }
            }
        }
        return out
    }

    private fun queryEmails(contactId: Long): List<String> {
        val out = mutableListOf<String>()
        context.contentResolver.query(
            ContactsContract.CommonDataKinds.Email.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Email.ADDRESS),
            "${ContactsContract.CommonDataKinds.Email.CONTACT_ID}=?",
            arrayOf(contactId.toString()),
            "${ContactsContract.CommonDataKinds.Email.IS_PRIMARY} DESC, ${ContactsContract.CommonDataKinds.Email._ID} ASC"
        )?.use { c ->
            val col = c.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Email.ADDRESS)
            while (c.moveToNext()) {
                val address = c.getString(col).orEmpty().trim()
                if (address.isNotBlank() && address !in out) {
                    out += address
                }
            }
        }
        return out
    }

    private fun queryNote(contactId: Long): String {
        context.contentResolver.query(
            ContactsContract.Data.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Note.NOTE),
            "${ContactsContract.Data.CONTACT_ID}=? AND ${ContactsContract.Data.MIMETYPE}=?",
            arrayOf(contactId.toString(), ContactsContract.CommonDataKinds.Note.CONTENT_ITEM_TYPE),
            "${ContactsContract.Data._ID} ASC"
        )?.use { c ->
            if (!c.moveToFirst()) return ""
            val col = c.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Note.NOTE)
            return c.getString(col).orEmpty()
        }
        return ""
    }

    private fun queryFirstPhone(contactId: Long): String {
        return queryPhones(contactId).firstOrNull().orEmpty()
    }

    private fun queryFirstEmail(contactId: Long): String {
        return queryEmails(contactId).firstOrNull().orEmpty()
    }

    private fun queryContactIdByRawId(rawContactId: Long): Long? {
        context.contentResolver.query(
            ContentUris.withAppendedId(ContactsContract.RawContacts.CONTENT_URI, rawContactId),
            arrayOf(ContactsContract.RawContacts.CONTACT_ID),
            null,
            null,
            null
        )?.use { c ->
            if (!c.moveToFirst()) return null
            val col = c.getColumnIndexOrThrow(ContactsContract.RawContacts.CONTACT_ID)
            return c.getLong(col)
        }
        return null
    }

    private fun queryFirstRawContactId(contactId: Long): Long? {
        context.contentResolver.query(
            ContactsContract.RawContacts.CONTENT_URI,
            arrayOf(ContactsContract.RawContacts._ID),
            "${ContactsContract.RawContacts.CONTACT_ID}=?",
            arrayOf(contactId.toString()),
            "${ContactsContract.RawContacts._ID} ASC"
        )?.use { c ->
            if (!c.moveToFirst()) return null
            val col = c.getColumnIndexOrThrow(ContactsContract.RawContacts._ID)
            return c.getLong(col)
        }
        return null
    }

    private fun insertContactData(
        rawContactId: Long,
        mimeType: String,
        fill: ContentValues.() -> Unit
    ): Int {
        val values = ContentValues().apply {
            put(ContactsContract.Data.RAW_CONTACT_ID, rawContactId)
            put(ContactsContract.Data.MIMETYPE, mimeType)
            fill()
        }
        val inserted = context.contentResolver.insert(ContactsContract.Data.CONTENT_URI, values)
        return if (inserted == null) 0 else 1
    }

    private fun deleteContactDataByMime(contactId: Long, mimeType: String): Int {
        return context.contentResolver.delete(
            ContactsContract.Data.CONTENT_URI,
            "${ContactsContract.Data.CONTACT_ID}=? AND ${ContactsContract.Data.MIMETYPE}=?",
            arrayOf(contactId.toString(), mimeType)
        )
    }

    private fun upsertSingleContactData(
        contactId: Long,
        rawContactId: Long,
        mimeType: String,
        valueColumn: String,
        value: String
    ): Int {
        val existingDataId = context.contentResolver.query(
            ContactsContract.Data.CONTENT_URI,
            arrayOf(ContactsContract.Data._ID),
            "${ContactsContract.Data.CONTACT_ID}=? AND ${ContactsContract.Data.MIMETYPE}=?",
            arrayOf(contactId.toString(), mimeType),
            "${ContactsContract.Data._ID} ASC"
        )?.use { c ->
            if (!c.moveToFirst()) {
                null
            } else {
                val col = c.getColumnIndexOrThrow(ContactsContract.Data._ID)
                c.getLong(col)
            }
        }

        return if (existingDataId != null) {
            context.contentResolver.update(
                ContentUris.withAppendedId(ContactsContract.Data.CONTENT_URI, existingDataId),
                ContentValues().apply { put(valueColumn, value) },
                null,
                null
            )
        } else {
            insertContactData(rawContactId, mimeType) {
                put(valueColumn, value)
                if (mimeType == ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE) {
                    put(ContactsContract.CommonDataKinds.Phone.TYPE, ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE)
                }
                if (mimeType == ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE) {
                    put(ContactsContract.CommonDataKinds.Email.TYPE, ContactsContract.CommonDataKinds.Email.TYPE_OTHER)
                }
            }
        }
    }

    @Serializable
    private data class ContactsArgs(
        val action: String,
        val contact_id: Long? = null,
        val query: String? = null,
        val name: String? = null,
        val phone: String? = null,
        val email: String? = null,
        val note: String? = null,
        val replace_phones: Boolean? = null,
        val replace_emails: Boolean? = null,
        val clear_phone: Boolean? = null,
        val clear_email: Boolean? = null,
        val clear_note: Boolean? = null,
        val count: Int? = null,
        val request_if_missing: Boolean? = null,
        val open_settings_if_failed: Boolean? = null,
        val wait_user_confirmation: Boolean? = null
    ) {
        val contactId: Long? get() = contact_id
        val replacePhones: Boolean? get() = replace_phones
        val replaceEmails: Boolean? get() = replace_emails
        val clearPhone: Boolean? get() = clear_phone
        val clearEmail: Boolean? get() = clear_email
        val clearNote: Boolean? get() = clear_note
        val requestIfMissing: Boolean? get() = request_if_missing
        val openSettingsIfFailed: Boolean? get() = open_settings_if_failed
        val waitUserConfirmation: Boolean? get() = wait_user_confirmation
    }

    private data class ContactRow(
        val id: Long,
        val name: String,
        val phone: String,
        val email: String
    ) {
        fun summary(): String {
            return "id=$id name=$name phone=$phone email=$email"
        }
    }

    private data class ContactDetailRow(
        val id: Long,
        val name: String,
        val phones: List<String>,
        val emails: List<String>,
        val note: String
    )
}
private suspend fun ensurePersonalPermissionsInteractive(
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

private fun openPersonalAppSettings(context: Context): ToolResult {
    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
        data = Uri.parse("package:${context.packageName}")
    }
    return launchIntent(context, intent)
}

private fun personalOk(
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

private fun personalError(
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
