# Full device inventory and per-app actions

This extends the current device page's existing **Apps** tab (including search, system filtering and CSV export).
It does not replace that tab or change configuration app actions.

**Scan now** requests all installed packages for the Android user managed by the agent. This includes apps without
launcher icons and policy-hidden apps, but excludes retained records of removed packages. Older scans are explicitly
labelled launcher-only. The kiosk picker keeps its launcher-only behavior even when reading a cached full scan.

## Actions

Users with `edit_devices` get per-row actions, after a scan from the updated agent:

- **Uninstall** confirms the app, package and device, then queues `app.uninstall`. This removes the app and its local
  data. An Install assignment in the configuration can install it again later. System-app removal is refused.
- **Disable** confirms before hiding the app with Android's Device Owner
  [`setApplicationHidden`](https://developer.android.com/reference/android/app/admin/DevicePolicyManager#setApplicationHidden(android.content.ComponentName,%20java.lang.String,%20boolean)).
  The app cannot be used while hidden, but its package and data remain. This is not PackageManager's enabled flag.
- **Enable** unhides a disabled app. Hidden apps stay in full inventory so they can be restored from the same row.

The agent checks protection when executing each command: its own package (including custom agent package names),
active administrators, the current launcher/keyboard, essential Android components, and apps in the current kiosk
are protected against removal and hiding. A protected package can still be unhidden to recover availability.
Other system apps can be hidden where Android permits it. A refusal or read-back mismatch is reported as a failure.
Protection is enforced even if a caller bypasses the console confirmation. The inventory's protection hints are for
presentation, not authorization.

Observers can read/export inventory but get no mutation controls. Older agents do not advertise the new app action
capabilities or the `appActions` full-scan flag; the console disables actions and asks for an agent update.

## Compact inventory display

The table shows App, Package, Version and Actions. Active user apps use the normal text color; active system apps
use orange-red text. Disabled apps use light gray text, including disabled system apps. The row's Enable/Disable
button indicates the available transition, and text tooltips describe both type and state. The system-app filter
and CSV export remain available. The Apps panel uses the available browser width and compact rows.

## Refresh and command results

The feature reuses the device page's polled history. It adds no inventory poll or package-event receiver.
Successful install, uninstall and visibility results invalidate older full inventory; one full scan refreshes it.
Manual/automatic requests do not overlap. Pending/failed commands do not claim a successful package change, and
the row's command status/result is shown while the last saved inventory remains visible.

For delivered app commands and the current scan, the console requests a sync at most every 15 seconds while
awaiting results. This wakes the agent to post its pending results; it never fabricates success or marks a
command complete. It respects page visibility and stops on navigation. An offline device still needs to reconnect.
Server scan-request time is used for freshness, avoiding browser-clock skew and late acknowledgements.

## Protocol and deployment

The feature adds only optional/defaulted inventory fields and a new command, retaining upstream protocol 1.9.
It does not redefine official protocol 1.6 (Location services). Existing launcher-only scans remain compatible;
`silentUninstall` and `setHidden` are advertised only while Device Owner, alongside upstream runtime-permission
management. This PR adds no database migration beyond those already in main.
Update the server/console and then the agent before using the new actions.

## Physical-device results

On 2026-11-12, the testing operator reported these successful operations on a real Android 13 device:

- Enrollment.
- Listing installed packages.
- Uninstalling packages.
- Disabling packages.
- Enabling packages again.

The device used testing build `0.5.0-pr147.20261112` (`com.mdmesh.agent.debug`, versionCode 402), based on
upstream main `6eb74da` plus these app-management changes. The console subsequently received the compact-list
presentation update; that update was checked separately in Chromium using synthetic fixtures.

These are operator-reported hardware results, not an automated hardware run. Device model, exact package names,
command IDs, uninstalling a previously hidden package, reboot/offline behavior, protected/system-app refusal and
data retention were not separately reported. Automated tests cover the guards, reversible visibility, payloads,
permissions, old-agent fallback and refresh/result handling; the report does not claim broader Android coverage.
