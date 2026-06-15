# Chat UX Manual QA

Use this checklist after chat state, projection, or scrolling changes. The goal is to catch regressions that unit tests cannot fully cover: perceived latency, keyboard insets, scroll jumps, and processing continuity.

## Setup

- Install a debug build on a device or emulator.
- Configure one working provider.
- Prepare at least three sessions:
  - A long session with many user, assistant, and tool messages.
  - A recently visited short session.
  - A new or empty session.
- Keep logcat open while testing:

```bash
adb logcat | grep -i palmclaw
```

Optional frame check:

```bash
adb shell dumpsys gfxinfo com.palmclaw
```

## Session Switching

- Open the long session.
  - Pass: existing cached messages or a visible loading row appears immediately.
  - Fail: the previous session remains visible, or the screen is blank for a noticeable delay.
- Switch to a previously visited session.
  - Pass: cached messages render immediately and `messagesLoading` is not visible.
  - Fail: the list flashes empty before messages appear.
- Switch to a new empty session.
  - Pass: the old session messages disappear immediately and the empty/loading state is clear.
  - Fail: old messages remain while the new session loads.

## Sending And Processing

- Type a short message and tap send.
  - Pass: the user bubble appears immediately, and the input clears immediately.
  - Fail: the UI waits for database/runtime work before showing the user bubble.
- While the agent is working:
  - Pass: the processing bubble appears promptly and stays visible.
  - Fail: the processing bubble disappears before the final assistant message appears.
- Trigger a request that uses multiple tools.
  - Pass: tool messages appear incrementally as they arrive.
  - Fail: all tool messages appear only after the whole turn finishes.

## Keyboard And Bottom Insets

- Open the keyboard near the end of a conversation.
  - Pass: the latest message and processing bubble move above the composer.
  - Fail: the composer or keyboard covers the latest content.
- Close the keyboard.
  - Pass: the list settles without jumping to the top or losing the tail position.

## History Loading

- Scroll upward in a long session until older history loads.
  - Pass: the loading row appears, older messages prepend, and the visible anchor stays stable.
  - Fail: the list jumps up/down or snaps to the bottom.
- Scroll away from the tail, then wait for a tool/update message.
  - Pass: the list does not force-scroll to bottom.
  - Pass after tapping latest: follow-latest resumes.

## Regression Notes

When a failure appears, narrow the likely owner before changing code:

- Session switch blank/stale messages: `ChatSessionCoordinator` or `ChatMessageProjectionCache`.
- User bubble delay: `ChatStateStore.commitOptimisticSend()` or `ChatSessionCoordinator.sendMessage()`.
- Processing bubble flicker: `ChatMessageRenderState`.
- Keyboard/tail obstruction: `ChatConversationPane`, `ChatMessageListPane`, or scroll clearance constants.
- History jump: `ChatScrollState` and history restore effects.
