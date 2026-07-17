# PalmClaw Engineering Roadmap

Last reviewed: 2026-07-17

This roadmap tracks reusable product and engineering improvements. It does not include task-specific shortcuts or evaluation-only instrumentation.

## Current Priorities

| Priority | Area | Status | Next outcome |
| --- | --- | --- | --- |
| P0 | Workspace text codec | Source and tests updated | Verify the ICU-backed policy through local unit tests and Android Studio compilation. |
| P0 | Text round-trip verification | Pending manual run | Run the focused codec and file-action tests, then record the APK size delta. |
| P1 | Runtime tool integration | Planned | Consolidate duplicated runtime, heartbeat, session, channel, and MCP tool callbacks and snapshots behind one runtime-owned boundary. |
| P1 | Runtime/UI boundaries | Planned | Make `ChatViewModel` delegate runtime actions and status projection without constructing tool registries or runtime-domain snapshots. |
| P2 | `GatewayRuntime` boundaries | Planned | Separate tool integration and channel or automation lifecycle management from the central runtime coordinator. |
| P2 | Refactor verification | In progress | Add focused tests and structural guards for each extracted boundary while keeping the full unit suite and debug build green. |
| P2 | Tool granularity review | Deferred | Review capability families when concrete schema or usability problems appear. |
| P2 | Long-task capabilities | Deferred | Reconsider progress, trace, recovery, pause, and resume after the core runtime and UI boundaries are stable. |

## Planned Work

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

Automated execution remains pending because this change is being compiled and tested manually through Android Studio.

### Runtime tool integration

`ChatViewModel` and `GatewayRuntime` both contain callback wiring and snapshot construction for runtime settings, heartbeat, sessions, channels, and MCP status. This duplication increases maintenance cost and can allow foreground and Always-on behavior to diverge.

The next implementation step is a runtime-owned tool integration boundary that:

- Owns runtime-tool callback registration and cleanup.
- Builds typed snapshots without depending on UI state models.
- Applies shared validation and persistence rules.
- Exposes explicit refresh signals to the UI instead of calling UI helpers.
- Is used by both normal and Always-on execution paths.

Acceptance conditions:

- Runtime, heartbeat, session, channel, and MCP tool snapshots have one implementation owner.
- `ChatViewModel` does not create a `ToolRegistry` or wire callbacks for runtime-owned tools.
- Normal and Always-on modes use the same tool integration behavior.
- Callback lifecycle and error conversion have focused unit tests.

### Runtime and UI boundary cleanup

Continue reducing [`ChatViewModel`](../../app/src/main/java/com/palmclaw/ui/chat/ChatViewModel.kt) toward a UI facade. It remains about 3,540 lines and still imports concrete channel adapters and built-in runtime tools. Extract stable workflow owners instead of splitting by file size.

After runtime tool integration is shared, the next UI seams are channel discovery diagnostics, runtime status observation, connected-channel projection, and runtime refresh requests.

Acceptance conditions:

- `ChatViewModel` does not construct repositories, databases, or runtime owners.
- `ChatViewModel` does not build runtime-domain tool snapshots or own runtime-tool callback wiring.
- Runtime behavior remains shared between foreground and Always-on entry points.
- Extracted coordinators have focused unit tests.
- Chat session switching, optimistic sending, processing continuity, and settings scroll restoration remain unchanged.

### `GatewayRuntime` boundary cleanup

[`GatewayRuntime`](../../app/src/main/java/com/palmclaw/runtime/GatewayRuntime.kt) is about 2,245 lines and currently coordinates agent turns, runtime-owned tools, channel adapters, cron, heartbeat, MCP, subagents, attachment delivery, and remote delivery state.

Keep agent-turn ownership and top-level lifecycle coordination in `GatewayRuntime`. Move capability-specific setup and cleanup behind focused services when the ownership boundary is stable. Start with runtime tool integration, then review channel adapter lifecycle and automation wiring separately.

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

### Tool granularity review

Keep tools grouped by cohesive capability family with typed `action` values. Review granularity only when there is evidence of model confusion, schema complexity, permission mismatch, or weak error recovery.

Do not merge unrelated tools or weaken confirmation, permission, schema, timeout, and workspace boundaries to reduce tool count.

## Deferred Capability Extensions

Long-task progress, compact trace presentation, retry and recovery, and durable pause and resume are useful product extensions, but they are not current system-optimization priorities.

Reconsider them after the runtime tool integration, UI/runtime boundary cleanup, and related regression coverage are complete. When resumed, recovery must be defined before durable pause and resume so retries do not duplicate completed external side effects.

## Source-Verified Improvements

| Area | Improvement | Main source |
| --- | --- | --- |
| Text handling | UTF-8 compilation and a Gradle check for invalid UTF-8 and common mojibake markers. | [`app/build.gradle.kts`](../../app/build.gradle.kts) |
| File tools | Bounded delete, move, and rename operations with protected roots, no-follow recursive deletion, destructive confirmation, target-conflict handling, and verified cross-filesystem file moves. | [`FileTools.kt`](../../app/src/main/java/com/palmclaw/tools/FileTools.kt) |
| Workspace text | Shared BOM/explicit/UTF-8/ICU decoding for read, append, edit, and grep; confidence metadata; explicit confirmation for legacy mutation; charset and BOM preservation without mixed encodings. Test execution and APK size measurement remain pending. | [`WorkspaceTextCodec.kt`](../../app/src/main/java/com/palmclaw/tools/WorkspaceTextCodec.kt); [`FileTools.kt`](../../app/src/main/java/com/palmclaw/tools/FileTools.kt) |
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
