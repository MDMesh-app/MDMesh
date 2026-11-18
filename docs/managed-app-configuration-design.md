# Managed App Configuration

**Status**: Implemented (protocol 1.8), not released yet
**Created**: 2026-11-18
**Scope**: Android managed configurations ("app restrictions") for a configuration's managed apps, with a per-device
override. Issue #96, point 3. It gives the inherited Headwind *application settings* a modern delivery path; it does
not revive the Headwind `/sync` behavior.

---

## Problem

An app that a managed device runs often needs values from the MDM: the server URL of its own backend, a tenant, a
per-device enrollment token, a feature switch. Android's standard channel for this is the managed configuration: the
Device Owner sets a `Bundle` for a package with `DevicePolicyManager.setApplicationRestrictions`, and the app reads it
with `RestrictionsManager.getApplicationRestrictions()` (and is told about changes by
`ACTION_APPLICATION_RESTRICTIONS_CHANGED`). Capacitor, React Native, Flutter and native apps all have libraries for it.

MDMesh has no way to set it today. The server still carries Headwind's application settings (configuration-level and
device-level typed values), but only the legacy `/sync` endpoint delivers them, to the Headwind launcher, and the
console has no editor for them.

Some values differ per device (an enrollment token), so a configuration-level value alone is not enough: a device
must be able to override a key.

## Findings

- `configurationApplicationSettings` and `deviceApplicationSettings` exist since Headwind's 2017 schema: per
  `(extRefId, applicationId, name)` a `type` (`STRING`, `INTEGER`, `BOOLEAN`), a `value`, a `comment`, `readonly`,
  `variable` and `lastUpdate`. The legacy sync merges them per device, and the device row wins
  (`SyncResource.combineDeviceLogRules`). These are exactly the semantics this feature needs: the existing home, not a
  new table.
- `Configuration.applicationSettings` already round-trips through `GET`/`PUT /private/configurations` (an absent list
  is preserved), configuration copy and customer copy. `GET`/`POST /private/devices/{id}/applicationSettings` already
  read and replace a device's rows, but the `POST` checks no `edit_devices` permission and validates nothing.
- `variable` marks a value with `%NUMBER%`, `%IMEI%`, `%PHONE%`, `%DESCRIPTION%`, `%CUSTOM1..3%` placeholders. The
  legacy code substitutes them with `String.replaceAll`, so a device field with `$` or `\` breaks it.
- The audit plugin stores the body of a configuration save (`PUT /private/configurations`, and `/copy`). Without a
  change, every setting value would land in the audit log.
- `ApplicationSetting.toString()` prints the value, and the legacy `/public/sync/applicationSettings` logs it at debug.
- `setApplicationRestrictions` needs a Device Owner or Profile Owner (API 21+; the agent's minimum is 24). It
  *replaces* the package's whole bundle; an empty bundle clears it. `getApplicationRestrictions(admin, pkg)` reads it
  back. Android drops a package's restrictions when the package is uninstalled.
- `config.apply` already has a per-app structured section with the same lifecycle needs: `appPermissions` (1.7).
  This feature copies its shape: optional section, capability token, per-entry outcomes, release of omitted entries,
  re-apply after `app.install`, a capability-specific document for older agents.

## Wire Contract (protocol 1.8)

An optional `appRestrictions` section in `config.apply`:

```json
{
  "appRestrictions": [
    {
      "packageName": "hr.example.tablet",
      "values": {
        "serverUrl":   { "type": "string", "value": "https://vhp.example.hr" },
        "deviceToken": { "type": "string", "value": "…" },
        "kioskMode":   { "type": "bool",   "value": true },
        "pollSeconds": { "type": "int",    "value": 30 }
      }
    }
  ]
}
```

- `type` is `bool`, `string` or `int` (a 32-bit signed integer). `value` is the JSON boolean, string or number of
  that type. The server sends only values that parse as their type.
- Values are per configuration; a device can override a key, and the device value wins. A device can also set a key
  the configuration doesn't have. The server merges before it builds the document, so the agent sees one value per
  key. The document, and its revision, are therefore per device for a configuration with device overrides.
- A package gets an entry when it has at least one value. Entries are sorted by package and keys are sorted, like
  every other section (canonical JSON).
- Agent semantics:
  - **Omitted section** (an older server): change nothing. The agent keeps its durable record of the packages it set.
  - **Explicit empty section**: clear every package the agent set earlier.
  - **Package omitted** from a later document: clear it (an empty bundle).
  - **Listed package**: set its bundle to exactly these values (it replaces the whole bundle), then read it back with
    `getApplicationRestrictions`; a mismatch fails the entry.
- Outcomes, in the existing outcome map, one key per package: `appRestrictions.<package>`.
  - `applied`: set and read back.
  - `failed: app not installed`: retryable, the document is not saved. As soon as `app.install` installs or updates
    the package, the agent applies the delivered document again (or only this package, when the document is already
    applied), as it does for `appPermissions`. The app then finds its configuration at first launch.
  - `failed: <reason>`: Android refused, or the read-back differs. Retryable.
  - `unsupported`: the agent is not Device Owner, or the entry has a value type it doesn't know (a newer server's).
    Final. Nothing is changed for that package: a partial bundle is never set.
  - A clear that fails is reported `failed` on the package's key and retried with the next document, like a failed
    permission release. Clearing an app that is no longer installed succeeds: Android dropped its bundle with it.
  - The agent's durable record (`activeAppRestrictions`) is the packages the last document listed plus those whose
    clear failed, written after every apply of the section. Unlike the permission record, it is written even when an
    entry failed, so a package survives an older server's document in between and is still cleared later.
- Capability token `app.managedConfig` (agent key `managedConfig` in `capabilities.appManagement`, advertised when
  Device Owner). The server sends the section only to agents advertising both `device.configApply` and
  `app.managedConfig`.
- Older agents: the section is removed and the revision recomputed (the capability-specific document, as for
  `appPermissions`). For the console's sync status such a device is in sync when no package has values, and never
  when one has some, because that device doesn't apply them.

This is additive-only (ADR-0009): a new optional response field and a new capability token. Old agents ignore the
field (they never receive it anyway); an old server never sends it, and a new agent then changes nothing.

## Persistence And Server Shape

No new table. The configuration's values are `configurationApplicationSettings` rows, a device's overrides are
`deviceApplicationSettings` rows, both keyed by the application assignment (`applicationId`), not by a free package
name, so a value can't name an app outside the configuration.

Building the document (`DesiredConfigBuilder`, still pure): for each application the configuration installs
(`action=install`), take the configuration's rows, then the device's rows for the same application, the device row
replacing the configuration row of the same `name`. A row with `variable=true` gets its placeholders substituted with
a literal replace (not a regex). A row whose value does not parse as its type is skipped. Rows for applications the
configuration doesn't install are not sent.

Configuration save validation (with `normalizeManagedAppPermissions`, in the same place):

1. reject a duplicate `(applicationId, name)`, a blank name, a name over 200 characters (the column), an unknown type,
   a `bool` value other than `true`/`false`, an `int` value outside 32-bit, a value over 4096 characters, and more than
   64 settings for one app;
2. drop the settings of an application the save leaves out or doesn't install (as for permission rules);
3. keep the settings when a save leaves the list absent (unchanged, the existing rule).

Device read and save (`GET`/`POST /private/devices/{id}/applicationSettings`): require Headwind's
`edit_device_app_settings` permission (seeded for Admin and Super-admin; no code checked it before) and access to the
device, because a value can be a secret. The save gets the same validation per application and keeps only
applications the device's configuration installs (others are dropped, as on a configuration save). Then it wakes the
device, so it checks in and gets the new document.

The console's sync summary builds one document per configuration, and a device's own document only when it has
settings of its own (or a variable setting needs its fields), so a large fleet without overrides costs what it did.

Secrets: values can carry tokens. They never go to a log line or the audit text.

- The audit plugin masks `applicationSettings[].value` in a configuration save or copy, and records a device settings
  save with its keys and the values masked.
- `ApplicationSetting.toString()` and `SyncApplicationSetting.toString()` leave the value out.
- Log lines name the device, configuration, package and key, never the value. The outcome strings carry no value.
- The `config.apply` payload is stored in `agentCommand.payload`, as the kiosk exit PIN already is. The agent keeps
  the applied document in its private storage, as it keeps the PIN. Both are the device's own state.

## Console

- **Configuration editor**: an "Application settings" disclosure in a managed app's row (an app the configuration
  installs), next to its permissions: key, type, value, remove, and Add setting. A save sends `applicationSettings`.
- **Device page**: an "Application settings" card in the Apps tab, for each app the device's configuration installs:
  the configuration's values (read only, struck through when overridden), the device's overrides (edit, remove, add a
  device-only key). Saving replaces the device's rows and wakes the device. A role without `edit_device_app_settings`
  doesn't see the card.
- The config outcome view labels `appRestrictions.<package>` as "<package>: application settings", as it labels
  permission outcomes by package.

## Deliberately Deferred

- **A typed editor from the APK's `app_restrictions.xml`** (titles, choices, defaults). The first release takes free
  keys.
- **`bundle` and `bundle_array` types.** A later protocol version can add them under a new type name; this agent
  reports such an entry `unsupported` and leaves the package alone.
- **Apps the configuration doesn't install** (preinstalled system apps). They may follow with an explicit install
  action or a system-app path.
- **Wi-Fi / WPA2-Enterprise** profiles.
- **A device-reported inventory** of the restrictions an app declares.

## Verification

1. Kotlin unit and protocol tests: decoding (types, unknown type), set and read-back, clear of an omitted package,
   explicit empty section, absent section, not-installed retry, the apply after install, a failed clear kept for the
   next document.
2. Java tests: save validation and pruning, device save permission and validation, the merge (device wins, device-only
   key), variable substitution, the capability-specific document and revision, the console sync revision for older
   agents, and that the audit text and `toString` hold no value.
3. Console tests: the configuration editor disclosure inside the app row, the device page overrides, the outcome
   label. Typecheck and production build pass.
4. `scripts/agent-v1-e2e.sh`: a capable agent gets the merged section, an incapable one doesn't.
5. Live: on a test server, a configuration value and a per-device override reach a test app through
   `RestrictionsManager` on a Device Owner tablet; a removed value disappears.

## Decision

Implemented as a `config.apply` section like `appPermissions`, stored in the inherited application settings tables
instead of a new one: they already hold typed, per-configuration values with a device override that wins, and the
legacy sync already reads them that way. One concept, one home.
