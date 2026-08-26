package com.palmclaw.tools

import java.time.DateTimeException
import java.time.LocalDate
import java.util.Locale

internal enum class ContactDataKind(
    val wireName: String,
    val groupName: String
) {
    NAME("name", "names"),
    PHONE("phone", "phones"),
    EMAIL("email", "emails"),
    ADDRESS("address", "addresses"),
    ORGANIZATION("organization", "organizations"),
    WEBSITE("website", "websites"),
    EVENT("event", "events"),
    RELATION("relation", "relations"),
    NICKNAME("nickname", "nicknames"),
    NOTE("note", "notes");

    companion object {
        fun fromGroupName(value: String): ContactDataKind? = entries.firstOrNull { it.groupName == value }
    }
}

internal data class ContactSelector(
    val contactId: Long? = null,
    val lookupKey: String? = null
) {
    init {
        require(contactId != null || !lookupKey.isNullOrBlank())
    }
}

internal data class ContactRawRecord(
    val rawContactId: Long,
    val contactId: Long,
    val accountName: String?,
    val accountType: String?,
    val writable: Boolean,
    val version: Long
)

internal data class ContactDataRecord(
    val dataId: Long,
    val rawContactId: Long,
    val kind: ContactDataKind,
    val isPrimary: Boolean,
    val isSuperPrimary: Boolean,
    val readOnly: Boolean,
    val values: Map<String, String>
)

internal data class ContactRecord(
    val contactId: Long,
    val lookupKey: String,
    val displayName: String,
    val starred: Boolean,
    val rawContacts: List<ContactRawRecord>,
    val data: List<ContactDataRecord>,
    val unsupportedDataTypes: List<String>
)

internal data class ContactSummary(
    val contactId: Long,
    val lookupKey: String,
    val displayName: String,
    val starred: Boolean
)

internal data class ContactDataDraft(
    val kind: ContactDataKind,
    val dataId: Long? = null,
    val rawContactId: Long? = null,
    val values: Map<String, String> = emptyMap(),
    val isPrimary: Boolean? = null,
    val isSuperPrimary: Boolean? = null
)

internal data class ContactCreateRequest(
    val starred: Boolean,
    val data: List<ContactDataDraft>,
    val nameSource: String
)

internal data class ContactUpdateRequest(
    val starred: Boolean? = null,
    val add: List<ContactDataDraft> = emptyList(),
    val update: List<ContactDataDraft> = emptyList(),
    val removeDataIds: List<Long> = emptyList()
) {
    val changeCount: Int get() = add.size + update.size + removeDataIds.size
}

internal data class ContactMutationResult(
    val contact: ContactRecord,
    val changedDataIds: List<Long>,
    val removedDataIds: List<Long>,
    val rawContactsChanged: List<Long>,
    val accountResolutionSource: String? = null,
    val nameSource: String? = null
)

internal data class ContactDeleteResult(
    val contactId: Long,
    val lookupKey: String,
    val rawContactsChanged: List<Long>
)

internal class ContactsGatewayException(
    val code: String,
    override val message: String,
    val nextStep: String? = null,
    cause: Throwable? = null
) : Exception(message, cause)

internal interface ContactsProviderGateway {
    fun search(query: String, count: Int): List<ContactSummary>
    fun getContact(selector: ContactSelector): ContactRecord?
    fun createContact(request: ContactCreateRequest): ContactMutationResult
    fun updateContact(contact: ContactRecord, request: ContactUpdateRequest): ContactMutationResult
    fun deleteContact(contact: ContactRecord): ContactDeleteResult
}

internal data class ContactPlannedUpdate(
    val existing: ContactDataRecord,
    val draft: ContactDataDraft
)

internal data class ContactPrimaryClear(
    val dataId: Long,
    val clearPrimary: Boolean,
    val clearSuperPrimary: Boolean
)

internal data class ContactMutationPlan(
    val adds: List<ContactDataDraft>,
    val updates: List<ContactPlannedUpdate>,
    val removals: List<ContactDataRecord>,
    val primaryClears: List<ContactPrimaryClear>,
    val rawContactsChanged: Set<Long>
)

internal object ContactsMutationPlanner {
    fun plan(contact: ContactRecord, request: ContactUpdateRequest): ContactMutationPlan {
        if (request.changeCount > 50) {
            throw ContactsGatewayException(
                code = "too_many_changes",
                message = "A contact update can contain at most 50 add, update, and remove changes.",
                nextStep = "Split the update into smaller batches."
            )
        }
        if (request.changeCount == 0 && request.starred == null) {
            throw ContactsGatewayException(
                code = "invalid_arguments",
                message = "No contact changes were provided.",
                nextStep = "Provide add, update, remove_data_ids, or starred."
            )
        }

        val rawById = contact.rawContacts.associateBy { it.rawContactId }
        val writableRaw = contact.rawContacts.filter { it.writable }
        val dataById = contact.data.associateBy { it.dataId }
        val claimedIds = mutableSetOf<Long>()
        if (request.starred != null && contact.rawContacts.any { !it.writable }) {
            throw ContactsGatewayException(
                code = "contact_not_writable",
                message = "Aggregate starred state cannot be changed while the contact contains read-only RawContacts.",
                nextStep = "Use open_contact to change starred state in the owning contacts app."
            )
        }

        val adds = request.add.map { draft ->
            val target = when (val requestedRawId = draft.rawContactId) {
                null -> {
                    if (writableRaw.size != 1) {
                        throw ContactsGatewayException(
                            code = "raw_contact_required",
                            message = "raw_contact_id is required when the contact does not have exactly one writable RawContact.",
                            nextStep = "Use get_contact and choose a writable raw_contact_id."
                        )
                    }
                    writableRaw.single()
                }

                else -> rawById[requestedRawId]
                    ?: throw ContactsGatewayException(
                        code = "raw_contact_required",
                        message = "RawContact id=$requestedRawId does not belong to the selected contact.",
                        nextStep = "Use a raw_contact_id returned by get_contact."
                    )
            }
            if (!target.writable) {
                throw ContactsGatewayException(
                    code = "contact_not_writable",
                    message = "RawContact id=${target.rawContactId} is read-only.",
                    nextStep = "Choose a writable RawContact or use open_contact."
                )
            }
            validateDraft(draft, isUpdate = false)
            draft.copy(
                rawContactId = target.rawContactId,
                isPrimary = normalizedPrimary(draft),
                isSuperPrimary = draft.isSuperPrimary
            )
        }

        val updates = request.update.map { draft ->
            val dataId = draft.dataId ?: throw ContactsGatewayException(
                code = "invalid_arguments",
                message = "Every update item requires data_id.",
                nextStep = "Use get_contact to obtain the stable data_id."
            )
            if (!claimedIds.add(dataId)) {
                throw ContactsGatewayException(
                    code = "invalid_arguments",
                    message = "data_id=$dataId appears more than once in the update.",
                    nextStep = "Apply at most one update or removal to each data_id."
                )
            }
            val existing = dataById[dataId] ?: throw ContactsGatewayException(
                code = "data_not_found",
                message = "Data id=$dataId does not belong to the selected contact.",
                nextStep = "Reload the contact and retry with a current data_id."
            )
            if (existing.kind != draft.kind) {
                throw ContactsGatewayException(
                    code = "data_kind_mismatch",
                    message = "Data id=$dataId is ${existing.kind.wireName}, not ${draft.kind.wireName}.",
                    nextStep = "Place the item under the matching update group."
                )
            }
            if (draft.rawContactId != null && draft.rawContactId != existing.rawContactId) {
                throw ContactsGatewayException(
                    code = "invalid_arguments",
                    message = "An update cannot move a Data row to another RawContact.",
                    nextStep = "Remove the old Data row and add a new row to the target RawContact."
                )
            }
            if (existing.readOnly || rawById[existing.rawContactId]?.writable != true) {
                throw ContactsGatewayException(
                    code = "data_read_only",
                    message = "Data id=$dataId is read-only.",
                    nextStep = "Use open_contact to edit it in the owning contacts app."
                )
            }
            validateDraft(draft, isUpdate = true)
            ContactPlannedUpdate(
                existing = existing,
                draft = draft.copy(
                    rawContactId = existing.rawContactId,
                    isPrimary = normalizedPrimary(draft),
                    isSuperPrimary = draft.isSuperPrimary
                )
            )
        }

        val removals = request.removeDataIds.map { dataId ->
            if (!claimedIds.add(dataId)) {
                throw ContactsGatewayException(
                    code = "invalid_arguments",
                    message = "data_id=$dataId appears more than once in the update.",
                    nextStep = "Apply at most one update or removal to each data_id."
                )
            }
            val existing = dataById[dataId] ?: throw ContactsGatewayException(
                code = "data_not_found",
                message = "Data id=$dataId does not belong to the selected contact.",
                nextStep = "Reload the contact and retry with a current data_id."
            )
            if (existing.readOnly || rawById[existing.rawContactId]?.writable != true) {
                throw ContactsGatewayException(
                    code = "data_read_only",
                    message = "Data id=$dataId is read-only.",
                    nextStep = "Use open_contact to edit it in the owning contacts app."
                )
            }
            existing
        }

        val normalized = normalizeBatchPrimary(adds, updates)
        val normalizedAdds = normalized.first
        val normalizedUpdates = normalized.second
        val primaryClears = planPrimaryClears(contact, normalizedAdds, normalizedUpdates, removals.map { it.dataId }.toSet())
        val rawContactsChanged = buildSet {
            normalizedAdds.mapNotNullTo(this) { it.rawContactId }
            normalizedUpdates.mapTo(this) { it.existing.rawContactId }
            removals.mapTo(this) { it.rawContactId }
            primaryClears.mapNotNullTo(this) { dataById[it.dataId]?.rawContactId }
            if (request.starred != null) {
                writableRaw.mapTo(this) { it.rawContactId }
            }
        }
        if (request.starred != null && writableRaw.isEmpty()) {
            throw ContactsGatewayException(
                code = "contact_not_writable",
                message = "The selected contact has no writable RawContact.",
                nextStep = "Use open_contact to edit it in the owning contacts app."
            )
        }

        return ContactMutationPlan(normalizedAdds, normalizedUpdates, removals, primaryClears, rawContactsChanged)
    }

    private fun normalizedPrimary(draft: ContactDataDraft): Boolean? =
        if (draft.isSuperPrimary == true) true else draft.isPrimary

    private fun normalizeBatchPrimary(
        adds: List<ContactDataDraft>,
        updates: List<ContactPlannedUpdate>
    ): Pair<List<ContactDataDraft>, List<ContactPlannedUpdate>> {
        val refs = buildList {
            adds.forEachIndexed { index, draft -> add(BatchPrimaryRef(index, draft)) }
            updates.forEachIndexed { index, update -> add(BatchPrimaryRef(adds.size + index, update.draft)) }
        }
        val primaryWinners = refs.filter { normalizedPrimary(it.draft) == true }
            .associateBy { (it.draft.rawContactId!! to it.draft.kind) }
        val superWinners = refs.filter { it.draft.isSuperPrimary == true }
            .associateBy { it.draft.kind }

        fun normalized(ref: BatchPrimaryRef): ContactDataDraft {
            val draft = ref.draft
            val requestedPrimary = normalizedPrimary(draft) == true
            val keepPrimary = !requestedPrimary || primaryWinners[draft.rawContactId!! to draft.kind]?.position == ref.position
            val keepSuper = draft.isSuperPrimary != true || (
                superWinners[draft.kind]?.position == ref.position && keepPrimary
                )
            return when {
                requestedPrimary && !keepPrimary -> draft.copy(isPrimary = false, isSuperPrimary = false)
                draft.isSuperPrimary == true && !keepSuper -> draft.copy(isSuperPrimary = false)
                draft.isSuperPrimary == true -> draft.copy(isPrimary = true)
                else -> draft
            }
        }

        val byPosition = refs.associate { it.position to normalized(it) }
        val normalizedAdds = adds.indices.map { byPosition.getValue(it) }
        val normalizedUpdates = updates.indices.map { index ->
            updates[index].copy(draft = byPosition.getValue(adds.size + index))
        }
        return normalizedAdds to normalizedUpdates
    }

    private data class BatchPrimaryRef(
        val position: Int,
        val draft: ContactDataDraft
    )

    private fun planPrimaryClears(
        contact: ContactRecord,
        adds: List<ContactDataDraft>,
        updates: List<ContactPlannedUpdate>,
        removedIds: Set<Long>
    ): List<ContactPrimaryClear> {
        val clears = linkedMapOf<Long, ContactPrimaryClear>()
        val requests = buildList {
            adds.forEach { add(PrimaryRequest(null, it.rawContactId!!, it.kind, it.isPrimary, it.isSuperPrimary)) }
            updates.forEach {
                add(
                    PrimaryRequest(
                        it.existing.dataId,
                        it.existing.rawContactId,
                        it.existing.kind,
                        it.draft.isPrimary,
                        it.draft.isSuperPrimary
                    )
                )
            }
        }
        requests.forEach { request ->
            if (request.primary != true && request.superPrimary != true) return@forEach
            contact.data.asSequence()
                .filter { it.dataId !in removedIds }
                .filter { it.dataId != request.dataId && it.kind == request.kind }
                .forEach { candidate ->
                    val clearSuper = request.superPrimary == true && candidate.isSuperPrimary
                    val clearPrimary = (
                        request.primary == true &&
                            candidate.rawContactId == request.rawContactId &&
                            candidate.isPrimary
                        ) || clearSuper
                    if (clearPrimary || clearSuper) {
                        if (candidate.readOnly) {
                            throw ContactsGatewayException(
                                code = "data_read_only",
                                message = "Data id=${candidate.dataId} has conflicting primary state but is read-only.",
                                nextStep = "Use open_contact to change primary state in the owning contacts app."
                            )
                        }
                        val previous = clears[candidate.dataId]
                        clears[candidate.dataId] = ContactPrimaryClear(
                            dataId = candidate.dataId,
                            clearPrimary = clearPrimary || previous?.clearPrimary == true,
                            clearSuperPrimary = clearSuper || previous?.clearSuperPrimary == true
                        )
                    }
                }
        }
        return clears.values.toList()
    }

    private data class PrimaryRequest(
        val dataId: Long?,
        val rawContactId: Long,
        val kind: ContactDataKind,
        val primary: Boolean?,
        val superPrimary: Boolean?
    )

    private fun validateDraft(draft: ContactDataDraft, isUpdate: Boolean) {
        if (draft.kind == ContactDataKind.EVENT) {
            draft.values["date"]?.let(ContactDateCodec::requireValid)
        }
        draft.values["type"]?.let { type ->
            if (type == "custom" && draft.values["label"].isNullOrBlank()) {
                throw ContactsGatewayException(
                    code = "invalid_arguments",
                    message = "type=custom requires a non-empty label.",
                    nextStep = "Provide label or use a standard type."
                )
            }
            if (type.startsWith("unknown:")) {
                throw ContactsGatewayException(
                    code = "invalid_arguments",
                    message = "Provider-specific unknown contact types are read-only through this tool.",
                    nextStep = "Use a standard type or type=custom with label."
                )
            }
        }
        if (!isUpdate && !hasMeaningfulValue(draft)) {
            throw ContactsGatewayException(
                code = "invalid_arguments",
                message = "A new ${draft.kind.wireName} item must contain a meaningful field.",
                nextStep = "Provide the item value before adding it."
            )
        }
        if (isUpdate && draft.values.isEmpty() && draft.isPrimary == null && draft.isSuperPrimary == null) {
            throw ContactsGatewayException(
                code = "invalid_arguments",
                message = "An update item must change at least one field or primary flag.",
                nextStep = "Provide fields to change, or remove the item by data_id."
            )
        }
    }

    internal fun hasMeaningfulValue(draft: ContactDataDraft): Boolean {
        val keys = when (draft.kind) {
            ContactDataKind.NAME -> setOf("display", "prefix", "given", "middle", "family", "suffix", "phonetic_given", "phonetic_middle", "phonetic_family")
            ContactDataKind.PHONE -> setOf("number")
            ContactDataKind.EMAIL -> setOf("address")
            ContactDataKind.ADDRESS -> setOf("formatted", "street", "po_box", "neighborhood", "city", "region", "postcode", "country")
            ContactDataKind.ORGANIZATION -> setOf("company", "department", "title", "job_description", "office_location")
            ContactDataKind.WEBSITE -> setOf("url")
            ContactDataKind.EVENT -> setOf("date")
            ContactDataKind.RELATION -> setOf("name")
            ContactDataKind.NICKNAME -> setOf("name")
            ContactDataKind.NOTE -> setOf("note")
        }
        return keys.any { !draft.values[it].isNullOrBlank() }
    }
}

internal object ContactDateCodec {
    private val fullDate = Regex("^\\d{4}-\\d{2}-\\d{2}$")
    private val yearlessDate = Regex("^--\\d{2}-\\d{2}$")

    fun requireValid(value: String) {
        val valid = try {
            when {
                fullDate.matches(value) -> {
                    LocalDate.parse(value)
                    true
                }

                yearlessDate.matches(value) -> {
                    LocalDate.parse("2000-${value.substring(2)}")
                    true
                }

                else -> false
            }
        } catch (_: DateTimeException) {
            false
        }
        if (!valid) {
            throw ContactsGatewayException(
                code = "invalid_arguments",
                message = "Contact dates must use YYYY-MM-DD or --MM-DD.",
                nextStep = "Provide a valid calendar date in one of the supported forms."
            )
        }
    }
}

internal object ContactTypeCodec {
    private const val CUSTOM = 0

    private val mappings: Map<ContactDataKind, Map<String, Int>> = mapOf(
        ContactDataKind.PHONE to mapOf(
            "home" to 1, "mobile" to 2, "work" to 3, "fax_work" to 4, "fax_home" to 5,
            "pager" to 6, "other" to 7, "callback" to 8, "car" to 9, "company_main" to 10,
            "isdn" to 11, "main" to 12, "other_fax" to 13, "radio" to 14, "telex" to 15,
            "tty_tdd" to 16, "work_mobile" to 17, "work_pager" to 18, "assistant" to 19, "mms" to 20
        ),
        ContactDataKind.EMAIL to mapOf("home" to 1, "work" to 2, "other" to 3, "mobile" to 4),
        ContactDataKind.ADDRESS to mapOf("home" to 1, "work" to 2, "other" to 3),
        ContactDataKind.ORGANIZATION to mapOf("work" to 1, "other" to 2),
        ContactDataKind.WEBSITE to mapOf("homepage" to 1, "blog" to 2, "profile" to 3, "home" to 4, "work" to 5, "ftp" to 6, "other" to 7),
        ContactDataKind.EVENT to mapOf("anniversary" to 1, "other" to 2, "birthday" to 3),
        ContactDataKind.RELATION to mapOf(
            "assistant" to 1, "brother" to 2, "child" to 3, "domestic_partner" to 4, "father" to 5,
            "friend" to 6, "manager" to 7, "mother" to 8, "parent" to 9, "partner" to 10,
            "referred_by" to 11, "relative" to 12, "sister" to 13, "spouse" to 14
        ),
        ContactDataKind.NICKNAME to mapOf("default" to 1, "other_name" to 2, "maiden_name" to 3, "short_name" to 4, "initials" to 5)
    )

    fun decode(kind: ContactDataKind, type: Int): String {
        if (type == CUSTOM) return "custom"
        return mappings[kind]?.entries?.firstOrNull { it.value == type }?.key ?: "unknown:$type"
    }

    fun encode(kind: ContactDataKind, type: String): Int {
        val normalized = type.trim().lowercase(Locale.US)
        if (normalized == "custom") return CUSTOM
        return mappings[kind]?.get(normalized) ?: throw ContactsGatewayException(
            code = "invalid_arguments",
            message = "Unsupported ${kind.wireName} type '$type'.",
            nextStep = "Use a standard type listed in the contacts tool schema, or type=custom with label."
        )
    }
}
