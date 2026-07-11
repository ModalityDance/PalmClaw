# Testing and QA

Last reviewed: 2026-07-11

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
| Tool schema or execution | `ToolArgumentsValidatorTest`, `BuiltInToolCatalogTest`, and tool-specific tests |
| File reading | `LocalFileReadSupportTest` and `LocalFileReadSupportAndroidTest` when Android libraries are involved |
| Chat projection or state | `MessageUiProjectorTest`, `ChatMessageRenderStateTest`, `ChatStateStoreTest` |
| Session switching or history | `ChatSessionCoordinatorEdgeCaseTest`, projection-cache and scroll-policy tests |
| Settings coordinators | Relevant coordinator, mapper, and structural guard tests |
| Storage schema | Room migration and integrity connected tests |

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
