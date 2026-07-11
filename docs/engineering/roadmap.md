# PalmClaw Engineering Roadmap

Last reviewed: 2026-07-11

This roadmap tracks reusable product and engineering improvements. It does not include task-specific shortcuts or evaluation-only instrumentation.

## Current Priorities

| Priority | Area | Status | Next outcome |
| --- | --- | --- | --- |
| P0 | Long-running execution | Planned | Expose a consistent progress and terminal-state model across normal and Always-on execution. |
| P0 | Long-task trace UI | Planned | Show current activity and recent outcomes without requiring raw log inspection. |
| P1 | Execution recovery | Planned | Define retry and recovery behavior before adding durable pause and resume. |
| P1 | Runtime/UI boundaries | Planned | Move remaining runtime and tool orchestration out of `ChatViewModel`. |
| P2 | File decoding verification | Planned | Add focused tests for supported legacy encodings and ambiguous byte sequences. |
| P2 | Tool granularity review | Deferred | Review capability families when concrete schema or usability problems appear. |

## Planned Work

### Long-running execution state

Current support includes user cancellation, maximum tool rounds, provider and tool timeouts, bounded tool-result storage, per-session turn serialization, and bounded cross-session concurrency.

The next step is a shared turn-progress model with at least:

- Session and turn identity.
- Lifecycle state: queued, running, completed, failed, timed out, or cancelled.
- Current round and configured maximum rounds.
- Current activity, such as waiting for the model or executing a named tool.
- Recent tool outcome summary.
- Start time and last update time.
- A user-facing next action for recoverable failures.

Acceptance conditions:

- Normal and Always-on modes report the same lifecycle states.
- Cancellation and timeout produce explicit terminal states.
- Switching sessions does not lose the active status of another session.
- Progress reporting does not store unrestricted tool output or reasoning text.

### Retry, recovery, pause, and resume

Retry and recovery must be defined before durable pause and resume. A retry should not duplicate a completed external side effect.

Acceptance conditions for the first recovery stage:

- Failures state whether retry is safe, unsafe, or requires user review.
- The UI can retry a failed model request without appending the same user message twice.
- Tool timeouts remain visible as tool outcomes instead of becoming a generic agent failure.
- Process restart does not present an interrupted turn as still running.

Durable pause and resume remain a later stage because they require persisted checkpoints and tool-specific side-effect rules.

### Long-task trace presentation

The current transcript supports compact tool summaries, expandable results, and available reasoning content. Long tasks still need a compact progress surface.

Acceptance conditions:

- The processing area shows the current activity and a bounded list of recent tool outcomes.
- Completed and failed tools are visually distinct.
- Raw logs are not required for normal progress inspection.
- Full tool details remain opt-in and do not make the default transcript noisy.
- Sensitive values in tool arguments and results are not surfaced by the progress summary.

### Runtime and UI boundary cleanup

Continue reducing `ChatViewModel` toward a UI facade. Prioritize duplicated runtime/tool callback orchestration and feature-specific snapshot assembly. Extract only when there is a clear owner and a testable interface.

Specific remaining seams from the earlier UI cleanup are channel discovery diagnostics, runtime and tool callback orchestration, and runtime, channel, and tool status snapshot assembly. The file was about 3,540 lines at this review. Reducing it toward 2,500 lines remains a directional indicator, not an acceptance condition by itself.

Acceptance conditions:

- `ChatViewModel` does not construct repositories, databases, or runtime owners.
- Runtime behavior remains shared between foreground and Always-on entry points.
- Extracted coordinators have focused unit tests.
- Chat session switching, optimistic sending, processing continuity, and settings scroll restoration remain unchanged.

### File decoding verification

Runtime file reading already uses BOM detection, strict decoding, and scored candidates for UTF-8, UTF-16, UTF-32, Big5, GBK, GB18030, Shift_JIS, and Windows-1252.

Remaining work is verification rather than initial implementation:

- Add representative fixtures for each supported encoding.
- Add invalid and binary-like inputs that must return explicit unsupported errors.
- Test ambiguous inputs where multiple decoders accept the same bytes.
- Confirm that reported charset metadata matches the selected decoder.

### Tool granularity review

Keep tools grouped by cohesive capability family with typed `action` values. Review granularity only when there is evidence of model confusion, schema complexity, permission mismatch, or weak error recovery.

Do not merge unrelated tools or weaken confirmation, permission, schema, timeout, and workspace boundaries to reduce tool count.

## Source-Verified Improvements

| Area | Improvement | Main source |
| --- | --- | --- |
| Text handling | UTF-8 compilation and a Gradle check for invalid UTF-8 and common mojibake markers. | [`app/build.gradle.kts`](../../app/build.gradle.kts) |
| File tools | Bounded delete, move, and rename operations. | [`FileTools.kt`](../../app/src/main/java/com/palmclaw/tools/FileTools.kt) |
| File reading | Strict multi-encoding decoding with explicit unsupported results. | [`LocalFileReadSupport.kt`](../../app/src/main/java/com/palmclaw/tools/LocalFileReadSupport.kt) |
| Calendar tools | Structured recurrence fields mapped to Android recurrence rules. | [`AndroidPersonalTools.kt`](../../app/src/main/java/com/palmclaw/tools/AndroidPersonalTools.kt) |
| Contact tools | Aggregate contact deletion through related raw-contact records with state verification. | [`AndroidPersonalTools.kt`](../../app/src/main/java/com/palmclaw/tools/AndroidPersonalTools.kt) |
| Bluetooth boundary | Explicit outcomes for direct and user-completed Bluetooth power changes. | [`AndroidBluetoothTools.kt`](../../app/src/main/java/com/palmclaw/tools/AndroidBluetoothTools.kt) |
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
