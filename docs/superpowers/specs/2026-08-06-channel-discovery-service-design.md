# Channel Discovery Service Design

Date: 2026-08-06

## Goal

Move Telegram, Feishu, Email, and WeCom channel discovery out of `ChatViewModel` into one
testable workflow owner. Discovery may open a bounded temporary Feishu or WeCom connection when
the requested credentials do not already have an active runtime adapter. It must not persist
credentials, enable a binding, route inbound messages, or change the formal gateway lifecycle.

Discord and Slack are outside this phase because the current settings flow does not expose a
discovery workflow for them.

## Service Boundary

`AppContainer` constructs one process-level `ChannelDiscoveryService` and exposes it through
`ChatViewModelEnvironment`. The service provides four suspending operations with typed requests:

- `discoverTelegram(request)`;
- `discoverFeishu(request, currentBinding)`;
- `discoverEmail(request)`;
- `discoverWeCom(request)`.

The requests contain the raw settings values needed for each operation. The service owns trimming,
port parsing, credential completeness checks, and adapter identity construction. It returns channel
domain candidates and a `ChannelDiscoveryOutcome`, not Compose state or `Ui*` models.

An outcome distinguishes successful candidates, a completed search with no candidates, and a
failed search. A completed search may carry a typed timeout reason and diagnostic guidance.
Failures are classified as invalid input, authentication, network, runtime conflict, or unexpected
failure and carry only a safe user-facing detail. Credentials, tokens, and unbounded response bodies
must not appear in results or logs. Both completion and failure outcomes may retain fallback
candidates so the UI can preserve its existing non-destructive behavior.

Production dependencies enter through narrow ports:

- a Telegram discovery client that owns HTTP and JSON parsing;
- an Email sender detector;
- Feishu and WeCom diagnostic snapshot sources;
- a transient adapter factory and host;
- the existing process `ChannelRuntimeSnapshotSource`;
- an injectable monotonic clock and delay function.

The production Telegram client continues to use the existing configured `OkHttpClient`. The Email
port continues to delegate to the existing mailbox detection implementation. Network protocols and
remote API behavior do not change.

## Active And Temporary Discovery

Feishu and WeCom discovery derive the primary adapter key through `ChannelAdapterIdentity`.
Feishu also keeps its canonical and legacy compatible keys when reading diagnostic history.

For each request, the service:

1. Validates and normalizes the request.
2. Checks whether any compatible formal runtime snapshot is running, connected, or ready.
3. Enters a keyed discovery task and checks the runtime snapshots again.
4. Reuses the formal runtime when active; otherwise starts one temporary adapter for the primary
   key.
5. Polls diagnostics until candidates appear, a non-ready snapshot reports an explicit error, or
   the 15-second monotonic deadline expires.
6. Stops a temporary adapter in `finally`, including cancellation and unexpected failure paths.

The second active check narrows the race with a simultaneous gateway refresh. A process-wide
adapter lease is deliberately deferred to the later gateway lifecycle phase. This phase does not
modify `GatewayOrchestrator` ownership.

Temporary adapters run in capture-only mode. They may authenticate, connect, and record candidate
metadata, but they receive no application inbound sink and must not publish a message, invoke an
agent, persist a session message, or mark an inbound event as forwarded. Feishu already stops before
publishing when it has no allowed targets. WeCom receives a small internal capture-only option,
defaulting to disabled for every production runtime adapter, so it returns after recording a
candidate.

The discovery host owns the temporary adapter coroutine scope. It never adds temporary adapters to
`GatewayOrchestrator` and never writes `ChannelsConfig` or `SessionChannelBinding`.

## Concurrency And Cancellation

The process service keeps an in-flight registry keyed by channel and primary adapter key. Requests
for the same identity await one shared task; requests for different identities remain independent.
The registry tracks active waiters. Cancelling a waiter releases it, and cancelling the last waiter
cancels the shared task and triggers adapter cleanup.

`ChatViewModel` retains one discovery `Job`. Starting a new discovery or clearing discovery state
cancels the previous job. `viewModelScope` cancellation releases the final waiter when the page is
destroyed. Shared work continues only while another caller is still waiting. Every task is bounded
by 15 seconds even if a caller fails to release normally.

Candidates and explicit credential errors end the task early. A ready connection without inbound
traffic continues waiting until the deadline so the user has time to send a message from Feishu or
WeCom.

## UI Integration

`ChatViewModel` keeps the public settings event methods but reduces each implementation to:

1. cancel the previous discovery job;
2. project the existing loading state;
3. call the matching service operation;
4. map domain candidates to the existing UI candidate models;
5. pass the outcome to `ChannelDiscoveryStateProjector`.

Cancellation is rethrown and does not become a visible failure. Clear actions cancel work before
clearing UI state. `ChatViewModel` no longer imports concrete channel adapters, OkHttp request or
JSON types, gateway diagnostic singletons, or discovery polling constants.

Existing success, failure, fallback, and clear presentation remain unchanged where accurate.
Messages that currently instruct the user to save credentials merely to start Feishu or WeCom are
replaced with guidance for the active 15-second discovery window. The service does not automatically
select or persist a discovered candidate.

## Compatibility Boundaries

- Configuration, database, and binding schemas do not change.
- Adapter key formats do not change.
- Formal gateway enablement, refresh, reconfiguration, and shutdown do not change.
- Message routing and remote delivery do not change.
- Discovery credentials remain memory-only unless the user separately saves the settings form.
- Telegram and Email keep their current one-shot discovery behavior and diagnostic fallback.
- Feishu and WeCom temporary connections are bounded, capture-only, and absent from the gateway
  adapter collection.

## Verification

Focused service tests use fake ports, snapshots, adapters, clocks, and delays to cover:

- Telegram normalization, successful parsing, authentication failure, and network failure;
- Email port normalization, successful detection, cached fallback, and failure classification;
- formal Feishu and WeCom runtime reuse without temporary adapter creation;
- temporary start, candidate completion, explicit-error completion, 15-second timeout, and cleanup;
- same-key task sharing, different-key independence, waiter cancellation, and final-waiter cleanup;
- Feishu compatible diagnostic keys and requested-identity isolation;
- capture-only operation without inbound publication.

Composition and structural tests verify the process-level service wiring and prevent discovery
networking, adapter construction, diagnostic polling, and retry constants from returning to
`ChatViewModel`. Existing `ChannelDiscoveryStateProjector` tests retain UI presentation coverage.

Per the current batch-verification workflow, implementation agents run static checks only. Manual
completion later requires `:app:testDebugUnitTest`, `:app:assembleDebug`, and device checks for
successful, invalid-credential, timeout, cancellation, and already-running Runtime discovery paths.
