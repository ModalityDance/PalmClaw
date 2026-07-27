---
name: android-file
description: Operate workspace files with find, grep, read, write, edit, mkdir, copy, move, and delete, including structured results, encoding preservation, atomic text updates, sandbox limits, and permission recovery.
---

# Android File

Use the nine focused workspace tools:

- `find`: inspect one path, list a directory, or find paths with a glob pattern
- `grep`: search decoded text content
- `read`: read text or extract supported document text
- `write`: explicitly create, overwrite, or append text
- `edit`: replace a verified text match
- `mkdir`: create an empty directory
- `copy`: copy a file or directory while preserving the source
- `move`: move or rename a file or directory
- `delete`: delete a file or directory only when the user explicitly requests removal

## Discovery and inspection

Use `find` for path and metadata questions:

- `find(path=".")` lists direct children.
- `find(path="README.md", max_depth=0)` inspects one file.
- `find(path="app/src", pattern="**/*.kt", max_depth=12, kind="file")` replaces glob.

`find` returns structured `base` and `entries`. It does not follow symbolic links. Check
`truncated` and `truncation_reason` before assuming the result is complete.

Use `grep` only for file contents. Directory grep detects every file independently; never pass
one `encoding` for a directory tree.

## Reading and editing

1. Inspect or locate paths with `find`.
2. Read the relevant range with `read`; continue with both `next_start_line` and
   `next_start_column` whenever the bounded response is truncated.
3. Keep the returned `revision` when concurrent modification is possible.
4. Use `edit` for a stable local replacement.
5. Use `write` only when creating or replacing complete content.
6. Validate with `read` or `grep`.

`write.mode` is required:

- `create`: fail if the target exists
- `overwrite`: replace the whole file with canonical UTF-8
- `append`: preserve the existing charset and BOM

`edit` defaults to `occurrence="unique"`. Use `first` or `all` only when that scope is intended.
Legacy text detected statistically requires an explicit `encoding` before append or edit.

## Path mutations

- Prefer non-destructive operations. Unless the user's current request explicitly requires
  deletion, do not call `delete`. Do not delete files merely to tidy the workspace; leave them
  intact and report them when needed.
- Use `mkdir` for empty directories.
- Use `copy` when the source must remain.
- Use `move` for relocation and rename.
- Directory copy requires `recursive=true`.
- Existing destinations are rejected unless `overwrite=true`.
- Non-empty directory deletion requires `recursive=true`.

Recursive delete, destination overwrite, and external shared-storage modification require user
confirmation. Cancellation performs no write.

## Common calls

- `find(path=".", pattern="**/*.md", max_depth=4, kind="file")`
- `read(path="README.md", start_line=1, max_lines=200)`
- `grep(query="WorkspacePathResolver", path="app/src", file_glob="**/*.kt")`
- `write(path="notes.md", text="...", mode="create")`
- `edit(path="notes.md", find="old", replace="new", occurrence="unique")`
- `mkdir(path="artifacts/output")`
- `copy(source="report.md", destination="archive/report.md", create_parent=true)`
- `move(source="draft.md", destination="final.md")`
- `delete(path="scratch/old", recursive=true)`

## Failure recovery

- `path_outside_workspace`: use the current workspace or `shared://`.
- `symbolic_link_not_allowed`: use the real workspace path.
- `operation_limit_exceeded`: split a large directory operation.
- `target_exists`: choose another target or explicitly request overwrite.
- `file_changed`: read again and retry with the new revision.
- `encoding_required_for_mutation`: pass the verified legacy encoding.
- `move_recovery_required`: inspect the reported source, destination, and backup paths before retrying.
- `confirmation_unavailable`: open the app UI and retry.
