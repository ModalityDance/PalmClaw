---
name: memory
description: Two-layer memory system with grep-based recall.
always: true
---

# Memory

## Structure

- `memory/MEMORY.md` — Long-term facts (preferences, project context, relationships).
- `memory/HISTORY.md` — Append-only event log. Search it by keyword.

## Search Past Events

Use file tools to search `memory/HISTORY.md` by keyword/regex.

## When to Update MEMORY.md

Write important facts immediately:
- User preferences
- Project context
- Key relationships

## Auto-consolidation

Older conversation turns can be summarized into HISTORY and MEMORY by consolidation logic.
