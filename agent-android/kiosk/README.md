# :kiosk

COSU (Corporate-Owned Single-Use) lock-task / kiosk control, built clean-room on the
native `DevicePolicyManager` lock-task APIs (Headwind's real COSU engine is closed
"Pro"; OSS only ships an overlay hack).

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
| `CrashLoopGuard` | crash-loop protection (ported from Headwind `CrashLoopProtection`) |
| `FaultStore` | counter persistence behind an interface |
| `SharedPrefsFaultStore` | production store (SharedPreferences, synchronous `commit()`) |
| `InMemoryFaultStore` | Android-free store for unit tests |

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

3. **`DISALLOW_CREATE_WINDOWS`** — set by `AdminReceiver` in `onLockTaskModeEntering(...)`
   and cleared in `onLockTaskModeExiting(...)` to block apps from drawing over the kiosk.

4. **Crash-loop wiring** — `KioskLauncherActivity` gets a `CrashLoopGuard` from Hilt and
   calls `registerFault()` each time the pinned single app returns to HOME; once
   `isCrashLoopDetected()` trips, it drops kiosk and shows a recovery screen instead of re-pinning, so a misconfigured deployment can't brick the device.

## Crash-loop algorithm

Ported from `reference/.../util/CrashLoopProtection.java`. More than
`LOOP_CRASHES` (3) faults within `LOOP_TIME_SPAN` (60_000 ms) trips the guard — i.e.
the 4th crash inside the window. A fault after the window restarts the count; an
aged-out window resets on the next `isCrashLoopDetected()` check. The clock is
injectable (`now: () -> Long`) and the counter sits behind `FaultStore`, so the
logic is unit-tested on the JVM (`src/test/.../CrashLoopGuardTest.kt`).
