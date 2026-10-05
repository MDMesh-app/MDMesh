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
| `wifi` | toggle/lock Wi-Fi | 24 | |
| `bluetooth` | toggle/lock Bluetooth | 24 | BLUETOOTH_SCAN auto-grant fails if target app targetSdk<=30 |
| `usbStorage` | block USB mass storage | 24 | |
| `camera` | disable camera | 24 | |
| `screenshots` | disable screenshots | 24 | |
| `factoryReset` | block/restore the user-initiated factory reset | 24 | `UserManager.DISALLOW_FACTORY_RESET`; Device Owner only; does **not** cover a wipe from recovery mode or fastboot. Not to be confused with `factoryResetProtection` below. |
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

## Command types

`requiresCapability` is what the console or server sets when it queues the command.

| type | requiresCapability | payload (sketch) |
|------|--------------------|------------------|
| `config.sync` | — | none (triggers a full reconcile); no console or server sender yet |
| `config.apply` | `device.configApply` | desired-state document, see `payloads/config-apply.schema.json`; result detail is `payloads/config-apply-result.schema.json` |
| `policy.apply` | `policy.<key>` (e.g. `policy.wifi`) | `{ policy: "wifi", value: false }` |
| `app.install` | `app.silentInstall` | `{ url, packageName, versionCode, sha256, runAfterInstall }`, or `parts: [{ url, sha256 }]` instead of `url`/`sha256` for a split APK |
| `app.uninstall` | — | `{ packageName }`; no console or server sender yet |
| `apps.scan` | — | none; result detail is `{ apps: [...] }` |
| `apps.icons` | — | `{ packages: [...] }`; result detail is `{ icons: [{ pkg, pngBase64 }] }` |
| `kiosk.enter` | — | `{ mode, allowedPackages, pinPackage, features, exitMode, password, theme }` (`KioskApplyPayload`) |
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
| `device.locationMode` | `device.locationMode` | `{ mode: "passive" \| "active" }` |
| `app.launch` | — | `{ packageName, activity? }`. Planned: no agent handler yet (a device replies `unsupported`). |
| `remote.startSession` | `remote.view` or `remote.control` | `{ sessionId, signaling: {...}, mode: "view"\|"control" }`. Planned: no agent handler yet. |
| `remote.stopSession` | — | `{ sessionId }`. Planned: no agent handler yet. |

Per-type payload JSON Schemas go in `proto/payloads/` as the registry grows.
