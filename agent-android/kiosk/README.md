# :kiosk

COSU (Corporate-Owned Single-Use) lock-task / kiosk control, built independently on the
native `DevicePolicyManager` lock-task APIs (the upstream open-source agent only ships an
overlay hack).

This module does **not** depend on `:policy` — its result type (`KioskResult`) is
defined locally.

## Contents

| Type | Role |
|------|------|
| `KioskController` | interface; never throws — returns `KioskResult` |
| `KioskResult` | `Ok` / `Failed(reason)` / `Unsupported` |
| `LockTaskKioskController` | real impl over `DevicePolicyManager` lock-task APIs |
| `StubKioskController` | no-op fallback for non-Device-Owner / tests (all ops `Unsupported`) |
| `KioskToggles` + `lockTaskFeatures()` | kiosk UI toggles → `LOCK_TASK_FEATURE_*` bitmask (`KioskFeatures.kt`) |
| `CrashLoopGuard` | crash-loop protection (ported from the upstream agent's `CrashLoopProtection`) |
| `FaultStore` | counter persistence behind an interface |
| `SharedPrefsFaultStore` | production store (SharedPreferences, synchronous `commit()`) |
| `InMemoryFaultStore` | Android-free store for unit tests |
| `KioskReturnPolicy` | single-app kiosk: reopen the pinned app, or hold on the "App closed" screen once the guard trips (never ends kiosk) |
| `ExitPinGate` | the on-device exit: none without a PIN; wrong PINs lock it for longer each time, timed on uptime |
| `PinAttemptStore` | attempt-count persistence (`SharedPrefsPinAttemptStore`, `InMemoryPinAttemptStore`) |

## What `LockTaskKioskController` does

Constructed directly with `(dpm: DevicePolicyManager, admin: ComponentName)` — no
`DpmHandle`. Guards `isDeviceOwnerApp` + `SDK_INT` on every call.

- **`enter(homeComponent: ComponentName, allowedPackages: List<String>, features: Int)`** —
  `setLockTaskPackages` (allowlist + the agent's own package), `setLockTaskFeatures(features)`
  on API 28+ (`features` is a `LOCK_TASK_FEATURE_*` bitmask, usually from
  `lockTaskFeatures(KioskToggles)`), and `addPersistentPreferredActivity` for
  `ACTION_MAIN` + `CATEGORY_HOME` + `CATEGORY_DEFAULT` so the agent owns HOME.
- **`exit()`** — clears the allowlist, `clearPackagePersistentPreferredActivities`,
  sets lock-task features to `LOCK_TASK_FEATURE_NONE` (API 28+).
- **`isLocked(context)`** — `ActivityManager.lockTaskModeState == LOCK_TASK_MODE_LOCKED`.
- **`allowedPackages()`** — `getLockTaskPackages(admin)`.

Lock-task base APIs need API 21; `setLockTaskFeatures` needs API 28. The module's
`minSdk` is 24, so the base APIs are always available and only features are guarded.
Real lock-task requires Device Owner (provisioned via `:app`).

## How `:app` wires it

`:kiosk` configures device-level policy; the kiosk activity in `:app` completes the
loop. `:app` is responsible for (done there, not here):

1. **Manifest** — the kiosk launcher is `com.mdmesh.agent.KioskLauncherActivity`, declared
   `android:lockTaskMode="if_whitelisted"`. The HOME intent filter sits on a disabled
   `activity-alias`, `.KioskHomeAlias`, that targets it; `kiosk.enter` enables the alias and
   passes it as `homeComponent`, `kiosk.exit` disables it so HOME falls back to the OEM launcher:

   ```xml
   <activity
       android:name=".KioskLauncherActivity"
       android:exported="true"
       android:launchMode="singleInstance"
       android:excludeFromRecents="true"
       android:stateNotNeeded="true"
       android:lockTaskMode="if_whitelisted" />

   <activity-alias
       android:name=".KioskHomeAlias"
       android:targetActivity=".KioskLauncherActivity"
       android:exported="true"
       android:enabled="false">
       <intent-filter>
           <action android:name="android.intent.action.MAIN" />
           <category android:name="android.intent.category.HOME" />
           <category android:name="android.intent.category.DEFAULT" />
       </intent-filter>
   </activity-alias>
   ```

2. **Start/stop lock task** — once `LockTaskKioskController.enter(...)` has
   allowlisted the package, `KioskLauncherActivity` calls `startLockTask()` in the
   foreground (and `stopLockTask()` on exit).

3. **Kiosk guard** — `KioskApplier` arms `KioskGuard` (`:core`) before it locks a kiosk, and disarms it only
   once lock task has ended. While armed, the device answers app permission requests itself (auto-grant; on
   Android 12+ devices set up without admin sensor grants, the guard leaves foreground sensor requests to Android
   and denies the kiosk apps the background ones), the kiosk apps can't be stopped, cleared or uninstalled
   (`DISALLOW_APPS_CONTROL`, an uninstall block per app), factory reset and installs from unknown sources are
   blocked, and `DISALLOW_CREATE_WINDOWS` keeps other apps from drawing over the kiosk. Disarming puts back the
   permission policy the device had (auto-grant on an Android 12+ QR-provisioned device, so nothing asks after kiosk
   either; a device that asked before asks again, the grants made in kiosk released to revocable ones), and sets
   Factory reset to what the configuration says (or, when it doesn't manage it, what the device had).
   `AdminReceiver`'s
   `onLockTaskModeEntering`/`onLockTaskModeExiting` reconcile it whatever started or stopped lock task.
   `KioskGuardControl` (here) is its device seam. After an agent update and at boot, a kiosk saved with lock task off
   is shown again (`KioskApplier.returnToKiosk`).

4. **Crash-loop wiring** — `KioskLauncherActivity` gets a `CrashLoopGuard` from Hilt and
   calls `registerFault()` each time the pinned single app returns to HOME; once
   `isCrashLoopDetected()` trips, it stops reopening the app and holds in kiosk on an "App closed" screen
   (`KioskReturnPolicy`), so a crashing app can't loop. Kiosk stays on: only the admin-PIN exit or the server ends it.

## Crash-loop algorithm

Ported from `reference/.../util/CrashLoopProtection.java`. More than
`LOOP_CRASHES` (3) faults within `LOOP_TIME_SPAN` (60_000 ms) trips the guard — i.e.
the 4th crash inside the window. A fault after the window restarts the count; an
aged-out window resets on the next `isCrashLoopDetected()` check. The clock is
injectable (`now: () -> Long`) and the counter sits behind `FaultStore`, so the
logic is unit-tested on the JVM (`src/test/.../CrashLoopGuardTest.kt`).
