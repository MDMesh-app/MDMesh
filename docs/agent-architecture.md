# Agent architecture — bakeoff conclusions (MDMesh device agent)

Synthesis of the Headwind teardown (`reference/hmdm-android/`, Apache-2.0) + current Android docs.
Verdict per area: **take** (port the pattern), **modernize** (keep idea, new impl), **build-new**.

## App installation — MODERNIZE Headwind's design
Headwind's serialized PackageInstaller pump (queue → commit one → result broadcast pumps the
next) is the *correct* shape and the fix for the classic "only first app installs then stalls"
bug. We keep the design, drop the implementation:
- **Take:** version policy (prefer `versionCode`; `"0"` = any), downgrade-requires-explicit-remove,
  persisted attempt-gating to break download/install loops, the Intent-Redirection mitigation for
  `STATUS_PENDING_USER_ACTION`, split-APK (sum sizes + `setSize` + per-split `openWrite`).
- **Modernize:** `AsyncTask` recursion → one `suspend` loop that `suspendCancellableCoroutine`s on
  each session's result; `HttpURLConnection` → OkHttp; result receiver → **manifest-registered**,
  explicit-intent, `FLAG_IMMUTABLE`, `RECEIVER_NOT_EXPORTED`; add `setInstallReason(POLICY)` and
  `setRequireUserAction(NOT_REQUIRED)` (API 31+).

## Kiosk / COSU — BUILD-NEW (Headwind's real kiosk is closed "Pro")
OSS only has an overlay hack; the COSU engine is stubbed in `ProUtils`. We build it the native way:
- `setLockTaskPackages` + `setLockTaskFeatures(HOME | OVERVIEW | NOTIFICATIONS | …)`,
  `android:lockTaskMode="if_whitelisted"` on the kiosk activity, `addPersistentPreferredActivity`
  for HOME, and `DISALLOW_CREATE_WINDOWS` in `onLockTaskModeEntering`/clear on exit.
- **Take from Headwind:** the default-launcher claim pattern (`addPersistentPreferredActivity` +
  HOME/LEANBACK manifest filters) and the **crash-loop protection** algorithm (SharedPreferences
  counter; >N crashes/60s → stop self-restart, offer a launcher chooser so a broken DPC can't brick
  the device). Keep the overlay lock screen only as a secondary "remote lock" UI, not for app-blocking.

## Policy — TAKE Headwind's catalog (highest-value artifact)
Port `Utils.java`'s DevicePolicyManager catalog into a typed Kotlin policy layer behind the
existing capability-abstraction (`:policy`): user restrictions (`DISALLOW_*`), `setCameraDisabled`,
`setScreenCaptureDisabled`, `setStatusBarDisabled`, `setKeyguardDisabled`, wifi/bluetooth/usb,
`setPermissionPolicy(AUTO_GRANT)`, `setSystemUpdatePolicy`, suspend/hide packages, `setUninstallBlocked`.
Keep the SDK-guard discipline; drop reflection hacks. Each policy advertises a capability key and is
applied via a `policy.apply` command or the enroll baseline.

### Desired state (`config.apply`)
Alongside the imperative `policy.apply`/`kiosk.enter` commands, the server reconciles each device to its
configuration on every check-in: it builds a desired-state document from the `Configuration` row (policy
toggles, kiosk shape, location mode), hashes it into a `revision`, and compares that with the revision the
device last applied. A mismatch enqueues one `config.apply` command in the same check-in — no extra polling.
The agent applies each present key through the existing strategies (wifi/bluetooth/usbStorage/screenshots
toggles, kiosk via the same `KioskApplier` path as `kiosk.enter`, location capture mode) and reports a
per-key outcome (`applied` / `unsupported` / `failed: reason`) in the command result; it persists the new
revision only when nothing failed, and re-applies the last-persisted document after boot or a self-update
so a reboot can't silently regress. The command is only sent to devices whose capability matrix advertises
`configApply` — older agents are never sent it and show as "Agent too old" in the console instead of hanging.
Kiosk follows the configuration: kiosk-on drives entry, kiosk-off exits kiosk only on devices that entered
it via a configuration (an ad-hoc console "Enter kiosk" is left alone). The 22 enforced configuration fields
are: `mainAppId`, `kioskMode`, `kioskExit`, `kioskHome`, `kioskRecents`, `kioskNotifications`,
`kioskSystemInfo`, `kioskKeyguard`, `kioskLockButtons`, `kioskLockVolume`, `password` (kiosk exit password, stored as entered),
`backgroundColor`, `textColor`, `iconSize`, `wifi`, `wifiRadio`, `bluetooth`, `usbStorage`, `disableScreenshots`,
`disableFactoryReset`, `disableSafeBoot` and `requestUpdates` (location capture mode); `name`/`description` are
metadata only. See `proto/payloads/config-apply.schema.json` for the wire shape and
`proto/payloads/config-apply-result.schema.json` for the per-key result. `config.apply` is protocol 1.1 — an
additive change; 1.0 agents keep working unchanged.
Protocol 1.2 adds the `factoryReset` policy key; agents that do not advertise it report it `unsupported`.
Protocol 1.3 adds the `safeBoot` policy key the same way; an agent updated afterwards picks it up from the
persisted document on its post-update re-apply, without re-enrollment. When a device reports a different
agent version than the one its configuration was last queued for, the server re-sends `config.apply` until one
queued for the new version comes back done (`device_state.configAgentVersion`; a failed or expired attempt keeps the
normal backoff), so per-key results such as a stale `unsupported` refresh. A re-sent
document with an unchanged kiosk payload is a no-op on a device already in lock task (no launcher relaunch).
Protocol 1.4 (v0.3.4): `requestUpdates = DONOTTRACK` ("Do not track", the column default) is sent as location mode
`off`, and the agent then reads no location at all; older agents treat `off` as passive, so the server also removes
the location from check-ins of devices on a Do-not-track configuration. `wifi` still only locks the Wi-Fi settings;
the new `wifiRadio` key switches the radio. Off is deferred, without blocking the rest, while Wi-Fi is the device's
only network, and tried again only at the next configuration change, reboot or agent update (phones that switch
mobile data off while on Wi-Fi never apply it). If no check-in reaches the server for 30 minutes (about three
heartbeats) after an Off turned Wi-Fi off (SIM removed, server only reachable on the Wi-Fi network), the agent's
heartbeat turns Wi-Fi back on and holds it on: Off is deferred (`deferred: no connection to the server ...`) until a
document with a revision other than the last delivered one arrives. The agent then reports an applied revision of 64
zeros, which matches no configuration, so the server re-sends `config.apply` after its normal backoff and the result
shows the deferral. A server outage that long, or a user cutting the device off, also heals; any change to the
configuration (a new revision) applies Off again. `kioskLockButtons` blocks the power menu only; the new
`kioskLockVolume` blocks volume in kiosk via `DISALLOW_ADJUST_VOLUME`, which Android pairs with muting, so it is a
separate opt-in. New columns are NULL on upgrade, so revisions only move for configurations that use them, and for
Do-not-track configurations (whose devices get one config.apply).

## Status UI — BUILD-NEW (Views, minSdk 24 friendly)
Replace the `TextView` stub `MainActivity` with a real MDMesh status screen: managed state, device id,
applied policies, kiosk on/off, last check-in, install results. `ComponentActivity` (Hilt). Drive from
a `StateFlow`. Edge-to-edge handled for Android 15.

## Android 14/15 guardrails (apply throughout)
Typed `foregroundServiceType`; manifest receivers + explicit intents + `FLAG_IMMUTABLE` +
`RECEIVER_NOT_EXPORTED`; scoped `<queries>` (no blanket `QUERY_ALL_PACKAGES` in base — Play-Protect
ADR 0005); edge-to-edge insets; don't rely on PendingIntent restart tricks (Android 15 clears them) —
lean on DO + lock-task auto-launch + WorkManager.

## Module map (additions)
- `:policy` — typed `PolicyManager` + full strategy catalog + capability registry (expanded).
- `:kiosk` — real `KioskController` (lock-task) + `CrashLoopGuard` + kiosk activity hooks.
- `:core` — `InstallManager` (coroutine pump) + new `CommandHandler`s (`app.install`/`app.uninstall`/
  `kiosk.enter`/`kiosk.exit`/`device.reboot`/`device.lock`); `policy.apply` expanded; telemetry in check-in.
- `:app` — status UI, manifest (install receiver, FGS types, `lockTaskMode`, queries), DI multibindings,
  baseline policy applied on enrollment.
