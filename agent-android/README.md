# MDMesh agent (Android)

A new, self-hosted **custom DPC** (Device Owner) Android MDM agent, with some parts ported from
the upstream project's agent (see [NOTICE](../NOTICE)).
Modern Kotlin: coroutines/Flow, Hilt, Room, DataStore, WorkManager,
kotlinx.serialization + Retrofit.

- `applicationId` / base namespace: `com.mdmesh.agent`
- `minSdk 24`, `targetSdk 35`, `compileSdk 35`
- Identity is a **server-issued device id** (DataStore). The agent never uses
  IMEI/IMSI/serial as identity (restricted post-Android 10).

> Building needs JDK 17 and the Android SDK: set `ANDROID_HOME`, or `sdk.dir` in
> `local.properties` (gitignored). Gradle itself comes from the committed wrapper.

## Module map

| Module | Type | Responsibility |
|--------|------|----------------|
| `:proto` | Kotlin/JVM lib | `@Serializable` wire contract mirroring `../proto/` (`CapabilityMatrix`, `CommandEnvelope`, `CommandResult`, `ProtocolJson`). No Android deps. |
| `:policy` | android-lib | **Capability-abstraction layer.** `DeviceControl`, `PolicyStrategy`, SDK-gated `WifiPolicy` (modern/legacy strategies + factory), `CapabilityRegistry`. All `DevicePolicyManager` calls stay behind interfaces. |
| `:core` | android-lib | Sync/check-in: Retrofit `MdmApi`, `CapabilityCollector`, `CommandDispatcher` (+ handlers), `DeviceIdStore` (DataStore), `CheckInCoordinator`/`CheckInWorker`. Base URL from the enrollment QR (`com.mdmesh.SERVER_URL`, kept in `ServerConfigStore`); `BuildConfig.MDM_BASE_URL` is the fallback for ADB enrollment. |
| `:kiosk` | android-lib | COSU lock-task: `KioskController`, its real impl `LockTaskKioskController` (+ `StubKioskController`), `CrashLoopGuard`, `ExitPinGate`. |
| `:remote` | android-lib | Remote view/control skeleton: `RemoteControlSession`, `RemoteControlTierDetector`, and the **only** Accessibility surface (`InputInjectionService`). |
| `:oem` | android-lib | `OemAdapter` + `GenericOemAdapter` (no-op) + `KnoxAdapter` (PARKED, no Knox dep). |
| `:app` | android-app | Hilt `Application`, `AdminReceiver`, provisioning activities, `MainActivity`, `KioskLauncherActivity` (the kiosk HOME), `CheckInService`, `LocalApiProvider` (the [local API](#local-api-offline-wi-fi-for-the-pinned-kiosk-app)), manifest with the minimal permission set. |

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

- Base `:app` manifest has **no** `READ_SMS`. It does request `QUERY_ALL_PACKAGES`
  (with a `<queries>` LAUNCHER entry): a sideloaded Device Owner agent manages arbitrary
  packages. A Play-distributed build would have to scope this to explicit `<queries>`
  ([ADR 0005](../docs/adr/0005-play-protect-permission-minimization.md)).
- **Accessibility / input injection lives only in `:remote`.** `:app` currently always
  includes `:remote`, so the service is in every APK, but its `AccessibilityService` ships
  `android:enabled="false"` and is toggled on only for an authorised control session.
- `READ_PHONE_STATE` / `READ_PHONE_NUMBERS` are requested for telemetry only; they are
  never used as identity, which is server-issued
  ([ADR 0003](../docs/adr/0003-server-issued-device-ids.md)).

## Build

```bash
./gradlew assembleDebug                                      # app/build/outputs/apk/debug/app-debug.apk
./gradlew detekt assembleDebug lintDebug testDebugUnitTest assembleDebugAndroidTest :proto:test   # what CI runs (T0)
```

> The Gradle wrapper is committed (`gradlew`, `gradlew.bat`, `gradle/wrapper/gradle-wrapper.jar`
> and `.properties`, pinned to Gradle 8.10.2), so no system Gradle is needed: the first `./gradlew`
> run downloads that version. Upgrade it with `./gradlew wrapper --gradle-version <version>` and
> commit all four files.

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

### Offline kiosk regression (manual)

On a fresh disposable Android emulator, follow the [ADB enrollment loop](#adb-device-owner-dev-enrollment-loop)
above, then run:

```bash
./gradlew :app:assembleDebugAndroidTest
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell svc wifi disable
adb shell svc data disable
adb shell am instrument -w -r -e class com.mdmesh.agent.OfflineKioskReturnTest \
  com.mdmesh.agent.debug.test/androidx.test.runner.AndroidJUnitRunner
```

CI builds the instrumented tests but doesn't run them, so run this after changing the classes they use. The tests
skip unless running on an offline Device Owner Android emulator with no existing kiosk or saved/delivered
configuration.

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

## Local API (offline Wi-Fi for the pinned kiosk app)

Since Android 10 an ordinary app cannot add, enable, disable or remove saved Wi-Fi networks, or switch the radio.
A kiosk tablet needs exactly that when it has no network at all, so the server cannot be involved: the agent, as
Device Owner, does it locally for the app it pins. The same app may also reboot the device (`device.reboot`), which
only a Device Owner or the system can do, so a kiosk can offer a Restart button of its own.

It is an exported content provider that implements only `call`:

| Build | Authority |
|-------|-----------|
| release | `com.mdmesh.agent.localapi` |
| debug | `com.mdmesh.agent.debug.localapi` |

```kotlin
val reply = contentResolver.call(Uri.parse("content://com.mdmesh.agent.localapi"), "wifi.saved", null, null)
```

On Android 11+ the caller must declare `<queries><provider android:authorities="com.mdmesh.agent.localapi" /></queries>`
(the debug authority too, if it talks to a debug agent), or the provider is invisible to it.

Logic: `:core` `localapi/` (`LocalApi`, Android-free and unit-tested); Wi-Fi calls: `:policy` `wifi/WifiNetworks.kt`;
provider: `:app` `localapi/LocalApiProvider.kt`.

### Methods

Every reply has `ok: Boolean`, `code: String`, `message: String` (never the password, never the SSID) and
`apiVersion: Int` (1), plus:

| Method | Extras | Does | Reply adds |
|--------|--------|------|------------|
| `describe` | — | Always answers, authorized or not. | `methods: ArrayList<String>`, `authorized: Boolean`, `reason` when not |
| `wifi.connect` | `ssid`, `password` (may be empty for open), `security`: `open` \| `wep` \| `psk` \| `sae` | Turns the radio on if off, removes every saved network with that SSID, adds the new one, `enableNetwork(id, true)`, `reconnect()`. SAE needs Android 10. | `networkId: Int` |
| `wifi.saved` | — | SSIDs of the saved networks, unquoted, each once. | `ssids: ArrayList<String>` |
| `wifi.forget` | `ssid` | Removes every saved network with that SSID. | — |
| `wifi.setAutoJoin` | `ssid`, `enabled: Boolean` | `enableNetwork(id, true)`, or `disableNetwork(id)` and leave it if Wi-Fi is on it. Credentials stay. | — |
| `wifi.disconnect` | — | `disconnect()`. | — |
| `wifi.setRadio` | `enabled: Boolean` | `setWifiEnabled(enabled)`. | — |
| `device.reboot` | — | `DevicePolicyManager.reboot()`, as the console's `device.reboot` command does. Android refuses at once or goes ahead without returning, so the reply waits up to 0.5 s for a refusal and otherwise answers `OK`, before the shutdown stops the agent: the device goes down a second or two later. Android refuses during a phone call, and only one registered with Telecom; a VoIP call is the app's to check before it asks. | — |

| Code | Meaning |
|------|---------|
| `OK` | Done (for `wifi.connect`: saved and joining; the connection itself follows). |
| `BAD_REQUEST` | A missing or mistyped extra, an SSID over 32 bytes, a password that cannot fit the security. |
| `UNAUTHORIZED` | See `reason` below. |
| `NOT_FOUND` | No saved network has that SSID. |
| `REFUSED` | `WifiManager` returned false or -1, or Android refused to reboot (during a phone call). |
| `UNSUPPORTED` | Unknown method, SAE below Android 10, or no Wi-Fi on the device. |
| `ERROR` | Something threw; the message names only the exception class. |

### Authorization

Checked on every call but `describe` (which reports it in `authorized` / `reason`), all of it on the device, so it
holds offline:

1. The agent is Device Owner — else `not_device_owner`.
2. The persisted kiosk (`KioskStateStore`) exists and is `mode == "single"` — else `no_kiosk` (launcher mode
   included).
3. The caller is its `pinPackage` — else `not_pinned_app`. The caller is `getCallingPackage()`, which the platform
   verifies against the calling UID; no extra or argument is trusted for identity.
4. The caller's installer of record is this agent — else `not_installed_by_agent`.

The provider is exported with no `android:permission`: the rule above is the access control. It adds no permission
to the agent (`ACCESS_WIFI_STATE`, `CHANGE_WIFI_STATE` and `ACCESS_FINE_LOCATION` were already declared), and the
installer lookup needs no package visibility, since an app calling the provider is visible to the agent.

Logcat (tag `LocalApi`) shows only the method and the code. Changes (`wifi.connect`, `forget`, `setAutoJoin`,
`disconnect`, `setRadio`, `device.reboot`) are also recorded in the event log as `localApi` `<method> <code>` and
reach the server on the next check-in.

### Caveats

- **Installer of record.** The app must be installed by the agent (`app.install`). An app that updates itself
  through its own `PackageInstaller` session becomes its own installer of record and gets `not_installed_by_agent`
  from then on. For development, `adb install -i com.mdmesh.agent.debug app.apk` records the agent as installer.
- **Location services.** Android hides the saved-network list from every non-system app while location is off,
  Device Owners included: `wifi.saved` is then empty, `forget` / `setAutoJoin` answer `NOT_FOUND`, and `connect`
  cannot remove an older entry for the SSID first.
- **`wifiRadio`.** `wifi.setRadio` can turn on a radio the configuration's `wifiRadio` Off turned off (a Device Owner
  is exempt from `DISALLOW_CHANGE_WIFI_STATE`); the configuration turns it off again on its next re-apply.

### Trying it from adb

```bash
adb shell content call --uri content://com.mdmesh.agent.debug.localapi --method describe
# Result: Bundle[{ok=true, code=OK, authorized=false, reason=not_pinned_app, methods=[describe, wifi.connect, ...], ...}]

adb shell content call --uri content://com.mdmesh.agent.debug.localapi --method wifi.saved
# Result: Bundle[{ok=false, code=UNAUTHORIZED, reason=not_pinned_app, ...}]
```

The shell (`com.android.shell`) is never the pinned app, so every `wifi.*` method answers `UNAUTHORIZED` from adb;
`describe` shows which check failed first. Extras are passed as `--extra key:type:value`, e.g.
`--extra ssid:s:Home --extra enabled:b:true` (recent Android versions). `adb logcat -s LocalApi` shows each call.
