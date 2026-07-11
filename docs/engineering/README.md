# PalmClaw Engineering Documentation

This directory is the public engineering knowledge base for PalmClaw. It records how the app is structured, which reusable improvements are planned, how changes are verified, and why major engineering changes were made.

## Documents

- [Architecture](architecture.md): application layers, runtime ownership, agent-turn execution, persistence, and extension points.
- [Engineering roadmap](roadmap.md): source-verified completed work and the current reusable improvement backlog.
- [Testing and QA](testing.md): automated checks, build verification, and manual regression checklists.
- [Engineering history](history/README.md): completed initiatives retained for context after they leave the active roadmap.

## Maintenance Rules

Update these documents in the same change as the related implementation when any of the following occurs:

- A runtime, storage, provider, tool, channel, or UI boundary changes.
- A roadmap item is started, completed, replaced, or found to be already implemented.
- A regression requires a new automated test or manual QA case.
- A large refactor changes the main owner of a workflow.

Use one of these status labels in the roadmap:

- `Planned`: agreed work with no implementation yet.
- `In progress`: implementation has started but acceptance checks are incomplete.
- `Source-verified`: confirmed in the current source; automated or manual checks are listed separately when available.
- `Deferred`: useful work that is intentionally not in the current development stage.

Do not mark an item `Source-verified` based only on a benchmark run, demo, generated trace, or local experiment.

## Public Documentation Boundary

This directory is intended to be safe to publish with the repository. Do not include:

- API keys, tokens, cookies, passwords, or private endpoints.
- Personal account details, private channel or calendar data, or contact records.
- Device serial numbers or machine-specific absolute paths.
- Raw benchmark traces containing user data.
- Temporary evaluation scripts presented as product capabilities.

Machine-specific setup can be described using placeholders when it is useful to contributors. Security-sensitive findings should follow [SECURITY.md](../../SECURITY.md) instead of being recorded here.

## Writing Style

Keep engineering documents concise and source-grounded. State the current behavior, the owner in the codebase, the remaining problem, and a verifiable acceptance condition. Avoid release marketing and task-specific workarounds.
