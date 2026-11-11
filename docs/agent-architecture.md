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
Use `Utils.java`'s DevicePolicyManager catalog as a checklist for a typed Kotlin policy layer behind the
existing capability-abstraction (`:policy`), porting some calls and adding others: user restrictions (`DISALLOW_*`), `setCameraDisabled`,
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
configuration; an ad-hoc "Custom kiosk" from the console is left alone. After someone leaves the kiosk, the console's
"Return to configured kiosk" sends `kiosk.enter` with the configuration's own kiosk block (the same payload
`config.apply` carries), so the device is back in exactly that kiosk without waiting for a configuration change.
After a PIN exit, staff can tap **Return to kiosk** in the agent to restore the latest locally stored configured
kiosk, even offline; the button is available only outside kiosk on Device Owner devices whose configuration enables
it. It asks first, says so when that kiosk has no exit on the device, enters only the kiosk that was confirmed, and
finishes the entry even if the screen goes away (a rotation); once in kiosk it queues a check-in. A newer delivered kiosk-off configuration removes the option even if another policy failed; a remote
`kiosk.exit` leaves it available while the configuration still enables kiosk.

**The 26 enforced fields**

| Area | Fields |
|------|--------|
| Kiosk | `mainAppId`, `kioskMode`, `kioskExit`, `kioskHome`, `kioskRecents`, `kioskNotifications`, `kioskSystemInfo`, `kioskKeyguard`, `kioskLockButtons`, `kioskLockVolume`, `password` (exit PIN, stored as entered) |
| Launcher look | `backgroundColor`, `textColor`, `iconSize` (colours: see below); also the kiosk background image (`backgroundImageFileId`, set in the Kiosk section, agent 0.5.0; see below) |
| Device policy | `wifi`, `wifiRadio`, `locationServices`, `bluetooth`, `camera`, `usbStorage`, `disableScreenshots`, `disableFactoryReset`, `disableSafeBoot` |
| Location | `requestUpdates` (location capture mode) |
| Connectivity | `connectivityMode` (live-connection mode, 1.5) |
| App permissions | `managedAppPermissions` (per installed app: Grant or Deny a runtime permission, 1.7) |

**Kiosk colours on the device** (agent 0.5.0+, `KioskPalette`). A colour is `#RRGGBB`, `#AARRGGBB` or one of
Android's colour names (any case, nothing around it), read the same way in the console's preview and on the device
([kiosk-background-scrim.md](kiosk-background-scrim.md), Parsing); anything else means the default colour. `backgroundColor` is drawn solid:
any transparency in it is dropped. `textColor` is used when it has at least 4.5:1 contrast with the background (WCAG AA for text; the
launcher has no text large enough for the 3:1 large-text level); below that the launcher uses whichever of the v2
text colours (or black or white) reads best, for all of its text and buttons. Secondary text is the text colour at
80% over the background, kept at 4.5:1 or more; focused apps and buttons get a 2dp ring in the text colour. Older
agents use both colours as sent.

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
  - **`wifiRadio`** switches the radio; `wifi` still only locks the Wi-Fi settings. See below.
  - **`kioskLockVolume`** blocks volume in kiosk (`DISALLOW_ADJUST_VOLUME`). Android mutes the device along with it,
    so it's a separate opt-in. `kioskLockButtons` blocks only the power menu.
  - New columns are NULL on upgrade, so revisions only change for configurations that use them, and for
    Do-not-track configurations (whose devices get one `config.apply`).
- **1.5:** the `connectivity` section (`connectivityMode`): `adaptive` (Battery-saver, the agent's default) holds the
  wake socket only while the screen is on or charging; `alwaysOn` holds it 24/7, so commands reach a locked device
  without waiting for the next heartbeat. Applied through the same `PowerModeStore` as `device.powerMode`. NULL is
  not managed: the section is omitted, so existing revisions don't move, and each device keeps its mode, including
  one set by `device.powerMode`. A managed mode wins over `device.powerMode`: the saved document is applied again
  after every boot and agent update (`ConfigReapplyWorker`), and the server re-sends `config.apply` when the agent
  version changes, so a device-level mode lasts only until then. Switching a configuration from `alwaysOn` back to
  not managed leaves its devices on `alwaysOn`; set `adaptive` to switch them back. Older agents ignore it.
- **1.6:** the `locationServices` policy key: Android 11+ Device Owners turn Android Location services on or off and
  lock the setting while managed; Not managed (NULL, the upgrade value) lifts the lock and leaves the switch as it is.
  It is separate from `requestUpdates`. Agents without it, and Android 10 and older, report `unsupported`.
- **1.7:** the `appPermissions` section: per app the configuration installs, runtime permissions set to `grant` or
  `deny` (`DevicePolicyManager.setPermissionGrantState`, Device Owner). A rule left out goes back to Default, the
  device-wide auto-grant set at enrollment. An empty list releases every override the agent set; an absent section
  (an older server) leaves them. The agent applies a rule only to an installed app that requests the permission
  (`not-requested` otherwise, which doesn't block); an app that isn't installed yet fails the document, and the
  agent applies its rules as soon as its `app.install` succeeds, also after an update. Only agents that advertise
  `app.runtimePermissions` get the section; the others get the document without it, under that document's revision,
  and show as out of sync while the configuration has a rule.

**Kiosk background image.** A configuration's managed image is an uploaded file (`backgroundImageFileId`). On
save the server refuses anything but a PNG, JPEG or WebP of at most 10 MB and 16 megapixels (read from the header),
and stores the file's URL in `backgroundImageUrl` and its SHA-256 in `backgroundImageSha256`; the browser's values
are never used. `kiosk.theme.backgroundImage` is `{url, sha256}`, sent only when all three are set. Agent 0.5.0
downloads it over HTTPS only (no redirect to HTTP), up to 10 MB, checks the SHA-256 and the 16-megapixel bound before
caching it in private storage (only the current image is kept), and draws it behind the launcher grid, the splash
and the hold screen (not the idle screen), edge to edge under the system bars. An image is known by its SHA-256
alone: a new URL for the same file (a server that moved) causes no re-download and no re-entry into the kiosk.

- Placement (protocol 1.10, optional `fit` and `focalX`/`focalY`): fill (cropped, keeping the focal point in view;
  the centre by default) or fit (the whole image, letterboxed with the background color). Servers without them mean
  fill around the centre. A new fit or focal point redraws the same decoded image.
- Decoding: BitmapFactory only (JPEG, PNG, WebP lossy, lossless and with transparency, on Android 7 and up), never
  a hardware bitmap, off the main thread, once per image, window size and background color (again after a
  rotation), upright by the JPEG's EXIF orientation, cropped to what the window shows. At most 2560 px on the long
  edge, 1280 px and 2 bytes a pixel on a low-RAM device or one with a heap class of 128 MB or less; on running out of
  memory, once more at half size, then the launcher keeps what it shows.
- Readability: the dim and the text plates of [kiosk-background-scrim.md](kiosk-background-scrim.md), shared with
  the console's preview and its test vectors.
- Failures keep the last good image, else the background color. A download retries with backoff on the elapsed-time
  clock (30 s doubling to 6 h, with jitter; a new image starts over, a restart may try once at once), and the
  launcher looks again every time it comes to the foreground. A download is only used once its SHA-256 matches; a
  cached copy is checked again once per process (a corrupt one is fetched again); an image that matches its hash but
  doesn't decode is never fetched again. An arriving image never reopens the pinned app or lifts a crash-loop hold.
- The download is queued (WorkManager, on the network) as soon as a kiosk with an image is entered, so a single-app
  kiosk has it before its launcher next shows; the old image stays until the new one is verified.

With an `http://` server address devices keep the color. Older agents ignore the key (their
kiosk is re-applied once when the image changes the revision). `backgroundImageUrl` is also the Headwind launcher's
wallpaper: a save that sends another URL there stores it as that wallpaper and drops the managed image.

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

### Configuration apps (`app.install`)

`config.apply` carries no app list: the apps a configuration marks Install reach a device as `app.install` commands.

- **Enrollment** queues all of them (`ConfigAppInstaller`).
- **Check-in** (`AppReconciler`, agents that advertise `app.silentInstall`) compares the configuration's install set
  (its id and Install version ids) with the one stored for the device in `device_state.appsRevision`. Unchanged,
  that's the whole cost: one indexed select. When a save or Deploy's "Add to a configuration" adds an app or a new
  version, each version added gets an `app.install` in the same response. When the device was moved to another
  configuration, or has no set stored (enrolled after the update), its configuration's whole set is decided. Either
  way an app is skipped when the device's command history shows it installed (an install of that version or a newer
  one done, with no `app.uninstall` since) or in flight (pending, delivered or accepted), so enrollment's installs
  aren't sent twice. Saves and moves wake the devices, so this happens in seconds; a missed wake waits for the next
  regular check-in.
- **Retries:** an install that reached the device and didn't come back done (failed, or its result was lost) is sent
  again 15 minutes and then an hour later, three attempts in all (`AppReconcileDecision`). One the server refused (no
  SHA-256 for the APK) is recorded once. Only attempts from the last 24 hours count, so an app that failed long ago
  and is added again is tried afresh. The new set is stored once every app decided is installed or given up on.
- **Upgrade:** the update that added the column stored the current set of each device on a configuration, so it sends no installs to
  the fleet, and an app added after it installs at the next check-in, even on a device that was offline during the
  update. Apps a configuration already had are left as they are.
- **Install config apps** on the device page sends every app again (`POST .../syncApps`), e.g. after one was removed on
  the device. Removing an app from a configuration doesn't uninstall it.
- A hosted APK is hashed once per file (kept until its size or modification time changes), not once per device.

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

## Location collection

Before reading a location, the agent (as Device Owner) grants itself any missing foreground or background location
permission; devices enrolled by QR code used to lack the background one (agent 0.3.8). A missing grant is retried at
most every 15 minutes. The fix callback runs on the binder thread and telemetry on Dispatchers.IO, so a check-in never
blocks the main thread. With no cached position, "GPS (fresh fix)" gets up to 30 seconds for a first fix at most once
every 15 minutes; otherwise a fix has 5 seconds.
