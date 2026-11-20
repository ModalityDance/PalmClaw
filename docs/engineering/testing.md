# Testing and QA

Last reviewed: 2026-11-20

Verify changes at the smallest useful level, then run the complete unit suite and an Android Studio build before integration. Platform-dependent behavior also needs focused device or emulator QA.

The build runs `verifyTextEncoding` through `preBuild` to reject invalid UTF-8 source text and common mojibake markers.

## Verification Levels

1. Run focused tests for the changed module and public contract.
2. Add ownership tests when moving state, callbacks, construction, or cleanup.
3. Run the complete `testDebugUnitTest` suite.
4. Build the debug application in Android Studio.
5. Use a device or emulator for Android behavior that local fakes cannot prove.

Connected tests are appropriate for Room migrations, document readers, provider behavior, and other platform APIs that local tests cannot represent.

## Coverage Map

| Area | Expected coverage |
| --- | --- |
| Agent and providers | Context construction, protocol and endpoint resolution, safe fallback, cached targets, tool calls, usage, errors, and cancellation. |
| Runtime and background work | Ownership, concurrency, restart, cleanup, recovery, Cron, heartbeat, and Always-on state. |
| Tools | Schema validation, permission and confirmation policy, bounds, structured errors, and mutation verification. |
| Storage and workspace | Migrations, integrity, path boundaries, encoding, atomic writes, and failure recovery. |
| Channels and MCP | Identity, lifecycle, reconnect, stale callback cleanup, capability publication, and safe status projection. |
| UI and settings | State mapping, session switching, processing, history, input behavior, localization, and structural ownership guards. |

## Device and Emulator QA

Use disposable data and accounts. Validation failures and cancelled confirmations must leave external state unchanged. Separate a committed write from failed read-back verification: return the known record ID and prohibit blind creation retries rather than reporting that nothing was written.

- Verify Calendar and Contacts against local and synced providers when available.
- Verify workspace files across supported Android versions and approved storage roots.
- Verify Bluetooth and notifications with real permissions and hardware behavior.
- Verify Cron, channels, and Always-on across process restart, reboot, network loss, Doze, and Android background restrictions.
- Verify MCP over HTTPS, local HTTP, approved private-LAN HTTP, legacy transport, reconnect, configuration changes, and multi-server isolation.
- Verify chat session switching, cancellation, history loading, keyboard insets, and optional tool details.

Always-on QA must distinguish desired enablement, foreground service, runtime, gateway, network, and channel readiness.

Include Android 15 behavior, the Play `specialUse` declaration, and a long-running connected check before making continuous-online claims.

Calendar regressions include provider projection differences and creation receipts after failed verification. `CalendarProviderDeviceTest` is opt-in (`-e calendarDeviceQa true` with the instrumentation runner); it requires granted calendar permissions and a writable local calendar, exercises single and recurring event CRUD, and cleans only its uniquely named fixtures. It does not use cloud calendars. Run provider compatibility tests separately from live-provider QA; one physical phone does not prove support for every vendor.

Mutation feedback regression coverage: `CronMutationTest`, `CronToolTest`, `NotificationMutationExecutorTest`, `MutationFeedbackTest`, and `ToolRegistryTest`. Device-specific coverage includes `ContactsProviderFeedbackAndroidTest` and `ContactsControlToolAndroidTest`; the provider fixtures use an isolated resolver, not real contacts. Keep instrumentation and hardware verification separate from the local unit suite.

On a device, verify contact recovery by RawContact ID, notification updates under the same key, Cron saved-job inspection after service/scheduling failure, BLE acknowledgement loss without command replay, and camera cancellation followed by `inspect_output` on the original URI. Inspect a recording's returned path after stop failure rather than starting a replacement recording. No automatic retries, fixture deletion, or cross-vendor compatibility should be inferred from local tests.

Gemini continuation regression coverage: `OpenAiMessageCodecTest`, `OpenAiContinuationRoundTripTest`, `AdaptiveLlmProviderContinuationTest`, `AssistantTraceCodecTest`, `ContextBuilderTest`, `ProviderHttpExceptionTest`, `ProviderEndpointRetryPolicyTest`, and `MessageUiProjectorTest`. Protocol tests use local HTTP interceptors, not paid API calls. On 2026-11-20, the maintainer reported tests passing and the original Gemini tool-call failure resolved after installing the updated app. Additional live cases are sequential/parallel calls, restart recovery, and model switching between turns. A continuation error must preserve completed tool results without repeating a write. Old missing signatures cannot be reconstructed.

## Build Size

When adding a large dependency or desugaring requirement, compare the same build variant before and after with Android Studio APK Analyzer. Record the result in the related change or release note.

## Regression Rule

Add a regression test when it protects reusable behavior or an ownership boundary. Keep one-off investigation logs and private device details outside this documentation.
