# PalmClaw Engineering Documentation

This directory gives contributors a concise view of PalmClaw's engineering structure and development rules.

## Documents

- [Architecture](architecture.md): system layers, ownership, and extension boundaries.
- [Engineering roadmap](roadmap.md): current work and near-term priorities.
- [Testing and QA](testing.md): verification levels and platform checks.

## Maintenance

- Update the architecture when ownership or a major boundary changes.
- Keep only active or deferred work in the roadmap; Git history records completed work.
- Add reusable verification rules to the testing guide, not one-off bug transcripts.
- Keep tool schemas, actions, and error details in code and tests.
- Remove temporary plans and audits after implementation.

Roadmap statuses are `Planned`, `In progress`, `Source-verified`, and `Deferred`. A benchmark, demo, or generated trace alone is not source verification.

## Public Boundary

These files are public. Do not include credentials, private endpoints, personal data, device identifiers, machine-specific paths, or raw private traces. Report security issues through [SECURITY.md](../../SECURITY.md).
