# Capability & command registries (v1)

Open string registries. Adding a row is an additive (MINOR) change. Keep this file in sync
with the agent's capability advertisement and the server's command catalog.

A command's `requiresCapability` is a flattened **token**, not a bare key: the server turns each
advertised key into `policy.<key>`, `app.<key>` or `device.<key>` (plus `remote.<tier>` and `oem.knox`;
see `endpoints.md` and `AgentCapabilityTokens`), and delivers a command only if its token is in the
device's set. A command with no `requiresCapability` is always delivered.

## Capability keys

### policy
Advertised only when the device has a working strategy for it (`CapabilityRegistry` in `agent-android/policy`).

| key | meaning | min SDK | notes |
|-----|---------|---------|-------|
| `wifi` | lock/allow the Wi-Fi settings | 24 | `false` = `DISALLOW_CONFIG_WIFI` (+ configured-networks lockdown on API 30+). Does **not** switch the radio; see `wifiRadio`. |
| `wifiRadio` | switch the Wi-Fi radio on/off | 24 | 1.4. `WifiManager.setWifiEnabled` (Device Owner is exempt from the API 29 restriction). Off is deferred (outcome `deferred: <reason>`, does not block the document) while Wi-Fi is the only validated non-VPN network, and only tried again on the next config.apply or boot re-apply (a configuration change, reboot or agent update), so devices whose OEM drops mobile data while Wi-Fi is connected never apply it; on API 33+ off also adds `DISALLOW_CHANGE_WIFI_STATE`, on lifts it, and so does the key becoming unmanaged (radio left as is). Self-heal: if no check-in reaches the server for 30 minutes after Off turned Wi-Fi off (checked on the agent heartbeat), the agent turns Wi-Fi back on, lifts the restriction and holds Wi-Fi on until a document with a revision other than the last delivered one arrives (Off reports `deferred: no connection to the server ...`). It then reports `appliedConfigRevision` = 64 zeros, which matches no configuration, so the server re-sends config.apply after its normal backoff. Device Owner only. |
| `bluetooth` | toggle/lock Bluetooth | 24 | BLUETOOTH_SCAN auto-grant fails if target app targetSdk<=30 |
| `usbStorage` | block USB mass storage | 24 | |
| `camera` | disable camera | 24 | |
| `screenshots` | disable screenshots | 24 | |
| `factoryReset` | block/restore the user-initiated factory reset | 24 | `UserManager.DISALLOW_FACTORY_RESET`; Device Owner only; does **not** cover a wipe from recovery mode or fastboot. Not to be confused with `factoryResetProtection` below. |
| `safeBoot` | block/restore rebooting into Safe Mode | 24 | `UserManager.DISALLOW_SAFE_BOOT` (API 23); Device Owner only. Closes the Safe-Mode escape from a kiosk. From agent 0.3.7 the kiosk also blocks Safe Mode itself while in kiosk unless the configuration sends `true`, and every `config.apply` re-checks it last, so a block nothing asks for is lifted (a `policy.apply` block lasts until the next `config.apply`, which includes the re-apply at boot). |
| `gps` | location toggle | 24 | Planned: not advertised yet. |
| `mobileData` | mobile data toggle | 24 | Planned: not advertised yet. |
| `kioskLockTask` | COSU lock-task kiosk | 24 | Planned: not advertised; kiosk commands are ungated (see below). |
| `passwordComplexity` | password policy | 31 | Planned: not advertised yet. setRequiredPasswordComplexity (setPasswordQuality deprecated @26) |
| `systemUpdatePolicy` | OS update windows | 24 | Planned: not advertised yet. |
| `factoryResetProtection` | FRP policy | 30 | setFactoryResetProtectionPolicy; does not *prevent* a wipe, it decides whether a wiped device demands a Google account. Not implemented. |

### appManagement
| key | meaning | notes |
|-----|---------|-------|
| `silentInstall` | PackageInstaller silent install/upgrade, single or split APK | Advertised only while the agent is Device Owner. |
| `silentUninstall` | silent uninstall | Planned: not advertised yet. |
| `splitApk` | split/.xapk install | Planned key: not advertised; `app.install` already takes split `parts`. |
| `fdroidCatalog` | can pull from an F-Droid repo | Planned: not advertised yet. |

### remoteControl
Advertised as an object (`tier`, `screenCapture`, `inputInjection`, `transport`). See README. The agent
advertises `tier: "none"` until the `:remote` module is implemented, so no `remote.*` token is emitted yet.

### oem
`vendor`, `knox` (parked tier; `knox` is always `false` for now).

### device
Device actions (`DeviceAction.ADVERTISED_KEYS`). For each, the command type and the token are the same
string, except `configApply`.

| key | token | command |
|-----|-------|---------|
| `lock` | `device.lock` | `device.lock` |
| `reboot` | `device.reboot` | `device.reboot` |
| `lockscreenMessage` | `device.lockscreenMessage` | `device.lockscreenMessage` |
| `alert` | `device.alert` | `device.alert` |
| `ring` | `device.ring` | `device.ring` |
| `ringStop` | `device.ringStop` | `device.ringStop` |
| `passcodeReset` | `device.passcodeReset` | `device.passcodeReset` |
| `wipe` | `device.wipe` | `device.wipe` |
| `powerMode` | `device.powerMode` | `device.powerMode` |
| `locationMode` | `device.locationMode` | `device.locationMode` |
| `configApply` | `device.configApply` | `config.apply` |
| `locationOff` | `device.locationOff` | `device.locationMode` with `mode: "off"` (1.4: the agent honours `off`; older agents read it as passive) |

## Command types

`requiresCapability` is what the console or server sets when it queues the command.

| type | requiresCapability | payload (sketch) |
|------|--------------------|------------------|
| `config.sync` | — | none (triggers a full reconcile); no console or server sender yet |
| `config.apply` | `device.configApply` | desired-state document, see `payloads/config-apply.schema.json`; result detail is `payloads/config-apply-result.schema.json`; the `connectivity` section (1.5) sets the same modes as `device.powerMode` for a whole configuration |
| `policy.apply` | `policy.<key>` (e.g. `policy.wifi`) | `{ policy: "wifi", value: false }` |
| `app.install` | `app.silentInstall` | `{ url, packageName, versionCode, sha256, runAfterInstall }`, or `parts: [{ url, sha256 }]` instead of `url`/`sha256` for a split APK; `sha256` (hex) is required while the check-in says `installIntegrity: "required"` (1.3) |
| `app.uninstall` | — | `{ packageName }`; no console or server sender yet |
| `apps.scan` | — | none; result detail is `{ apps: [...] }` |
| `apps.icons` | — | `{ packages: [...] }`; result detail is `{ icons: [{ pkg, pngBase64 }] }` |
| `kiosk.enter` | — | `{ mode, allowedPackages, pinPackage, features, exitMode, password, theme }` (`KioskApplyPayload`); `features.lockButtons` blocks the power menu, `features.lockVolume` (1.4) blocks volume changes and mutes while in kiosk |
| `kiosk.exit` | — | none |
| `device.lock` | `device.lock` | none |
| `device.reboot` | `device.reboot` | none (DO) |
| `device.lockscreenMessage` | `device.lockscreenMessage` | `{ message }` (empty clears it) |
| `device.alert` | `device.alert` | `{ title?, body }` |
| `device.ring` | `device.ring` | `{ durationMs? }` |
| `device.ringStop` | `device.ringStop` | none |
| `device.passcodeReset` | `device.passcodeReset` | `{ newPassword }` (empty clears it) |
| `device.wipe` | `device.wipe` | none |
| `device.powerMode` | `device.powerMode` | `{ mode: "adaptive" \| "alwaysOn" }` |
| `device.locationMode` | `device.locationMode` (`device.locationOff` for `off`) | `{ mode: "passive" \| "active" \| "off" }`; `off` (1.4) reads no location at all |
| `app.launch` | — | `{ packageName, activity? }`. Planned: no agent handler yet (a device replies `unsupported`). |
| `remote.startSession` | `remote.view` or `remote.control` | `{ sessionId, signaling: {...}, mode: "view"\|"control" }`. Planned: no agent handler yet. |
| `remote.stopSession` | — | `{ sessionId }`. Planned: no agent handler yet. |

Per-type payload JSON Schemas go in `proto/payloads/` as the registry grows.
