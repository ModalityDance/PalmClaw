package com.palmclaw.tools

import android.accounts.Account
import android.content.ContentProviderResult
import android.content.ContentUris
import android.provider.ContactsContract
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ContactsBatchOperationFactoryAndroidTest {
    @Test
    fun createBatchUsesRawContactBackReferenceForEveryDataRow() {
        val request = ContactCreateRequest(
            starred = true,
            data = listOf(
                ContactDataDraft(ContactDataKind.NAME, values = mapOf("display" to "Ada Lovelace")),
                ContactDataDraft(ContactDataKind.PHONE, values = mapOf("number" to "123", "type" to "mobile"))
            ),
            nameSource = "explicit"
        )

        val operations = ContactsBatchOperationFactory.create(request, Account("owner@example.com", "example"))

        assertEquals(3, operations.size)
        assertTrue(operations[0].isInsert)
        assertEquals(ContactsContract.RawContacts.CONTENT_URI, operations[0].uri)
        val insertedRawUri = ContentUris.withAppendedId(ContactsContract.RawContacts.CONTENT_URI, 77)
        val backReferences = arrayOf(ContentProviderResult(insertedRawUri))
        operations.drop(1).forEach { operation ->
            assertTrue(operation.isInsert)
            assertEquals(ContactsContract.Data.CONTENT_URI, operation.uri)
            val values = requireNotNull(operation.resolveValueBackReferences(backReferences, 1))
            assertEquals(77L, values.getAsLong(ContactsContract.Data.RAW_CONTACT_ID))
            assertTrue(values.getAsString(ContactsContract.Data.MIMETYPE).isNotBlank())
        }
    }

    @Test
    fun updateBatchAssertsVersionAndKeepsExistingDataId() {
        val raw = ContactRawRecord(10, 1, "owner@example.com", "example", true, version = 4)
        val phone = ContactDataRecord(21, 10, ContactDataKind.PHONE, true, true, false, mapOf("number" to "111", "type" to "mobile"))
        val contact = contact(listOf(raw), listOf(phone))
        val request = ContactUpdateRequest(
            add = listOf(ContactDataDraft(ContactDataKind.EMAIL, rawContactId = 10, values = mapOf("address" to "a@example.com"))),
            update = listOf(ContactDataDraft(ContactDataKind.PHONE, dataId = 21, values = mapOf("number" to "222")))
        )
        val plan = ContactsMutationPlanner.plan(contact, request)

        val batch = ContactsBatchOperationFactory.update(contact, request, plan)

        val assertion = batch.operations.first()
        assertTrue(assertion.isAssertQuery)
        assertEquals(ContentUris.withAppendedId(ContactsContract.RawContacts.CONTENT_URI, 10), assertion.uri)
        val assertedValues = requireNotNull(assertion.resolveValueBackReferences(emptyArray(), 0))
        assertEquals(4L, assertedValues.getAsLong(ContactsContract.RawContacts.VERSION))
        val dataUpdate = batch.operations.first { it.isUpdate && it.uri == ContentUris.withAppendedId(ContactsContract.Data.CONTENT_URI, 21) }
        val updatedValues = requireNotNull(dataUpdate.resolveValueBackReferences(emptyArray(), 0))
        assertEquals("222", updatedValues.getAsString(ContactsContract.CommonDataKinds.Phone.NUMBER))
        assertEquals(1, batch.insertedOperationIndexes.size)
        assertTrue(batch.operations[batch.insertedOperationIndexes.single()].isInsert)
    }

    @Test
    fun deleteBatchAssertsEveryVersionBeforeDeletingAllRawContacts() {
        val contact = contact(
            raws = listOf(
                ContactRawRecord(10, 1, "a", "type", true, version = 4),
                ContactRawRecord(11, 1, "b", "type", true, version = 7)
            )
        )

        val operations = ContactsBatchOperationFactory.delete(contact)

        assertEquals(4, operations.size)
        assertTrue(operations.take(2).all { it.isAssertQuery })
        assertTrue(operations.drop(2).all { it.isDelete })
        assertEquals(
            setOf(
                ContentUris.withAppendedId(ContactsContract.RawContacts.CONTENT_URI, 10),
                ContentUris.withAppendedId(ContactsContract.RawContacts.CONTENT_URI, 11)
            ),
            operations.drop(2).map { it.uri }.toSet()
        )
    }

    private fun contact(
        raws: List<ContactRawRecord>,
        data: List<ContactDataRecord> = emptyList()
    ) = ContactRecord(1, "lookup", "Test", false, raws, data, emptyList())
}
