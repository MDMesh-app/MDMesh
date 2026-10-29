# Upgrading MDMesh

<sub>[← README](../README.md) · [Deploy](../DEPLOY.md) · **Upgrading** · [Operations](OPERATIONS.md) · [Structure](../STRUCTURE.md) · [Contributing](../CONTRIBUTING.md) · [Releasing](../RELEASING.md)</sub>

| Install | Update with | One-click rollback |
|---------|-------------|--------------------|
| Quick start (Option A) | **Update** in the console, or automatic updates | Yes, from `/recovery` |
| From source (Option B) | `git pull && ./setup.sh` | Only with a registry `IMAGE_OWNER` ([below](#from-source-docker-installs)) |
| Native (Option C) | `git pull && sudo ./setup.sh --native` | No |

Before you update, read the [version notes](#version-notes) for each release you are moving past.

## How updates work

The **supervisor** offers only GitHub releases whose signed manifest verifies. On quick-start installs it also
applies them: it backs up the database, then rolls back if the new version fails its health check
([settings](OPERATIONS.md#updater-supervisor)). If no update shows up, see the `[verify]` line in
`docker compose logs supervisor` (native: `journalctl -u mdmesh-supervisor`).

## Quick-start installs

The install's admin (a super-admin, or the org admin of a single-customer install) clicks **Update** on the console's
banner. A failed update rolls back by itself.

**Automatic updates** (Settings, or `AUTO_UPDATE=1`) apply each release without a prompt. Once `/backups/auto.json`
exists (the Settings switch, or any failed or rolled-back update, creates it), it wins over `AUTO_UPDATE`; change it
in Settings. A release that failed, or that you rolled back
from, is never applied automatically again (`autoSkipped` in `/update/status`). **Update** still applies it by hand.

Updates never touch the supervisor. To pick up its fixes:

```bash
docker compose pull && docker compose up -d
```

This works because `SUPERVISOR_VERSION=latest`. If you pinned it, bump it by hand.

## From-source Docker installs

```bash
git pull && ./setup.sh
```

setup.sh takes no backup, so dump the database first ([command](OPERATIONS.md#backups-and-restore)). Don't use
`docker compose up -d --build` alone: only setup.sh updates `CURRENT_VERSION` and the image tags from the
checkout's release tag. Without a release tag, it keeps the old values and warns.

With a registry `IMAGE_OWNER`, one-click Update and rollback work as on the quick start, and setup.sh refuses to
build in two cases:

- **Older checkout.** The checkout is older than the running `CURRENT_VERSION`, for example
  `running 0.4.0, checkout is 0.3.1 — git pull first, or re-run with --allow-downgrade`. Run `git pull`.
  `--allow-downgrade` runs the older code on purpose, against the current database, which is not rolled back.
- **No version.** setup.sh can't read a release tag (a source tarball, or tags not fetched). Build from a tagged
  checkout, or pass `--allow-downgrade`. setup.sh then keeps the versions in `.env`; on a fresh install that is
  `0.0.0`, so every release shows as an update.

## Native installs

```bash
git pull && sudo ./setup.sh --native
```

Choose **Keep** (the default) or **Erase** (type `ERASE`). Keep runs migrations and leaves all data and the
enrollment secret alone, after a `pg_dump` to `/opt/mdmesh/backups/` ([restore](OPERATIONS.md#backups-and-restore)).

- **Base URL:** kept from the last run (`base.url` in `ROOT.xml`). An interactive run offers it as the default, and
  `-y` uses it. Set `BASE_URL` to change it.
- **Unattended (`-y`):** never erases unless you set `REPLACE_DATA=yes`. It keeps the current port unless you set
  `HTTP_PORT`.
- **Interrupted run:** run the same command again. It picks up where the last run stopped.
- **Settings:** SMTP, `LEGACY_DEVICE_API`, `GITHUB_REPO` and `GITHUB_TOKEN` carry over from earlier runs, Erase
  included. Pass one to change it; `GITHUB_TOKEN=` removes the token ([how](OPERATIONS.md#updater-supervisor)). On the
  first upgrade from v0.3.1 or older they are still kept, but that run's agent APK download can't read the old
  settings file in time: pass `GITHUB_TOKEN` and `GITHUB_REPO` once more if that download needs them.

## Recovery and rollback

### The recovery page

With one-click updates (`APPLY_SUPPORTED=1`), `https://<host>/recovery` shows the update status and a **Roll back**
button. Signed in as an admin who can update, you need no token. If the server is down, use the recovery token instead:

```bash
docker compose exec supervisor cat /backups/recovery.token
```

Other installs (`IMAGE_OWNER=local`, and native) get the update commands above instead of the button.
On native, open it on the host: `curl 127.0.0.1:9000/recovery`.

### What a rollback does

A rollback, after a failed update or from **Roll back**:

1. stops `server`;
2. restores the database dump taken just before the update;
3. sets the image tags and `CURRENT_VERSION` in `.env` back to the old release;
4. starts the old `server` and `caddy`.

**Anything written to the database after the update is lost.**

If the restore fails, nothing changes: it runs in one transaction, and `.env` still matches the database. `server`
stays **stopped** on purpose, so the old version can't run on the wrong database. Fix the cause, then press
**Roll back** again; it reuses the same backup. psql's full output:

```bash
docker compose exec supervisor cat /backups/<stamp>.restore.log
```

### Restoring by hand

From the install directory:

```bash
STAMP=$(docker compose exec -T supervisor cat /backups/latest)
docker compose exec -T supervisor cat "/backups/$STAMP.env"   # the pre-update versions
docker compose stop server
docker compose exec -T supervisor cat "/backups/$STAMP.sql" \
  | sed "1,/^SET lock_timeout = 0;\$/s/^SET lock_timeout = 0;\$/SET lock_timeout = '60s';/" \
  | docker compose exec -T postgres psql -X -v ON_ERROR_STOP=1 --single-transaction -U mdmesh -d mdmesh \
      -c "SET lock_timeout = '60s'" \
      -c "SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE datname = current_database() AND pid <> pg_backend_pid()" \
      -f -
```

Then:

```bash
# only if that succeeded: copy SERVER_VERSION, WEB_VERSION and CURRENT_VERSION from the .env snapshot above into .env
docker compose up -d --no-deps server caddy
```

The restore ends other sessions and gives up after 60 seconds without a lock. Keep the `sed`: without it, the dump's
own `SET lock_timeout = 0;` removes that limit.

## Agent compatibility

Older agents keep working across server updates ([ADR 0009](adr/0009-agent-v1-contract-stability.md)). Updating the
server doesn't update agents; roll them out from **Settings → Agent rollout** (shown once an agent APK is
available).

## Version notes

### v0.4.0

The console is new: every page was rebuilt. This part is for everyone who runs MDMesh. Forks that change the console
or add server plugins also read [v0.4.0 for forks](#v040-for-forks) below.

- **Updating.** As the [table](#upgrading-mdmesh) at the top says: quick start, **Settings → Updates**; from source,
  `git pull && ./setup.sh`; native, `git pull && sudo ./setup.sh --native`. The database update is automatic: on start, the server adds one column, `configurations.extensions`, set
  to `{}` for every existing configuration. There is nothing new to set: no new `.env` setting or server parameter,
  no new agent, no supervisor update, and no device re-applies its configuration.
- **Rolling back to 0.3.9 is safe.** A one-click rollback, and the restores in
  [Backups and restore](OPERATIONS.md#backups-and-restore), bring back the database from before the update, without
  the column. A 0.3.9 server started on the 0.4.0 database works too: it never reads or writes the column, and a
  configuration it creates gets the `{}` default. What a fork's plugin or extension stored there stays for the next
  0.4.0.
- **Pages and actions follow the role**, below.
- **Old bookmarks redirect.** The v0.3 console's `/dashboard` goes to `/`, and `/configs` to `/configurations`. The
  device page's tab is in its address now (`/devices/WH-12?tab=activity`; the tabs are `control`, `telemetry`,
  `activity` and `location`).
- **What's new.** After the update, each browser shows 0.4.0's notes once, after sign-in, and Settings → Server opens
  them again. A browser that never opened the console before doesn't show them. A tab left open on the v0.3 console
  during the update is signed out when the server restarts: reload it and sign in, and the notes show.
- **API changes.** `POST /rest/public/passwordReset/reset` answers OK with no data. It returned the account, with the
  new password's stored hash in `newPassword`. A configuration carries a new `extensions` object, which a save that
  leaves it out keeps; the configuration lists leave it out.

**Pages and actions follow the role.** The console hides what the server, or the updater, would refuse for the
signed-in role. Super-admin, Admin and User roles see every page; Observer sees Overview, Devices, Apps and Settings.
Configurations need the `configurations` permission, Apps `applications` and Enroll `edit_devices`, and a link to a
hidden page says "You don't have access to this page". The buttons follow these permissions:

- `edit_devices`: device actions and kiosk, Sync now, the Devices list's row and bulk actions, pushing an app to
  devices from Deploy, and Enroll. Agent rollouts also need a user not limited to some device groups.
- `edit_device_desc`: renaming a device.
- `edit_applications`: Add to library (F-Droid, Custom APK), Remove from library, and Deploy's "Add to a
  configuration". Deploy shows when the role may push or add, with only those choices.
- `edit_application_versions`: setting an app's SHA-256 checksum. `edit_files`: uploading on Custom APK.
- `add_config`: New configuration; a link to a new one says "You don't have access to this page". `copy_config`:
  Duplicate and Delete.
- Check now, Update and automatic updates in Settings → Updates, and the help that speaks of Update, show only to
  the admin who runs the install (a super-admin, or the org admin of a single-customer install, as since
  [v0.3.8](#v038)), whatever the role's permissions. Everyone else's sidebar update card says an admin who runs the
  install can apply it. The updater refuses everyone else, roll back included.

Every role still sees what it saw before, with two exceptions. Without `edit_devices`, the device page's Control tab
loses its Remote actions card, and with it the location mode and the kiosk and "Do not track" hints it showed. Apps
shows its Custom APK and F-Droid tabs only to a role that can add to the library or deploy; without them the Library
is the only tab, and a link to a hidden one opens it.

A role change reaches a console that is already open when its tab is shown again, or when the page is loaded, with no
new sign-in.

### v0.4.0 for forks

For forks that change the console or add server plugins; read [v0.4.0](#v040) first. From 0.4.0 a fork adds to the
console in `web/src/extensions/<id>/` and to the server in `plugins/fork/<id>/`, and edits no core file
([web/EXTENDING.md](../web/EXTENDING.md)).

**The new console replaces the v0.3 console.** Every page was rewritten, so a patch to a v0.3 page file won't apply.
Where they went (paths under `web/src/`):

| v0.3 | v0.4.0 | Add to it with |
|---|---|---|
| `App.tsx` (routes), `ui/AppShell.tsx` (sidebar) | `ext/core/routes.ts` (the routes), `ext/core/nav.ts` (the sidebar's items), `ui/Sidebar.tsx` (the sidebar), `ui/AppShell.tsx` (the frame around the pages) | `route.page`, `nav.item` |
| `pages/DashboardPage.tsx` | `pages/OverviewPage.tsx`, `pages/overview/` | `overview.card` |
| `pages/DevicesPage.tsx`, `components/BulkActionModal.tsx` | `pages/DevicesPage.tsx`, `ui/BulkModals.tsx` | `list.column`, `list.rowAction`, `list.bulkAction` |
| `pages/DeviceDetailPage.tsx`, `components/ActionConsole.tsx`, `ConfigStatusCard.tsx` | `pages/DeviceDetailPage.tsx` (data, rail), `pages/DeviceDetailControl.tsx` (Control tab) | `device.tab`, `device.railGroup` |
| `components/TelemetryCard.tsx`, `EventTimeline.tsx`, `LocationPanel.tsx`, `LocationMap.tsx` | `pages/DeviceDetailTabs.tsx` (Telemetry, Activity, Location), `data/deviceActivity.ts`, `ui/LocationMap.tsx` (moved) | `device.tab` |
| `components/KioskEnterModal.tsx`, `BulkKioskModal.tsx` | `pages/DeviceKioskDialog.tsx`, `ui/BulkModals.tsx` | — |
| `pages/ConfigurationsPage.tsx` (list and editor), `components/AppPicker.tsx`, `KioskChangeConfirm.tsx`, `SyncBar.tsx` | `pages/ConfigurationsPage.tsx` (list), `pages/ConfigEditorPage.tsx` (`/configurations/:id`), `pages/configurations/` (`sync.ts` has the sync line) | `config.section` |
| `pages/AppsPage.tsx`, `components/DeployModal.tsx` | `pages/AppsPage.tsx`, `AppsLibrary.tsx`, `AppsCustom.tsx`, `AppsFdroid.tsx`, `ui/DeployModal.tsx` | `list.column`, `list.rowAction` (`list: 'apps'`) |
| `pages/SettingsPage.tsx`, `components/RolloutPanel.tsx` | `pages/SettingsPage.tsx`, `pages/settings/` | `settings.section` |
| `components/UpdateBanner.tsx`, `ReloadPrompt.tsx` | `ui/Sidebar.tsx` (the update card), `pages/settings/UpdatesSection.tsx` (Update and its progress), `ui/ReloadPrompt.tsx` | — |
| `pages/EnrollPage.tsx`, `components/QrCanvas.tsx` | `pages/EnrollPage.tsx` (rewritten), `ui/QrCode.tsx`, `ui/QrPanel.tsx` | — |
| `pages/LoginPage.tsx`, `SetPasswordPage.tsx`, `ui/Wordmark.tsx` | `pages/LoginPage.tsx`, `SetPasswordPage.tsx` (rewritten), `pages/authCommon.ts`, `ui/AuthLayout.tsx`, `ui/Sidebar.tsx` (`Wordmark`) | — |
| `ui/status.ts` | `data/presence.ts` (`isOnline`), `ui/Status.tsx` (status pills); `statusMeta` is gone, and only `ui/DeployModal.tsx` still reads a device's `statusCode` | — |
| `styles.css` | `tokens.css`, one CSS file per page | the design tokens, `x-<id>-` classes |

**Port a page or tab onto slots:**

1. Move your files into `web/src/extensions/<id>/` and add an `index.ts` with `defineExtension`.
2. Replace each edit of a core file with a contribution: a page becomes a `route.page` and a `nav.item`; a device tab a
   `device.tab` that reads the device with `useDevice()` instead of the page's props; extra configuration fields a
   `config.section` that stores its value in the configuration's `extensions`, instead of a new column.
3. Import only `@mdmesh/console`, `react` and your extension's own files. Its tests may also import `vitest`,
   `@testing-library/react`, `@testing-library/dom` and `@mdmesh/console/testing`. If you need something
   `@mdmesh/console` lacks, open an issue: adding to it is cheap.
4. Run `npm test` in `web/`: it validates your manifest and the import rules.

**After the merge.** Merging 0.4.0 into a fork conflicts on every v0.3 page the fork changed. Then:

1. Run `npm run build` in `web/`. It stops first on each v0.3 console file still in the tree (`deleted by them` in
   `git status`), with one line per file saying where its content belongs now. Move your changes there or into your
   extension, then delete the file.
2. `web/src/styles.css` above all: nothing imports it any more, so without that check a kept copy would build and
   none of its styles would apply. The v0.3 class names (`btn`, `card` and the rest) are gone too, so fork CSS kept
   elsewhere stops matching without an error. Restyle with the design tokens and `x-<id>-` classes in your extension.
3. `components/` → `ui/`: git takes `web/src/components/` as renamed to `ui/` (`LocationMap.tsx` moved), so a file
   your fork added under `components/` gets `CONFLICT (file location)` and lands in `web/src/ui/`. The build can't
   tell it from a file you put in `ui/` on purpose, so look for it in `git status` and move it to
   `web/src/extensions/<id>/`.
4. Extensions: run `npm test` in `web/`. It checks each manifest and the import rules.
5. Plugins: move them under `plugins/fork/` as described below, then run the server build's tests
   (`mvn -B -pl server -am test`), which check the public routes.

**Your contributions follow the role too.** Pages and actions follow the role ([v0.4.0](#v040)), and your items
follow the same check through their `requires`, so give each the permission its server calls need. When a role
changes, an unsaved edit in a configuration section an extension added, which the new role can't see, is dropped,
and a message says so. Without extensions, the console is as it is for everyone else.

**Server plugins** go in `plugins/fork/<id>/`. `init-plugin.sh` registers a plugin folder by editing core's poms: it
adds a `<dependency>` to `server/pom.xml` and a `<module>` to `plugins/pom.xml`. (The `<module>` line it writes is
literally `<module>%~1</module>`, not the plugin's name.) For such a plugin, move its folder under `plugins/fork/`,
set its parent to `fork`, take its lines out of those two poms, and add it to `plugins/fork/pom.xml` and
`plugins/fork/bundle/pom.xml`. The plugin scan now also covers `com.mdmesh`. Keep the conventions (web/EXTENDING.md,
Server plugin): REST under `/rest/plugins/<id>/`, tables `plugin_<id>_*` and permissions `plugin_<id>_*` (dashes as
underscores), changeSet ids `plugin-<id>-*`, and one `plugins` row, without which the plugin is off for every
customer.

**The AngularJS parts of plugins are dead.** The server no longer copies a plugin's `src/main/webapp` (its AngularJS
module, views and translations) into the WAR, as the console that loaded them is gone. The `plugins` row's
`javascriptModuleFile`, `functionsViewTemplate` and `settingsViewTemplate` are not read by the console. Port a
plugin's screens to a console extension.

### v0.3.9

A security release. Update every server. Devices need no new agent, and the supervisor is unchanged: quick-start
installs apply it from **Settings → Updates**, from-source installs run `git pull && ./setup.sh`, and native installs
`git pull && sudo ./setup.sh --native`.

- **Sign-in limits.** Failed sign-ins are limited per address and per account, for the console, for API clients
  (`/rest/public/jwt/login`) and for the current-password check of a password or e-mail change. A browser or API
  client that signed in to an account before (it keeps the `MDMESH_SIGNIN` cookie), and an address that signed in to
  it in the last 30 days, keep signing in while the account is limited; a new browser on a new address waits. The
  console says "Too many failed sign-ins"; the API answers `429` with `Retry-After: 900`.
  [Sign-in limits](OPERATIONS.md#sign-in-limits) has the numbers and what to do during an attack.
- **API clients:** a token sign-in now also sets the `MDMESH_SIGNIN` cookie (path `/rest/public`). Keep it and send it
  with the next sign-in to be spared someone else's failed sign-ins. Clients that ignore cookies work as before.
- **https installs: the session cookie is Secure.** When `BASE_URL` starts with `https://`, the browser sends the
  console's session cookie over https only. Sign in at the https address: signing in over plain http (Tomcat's own
  port on a native install, say) no longer works. http installs (a LAN address) are unchanged.
- **Security headers.** Every response says the console may not be framed (`X-Frame-Options: DENY`,
  `frame-ancestors 'none'`), `nosniff` and `Referrer-Policy: same-origin`; https installs also get
  `Strict-Transport-Security: max-age=15552000` (six months, this host only). On Docker the edge sends them; on native
  installs the server does, so a proxy of yours needs no change. A page of your own that framed the console stops
  working.
- **Ambiguous paths are refused.** A request path with a `;` or an encoded `/`, `\`, `;` or `.`, or with `//`, `.` or
  `..` segments, gets `400`. The console, the agent and the QR never send one. A hosted file whose name contains `;`
  can't be downloaded any more: upload it again (uploads replace `;` with `_` now).
- **Native installs behind a proxy on another host: list it in `proxy.addresses`.** The server believes
  `X-Forwarded-For` for the sign-in limit only from a proxy on loopback or one listed there (a proxy in a container
  counts as another host). Without the entry, the proxy is taken for a single client, so every client behind it
  shares one address limit: 10 failed sign-ins from anyone refuse every new browser for 15 minutes (browsers and
  addresses that signed in before keep signing in). The server logs a warning naming the proxy (`... is not a trusted
  proxy`): re-run the installer with `PROXY_ADDRESSES=<its address>`, or add it to `proxy.addresses` in `ROOT.xml`
  and restart. Installer re-runs keep it from now on. A LAN-only install with no proxy needs nothing: each client
  counts by its own address ([Sign-in limits](OPERATIONS.md#sign-in-limits)).
- **Docker behind a CDN or another proxy in front of Caddy** (Cloudflare's proxy in own-domain mode, a load
  balancer): add its ranges to `TRUSTED_PROXIES` in `.env`, or every client shares one address limit. A compose file
  from before v0.3.9 (updates keep yours) doesn't pass that variable to Caddy: add one line to it first
  ([Sign-in limits](OPERATIONS.md#sign-in-limits)). The Cloudflare tunnel mode needs nothing.
- **Docker own-domain installs reached over IPv6:** with Docker's default IPv4-only networks, every IPv6 client
  reaches Caddy from one gateway address and shares one address limit. Turn on IPv6 for the stack's network, or
  remove the domain's AAAA record ([Sign-in limits](OPERATIONS.md#sign-in-limits)).

### v0.3.8

A security release. Update every server. Devices need no new agent.

- **Update the supervisor too.** Part of this release is in the supervisor, and updates never touch it:
  - **Quick start:** apply 0.3.8 from **Settings → Updates**, then run `docker compose pull && docker compose up -d`
    in the install directory. The quick start sets `SUPERVISOR_VERSION=latest`; if you pinned it in `.env`, set it to
    `0.3.8` first.
  - **From source:** `git pull && ./setup.sh`, also after applying 0.3.8 from the console with a registry
    `IMAGE_OWNER`. setup.sh pins `SUPERVISOR_VERSION` to the checkout's release and rebuilds the supervisor, so
    `docker compose pull` alone keeps the old one.
  - **Native:** `git pull && sudo ./setup.sh --native`, which updates both.

  Either order works. In front of an older server, a 0.3.8 supervisor lets only a super-admin, or the org admin of
  customer 1, update.
- **Who can update:** the admin who runs the install. That is a super-admin, or the org admin (the built-in
  Administrator role) of a single-customer install. Every install made with quick start, `setup.sh` or the native
  installer is single-customer and has no super-admin, so its admin keeps updating. Another admin with a custom role
  can't, even with the Settings permission. Once an install has a second customer, only a super-admin can apply an
  update, roll back, check for updates or switch automatic updates. The updater refuses everyone else, and the
  console hides the buttons from them. The recovery token still rolls back when the server is down.
- **Customers you didn't create:** check every single-customer install ([below](#customers-you-didnt-create)).
- **A new password signs the account out elsewhere:** setting a password (a reset link, the change at first sign-in,
  the user's own change, an admin's change) ends that account's other console sessions and its API tokens
  (`/rest/public/jwt/login`). The console session that changed its own password stays signed in. API clients sign
  in again.
- **Reset links:** a password reset link works once, for 24 hours. Links sent before the update no longer work; ask
  for a new one. An account that must change its password at first sign-in gets a fresh link when it signs in, so
  freshly installed admins are not affected. An account gets at most 3 recovery mails an hour.
- **Self-signup:** the `/rest/public/signup/*` API is removed (404). MDMesh never offered it. The `customer.signup*`
  and `email.signup*` settings are ignored.
- **Impersonation is a POST:** `/rest/private/users/impersonate/{id}` and `/rest/private/customers/impersonate/{id}`
  answer only POST; a GET gets 405. The console doesn't use them. A script that calls them must POST.
- **Session cookie:** the console's session cookie is `SameSite=Lax`. Docker sets it on every start, native installs
  when the installer rewrites `ROOT.xml`.

#### Customers you didn't create

A single-customer install has one customer, id 1. Before 0.3.8, the self-signup API could add more. From 0.3.8 on,
the server lists any others at startup, in `SECURITY:` lines:

```bash
docker compose logs server | grep 'SECURITY:'        # native: journalctl -u mdmesh-server | grep 'SECURITY:'
```

Or ask the database (Docker; on native, run the query as in [Removing a customer](OPERATIONS.md#removing-a-customer)):

```bash
docker compose exec -T postgres psql -X -U mdmesh -d mdmesh -c \
  "SELECT id, name, email, to_timestamp(registrationTime / 1000) AS created FROM customers WHERE id > 1"
```

No rows means you are not affected. Remove each customer you didn't add yourself, with
[Removing a customer](OPERATIONS.md#removing-a-customer). Until then the install counts as multi-customer: only a
super-admin may update it, and it has none. Removing them restores that.

### v0.3.7

Completes the Safe Mode part of 0.3.6. Update the server, then roll out agent 0.3.7 from **Settings → Agent
rollout**, to one device first.

- **Safe Mode in kiosk:** agent 0.3.7 blocks Safe Mode whenever the device is in kiosk, however kiosk was started
  (a configuration, or **Enter kiosk** on the device page), unless the configuration's Safe Mode setting is **Allow**.
- **When the block is lifted:** once nothing asks for it. That is when a kiosk started with **Enter kiosk** ends, or
  when kiosk is turned off on the configuration, unless the setting is **Block**. A kiosk configuration keeps Safe Mode
  blocked while it has kiosk on, even after someone leaves kiosk with the PIN.
- **Not managed** now really means not managed outside kiosk: agent 0.3.7 lifts a Safe Mode block nothing asks for.
  On older agents, a device that left a kiosk configuration keeps Safe Mode blocked until the setting is **Allow**.
- **Until devices have agent 0.3.7:** a device put into kiosk with **Enter kiosk**, on a configuration without kiosk,
  can still reboot into Safe Mode. Set Safe Mode to **Block** on that configuration.

### v0.3.6

A security release for kiosk devices. Update every server, then roll out agent 0.3.6 from **Settings → Agent
rollout**, to one device first.

- **Exit PIN:** with agent 0.3.6, a kiosk configuration without an exit PIN has no exit on the device; kiosk then ends
  only from the console. Set an exit PIN on the configuration before the rollout if people need to leave kiosk on the
  device.
- **Wrong PINs:** 3 wrong exit PINs lock the exit for 30 seconds, and each further wrong PIN locks it for longer, up to
  an hour. A restart doesn't end a lockout. Administrators who can manage the device get an email, if SMTP is set up
  ([Kiosk alerts](OPERATIONS.md#server)).
- **Safe Mode:** kiosk configurations whose Safe Mode setting is **Not managed** now block Safe Mode. Kiosk devices
  get their configuration again once after the server update, which also applies it to devices that hadn't received
  the latest version. That puts a device you took out with **Exit kiosk** back into its configuration's kiosk, and
  starts kiosk on configurations whose Recents or Notifications without Home had kept it from starting. To allow Safe
  Mode, set it to **Allow**. See v0.3.7 for kiosks started from the device page.
- **A kiosk app that keeps stopping** leaves the device in kiosk on an "App closed" screen with an **Open again**
  button. Before, kiosk ended.
- **Apps page:** saving app assignments no longer changes other configurations' settings. With `LEGACY_DEVICE_API=1`,
  removing a Headwind content app no longer turns kiosk off.

### v0.3.4

Update the server, then roll out agent 0.3.4 from **Settings → Agent rollout**. Then check location reporting and
kiosk PINs.

- **Location:** **Do not track** now collects no location: agent 0.3.4 reads none, and the server drops what older
  agents send. It is the default for new configurations and the ones the installer creates. To keep location, set
  **Location reporting** to **Last known (passive)** (called *Network* before) or **GPS (fresh fix)**.
- **Wi-Fi radio** (new, off by default; needs agent 0.3.4, and older agents report it as unsupported) turns Wi-Fi on
  or off.
  - Off is deferred while Wi-Fi is the device's only network. It is retried at the next configuration change, reboot
    or agent update.
  - Phones that turn mobile data off while on Wi-Fi can't apply it.
  - If the device can't reach the server for about 30 minutes after Wi-Fi goes off, the agent turns Wi-Fi back on.
    Causes include a removed SIM, a server only reachable over Wi-Fi, a server outage that long (keep upgrades
    short), or airplane mode. Wi-Fi then stays on, shown as deferred, until the configuration changes.
  - The old Wi-Fi setting, now named **Wi-Fi settings**, still only locks the Wi-Fi settings screen.
- **Lock volume buttons (kiosk)** (new, off by default; needs agent 0.3.4, and older agents ignore it) blocks volume
  changes in kiosk. Android mutes a locked device, so **Ring device** is silent on it.
- **Lock hardware buttons** blocks only the power menu.
- **Headwind QR routes:** `/rest/public/qr/*` returns 404 unless `LEGACY_DEVICE_API=1`; the console doesn't use
  them. The three installer-created configurations get a new QR key on first start. With `LEGACY_DEVICE_API=1`, the
  Headwind QR codes printed for them before stop working.
- **Kiosk PINs:** new installs give each installer-created configuration a random 8-digit exit PIN, shown in the
  configuration editor. Existing PINs are kept. The console flags any still set to the old default `12345678`;
  change it, since anyone who knows the default can leave kiosk.
- **Observer role:** loses the push and messaging send permissions and device-description editing.
- **`SECURE_ENROLLMENT=1`** still makes `/files/*` require the Headwind launcher's signature, which also blocks the
  agent's APK and app downloads. With `LEGACY_DEVICE_API` off, the server now warns about it at startup. Set it to
  `0` unless you need launcher-signed downloads.

### v0.3.3

- The Headwind launcher's device routes (`/rest/public/sync/*`, notifications, plugin device uploads, usage stats)
  return 404 unless `LEGACY_DEVICE_API=1`.
- App installs now carry the APK's SHA-256, and current agents refuse an install without one. Hosted APKs are hashed
  automatically. For a Library app with an external URL, add the hash with the **SHA-256…** button, or upload the
  APK instead.

### v0.3.1

On every Docker install from v0.1.0 to v0.3.0, the supervisor restarted in a loop with
`Cannot find module '/project/server.js'` (#27). As a result, updates, `/recovery` and the `/files/agent.apk` mirror
never worked. The fix is in the image, so pull it by hand:

- **Quick start:** `docker compose pull && docker compose up -d`. If `.env` pins `SUPERVISOR_VERSION`, set it to
  `0.3.1` first.
- **From source:** `git pull` and re-run `./setup.sh`. If you removed `working_dir` by hand, run
  `git checkout -- docker-compose.yml` first.

### v0.3.0

Desired-state configuration:

- At its first check-in, each capable device applies its configuration's managed policies. If kiosk is on in that
  configuration, the device enters kiosk.
- The upgrade never takes a device out of kiosk; only turning kiosk off in its configuration does.
- Older agents show "agent too old" and don't get the new command.
- Location mode follows the configuration (GPS → active, otherwise passive), replacing any ad-hoc
  `device.locationMode`.

### v0.2.2 to v0.2.6

From-source installs made with `./setup.sh` in these versions kept the `admin` / `admin` login (fixed in v0.2.7):
`git pull`, re-run `./setup.sh`, and change the admin password.
