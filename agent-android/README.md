# MDM Agent (Android)

A from-scratch, self-hosted **custom DPC** (Device Owner) Android MDM agent.
Modern Kotlin: coroutines/Flow, Hilt, Room, DataStore, WorkManager,
kotlinx.serialization + Retrofit.

- `applicationId` / base namespace: `com.mdmesh.agent`
- `minSdk 24`, `targetSdk 35`, `compileSdk 35`
- Identity is a **server-issued device id** (DataStore). The agent never uses
  IMEI/IMSI/serial as identity (restricted post-Android 10).

> This scaffold will not build on a box without the Android SDK/Gradle. It is
> structured to compile cleanly in CI or a provisioned dev box once an SDK is
> present.

## Module map

| Module | Type | Responsibility |
|--------|------|----------------|
| `:proto` | Kotlin/JVM lib | `@Serializable` wire contract mirroring `../proto/` (`CapabilityMatrix`, `CommandEnvelope`, `CommandResult`, `ProtocolJson`). No Android deps. |
| `:policy` | android-lib | **Capability-abstraction layer.** `DeviceControl`, `PolicyStrategy`, SDK-gated `WifiPolicy` (modern/legacy strategies + factory), `CapabilityRegistry`. All `DevicePolicyManager` calls stay behind interfaces. |
| `:core` | android-lib | Sync/check-in: Retrofit `MdmApi`, `CapabilityCollector`, `CommandDispatcher` (+ handlers), `DeviceIdStore` (DataStore), `CheckInCoordinator`/`CheckInWorker`. Base URL via `BuildConfig`. |
| `:kiosk` | android-lib | COSU skeleton: `KioskController` (+ stub), `CrashLoopGuard`. |
| `:remote` | android-lib | Remote view/control skeleton: `RemoteControlSession`, `RemoteControlTierDetector`, and the **only** Accessibility surface (`InputInjectionService`). |
| `:oem` | android-lib | `OemAdapter` + `GenericOemAdapter` (no-op) + `KnoxAdapter` (PARKED, no Knox dep). |
| `:app` | android-app | Hilt `Application`, `AdminReceiver`, provisioning activities, `MainActivity` launcher/home stub, `CheckInService`, manifest with the minimal permission set. |

### The capability-abstraction intent

Android is a permanent version treadmill — every OS release adds, removes, or
restricts a policy API. Rather than scatter `Build.VERSION.SDK_INT` branches through
feature code, each policy area defines an interface (`WifiPolicy`) with multiple
`PolicyStrategy` implementations; a factory picks the supported one **once**. The
`CapabilityRegistry` reports only the keys that have a working strategy, and that
set becomes the `capabilities` advertised in the `CapabilityMatrix`. The server is
contractually forbidden from sending a command whose `requiresCapability` isn't
advertised, so a 3-year-old agent on Android 9 and a fresh agent on Android 16 talk
to the same server without special-casing. Unknown command types degrade to
`status=unsupported` instead of crashing.

### Permission minimization (Play Protect)

- Base `:app` manifest has **no** `READ_SMS` and **no** `QUERY_ALL_PACKAGES`;
  package visibility is scoped via `<queries>`.
- **Accessibility / input injection lives only in `:remote`** (and merges in only
  for builds that include it). Its `AccessibilityService` ships
  `android:enabled="false"` and is toggled on only for an authorised control session.
- No phone-state / device-identifier permissions — identity is server-issued.

## Build

```bash
# One-time: generate the binary wrapper jar (cannot be committed from this box).
gradle wrapper --gradle-version 8.10.2

# Then the usual:
./gradlew assembleDebug
./gradlew test          # :proto + :core JVM unit tests
```

> The repo ships `gradlew`, `gradlew.bat`, and `gradle/wrapper/gradle-wrapper.properties`,
> but **not** `gradle/wrapper/gradle-wrapper.jar` (a binary). Run `gradle wrapper`
> once on a machine that has a system Gradle to materialise it.

Release signing reads from env vars (`MDM_RELEASE_STORE_FILE`,
`MDM_RELEASE_STORE_PASSWORD`, `MDM_RELEASE_KEY_ALIAS`, `MDM_RELEASE_KEY_PASSWORD`);
see the `// TODO(keystore custody)` note in `app/build.gradle.kts`. The DO binding
is tied to the signing certificate — re-signing a deployed DPC with a different key
forces a factory reset of every enrolled device, so guard the release key carefully.

## ADB Device-Owner dev enrollment loop

Device Owner can only be set on a device/emulator with **no accounts** (fresh or
factory-reset). Then:

```bash
# 1. Install the agent.
adb install -r app/build/outputs/apk/debug/app-debug.apk

# 2. Bind it as Device Owner (note the .debug applicationIdSuffix on debug builds).
adb shell dpm set-device-owner com.mdmesh.agent.debug/com.mdmesh.agent.admin.AdminReceiver
#   release build would be:
#   adb shell dpm set-device-owner com.mdmesh.agent/com.mdmesh.agent.admin.AdminReceiver

# 3. Confirm.
adb shell dumpsys device_policy | grep -i "Device Owner"
```

If `set-device-owner` fails with "Not allowed to set the device owner because
there are already some accounts" — remove all accounts, or factory reset.

### Getting OFF Device Owner (dev cycle)

A Device Owner cannot simply be uninstalled. To clear it:

```bash
# Clears the DO binding (works on debug/userdebug builds).
adb shell dpm remove-active-admin com.mdmesh.agent.debug/com.mdmesh.agent.admin.AdminReceiver

# If that is blocked, factory reset:
adb shell am broadcast -a android.intent.action.MASTER_CLEAR   # or wipe via Settings / recovery
```

On an emulator, the fastest reset is **Wipe Data** (cold boot) from the AVD
manager, then re-run the enrollment loop.

## Real provisioning (production)

Production enrollment is via QR / NFC / zero-touch using the
`GET_PROVISIONING_MODE` + `ADMIN_POLICY_COMPLIANCE` activities (already wired). The
ADB loop above is for the dev inner loop only.
