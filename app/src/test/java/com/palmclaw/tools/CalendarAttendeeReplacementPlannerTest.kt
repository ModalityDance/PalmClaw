package com.palmclaw.tools

import android.provider.CalendarContract
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class CalendarAttendeeReplacementPlannerTest {

    @Test
    fun `replacement preserves organizer and current account while replacing guests`() {
        val existing = listOf(
            attendee(1, "organizer@example.com", CalendarContract.Attendees.RELATIONSHIP_ORGANIZER),
            attendee(2, "self@example.com", CalendarContract.Attendees.RELATIONSHIP_ATTENDEE),
            attendee(3, "old@example.com", CalendarContract.Attendees.RELATIONSHIP_ATTENDEE)
        )

        val plan = CalendarAttendeeReplacementPlanner.plan(
            existing = existing,
            requested = listOf(
                CalendarAttendeeDraft("Self", "self@example.com", CalendarContract.Attendees.TYPE_REQUIRED),
                CalendarAttendeeDraft("New", "new@example.com", CalendarContract.Attendees.TYPE_OPTIONAL)
            ),
            accountIdentity = "self@example.com"
        )

        assertEquals(listOf(3L), plan.deleteIds)
        assertEquals(listOf("new@example.com"), plan.inserts.map { it.attendee.email })
        assertFalse(plan.deleteIds.contains(1L))
        assertFalse(plan.deleteIds.contains(2L))
    }

    @Test
    fun `replacement retains response status for a matching guest`() {
        val accepted = attendee(
            id = 4,
            email = "returning@example.com",
            relationship = CalendarContract.Attendees.RELATIONSHIP_ATTENDEE,
            status = CalendarContract.Attendees.ATTENDEE_STATUS_ACCEPTED
        )

        val plan = CalendarAttendeeReplacementPlanner.plan(
            existing = listOf(accepted),
            requested = listOf(
                CalendarAttendeeDraft("Returning", "returning@example.com", CalendarContract.Attendees.TYPE_REQUIRED)
            ),
            accountIdentity = "self@example.com"
        )

        assertTrue(plan.deleteIds.contains(4L))
        assertEquals(CalendarContract.Attendees.ATTENDEE_STATUS_ACCEPTED, plan.inserts.single().status)
    }

    @Test
    fun `replacement refuses to run without current account identity`() {
        assertThrows(IllegalArgumentException::class.java) {
            CalendarAttendeeReplacementPlanner.plan(emptyList(), emptyList(), "")
        }
    }

    private fun attendee(
        id: Long,
        email: String,
        relationship: Int,
        status: Int = CalendarContract.Attendees.ATTENDEE_STATUS_INVITED
    ) = CalendarAttendeeRecord(
        id = id,
        name = "",
        email = email,
        type = CalendarContract.Attendees.TYPE_REQUIRED,
        relationship = relationship,
        status = status
    )
}
