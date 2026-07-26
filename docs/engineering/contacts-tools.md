# Contacts Tool Contract

Last reviewed: 2026-07-26

The cohesive `contacts` tool exposes Android contacts as aggregate contacts, account-owned RawContacts, and typed Data rows. The tool interface does not expose provider MIME strings or `DATA1` through `DATA15`; `ContactsProviderGateway` and its Android adapter own that mapping.

## Actions and selectors

The actions are `search`, `get_contact`, `create_contact`, `update_contact`, `delete_contact`, `open_contact`, `open_create_contact`, and `open_app_settings`.

Read, update, delete, and open actions accept `contact_id`, `lookup_key`, or both. When both are present, they must resolve to the same aggregate contact. `get_contact` returns aggregate identity and starred state, every RawContact with account and writability metadata, and supported Data rows with stable `data_id`, ownership, primary state, read-only state, and typed fields.

Supported Data kinds are structured name, phone, email, postal address, organization, website, event, relation, nickname, and note. Unknown provider type integers are returned as `unknown:<int>`. Custom types use `type=custom` and require `label`. Dates use `YYYY-MM-DD` or `--MM-DD`. Unsupported MIME rows are summarized in `unsupported_data_types` and are not modified.

Photos, groups, IM, SIP, user Profile, SIM management, aggregation control, and custom MIME data are outside this version.

## Mutation rules

`create_contact` accepts one structured `contact` object and creates one RawContact plus all Data rows in one provider batch. It always creates StructuredName. If a name is absent, a display value is derived from organization, phone, or email and the result reports `name_source=derived`.

Account selection is platform-owned: API 33 and later uses the system default account, API 30–32 uses Android's local-account identity, and API 24–29 lets the provider create a local RawContact. PalmClaw does not guess another cloud account when the selected default cannot be written; the error directs the agent to `open_create_contact`.

`update_contact` accepts grouped `add` and `update` arrays plus `remove_data_ids`. Updates require `data_id` and change only supplied fields. Adds require `raw_contact_id` unless exactly one writable RawContact exists. Data rows never move between RawContacts. One call is limited to 50 requested changes.

Setting `is_super_primary=true` also sets primary and clears conflicting primary state for the same kind in the same batch. Read-only rows, read-only RawContacts, kind mismatches, and ambiguous RawContact selection fail before the batch is written.

Updates assert every affected RawContact `VERSION`, apply one `ContentResolver.applyBatch()` transaction, resolve the possibly changed aggregate identity through a RawContact, and verify added, updated, and removed Data rows. Unsupported Data rows are preserved.

Whole-contact deletion always asks for confirmation. If any RawContact is read-only, the whole deletion is refused. A confirmed deletion asserts versions, deletes all RawContacts in one batch, and verifies that neither the RawContacts nor aggregate lookup remain.

## Result and error contract

Mutation results include `status`, structured `contact` when it still exists, `changed_data_ids`, `removed_data_ids`, and `raw_contacts_changed`. Creation also returns `account_resolution_source` and `name_source`.

Stable errors include `raw_contact_required`, `contact_not_writable`, `data_read_only`, `data_kind_mismatch`, `contact_conflict`, and `verification_failed`. User-mediated fallbacks use `open_contact`, `open_create_contact`, or `open_app_settings` instead of reporting a provider mutation that was not verified.

## Device verification

- Create a contact in the device default account with multiple phones, emails, an address, an organization, and a birthday; verify the system Contacts app and returned account metadata.
- Edit one phone by `data_id`; verify every other supported and unsupported row is unchanged.
- Remove one Data row; verify other rows and RawContacts remain.
- On a multi-account aggregate, add a Data row to an explicit writable `raw_contact_id` and verify account ownership.
- Cancel whole-contact deletion and verify no change. Confirm once and verify every related writable RawContact disappears.
- Repeat a create or update with cloud sync enabled and confirm the system Contacts app and the synced account show the same values.

The current source, planner tests, schema tests, fake-gateway tool tests, and create/update/delete batch-shape tests are implemented. Android Studio compilation and the focused automated tests passed on 2026-07-26. Aggregate re-resolution, rollback, deletion verification against a real provider, and the device checks above remain pending.
