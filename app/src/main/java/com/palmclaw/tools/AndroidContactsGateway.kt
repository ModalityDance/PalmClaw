package com.palmclaw.tools

import android.accounts.Account
import android.content.ContentProviderOperation
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.OperationApplicationException
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.RemoteException
import android.provider.ContactsContract

internal class AndroidContactsProviderGateway(
    private val context: Context
) : ContactsProviderGateway {
    private val resolver get() = context.contentResolver

    override fun search(query: String, count: Int): List<ContactSummary> {
        val uri = if (query.isBlank()) {
            ContactsContract.Contacts.CONTENT_URI
        } else {
            Uri.withAppendedPath(ContactsContract.Contacts.CONTENT_FILTER_URI, Uri.encode(query))
        }
        val out = mutableListOf<ContactSummary>()
        resolver.query(
            uri,
            CONTACT_PROJECTION,
            null,
            null,
            "${ContactsContract.Contacts.DISPLAY_NAME_PRIMARY} COLLATE LOCALIZED ASC"
        )?.use { cursor ->
            while (cursor.moveToNext() && out.size < count) {
                out += cursor.toSummary()
            }
        }
        return out
    }

    override fun getContact(selector: ContactSelector): ContactRecord? {
        val byId = selector.contactId?.let(::findContactSummaryById)
        val byLookup = selector.lookupKey?.let(::findContactSummaryByLookupKey)
        if (selector.contactId != null && byId == null) return null
        if (selector.lookupKey != null && byLookup == null) return null
        if (byId != null && byLookup != null && byId.lookupKey != byLookup.lookupKey) {
            throw ContactsGatewayException(
                code = "contact_selector_mismatch",
                message = "contact_id and lookup_key resolve to different contacts.",
                nextStep = "Reload the contact and use one current selector."
            )
        }
        val summary = byId ?: byLookup ?: return null
        return loadContact(summary)
    }

    override fun createContact(request: ContactCreateRequest): ContactMutationResult {
        val accountResolution = resolveDefaultAccount()
        val operations = ContactsBatchOperationFactory.create(request, accountResolution.account)

        val results = try {
            resolver.applyBatch(ContactsContract.AUTHORITY, operations)
        } catch (error: Exception) {
            val code = if (accountResolution.account != null) "default_account_not_writable" else "provider_failed"
            val next = if (accountResolution.account != null) {
                "Use open_create_contact to let the system contacts app choose a writable account."
            } else {
                "Retry, or use open_create_contact."
            }
            throw ContactsGatewayException(code, "The contacts provider rejected contact creation.", next, error)
        }

        val rawContactId = results.firstOrNull()?.uri?.let(ContentUris::parseId)
            ?: throw verificationFailure("The provider did not return the created RawContact id.")
        val contact = loadContactByRawContactId(rawContactId)
            ?: throw verificationFailure("The created RawContact could not be resolved to an aggregate contact.")
        val createdData = contact.data.filter { it.rawContactId == rawContactId }
        if (!containsAllDrafts(createdData, request.data)) {
            throw verificationFailure("The created contact does not contain all requested Data rows.")
        }
        val actualRaw = contact.rawContacts.firstOrNull { it.rawContactId == rawContactId }
            ?: throw verificationFailure("The created RawContact is missing after reload.")
        if (accountResolution.account != null &&
            (actualRaw.accountName != accountResolution.account.name || actualRaw.accountType != accountResolution.account.type)
        ) {
            throw verificationFailure("The provider created the contact under a different account.")
        }
        return ContactMutationResult(
            contact = contact,
            changedDataIds = createdData.map { it.dataId }.sorted(),
            removedDataIds = emptyList(),
            rawContactsChanged = listOf(rawContactId),
            accountResolutionSource = accountResolution.source,
            nameSource = request.nameSource
        )
    }

    override fun updateContact(contact: ContactRecord, request: ContactUpdateRequest): ContactMutationResult {
        val plan = ContactsMutationPlanner.plan(contact, request)
        val batch = ContactsBatchOperationFactory.update(contact, request, plan)
        val operations = batch.operations

        val results = try {
            resolver.applyBatch(ContactsContract.AUTHORITY, operations)
        } catch (error: OperationApplicationException) {
            throw ContactsGatewayException(
                code = "contact_conflict",
                message = "The contact changed before the atomic update could be applied.",
                nextStep = "Reload the contact and retry.",
                cause = error
            )
        } catch (error: RemoteException) {
            throw ContactsGatewayException("provider_failed", "The contacts provider was unavailable.", "Retry the update.", error)
        } catch (error: RuntimeException) {
            throw ContactsGatewayException("provider_failed", "The contacts provider rejected the update.", "Reload the contact and retry.", error)
        }

        val addedIds = batch.insertedOperationIndexes.mapNotNull { index -> results.getOrNull(index)?.uri?.let(ContentUris::parseId) }
        if (addedIds.size != plan.adds.size) {
            throw verificationFailure("The provider did not return every inserted Data id.")
        }
        val resolveRawId = plan.rawContactsChanged.firstOrNull()
            ?: contact.rawContacts.firstOrNull()?.rawContactId
            ?: throw verificationFailure("The updated contact has no RawContact.")
        val reloaded = loadContactByRawContactId(resolveRawId)
            ?: throw verificationFailure("The updated contact could not be resolved after aggregation.")
        verifyUpdate(reloaded, request, plan, addedIds)

        val changedIds = buildSet {
            addAll(addedIds)
            plan.updates.mapTo(this) { it.existing.dataId }
            plan.primaryClears.mapTo(this) { it.dataId }
        }.sorted()
        return ContactMutationResult(
            contact = reloaded,
            changedDataIds = changedIds,
            removedDataIds = plan.removals.map { it.dataId }.sorted(),
            rawContactsChanged = plan.rawContactsChanged.sorted()
        )
    }

    override fun deleteContact(contact: ContactRecord): ContactDeleteResult {
        val readOnly = contact.rawContacts.filterNot { it.writable }
        if (readOnly.isNotEmpty()) {
            throw ContactsGatewayException(
                code = "contact_not_writable",
                message = "The aggregate contact contains read-only RawContacts: ${readOnly.joinToString { it.rawContactId.toString() }}.",
                nextStep = "Use open_contact to review or delete it in the owning contacts app."
            )
        }
        if (contact.rawContacts.isEmpty()) {
            throw ContactsGatewayException("not_found", "The contact has no RawContacts.", "Reload the contact and retry.")
        }
        val operations = ContactsBatchOperationFactory.delete(contact)
        try {
            resolver.applyBatch(ContactsContract.AUTHORITY, operations)
        } catch (error: OperationApplicationException) {
            throw ContactsGatewayException("contact_conflict", "The contact changed before deletion.", "Reload the contact and retry.", error)
        } catch (error: Exception) {
            throw ContactsGatewayException("provider_failed", "The contacts provider rejected deletion.", "Use open_contact and retry.", error)
        }
        val remainingRaw = contact.rawContacts.any { findContactIdByRawContactId(it.rawContactId) != null }
        val remainingAggregate = getContact(ContactSelector(lookupKey = contact.lookupKey))
        if (remainingRaw || remainingAggregate != null) {
            throw verificationFailure("The provider still reports part of the deleted aggregate contact.")
        }
        return ContactDeleteResult(
            contactId = contact.contactId,
            lookupKey = contact.lookupKey,
            rawContactsChanged = contact.rawContacts.map { it.rawContactId }.sorted()
        )
    }

    private fun findContactSummaryById(contactId: Long): ContactSummary? =
        querySingleContact(ContentUris.withAppendedId(ContactsContract.Contacts.CONTENT_URI, contactId))

    private fun findContactSummaryByLookupKey(lookupKey: String): ContactSummary? =
        querySingleContact(Uri.withAppendedPath(ContactsContract.Contacts.CONTENT_LOOKUP_URI, Uri.encode(lookupKey)))

    private fun querySingleContact(uri: Uri): ContactSummary? {
        resolver.query(uri, CONTACT_PROJECTION, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) return cursor.toSummary()
        }
        return null
    }

    private fun loadContact(summary: ContactSummary): ContactRecord {
        val rawContacts = loadRawContacts(summary.contactId)
        val rawIds = rawContacts.map { it.rawContactId }
        val dataResult = loadData(rawIds, rawContacts.associateBy { it.rawContactId })
        return ContactRecord(
            contactId = summary.contactId,
            lookupKey = summary.lookupKey,
            displayName = summary.displayName,
            starred = summary.starred,
            rawContacts = rawContacts,
            data = dataResult.supported,
            unsupportedDataTypes = dataResult.unsupported.sorted()
        )
    }

    private fun loadContactByRawContactId(rawContactId: Long): ContactRecord? {
        val contactId = findContactIdByRawContactId(rawContactId) ?: return null
        val summary = findContactSummaryById(contactId) ?: return null
        return loadContact(summary)
    }

    private fun findContactIdByRawContactId(rawContactId: Long): Long? {
        resolver.query(
            rawUri(rawContactId),
            arrayOf(ContactsContract.RawContacts.CONTACT_ID),
            null,
            null,
            null
        )?.use { cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0)) return cursor.getLong(0)
        }
        return null
    }

    private fun loadRawContacts(contactId: Long): List<ContactRawRecord> {
        val out = mutableListOf<ContactRawRecord>()
        resolver.query(
            ContactsContract.RawContacts.CONTENT_URI,
            RAW_CONTACT_PROJECTION,
            "${ContactsContract.RawContacts.CONTACT_ID}=? AND ${ContactsContract.RawContacts.DELETED}=0",
            arrayOf(contactId.toString()),
            "${ContactsContract.RawContacts._ID} ASC"
        )?.use { cursor ->
            val id = cursor.index(ContactsContract.RawContacts._ID)
            val contact = cursor.index(ContactsContract.RawContacts.CONTACT_ID)
            val accountName = cursor.index(ContactsContract.RawContacts.ACCOUNT_NAME)
            val accountType = cursor.index(ContactsContract.RawContacts.ACCOUNT_TYPE)
            val readOnly = cursor.index(ContactsContract.RawContacts.RAW_CONTACT_IS_READ_ONLY)
            val version = cursor.index(ContactsContract.RawContacts.VERSION)
            while (cursor.moveToNext()) {
                out += ContactRawRecord(
                    rawContactId = cursor.getLong(id),
                    contactId = cursor.getLong(contact),
                    accountName = cursor.stringOrNull(accountName),
                    accountType = cursor.stringOrNull(accountType),
                    writable = cursor.getInt(readOnly) == 0,
                    version = cursor.getLong(version)
                )
            }
        }
        return out
    }

    private fun loadData(rawContactIds: List<Long>, rawById: Map<Long, ContactRawRecord>): DataLoadResult {
        if (rawContactIds.isEmpty()) return DataLoadResult(emptyList(), emptySet())
        val placeholders = rawContactIds.joinToString(",") { "?" }
        val supported = mutableListOf<ContactDataRecord>()
        val unsupported = linkedSetOf<String>()
        resolver.query(
            ContactsContract.Data.CONTENT_URI,
            DATA_PROJECTION,
            "${ContactsContract.Data.RAW_CONTACT_ID} IN ($placeholders)",
            rawContactIds.map(Long::toString).toTypedArray(),
            "${ContactsContract.Data.RAW_CONTACT_ID} ASC, ${ContactsContract.Data._ID} ASC"
        )?.use { cursor ->
            val idIndex = cursor.index(ContactsContract.Data._ID)
            val rawIndex = cursor.index(ContactsContract.Data.RAW_CONTACT_ID)
            val mimeIndex = cursor.index(ContactsContract.Data.MIMETYPE)
            val primaryIndex = cursor.index(ContactsContract.Data.IS_PRIMARY)
            val superIndex = cursor.index(ContactsContract.Data.IS_SUPER_PRIMARY)
            val readOnlyIndex = cursor.index(ContactsContract.Data.IS_READ_ONLY)
            while (cursor.moveToNext()) {
                val mime = cursor.getString(mimeIndex).orEmpty()
                val kind = ContactsMimeCodec.kindForMime(mime)
                if (kind == null) {
                    if (mime.isNotBlank()) unsupported += mime
                    continue
                }
                val rawId = cursor.getLong(rawIndex)
                supported += ContactDataRecord(
                    dataId = cursor.getLong(idIndex),
                    rawContactId = rawId,
                    kind = kind,
                    isPrimary = cursor.getInt(primaryIndex) != 0,
                    isSuperPrimary = cursor.getInt(superIndex) != 0,
                    readOnly = cursor.getInt(readOnlyIndex) != 0 || rawById[rawId]?.writable != true,
                    values = ContactsMimeCodec.readValues(cursor, kind)
                )
            }
        }
        return DataLoadResult(supported, unsupported)
    }

    private fun verifyUpdate(
        reloaded: ContactRecord,
        request: ContactUpdateRequest,
        plan: ContactMutationPlan,
        addedIds: List<Long>
    ) {
        val postById = reloaded.data.associateBy { it.dataId }
        if (plan.removals.any { postById.containsKey(it.dataId) }) {
            throw verificationFailure("At least one removed Data row still exists.")
        }
        plan.updates.forEach { update ->
            val actual = postById[update.existing.dataId]
                ?: throw verificationFailure("Updated Data id=${update.existing.dataId} is missing.")
            if (!recordMatchesDraft(actual, update.draft)) {
                throw verificationFailure("Updated Data id=${actual.dataId} does not match the requested fields.")
            }
        }
        plan.adds.zip(addedIds).forEach { (draft, id) ->
            val actual = postById[id] ?: throw verificationFailure("Added Data id=$id is missing.")
            if (!recordMatchesDraft(actual, draft)) {
                throw verificationFailure("Added Data id=$id does not match the requested fields.")
            }
        }
        plan.primaryClears.forEach { clear ->
            val actual = postById[clear.dataId] ?: return@forEach
            if ((clear.clearPrimary && actual.isPrimary) || (clear.clearSuperPrimary && actual.isSuperPrimary)) {
                throw verificationFailure("Primary state was not cleared for Data id=${clear.dataId}.")
            }
        }
        request.starred?.let { if (reloaded.starred != it) throw verificationFailure("The starred state was not applied.") }
    }

    private fun containsAllDrafts(records: List<ContactDataRecord>, drafts: List<ContactDataDraft>): Boolean {
        val remaining = records.toMutableList()
        drafts.forEach { draft ->
            val index = remaining.indexOfFirst { recordMatchesDraft(it, draft) }
            if (index < 0) return false
            remaining.removeAt(index)
        }
        return true
    }

    private fun recordMatchesDraft(record: ContactDataRecord, draft: ContactDataDraft): Boolean {
        if (record.kind != draft.kind) return false
        if (draft.rawContactId != null && record.rawContactId != draft.rawContactId) return false
        if (draft.values.any { (key, value) -> record.values[key].orEmpty() != value }) return false
        if (draft.isPrimary != null && record.isPrimary != draft.isPrimary) return false
        if (draft.isSuperPrimary != null && record.isSuperPrimary != draft.isSuperPrimary) return false
        return true
    }

    private fun resolveDefaultAccount(): AccountResolution {
        return when {
            Build.VERSION.SDK_INT >= 33 -> {
                @Suppress("DEPRECATION")
                val account = ContactsContract.Settings.getDefaultAccount(resolver)
                    ?: throw ContactsGatewayException(
                        code = "default_account_unavailable",
                        message = "Android does not report a default contacts account.",
                        nextStep = "Use open_create_contact to choose an account in the system contacts app."
                    )
                AccountResolution(account, "system_default")
            }

            Build.VERSION.SDK_INT >= 30 -> {
                val name = ContactsContract.RawContacts.getLocalAccountName(context)
                val type = ContactsContract.RawContacts.getLocalAccountType(context)
                if (name.isNullOrBlank() || type.isNullOrBlank()) {
                    throw ContactsGatewayException(
                        code = "default_account_unavailable",
                        message = "Android does not report a writable local contacts account.",
                        nextStep = "Use open_create_contact to choose an account in the system contacts app."
                    )
                }
                val account = Account(name, type)
                AccountResolution(account, "system_local_account")
            }

            else -> AccountResolution(null, "provider_local_account")
        }
    }

    private fun verificationFailure(message: String) = ContactsGatewayException(
        code = "verification_failed",
        message = message,
        nextStep = "Reload the contact in the system contacts app before retrying."
    )

    private data class AccountResolution(val account: Account?, val source: String)
    private data class DataLoadResult(val supported: List<ContactDataRecord>, val unsupported: Set<String>)

    private fun rawUri(id: Long): Uri = ContentUris.withAppendedId(ContactsContract.RawContacts.CONTENT_URI, id)
    private fun dataUri(id: Long): Uri = ContentUris.withAppendedId(ContactsContract.Data.CONTENT_URI, id)

    private fun Cursor.toSummary(): ContactSummary = ContactSummary(
        contactId = getLong(index(ContactsContract.Contacts._ID)),
        lookupKey = getString(index(ContactsContract.Contacts.LOOKUP_KEY)).orEmpty(),
        displayName = getString(index(ContactsContract.Contacts.DISPLAY_NAME_PRIMARY)).orEmpty(),
        starred = getInt(index(ContactsContract.Contacts.STARRED)) != 0
    )

    private fun Cursor.index(column: String): Int = getColumnIndexOrThrow(column)
    private fun Cursor.stringOrNull(index: Int): String? = if (isNull(index)) null else getString(index)

    private companion object {
        val CONTACT_PROJECTION = arrayOf(
            ContactsContract.Contacts._ID,
            ContactsContract.Contacts.LOOKUP_KEY,
            ContactsContract.Contacts.DISPLAY_NAME_PRIMARY,
            ContactsContract.Contacts.STARRED
        )
        val RAW_CONTACT_PROJECTION = arrayOf(
            ContactsContract.RawContacts._ID,
            ContactsContract.RawContacts.CONTACT_ID,
            ContactsContract.RawContacts.ACCOUNT_NAME,
            ContactsContract.RawContacts.ACCOUNT_TYPE,
            ContactsContract.RawContacts.RAW_CONTACT_IS_READ_ONLY,
            ContactsContract.RawContacts.VERSION
        )
        val DATA_PROJECTION = arrayOf(
            ContactsContract.Data._ID,
            ContactsContract.Data.RAW_CONTACT_ID,
            ContactsContract.Data.MIMETYPE,
            ContactsContract.Data.IS_PRIMARY,
            ContactsContract.Data.IS_SUPER_PRIMARY,
            ContactsContract.Data.IS_READ_ONLY,
            ContactsContract.Data.DATA1,
            ContactsContract.Data.DATA2,
            ContactsContract.Data.DATA3,
            ContactsContract.Data.DATA4,
            ContactsContract.Data.DATA5,
            ContactsContract.Data.DATA6,
            ContactsContract.Data.DATA7,
            ContactsContract.Data.DATA8,
            ContactsContract.Data.DATA9,
            ContactsContract.Data.DATA10
        )
    }
}

internal object ContactsMimeCodec {
    private val kindToMime = mapOf(
        ContactDataKind.NAME to ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE,
        ContactDataKind.PHONE to ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE,
        ContactDataKind.EMAIL to ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE,
        ContactDataKind.ADDRESS to ContactsContract.CommonDataKinds.StructuredPostal.CONTENT_ITEM_TYPE,
        ContactDataKind.ORGANIZATION to ContactsContract.CommonDataKinds.Organization.CONTENT_ITEM_TYPE,
        ContactDataKind.WEBSITE to ContactsContract.CommonDataKinds.Website.CONTENT_ITEM_TYPE,
        ContactDataKind.EVENT to ContactsContract.CommonDataKinds.Event.CONTENT_ITEM_TYPE,
        ContactDataKind.RELATION to ContactsContract.CommonDataKinds.Relation.CONTENT_ITEM_TYPE,
        ContactDataKind.NICKNAME to ContactsContract.CommonDataKinds.Nickname.CONTENT_ITEM_TYPE,
        ContactDataKind.NOTE to ContactsContract.CommonDataKinds.Note.CONTENT_ITEM_TYPE
    )
    private val mimeToKind = kindToMime.entries.associate { it.value to it.key }

    fun mimeForKind(kind: ContactDataKind): String = kindToMime.getValue(kind)
    fun kindForMime(mime: String): ContactDataKind? = mimeToKind[mime]

    fun readValues(cursor: Cursor, kind: ContactDataKind): Map<String, String> {
        val columns = fieldColumns(kind)
        return buildMap {
            columns.forEach { (field, column) ->
                val index = cursor.getColumnIndex(column)
                if (index >= 0 && !cursor.isNull(index)) put(field, cursor.getString(index).orEmpty())
            }
            if (kind in TYPED_KINDS) {
                val typeIndex = cursor.getColumnIndex(ContactsContract.Data.DATA2)
                val typeValue = if (typeIndex >= 0 && !cursor.isNull(typeIndex)) cursor.getInt(typeIndex) else 0
                val labelIndex = cursor.getColumnIndex(ContactsContract.Data.DATA3)
                val label = if (labelIndex >= 0 && !cursor.isNull(labelIndex)) cursor.getString(labelIndex).orEmpty() else ""
                put("type", if (typeValue == 0 && label.isBlank()) "unknown:0" else ContactTypeCodec.decode(kind, typeValue))
                if (label.isNotEmpty()) put("label", label)
            }
        }
    }

    fun writeValues(draft: ContactDataDraft): ContentValues {
        val columns = fieldColumns(draft.kind)
        return ContentValues().apply {
            draft.values.forEach { (field, value) ->
                when (field) {
                    "type" -> put(ContactsContract.Data.DATA2, ContactTypeCodec.encode(draft.kind, value))
                    "label" -> put(ContactsContract.Data.DATA3, value)
                    else -> {
                        val column = columns[field] ?: throw ContactsGatewayException(
                            code = "invalid_arguments",
                            message = "Field '$field' is not valid for ${draft.kind.wireName}.",
                            nextStep = "Use only fields declared in the contacts tool schema."
                        )
                        put(column, value)
                    }
                }
            }
        }
    }

    fun valuesForDraft(draft: ContactDataDraft, includeMime: Boolean): ContentValues {
        val values = writeValues(draft)
        if (includeMime) values.put(ContactsContract.Data.MIMETYPE, mimeForKind(draft.kind))
        draft.isPrimary?.let { values.put(ContactsContract.Data.IS_PRIMARY, if (it) 1 else 0) }
        draft.isSuperPrimary?.let { values.put(ContactsContract.Data.IS_SUPER_PRIMARY, if (it) 1 else 0) }
        return values
    }

    private fun fieldColumns(kind: ContactDataKind): Map<String, String> = when (kind) {
        ContactDataKind.NAME -> mapOf(
            "display" to ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME,
            "given" to ContactsContract.CommonDataKinds.StructuredName.GIVEN_NAME,
            "family" to ContactsContract.CommonDataKinds.StructuredName.FAMILY_NAME,
            "prefix" to ContactsContract.CommonDataKinds.StructuredName.PREFIX,
            "middle" to ContactsContract.CommonDataKinds.StructuredName.MIDDLE_NAME,
            "suffix" to ContactsContract.CommonDataKinds.StructuredName.SUFFIX,
            "phonetic_given" to ContactsContract.CommonDataKinds.StructuredName.PHONETIC_GIVEN_NAME,
            "phonetic_middle" to ContactsContract.CommonDataKinds.StructuredName.PHONETIC_MIDDLE_NAME,
            "phonetic_family" to ContactsContract.CommonDataKinds.StructuredName.PHONETIC_FAMILY_NAME
        )
        ContactDataKind.PHONE -> mapOf("number" to ContactsContract.CommonDataKinds.Phone.NUMBER)
        ContactDataKind.EMAIL -> mapOf("address" to ContactsContract.CommonDataKinds.Email.ADDRESS)
        ContactDataKind.ADDRESS -> mapOf(
            "formatted" to ContactsContract.CommonDataKinds.StructuredPostal.FORMATTED_ADDRESS,
            "street" to ContactsContract.CommonDataKinds.StructuredPostal.STREET,
            "po_box" to ContactsContract.CommonDataKinds.StructuredPostal.POBOX,
            "neighborhood" to ContactsContract.CommonDataKinds.StructuredPostal.NEIGHBORHOOD,
            "city" to ContactsContract.CommonDataKinds.StructuredPostal.CITY,
            "region" to ContactsContract.CommonDataKinds.StructuredPostal.REGION,
            "postcode" to ContactsContract.CommonDataKinds.StructuredPostal.POSTCODE,
            "country" to ContactsContract.CommonDataKinds.StructuredPostal.COUNTRY
        )
        ContactDataKind.ORGANIZATION -> mapOf(
            "company" to ContactsContract.CommonDataKinds.Organization.COMPANY,
            "title" to ContactsContract.CommonDataKinds.Organization.TITLE,
            "department" to ContactsContract.CommonDataKinds.Organization.DEPARTMENT,
            "job_description" to ContactsContract.CommonDataKinds.Organization.JOB_DESCRIPTION,
            "office_location" to ContactsContract.CommonDataKinds.Organization.OFFICE_LOCATION
        )
        ContactDataKind.WEBSITE -> mapOf("url" to ContactsContract.CommonDataKinds.Website.URL)
        ContactDataKind.EVENT -> mapOf("date" to ContactsContract.CommonDataKinds.Event.START_DATE)
        ContactDataKind.RELATION -> mapOf("name" to ContactsContract.CommonDataKinds.Relation.NAME)
        ContactDataKind.NICKNAME -> mapOf("name" to ContactsContract.CommonDataKinds.Nickname.NAME)
        ContactDataKind.NOTE -> mapOf("note" to ContactsContract.CommonDataKinds.Note.NOTE)
    }

    private val TYPED_KINDS = setOf(
        ContactDataKind.PHONE,
        ContactDataKind.EMAIL,
        ContactDataKind.ADDRESS,
        ContactDataKind.ORGANIZATION,
        ContactDataKind.WEBSITE,
        ContactDataKind.EVENT,
        ContactDataKind.RELATION,
        ContactDataKind.NICKNAME
    )
}

internal object ContactsBatchOperationFactory {
    fun create(request: ContactCreateRequest, account: Account?): ArrayList<ContentProviderOperation> {
        val operations = arrayListOf<ContentProviderOperation>()
        val rawValues = ContentValues().apply {
            if (account != null) {
                put(ContactsContract.RawContacts.ACCOUNT_NAME, account.name)
                put(ContactsContract.RawContacts.ACCOUNT_TYPE, account.type)
            }
            put(ContactsContract.RawContacts.STARRED, if (request.starred) 1 else 0)
        }
        operations += ContentProviderOperation.newInsert(ContactsContract.RawContacts.CONTENT_URI)
            .withValues(rawValues)
            .build()
        request.data.forEach { draft ->
            operations += ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                .withValues(ContactsMimeCodec.valuesForDraft(draft, includeMime = true))
                .build()
        }
        return operations
    }

    fun update(
        contact: ContactRecord,
        request: ContactUpdateRequest,
        plan: ContactMutationPlan
    ): ContactsOperationBatch {
        val rawById = contact.rawContacts.associateBy { it.rawContactId }
        val operations = arrayListOf<ContentProviderOperation>()
        val insertedOperationIndexes = mutableListOf<Int>()
        plan.rawContactsChanged.sorted().forEach { rawId ->
            val raw = rawById[rawId] ?: throw ContactsGatewayException(
                "contact_conflict",
                "A RawContact changed while the update was being prepared.",
                "Reload the contact and retry."
            )
            operations += assertVersion(raw)
        }
        if (request.starred != null) {
            contact.rawContacts.filter { it.writable }.forEach { raw ->
                operations += ContentProviderOperation.newUpdate(rawUri(raw.rawContactId))
                    .withValue(ContactsContract.RawContacts.STARRED, if (request.starred) 1 else 0)
                    .withExpectedCount(1)
                    .build()
            }
        }
        plan.primaryClears.forEach { clear ->
            operations += ContentProviderOperation.newUpdate(dataUri(clear.dataId))
                .apply {
                    if (clear.clearPrimary) withValue(ContactsContract.Data.IS_PRIMARY, 0)
                    if (clear.clearSuperPrimary) withValue(ContactsContract.Data.IS_SUPER_PRIMARY, 0)
                }
                .withExpectedCount(1)
                .build()
        }
        plan.updates.forEach { update ->
            operations += ContentProviderOperation.newUpdate(dataUri(update.existing.dataId))
                .withValues(ContactsMimeCodec.valuesForDraft(update.draft, includeMime = false))
                .withExpectedCount(1)
                .build()
        }
        plan.removals.forEach { removal ->
            operations += ContentProviderOperation.newDelete(dataUri(removal.dataId))
                .withExpectedCount(1)
                .build()
        }
        plan.adds.forEach { draft ->
            insertedOperationIndexes += operations.size
            operations += ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                .withValue(ContactsContract.Data.RAW_CONTACT_ID, draft.rawContactId)
                .withValues(ContactsMimeCodec.valuesForDraft(draft, includeMime = true))
                .build()
        }
        return ContactsOperationBatch(operations, insertedOperationIndexes)
    }

    fun delete(contact: ContactRecord): ArrayList<ContentProviderOperation> = arrayListOf<ContentProviderOperation>().apply {
        contact.rawContacts.forEach { add(assertVersion(it)) }
        contact.rawContacts.forEach { raw ->
            add(
                ContentProviderOperation.newDelete(rawUri(raw.rawContactId))
                    .withExpectedCount(1)
                    .build()
            )
        }
    }

    private fun assertVersion(raw: ContactRawRecord): ContentProviderOperation =
        ContentProviderOperation.newAssertQuery(rawUri(raw.rawContactId))
            .withValue(ContactsContract.RawContacts.VERSION, raw.version)
            .withExpectedCount(1)
            .build()

    private fun rawUri(id: Long): Uri = ContentUris.withAppendedId(ContactsContract.RawContacts.CONTENT_URI, id)
    private fun dataUri(id: Long): Uri = ContentUris.withAppendedId(ContactsContract.Data.CONTENT_URI, id)
}

internal data class ContactsOperationBatch(
    val operations: ArrayList<ContentProviderOperation>,
    val insertedOperationIndexes: List<Int>
)
