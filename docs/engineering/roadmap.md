# PalmClaw Engineering Roadmap

Last reviewed: 2026-08-26

Completed architecture and tool work is described in [Architecture](architecture.md) and retained in Git history. This file contains only current development priorities.

## Current Priorities

| Priority | Area | Status | Next outcome |
| --- | --- | --- | --- |
| P1 | UI boundary | In progress | Move stable settings and workflow ownership out of `ChatViewModel` while keeping presentation state in the UI layer. |
| P2 | Secondary tool coverage | Planned | Review media import, confirmed memory clearing, device status detail, and portable web-search filters against real agent use cases. |
| P2 | Long-task capabilities | Deferred | Reconsider progress, compact trace, retry, recovery, pause, and resume after the current system boundaries remain stable. |

## Maintenance Work

Run focused device regression when Android platform behavior, providers, background execution, channels, or MCP transports change. The reusable checklist is in [Testing and QA](testing.md).

Do not add a platform capability only because an API exists. New work needs a clear agent use case, a lifecycle owner, bounded behavior, and a verifiable result.
