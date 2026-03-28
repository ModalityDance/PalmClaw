---
name: text-encoding
description: Safely inspect and edit Chinese text resources, translation tables, and UI copy without introducing encoding corruption. Use when touching UiPreferences.kt, localized strings, SKILL.md text, README translations, or when terminal output looks garbled.
---

# Text Encoding

Use this skill whenever work involves Chinese copy or when terminal output suggests possible mojibake.

## Rules

- Treat terminal display and file content as different things.
- Verify suspicious text by reading the file as UTF-8 before concluding the file is broken.
- Prefer editing text files with `apply_patch`.
- Do not use shell rewrite commands for text files unless the change is narrowly scoped and encoding-safe.
- Do not add fallback logic to hide encoding problems. Fix the source text instead.

## Verification Workflow

1. If console output looks garbled, read the file again with explicit UTF-8.
2. Compare the actual file content, not the terminal rendering.
3. If the file is truly corrupted, restore from a known-good git revision instead of hand-fixing large mojibake blocks.
4. After edits, re-read the changed file as UTF-8 and spot-check the exact strings that were touched.

## Preferred Recovery

- For translation tables or large Chinese copy blocks, prefer restoring the whole file from git history.
- Only do manual line edits when the source file is already clean and the edit scope is small.

## Translation Table Policy

- Route reusable UI copy through the translation table.
- Do not mix direct inline bilingual strings with table-driven strings unless there is a deliberate exception.
- When adding a new UI label, ensure the English key exists in the translation table in the same change.

## Red Flags

- Strings like `闁`, `閸`, `濞`, `鍙`, `鈧`
- Large sections where punctuation or Chinese quotes become malformed
- A file that looks wrong in PowerShell but correct when read as UTF-8

## Done Criteria

- Changed text reads correctly from the file itself in UTF-8.
- No new inline copy bypasses the translation table without reason.
- No encoding workaround code was added.
