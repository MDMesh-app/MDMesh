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
| `CrashLoopGuard` | crash-loop protection (ported from Headwind `CrashLoopProtection`) |
| `FaultStore` | counter persistence behind an interface |
| `SharedPrefsFaultStore` | production store (SharedPreferences, synchronous `commit()`) |
| `InMemoryFaultStore` | Android-free store for unit tests |

## What `LockTaskKioskController` does

Constructed directly with `(dpm: DevicePolicyManager, admin: ComponentName)` — no
`DpmHandle`. Guards `isDeviceOwnerApp` + `SDK_INT` on every call.

- **`enter(homeComponent, allowedPackages)`** — `setLockTaskPackages` (allowlist +
  the agent's own package), `setLockTaskFeatures(HOME | OVERVIEW | GLOBAL_ACTIONS |
  NOTIFICATIONS)` on API 28+, and `addPersistentPreferredActivity` for
  `ACTION_MAIN` + `CATEGORY_HOME` + `CATEGORY_DEFAULT` so the agent owns HOME.
- **`exit()`** — clears the allowlist, `clearPackagePersistentPreferredActivities`,
  resets lock-task features to default (API 28+).
- **`isLocked(context)`** — `ActivityManager.lockTaskModeState == LOCK_TASK_MODE_LOCKED`.
- **`allowedPackages()`** — `getLockTaskPackages(admin)`.

Lock-task base APIs need API 21; `setLockTaskFeatures` needs API 28. The module's
`minSdk` is 24, so the base APIs are always available and only features are guarded.
Real lock-task requires Device Owner (provisioned via `:app`).

## How `:app` wires it

`:kiosk` configures device-level policy; the kiosk activity in `:app` completes the
loop. `:app` is responsible for (done there, not here):

1. **Manifest** — the HOME/launcher activity declares
   `android:lockTaskMode="if_whitelisted"`, plus the launcher intent filter:

   ```xml
   <activity
       android:name=".kiosk.KioskActivity"
       android:lockTaskMode="if_whitelisted"
       android:launchMode="singleTask"
       android:exported="true">
       <intent-filter>
           <action android:name="android.intent.action.MAIN" />
           <category android:name="android.intent.category.HOME" />
           <category android:name="android.intent.category.DEFAULT" />
       </intent-filter>
   </activity>
   ```

2. **Start/stop lock task** — once `LockTaskKioskController.enter(...)` has
   allowlisted the package, the foreground kiosk activity calls
   `startLockTask()` (and `stopLockTask()` on exit).

3. **`DISALLOW_CREATE_WINDOWS`** — set in `onLockTaskModeEntering(...)` and cleared
   in `onLockTaskModeExiting(...)` to block apps from drawing over the kiosk.

4. **Crash-loop wiring** — register an uncaught-exception handler that calls
   `CrashLoopGuard(SharedPrefsFaultStore(context)).registerFault()`; on next launch,
   if `isCrashLoopDetected()` is true, skip auto-relaunch / exit lock-task and show a
   recovery surface (e.g. launcher chooser) so a broken DPC can't brick the device.

## Crash-loop algorithm

Ported from `reference/.../util/CrashLoopProtection.java`. More than
`LOOP_CRASHES` (3) faults within `LOOP_TIME_SPAN` (60_000 ms) trips the guard — i.e.
the 4th crash inside the window. A fault after the window restarts the count; an
aged-out window resets on the next `isCrashLoopDetected()` check. The clock is
injectable (`now: () -> Long`) and the counter sits behind `FaultStore`, so the
logic is unit-tested on the JVM (`src/test/.../CrashLoopGuardTest.kt`).
