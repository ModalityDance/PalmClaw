# Testing and QA

Last reviewed: 2026-08-26

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

Use disposable data and accounts. Failed or cancelled mutations must leave external state unchanged, and successful mutations must be read back from the platform source of truth.

- Verify Calendar and Contacts against local and synced providers when available.
- Verify workspace files across supported Android versions and approved storage roots.
- Verify Bluetooth and notifications with real permissions and hardware behavior.
- Verify Cron, channels, and Always-on across process restart, reboot, network loss, Doze, and Android background restrictions.
- Verify MCP over HTTPS, local HTTP, approved private-LAN HTTP, legacy transport, reconnect, configuration changes, and multi-server isolation.
- Verify chat session switching, cancellation, history loading, keyboard insets, and optional tool details.

Always-on QA must distinguish desired enablement, foreground service, runtime, gateway, network, and channel readiness.

Include Android 15 behavior, the Play `specialUse` declaration, and a long-running connected check before making continuous-online claims.

## Build Size

When adding a large dependency or desugaring requirement, compare the same build variant before and after with Android Studio APK Analyzer. Record the result in the related change or release note.

## Regression Rule

Add a regression test when it protects reusable behavior or an ownership boundary. Keep one-off investigation logs and private device details outside this documentation.
