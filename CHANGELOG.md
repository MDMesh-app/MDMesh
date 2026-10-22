# Changelog

What changed in each MDMesh release, for the people running it, newest first. A version's section is its tag
annotation and GitHub release notes, word for word ([RELEASING.md](RELEASING.md)). From v0.3.2 on, its sections come
in this order, empty ones left out: Upgrading, Heads-up, New, Fixes, Under the hood. Feature releases (0.x.0) ship
every three weeks; patch releases (0.x.y) only for a security fix or something broken in the field. Each PR adds its
line under Unreleased.

## Unreleased

## v0.3.6 — 2026-10-21

Upgrading
- Update the server first, then roll out agent 0.3.6 from Settings → Agent rollout, to one device first. This is a
  security release for kiosk devices: update every server.
- Kiosk configurations without an exit PIN have no exit on the device with agent 0.3.6. Set an exit PIN first if you
  need one.
- Kiosk devices re-apply their configuration once after the server update, because Safe Mode is now blocked on them
  unless allowed.

Heads-up
- Kiosk: a kiosk without an exit PIN has no exit on the device; end it from the console. Set an exit PIN on the
  configuration to keep the on-device exit. Needs agent 0.3.6.
- Legacy device API (`LEGACY_DEVICE_API=1`): removing a Headwind content app from a configuration on the Apps page no
  longer turns kiosk mode off by itself. Turn kiosk off on the configuration page if you want it off.

Fixes
- Kiosk: a kiosk app that keeps stopping no longer ends kiosk. The device stays in kiosk on an "App closed" screen with
  an "Open again" button, until kiosk is ended with the exit PIN or from the console. Needs agent 0.3.6.
- Kiosk: a kiosk change that fails on a device already in kiosk leaves the device in the kiosk it had. Needs agent
  0.3.6.
- Kiosk: Recents and Notifications stay off while Home is off, as Android requires. Before, kiosk didn't start with
  that combination.
- Kiosk: too many wrong exit PINs lock the exit for a while, and administrators get an email (needs SMTP). Needs
  agent 0.3.6.
- Kiosk: Safe Mode is blocked on kiosk devices unless the configuration's Safe Mode setting says Allow.
- Saving app assignments on the Apps page no longer changes other configurations' settings, and devices pick up the
  change right away.
- A device whose agent stopped while applying its configuration (Android ended the app, the phone restarted, the
  agent updated) gets the configuration again on its next check-in. Before, newer changes could wait up to 6 hours.

## v0.3.5 — 2026-10-21

Upgrading
- Update the server: Settings → Updates (quick-start installs), or re-run the installer on native installs. Then
  roll out the new agent from Settings → Agent rollout, to one device first. Devices enrolled by QR code on 0.3.4 or
  earlier apply their restrictions once they have the new agent (or after a restart).

Fixes
- Agent: restrictions that need Device Owner (camera, screenshots, factory reset, Bluetooth, USB storage, Safe Mode,
  Wi-Fi) apply on devices enrolled by QR code. Before, they showed as "unsupported" until the phone restarted.
- Re-running the native installer to upgrade keeps `BASE_URL`, `GITHUB_TOKEN` and a `GITHUB_REPO` you set from the
  existing install, so `sudo ./setup.sh --native -y` is enough. A repo that came from the clone's origin keeps
  following the origin. A value you pass still wins, and `GITHUB_TOKEN=` clears the token.
- The native installer says when GitHub refuses the token, and downloads the release without it. Rate limits are
  reported as rate limits.
- Saving a configuration keeps its configuration files and app settings.
- Configuration copies keep their apps, and saving one never overwrites the original.
- Device pages always open the device you clicked, even when a device's number matches another device's internal id.
  Device action dialogs, Enter kiosk included, name the device.
- Enroll: "New code" keeps the chosen configuration.
- Location popups in the device map render as plain text.
- The "Password updated" notice shows on the sign-in page.
- The enrollment QR code is drawn at its real size, so it isn't blurred and scans reliably (thanks @vanek-Impacts).
- A device's recorded public address comes from the edge, not from headers the device sends. Native installs: set
  your proxy's headers and limit Tomcat's port to the proxy, as docs/OPERATIONS.md "Device public address" describes.

Under the hood: dependency updates (React Router 8, Caddy 2.11.7, pgjdbc 42.7.14, Vite, Guava), and CI runs the
agent's protocol tests on every change.

## v0.3.4 — 2026-10-14

A follow-up security release with console and agent fixes. Please update every server.

Upgrading
- Update the server first (Settings → Updates, or re-run the installer on native installs), then roll out
  agent 0.3.4 from Rollouts, to one device first. Server first matters: a new agent talking to an older
  server still has its location stored on "Do not track".
- After the update, devices on "Do not track" configurations re-apply their configuration once. Kiosks on
  agent 0.3.0–0.3.2 relaunch once, and a device someone took out of kiosk by hand goes back into kiosk if
  its configuration still has kiosk on.

Heads-up
- "Do not track" now really collects no location. It is the default for the configurations the installer
  creates and for new ones, so those devices stop reporting location. To keep it, set Location reporting to
  "Last known (passive)" (was "Network") or "GPS". Location already stored is kept.
- Kiosk exit PIN: new installs get a random PIN, shown (and editable) in the configuration editor. A
  configuration still on the old default 12345678 gets a warning in the console. Set your own PIN there.
- The Headwind launcher's QR routes now answer 404 unless LEGACY_DEVICE_API=1 (the console draws the
  agent's QR itself). The installer-created configurations get new QR keys.
- The read-only Observer role can no longer send push messages or edit device descriptions.
- SECURE_ENROLLMENT=1 also blocks the agent's APK downloads; the server now warns about it at startup.
  Leave it at 0 for MDMesh agents.

New (agent 0.3.4)
- Wi-Fi radio on/off per configuration.
  - Off is skipped while Wi-Fi is the device's only network, shown as deferred, and tried again at the
    next configuration change, reboot or agent update. Phones that drop mobile data while on Wi-Fi can't
    apply it.
  - If a device can't reach the server for about 30 minutes after Wi-Fi went off (SIM removed, airplane
    mode, server only reachable over Wi-Fi, a server outage), it turns Wi-Fi back on and keeps it on until
    the configuration changes. The console shows "Reset by device".
  - The old Wi-Fi setting, now "Wi-Fi settings", still only locks the settings screen.
- Lock volume buttons in kiosk. Android mutes the device while it's locked, so Ring device is silent.
  "Lock hardware buttons" is the power menu only.

Fixes
- The configuration editor shows only settings the agent applies, with clearer Not managed / Allow / Block
  choices and kiosk-only labels. Old Headwind launcher settings moved to their own section.
- The dashboard only says "in sync" after a complete check, and flags devices whose configuration isn't
  applied.
- "Deferred" results show as a warning instead of a failure.
- The hosted-APK check uses the server's address, and the device page shows just the agent version.
- App SHA-256 must be 64 hex characters.

Under the hood: agent protocol 1.4 (additive), unused Headwind dependencies removed.

## v0.3.3 — 2026-10-13

This is a security release. A full review of the server found a number of permission and input-handling
issues, several of them serious, mostly in code inherited from Headwind. All are fixed here. Please update
every server as soon as you can.

Upgrading
- Update the server first: Settings → Updates (quick-start installs), or re-run the installer on native
  installs. Then roll out the new agent from Rollouts, to one device first. Older agents keep working
  until they update.

Heads-up
- App installs are now always checked against a SHA-256. Uploaded APKs and F-Droid apps are covered
  automatically. For an app added by an external link, add its SHA-256 in the Library (new "SHA-256…"
  button) or upload the APK; until then its installs stop with a message saying so.
- The old Headwind launcher endpoints are now off by default (LEGACY_DEVICE_API=0). The MDMesh agent
  doesn't use them. Unused legacy routes (self-update, config-file and video uploads, stats, AppList
  upload) are removed.
- Organizations with two-factor sign-in turned on no longer get API (JWT) tokens.
- Users limited to some device groups only see and act on those devices. Creating, promoting and
  cancelling rollouts needs access to all devices.
- Changing your email address asks for your current password. Org admins can't create, edit or
  impersonate superadmin accounts.
- Icon uploads need the Settings permission (max 1 MB, 1024 px).

New
- Disable Safe Mode (Configuration → Restrictions), so Safe Mode can't be used to get out of kiosk.
  Needs the new agent.
- The native installer brings its own Node 24 and JDK 17 when the distro's are too old, so Debian 12/13
  and Ubuntu 24.04 work out of the box.
- Password-recovery email on native installs, and SMTP_SSL / SMTP_STARTTLS on Docker.

Fixes
- Agent updates that got stuck are sent again, and the agent no longer freezes waiting on an install.
- The agent connects as soon as it's enrolled, so commands don't wait for the first check-in.
- Moving a device to another configuration (e.g. kiosk) applies right away.
- Command results survive an agent restart, and large backlogs are sent in batches.
- Setting statuses refresh after an agent update, without a kiosk flicker.
- The old Headwind demo apps are no longer sent to devices.

Under the hood: agent protocol 1.3 (additive), Node 24 across the build, a stricter release pipeline.

Thanks @TalesRayn and @m87938510-ctrl for the detailed bug reports, and @kmehmonov for the Safe Mode request.

## v0.3.2 — 2026-10-08

Upgrading
- Update the server from Settings → Updates (quick-start installs) or with the command it shows.
  Then roll out the new agent from Rollouts. Agents on v0.3.1 keep working until they update.

Heads-up
- Kiosk: apps set to hide their icon are no longer allowed in the kiosk launcher. Tick
  "Show in kiosk" on any app you still want there.
- The old Headwind launcher entry is removed from the app library and from configurations
  that used it as the main app.

New
- Block factory reset (Configuration → Restrictions). Needs the new agent.
- "Show in kiosk" per app, separate from installing it.
- Remove apps from the Library. Devices keep the installed app.

Fixes
- Agents install APKs hosted on other servers, and pick up queued commands as soon as they reconnect.
- Manual and bulk "Enter kiosk" keep the configuration's launcher colours.
- One-click update and rollback work on Docker installs. Native install and upgrade fixes.
- An expired session takes you to sign-in, then back to the page you were on.
- Dialogs stay on screen on long pages.
- The device page shows the last known location.
- Configurations show app versions, and sync status refreshes after saving.
- Library: only installable apps are listed, no duplicate entries, uploaded versions are kept, and
  shared APK files are no longer deleted with another entry.

Under the hood: React 19, dependency updates, quieter logs.

Thanks @letternumber, @Keidevk and @benailjamim for the contributions.

## v0.3.1 — 2026-09-25

v0.3.1 — security fixes, Docker supervisor fix (#27), Cloudflare-mode fix

Security
- Agent admin and rollout APIs now enforce the `edit_devices` permission for every change: queueing commands (incl.
  wipe / passcode reset), bulk commands, sync, app sync, minting enrollment tokens, and creating, promoting or cancelling
  rollouts. Users without it (the seeded Observer role, or custom roles) now get "permission denied"; read-only views are
  unchanged. The built-in Administrator and User roles already have it.
- New auth tokens, password-reset tokens and generated passwords now come from SecureRandom (previously java.util.Random),
  and a build-time guard fails on any weak random generator. Reset links and server-generated org-admin passwords issued
  BEFORE this release came from the old generator: re-issue reset links and have those users change their password
  (changing a password also rotates that user's stored session token).
  The default JWT signing key (used only by API clients of /rest/public/jwt/login) is still generated at each server
  start, now from SecureRandom.
- Device command history no longer returns command payloads (configuration and kiosk commands embedded the configuration
  admin password hash); the API returns the target package as `subject` for app.install/app.uninstall. The queue endpoint
  (POST …/devices/{id}/commands) answers with the same slim view instead of echoing the payload. Integrations that read
  `payload` or `deviceNumber` from these endpoints must adapt.

Fixes
- Docker (#27): the updater/recovery supervisor crash-looped on every Docker install since v0.1.0
  ("Cannot find module '/project/server.js'"). Settings → Updates, the recovery page and the Docker /files/agent.apk
  download (the APK URL in enrollment QR codes) now work. On from-source Docker installs the recovery page shows status and
  the manual update steps (no one-click Roll back). Thanks @detobel36 for the diagnosis.
  Quick-start: `docker compose pull && docker compose up -d` (if .env pins SUPERVISOR_VERSION, set it to 0.3.1 first).
  From source: `git pull && ./setup.sh` (not just `docker compose up --build`).
  The supervisor now runs for the first time on existing Docker installs: take a DB backup before your first one-click update.
- Existing quick-start installs (CURRENT_VERSION=0.0.0) show "Update available: 0.3.1" after pulling; applying it once pins
  the versions (it takes a DB backup and restarts the server briefly). New quick-start installs pin the release they install
  (the supervisor stays on :latest so pulls deliver its fixes).
- From-source Docker installs no longer offer one-click Update (it could only fail and roll back); update with
  `git pull && ./setup.sh`, which now also records the running version from the checkout's tag on every run, so the console
  no longer shows a permanent false "Update available". Pull and native installs are unchanged.
- Cloudflare mode: the web container's Caddy config now starts with an empty ACME_EMAIL. Own-domain mode unchanged.

CI / release
- The supervisor image is smoke-tested (booted the way compose runs it) in the fast gate and before any release push, and the
  release publishes exactly the image that passed. `:latest` image tags move only after the release is published (same digest
  as `:VERSION`). The Caddyfile is validated in every hosting mode and all compose files are checked in CI.

## v0.3.0 — 2026-09-22

v0.3.0: desired-state configuration — saving a configuration now converges its devices

Configurations are now enforced on devices, not just stored.

- Server: on every check-in the server derives a desired-state document from the device's configuration, hashes it, and sends one `config.apply` command when the device has drifted (delivered in the same check-in). Saving a configuration wakes its devices. New admin endpoints: `GET /rest/private/agent/v1/devices/{id}/configStatus` and `GET /rest/private/agent/v1/configurations/syncSummary`.
- Agent: applies Wi-Fi, Bluetooth, USB storage, screenshots, kiosk (same path as `kiosk.enter`) and location capture mode; reports per-key outcomes (applied / unsupported / failed); persists the revision only when nothing failed; re-applies after boot or self-update. Advertises the new `configApply` capability.
- Console: an "Enforced" chip marks the 18 fields the agent applies; the rest are collapsed under "Legacy Headwind fields". Configuration list shows "N/M in sync"; device pages get a "Configuration status" card; kiosk-affecting edits ask for confirmation with the affected device count. The main-app picker now stores the correct application-version id.
- Protocol: additive (1.1). Old agents are never sent `config.apply` and show "Agent too old" until updated.

Behaviour change on upgrade: the first check-in of every updated device applies its configuration's managed policies, and devices whose configuration has kiosk ON enter kiosk. Kiosk is never lifted by the upgrade itself; turning kiosk OFF in a configuration only lifts it on devices that entered through that configuration. Location capture mode follows the configuration (GPS = active, otherwise passive). See DEPLOY.md "Upgrading to v0.3.0".

Database: two nullable columns on device_state (Liquibase, automatic). Docker installs: pull the new images as usual.

## Older releases

v0.1.0 to v0.2.9: see their notes on the [GitHub releases page](https://github.com/MDMesh-app/MDMesh/releases).
