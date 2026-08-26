package com.palmclaw.tools

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ContactsControlToolAndroidTest {
    @Test
    fun createUsesStructuredFieldsAndDerivesRequiredName() = runBlocking {
        val gateway = FakeContactsGateway()
        val tool = createTool(gateway)

        val result = tool.run(
            """{
              "action":"create_contact",
              "contact":{
                "phones":[{"number":"+852 1234 5678","type":"mobile","is_super_primary":true}],
                "emails":[{"address":"test@example.com","type":"work"}],
                "organizations":[{"company":"PalmClaw","title":"Researcher"}],
                "events":[{"date":"--07-18","type":"birthday"}]
              }
            }""".trimIndent()
        )

        assertFalse(result.isError)
        val request = gateway.created.single()
        assertEquals("derived", request.nameSource)
        assertEquals("PalmClaw", request.data.first { it.kind == ContactDataKind.NAME }.values["display"])
        assertTrue(request.data.first { it.kind == ContactDataKind.PHONE }.isSuperPrimary == true)
        assertTrue(request.data.first { it.kind == ContactDataKind.PHONE }.isPrimary == true)
        assertEquals("system_default", result.metadata?.get("account_resolution_source")?.jsonPrimitive?.content)
    }

    @Test
    fun selectorsAndExactDataUpdatesPassThroughTheGateway() = runBlocking {
        val gateway = FakeContactsGateway()
        val tool = createTool(gateway)

        val loaded = tool.run("""{"action":"get_contact","lookup_key":"lookup-1"}""")
        assertFalse(loaded.isError)
        assertEquals("lookup-1", gateway.lastSelector?.lookupKey)

        val updated = tool.run(
            """{
              "action":"update_contact",
              "contact_id":1,
              "lookup_key":"lookup-1",
              "update":{"phones":[{"data_id":21,"number":"222"}]},
              "remove_data_ids":[22]
            }""".trimIndent()
        )
        assertFalse(updated.isError)
        val request = gateway.updated.single()
        assertEquals(21L, request.update.single().dataId)
        assertEquals(mapOf("number" to "222"), request.update.single().values)
        assertEquals(listOf(22L), request.removeDataIds)
        assertNull(request.update.single().rawContactId)
    }

    @Test
    fun invalidCustomTypeAndDateFailBeforeGatewayMutation() = runBlocking {
        val gateway = FakeContactsGateway()
        val tool = createTool(gateway)

        val missingLabel = tool.run(
            """{"action":"create_contact","contact":{"phones":[{"number":"123","type":"custom"}]}}"""
        )
        val invalidDate = tool.run(
            """{"action":"create_contact","contact":{"name":{"display":"Test"},"events":[{"date":"2026-02-29","type":"birthday"}]}}"""
        )

        assertTrue(missingLabel.isError)
        assertTrue(invalidDate.isError)
        assertTrue(gateway.created.isEmpty())
    }

    @Test
    fun deletionRequiresConfirmationAndNeverCallsGatewayWhenCancelled() = runBlocking {
        val gateway = FakeContactsGateway()

        val cancelled = createTool(gateway, confirmation = false).run(
            """{"action":"delete_contact","lookup_key":"lookup-1"}"""
        )
        assertTrue(cancelled.isError)
        assertEquals(0, gateway.deleteCount)

        val deleted = createTool(gateway, confirmation = true).run(
            """{"action":"delete_contact","lookup_key":"lookup-1"}"""
        )
        assertFalse(deleted.isError)
        assertEquals(1, gateway.deleteCount)
    }

    @Test
    fun readOnlyAggregateRejectsDeletionBeforeConfirmationOrMutation() = runBlocking {
        val gateway = FakeContactsGateway(readOnlyAggregate = true)
        var confirmations = 0
        val context = ApplicationProvider.getApplicationContext<Context>()
        val tool = ContactsControlTool(
            context = context,
            gateway = gateway,
            permissionRequester = { _, _, _, _, _ -> null },
            confirmationRequester = { _, _, _, _ -> confirmations += 1; true },
            contactLauncher = { ToolResult("", "opened", false) }
        )

        val result = tool.run("""{"action":"delete_contact","contact_id":1}""")

        assertTrue(result.isError)
        assertEquals("contact_not_writable", result.metadata?.get("error")?.jsonPrimitive?.content)
        assertEquals(0, confirmations)
        assertEquals(0, gateway.deleteCount)
    }

    private fun createTool(gateway: FakeContactsGateway, confirmation: Boolean = true): ContactsControlTool {
        val context = ApplicationProvider.getApplicationContext<Context>()
        return ContactsControlTool(
            context = context,
            gateway = gateway,
            permissionRequester = { _, _, _, _, _ -> null },
            confirmationRequester = { _, _, _, _ -> confirmation },
            contactLauncher = { ToolResult("", "opened", false) }
        )
    }

    private class FakeContactsGateway(
        readOnlyAggregate: Boolean = false
    ) : ContactsProviderGateway {
        val created = mutableListOf<ContactCreateRequest>()
        val updated = mutableListOf<ContactUpdateRequest>()
        var deleteCount = 0
        var lastSelector: ContactSelector? = null

        private val raw = ContactRawRecord(10, 1, "owner@example.com", "example", !readOnlyAggregate, 4)
        private val contact = ContactRecord(
            contactId = 1,
            lookupKey = "lookup-1",
            displayName = "Test User",
            starred = false,
            rawContacts = listOf(raw),
            data = listOf(
                ContactDataRecord(21, 10, ContactDataKind.PHONE, true, true, readOnlyAggregate, mapOf("number" to "111", "type" to "mobile")),
                ContactDataRecord(22, 10, ContactDataKind.EMAIL, false, false, readOnlyAggregate, mapOf("address" to "old@example.com", "type" to "work"))
            ),
            unsupportedDataTypes = listOf("vnd.example/custom")
        )

        override fun search(query: String, count: Int): List<ContactSummary> =
            listOf(ContactSummary(1, "lookup-1", "Test User", false))

        override fun getContact(selector: ContactSelector): ContactRecord? {
            lastSelector = selector
            if (selector.contactId != null && selector.contactId != 1L) return null
            if (selector.lookupKey != null && selector.lookupKey != "lookup-1") return null
            return contact
        }

        override fun createContact(request: ContactCreateRequest): ContactMutationResult {
            created += request
            return ContactMutationResult(
                contact = contact,
                changedDataIds = listOf(21, 22),
                removedDataIds = emptyList(),
                rawContactsChanged = listOf(10),
                accountResolutionSource = "system_default",
                nameSource = request.nameSource
            )
        }

        override fun updateContact(contact: ContactRecord, request: ContactUpdateRequest): ContactMutationResult {
            updated += request
            return ContactMutationResult(
                contact = contact,
                changedDataIds = request.update.mapNotNull { it.dataId },
                removedDataIds = request.removeDataIds,
                rawContactsChanged = listOf(10)
            )
        }

        override fun deleteContact(contact: ContactRecord): ContactDeleteResult {
            deleteCount += 1
            return ContactDeleteResult(contact.contactId, contact.lookupKey, contact.rawContacts.map { it.rawContactId })
        }
    }
}
