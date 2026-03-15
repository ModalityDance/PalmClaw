---
name: android-file
description: Operate workspace file and code workflows with list, glob, read, write, edit, and grep tools, including safe sequencing, sandbox boundaries, and permission recovery. Use for file inspection, code editing, and text search tasks.
---

# Android File

Use the file tool set: `list`, `glob`, `read`, `write`, `edit`, `grep`.

## Tool Map

- `list`: enumerate files/dirs
- `glob`: find by pattern
- `read`: read text with range limits
- `write`: overwrite or append text
- `edit`: deterministic find/replace updates
- `grep`: search content across files

## Default Automation Policy

- Run without extra pre-confirmation.
- Use precise paths under workspace sandbox.
- Keep `open_settings_if_failed=true` and `wait_user_confirmation=true` only for permission recovery.

## Editing Playbook

1. Discover with `list` or `glob`.
2. Inspect with `read`.
3. Update with `edit` when pattern is stable.
4. Use `write` when replacing full content.
5. Validate with `read` or `grep`.

## Common Calls

- `list(path=".", recursive=true, max_depth=3, limit=200)`
- `glob(pattern="**/*.kt", path=".", files_only=true, limit=200)`
- `read(path="app/src/main/java/...", start_line=1, max_lines=200)`
- `edit(path="...", find="old", replace="new", all=false)`
- `grep(query="requestUserConfirmation", path="app/src/main/java", regex=false, limit=200)`

## Failure Recovery

- `path_outside_workspace`: move operation under workspace root.
- `ambiguous_match`: refine pattern or set `all=true` intentionally.
- `permission_denied`: allow settings recovery and retry same action.