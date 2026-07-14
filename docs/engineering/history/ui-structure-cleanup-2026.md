# UI Structure Cleanup (2026)

Status: completed initial phases; continued cleanup is tracked in the engineering roadmap.

## Goal

Reduce maintenance risk in `ChatScreen`, `SettingsContent`, and `ChatViewModel` while keeping user-visible behavior stable.

## Completed Boundaries

- Extracted the main transcript into `ChatMessageListPane`.
- Extracted session settings into `SessionSettingsSheet`.
- Extracted provider, tool/search, automation, Always-on, channel, and MCP settings pages.
- Added focused provider, MCP, skill, channel-binding, and status mapping or coordination classes.
- Added structural guard tests against moving major workflows back into shell files, collecting the full legacy UI state, saving sensitive drafts with `rememberSaveable`, or constructing repositories and runtime services in `ChatViewModel`.

## Behavior Preserved

- Immediate session switching with cached or loading state.
- Optimistic display of sent user messages.
- Continuous processing indication until a terminal result.
- Stable older-history loading and scroll position.
- Settings scroll restoration.
- Skill download and installation feedback.
- Non-saveable secret input drafts.

## Remaining Lesson

The first extraction reduced UI shell responsibilities, but `ChatViewModel` remains large and still contains runtime and tool coordination helpers. Future work should extract stable workflow owners with focused tests. File size alone is not an interface boundary.

Current work belongs in [the engineering roadmap](../roadmap.md), not in this historical record.
