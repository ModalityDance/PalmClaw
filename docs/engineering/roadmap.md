# PalmClaw Engineering Roadmap

Last reviewed: 2026-08-06

This roadmap tracks reusable product and engineering improvements. It does not include task-specific shortcuts or evaluation-only instrumentation.

## Current Priorities

| Priority | Area | Status | Next outcome |
| --- | --- | --- | --- |
| P0 | Calendar tool coverage | In progress | Verify recurring event behavior against real Android calendar providers. |
| P0 | Contacts tool coverage | In progress | Verify default-account, multi-RawContact, sync, and deletion behavior on a real device. |
| P1 | Workspace file tools | In progress | Verify the nine-tool NIO implementation in Android Studio, on API 24/25, and against external storage on a device. |
| P1 | Native tool capability coverage | In progress | Compile and verify the bounded BLE client and agent notification lifecycle. |
| P1 | Workspace text codec | Source-verified and manually tested | Record the ICU4J APK size delta when a comparable pre-ICU build is available. |
| P1 | Runtime tool integration | In progress | Manually compile and run focused, full-suite, foreground, and Always-on verification for the source implementation. |
| P1 | Runtime/UI boundaries | In progress | Verify shared channel projection, discovery, and runtime UI observation, then extract gateway adapter lifecycle ownership. |
| P2 | `GatewayRuntime` boundaries | In progress | Verify shared channel projection, then review adapter and automation lifecycle ownership separately. |
| P2 | Refactor verification | In progress | Add focused tests and structural guards for each extracted boundary while keeping the full unit suite and debug build green. |
| P2 | Secondary tool coverage | Planned | Review safe media mutation, Cron get/update, explicit memory clear, and portable web-search filters after native capability modules are stable. |
| P2 | Long-task capabilities | Deferred | Reconsider progress, trace, recovery, pause, and resume after the core runtime and UI boundaries are stable. |

## Planned Work

### Calendar tool coverage

The unified `calendar` tool now exposes structured recurrence, timezones, availability, access level, guest controls, reminders, attendees, RSVP, calendar capabilities, and series or occurrence mutations. `CalendarProviderGateway` isolates `CalendarContract` access, while the tool keeps typed user-facing values and structured results.

The implementation uses `DTEND` for non-recurring events and `DURATION` plus recurrence values for recurring events. It preserves unsupported raw provider recurrence rules during unrelated updates. Omitted reminder and attendee arrays preserve provider rows; present arrays replace them. Cross-calendar movement and “this and following” series splitting remain deliberately excluded.

Acceptance conditions:

- Focused recurrence tests and fake-gateway instrumented tests pass.
- Timed and all-day events round-trip on a device.
- Series and one-occurrence update or deletion affect only the requested scope.
- Reminder and attendee preservation, replacement, and clearing match the tool contract.
- RSVP either updates the uniquely resolved current-account attendee row or returns an explicit fallback error.
- Every deletion requires user confirmation, and a cancelled confirmation performs no provider mutation.

See the [calendar tool contract](calendar-tools.md) for the exact boundary and device checklist.

Android Studio compilation and the focused automated tests passed in the manual verification reported on 2026-07-26. Real-provider device checks remain pending.

### Contacts tool coverage

The `contacts` tool now exposes aggregate identity, writable RawContacts, and stable typed Data rows for structured names, phones, emails, addresses, organizations, websites, events, relations, nicknames, and notes. The Android adapter owns account resolution, MIME columns, optimistic RawContact version assertions, atomic provider batches, aggregate re-resolution, and verification.

Create uses the system default or platform local-account policy without guessing another cloud account. Update uses exact `data_id` add, update, and remove operations and never replaces a whole Data kind or moves a row between RawContacts. Whole-contact deletion requires confirmation and refuses aggregates containing any read-only RawContact.

Acceptance conditions:

- Focused planner and fake-gateway tests pass in Android Studio.
- A default-account contact with multiple typed values round-trips through the system Contacts app.
- Exact Data updates and removals preserve other RawContact and unsupported MIME rows.
- Ambiguous or read-only ownership fails before any provider write.
- Version conflicts roll back the whole batch and return `contact_conflict`.
- Cancelled deletion performs no write; confirmed deletion removes and verifies all related writable RawContacts.

See the [contacts tool contract](contacts-tools.md) for the exact interface and device checklist.

Android Studio compilation and the focused automated tests passed in the manual verification reported on 2026-07-26. Real-provider device checks remain pending.

### Workspace text codec

This is the current implementation priority. The problem is in PalmClaw's text-tool policy, not in Android's Unicode support.

The source-confirmed causes before this implementation were:

- `read` scores UTF-8, Big5, GBK, GB18030, Shift_JIS, and Windows-1252 together. A valid UTF-8 result can lose to a higher-scoring legacy decode.
- `edit` and `grep` bypass the multi-encoding reader and read every file as UTF-8.
- `append` always appends UTF-8 bytes, even when the existing file uses another encoding. This can create a mixed-encoding file.
- Plain-text normalization replaces each carriage return with a newline, so CRLF can become two logical line breaks.
- BOM handling covered UTF-8 and UTF-16LE/BE, but not UTF-32.

The implemented policy is:

- Files created or overwritten inside PalmClaw workspaces use UTF-8 as the canonical encoding.
- Decoding order is fixed: BOM, explicit `encoding`, strict UTF-8, then ICU4J detection.
- ICU4J only exposes supported Big5, GB18030, Shift_JIS, Windows-1252, UTF-16, and UTF-32 candidates to the codec. GBK remains available as an explicit encoding.
- `read` and `grep` can use the highest supported statistical candidate at confidence 50 or above. Lower confidence returns `encoding_detection_uncertain` with up to three candidates.
- `append` and `edit` never use a statistically detected encoding, regardless of confidence. They return `encoding_required_for_mutation` until the caller supplies an explicit encoding.
- Successful results include `encoding_source`; statistical results also include `encoding_confidence` and structured `encoding_candidates`.
- Existing non-UTF-8 files are never modified through raw UTF-8 append. Explicit legacy mutation preserves the charset and BOM, and rejects unrepresentable text before writing.
- Charset handling and newline handling remain separate. Reading CRLF, LF, or CR text must not add or remove logical lines.
- NUL and C0 controls other than tab, line feed, and carriage return are rejected as binary-like content. There is no language-specific character scoring.
- OOXML and ODT XML stay on BOM, XML declaration, and strict UTF-8 decoding and do not use ICU legacy detection.

Acceptance conditions:

- UTF-8 text containing simplified Chinese, traditional Chinese, Japanese, Latin text, and emoji round-trips through write, read, append, edit, and grep.
- Valid UTF-8 without an explicit encoding is never reported as GBK, GB18030, Big5, Shift_JIS, or Windows-1252.
- UTF-8 BOM, UTF-16LE/BE BOM, and UTF-32LE/BE BOM fixtures are handled explicitly.
- High-confidence legacy fixtures are readable, while low-confidence input returns candidates and never enters a destructive write path.
- Append and edit cannot create a file containing multiple encodings.
- CRLF, LF, and CR fixtures preserve the same logical line count.

### Text round-trip verification

Focused tests cover plain UTF-8 multilingual and symbol-only text, BOM-based UTF-8/UTF-16/UTF-32, explicit encodings, ICU legacy fixtures, the 49/50 confidence boundary, control characters, newline handling, and file-action round trips. They also verify that append and edit require explicit legacy encodings, preserve the selected charset and BOM, and reject unrepresentable text without changing file bytes.

The verification is split across two levels:

- Codec tests for precedence, strict decoding, ICU candidates, confidence boundaries, format preservation, and representability.
- File-tool tests that execute write, read, append, edit, and grep against the same fixtures.

Android connected tests are needed only for platform document/PDF paths. Workspace text codec behavior should remain testable as local unit tests.

The focused codec and file-tool tests, together with Android Studio compilation, passed in the manual verification reported on 2026-07-17. Only the comparable APK size measurement remains pending.

### Workspace file tools

The public file interface now contains `find`, `grep`, `read`, `write`, `edit`, `mkdir`, `copy`,
`move`, and `delete`. `find` replaces the overlapping list, glob, and stat concepts. Copy and move
remain separate because only move consumes the source.

`WorkspaceFileSystem` uses Java NIO for attributes, traversal, publication, copy, move, and delete.
All traversals are bounded and do not follow symbolic links. Text writes use sibling temporary
files and safe publication, using atomic replacement when supported and no-replace publication
for create-only operations. Copy verifies staged files before publication. Move
removes the source only after destination verification and reports surviving paths when cleanup or
recovery is incomplete. See the [workspace file tool contract](file-tools.md).

Acceptance conditions:

- `FileToolsContractTest`, `FileToolsTest`, `FileToolsTextEncodingTest`,
  `WorkspaceTextCodecTest`, `WorkspacePathResolverTest`, `BuiltInToolCatalogTest`, and
  `ToolArgumentsValidatorTest` pass.
- API 24/25 and a current Android version can execute NIO find, safe write, copy, move, and
  delete without missing-class failures.
- Read-only limits truncate explicitly; mutation limits fail before writing.
- Symlink, revision, encoding, confirmation, and copy-verification failures preserve original
  bytes and paths.
- APK Analyzer records the NIO desugaring size delta for the same build variant.
- A real device verifies session workspace, `shared://`, and approved external-storage paths.

Source implementation and focused tests are present. Android Studio compilation and all manual
acceptance checks remain pending.

### Bluetooth BLE client

The `bluetooth` tool now has a bounded BLE client contract: adapter status and power, paired-device
listing, scan, one verified GATT connection, service and characteristic inspection, characteristic
read/write, and disconnect. `BleClientGateway` keeps Android callbacks, operation serialization,
timeouts, MTU state, and cleanup out of the tool interface. A separate interaction boundary owns
permissions, settings, and write confirmation.

The generic transport does not infer device commands. Every write uses explicit UUIDs and
user-provided or trusted protocol bytes, and requires user confirmation. Completing a system
settings flow cannot produce a successful connection result unless GATT connection and service
discovery are verified.

Acceptance conditions:

- `BluetoothValueCodecTest` and `BluetoothControlToolTest` pass in Android Studio.
- The debug application compiles without retaining the old Bluetooth schema or multi-connection
  implementation.
- A known BLE peripheral completes scan, connect, inspect, documented read, confirmed write, and
  disconnect.
- A cancelled write changes no device state; a write without response does not claim device
  acknowledgement.
- Permission denial, physical disconnect, timeout, and a second concurrent connection return
  explicit structured errors.

Classic Bluetooth data transfer, multiple connections, background reconnect, subscriptions,
descriptor actions, and device-profile decoding remain outside the current agent use case. See the
[Bluetooth tool contract](bluetooth-tools.md).

### Agent notification lifecycle

Immediate notifications now use a dedicated `notification` tool with status, bounded active
listing, stable-key post and update, exact cancel, and settings recovery. The Android gateway
owns channel creation, namespaced `(tag, id)` identity, explicit immutable PendingIntents,
mutation serialization, and post-mutation verification.

The module sees only `palmclaw.agent.*` notifications. Cron reminders and Always-on foreground
notifications keep their separate lifecycle owners. Future or recurring delivery remains a Cron
responsibility, and notification progress remains outside the current long-task scope.

Acceptance conditions:

- `NotificationKeyCodecTest`, `NotificationControlToolTest`,
  `BuiltInToolCatalogTest`, and relevant schema tests pass.
- Android Studio compiles without the legacy `device(action="notify")` schema.
- Permission allow, deny, and settings recovery work on Android 13 or later.
- Post, duplicate rejection, update, user dismissal, timeout, process restart, list, and cancel
  match the notification contract.
- The tool cannot list or cancel Cron or Always-on notifications.

See the [notification tool contract](notification-tools.md).

### Runtime tool integration

The source implementation now shares `RuntimeControlService` between the UI path and every `GatewayRuntime`. `RuntimeToolIntegration` owns the ten concrete runtime tool classes, DTO mapping, callback registration, and cleanup. `ChatViewModel` sends domain commands for runtime settings, heartbeat, and channel enablement while retaining UI parsing and presentation.

The boundary now:

- Owns runtime-tool callback registration and cleanup.
- Builds typed snapshots without depending on UI state models.
- Applies shared validation and persistence rules.
- Exposes explicit refresh signals to the UI instead of calling UI helpers.
- Is used by both normal and Always-on execution paths.

Focused service, integration, composition-root, and UI structural tests are present. Android Studio compilation, the full unit suite, and the runtime manual checklist remain pending, so this item is not yet source-verified.

Acceptance conditions:

- Runtime, heartbeat, session, channel, and MCP tool snapshots have one implementation owner.
- `ChatViewModel` does not create a `ToolRegistry` or wire callbacks for runtime-owned tools.
- Normal and Always-on modes use the same tool integration behavior.
- Callback lifecycle and error conversion have focused unit tests.

### Runtime and UI boundary cleanup

Continue reducing [`ChatViewModel`](../../app/src/main/java/com/palmclaw/ui/chat/ChatViewModel.kt) toward a UI facade. It no longer imports the ten runtime-owned tool classes, builds their snapshots, performs channel discovery networking and polling, reads process channel diagnostics, or collects runtime status flows. Extract stable workflow owners instead of splitting by file size.

Channel adapter identity, target normalization, binding completeness, and runtime status labels now live in `ChannelAdapterIdentity` and `ChannelBindingRuntimeProjector`. `ChannelDiscoveryService` owns Telegram and Email active discovery plus bounded Feishu and WeCom capture-only discovery. `ChannelGatewayDiagnosticsSource`, `GatewayStatusOverviewAssembler`, and `RuntimeStatusCoordinator` own settings status snapshots and runtime flow observation. `RuntimeApplicationGateway` is exposed through separate status, execution, and refresh interfaces. Source implementation is complete for this boundary, while focused tests, compilation, and device verification remain pending.

Acceptance conditions:

- `ChatViewModel` does not construct repositories, databases, or runtime owners.
- `ChatViewModel` does not build runtime-domain tool snapshots or own runtime-tool callback wiring.
- Runtime behavior remains shared between foreground and Always-on entry points.
- Extracted coordinators have focused unit tests.
- Chat session switching, optimistic sending, processing continuity, and settings scroll restoration remain unchanged.

### `GatewayRuntime` boundary cleanup

[`GatewayRuntime`](../../app/src/main/java/com/palmclaw/runtime/GatewayRuntime.kt) is about 1,790 lines and coordinates agent turns, `RuntimeToolIntegration`, channel adapters, cron, heartbeat, MCP, subagents, attachment delivery, and remote delivery state.

Keep agent-turn ownership and top-level lifecycle coordination in `GatewayRuntime`. Runtime tool construction, callback wiring, DTO mapping, and cleanup live in `RuntimeToolIntegration`; adapter keys, binding completeness, targets, status labels, and UI observation live in focused shared boundaries. After the current unified verification, extract gateway adapter lifecycle ownership first, automation wiring second, and MCP lifecycle third.

Acceptance conditions:

- Extracted services own their callback registration and cleanup as one lifecycle.
- Channel, cron, heartbeat, and MCP behavior still enter the shared runtime path.
- `GatewayRuntime` coordinates services without reimplementing their validation or snapshot mapping.
- Each extracted boundary has focused tests and does not require a full Android UI test to verify core behavior.

### Refactor verification

System cleanup must preserve behavior while ownership changes.

For each boundary extraction:

- Add focused behavioral tests for the new owner.
- Add or update a structural guard when it prevents responsibility from moving back into a shell class.
- Run `:app:testDebugUnitTest` and `:app:assembleDebug` before completion.
- Run focused device checks when session switching, channel continuity, Android permissions, or background execution is affected.

File-size reduction is a useful signal, not an acceptance condition by itself.

### Tool capability coverage

The source audit found capability gaps beyond Calendar and Contacts. Contacts now has a typed implementation with compilation and focused tests complete; real-provider verification remains. Workspace files, the bounded BLE client, and the agent notification lifecycle are source-implemented with verification pending. Media, Cron, memory, and web search have smaller or more scope-dependent gaps.

See the [tool capability coverage audit](tool-capability-audit.md) for the evidence, deliberate exclusions, recommended sequence, and acceptance direction.

Keep tools grouped by cohesive capability family with typed `action` values. Review granularity when there is evidence of incomplete native data modeling, model confusion, schema complexity, permission mismatch, false success, or weak error recovery.

Do not merge unrelated tools or weaken confirmation, permission, schema, timeout, and workspace boundaries to reduce tool count.

## Deferred Capability Extensions

Long-task progress, compact trace presentation, retry and recovery, and durable pause and resume are useful product extensions, but they are not current system-optimization priorities.

Reconsider them after the runtime tool integration, UI/runtime boundary cleanup, and related regression coverage are complete. When resumed, recovery must be defined before durable pause and resume so retries do not duplicate completed external side effects.

## Source-Verified Improvements

| Area | Improvement | Main source |
| --- | --- | --- |
| Text handling | UTF-8 compilation and a Gradle check for invalid UTF-8 and common mojibake markers. | [`app/build.gradle.kts`](../../app/build.gradle.kts) |
| File tools | Nine focused tools over `WorkspaceFileSystem`, with structured results, NIO attributes, bounded no-follow traversal, atomic text publication, verified copy, and recoverable move/delete behavior. Manual verification remains pending. | [`FileTools.kt`](../../app/src/main/java/com/palmclaw/tools/FileTools.kt); [`WorkspaceFileSystem.kt`](../../app/src/main/java/com/palmclaw/tools/WorkspaceFileSystem.kt) |
| Workspace text | Shared BOM/explicit/UTF-8/ICU decoding for read, append, edit, and grep; confidence metadata; explicit encoding for legacy mutation; charset and BOM preservation without mixed encodings. Focused tests and Android Studio compilation passed; APK size measurement remains pending. | [`WorkspaceTextCodec.kt`](../../app/src/main/java/com/palmclaw/tools/WorkspaceTextCodec.kt); [`FileTools.kt`](../../app/src/main/java/com/palmclaw/tools/FileTools.kt) |
| Calendar tools | Unified schema and provider boundary for recurrence, reminders, attendees, RSVP, calendar capabilities, and series or occurrence operations. Android Studio and device verification are pending. | [`AndroidPersonalTools.kt`](../../app/src/main/java/com/palmclaw/tools/AndroidPersonalTools.kt); [`AndroidCalendarGateway.kt`](../../app/src/main/java/com/palmclaw/tools/AndroidCalendarGateway.kt); [`CalendarRecurrenceCodec.kt`](../../app/src/main/java/com/palmclaw/tools/CalendarRecurrenceCodec.kt) |
| Bluetooth tools | Bounded BLE client with one verified GATT connection, profile inspection, characteristic read/write, explicit confirmation, structured errors, and isolated Android lifecycle handling. Manual verification remains pending. | [`BluetoothControlTool.kt`](../../app/src/main/java/com/palmclaw/tools/BluetoothControlTool.kt); [`AndroidBleClientGateway.kt`](../../app/src/main/java/com/palmclaw/tools/AndroidBleClientGateway.kt) |
| Notification tools | Stable namespaced agent notifications with status, list, post, update, cancel, settings recovery, and isolation from Cron and Always-on lifecycles. Manual verification remains pending. | [`NotificationControlTool.kt`](../../app/src/main/java/com/palmclaw/tools/NotificationControlTool.kt); [`AndroidNotificationGateway.kt`](../../app/src/main/java/com/palmclaw/tools/AndroidNotificationGateway.kt) |
| Tool interfaces | Cohesive capability tools with typed action arguments and structured results. | [`tools/`](../../app/src/main/java/com/palmclaw/tools) |
| Tool-result UI | Compact summaries, expandable details, and available reasoning presentation. | [`MessageUiProjector.kt`](../../app/src/main/java/com/palmclaw/ui/chat/MessageUiProjector.kt) |
| Composer UI | Multi-line growth, attachment presentation, and aligned send/stop controls. | [`ChatComposerBar.kt`](../../app/src/main/java/com/palmclaw/ui/chat/ChatComposerBar.kt) |
| Language policy | Reply in the language of the latest user message. | [`AGENT.md`](../../app/src/main/assets/templates/AGENT.md); [`ContextBuilder.kt`](../../app/src/main/java/com/palmclaw/agent/ContextBuilder.kt) |
| Execution control | Cancellation, bounded tool results, per-session coordination, and Always-on stop behavior. | [`AgentLoop.kt`](../../app/src/main/java/com/palmclaw/agent/AgentLoop.kt); [`SessionTurnCoordinator.kt`](../../app/src/main/java/com/palmclaw/runtime/SessionTurnCoordinator.kt); [`AlwaysOnGatewayService.kt`](../../app/src/main/java/com/palmclaw/runtime/AlwaysOnGatewayService.kt) |

## Evaluation-Only Engineering

Evaluation runners may create fresh sessions, clean evaluation-created state, and collect trajectories or usage metrics. These behaviors isolate benchmark tasks but are not PalmClaw product capabilities unless they are implemented and verified in the app source.

Generated runs, private traces, and task-specific cleanup scripts should not be committed to this public engineering documentation.

## Design Rule

Runtime and tool changes should provide reusable atomic capabilities. PalmClaw should not add task-specific shortcuts solely to pass an evaluation item.
