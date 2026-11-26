# Policies extension

A policy console in the style of an endpoint-protection product, at `/policies`. It changes no core file and adds
no database table.

It works on its own, and faster with its server plugin (`plugins/fork/policies`): with the plugin the page loads every
phone's details in one request; without it, it asks each phone's endpoints (three requests per phone), as before.

## What it does

The page has three views (`?view=`), laid out like the device page: a list rail beside a tabbed main column.

- **Policies:** the policy list on the left (your policies, then the built-in ones). The selected policy
  (`?policy=<id>`) opens on the right with its feature tabs (`?tab=`):
  - Overview: security level (Strict, Balanced or Open sets every device control in one click) and a summary.
  - Device control: camera, screenshots, USB, Bluetooth; Wi-Fi settings, Wi-Fi radio, location services; factory
    reset, Safe Mode; location reporting and live connection.
  - Allowed apps: pick a host phone, scan it (apps with an icon), tick the apps the policy's phones may use, turn
    the kiosk screen on with an exit PIN. Every app not ticked is blocked from opening on every phone of the policy,
    including apps the host doesn't have. Below: what to do with extra apps (installed, not allowed) and per-phone
    exceptions.
  - Groups: the brand groups on this policy.
  - Phones: the phones on this policy and their status.
  - `/policies/<id>` opens the same view with that policy selected.
- **Groups:** brand groups (Oppo family = Oppo, Realme, OnePlus, and so on), built from the brand each phone
  reports. A brand belongs to one group, and a group to one policy. "Move the rest" puts a group's phones on its
  policy. The Brands table shows each brand's phones and group.
- **Status:** every phone, its brand and group, whether its policy applied, unsupported settings, and extra apps
  (user-installed apps outside its policy's allowed list) from its latest app scan.
  - Actions on each phone: Sync now, Scan apps, Move to its group's policy, Remove extra apps, Let it keep an app.
  - Bulk actions: Scan all phones, Move every misplaced phone.

## Where the data lives

- **Device controls, location and connectivity:** the configuration's own columns, the same ones the configuration
  editor shows. Phones get them through `config.apply`, as before.
- **Allowed apps and kiosk:** the configuration's own app list and `kioskMode`/`password` columns. An allowed app is
  a configuration app set to Install and shown in kiosk, which is exactly what the server sends phones as the kiosk
  allowlist (`DesiredConfigBuilder.kiosk`), so the console and the phones can't disagree. Unticking an app only hides
  it from kiosk; it is never uninstalled by that. A ticked app the Library doesn't know gets a package-only Library
  entry (no APK, like the seeded system apps), which the server never tries to install.
- **Groups, host phone and extra-app rules:** stored in the configuration's `extensions.policies` namespace
  (`model.ts`, `PolicyData`). This needs MDMesh 0.4.0 or later on the server, which added configuration extensions.
- **Saves:** a save sends the whole configuration back with its assigned apps and every other namespace, as the
  configuration editor does (`api.ts`, `saveConfig`).

## Limits to know

- **Blocking:** it is the kiosk (Android lock task) that blocks apps, so the allowed list works only while kiosk is
  on. With kiosk off, the list is kept but nothing is blocked.
- **Scans:** `apps.scan` lists apps a user can open (with an icon), not background packages. Scanning the host and
  fetching icons need the phone online.
- **Uninstalls:**
  - Extra apps are removed with `app.uninstall`, a silent uninstall the agent does as Device Owner.
  - Apps the phone marks as system are never listed as extra apps: Android doesn't let an MDM remove them, and kiosk
    already stops them opening. Only the phone's own flag counts: a maker's preloaded app the phone reports as a
    normal app (a Redmi's Clock, Calculator or Notes) is listed and can be removed like any other.
- **Auto-remove:** runs in the browser, while someone has the Policies page open. There is no server-side job.
- **App lists:** they come from app scans. Phones are scanned on demand (Scan all phones), not on a schedule.
- **New phones:** a phone isn't moved onto its group's policy by itself. The Compliance tab shows it as "On another
  policy than its group" until someone moves it.

## Tests

```bash
npx vitest run src/extensions/policies
```
