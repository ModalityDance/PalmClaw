package com.palmclaw.tools

import android.content.Context
import android.provider.CalendarContract
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CalendarControlToolAndroidTest {

    @Test
    fun createRecurringEventPassesStructuredRelationsThroughGateway() = runBlocking {
        val gateway = FakeCalendarGateway()
        val tool = createTool(gateway)

        val result = tool.run(
            """{
              "action":"create_event",
              "title":"Weekly review",
              "start_ms":1782000000000,
              "end_ms":1782003600000,
              "recurrence":{"frequency":"weekly","by_weekdays":["monday"]},
              "reminders":[{"minutes_before":15,"method":"alert"}],
              "attendees":[{"email":"guest@example.com","type":"optional"}]
            }""".trimIndent()
        )

        assertFalse(result.isError)
        val mutation = gateway.mutations.single() as CalendarMutation.Create
        assertEquals("FREQ=WEEKLY;BYDAY=MO", mutation.event.rrule)
        assertEquals("PT3600S", mutation.event.duration)
        assertEquals(15, mutation.reminders.single().minutesBefore)
        assertEquals("guest@example.com", mutation.attendees.single().email)
    }

    @Test
    fun omittedRelationsArePreservedButEmptyArraysClearThem() = runBlocking {
        val gateway = FakeCalendarGateway()
        val tool = createTool(gateway)

        val preserve = tool.run("""{"action":"update_event","event_id":7,"title":"Renamed"}""")
        assertFalse(preserve.isError)
        val preserveMutation = gateway.mutations.last() as CalendarMutation.UpdateSeries
        assertEquals(null, preserveMutation.reminders)
        assertEquals(null, preserveMutation.attendees)
        assertEquals("owner@example.com", preserveMutation.accountIdentity)

        val clear = tool.run("""{"action":"update_event","event_id":7,"reminders":[],"attendees":[]}""")
        assertFalse(clear.isError)
        val clearMutation = gateway.mutations.last() as CalendarMutation.UpdateSeries
        assertTrue(clearMutation.reminders?.isEmpty() == true)
        assertTrue(clearMutation.attendees?.isEmpty() == true)
    }

    @Test
    fun occurrenceUpdateCreatesExceptionAndRejectsSeriesOnlyFields() = runBlocking {
        val gateway = FakeCalendarGateway()
        val tool = createTool(gateway)

        val updated = tool.run(
            """{"action":"update_event","event_id":7,"scope":"occurrence","instance_start_ms":1782000000000,"location":"Room B"}"""
        )
        assertFalse(updated.isError)
        val exception = gateway.mutations.last() as CalendarMutation.UpsertException
        assertEquals(1_782_000_000_000L, exception.originalInstanceStartMs)
        assertEquals("Room B", exception.event.location)

        val mutationCount = gateway.mutations.size
        val rejected = tool.run(
            """{"action":"update_event","event_id":7,"scope":"occurrence","instance_start_ms":1782000000000,"reminders":[]}"""
        )
        assertTrue(rejected.isError)
        assertEquals(mutationCount, gateway.mutations.size)

        val mislinkedGateway = FakeCalendarGateway(mislinkExceptions = true)
        val mislinked = createTool(mislinkedGateway).run(
            """{"action":"update_event","event_id":7,"scope":"occurrence","instance_start_ms":1782000000000,"location":"Room C"}"""
        )
        assertTrue(mislinked.isError)
    }

    @Test
    fun deletionRequiresConfirmationBeforeGatewayMutation() = runBlocking {
        val gateway = FakeCalendarGateway()
        val cancelledTool = createTool(gateway, confirmation = false)
        val cancelled = cancelledTool.run("""{"action":"delete_event","event_id":7}""")
        assertTrue(cancelled.isError)
        assertTrue(gateway.mutations.isEmpty())

        val confirmedTool = createTool(gateway, confirmation = true)
        val deleted = confirmedTool.run("""{"action":"delete_event","event_id":7}""")
        assertFalse(deleted.isError)
        assertTrue(gateway.mutations.last() is CalendarMutation.DeleteSeries)

        val occurrenceGateway = FakeCalendarGateway()
        val occurrenceTool = createTool(occurrenceGateway, confirmation = true)
        val occurrenceDeleted = occurrenceTool.run(
            """{"action":"delete_event","event_id":7,"scope":"occurrence","instance_start_ms":1782000000000}"""
        )
        assertFalse(occurrenceDeleted.isError)
        val cancellation = occurrenceGateway.mutations.last() as CalendarMutation.UpsertException
        assertEquals(CalendarContract.Events.STATUS_CANCELED, cancellation.event.status)
        val mutationCount = occurrenceGateway.mutations.size
        val restoreAttempt = occurrenceTool.run(
            """{"action":"update_event","event_id":7,"scope":"occurrence","instance_start_ms":1782000000000,"location":"Restored"}"""
        )
        assertTrue(restoreAttempt.isError)
        assertEquals(mutationCount, occurrenceGateway.mutations.size)
    }

    @Test
    fun rsvpUpdatesOnlyTheResolvedCurrentAccountAttendee() = runBlocking {
        val gateway = FakeCalendarGateway().apply { addCurrentAccountAttendee() }
        val tool = createTool(gateway)

        val result = tool.run("""{"action":"respond_to_event","event_id":7,"response":"accepted"}""")

        assertFalse(result.isError)
        val response = gateway.mutations.last() as CalendarMutation.Respond
        assertEquals(50L, response.attendeeId)
        assertEquals(CalendarContract.Attendees.ATTENDEE_STATUS_ACCEPTED, response.status)
    }

    private fun createTool(gateway: FakeCalendarGateway, confirmation: Boolean = true): CalendarControlTool {
        val context = ApplicationProvider.getApplicationContext<Context>()
        return CalendarControlTool(
            context = context,
            gateway = gateway,
            permissionRequester = { _, _, _, _, _ -> null },
            confirmationRequester = { _, _, _, _ -> confirmation },
            eventLauncher = { ToolResult("", "opened", false) }
        )
    }

    private class FakeCalendarGateway(
        private val mislinkExceptions: Boolean = false
    ) : CalendarProviderGateway {
        val mutations = mutableListOf<CalendarMutation>()
        private val calendar = CalendarInfo(
            id = 1,
            name = "Test",
            accountName = "owner@example.com",
            owner = "owner@example.com",
            timezone = "UTC",
            visible = true,
            accessLevel = CalendarContract.Calendars.CAL_ACCESS_OWNER,
            primary = true,
            maxReminders = 5,
            allowedReminderMethods = setOf(
                CalendarContract.Reminders.METHOD_DEFAULT,
                CalendarContract.Reminders.METHOD_ALERT
            ),
            allowedAttendeeTypes = setOf(
                CalendarContract.Attendees.TYPE_REQUIRED,
                CalendarContract.Attendees.TYPE_OPTIONAL,
                CalendarContract.Attendees.TYPE_RESOURCE
            ),
            allowedAvailability = setOf(
                CalendarContract.Events.AVAILABILITY_BUSY,
                CalendarContract.Events.AVAILABILITY_FREE,
                CalendarContract.Events.AVAILABILITY_TENTATIVE
            )
        )
        private var aggregate = aggregate(eventId = 7)
        private var exceptionAggregate: CalendarEventAggregate? = null
        private val deletedSeriesIds = mutableSetOf<Long>()

        fun addCurrentAccountAttendee() {
            aggregate = aggregate.copy(
                attendees = listOf(
                    CalendarAttendeeRecord(
                        id = 50,
                        name = "Self",
                        email = calendar.accountIdentity,
                        type = CalendarContract.Attendees.TYPE_REQUIRED,
                        relationship = CalendarContract.Attendees.RELATIONSHIP_ATTENDEE,
                        status = CalendarContract.Attendees.ATTENDEE_STATUS_INVITED
                    )
                )
            )
        }

        override fun listCalendars(): List<CalendarInfo> = listOf(calendar)

        override fun listInstances(fromMs: Long, toMs: Long, limit: Int): List<CalendarInstanceRecord> {
            val start = 1_782_000_000_000L
            if (start !in fromMs until toMs) return emptyList()
            return listOf(
                CalendarInstanceRecord(
                    seriesEventId = 7,
                    instanceEventId = 7,
                    calendarId = 1,
                    title = "Weekly review",
                    beginMs = start,
                    endMs = start + 3_600_000L,
                    location = "Room A",
                    allDay = false,
                    recurring = true,
                    originalInstanceStartMs = null
                )
            )
        }

        override fun findOccurrence(seriesEventId: Long, instanceStartMs: Long): CalendarInstanceRecord? {
            val exception = exceptionAggregate?.event
            if (exception?.originalId == seriesEventId && exception.originalInstanceStartMs == instanceStartMs &&
                exception.status == CalendarContract.Events.STATUS_CANCELED
            ) return null
            return listInstances(instanceStartMs, instanceStartMs + 1L, 100).firstOrNull {
                it.seriesEventId == seriesEventId
            }
        }

        override fun getEvent(eventId: Long): CalendarEventAggregate? {
            if (eventId in deletedSeriesIds) return null
            return aggregate.takeIf { eventId == it.event.id }
                ?: exceptionAggregate?.takeIf { eventId == it.event.id }
        }

        override fun mutate(mutation: CalendarMutation): CalendarMutationResult {
            mutations += mutation
            return when (mutation) {
                is CalendarMutation.Create -> {
                    aggregate = aggregate(42, mutation.event, mutation.reminders, mutation.attendees)
                    CalendarMutationResult(42, 1 + mutation.reminders.size + mutation.attendees.size)
                }
                is CalendarMutation.UpdateSeries -> {
                    aggregate = aggregate(
                        mutation.eventId,
                        mutation.event,
                        mutation.reminders ?: aggregate.reminders.map { CalendarReminderDraft(it.minutesBefore, it.method) },
                        mutation.attendees ?: aggregate.attendees.map { CalendarAttendeeDraft(it.name, it.email, it.type) }
                    )
                    CalendarMutationResult(mutation.eventId, 1)
                }
                is CalendarMutation.UpsertException -> {
                    exceptionAggregate = aggregate(99, mutation.event).let { created ->
                        created.copy(
                            event = created.event.copy(
                                originalId = if (mislinkExceptions) mutation.seriesEventId + 1 else mutation.seriesEventId,
                                originalInstanceStartMs = mutation.originalInstanceStartMs
                            )
                        )
                    }
                    CalendarMutationResult(99, 1)
                }
                is CalendarMutation.DeleteSeries -> {
                    deletedSeriesIds += mutation.eventId
                    CalendarMutationResult(mutation.eventId, 1)
                }
                is CalendarMutation.Respond -> {
                    aggregate = aggregate.copy(
                        attendees = aggregate.attendees.map {
                            if (it.id == mutation.attendeeId) it.copy(status = mutation.status) else it
                        }
                    )
                    CalendarMutationResult(null, 1)
                }
            }
        }

        private fun aggregate(
            eventId: Long,
            draft: CalendarEventDraft = CalendarEventDraft(
                calendarId = 1,
                title = "Weekly review",
                startMs = 1_782_000_000_000L,
                endMs = null,
                duration = "PT3600S",
                description = "",
                location = "Room A",
                allDay = false,
                timezone = "UTC",
                endTimezone = "UTC",
                availability = CalendarContract.Events.AVAILABILITY_BUSY,
                accessLevel = CalendarContract.Events.ACCESS_DEFAULT,
                guestsCanModify = false,
                guestsCanInviteOthers = true,
                guestsCanSeeGuests = true,
                rrule = "FREQ=WEEKLY;BYDAY=MO",
                rdate = null,
                exdate = null
            ),
            reminders: List<CalendarReminderDraft> = listOf(CalendarReminderDraft(10, CalendarContract.Reminders.METHOD_ALERT)),
            attendees: List<CalendarAttendeeDraft> = emptyList()
        ): CalendarEventAggregate {
            return CalendarEventAggregate(
                event = CalendarEventRecord(
                    id = eventId,
                    calendarId = draft.calendarId,
                    title = draft.title,
                    startMs = draft.startMs,
                    endMs = draft.endMs,
                    duration = draft.duration,
                    description = draft.description,
                    location = draft.location,
                    allDay = draft.allDay,
                    timezone = draft.timezone,
                    endTimezone = draft.endTimezone,
                    availability = draft.availability,
                    accessLevel = draft.accessLevel,
                    guestsCanModify = draft.guestsCanModify,
                    guestsCanInviteOthers = draft.guestsCanInviteOthers,
                    guestsCanSeeGuests = draft.guestsCanSeeGuests,
                    rrule = draft.rrule,
                    rdate = draft.rdate,
                    exdate = draft.exdate,
                    status = draft.status ?: CalendarContract.Events.STATUS_CONFIRMED,
                    organizer = "owner@example.com",
                    selfAttendeeStatus = CalendarContract.Attendees.ATTENDEE_STATUS_ACCEPTED,
                    isOrganizer = true,
                    canInviteOthers = true,
                    originalId = null,
                    originalInstanceStartMs = null
                ),
                reminders = reminders.mapIndexed { index, value ->
                    CalendarReminderRecord(index.toLong(), value.minutesBefore, value.method)
                },
                attendees = attendees.mapIndexed { index, value ->
                    CalendarAttendeeRecord(
                        id = index.toLong(),
                        name = value.name,
                        email = value.email,
                        type = value.type,
                        relationship = CalendarContract.Attendees.RELATIONSHIP_ATTENDEE,
                        status = CalendarContract.Attendees.ATTENDEE_STATUS_INVITED
                    )
                }
            )
        }
    }
}
