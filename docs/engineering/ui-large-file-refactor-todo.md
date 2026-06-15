# UI Large File Refactor TODO

Last updated: 2026-06-12

## Goal

Reduce the maintenance and performance risk from the remaining oversized UI/ViewModel files:

- `app/src/main/java/com/palmclaw/ui/ChatScreen.kt`
- `app/src/main/java/com/palmclaw/ui/settings/SettingsContent.kt`
- `app/src/main/java/com/palmclaw/ui/chat/ChatViewModel.kt`

This refactor should keep user-visible behavior stable while moving each major workflow into smaller, testable, feature-scoped units.

## Current State

- `ChatScreen.kt` is still the main owner of chat layout, session drawer, message list, scroll behavior, dialogs, session channel settings, permission launchers, media preview, settings routing, and snackbar presentation.
- `SettingsContent.kt` still owns the settings shell plus provider, tools/search, channels, cron, heartbeat, MCP, guide, permissions/about, and several page-local states.
- `ChatViewModel.kt` already delegates to several coordinators, but it still contains many internal implementations, mapping helpers, runtime/channel/status assembly helpers, and compatibility facade methods.

## Refactor Principles

- Prefer workflow boundaries over mechanical file splitting.
- Extract UI first without changing behavior; optimize behavior only after the extracted boundary is stable.
- Each extracted composable should receive a focused state object and an action object or explicit callbacks.
- Keep sensitive drafts out of `rememberSaveable`.
- Preserve existing UX regressions that were recently fixed: immediate session switching, optimistic sent messages, continuous processing bubble, settings scroll position, and skill download/install feedback.
- Do not reintroduce collection of the full `ChatUiState` in `ChatScreen`.

## Phase 1: Extract Chat Message List

Status: DONE

Completed in this phase:

- Extracted the main chat transcript list into `ChatMessageListPane`.
- Kept composer, attachment opening, and audio preview lifecycle in `ChatScreen`.
- Preserved existing session switch, processing bubble, scroll-to-latest, and older-history behavior.

Target files:

- Add `app/src/main/java/com/palmclaw/ui/chat/ChatMessageListPane.kt`
- Keep message models in `MessageUiModels.kt`
- Reuse `ChatMessageComponents.kt`, `ChatMediaComponents.kt`, and `ChatScrollOverlay.kt`

Scope:

- Move the `LazyColumn` message rendering out of `ChatScreen`.
- Move history loading trigger UI and older-message loading rows.
- Move assistant/tool/user bubble rendering orchestration.
- Move expanded tool-message UI state if it can remain local to the pane.
- Move scroll-to-latest affordance and near-tail calculations if practical.
- Keep `ChatScreen` responsible only for passing `ChatContentState`, language/theme flags, attachment open actions, and message actions.

Acceptance:

- `ChatScreen.kt` no longer contains the main message `LazyColumn`.
- Session switching still shows cached/recent messages immediately.
- Sending a message still displays the user bubble optimistically.
- Processing bubble stays visible until the assistant reply appears.
- Loading older messages preserves scroll anchor.

Verification:

- Run focused unit tests around `ChatSessionCoordinator` and message projection.
- Manually test: long session open, switch sessions, send message, tool-call stream, load older history.

## Phase 2: Extract Session Settings Sheet

Status: DONE

Completed in this phase:

- Extracted the session settings dialog into `SessionSettingsSheet`.
- Moved channel binding forms, diagnostics, and local sheet UI state out of `ChatScreen`.
- Kept session id/page ownership and draft initialization in `ChatScreen` for compatibility.

Target files:

- Add `app/src/main/java/com/palmclaw/ui/chat/SessionSettingsSheet.kt`
- Add focused UI state/action types if needed, for example `SessionSettingsSheetState` and `SessionSettingsSheetActions`

Scope:

- Move session settings dialog/sheet from `ChatScreen`.
- Move channel binding form UI for Telegram, Discord, Slack, Feishu, Email, and WeCom.
- Move session diagnostics view.
- Move local sheet-only expanded menu and advanced-section states.
- Keep secret input drafts non-saveable.

Acceptance:

- `ChatScreen.kt` only owns open/close state and selected session id.
- Existing channel binding save/detect flows behave the same.
- Back handling inside session settings remains correct.
- No sensitive token/password field uses `rememberSaveable`.

Verification:

- Manually test binding setup for at least local, Telegram, Discord, Email, and one enterprise channel.
- Confirm dismiss/back behavior from each nested page.

## Phase 3: Extract Provider Settings Page

Status: DONE

Completed in this phase:

- Extracted the provider settings workflow into `ProviderSettingsPage`.
- Moved provider list, editor dialog, test/save feedback, and token usage card out of `SettingsContent`.
- Kept settings page scroll restoration and shared confirmation dialog owned by `SettingsContent`.
- Kept provider API key draft in coordinator state instead of local saveable UI state.

Target files:

- Add `app/src/main/java/com/palmclaw/ui/settings/ProviderSettingsPage.kt`
- Reuse `ProviderUiModels.kt`, `ProviderUiHelpers.kt`, and common settings components.

Scope:

- Move provider list, active provider selection, add/edit/delete provider UI.
- Move provider editor dialog.
- Move provider test/save feedback flow.
- Move provider token usage card.

Acceptance:

- `SettingsContent.kt` delegates `SettingsPanelPage.Provider` to `ProviderSettingsPage`.
- Provider add/edit/delete/test/save behavior is unchanged.
- API key draft remains non-saveable or otherwise avoids saved instance state persistence.
- Provider page scroll position still restores when navigating away and back.

Verification:

- Manually test add provider, edit provider, set active, delete provider, test provider, save provider.
- Run existing provider/settings coordinator tests if available.

## Phase 4: Extract Tools And Search Settings

Status: DONE

Completed in this phase:

- Extracted the built-in tools and search provider settings workflow into `ToolSettingsPage`.
- Moved search provider editor cards and transient feedback out of `SettingsContent`.
- Kept Tools page scroll restoration owned by `SettingsContent`.
- Kept search API key editor drafts as non-saveable local state.

Target files:

- Add `app/src/main/java/com/palmclaw/ui/settings/ToolSettingsPage.kt`
- Optionally move `SearchProviderSettingsCard` into the same file.

Scope:

- Move built-in tool enable toggles.
- Move search provider selection and API key cards.
- Keep transient search provider feedback local to the tools page.

Acceptance:

- `SettingsContent.kt` delegates `SettingsPanelPage.Tools` to `ToolSettingsPage`.
- Search provider feedback and save behavior stay unchanged.
- Search API key drafts are not persisted in saved instance state.

Verification:

- Manually test enabling/disabling tools and switching/saving search providers.

## Phase 5: Extract Runtime Automation Settings

Status: DONE

Completed in this phase:

- Extracted Cron and Heartbeat settings into `AutomationSettingsPage`.
- Moved cron log show/hide state and cron confirmation requests out of `SettingsContent`.
- Moved `AlwaysOnModeContent` into `AlwaysOnSettingsPage` as an isolated page component.
- Kept shared settings scroll restoration and confirmation dialog owned by `SettingsContent`.

Target files:

- Add `app/src/main/java/com/palmclaw/ui/settings/AutomationSettingsPage.kt`
- Add `app/src/main/java/com/palmclaw/ui/settings/CronSettingsPage.kt` if the combined file grows too large.
- Add `app/src/main/java/com/palmclaw/ui/settings/HeartbeatSettingsPage.kt` if the combined file grows too large.

Scope:

- Move Cron settings, cron job list, cron logs, and cron actions.
- Move Heartbeat settings and heartbeat action entry points.
- Move Always-on settings if it remains coupled to automation, or keep existing `AlwaysOnModeContent` as a separate page.

Acceptance:

- `SettingsContent.kt` delegates Cron and Heartbeat pages.
- Cron log show/hide state and refresh behavior stay intact.
- Always-on page remains readable and isolated.

Verification:

- Manually test save cron config, run job now, enable/disable job, clear logs, trigger heartbeat, open heartbeat editor.

## Phase 6: Extract Channels And MCP Settings

Status: DONE

Completed in this phase:

- Extracted channel route overview and diagnostics into `ChannelSettingsPage`.
- Extracted MCP remote and server editor UI into `McpSettingsPage`.
- Kept shared settings scroll restoration and confirmation dialog owned by `SettingsContent`.
- Kept MCP token reveal behavior and existing save/validation flow unchanged.

Target files:

- Add `app/src/main/java/com/palmclaw/ui/settings/ChannelSettingsPage.kt`
- Add `app/src/main/java/com/palmclaw/ui/settings/McpSettingsPage.kt`

Scope:

- Move connected channel overview and per-session channel status list.
- Move global channel settings if present.
- Move MCP enable/server list/edit/remove UI.

Acceptance:

- `SettingsContent.kt` delegates Channels and MCP pages.
- MCP cleartext-localhost behavior remains as currently intended.
- Session channel enable/disable from settings still works.

Verification:

- Manually test channel status refresh, per-session enable/disable, MCP server add/edit/remove/save.

## Phase 7: Thin ChatViewModel Further

Status: DONE

Completed in this phase:

- Extracted provider validation/config mapping into `ProviderSettingsMapper`.
- Extracted MCP config normalization, localhost cleartext validation, and status snapshot mapping into `McpSettingsMapper`.
- Moved skill staging, local import, staged install, and delete flows into `SkillSettingsCoordinator`.
- Extracted skill UI/domain mapping into `SkillSettingsMapper`.
- Moved session channel binding save/draft logic into `ChannelBindingCoordinator`.
- Extracted connected channel overview/status assembly into `ConnectedChannelOverviewAssembler`.
- Reduced `ChatViewModel.kt` below the first target of 3500 lines.

Remaining for later cleanup:

- Move channel discovery diagnostics out of `ChatViewModel`.
- Move runtime/tool callback orchestration into smaller domain coordinators.
- Continue reducing `ChatViewModel.kt` toward the later 2500-line target.

Target files:

- `ChatViewModel.kt`
- Existing coordinators under `ui/chat` and `ui/settings`
- Add small mapper/assembler files only where a clear domain boundary exists.

Scope:

- Move remaining provider validation/build helpers into `ProviderSettingsCoordinator` or a provider settings mapper.
- Move skill staging/install/delete implementation fully into `SkillSettingsCoordinator`.
- Move channel binding discovery/save implementation fully into `ChannelBindingCoordinator`.
- Move runtime status formatting and gateway status assembly into focused formatter/assembler classes.
- Keep `ChatViewModel` as a thin facade for UI events and `StateFlow` exposure.

Acceptance:

- No new repository/service construction in `ChatViewModel`.
- Most public ViewModel methods are one-line delegations or simple UI shell actions.
- Internal methods in `ChatViewModel` are limited to cross-feature orchestration that genuinely needs multiple coordinators.
- File size trends below 3500 lines first, then below 2500 lines in later cleanup.

Verification:

- Run coordinator tests.
- Manually smoke test full chat, settings, skills, runtime, channel binding flows.

## Phase 8: Add Structural Guard Tests

Status: DONE

Completed in this phase:

- Added source-level structural guard tests for the remaining large UI/ViewModel entry files.
- Guarded against collecting the full legacy `ChatUiState` in `ChatScreen`.
- Guarded against moving extracted message/session/settings pages back into shell files.
- Guarded against sensitive token/password/secret drafts being persisted with `rememberSaveable`.
- Guarded against direct repository/service construction returning to `ChatViewModel`.

Note:

- These are pragmatic source guards intended to catch major regressions. They are not a replacement for focused unit, integration, or manual UX tests.

Scope:

- Add lightweight source-structure tests to prevent regression into giant entry files.
- Guard against `ChatScreen` collecting full `ChatUiState`.
- Guard against sensitive drafts using `rememberSaveable`.
- Guard against direct repository/service creation inside `ChatViewModel`.

Acceptance:

- Tests fail when major extracted workflows are moved back into `ChatScreen.kt` or `SettingsContent.kt`.
- Tests remain pragmatic and do not block normal UI copy/layout changes.

Suggested checks:

- `ChatScreen.kt` should not contain the main message-list `LazyColumn` after Phase 1.
- `SettingsContent.kt` should not contain provider editor implementation after Phase 3.
- `ChatScreen.kt` should not contain `collectAsStateWithLifecycle()` for full `uiState`.
- `ChatViewModel.kt` should not instantiate database/repository/runtime dependencies directly.

## Completion Definition

This item is considered complete when:

- `ChatScreen.kt` is primarily a top-level shell that wires drawer, chat pane, settings pane, and dialogs.
- `SettingsContent.kt` is primarily a settings router/shell.
- `ChatViewModel.kt` is primarily a facade over focused coordinators and state stores.
- Major feature pages can be edited independently without touching unrelated chat/settings flows.
- Current performance-sensitive UX remains stable: session switch, send-message optimism, processing bubble continuity, history loading, settings scroll restoration, and skill download/install feedback.
