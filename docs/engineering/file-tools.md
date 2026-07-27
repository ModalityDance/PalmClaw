# Workspace File Tool Contract

Last reviewed: 2026-07-27

PalmClaw exposes nine focused workspace tools:

`find`, `grep`, `read`, `write`, `edit`, `mkdir`, `copy`, `move`, and `delete`.

The interface does not expose one broad `file(action=...)` tool. `read`, `write`, and `edit`
remain separate content operations. `copy` and `move` remain separate because only move consumes
the source. `find` combines list, glob, and stat because all three query the same path-entry model.
Rename remains the same operation as move.

## Path model

Relative paths resolve under the active session workspace. `shared://` resolves under PalmClaw's
shared workspace. Explicit external-storage paths are accepted only under the configured external
root and only when Android grants the required access.

Every operation uses lexical resolution followed by no-follow validation of existing path
components. Traversal does not follow symbolic links. Workspace roots, the shared workspace root,
and the configured external-storage root are protected from deletion.

The current contract covers real filesystem paths. Storage Access Framework document URIs are a
separate future adapter and must not be mixed with `File` or `Path` semantics.

## Read-only tools

`find` returns a structured `base` entry and bounded `entries`. `max_depth=0` inspects only the
selected path. A glob `pattern` is matched relative to the selected directory. Results include
the scanned count and explicit truncation reason.

`read` returns bounded text, source type, line range, truncation state, source-file SHA-256
revision, and encoding information. `start_column` and the returned next line/column cursor avoid
losing the remainder of a long line when `max_chars` is reached. The file revision is checked
before and after extraction so the returned text and revision describe the same bytes. Existing
PDF, Office, and ODT extraction remains behind the same tool and is identified as extracted text.
One response returns at most 1,800 text characters so the complete structured JSON normally stays
within the runtime's default 5,000-character tool-result budget. Larger content is read through the
returned cursor instead of relying on runtime truncation, which would invalidate the JSON object.

`grep` returns one structured result per matching line with its path, line, first matching column,
line text, and matched text. This prevents repeated occurrences on one long line from consuming the
complete bounded response before later files are inspected. It independently decodes every file in
a directory search. An explicit encoding is accepted only for a single-file search. File count,
file size, total bytes, depth, and result count are bounded separately.

## Text mutation

`write.mode` is required:

- `create` fails if the target exists.
- `overwrite` publishes canonical UTF-8.
- `append` decodes the complete existing file, preserves its charset and BOM, appends in memory,
  then republishes the complete bytes.

`edit` supports literal or regular-expression replacement with `unique`, `first`, or `all`
occurrence policy. Unique matching is the default. `write` and `edit` accept an optional
`expected_revision`; a mismatch returns `file_changed` before writing.

Append and edit read at most 5 MB of existing text into memory. Larger files fail before decoding
or mutation; complete overwrite remains available when the caller intentionally supplies the full
replacement.

Encoding validation and representability checks finish before a temporary file is created. Text
is written to a sibling temporary file and synchronized. The revision or create-only condition is
checked again immediately before publication. Existing overwrite and append targets retain their
preflight revision guard even when the caller did not supply `expected_revision`; targets that
were absent at preflight use no-replace publication. Replacement uses an atomic move when
supported. Failure before publication leaves the original bytes unchanged.

## Path mutation

`mkdir` explicitly creates an empty directory and is idempotent by default.

`copy` supports regular files and bounded directory trees. Directory copy requires
`recursive=true`. It copies to a sibling staging path, verifies file sizes and SHA-256 values, and
then publishes the staged result. Existing destinations require `overwrite=true` and confirmation.
Source and destination tree identities are rechecked after confirmation and before replacement.

`move` first attempts a same-filesystem no-replace move. When that is unavailable, it uses
verified copy, moves the source to a sibling quarantine path, verifies that secured source against
the destination, and only then removes it. If source cleanup fails, the destination and secured
source path are reported with `partial=true`. A replaced destination is retained in a backup path
until the new destination is published and the source is removed.

`delete` preflights the complete bounded tree. Non-empty directories require `recursive=true` and
confirmation. Immediately before deletion it recomputes the tree identity; a target changed while
confirmation was open is rejected. An unreadable entry, symbolic link, unsupported file type,
path escape, or operation limit produces zero deletion.

## Deep module

`WorkspaceFileSystem` is the shared deep module behind the nine tools. It owns:

- lexical workspace resolution and protected roots;
- Java NIO file attributes and operations;
- bounded no-follow traversal;
- mutation entry, depth, and byte budgets;
- temporary publication, verification, backup, and recovery;
- structured path and mutation results.

`WorkspaceTextCodec` continues to own text encoding. `LocalFileReadSupport` continues to own
document extraction. Tool classes own typed arguments, confirmation policy, permission recovery,
and projection into bounded JSON results.

The app uses the NIO core-library desugaring variant so the same `java.nio.file` behavior is
available with PalmClaw's `minSdk 24`.

## Result and error contract

The primary tool content is bounded JSON because only `ToolResult.content` is sent back to the
model. Metadata contains the small fields needed by the UI and runtime.

Successful mutation results include `partial=false`, affected paths, entry and byte counts,
verification state, and whether publication was atomic. Errors include `code`, `message`,
`recoverable`, `next_step`, and `partial`.

Main file-specific errors include:

- `path_outside_workspace`
- `symbolic_link_not_allowed`
- `operation_limit_exceeded`
- `target_exists`
- `file_changed`
- `encoding_required_for_mutation`
- `verification_failed`
- `move_source_cleanup_failed`
- `move_source_changed`
- `move_recovery_required`
- `backup_cleanup_failed`
- `confirmation_unavailable`

## Verification status

Source implementation and focused tests are present. Android Studio unit tests, API 24/25
compatibility, debug build, APK-size comparison for NIO desugaring, and real-device external
storage checks remain pending user verification.
