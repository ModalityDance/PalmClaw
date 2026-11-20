package com.palmclaw.tools

import android.content.*
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Build
import android.provider.ContactsContract
import androidx.test.core.app.ApplicationProvider
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/** Only an isolated wrapped provider is mutated; never the device's contacts database. */
class ContactsProviderFeedbackAndroidTest {
    @Before
    fun requireWrappedResolver() { assumeTrue(Build.VERSION.SDK_INT >= 29) }

    @Test
    fun createRetainsRawIdWhenReadbackReturnsNull() {
        val provider = FixtureProvider()
        val failure = assertThrows(ContactsGatewayException::class.java) {
            gateway(provider).createContact(createRequest())
        }
        assertEquals(1, provider.batches)
        assertEquals(ExecutionState.COMMITTED, failure.feedback!!.executionState)
        assertEquals(VerificationState.UNAVAILABLE, failure.feedback.verification)
        assertEquals(77L, failure.feedback.receipt["raw_contact_id"]!!.jsonPrimitive.long)
    }

    @Test
    fun successfulBatchWithoutIdIsNotReportedAsNoWrite() {
        val provider = FixtureProvider().apply { omitIds = true }
        val failure = assertThrows(ContactsGatewayException::class.java) {
            gateway(provider).createContact(createRequest())
        }
        assertEquals(1, provider.batches)
        assertEquals(ExecutionState.COMMITTED, failure.feedback!!.executionState)
        assertTrue(failure.feedback.receipt.isEmpty())
    }

    @Test
    fun updateRetainsAddedDataIdsWhenReadbackFails() {
        val provider = FixtureProvider()
        val failure = assertThrows(ContactsGatewayException::class.java) {
            gateway(provider).updateContact(contact(), ContactUpdateRequest(
                add = listOf(ContactDataDraft(ContactDataKind.PHONE, values = mapOf("number" to "123", "type" to "mobile")))
            ))
        }
        assertEquals(1, provider.batches)
        assertEquals(ExecutionState.COMMITTED, failure.feedback!!.executionState)
        assertTrue(failure.feedback.receipt["added_data_ids"].toString().contains("101"))
    }

    @Test
    fun nullReadbackDoesNotVerifyDeletion() {
        val provider = FixtureProvider()
        val failure = assertThrows(ContactsGatewayException::class.java) {
            gateway(provider).deleteContact(contact())
        }
        assertEquals(1, provider.batches)
        assertEquals(VerificationState.UNAVAILABLE, failure.feedback!!.verification)
    }

    @Test
    fun nullQueriesAreNotEmptySearchOrMissingContact() {
        val gateway = gateway(FixtureProvider())
        assertEquals("provider_query_unavailable", assertThrows(ContactsGatewayException::class.java) {
            gateway.search("test", 10)
        }.code)
        assertEquals("provider_query_unavailable", assertThrows(ContactsGatewayException::class.java) {
            gateway.getContact(ContactSelector(contactId = 1))
        }.code)
    }

    @Test
    fun rawContactReceiptCanResolveTheCurrentAggregate() {
        val provider = FixtureProvider().apply { returnContact = true }
        val result = requireNotNull(gateway(provider).getContact(ContactSelector(rawContactId = 77)))
        assertEquals(1L, result.contactId)
        assertEquals(77L, result.rawContacts.single().rawContactId)
    }

    @Test
    fun missingWritableStateIsNeverAssumedWritable() {
        val provider = FixtureProvider().apply { returnContact = true; omitReadOnly = true }
        val failure = assertThrows(ContactsGatewayException::class.java) {
            gateway(provider).getContact(ContactSelector(contactId = 1))
        }
        assertEquals("provider_field_unavailable", failure.code)
        assertEquals(0, provider.batches)
    }

    private fun gateway(provider: FixtureProvider): AndroidContactsProviderGateway {
        val context = ApplicationProvider.getApplicationContext<Context>()
        provider.attachInfo(context, ProviderInfo().apply { authority = ContactsContract.AUTHORITY })
        return AndroidContactsProviderGateway(
            context, ContentResolver.wrap(provider),
            accountResolver = { AndroidContactsProviderGateway.AccountResolution(null, "provider_local_account") }
        )
    }

    private fun createRequest() = ContactCreateRequest(false,
        listOf(ContactDataDraft(ContactDataKind.NAME, values = mapOf("display" to "Fixture"))), "explicit")
    private fun contact() = ContactRecord(1, "lookup", "Fixture", false,
        listOf(ContactRawRecord(77, 1, null, null, true, 1)), emptyList(), emptyList())

    private class FixtureProvider : ContentProvider() {
        var batches = 0
        var omitIds = false
        var returnContact = false
        var omitReadOnly = false
        override fun onCreate() = true
        override fun applyBatch(operations: ArrayList<ContentProviderOperation>): Array<ContentProviderResult> {
            batches++
            return operations.mapIndexed { index, operation ->
                if (operation.isInsert && !omitIds) ContentProviderResult(ContentUris.withAppendedId(
                    operation.uri, if (operation.uri == ContactsContract.RawContacts.CONTENT_URI) 77L else 100L + index
                )) else ContentProviderResult(1)
            }.toTypedArray()
        }
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
                           selectionArgs: Array<out String>?, sortOrder: String?): Cursor? {
            if (!returnContact) return null
            val columns = requireNotNull(projection)
            return MatrixCursor(columns).apply {
                if (uri.pathSegments.firstOrNull() == "data") return@apply
                val raw = uri.pathSegments.firstOrNull() == "raw_contacts"
                addRow(columns.map { column -> when (column) {
                    ContactsContract.RawContacts.CONTACT_ID -> 1L
                    ContactsContract.Contacts._ID -> if (raw) 77L else 1L
                    ContactsContract.Contacts.LOOKUP_KEY -> "lookup"
                    ContactsContract.Contacts.DISPLAY_NAME_PRIMARY -> "Fixture"
                    ContactsContract.RawContacts.RAW_CONTACT_IS_READ_ONLY -> if (omitReadOnly) null else 0
                    ContactsContract.RawContacts.VERSION -> 1L
                    ContactsContract.RawContacts.ACCOUNT_NAME, ContactsContract.RawContacts.ACCOUNT_TYPE -> null
                    else -> 0
                } })
            }
        }
        override fun getType(uri: Uri): String? = null
        override fun insert(uri: Uri, values: ContentValues?): Uri? = error("Use batch")
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = error("Use batch")
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = error("Use batch")
    }
}
