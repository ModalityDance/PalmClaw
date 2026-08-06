# Channel Identity and Runtime Projection Design

Date: 2026-08-06

## Goal

Use one adapter identity and channel runtime projection rule set across the settings UI,
`RuntimeControlService`, normal runtime, and Always-on runtime without moving channel discovery
network flows or gateway lifecycle ownership.

## Shared Identity

`ChannelAdapterIdentity` owns the existing `channel:sha256(seed).take(16)` format. It exposes a
raw `key`, all compatible keys for a persisted binding, and one primary key. Telegram, Discord,
Slack, Email, and WeCom each have one key. Feishu uses the app ID and secret as its canonical
primary identity and retains the previous four-field key as a compatibility candidate.

Gateway adapter construction, outbound metadata, inbound binding matching, discovery diagnostics,
and runtime status lookup use this module. Routing still prefers an exact adapter-key and target
match before the existing target-only fallback.

## Shared Projection

`ChannelBindingRuntimeProjector` receives a nullable binding, gateway enabled state, and a
`ChannelRuntimeSnapshotSource`. It returns:

- normalized channel;
- normalized target;
- compatible adapter keys;
- the existing user-visible status label.

Status precedence is fixed:

1. `Unbound`.
2. `Disabled`.
3. Existing credential or target validation labels.
4. `Gateway idle`.
5. `Error`.
6. `Connected`.
7. `Connecting`.
8. `Starting`.
9. `Configured`.

For Feishu, all compatible keys are checked for activity or an error before falling back to the
primary key. Email syntax is checked through an injected `EmailAddressValidator`; the Android
production implementation continues to use `Patterns.EMAIL_ADDRESS`.

`canStartAdapter` owns the existing gateway-capable binding rules. Discovery-capable Feishu,
Email, and WeCom bindings can start before a target is detected; Telegram, Discord, and Slack
retain their current target requirements.

## Composition

`AppContainer` constructs and shares the Android email validator, projector, and process-level
diagnostic snapshot source. `RuntimeControlService` owns the projector and accepts only the live
snapshot source at operation time. `ConnectedChannelOverviewAssembler` performs session-to-row
mapping, filtering, and sorting from already projected values.

`GatewayRuntime` retains adapter and gateway lifecycle coordination. This change only replaces
its local key generation, binding completeness, target projection, and status resolution.

## Compatibility Boundaries

- Configuration and database formats do not change.
- Tool schemas and status strings do not change.
- Remote routing order and target-only fallback do not change.
- Channel discovery networking does not move.
- `GatewayOrchestrator` creation, refresh, and stop ownership remain in `GatewayRuntime`.

## Verification

Focused tests cover fixed key vectors, empty credentials, Feishu compatibility keys, target
normalization, status precedence, multi-key snapshots, gateway completeness, overview mapping,
service persistence/refresh order, composition-root sharing, and structural regression guards.

Manual completion requires `:app:testDebugUnitTest`, `:app:assembleDebug`, and device comparison of
settings, `session_status`, normal runtime, and Always-on runtime for the same bindings.
