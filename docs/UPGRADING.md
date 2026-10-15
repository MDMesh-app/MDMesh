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

An admin clicks **Update** on the console's banner. A failed update rolls back by itself.

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

- **Base URL:** every run asks for it again. With `-y`, pass it every time:
  `sudo BASE_URL=https://mdm.example.com ./setup.sh --native -y`.
- **Unattended (`-y`):** never erases unless you set `REPLACE_DATA=yes`. It keeps the current port unless you set
  `HTTP_PORT`.
- **Interrupted run:** run the same command again. It picks up where the last run stopped.
- **Settings:** SMTP and `LEGACY_DEVICE_API` carry over from earlier runs. `GITHUB_REPO` and `GITHUB_TOKEN` don't:
  `/etc/mdmesh/supervisor.env` is rewritten on every run, so pass them each time
  ([how](OPERATIONS.md#updater-supervisor)).

## Recovery and rollback

### The recovery page

With one-click updates (`APPLY_SUPPORTED=1`), `https://<host>/recovery` shows the update status and a **Roll back**
button. Signed in, you need no token. If the server is down, use the recovery token instead:

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
