# Managed App Runtime Permissions

**Status**: Proposed design — no implementation in this branch
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

## Recommended First Release

Only configured applications with `action=install` are eligible. The console offers only permissions that the agent
reports the installed package requested. For each eligible package/permission pair, the administrator chooses:

| State | Agent action | Meaning |
| --- | --- | --- |
| Default | `PERMISSION_GRANT_STATE_DEFAULT` | Remove MDMesh's explicit per-app override and return to the existing global auto-grant baseline. |
| Grant | `PERMISSION_GRANT_STATE_GRANTED` | Explicitly grant the requested runtime permission. |
| Deny | `PERMISSION_GRANT_STATE_DENIED` | Explicitly deny the requested runtime permission. |

The first UI should use a curated, readable list of dangerous runtime permissions—Location, Camera, Microphone,
Contacts, Calendar, Phone, SMS, and media/files—while retaining exact Android permission names in the contract.
Do not offer a blanket "grant all" or "deny all" action, because it cannot explain the app's declared permissions
or the effect of future APK updates.

## Proposed Wire Contract

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

- Omitted section: no application permission state is managed by this configuration.
- A package omitted from a later document: release every override MDMesh recorded for that package to `DEFAULT`.
- A permission omitted from a listed package: release that override to `DEFAULT`.
- The agent validates package names, exact permission names, and states. It queries `PackageManager` and reports a
  final non-error outcome such as `not-requested` when an installed package does not request a configured permission.
- Add capability token `app.runtimePermissions`. The server sends the section only to agents advertising both
  `device.configApply` and `app.runtimePermissions`; older agents continue to receive the rest of their configuration.
- Add result keys such as `appPermissions.<package>.<permission>` to the existing outcome map. Failed release/apply
  results remain retryable through `ConfigStateStore`, just like current policy release failures.

This is additive-only: older agents ignore the unknown optional section, and the server capability gate avoids
claiming the configuration is applied on devices that cannot honor it.

## Persistence And Server Shape

Store rules independently of the deprecated `Configuration.appPermissions` scalar. The natural relationship is a
new table keyed by `(configurationId, applicationId, permission)` with a state enum (`grant` or `deny`); absence is
Default. Use the application assignment rather than an arbitrary package field so a permission rule cannot be saved
for an application outside the configuration. Resolve the assigned version's package name when building desired
state.

Configuration save validation must:

1. reject duplicate rules and unknown states;
2. require the application to remain assigned with install action;
3. remove rules when that application is removed from the configuration; and
4. preserve the rules when a save leaves the new collection absent, following the established `extensions`/
   `applicationSettings` compatibility rules until every console version sends it.

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

## Test Plan Before Implementation

1. Kotlin unit tests: Device Owner/API guards; requested/unrequested permissions; Grant, Deny and Default; release
   after a rule/package disappears; failed DPM call retried on the next apply.
2. Kotlin protocol/golden tests: absent field compatibility, strict decoding of malformed entries, capability
   advertisement, and complete result keys.
3. Java tests: configuration validation; desired-state canonical JSON/revision; capability-gated check-in; migration
   and delete/reassignment cleanup.
4. Console tests: picker only shows eligible application rows; accurate Default wording; changed/save/apply state;
   unsupported-device and not-requested outcomes.
5. Live test on the debug S8 and a current Android device: install an app requesting Camera/Location, deny then grant
   one permission, verify the Android behavior and server result, then remove the rule and verify the documented
   auto-grant-baseline behavior.

## Decision

Do not implement this as a small follow-up to Camera policy. It is a separate multi-plane feature with a new
structured desired-state section, database persistence, capability/result behavior, and release semantics. Start
only after the Default-versus-Ask product decision is accepted.
