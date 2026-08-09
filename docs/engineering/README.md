# PalmClaw Engineering Documentation

This directory is the public engineering knowledge base for PalmClaw. It records the current architecture, active engineering work, verification policy, and stable tool contracts.

## Documents

- [Architecture](architecture.md): application layers, runtime ownership, agent-turn execution, persistence, and extension points.
- [Engineering roadmap](roadmap.md): source-verified completed work and the current reusable improvement backlog.
- [Testing and QA](testing.md): automated checks, build verification, and manual regression checklists.
- [Calendar tool contract](calendar-tools.md): supported calendar fields, recurrence and occurrence semantics, provider boundaries, and verification.
- [Contacts tool contract](contacts-tools.md): typed contact data, RawContact ownership, atomic mutation rules, and device verification.
- [Workspace file tool contract](file-tools.md): nine focused tools, no-follow traversal, atomic text publication, copy and move recovery, and verification.
- [Bluetooth tool contract](bluetooth-tools.md): bounded BLE client actions, single-connection ownership, GATT result truthfulness, and device verification.
- [Notification tool contract](notification-tools.md): stable agent-notification identity, lifecycle actions, namespace isolation, and device verification.

## Maintenance Rules

Update these documents in the same change as the related implementation when any of the following occurs:

- A runtime, storage, provider, tool, channel, or UI boundary changes.
- A roadmap item is started, completed, replaced, or found to be already implemented.
- A regression requires a new automated test or manual QA case.
- A large refactor changes the main owner of a workflow.

Keep current behavior in architecture or a focused contract, active work in the roadmap, and reusable verification rules in the testing guide. Do not keep separate audit, status, or history documents when one of these files can hold the conclusion.

Use one of these status labels in the roadmap:

- `Planned`: agreed work with no implementation yet.
- `In progress`: implementation has started but acceptance checks are incomplete.
- `Source-verified`: confirmed in the current source; automated or manual checks are listed separately when available.
- `Deferred`: useful work that is intentionally not in the current development stage.

Do not mark an item `Source-verified` based only on a benchmark run, demo, generated trace, or local experiment.

Design notes and implementation plans are working artifacts. Remove them from the current tree after integration; Git history and issues retain their context. Add a focused contract only when a module has distinct data, permission, safety, or lifecycle rules that would make the core documents harder to maintain.

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
