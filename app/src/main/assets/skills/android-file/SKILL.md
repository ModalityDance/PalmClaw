---
name: android-file
description: Operate workspace file and code workflows with list, glob, read, write, edit, grep, delete, and move tools, including safe sequencing, sandbox boundaries, and permission recovery. Use for file inspection, code editing, text search, deletion, moving, and renaming tasks.
---

# Android File

Use the file tool set: `list`, `glob`, `read`, `write`, `edit`, `grep`, `delete`, `move`.

## Tool Map

- `list`: enumerate files/dirs
- `glob`: find by pattern
- `read`: read text with range limits
- `write`: overwrite or append text
- `edit`: deterministic find/replace updates
- `grep`: search content across files
- `delete`: delete a file or an empty directory; non-empty directories require `recursive=true`
- `move`: move or rename a file or directory; existing destinations are not overwritten by default

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

For destructive file changes:

1. Inspect the target with `list` or `read` first.
2. Use `delete` without `recursive` for files and empty directories.
3. Set `recursive=true` only when the user requested removal of a reviewed non-empty directory.
4. Use `move` for both relocation and rename. Set `create_parent=true` only when the destination parent should be created.
5. Do not set `overwrite=true` unless replacing the reviewed destination is intended.

Recursive deletion and destination overwrite require user confirmation. File moves can fall back to verified copy-and-delete across filesystems. Directory moves must remain on one filesystem; move their files separately when storage volumes differ.

## Common Calls

- `list(path=".", recursive=true, max_depth=3, limit=200)`
- `glob(pattern="**/*.kt", path=".", files_only=true, limit=200)`
- `read(path="app/src/main/java/...", start_line=1, max_lines=200)`
- `edit(path="...", find="old", replace="new", all=false)`
- `grep(query="requestUserConfirmation", path="app/src/main/java", regex=false, limit=200)`
- `delete(path="scratch/old.txt")`
- `move(source="draft.txt", destination="archive/final.txt", create_parent=true)`

## Failure Recovery

- `path_outside_workspace`: move operation under workspace root.
- `directory_not_empty`: inspect the directory, then use `recursive=true` only when recursive deletion is intended.
- `target_exists`: choose another destination or use `overwrite=true` only after reviewing the target.
- `directory_move_failed`: choose a writable directory destination on the same storage volume, or move files separately.
- `ambiguous_match`: refine pattern or set `all=true` intentionally.
- `permission_denied`: allow settings recovery and retry same action.
