# Full device inventory and per-app actions

This extends the 0.4.0 device page's existing **Apps** tab (including search, system filtering and CSV export).
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

Protocol 1.6 adds only optional/defaulted inventory fields and a new command. Existing launcher-only scans remain
compatible; `silentUninstall` and `setHidden` are advertised only while Device Owner. No database migration is needed.
Update the server/console and then the agent before using the new actions. Hardware validation of this exact 0.4.0
branch remains pending; automated tests cover guard enforcement, reversible visibility, payloads, permissions,
old-agent fallback and refresh/result handling.
