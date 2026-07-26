package com.palmclaw.tools

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.ContactsContract
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.util.Locale

internal class ContactsControlTool(
    private val context: Context,
    private val gateway: ContactsProviderGateway = AndroidContactsProviderGateway(context),
    private val permissionRequester: suspend (String, List<String>, Boolean, Boolean, Boolean) -> ToolResult? =
        { action, required, requestIfMissing, openSettingsIfFailed, waitUserConfirmation ->
            ensurePersonalPermissionsInteractive(
                context = context,
                toolName = "contacts",
                action = action,
                required = required,
                requestIfMissing = requestIfMissing,
                openSettingsIfFailed = openSettingsIfFailed,
                waitUserConfirmation = waitUserConfirmation
            )
        },
    private val confirmationRequester: suspend (String, String, String, String) -> Boolean? =
        { title, message, confirmLabel, cancelLabel ->
            AndroidUserActionBridge.requestUserConfirmation(title, message, confirmLabel, cancelLabel)
        },
    private val contactLauncher: (Intent) -> ToolResult = { intent -> launchIntent(context, intent) }
) : Tool, TimedTool {
    override val name: String = "contacts"
    override val description: String =
        "Manage Android aggregate contacts through stable contact, RawContact, and Data identifiers. " +
            "Use action=search|get_contact|create_contact|update_contact|delete_contact|open_contact|open_create_contact|open_app_settings. " +
            "Updates use exact data_id add/update/remove operations; whole-contact deletion requires user confirmation."
    override val timeoutMs: Long = 120_000L
    override val jsonSchema: JsonObject = contactsToolSchema()

    override suspend fun run(argumentsJson: String): ToolResult = withContext(Dispatchers.IO) {
        val args = runCatching { Json.parseToJsonElement(argumentsJson).jsonObject }.getOrElse { error ->
            return@withContext personalError(name, "unknown", "invalid_arguments", "Invalid contacts arguments: ${error.message}")
        }
        val action = args.string("action")?.trim()?.lowercase(Locale.US).orEmpty()
        try {
            when (action) {
                "search" -> search(args)
                "get_contact" -> getContact(args)
                "create_contact" -> createContact(args)
                "update_contact" -> updateContact(args)
                "delete_contact" -> deleteContact(args)
                "open_contact" -> openContact(args)
                "open_create_contact" -> openCreateContact(action)
                "open_app_settings" -> openAppSettings(action)
                else -> personalError(
                    name,
                    action.ifBlank { "unknown" },
                    "unsupported_action",
                    "Unsupported action '${args.string("action").orEmpty()}'.",
                    "Use one of the actions declared in the contacts tool schema."
                )
            }
        } catch (error: ContactsGatewayException) {
            personalError(name, action, error.code, error.message, error.nextStep)
        } catch (error: IllegalArgumentException) {
            personalError(name, action, "invalid_arguments", error.message ?: "Invalid contacts arguments.")
        }
    }

    private suspend fun search(args: JsonObject): ToolResult {
        val action = "search"
        permission(args, action, listOf(Manifest.permission.READ_CONTACTS))?.let { return it }
        val query = args.string("query")?.trim().orEmpty()
        val count = (args.int("count") ?: 20).coerceIn(1, 50)
        val contacts = gateway.search(query, count)
        return personalOk(name, action, if (contacts.isEmpty()) "No contacts found." else "Found ${contacts.size} contacts.") {
            put("query", query)
            put("count", contacts.size)
            putJsonArray("contacts") { contacts.forEach { add(it.toJson()) } }
        }
    }

    private suspend fun getContact(args: JsonObject): ToolResult {
        val action = "get_contact"
        permission(args, action, listOf(Manifest.permission.READ_CONTACTS))?.let { return it }
        val contact = resolveContact(args)
        return personalOk(name, action, "contact loaded: ${contact.displayName.ifBlank { contact.lookupKey }}") {
            put("contact", contact.toJson())
        }
    }

    private suspend fun createContact(args: JsonObject): ToolResult {
        val action = "create_contact"
        permission(args, action, listOf(Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS))?.let { return it }
        val request = parseCreateRequest(args["contact"] as? JsonObject ?: throw invalid("contact is required."))
        val result = gateway.createContact(request)
        return mutationResult(action, "contact created", result)
    }

    private suspend fun updateContact(args: JsonObject): ToolResult {
        val action = "update_contact"
        permission(args, action, listOf(Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS))?.let { return it }
        val contact = resolveContact(args)
        val request = ContactUpdateRequest(
            starred = args.boolean("starred"),
            add = parseGroups(args["add"] as? JsonObject, requireDataId = false),
            update = parseGroups(args["update"] as? JsonObject, requireDataId = true),
            removeDataIds = (args["remove_data_ids"] as? JsonArray).orEmpty().map { element ->
                element.jsonPrimitive.longOrNull ?: throw invalid("remove_data_ids must contain integers.")
            }
        )
        val result = gateway.updateContact(contact, request)
        return mutationResult(action, "contact updated", result)
    }

    private suspend fun deleteContact(args: JsonObject): ToolResult {
        val action = "delete_contact"
        permission(args, action, listOf(Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS))?.let { return it }
        val contact = resolveContact(args)
        val readOnly = contact.rawContacts.filterNot { it.writable }
        if (readOnly.isNotEmpty()) {
            throw ContactsGatewayException(
                "contact_not_writable",
                "The aggregate contact contains read-only RawContacts: ${readOnly.joinToString { it.rawContactId.toString() }}.",
                "Use open_contact to review or delete it in the owning contacts app."
            )
        }
        when (
            confirmationRequester(
                "Delete contact?",
                "Delete ${contact.displayName.ifBlank { "this contact" }} and all ${contact.rawContacts.size} RawContact records?",
                "Delete",
                "Cancel"
            )
        ) {
            false -> return personalError(name, action, "user_cancelled", "Contact deletion was cancelled.", "No contact data was changed.")
            null -> return personalError(name, action, "ui_unavailable", "Deletion confirmation is unavailable.", "Use open_contact to delete it manually.")
            true -> Unit
        }
        val deleted = gateway.deleteContact(contact)
        return personalOk(name, action, "contact deleted: ${deleted.lookupKey}") {
            put("contact_id", deleted.contactId)
            put("lookup_key", deleted.lookupKey)
            putJsonArray("changed_data_ids") {}
            putJsonArray("removed_data_ids") { contact.data.forEach { add(it.dataId) } }
            putJsonArray("raw_contacts_changed") { deleted.rawContactsChanged.forEach { add(it) } }
        }
    }

    private suspend fun openContact(args: JsonObject): ToolResult {
        val action = "open_contact"
        permission(args, action, listOf(Manifest.permission.READ_CONTACTS))?.let { return it }
        val contact = resolveContact(args)
        val uri = ContactsContract.Contacts.getLookupUri(contact.contactId, contact.lookupKey)
            ?: throw ContactsGatewayException("not_found", "The contact no longer has a lookup URI.", "Reload the contact and retry.")
        val launch = contactLauncher(Intent(Intent.ACTION_VIEW, uri))
        return if (launch.isError) {
            personalError(name, action, "open_contact_failed", launch.content, "Open the system contacts app manually.")
        } else {
            personalOk(name, action, "contact opened") { put("contact", contact.toJson()) }
        }
    }

    private fun openCreateContact(action: String): ToolResult {
        val intent = Intent(ContactsContract.Intents.Insert.ACTION).apply {
            type = ContactsContract.RawContacts.CONTENT_TYPE
        }
        val launch = contactLauncher(intent)
        return if (launch.isError) {
            personalError(name, action, "open_create_contact_failed", launch.content, "Open the system contacts app manually.")
        } else {
            personalOk(name, action, "system contact editor opened")
        }
    }

    private fun openAppSettings(action: String): ToolResult {
        val launch = openPersonalAppSettings(context)
        return if (launch.isError) {
            personalError(name, action, "open_settings_failed", launch.content, "Open app settings manually.")
        } else {
            personalOk(name, action, "app settings opened")
        }
    }

    private suspend fun permission(args: JsonObject, action: String, permissions: List<String>): ToolResult? =
        permissionRequester(
            action,
            permissions,
            args.boolean("request_if_missing") ?: true,
            args.boolean("open_settings_if_failed") ?: true,
            args.boolean("wait_user_confirmation") ?: true
        )

    private fun resolveContact(args: JsonObject): ContactRecord {
        val contactId = args.long("contact_id")
        val lookupKey = args.string("lookup_key")?.trim()?.takeIf { it.isNotEmpty() }
        if (contactId == null && lookupKey == null) throw invalid("contact_id or lookup_key is required.")
        return gateway.getContact(ContactSelector(contactId, lookupKey)) ?: throw ContactsGatewayException(
            "not_found",
            "The selected contact was not found.",
            "Use search to obtain a current contact_id or lookup_key."
        )
    }

    private fun parseCreateRequest(contact: JsonObject): ContactCreateRequest {
        val suppliedName = (contact["name"] as? JsonObject)?.let { parseDraft(ContactDataKind.NAME, it, false) }
        val otherData = ContactDataKind.entries.filterNot { it == ContactDataKind.NAME }.flatMap { kind ->
            val array = contact[kind.groupName] as? JsonArray ?: JsonArray(emptyList())
            array.map { parseDraft(kind, it.jsonObject, false) }
        }
        var nameSource = "explicit"
        val name = if (suppliedName != null && ContactsMutationPlanner.hasMeaningfulValue(suppliedName)) {
            val display = suppliedName.values["display"].orEmpty().ifBlank { structuredDisplayName(suppliedName.values) }
            if (display.isBlank()) throw invalid("contact.name must contain display or structured name fields.")
            suppliedName.copy(values = suppliedName.values + ("display" to display))
        } else {
            val display = otherData.firstNotNullOfOrNull { draft ->
                when (draft.kind) {
                    ContactDataKind.ORGANIZATION -> draft.values["company"]
                    ContactDataKind.PHONE -> draft.values["number"]
                    ContactDataKind.EMAIL -> draft.values["address"]
                    else -> null
                }?.trim()?.takeIf { it.isNotEmpty() }
            } ?: throw invalid("A contact requires a name, organization company, phone number, or email address.")
            nameSource = "derived"
            ContactDataDraft(ContactDataKind.NAME, values = mapOf("display" to display))
        }
        otherData.forEach { draft ->
            if (!ContactsMutationPlanner.hasMeaningfulValue(draft)) {
                throw invalid("A ${draft.kind.wireName} item must contain a meaningful value.")
            }
            if (draft.kind == ContactDataKind.EVENT) draft.values["date"]?.let(ContactDateCodec::requireValid)
            validateCustomType(draft)
        }
        validateCustomType(name)
        return ContactCreateRequest(
            starred = contact.boolean("starred") ?: false,
            data = normalizeCreatePrimary(listOf(name) + otherData),
            nameSource = nameSource
        )
    }

    private fun normalizeCreatePrimary(data: List<ContactDataDraft>): List<ContactDataDraft> {
        val primaryWinners = data.withIndex()
            .filter { (_, draft) -> draft.isPrimary == true || draft.isSuperPrimary == true }
            .associateBy { it.value.kind }
        val superWinners = data.withIndex()
            .filter { (_, draft) -> draft.isSuperPrimary == true }
            .associateBy { it.value.kind }
        return data.mapIndexed { index, draft ->
            val requestedPrimary = draft.isPrimary == true || draft.isSuperPrimary == true
            val keepPrimary = !requestedPrimary || primaryWinners[draft.kind]?.index == index
            val keepSuper = draft.isSuperPrimary != true || (
                superWinners[draft.kind]?.index == index && keepPrimary
                )
            when {
                requestedPrimary && !keepPrimary -> draft.copy(isPrimary = false, isSuperPrimary = false)
                draft.isSuperPrimary == true && !keepSuper -> draft.copy(isSuperPrimary = false)
                draft.isSuperPrimary == true -> draft.copy(isPrimary = true)
                else -> draft
            }
        }
    }

    private fun parseGroups(groups: JsonObject?, requireDataId: Boolean): List<ContactDataDraft> {
        if (groups == null) return emptyList()
        return ContactDataKind.entries.flatMap { kind ->
            val array = groups[kind.groupName] as? JsonArray ?: JsonArray(emptyList())
            array.map { element ->
                parseDraft(kind, element.jsonObject, requireDataId).also { draft ->
                    if (draft.kind == ContactDataKind.EVENT) draft.values["date"]?.let(ContactDateCodec::requireValid)
                    validateCustomType(draft)
                }
            }
        }
    }

    private fun parseDraft(kind: ContactDataKind, obj: JsonObject, requireDataId: Boolean): ContactDataDraft {
        val dataId = obj.long("data_id")
        if (requireDataId && dataId == null) throw invalid("Every ${kind.wireName} update requires data_id.")
        val common = setOf("data_id", "raw_contact_id", "is_primary", "is_super_primary")
        val values = obj.entries.filterNot { it.key in common }.associate { (key, element) ->
            key to (element as? JsonPrimitive)?.contentOrNull.orEmpty()
        }
        return ContactDataDraft(
            kind = kind,
            dataId = dataId,
            rawContactId = obj.long("raw_contact_id"),
            values = values,
            isPrimary = obj.boolean("is_primary"),
            isSuperPrimary = obj.boolean("is_super_primary")
        )
    }

    private fun validateCustomType(draft: ContactDataDraft) {
        val type = draft.values["type"] ?: return
        if (type == "custom" && draft.values["label"].isNullOrBlank()) throw invalid("type=custom requires label.")
        if (type.startsWith("unknown:")) throw invalid("unknown provider types cannot be written.")
        ContactTypeCodec.encode(draft.kind, type)
    }

    private fun structuredDisplayName(values: Map<String, String>): String =
        listOf("prefix", "given", "middle", "family", "suffix")
            .mapNotNull { values[it]?.trim()?.takeIf(String::isNotEmpty) }
            .joinToString(" ")

    private fun mutationResult(action: String, message: String, result: ContactMutationResult): ToolResult =
        personalOk(name, action, "$message: ${result.contact.displayName.ifBlank { result.contact.lookupKey }}") {
            put("contact", result.contact.toJson())
            putJsonArray("changed_data_ids") { result.changedDataIds.forEach { add(it) } }
            putJsonArray("removed_data_ids") { result.removedDataIds.forEach { add(it) } }
            putJsonArray("raw_contacts_changed") { result.rawContactsChanged.forEach { add(it) } }
            result.accountResolutionSource?.let { put("account_resolution_source", it) }
            result.nameSource?.let { put("name_source", it) }
        }

    private fun invalid(message: String) = ContactsGatewayException(
        "invalid_arguments",
        message,
        "Use the structured contacts schema and current identifiers from get_contact."
    )

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.longOrNull
    private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull
    private fun JsonObject.boolean(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull
}

private fun ContactSummary.toJson(): JsonObject = buildJsonObject {
    put("contact_id", contactId)
    put("lookup_key", lookupKey)
    put("display_name", displayName)
    put("starred", starred)
}

private fun ContactRecord.toJson(): JsonObject = buildJsonObject {
    put("contact_id", contactId)
    put("lookup_key", lookupKey)
    put("display_name", displayName)
    put("starred", starred)
    putJsonArray("raw_contacts") {
        rawContacts.forEach { raw ->
            add(buildJsonObject {
                put("raw_contact_id", raw.rawContactId)
                raw.accountName?.let { put("account_name", it) }
                raw.accountType?.let { put("account_type", it) }
                put("writable", raw.writable)
            })
        }
    }
    ContactDataKind.entries.forEach { kind ->
        putJsonArray(kind.groupName) {
            data.filter { it.kind == kind }.forEach { add(it.toJson()) }
        }
    }
    putJsonArray("unsupported_data_types") { unsupportedDataTypes.forEach { add(it) } }
}

private fun ContactDataRecord.toJson(): JsonObject = buildJsonObject {
    put("data_id", dataId)
    put("raw_contact_id", rawContactId)
    put("kind", kind.wireName)
    put("is_primary", isPrimary)
    put("is_super_primary", isSuperPrimary)
    put("read_only", readOnly)
    values.forEach { (key, value) -> put(key, value) }
}

internal fun contactsToolSchema(): JsonObject {
    val rootProperties = buildJsonObject {
        put("action", stringSchema("search", "get_contact", "create_contact", "update_contact", "delete_contact", "open_contact", "open_create_contact", "open_app_settings"))
        put("contact_id", integerSchema())
        put("lookup_key", stringSchema())
        put("query", stringSchema())
        put("count", integerSchema(1, 50))
        put("starred", booleanSchema())
        put("contact", createContactSchema())
        put("add", mutationGroupsSchema(requireDataId = false))
        put("update", mutationGroupsSchema(requireDataId = true))
        put("remove_data_ids", arraySchema(integerSchema(), 50))
        put("request_if_missing", booleanSchema())
        put("open_settings_if_failed", booleanSchema())
        put("wait_user_confirmation", booleanSchema())
    }
    return objectSchema(rootProperties, listOf("action"))
}

private fun createContactSchema(): JsonObject {
    val properties = buildJsonObject {
        put("starred", booleanSchema())
        put("name", draftSchema(ContactDataKind.NAME, includeDataId = false, includeRawId = false))
        ContactDataKind.entries.filterNot { it == ContactDataKind.NAME }.forEach { kind ->
            put(kind.groupName, arraySchema(draftSchema(kind, false, false), 50))
        }
    }
    return objectSchema(properties)
}

private fun mutationGroupsSchema(requireDataId: Boolean): JsonObject {
    val properties = buildJsonObject {
        ContactDataKind.entries.forEach { kind ->
            put(kind.groupName, arraySchema(draftSchema(kind, requireDataId, includeRawId = true), 50))
        }
    }
    return objectSchema(properties)
}

private fun draftSchema(kind: ContactDataKind, includeDataId: Boolean, includeRawId: Boolean): JsonObject {
    val properties = buildJsonObject {
        if (includeDataId) put("data_id", integerSchema())
        if (includeRawId) put("raw_contact_id", integerSchema())
        put("is_primary", booleanSchema())
        put("is_super_primary", booleanSchema())
        when (kind) {
            ContactDataKind.NAME -> listOf("display", "prefix", "given", "middle", "family", "suffix", "phonetic_given", "phonetic_middle", "phonetic_family").forEach { put(it, stringSchema()) }
            ContactDataKind.PHONE -> {
                put("number", stringSchema())
                typedFields(kind)
            }
            ContactDataKind.EMAIL -> {
                put("address", stringSchema())
                typedFields(kind)
            }
            ContactDataKind.ADDRESS -> {
                listOf("formatted", "street", "po_box", "neighborhood", "city", "region", "postcode", "country").forEach { put(it, stringSchema()) }
                typedFields(kind)
            }
            ContactDataKind.ORGANIZATION -> {
                listOf("company", "department", "title", "job_description", "office_location").forEach { put(it, stringSchema()) }
                typedFields(kind)
            }
            ContactDataKind.WEBSITE -> {
                put("url", stringSchema())
                typedFields(kind)
            }
            ContactDataKind.EVENT -> {
                put("date", stringSchema())
                typedFields(kind)
            }
            ContactDataKind.RELATION -> {
                put("name", stringSchema())
                typedFields(kind)
            }
            ContactDataKind.NICKNAME -> {
                put("name", stringSchema())
                typedFields(kind)
            }
            ContactDataKind.NOTE -> put("note", stringSchema())
        }
    }
    return objectSchema(properties, if (includeDataId) listOf("data_id") else emptyList())
}

private fun kotlinx.serialization.json.JsonObjectBuilder.typedFields(kind: ContactDataKind) {
    val types = when (kind) {
        ContactDataKind.PHONE -> listOf("custom", "home", "mobile", "work", "fax_work", "fax_home", "pager", "other", "callback", "car", "company_main", "isdn", "main", "other_fax", "radio", "telex", "tty_tdd", "work_mobile", "work_pager", "assistant", "mms")
        ContactDataKind.EMAIL -> listOf("custom", "home", "work", "other", "mobile")
        ContactDataKind.ADDRESS -> listOf("custom", "home", "work", "other")
        ContactDataKind.ORGANIZATION -> listOf("custom", "work", "other")
        ContactDataKind.WEBSITE -> listOf("custom", "homepage", "blog", "profile", "home", "work", "ftp", "other")
        ContactDataKind.EVENT -> listOf("custom", "anniversary", "other", "birthday")
        ContactDataKind.RELATION -> listOf("custom", "assistant", "brother", "child", "domestic_partner", "father", "friend", "manager", "mother", "parent", "partner", "referred_by", "relative", "sister", "spouse")
        ContactDataKind.NICKNAME -> listOf("custom", "default", "other_name", "maiden_name", "short_name", "initials")
        else -> emptyList()
    }
    put("type", stringSchema(*types.toTypedArray()))
    put("label", stringSchema())
}

private fun objectSchema(properties: JsonObject, required: List<String> = emptyList()): JsonObject = buildJsonObject {
    put("type", "object")
    put("additionalProperties", false)
    put("properties", properties)
    if (required.isNotEmpty()) put("required", buildJsonArray { required.forEach { add(it) } })
}

private fun arraySchema(items: JsonObject, maxItems: Int): JsonObject = buildJsonObject {
    put("type", "array")
    put("maxItems", maxItems)
    put("items", items)
}

private fun stringSchema(vararg values: String): JsonObject = buildJsonObject {
    put("type", "string")
    if (values.isNotEmpty()) put("enum", buildJsonArray { values.forEach { add(it) } })
}

private fun integerSchema(minimum: Int? = null, maximum: Int? = null): JsonObject = buildJsonObject {
    put("type", "integer")
    minimum?.let { put("minimum", it) }
    maximum?.let { put("maximum", it) }
}

private fun booleanSchema(): JsonObject = buildJsonObject { put("type", "boolean") }
