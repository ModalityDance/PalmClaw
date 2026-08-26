package com.palmclaw.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ContactsMutationPlannerTest {
    @Test
    fun `single writable raw contact is selected for an add`() {
        val contact = contact(raws = listOf(raw(10, writable = true)))

        val plan = ContactsMutationPlanner.plan(
            contact,
            ContactUpdateRequest(add = listOf(draft(ContactDataKind.PHONE, "number" to "+85212345678")))
        )

        assertEquals(10L, plan.adds.single().rawContactId)
        assertEquals(setOf(10L), plan.rawContactsChanged)
    }

    @Test
    fun `multiple writable raw contacts require an explicit target`() {
        val contact = contact(raws = listOf(raw(10, true), raw(11, true)))

        val error = assertThrows(ContactsGatewayException::class.java) {
            ContactsMutationPlanner.plan(
                contact,
                ContactUpdateRequest(add = listOf(draft(ContactDataKind.EMAIL, "address" to "a@example.com")))
            )
        }

        assertEquals("raw_contact_required", error.code)
    }

    @Test
    fun `updates are exact and cannot move a data row`() {
        val existing = data(21, 10, ContactDataKind.PHONE, mapOf("number" to "111", "type" to "home"))
        val contact = contact(raws = listOf(raw(10, true), raw(11, true)), data = listOf(existing))

        val plan = ContactsMutationPlanner.plan(
            contact,
            ContactUpdateRequest(
                update = listOf(
                    ContactDataDraft(ContactDataKind.PHONE, dataId = 21, values = mapOf("number" to "222"))
                )
            )
        )
        assertEquals(mapOf("number" to "222"), plan.updates.single().draft.values)
        assertEquals(10L, plan.updates.single().draft.rawContactId)

        val error = assertThrows(ContactsGatewayException::class.java) {
            ContactsMutationPlanner.plan(
                contact,
                ContactUpdateRequest(
                    update = listOf(
                        ContactDataDraft(ContactDataKind.PHONE, dataId = 21, rawContactId = 11, values = mapOf("number" to "333"))
                    )
                )
            )
        }
        assertEquals("invalid_arguments", error.code)
    }

    @Test
    fun `kind mismatch and read only data fail before planning mutations`() {
        val writablePhone = data(21, 10, ContactDataKind.PHONE, mapOf("number" to "111"))
        val readOnlyEmail = data(22, 11, ContactDataKind.EMAIL, mapOf("address" to "a@example.com"), readOnly = true)
        val contact = contact(raws = listOf(raw(10, true), raw(11, false)), data = listOf(writablePhone, readOnlyEmail))

        val mismatch = assertThrows(ContactsGatewayException::class.java) {
            ContactsMutationPlanner.plan(
                contact,
                ContactUpdateRequest(update = listOf(ContactDataDraft(ContactDataKind.EMAIL, dataId = 21, values = mapOf("address" to "b@example.com"))))
            )
        }
        assertEquals("data_kind_mismatch", mismatch.code)

        val readOnly = assertThrows(ContactsGatewayException::class.java) {
            ContactsMutationPlanner.plan(contact, ContactUpdateRequest(removeDataIds = listOf(22)))
        }
        assertEquals("data_read_only", readOnly.code)
    }

    @Test
    fun `super primary implies primary and clears conflicts`() {
        val oldPrimary = data(21, 10, ContactDataKind.PHONE, mapOf("number" to "111"), primary = true, superPrimary = true)
        val other = data(22, 10, ContactDataKind.PHONE, mapOf("number" to "222"))
        val contact = contact(raws = listOf(raw(10, true)), data = listOf(oldPrimary, other))

        val plan = ContactsMutationPlanner.plan(
            contact,
            ContactUpdateRequest(
                update = listOf(ContactDataDraft(ContactDataKind.PHONE, dataId = 22, isSuperPrimary = true))
            )
        )

        assertTrue(plan.updates.single().draft.isPrimary == true)
        assertEquals(ContactPrimaryClear(21, clearPrimary = true, clearSuperPrimary = true), plan.primaryClears.single())
    }

    @Test
    fun `last primary request wins within the same batch and raw contact`() {
        val contact = contact(raws = listOf(raw(10, true)))

        val plan = ContactsMutationPlanner.plan(
            contact,
            ContactUpdateRequest(
                add = listOf(
                    ContactDataDraft(ContactDataKind.PHONE, values = mapOf("number" to "111"), isSuperPrimary = true),
                    ContactDataDraft(ContactDataKind.PHONE, values = mapOf("number" to "222"), isPrimary = true)
                )
            )
        )

        assertFalse(plan.adds[0].isPrimary == true)
        assertFalse(plan.adds[0].isSuperPrimary == true)
        assertTrue(plan.adds[1].isPrimary == true)
    }

    @Test
    fun `starred update rejects a mixed writable aggregate before mutation planning`() {
        val contact = contact(raws = listOf(raw(10, true), raw(11, false)))

        val error = assertThrows(ContactsGatewayException::class.java) {
            ContactsMutationPlanner.plan(contact, ContactUpdateRequest(starred = true))
        }

        assertEquals("contact_not_writable", error.code)
    }

    @Test
    fun `change limit is atomic and counts add update and remove`() {
        val contact = contact(raws = listOf(raw(10, true)))
        val accepted = ContactsMutationPlanner.plan(
            contact,
            ContactUpdateRequest(add = (1..50).map { draft(ContactDataKind.PHONE, "number" to it.toString()) })
        )
        assertEquals(50, accepted.adds.size)

        val request = ContactUpdateRequest(
            add = (1..51).map { draft(ContactDataKind.PHONE, "number" to it.toString()) }
        )

        val error = assertThrows(ContactsGatewayException::class.java) {
            ContactsMutationPlanner.plan(contact, request)
        }

        assertEquals("too_many_changes", error.code)
    }

    @Test
    fun `contact dates and provider types use stable wire forms`() {
        ContactDateCodec.requireValid("2026-07-18")
        ContactDateCodec.requireValid("--02-29")
        assertThrows(ContactsGatewayException::class.java) { ContactDateCodec.requireValid("2026-02-29") }
        assertThrows(ContactsGatewayException::class.java) { ContactDateCodec.requireValid("07-18") }

        assertEquals("mobile", ContactTypeCodec.decode(ContactDataKind.PHONE, 2))
        assertEquals("unknown:91", ContactTypeCodec.decode(ContactDataKind.PHONE, 91))
        assertEquals(0, ContactTypeCodec.encode(ContactDataKind.EMAIL, "custom"))
        assertThrows(ContactsGatewayException::class.java) { ContactTypeCodec.encode(ContactDataKind.EMAIL, "satellite") }
    }

    private fun contact(
        raws: List<ContactRawRecord>,
        data: List<ContactDataRecord> = emptyList()
    ) = ContactRecord(
        contactId = 1,
        lookupKey = "lookup",
        displayName = "Test",
        starred = false,
        rawContacts = raws,
        data = data,
        unsupportedDataTypes = listOf("vnd.example/custom")
    )

    private fun raw(id: Long, writable: Boolean) = ContactRawRecord(id, 1, "name", "type", writable, version = 3)

    private fun data(
        id: Long,
        rawId: Long,
        kind: ContactDataKind,
        values: Map<String, String>,
        readOnly: Boolean = false,
        primary: Boolean = false,
        superPrimary: Boolean = false
    ) = ContactDataRecord(id, rawId, kind, primary, superPrimary, readOnly, values)

    private fun draft(kind: ContactDataKind, vararg values: Pair<String, String>) =
        ContactDataDraft(kind = kind, values = mapOf(*values))
}
