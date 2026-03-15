---
name: skill-creator
description: Create or update AgentSkills. Use when designing, structuring, or packaging skills with scripts, references, and assets.
---

# Skill Creator

Use this skill when you need to create or refine reusable skills.

## Core Principles

- Keep skills concise. Put only non-obvious, reusable knowledge.
- Keep `SKILL.md` focused on triggering and workflow guidance.
- Put large reference material in separate files.
- Prefer deterministic scripts for fragile repetitive tasks.

## Anatomy

Each skill should have:

1. `SKILL.md` (required)
2. Optional `scripts/`
3. Optional `references/`
4. Optional `assets/`

## Frontmatter

`SKILL.md` frontmatter should include:

- `name`
- `description`

## Process

1. Gather concrete usage examples.
2. Plan reusable resources.
3. Create/adjust folder structure.
4. Write/iterate `SKILL.md`.
5. Test with real requests and refine.
