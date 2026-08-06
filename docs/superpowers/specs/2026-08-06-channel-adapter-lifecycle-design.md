# Channel Adapter Lifecycle Ownership Design

Date: 2026-08-06

## Goal

Move configured channel-adapter assembly and `GatewayOrchestrator` lifecycle ownership out of
`GatewayRuntime` without changing configuration persistence, adapter identity, routing, agent-turn
coordination, or foreground and Always-on runtime behavior.

This phase follows the shared channel runtime projection and runtime UI observation work. It is limited to
configured adapters and their owned orchestrator. Automation wiring and MCP lifecycle remain separate
future phases.

## Current Problems

`GatewayRuntime` currently owns three different levels of responsibility in one class:

- top-level agent-turn coordination, including per-session locks and processing-aware config deferral;
- conversion of `SessionChannelBinding` values into six concrete adapter types;
- creation, reconfiguration, outbound use, capability lookup, and shutdown of `GatewayOrchestrator`.

The adapter conversion block repeats channel-specific validation and grouping concerns inside the main
runtime. The orchestrator field also makes every outbound delivery and shutdown path depend directly on
that implementation. As a result, adapter lifecycle behavior cannot be tested without constructing a
large runtime graph.

## Scope

This phase includes:

- a configured adapter factory for Telegram, Discord, Slack, Feishu, Email, and WeCom;
- a focused lifecycle owner for one optional `GatewayOrchestrator`;
- a small orchestrator control contract so lifecycle behavior can use test doubles;
- migration of gateway apply, stop, outbound delivery, and attachment-capability lookup;
- focused adapter-factory, lifecycle, integration-structure, and compatibility tests;
- architecture, roadmap, and testing documentation updates.

This phase excludes:

- moving `gatewayProcessingSessions` or `pendingGatewayConfig` out of `GatewayRuntime`;
- changing when runtime-tool config updates are deferred;
- changing channel binding lookup, inbound session resolution, or outbound adapter selection;
- changing adapter keys, credentials, target normalization, route rules, or status strings;
- changing channel discovery or transient discovery adapters;
- changing `GatewayOrchestrator` inbound queues, agent execution, or deduplication;
- extracting cron, heartbeat, MCP, subagent, attachment-transfer, or remote-delivery lifecycle.

## Configured Adapter Factory

Add `ChannelAdapterFactory` and `ConfiguredChannelAdapterFactory` under `com.palmclaw.channels`.
`ConfiguredChannelAdapterFactory` accepts the Android `Application` needed by Email and WeCom adapters
and exposes one operation:

```kotlin
internal fun interface ChannelAdapterFactory {
    fun create(bindings: List<SessionChannelBinding>): List<ChannelAdapter>
}
```

The implementation owns all code currently in `GatewayRuntime.buildAdapters()`:

- filtering disabled bindings;
- trimming credentials and targets;
- applying the equivalent shared `SessionChannelBindingRules` normalization and validity checks while
  preserving Telegram and Discord's current build-time treatment;
- grouping bindings that share one adapter credential identity;
- deriving adapter keys through `ChannelAdapterIdentity.primaryKeyForBinding()`;
- building route rules and allowed-target sets;
- applying the existing Feishu stable-configuration conflict rule;
- constructing concrete adapter instances.

The factory does not read `ConfigStore`, persist configuration, start adapters, inspect runtime status, or
resolve sessions. It returns a new list for every call and leaves reuse decisions to
`GatewayOrchestrator.reconfigure()`.

`EmailCredentialKey` moves with the factory because it exists only to group Email bindings. No adapter
constructor or channel protocol changes in this phase.

The factory must not copy the private normalization helpers from `GatewayRuntime`. Slack, response-mode,
Email, WeCom, Discord-validity, and Slack-validity rules use `SessionChannelBindingRules`; Feishu target
normalization continues to use the existing channel helper. Telegram token and Discord target handling
remain byte-for-byte equivalent to the current `buildAdapters()` code so this extraction does not silently
broaden accepted raw configuration.

## Orchestrator Control Boundary

Add `GatewayOrchestratorControl` beside `GatewayOrchestrator`. The concrete orchestrator implements the
contract without changing its method bodies:

```kotlin
interface GatewayOrchestratorControl {
    val adapterCount: Int
    fun start()
    fun reconfigure(adapters: List<ChannelAdapter>)
    fun stop()
    suspend fun deliverOutboundNow(outbound: OutboundMessage)
    fun resolveOutboundAttachmentCapability(outbound: OutboundMessage): ChannelAttachmentCapability?
}
```

An internal `GatewayOrchestratorFactory` functional interface creates that control from an adapter list.
`GatewayRuntime` remains the composition root for the orchestrator's agent-loop, repositories, turn-lock,
session-resolution, and remote-delivery callbacks. It supplies those dependencies through the factory
closure, while the lifecycle service decides when creation occurs.

## Channel Gateway Lifecycle

Add `ChannelGatewayLifecycle` under `com.palmclaw.channels`. It is the only owner of the current
orchestrator reference and accepts:

- `ChannelAdapterFactory`;
- `GatewayOrchestratorFactory`;
- a callback receiving `ChannelGatewayLifecycleSnapshot`.

Its public operations are:

```kotlin
internal data class ChannelGatewayLifecycleSnapshot(
    val running: Boolean,
    val adapterCount: Int,
    val lastError: String
)

internal class ChannelGatewayLifecycle {
    fun apply(enabled: Boolean, bindings: List<SessionChannelBinding>): ChannelGatewayLifecycleSnapshot
    fun stop(): ChannelGatewayLifecycleSnapshot
    suspend fun deliverOutbound(outbound: OutboundMessage)
    fun resolveOutboundAttachmentCapability(outbound: OutboundMessage): ChannelAttachmentCapability?
}
```

State transitions preserve the existing order:

1. Disabled configuration stops and clears the orchestrator, then reports stopped with no error.
2. Enabled configuration builds adapters.
3. An empty result stops and clears the orchestrator. It reports the current exact error only when an
   enabled binding has a non-blank channel.
4. An existing orchestrator receives `reconfigure(adapters)` and remains running.
5. Otherwise the factory creates an orchestrator, which is started and retained.

Outbound delivery uses the retained orchestrator. Missing runtime and delivery failure messages remain
unchanged. A successful delivery clears `lastError`; a failed delivery records the exception message and
rethrows it. Attachment capability lookup remains nullable and does not mutate state.

The lifecycle service does not introduce retries, coroutine scopes, locks, or exception conversion. The
concrete orchestrator continues to synchronize adapter replacement and outbound resolution internally.

## GatewayRuntime Boundary

`GatewayRuntime` retains processing-aware application policy:

```text
RuntimeToolIntegration update
    -> requestGatewayRuntimeConfig
    -> defer while gatewayProcessingSessions is non-empty
    -> apply latest pendingGatewayConfig when all sessions become idle
```

`applyGatewayRuntimeConfig()` continues to:

- read bindings from `ConfigStore`;
- use `ChannelBindingRuntimeProjector.canStartAdapter()` to reconcile `ChannelsConfig.enabled`;
- persist the corrected enabled value when required;
- call `ChannelGatewayLifecycle.apply()`;
- clear the processing set after a stopped result, matching current behavior.

The lifecycle state callback maps directly to `GatewayRuntime.updateState()`. `GatewayRuntime` delegates
owned outbound delivery and attachment-capability lookup to the lifecycle service. Shutdown calls
`ChannelGatewayLifecycle.stop()` before closing the message bus and agent loop.

Direct reload behavior remains unchanged: `reloadGatewayFromStoredConfig()` applies immediately, while
runtime-tool updates continue through the processing-aware request method.

## Data Flow

Configured adapter application follows:

```text
ConfigStore bindings + reconciled enabled flag
    -> GatewayRuntime processing policy
    -> ChannelGatewayLifecycle.apply
    -> ConfiguredChannelAdapterFactory.create
    -> GatewayOrchestrator create or reconfigure
    -> lifecycle snapshot
    -> GatewayRuntimeState
```

Outbound delivery follows:

```text
GatewayRuntime delivery caller
    -> ChannelGatewayLifecycle.deliverOutbound
    -> GatewayOrchestrator.deliverOutboundNow
    -> existing adapter selection and send behavior
```

## Compatibility Boundaries

- Adapter keys continue to come exclusively from `ChannelAdapterIdentity`.
- Six-channel filtering, grouping, normalized fields, route rules, and allowed-target sets remain
  behaviorally identical.
- The strings `Gateway is not running; cannot deliver outbound message` and
  `No active adapter could start. Check credentials and target IDs.` remain unchanged.
- `ChannelsConfig`, `SessionChannelBinding`, database storage, and tool schemas do not change.
- Inbound and outbound adapter selection remains in `GatewayOrchestrator`.
- Processing-aware config deferral remains in `GatewayRuntime`.
- Foreground and Always-on runtimes continue to construct the same runtime implementation through the
  existing supervisor and dependency factory.

## Test Strategy

Add focused tests for:

- all six adapter types, adapter keys, invalid-binding omission, and credential grouping;
- normalized outbound target matching for channel adapters;
- disabled, empty, first-start, reconfigure, stop, delivery, delivery-error, and capability states;
- exact compatibility error messages;
- structural ownership: no concrete adapter construction, `buildAdapters()`, orchestrator field, or
  `EmailCredentialKey` in `GatewayRuntime`;
- structural retention of `gatewayProcessingSessions`, `pendingGatewayConfig`, and
  `requestGatewayRuntimeConfig()` in `GatewayRuntime`.

Implementation continues the deferred-validation workflow. Static checks run during the phase. The later
unified verification runs `:app:testDebugUnitTest`, `:app:assembleDebug`, and device checks covering normal
and Always-on runtime adapter refresh, processing-time config changes, outbound delivery, and settings or
`session_status` consistency.

## Completion Criteria

- `ConfiguredChannelAdapterFactory` is the only configured-binding-to-adapter implementation.
- `ChannelGatewayLifecycle` is the only owner of an orchestrator reference.
- `GatewayRuntime` has no concrete channel-adapter imports or local `buildAdapters()` implementation.
- Config deferral and processing-session ownership remain in `GatewayRuntime`.
- Existing key, routing, status, error, persistence, reload, and shutdown behavior remains unchanged.
- Automation and MCP lifecycle code is untouched except for line movement caused by deleted adapter code.
