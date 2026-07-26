package com.palmclaw.tools

import android.database.MatrixCursor
import android.provider.ContactsContract
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ContactsMimeCodecAndroidTest {
    @Test
    fun supportedFieldsMapToTheirAndroidColumns() {
        val cases = listOf(
            ContactDataDraft(ContactDataKind.NAME, values = mapOf("display" to "Ada Lovelace", "prefix" to "Countess", "given" to "Ada", "middle" to "Byron", "family" to "Lovelace", "suffix" to "I", "phonetic_given" to "A", "phonetic_middle" to "B", "phonetic_family" to "L")),
            ContactDataDraft(ContactDataKind.PHONE, values = mapOf("number" to "123", "type" to "custom", "label" to "Lab")),
            ContactDataDraft(ContactDataKind.EMAIL, values = mapOf("address" to "ada@example.com", "type" to "work")),
            ContactDataDraft(ContactDataKind.ADDRESS, values = mapOf("formatted" to "Full", "street" to "Street", "po_box" to "PO", "neighborhood" to "Area", "city" to "London", "region" to "London", "postcode" to "1", "country" to "UK", "type" to "home")),
            ContactDataDraft(ContactDataKind.ORGANIZATION, values = mapOf("company" to "Analytical Engine", "department" to "Research", "title" to "Programmer", "job_description" to "Computing", "office_location" to "Lab", "type" to "work")),
            ContactDataDraft(ContactDataKind.WEBSITE, values = mapOf("url" to "https://example.com", "type" to "profile")),
            ContactDataDraft(ContactDataKind.EVENT, values = mapOf("date" to "--12-10", "type" to "birthday")),
            ContactDataDraft(ContactDataKind.RELATION, values = mapOf("name" to "Charles", "type" to "friend")),
            ContactDataDraft(ContactDataKind.NICKNAME, values = mapOf("name" to "Enchantress", "type" to "other_name")),
            ContactDataDraft(ContactDataKind.NOTE, values = mapOf("note" to "First programmer"))
        )

        cases.forEach { draft ->
            val values = ContactsMimeCodec.valuesForDraft(draft, includeMime = true)
            assertEquals(ContactsMimeCodec.mimeForKind(draft.kind), values.getAsString(ContactsContract.Data.MIMETYPE))
            assertTrue(values.size() >= draft.values.size + 1)
        }
        val customPhone = ContactsMimeCodec.valuesForDraft(cases[1], includeMime = true)
        assertEquals(0, customPhone.getAsInteger(ContactsContract.CommonDataKinds.Phone.TYPE))
        assertEquals("Lab", customPhone.getAsString(ContactsContract.CommonDataKinds.Phone.LABEL))
    }

    @Test
    fun unknownProviderTypesUseStableUnknownWireValue() {
        val columns = arrayOf(
            ContactsContract.Data.DATA1,
            ContactsContract.Data.DATA2,
            ContactsContract.Data.DATA3
        )
        val cursor = MatrixCursor(columns).apply { addRow(arrayOf("123", 91, null)) }
        cursor.moveToFirst()

        val values = ContactsMimeCodec.readValues(cursor, ContactDataKind.PHONE)

        assertEquals("123", values["number"])
        assertEquals("unknown:91", values["type"])
        cursor.close()
    }
}
