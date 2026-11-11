# Managed App Runtime Permissions

**Status**: Implemented in v0.5.0 (protocol 1.6)
**Created**: 2026-11-09
**Scope**: A modern replacement for the inherited `appPermissions` configuration field. It does not revive the
Headwind `/sync` behavior.

---

## Problem

MDMesh currently makes the agent Device Owner and sets Android's global runtime-permission policy to auto-grant at
enrollment. That prevents prompts for apps the device manages, but offers no per-application restriction. The
inherited `appPermissions` values (`GRANTALL`, `ASKLOCATION`, `DENYLOCATION`, `ASKALL`) are not delivered to the
modern agent and must not be presented as working policy.

An administrator needs a narrow, auditable way to grant or deny sensitive runtime permissions to a configuration's
managed applications: for example, let a maps app use Location while denying a game Camera and Microphone.

## Findings

- The existing Device Owner agent already uses `DevicePolicyManager.setPermissionGrantState` for its own Location and
  notification permissions. The required Android authority and failure handling pattern therefore already exist.
- Android controls individual *runtime permissions*, not abstract permission groups. A policy must name Android
  permission strings and can only affect a package that requests that permission.
- Android 6/API 23 is the practical minimum for runtime-permission control. A Device Owner is required. Apps targeting
  earlier APIs and install-time permissions cannot be made to follow this contract.
- The current enrollment baseline calls `setPermissionPolicy(PERMISSION_POLICY_AUTO_GRANT)`. Consequently,
  `PERMISSION_GRANT_STATE_DEFAULT` means "return to the device-wide auto-grant baseline", **not** "ask the user".
  A UI must not label this state Ask.
- Special access controls—Accessibility, notification listener, exact alarms, unrestricted battery use, VPN, and
  Settings roles—are not normal runtime permissions and are explicitly out of scope.
- The current `config.apply` document has only Boolean `policies`; per-app permission state needs a new optional
  structured section. It must be an additive agent-v1 extension and capability-gated.

## Implemented First Release

Only configured applications with `action=install` are eligible. The console initially derives a curated candidate
list from the selected locally hosted APK; the agent checks the installed package before applying each rule and reports
the outcome. APK declarations are therefore candidates, not a claim that every device can apply the permission. For
each eligible package/permission pair, the administrator chooses:

| State | Agent action | Meaning |
| --- | --- | --- |
| Default | `PERMISSION_GRANT_STATE_DEFAULT` | Remove MDMesh's explicit per-app override and return to the existing global auto-grant baseline. |
| Grant | `PERMISSION_GRANT_STATE_GRANTED` | Explicitly grant the requested runtime permission. |
| Deny | `PERMISSION_GRANT_STATE_DENIED` | Explicitly deny the requested runtime permission. |

The UI uses a curated, readable list of dangerous runtime permissions—Location, Camera, Microphone, Contacts,
Calendar, Phone, SMS, media/files, notifications, and nearby-device permissions—while retaining exact Android
permission names in the contract. It deliberately excludes special app access such as `MANAGE_EXTERNAL_STORAGE`.
Do not offer a blanket "grant all" or "deny all" action, because it cannot explain the app's declared permissions
or the effect of future APK updates.

## Implemented Wire Contract

Add an optional `appPermissions` section to `config.apply`:

```json
{
  "appPermissions": [
    {
      "packageName": "org.example.maps",
      "permissions": {
        "android.permission.ACCESS_FINE_LOCATION": "grant",
        "android.permission.RECORD_AUDIO": "deny"
      }
    }
  ]
}
```

- An explicit empty section releases every explicit override the agent recorded for this configuration.
- An omitted section from an older server preserves the agent's durable active-rule record. This avoids treating an
  old server's unknown/absent field as an instruction to release working restrictions; a later explicit empty
  section remains the intentional release signal.
- A package omitted from a later document: release every override MDMesh recorded for that package to `DEFAULT`.
- A permission omitted from a listed package: release that override to `DEFAULT`.
- The agent applies only `grant` and `deny` (another state is reported `unsupported`). It queries `PackageManager`
  and reports a final non-error outcome, `not-requested`, when an installed package does not request a configured
  permission. A package that is not installed fails the document (retryable, not saved); as soon as `app.install`
  installs or updates the package, the agent applies the delivered document again (or only that package's rules, when
  it is already applied), so a new app's Deny holds from its first launch after install, and an update that starts
  requesting a configured permission gets the rule too.
- Add capability token `app.runtimePermissions`. The server sends the section only to agents advertising both
  `device.configApply` and `app.runtimePermissions`; older agents continue to receive the rest of their configuration.
- Add result keys such as `appPermissions.<package>.<permission>` to the existing outcome map. Failed release/apply
  results remain retryable through `ConfigStateStore`, just like current policy release failures.

This is additive-only (protocol 1.6): older agents receive a capability-specific document with the section removed
and a distinct revision. The console compares such a device with that revision when the configuration has no rules
(it is in sync), and with the full document's when it has some, so a device that can't enforce the rules never shows
as in sync. A device-reported permission inventory is future work.

## Persistence And Server Shape

Store rules independently of the deprecated `Configuration.appPermissions` scalar. The natural relationship is a
new table keyed by `(configurationId, applicationId, permission)` with a state enum (`grant` or `deny`); absence is
Default. Use the application assignment rather than an arbitrary package field so a permission rule cannot be saved
for an application outside the configuration. Resolve the assigned version's package name when building desired
state.

Configuration save validation must:

1. reject duplicate rules, unknown states, names other than `android.permission.*` (at most 128 characters) and more
   than 64 rules for one app;
2. drop the rules of an application the save leaves out or doesn't install (another path, such as the Applications
   page, can change a configuration's apps under the editor, so refusing would make it unsaveable);
3. preserve the rules when a save leaves the collection absent, following the established `extensions`/
   `applicationSettings` compatibility rules until every console version sends it.

Copying a configuration copies its rules.

The console belongs in an application's row or detail panel in the configuration editor, not in the global Security
section. The device Apps tab can display the last reported declared permissions and per-key apply outcome, but it
must distinguish *requested*, *configured*, and *Android currently granted* state.

## Deliberately Deferred

- **Ask at runtime.** This requires replacing or narrowing the global auto-grant enrollment baseline and has fleet-
  wide behavioral consequences. It is not a first-release option.
- **System or preinstalled apps.** They may be included after explicit API/OEM tests, but should not be implied by
  the initial configured-APK scope.
- **Permissions added by a later APK version.** Show them as unconfigured after device inventory reports the new
  manifest; never automatically grant them because an old rule had a similarly named group.
- **Per-device exceptions.** They should compose with the separate profile/override design, not be invented here.

## Verification

1. Kotlin unit and protocol tests cover Grant/Deny/Default, release behavior, absent-section compatibility, decoding,
   unknown states, not-installed retry and the apply after install.
2. Java tests cover save validation and pruning, copying, the capability-specific document and revision, and the
   console's sync revision for older agents.
3. Console tests cover candidate filtering and, specifically, keep the expandable disclosure inside its app row rather
   than creating a separate table row. Typecheck and production builds pass.
4. Live S8 validation passed for Samsung Camera: Deny blocked Camera as administrator, Grant restored use, and Default
   released the explicit MDMesh restriction. A representative media/files Deny → Default validation on File Manager
   also passed; app-specific handling after denial may return directly to launcher rather than opening Settings.
5. Still to check on a device: the apply right after a new app's install, and Android 12+ behavior for sensor
   permissions (location, camera, microphone, body sensors).

## Decision

Implemented as a separate multi-plane feature, not a follow-up to the Camera policy: a structured desired-state
section, its own table, a capability token and per-rule outcomes. Default means the device-wide auto-grant baseline,
not Ask; Ask stays deferred (above).
