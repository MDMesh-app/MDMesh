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
`configApply` — older agents are never sent it and show as unsupported in the console instead of hanging.
Kiosk follows the configuration: kiosk-on drives entry, kiosk-off exits kiosk only on devices that entered
it via a configuration (an ad-hoc console "Enter kiosk" is left alone). See
`proto/payloads/config-apply.schema.json` for the wire shape.

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
