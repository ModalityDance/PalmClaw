package com.palmclaw.tools

import android.content.ContentProviderOperation
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.CalendarContract
import java.util.Locale

internal object CalendarDomainValues {
    val defaultAvailability: Int = CalendarContract.Events.AVAILABILITY_BUSY
    val defaultAccessLevel: Int = CalendarContract.Events.ACCESS_DEFAULT
    val defaultReminderMethod: Int = CalendarContract.Reminders.METHOD_DEFAULT
    val confirmedEventStatus: Int = CalendarContract.Events.STATUS_CONFIRMED
    val cancelledEventStatus: Int = CalendarContract.Events.STATUS_CANCELED

    fun responseStatus(value: String): Int? = when (value) {
        "accepted" -> CalendarContract.Attendees.ATTENDEE_STATUS_ACCEPTED
        "declined" -> CalendarContract.Attendees.ATTENDEE_STATUS_DECLINED
        "tentative" -> CalendarContract.Attendees.ATTENDEE_STATUS_TENTATIVE
        "none" -> CalendarContract.Attendees.ATTENDEE_STATUS_NONE
        else -> null
    }

    fun availability(value: String): Int? = when (value) {
        "busy" -> CalendarContract.Events.AVAILABILITY_BUSY
        "free" -> CalendarContract.Events.AVAILABILITY_FREE
        "tentative" -> CalendarContract.Events.AVAILABILITY_TENTATIVE
        else -> null
    }

    fun availabilityName(value: Int): String = when (value) {
        CalendarContract.Events.AVAILABILITY_FREE -> "free"
        CalendarContract.Events.AVAILABILITY_TENTATIVE -> "tentative"
        else -> "busy"
    }

    fun accessLevel(value: String): Int? = when (value) {
        "default" -> CalendarContract.Events.ACCESS_DEFAULT
        "private" -> CalendarContract.Events.ACCESS_PRIVATE
        "public" -> CalendarContract.Events.ACCESS_PUBLIC
        else -> null
    }

    fun accessLevelName(value: Int): String = when (value) {
        CalendarContract.Events.ACCESS_PRIVATE -> "private"
        CalendarContract.Events.ACCESS_PUBLIC -> "public"
        else -> "default"
    }

    fun calendarAccessName(value: Int): String = when (value) {
        CalendarContract.Calendars.CAL_ACCESS_NONE -> "none"
        CalendarContract.Calendars.CAL_ACCESS_FREEBUSY -> "free_busy"
        CalendarContract.Calendars.CAL_ACCESS_READ -> "read"
        CalendarContract.Calendars.CAL_ACCESS_RESPOND -> "respond"
        CalendarContract.Calendars.CAL_ACCESS_OVERRIDE -> "override"
        CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR -> "contributor"
        CalendarContract.Calendars.CAL_ACCESS_EDITOR -> "editor"
        CalendarContract.Calendars.CAL_ACCESS_OWNER -> "owner"
        CalendarContract.Calendars.CAL_ACCESS_ROOT -> "root"
        else -> "unknown"
    }

    fun reminderMethod(value: String): Int? = when (value) {
        "default" -> CalendarContract.Reminders.METHOD_DEFAULT
        "alert" -> CalendarContract.Reminders.METHOD_ALERT
        else -> null
    }

    fun reminderMethodName(value: Int): String = when (value) {
        CalendarContract.Reminders.METHOD_DEFAULT -> "default"
        CalendarContract.Reminders.METHOD_ALERT -> "alert"
        CalendarContract.Reminders.METHOD_EMAIL -> "email"
        CalendarContract.Reminders.METHOD_SMS -> "sms"
        else -> "unknown:$value"
    }

    fun attendeeType(value: String): Int? = when (value) {
        "required" -> CalendarContract.Attendees.TYPE_REQUIRED
        "optional" -> CalendarContract.Attendees.TYPE_OPTIONAL
        "resource" -> CalendarContract.Attendees.TYPE_RESOURCE
        else -> null
    }

    fun attendeeTypeName(value: Int): String = when (value) {
        CalendarContract.Attendees.TYPE_REQUIRED -> "required"
        CalendarContract.Attendees.TYPE_OPTIONAL -> "optional"
        CalendarContract.Attendees.TYPE_RESOURCE -> "resource"
        else -> "none"
    }

    fun isManagedAttendeeRelationship(value: Int): Boolean =
        value != CalendarContract.Attendees.RELATIONSHIP_ORGANIZER

    fun isAttendeeRelationship(value: Int): Boolean =
        value == CalendarContract.Attendees.RELATIONSHIP_ATTENDEE

    fun attendeeRelationshipName(value: Int): String = when (value) {
        CalendarContract.Attendees.RELATIONSHIP_ORGANIZER -> "organizer"
        CalendarContract.Attendees.RELATIONSHIP_PERFORMER -> "performer"
        CalendarContract.Attendees.RELATIONSHIP_SPEAKER -> "speaker"
        else -> "attendee"
    }

    fun attendeeStatusName(value: Int): String = when (value) {
        CalendarContract.Attendees.ATTENDEE_STATUS_ACCEPTED -> "accepted"
        CalendarContract.Attendees.ATTENDEE_STATUS_DECLINED -> "declined"
        CalendarContract.Attendees.ATTENDEE_STATUS_TENTATIVE -> "tentative"
        CalendarContract.Attendees.ATTENDEE_STATUS_INVITED -> "invited"
        else -> "none"
    }
}

internal data class CalendarInfo(
    val id: Long,
    val name: String,
    val accountName: String,
    val owner: String,
    val timezone: String,
    val visible: Boolean,
    val accessLevel: Int,
    val primary: Boolean,
    val maxReminders: Int,
    val allowedReminderMethods: Set<Int>,
    val allowedAttendeeTypes: Set<Int>,
    val allowedAvailability: Set<Int>
) {
    val writable: Boolean
        get() = visible && accessLevel >= CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR
    val accountIdentity: String
        get() = accountName.ifBlank { owner }
}

internal data class CalendarInstanceRecord(
    val seriesEventId: Long,
    val instanceEventId: Long,
    val calendarId: Long,
    val title: String,
    val beginMs: Long,
    val endMs: Long,
    val location: String,
    val allDay: Boolean,
    val recurring: Boolean,
    val originalInstanceStartMs: Long?
)

internal data class CalendarEventRecord(
    val id: Long,
    val calendarId: Long,
    val title: String,
    val startMs: Long,
    val endMs: Long?,
    val duration: String?,
    val description: String,
    val location: String,
    val allDay: Boolean,
    val timezone: String,
    val endTimezone: String?,
    val availability: Int,
    val accessLevel: Int,
    val guestsCanModify: Boolean,
    val guestsCanInviteOthers: Boolean,
    val guestsCanSeeGuests: Boolean,
    val rrule: String?,
    val rdate: String?,
    val exdate: String?,
    val status: Int,
    val organizer: String,
    val selfAttendeeStatus: Int,
    val isOrganizer: Boolean,
    val canInviteOthers: Boolean,
    val originalId: Long?,
    val originalInstanceStartMs: Long?
) {
    val recurring: Boolean get() = !rrule.isNullOrBlank() || !rdate.isNullOrBlank()
    val resolvedEndMs: Long?
        get() = endMs ?: CalendarRecurrenceCodec.parseDurationMillis(duration)?.let(startMs::plus)
}

internal data class CalendarReminderRecord(
    val id: Long,
    val minutesBefore: Int,
    val method: Int
)

internal data class CalendarAttendeeRecord(
    val id: Long,
    val name: String,
    val email: String,
    val type: Int,
    val relationship: Int,
    val status: Int
)

internal data class CalendarEventAggregate(
    val event: CalendarEventRecord,
    val reminders: List<CalendarReminderRecord>,
    val attendees: List<CalendarAttendeeRecord>
)

internal data class CalendarReminderDraft(
    val minutesBefore: Int,
    val method: Int
)

internal data class CalendarAttendeeDraft(
    val name: String,
    val email: String,
    val type: Int
)

internal data class CalendarAttendeeInsert(
    val attendee: CalendarAttendeeDraft,
    val status: Int
)

internal data class CalendarAttendeeReplacementPlan(
    val deleteIds: List<Long>,
    val inserts: List<CalendarAttendeeInsert>
)

internal object CalendarAttendeeReplacementPlanner {
    fun plan(
        existing: List<CalendarAttendeeRecord>,
        requested: List<CalendarAttendeeDraft>,
        accountIdentity: String
    ): CalendarAttendeeReplacementPlan {
        require(accountIdentity.isNotBlank()) { "Calendar account identity is required for safe attendee replacement." }
        val statusByEmail = existing.associate { it.email.lowercase(Locale.US) to it.status }
        val deleteIds = existing.filter {
            CalendarDomainValues.isManagedAttendeeRelationship(it.relationship) &&
                !it.email.equals(accountIdentity, ignoreCase = true)
        }.map { it.id }
        val inserts = requested
            .filterNot { it.email.equals(accountIdentity, ignoreCase = true) }
            .map { attendee ->
                CalendarAttendeeInsert(
                    attendee,
                    statusByEmail[attendee.email.lowercase(Locale.US)]
                        ?: CalendarContract.Attendees.ATTENDEE_STATUS_INVITED
                )
            }
        return CalendarAttendeeReplacementPlan(deleteIds, inserts)
    }
}

internal data class CalendarEventDraft(
    val calendarId: Long,
    val title: String,
    val startMs: Long,
    val endMs: Long?,
    val duration: String?,
    val description: String,
    val location: String,
    val allDay: Boolean,
    val timezone: String,
    val endTimezone: String?,
    val availability: Int,
    val accessLevel: Int,
    val guestsCanModify: Boolean,
    val guestsCanInviteOthers: Boolean,
    val guestsCanSeeGuests: Boolean,
    val rrule: String?,
    val rdate: String?,
    val exdate: String?,
    val status: Int? = null
)

internal sealed interface CalendarMutation {
    data class Create(
        val event: CalendarEventDraft,
        val reminders: List<CalendarReminderDraft>,
        val attendees: List<CalendarAttendeeDraft>
    ) : CalendarMutation

    data class UpdateSeries(
        val eventId: Long,
        val event: CalendarEventDraft,
        val reminders: List<CalendarReminderDraft>?,
        val attendees: List<CalendarAttendeeDraft>?,
        val accountIdentity: String
    ) : CalendarMutation

    data class UpsertException(
        val seriesEventId: Long,
        val originalInstanceStartMs: Long,
        val event: CalendarEventDraft
    ) : CalendarMutation

    data class DeleteSeries(val eventId: Long) : CalendarMutation

    data class Respond(
        val attendeeId: Long,
        val status: Int
    ) : CalendarMutation
}

internal data class CalendarMutationResult(
    val eventId: Long?,
    val affectedRows: Int
)

internal interface CalendarProviderGateway {
    fun listCalendars(): List<CalendarInfo>
    fun listInstances(fromMs: Long, toMs: Long, limit: Int): List<CalendarInstanceRecord>
    fun findOccurrence(seriesEventId: Long, instanceStartMs: Long): CalendarInstanceRecord?
    fun getEvent(eventId: Long): CalendarEventAggregate?
    fun mutate(mutation: CalendarMutation): CalendarMutationResult
}

internal class AndroidCalendarProviderGateway(
    context: Context
) : CalendarProviderGateway {
    private val resolver = context.contentResolver

    override fun listCalendars(): List<CalendarInfo> {
        val projection = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
            CalendarContract.Calendars.ACCOUNT_NAME,
            CalendarContract.Calendars.OWNER_ACCOUNT,
            CalendarContract.Calendars.CALENDAR_TIME_ZONE,
            CalendarContract.Calendars.VISIBLE,
            CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL,
            CalendarContract.Calendars.IS_PRIMARY,
            CalendarContract.Calendars.MAX_REMINDERS,
            CalendarContract.Calendars.ALLOWED_REMINDERS,
            CalendarContract.Calendars.ALLOWED_ATTENDEE_TYPES,
            CalendarContract.Calendars.ALLOWED_AVAILABILITY
        )
        val rows = mutableListOf<CalendarInfo>()
        resolver.query(
            CalendarContract.Calendars.CONTENT_URI,
            projection,
            null,
            null,
            "${CalendarContract.Calendars.CALENDAR_DISPLAY_NAME} ASC"
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                rows += CalendarInfo(
                    id = cursor.getLong(0),
                    name = cursor.getString(1).orEmpty(),
                    accountName = cursor.getString(2).orEmpty(),
                    owner = cursor.getString(3).orEmpty(),
                    timezone = cursor.getString(4).orEmpty(),
                    visible = cursor.getInt(5) == 1,
                    accessLevel = cursor.getInt(6),
                    primary = cursor.getInt(7) == 1,
                    maxReminders = cursor.getInt(8).coerceAtLeast(0),
                    allowedReminderMethods = parseIntSet(cursor.getString(9)),
                    allowedAttendeeTypes = parseIntSet(cursor.getString(10)),
                    allowedAvailability = parseIntSet(cursor.getString(11))
                )
            }
        }
        return rows
    }

    override fun listInstances(fromMs: Long, toMs: Long, limit: Int): List<CalendarInstanceRecord> {
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also { builder ->
            ContentUris.appendId(builder, fromMs)
            ContentUris.appendId(builder, toMs)
        }.build()
        val projection = arrayOf(
            CalendarContract.Instances.EVENT_ID,
            CalendarContract.Instances.CALENDAR_ID,
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END,
            CalendarContract.Instances.EVENT_LOCATION,
            CalendarContract.Instances.ALL_DAY,
            CalendarContract.Instances.RRULE,
            CalendarContract.Instances.RDATE,
            CalendarContract.Instances.ORIGINAL_ID,
            CalendarContract.Instances.ORIGINAL_INSTANCE_TIME
        )
        val rows = mutableListOf<CalendarInstanceRecord>()
        resolver.query(uri, projection, null, null, "${CalendarContract.Instances.BEGIN} ASC")?.use { cursor ->
            while (cursor.moveToNext() && rows.size < limit) {
                val instanceEventId = cursor.getLong(0)
                val originalId = cursor.getString(9)?.toLongOrNull()
                rows += CalendarInstanceRecord(
                    seriesEventId = originalId ?: instanceEventId,
                    instanceEventId = instanceEventId,
                    calendarId = cursor.getLong(1),
                    title = cursor.getString(2).orEmpty(),
                    beginMs = cursor.getLong(3),
                    endMs = cursor.getLong(4),
                    location = cursor.getString(5).orEmpty(),
                    allDay = cursor.getInt(6) == 1,
                    recurring = !cursor.getString(7).isNullOrBlank() || !cursor.getString(8).isNullOrBlank() || originalId != null,
                    originalInstanceStartMs = cursor.getLongOrNull(10)
                )
            }
        }
        return rows
    }

    override fun findOccurrence(seriesEventId: Long, instanceStartMs: Long): CalendarInstanceRecord? {
        val exceptionId = findExceptionId(seriesEventId, instanceStartMs)
        if (exceptionId != null) {
            val exception = queryEvent(exceptionId) ?: return null
            if (exception.status == CalendarDomainValues.cancelledEventStatus) return null
            val end = exception.resolvedEndMs ?: return null
            return CalendarInstanceRecord(
                seriesEventId = seriesEventId,
                instanceEventId = exception.id,
                calendarId = exception.calendarId,
                title = exception.title,
                beginMs = exception.startMs,
                endMs = end,
                location = exception.location,
                allDay = exception.allDay,
                recurring = true,
                originalInstanceStartMs = instanceStartMs
            )
        }
        return listInstances(instanceStartMs, instanceStartMs + 1L, 100).firstOrNull {
            it.seriesEventId == seriesEventId && it.beginMs == instanceStartMs
        }
    }

    override fun getEvent(eventId: Long): CalendarEventAggregate? {
        val event = queryEvent(eventId) ?: return null
        return CalendarEventAggregate(
            event = event,
            reminders = queryReminders(eventId),
            attendees = queryAttendees(eventId)
        )
    }

    override fun mutate(mutation: CalendarMutation): CalendarMutationResult {
        return when (mutation) {
            is CalendarMutation.Create -> create(mutation)
            is CalendarMutation.UpdateSeries -> updateSeries(mutation)
            is CalendarMutation.UpsertException -> upsertException(mutation)
            is CalendarMutation.DeleteSeries -> {
                val deleted = resolver.delete(eventUri(mutation.eventId), null, null)
                CalendarMutationResult(mutation.eventId, deleted)
            }
            is CalendarMutation.Respond -> {
                val updated = resolver.update(
                    ContentUris.withAppendedId(CalendarContract.Attendees.CONTENT_URI, mutation.attendeeId),
                    ContentValues().apply {
                        put(CalendarContract.Attendees.ATTENDEE_STATUS, mutation.status)
                    },
                    null,
                    null
                )
                CalendarMutationResult(null, updated)
            }
        }
    }

    private fun create(command: CalendarMutation.Create): CalendarMutationResult {
        val operations = arrayListOf<ContentProviderOperation>()
        operations += ContentProviderOperation.newInsert(CalendarContract.Events.CONTENT_URI)
            .withValues(command.event.toContentValues())
            .build()
        command.reminders.forEach { reminder ->
            operations += ContentProviderOperation.newInsert(CalendarContract.Reminders.CONTENT_URI)
                .withValueBackReference(CalendarContract.Reminders.EVENT_ID, 0)
                .withValue(CalendarContract.Reminders.MINUTES, reminder.minutesBefore)
                .withValue(CalendarContract.Reminders.METHOD, reminder.method)
                .build()
        }
        command.attendees.forEach { attendee ->
            operations += attendeeInsertOperation(attendee, eventBackReference = 0)
        }
        val results = resolver.applyBatch(CalendarContract.AUTHORITY, operations)
        val eventId = results.firstOrNull()?.uri?.let(ContentUris::parseId)
        return CalendarMutationResult(eventId, results.size)
    }

    private fun updateSeries(command: CalendarMutation.UpdateSeries): CalendarMutationResult {
        val operations = arrayListOf<ContentProviderOperation>()
        operations += ContentProviderOperation.newUpdate(eventUri(command.eventId))
            .withValues(command.event.toContentValues())
            .build()
        command.reminders?.let { reminders ->
            operations += ContentProviderOperation.newDelete(CalendarContract.Reminders.CONTENT_URI)
                .withSelection("${CalendarContract.Reminders.EVENT_ID}=?", arrayOf(command.eventId.toString()))
                .build()
            reminders.forEach { reminder ->
                operations += ContentProviderOperation.newInsert(CalendarContract.Reminders.CONTENT_URI)
                    .withValue(CalendarContract.Reminders.EVENT_ID, command.eventId)
                    .withValue(CalendarContract.Reminders.MINUTES, reminder.minutesBefore)
                    .withValue(CalendarContract.Reminders.METHOD, reminder.method)
                    .build()
            }
        }
        command.attendees?.let { attendees ->
            val existing = queryAttendees(command.eventId)
            val plan = CalendarAttendeeReplacementPlanner.plan(existing, attendees, command.accountIdentity)
            plan.deleteIds.forEach { attendeeId ->
                    operations += ContentProviderOperation.newDelete(
                        ContentUris.withAppendedId(CalendarContract.Attendees.CONTENT_URI, attendeeId)
                    ).build()
            }
            plan.inserts.forEach { insert ->
                operations += attendeeInsertOperation(
                    attendee = insert.attendee,
                    eventId = command.eventId,
                    status = insert.status
                )
            }
        }
        val results = resolver.applyBatch(CalendarContract.AUTHORITY, operations)
        return CalendarMutationResult(command.eventId, results.sumOf { it.count ?: 1 })
    }

    private fun upsertException(command: CalendarMutation.UpsertException): CalendarMutationResult {
        val existingId = findExceptionId(command.seriesEventId, command.originalInstanceStartMs)
        val values = command.event.toContentValues().apply {
            put(CalendarContract.Events.ORIGINAL_ID, command.seriesEventId.toString())
            put(CalendarContract.Events.ORIGINAL_INSTANCE_TIME, command.originalInstanceStartMs)
        }
        return if (existingId != null) {
            val updated = resolver.update(eventUri(existingId), values, null, null)
            CalendarMutationResult(existingId, updated)
        } else {
            val uri = CalendarContract.Events.CONTENT_EXCEPTION_URI.buildUpon()
                .appendPath(command.seriesEventId.toString())
                .build()
            val inserted = resolver.insert(uri, values)
            CalendarMutationResult(inserted?.let(ContentUris::parseId), if (inserted == null) 0 else 1)
        }
    }

    private fun queryEvent(eventId: Long): CalendarEventRecord? {
        val projection = arrayOf(
            CalendarContract.Events._ID,
            CalendarContract.Events.CALENDAR_ID,
            CalendarContract.Events.TITLE,
            CalendarContract.Events.DTSTART,
            CalendarContract.Events.DTEND,
            CalendarContract.Events.DURATION,
            CalendarContract.Events.DESCRIPTION,
            CalendarContract.Events.EVENT_LOCATION,
            CalendarContract.Events.ALL_DAY,
            CalendarContract.Events.EVENT_TIMEZONE,
            CalendarContract.Events.EVENT_END_TIMEZONE,
            CalendarContract.Events.AVAILABILITY,
            CalendarContract.Events.ACCESS_LEVEL,
            CalendarContract.Events.GUESTS_CAN_MODIFY,
            CalendarContract.Events.GUESTS_CAN_INVITE_OTHERS,
            CalendarContract.Events.GUESTS_CAN_SEE_GUESTS,
            CalendarContract.Events.RRULE,
            CalendarContract.Events.RDATE,
            CalendarContract.Events.EXDATE,
            CalendarContract.Events.STATUS,
            CalendarContract.Events.ORGANIZER,
            CalendarContract.Events.SELF_ATTENDEE_STATUS,
            CalendarContract.Events.IS_ORGANIZER,
            CalendarContract.Events.CAN_INVITE_OTHERS,
            CalendarContract.Events.ORIGINAL_ID,
            CalendarContract.Events.ORIGINAL_INSTANCE_TIME
        )
        resolver.query(eventUri(eventId), projection, null, null, null)?.use { cursor ->
            if (!cursor.moveToFirst()) return null
            return CalendarEventRecord(
                id = cursor.getLong(0),
                calendarId = cursor.getLong(1),
                title = cursor.getString(2).orEmpty(),
                startMs = cursor.getLong(3),
                endMs = cursor.getLongOrNull(4),
                duration = cursor.getString(5),
                description = cursor.getString(6).orEmpty(),
                location = cursor.getString(7).orEmpty(),
                allDay = cursor.getInt(8) == 1,
                timezone = cursor.getString(9).orEmpty(),
                endTimezone = cursor.getString(10),
                availability = cursor.getInt(11),
                accessLevel = cursor.getInt(12),
                guestsCanModify = cursor.getInt(13) == 1,
                guestsCanInviteOthers = cursor.getInt(14) == 1,
                guestsCanSeeGuests = cursor.getInt(15) == 1,
                rrule = cursor.getString(16),
                rdate = cursor.getString(17),
                exdate = cursor.getString(18),
                status = cursor.getInt(19),
                organizer = cursor.getString(20).orEmpty(),
                selfAttendeeStatus = cursor.getInt(21),
                isOrganizer = cursor.getInt(22) == 1,
                canInviteOthers = cursor.getInt(23) == 1,
                originalId = cursor.getString(24)?.toLongOrNull(),
                originalInstanceStartMs = cursor.getLongOrNull(25)
            )
        }
        return null
    }

    private fun queryReminders(eventId: Long): List<CalendarReminderRecord> {
        val rows = mutableListOf<CalendarReminderRecord>()
        resolver.query(
            CalendarContract.Reminders.CONTENT_URI,
            arrayOf(
                CalendarContract.Reminders._ID,
                CalendarContract.Reminders.MINUTES,
                CalendarContract.Reminders.METHOD
            ),
            "${CalendarContract.Reminders.EVENT_ID}=?",
            arrayOf(eventId.toString()),
            "${CalendarContract.Reminders.MINUTES} ASC"
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                rows += CalendarReminderRecord(cursor.getLong(0), cursor.getInt(1), cursor.getInt(2))
            }
        }
        return rows
    }

    private fun queryAttendees(eventId: Long): List<CalendarAttendeeRecord> {
        val rows = mutableListOf<CalendarAttendeeRecord>()
        resolver.query(
            CalendarContract.Attendees.CONTENT_URI,
            arrayOf(
                CalendarContract.Attendees._ID,
                CalendarContract.Attendees.ATTENDEE_NAME,
                CalendarContract.Attendees.ATTENDEE_EMAIL,
                CalendarContract.Attendees.ATTENDEE_TYPE,
                CalendarContract.Attendees.ATTENDEE_RELATIONSHIP,
                CalendarContract.Attendees.ATTENDEE_STATUS
            ),
            "${CalendarContract.Attendees.EVENT_ID}=?",
            arrayOf(eventId.toString()),
            "${CalendarContract.Attendees.ATTENDEE_EMAIL} ASC"
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                rows += CalendarAttendeeRecord(
                    id = cursor.getLong(0),
                    name = cursor.getString(1).orEmpty(),
                    email = cursor.getString(2).orEmpty(),
                    type = cursor.getInt(3),
                    relationship = cursor.getInt(4),
                    status = cursor.getInt(5)
                )
            }
        }
        return rows
    }

    private fun attendeeInsertOperation(
        attendee: CalendarAttendeeDraft,
        eventId: Long? = null,
        eventBackReference: Int? = null,
        status: Int = CalendarContract.Attendees.ATTENDEE_STATUS_INVITED
    ): ContentProviderOperation {
        val builder = ContentProviderOperation.newInsert(CalendarContract.Attendees.CONTENT_URI)
        if (eventBackReference != null) {
            builder.withValueBackReference(CalendarContract.Attendees.EVENT_ID, eventBackReference)
        } else {
            builder.withValue(CalendarContract.Attendees.EVENT_ID, eventId)
        }
        return builder
            .withValue(CalendarContract.Attendees.ATTENDEE_NAME, attendee.name)
            .withValue(CalendarContract.Attendees.ATTENDEE_EMAIL, attendee.email)
            .withValue(CalendarContract.Attendees.ATTENDEE_TYPE, attendee.type)
            .withValue(CalendarContract.Attendees.ATTENDEE_RELATIONSHIP, CalendarContract.Attendees.RELATIONSHIP_ATTENDEE)
            .withValue(CalendarContract.Attendees.ATTENDEE_STATUS, status)
            .build()
    }

    private fun CalendarEventDraft.toContentValues(): ContentValues {
        return ContentValues().apply {
            put(CalendarContract.Events.CALENDAR_ID, calendarId)
            put(CalendarContract.Events.TITLE, title)
            put(CalendarContract.Events.DTSTART, startMs)
            if (endMs == null) putNull(CalendarContract.Events.DTEND) else put(CalendarContract.Events.DTEND, endMs)
            if (duration == null) putNull(CalendarContract.Events.DURATION) else put(CalendarContract.Events.DURATION, duration)
            put(CalendarContract.Events.DESCRIPTION, description)
            put(CalendarContract.Events.EVENT_LOCATION, location)
            put(CalendarContract.Events.ALL_DAY, if (allDay) 1 else 0)
            put(CalendarContract.Events.EVENT_TIMEZONE, timezone)
            if (endTimezone == null) putNull(CalendarContract.Events.EVENT_END_TIMEZONE) else put(CalendarContract.Events.EVENT_END_TIMEZONE, endTimezone)
            put(CalendarContract.Events.AVAILABILITY, availability)
            put(CalendarContract.Events.ACCESS_LEVEL, accessLevel)
            put(CalendarContract.Events.GUESTS_CAN_MODIFY, if (guestsCanModify) 1 else 0)
            put(CalendarContract.Events.GUESTS_CAN_INVITE_OTHERS, if (guestsCanInviteOthers) 1 else 0)
            put(CalendarContract.Events.GUESTS_CAN_SEE_GUESTS, if (guestsCanSeeGuests) 1 else 0)
            if (rrule == null) putNull(CalendarContract.Events.RRULE) else put(CalendarContract.Events.RRULE, rrule)
            if (rdate == null) putNull(CalendarContract.Events.RDATE) else put(CalendarContract.Events.RDATE, rdate)
            if (exdate == null) putNull(CalendarContract.Events.EXDATE) else put(CalendarContract.Events.EXDATE, exdate)
            status?.let { put(CalendarContract.Events.STATUS, it) }
        }
    }

    private fun findExceptionId(seriesEventId: Long, originalInstanceStartMs: Long): Long? {
        resolver.query(
            CalendarContract.Events.CONTENT_URI,
            arrayOf(CalendarContract.Events._ID),
            "${CalendarContract.Events.ORIGINAL_ID}=? AND ${CalendarContract.Events.ORIGINAL_INSTANCE_TIME}=?",
            arrayOf(seriesEventId.toString(), originalInstanceStartMs.toString()),
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) return cursor.getLong(0)
        }
        return null
    }

    private fun eventUri(eventId: Long): Uri {
        return ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId)
    }

    private fun parseIntSet(raw: String?): Set<Int> {
        return raw.orEmpty().split(',').mapNotNull { it.trim().toIntOrNull() }.toSet()
    }

    private fun android.database.Cursor.getLongOrNull(index: Int): Long? {
        return if (isNull(index)) null else getLong(index)
    }
}
