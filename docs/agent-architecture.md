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

Besides the one-off `policy.apply`/`kiosk.enter` commands, the server keeps each device in line with its
configuration.

**How it works**
- On every check-in the server builds a desired-state document from the `Configuration` row (policy toggles, kiosk
  shape, location mode) and hashes it into a `revision`.
- If that differs from the revision the device last applied, the same check-in carries one `config.apply`. No extra
  polling.
- The agent applies each key through the existing strategies (kiosk via the same `KioskApplier` as `kiosk.enter`)
  and reports a per-key outcome: `applied`, `unsupported`, `failed: <reason>` or `deferred: <reason>`.
- It saves the new revision only when nothing failed, and re-applies the saved document after a boot or self-update,
  so a reboot can't silently undo it.
- Only agents that advertise `configApply` get the command. Older ones show "Agent too old" in the console.
- When a device reports a new agent version, the server re-sends `config.apply` until one queued for that version
  comes back done (`device_state.configAgentVersion`), so stale `unsupported` results refresh. A re-sent document
  with the same kiosk payload doesn't relaunch the launcher.

**Kiosk.** Kiosk on in the configuration enters kiosk. Kiosk off exits it only on devices that entered via a
configuration; an ad-hoc "Enter kiosk" from the console is left alone.

**The 22 enforced fields**

| Area | Fields |
|------|--------|
| Kiosk | `mainAppId`, `kioskMode`, `kioskExit`, `kioskHome`, `kioskRecents`, `kioskNotifications`, `kioskSystemInfo`, `kioskKeyguard`, `kioskLockButtons`, `kioskLockVolume`, `password` (exit PIN, stored as entered) |
| Launcher look | `backgroundColor`, `textColor`, `iconSize` |
| Device policy | `wifi`, `wifiRadio`, `bluetooth`, `usbStorage`, `disableScreenshots`, `disableFactoryReset`, `disableSafeBoot` |
| Location | `requestUpdates` (location capture mode) |

`name` and `description` are metadata only. Wire shapes: `proto/payloads/config-apply.schema.json` and
`proto/payloads/config-apply-result.schema.json`.

**Protocol history** (all additive; older agents keep working)
- **1.1:** `config.apply` itself.
- **1.2:** the `factoryReset` policy key. Agents without it report `unsupported`.
- **1.3:** the `safeBoot` key, the same way. An agent updated later picks it up from the saved document on its
  post-update re-apply, without re-enrollment.
- **1.4 (v0.3.4):**
  - **Do not track** (`requestUpdates = DONOTTRACK`, the column default) is sent as location mode `off`, and the
    agent reads no location. Older agents treat `off` as passive, so the server also drops location from check-ins
    of devices on a Do-not-track configuration.
  - **`keepLocationEnabled`** (Device Owner, Android 11+) keeps system location on and prevents user changes. Off or Not managed unlocks it without switching it off. Reporting remains controlled by Location reporting. Before collecting a location, the agent repairs missing foreground/background permissions as Device Owner, including after an upgrade; denied grants are logged and retried.
  - **`wifiRadio`** switches the radio; `wifi` still only locks the Wi-Fi settings. See below.
  - **`kioskLockVolume`** blocks volume in kiosk (`DISALLOW_ADJUST_VOLUME`). Android mutes the device along with it,
    so it's a separate opt-in. `kioskLockButtons` blocks only the power menu.
  - New columns are NULL on upgrade, so revisions only change for configurations that use them, and for
    Do-not-track configurations (whose devices get one `config.apply`).

**Wi-Fi radio Off, in detail**
- Off is deferred, without blocking the other keys, while Wi-Fi is the device's only network. It's retried only at
  the next configuration change, reboot or agent update. Phones that drop mobile data while on Wi-Fi never apply it.
- If no check-in reaches the server for 30 minutes (about three heartbeats) after Off turned Wi-Fi off (SIM
  removed, server only reachable over Wi-Fi), the heartbeat turns Wi-Fi back on and holds it on. Off stays deferred
  (`deferred: no connection to the server ...`) until a document with a different revision than the last delivered
  one arrives.
- The agent then reports an applied revision of 64 zeros, which matches no configuration, so the server re-sends
  `config.apply` after its normal backoff and the console shows the deferral.
- A server outage that long, or a user cutting the device off, also triggers this. Any configuration change (a new
  revision) applies Off again.

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

GPS reporting applies `location.mode = active` automatically from Location reporting = GPS.
The agent immediately checks in once more after a successful GPS config.apply, so its first GPS
telemetry does not depend on a manual command or the next heartbeat. Telemetry collectors run on
Dispatchers.IO (location callbacks run on the main executor); a device without a cached position
has up to 30 seconds for its first fix, with a 5-second timeout for later fixes.
