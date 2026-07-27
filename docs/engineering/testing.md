# Testing and QA

Last reviewed: 2026-07-27

PalmClaw changes should be verified at the smallest relevant level during implementation and with the full unit-test suite before completion. User-visible runtime or UI changes also require a focused device or emulator check.

## Automated Checks

Run the relevant focused test class while editing, then run:

```bash
./gradlew :app:testDebugUnitTest
./gradlew :app:assembleDebug
```

Changes involving Room migrations, Android document readers, or platform APIs may also require connected tests:

```bash
./gradlew :app:connectedDebugAndroidTest
```

The application build runs `verifyTextEncoding` through `preBuild`. It rejects invalid UTF-8 in source text and common mojibake markers.

If the active shell has no Java runtime, use an installed JDK 17 through the platform-native Gradle wrapper. Keep machine-specific JDK, SDK, and device paths outside this documentation.

## Test Selection

| Change area | Minimum focused verification |
| --- | --- |
| Agent context or policy | `ContextBuilderTest` and relevant provider protocol tests |
| Runtime ownership or concurrency | `GatewayRuntimeSupervisorTest`, `RuntimeApplicationServiceTest`, `SessionTurnCoordinatorTest` |
| Runtime-owned tool callbacks or snapshots | Focused runtime tool-integration tests, `BuiltInToolCatalogTest`, `ToolArgumentsValidatorTest`, and relevant runtime tests |
| Tool schema or execution | `ToolArgumentsValidatorTest`, `BuiltInToolCatalogTest`, and tool-specific tests |
| Android calendar tools | `CalendarRecurrenceCodecTest`, `CalendarAttendeeReplacementPlannerTest`, `CalendarControlToolAndroidTest`, and the device checklist below |
| Android contacts tools | `ContactsMutationPlannerTest`, `ContactsToolSchemaTest`, `ContactsControlToolAndroidTest`, `ContactsMimeCodecAndroidTest`, `ContactsBatchOperationFactoryAndroidTest`, Android Studio compilation, and the device checklist below |
| Android Bluetooth and BLE | `BluetoothValueCodecTest`, `BluetoothControlToolTest`, Android Studio compilation, and the known-peripheral checklist below |
| Android agent notifications | `NotificationKeyCodecTest`, `NotificationControlToolTest`, `BuiltInToolCatalogTest`, Android Studio compilation, and the notification checklist below |
| Workspace text read/write/edit/grep | `WorkspaceTextCodecTest`, `FileToolsTextEncodingTest`, and `LocalFileReadSupportTest`, including precedence, BOM and ICU fixtures, the 49/50 confidence boundary, mutation guards, byte preservation, and newline handling |
| Workspace file interface and NIO behavior | `FileToolsContractTest`, `FileToolsTest`, `WorkspacePathResolverTest`, `BuiltInToolCatalogTest`, and `ToolArgumentsValidatorTest`; verify no-follow traversal, structured results, operation bounds, atomic publication, copy verification, and move recovery |
| Android document and PDF reading | `LocalFileReadSupportAndroidTest` and related document tests when Android libraries are involved |
| Chat projection or state | `MessageUiProjectorTest`, `ChatMessageRenderStateTest`, `ChatStateStoreTest` |
| Session switching or history | `ChatSessionCoordinatorEdgeCaseTest`, projection-cache and scroll-policy tests |
| Settings coordinators | Relevant coordinator, mapper, and structural guard tests |
| Storage schema | Room migration and integrity connected tests |

Android Studio compilation and the focused Calendar and Contacts automated tests passed in the manual verification reported on 2026-07-26. The provider-specific device checklists below remain pending.

## ICU APK Size Record

Use Android Studio APK Analyzer on the same build variant before and after ICU4J `78.3`. Record the APK file size and download-size estimate here after the manual build. This measurement is informational and does not block the encoding implementation.

| Build variant | Before ICU4J | After ICU4J | APK delta | Download-size delta |
| --- | ---: | ---: | ---: | ---: |
| Pending manual measurement | — | — | — | — |

## NIO Desugaring APK Size Record

Use APK Analyzer on the same build variant before and after replacing the default core-library
desugaring artifact with the NIO variant.

| Build variant | Default desugaring | NIO desugaring | APK delta | Download-size delta |
| --- | ---: | ---: | ---: | ---: |
| Pending manual measurement | — | — | — | — |

## Workspace File Manual QA

- On API 24/25 and a current Android device, run `find` on one file and a nested glob.
- Create UTF-8 text, append it, edit it with the returned revision, and verify the final bytes.
- Create an empty directory, copy a file into it, rename the copy, and verify the source behavior.
- Recursively copy a small directory and compare its contents before deleting the copy.
- Cancel recursive delete and overwrite confirmations; verify zero path changes.
- Repeat a non-overwrite write and move under `shared://`.
- With approved external storage, repeat find, copy, edit, move, and delete; verify every mutation
  requests confirmation.
- Place a symbolic link in a disposable directory where the device permits it; verify recursive
  find does not follow it and recursive mutation performs zero writes.
- Force or simulate a copy/move failure and verify source, destination, and any backup path match
  the structured result.

## Calendar Manual QA

Use a disposable test calendar where possible. If both local and synced calendars are available, repeat the core flow once for each provider.

- List calendars and verify visibility, write access, timezone, reminder limit, and allowed capability metadata.
- Create a timed event and an all-day event; read each back and verify the time range and timezone representation.
- Create weekly and monthly recurring events with an end condition, reminder, and attendee; verify expansion in the system Calendar app.
- Update an unrelated series field without passing recurrence, reminders, or attendees; verify those values are preserved.
- Replace reminders and attendees, then clear each with an explicit empty array.
- Edit one occurrence and verify other occurrences are unchanged. Repeat for one-occurrence deletion.
- Edit the full series and verify recurrence remains valid. Cancel a series deletion confirmation and verify no event changes, then confirm it once.
- Respond to an invited event. If account identity cannot be resolved uniquely, verify the tool returns the explicit system-Calendar fallback.
- Open an event through `open_event` and verify the correct event and occurrence time are shown.

## Contacts Manual QA

Use disposable contacts and record their `lookup_key`, RawContact ownership, and Data IDs before mutation.

- Create a default-account contact with multiple phones, emails, an address, an organization, and a birthday; verify the returned fields and system Contacts app.
- Update one phone by `data_id`; verify every other supported field and unsupported provider row remains unchanged.
- Remove one Data row; verify other Data rows and RawContacts remain.
- For a multi-account aggregate, add a value to an explicit writable `raw_contact_id`; verify the account owner does not change.
- Set a new super-primary phone and verify primary flags are consistent in PalmClaw and the system Contacts app.
- Cancel whole-contact deletion and verify no provider state changes. Confirm deletion once and verify every related writable RawContact disappears.
- Repeat the main create and exact-update flow with cloud sync enabled; verify values after sync settles.

## Bluetooth Manual QA

Use a known disposable BLE peripheral with documented readable and writable characteristics. Do
not invent protocol bytes during testing.

- Deny Bluetooth permissions, run a Bluetooth action, and verify an explicit permission result.
  Grant permissions through the offered system flow and retry.
- Scan and verify address, name when available, RSSI, device type, and bond state.
- Connect and inspect the GATT profile. Verify the returned service and characteristic UUIDs and
  properties against a trusted BLE inspection app or the peripheral documentation.
- Read a documented readable characteristic and compare its hex bytes. Verify `value_utf8` appears
  only when the complete value is valid UTF-8.
- Cancel a documented write and verify the peripheral state is unchanged.
- Confirm a write with response and verify `device_acknowledged=true` only after device
  acknowledgement.
- Perform a supported write without response and verify `device_acknowledged=false`.
- While connected, attempt to connect another device and verify `active_connection_exists`.
- Move the peripheral out of range during an operation and verify a structured disconnect or
  timeout result, then reconnect.
- Disconnect explicitly and verify `status` has no active connection.

## Notification Manual QA

Use disposable agent notification keys. Do not use Cron or Always-on notification identifiers.

- On Android 13 or later, deny `POST_NOTIFICATIONS`, attempt `post`, and verify an explicit
  permission result with zero active agent notifications.
- Grant permission through the offered flow, post a stable key, and verify title, text, channel,
  tap action, and structured result.
- Post the same key again and verify `notification_exists`.
- Update the active key and verify the notification is replaced rather than duplicated.
- Tap the notification and verify PalmClaw opens and the auto-cancelled key leaves
  `list_active`.
- Post with a short timeout and verify it disappears from `list_active` after expiry.
- Post a notification, restart PalmClaw, then list and cancel the same key.
- Dismiss a notification manually and verify update returns `notification_not_found`.
- Disable all PalmClaw notifications and the default channel separately; verify
  `notifications_disabled` and `channel_disabled`.
- Keep a Cron reminder and Always-on notification active while listing and cancelling an agent
  key; verify those notifications are unchanged.

## Chat UX Manual QA

Use this checklist after chat state, message projection, scrolling, composer, or long-running execution changes.

### Setup

- Install a debug build on a device or emulator.
- Configure one working provider.
- Prepare a long session with tool messages, a recently visited short session, and a new session.
- Keep filtered logcat available for diagnosis.

### Session switching

- Open the long session. Cached messages or a visible loading state should appear immediately.
- Switch to the recently visited session. The list should not flash empty or show messages from the previous session.
- Switch to the empty session. Previous messages should disappear immediately.
- Start work in one session and switch to another. Returning should show the correct active or terminal processing state.

### Sending and execution

- Send a short message. The user bubble should appear and the composer should clear immediately.
- During execution, the processing surface should remain visible until the assistant result appears or the current execution ends.
- Run a multi-tool request. Tool outcomes should appear incrementally rather than only after the complete turn.
- Stop an active request. The stop control should end local generation and remain available while work is active.
- Trigger a provider or tool timeout. Confirm that the transcript contains the current explicit timeout or error message.

### Keyboard and bottom insets

- Open the keyboard near the end of a conversation. The latest content and processing surface should remain above the composer.
- Close the keyboard. The list should settle without jumping to the top or losing its tail position.
- Grow the composer to multiple lines and add attachments. Send and stop controls should remain aligned and usable.

### History and trace presentation

- Load older history in a long session. Older messages should prepend without moving the visible anchor.
- Scroll away from the tail and wait for a tool update. The list should not force-scroll to the bottom.
- Resume follow-latest behavior through the latest-message control.
- Expand and collapse tool details. Compact summaries should remain readable and full details should be opt-in.
- Confirm that progress summaries do not expose secrets or unrestricted raw tool output.

### Always-on and channel continuity

- Start a turn through the foreground UI and confirm that only one runtime owns it.
- Exercise an enabled remote channel and confirm that session processing state matches local runtime state.
- Stop Always-on processing from the supported user control and confirm an explicit outcome.

The roadmap defines stronger terminal-state, recovery, and restart checks. Add them to this current regression checklist only after the related behavior is implemented.

## Regression Ownership Guide

| Symptom | Likely first owner |
| --- | --- |
| Blank or stale messages after session switch | `ChatSessionCoordinator`, `ChatMessageProjectionCache` |
| Delayed user bubble | `ChatStateStore`, session send coordination |
| Processing indicator flicker | `ChatMessageRenderState`, `GatewayProcessingCoordinator` |
| Wrong active state across sessions | Runtime status flow, `GatewayProcessingCoordinator` |
| Keyboard covers recent content | `ChatConversationPane`, `ChatMessageListPane` |
| History jumps during prepend | `ChatScrollState`, history restore effects |
| Tool summary does not match stored result | `MessageUiProjector` |
| Foreground and Always-on behavior differs | `RuntimeApplicationService`, `GatewayRuntimeSupervisor` |

Record a new regression case here only when it remains useful beyond one bug or device.
