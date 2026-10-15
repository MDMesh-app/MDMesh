# Operations

<sub>[← README](../README.md) · [Deploy](../DEPLOY.md) · [Upgrading](UPGRADING.md) · **Operations** · [Structure](../STRUCTURE.md) · [Contributing](../CONTRIBUTING.md) · [Releasing](../RELEASING.md)</sub>

## Configuration reference

**Docker:** settings live in `.env`, in the install directory (template: [`.env.example`](../.env.example)). After
you edit it, run `docker compose up -d`.

**Native:** pass a setting to `sudo ./setup.sh --native` in its environment. The installer writes it to
`/opt/mdmesh-tc/conf/Catalina/localhost/ROOT.xml` or `/etc/mdmesh/supervisor.env`, and later runs keep it until you
pass a new value.

### Server

| Setting | Where | Default | Meaning |
|---------|-------|---------|---------|
| `BASE_URL` | `.env`; native: env or prompt → `ROOT.xml` | none | Public address of the server, also put in the enrollment QR ([rules](#base-url-and-hostname-rules)). |
| `HTTP_PORT` | native env | current port; fresh install `8080` | Tomcat's HTTP port. |
| `REPLACE_DATA` | native env | asks; `-y`: keep | `yes` erases existing data on a re-run. |
| `DB_PASSWORD`, `HASH_SECRET` | `.env`; native: `ROOT.xml` | generated | Don't change them: the database keeps its original password, and a new `HASH_SECRET` invalidates every enrolled device's token. |
| `SERVER_JWT_SECRET` | `.env`; native: `ROOT.xml` `jwt.secretkey` | generated | API token signing key ([details](#api-signing-key-jwt)). |
| `SERVER_JAVA_OPTS` | `.env` (native: `JAVA_OPTS` in a `systemctl edit mdmesh-server` drop-in) | empty | Extra JVM flags, e.g. `-Xmx1g`. |
| `LEGACY_DEVICE_API` | `.env`; native env → `ROOT.xml` | `0` | `1` re-enables the Headwind launcher's device routes. |
| `SECURE_ENROLLMENT` | `.env`; native: always `0` | `0` | Legacy Headwind setting. Leave it at `0`: `1` blocks agent downloads. |
| `SMTP_HOST`, `SMTP_PORT` | `.env`; native env → `ROOT.xml` | empty, `25` | Mail server for password recovery. Empty host: no email. |
| `SMTP_FROM` | same | `mdm@<hostname>` (Docker), `mdm@localhost` (native) | Sender address. |
| `SMTP_USERNAME`, `SMTP_PASSWORD` | same | empty | Mail login. |
| `SMTP_SSL`, `SMTP_STARTTLS` | same | `false` | `SMTP_SSL=true` for port 465, `SMTP_STARTTLS=true` for 587. |

The wizard writes `SITE_ADDRESS`, `ACME_EMAIL` and `TUNNEL_TOKEN` for the hosting mode you choose.

### Updater (supervisor)

| Setting | Where | Default | Meaning |
|---------|-------|---------|---------|
| `GITHUB_REPO` | `.env`; native env → `supervisor.env` | the clone's `origin` (quick start asks) | `owner/repo` to poll. Without it, there are no updates. |
| `UPDATE_CHANNEL` | `.env` (native: always `stable`) | `stable` | `beta` also offers prereleases. |
| `POLL_INTERVAL_HOURS` | `.env` (native: always `6`) | `6` | How often to check. |
| `GITHUB_TOKEN` | `.env`; native env → `supervisor.env` | empty | Needed for a private repo; raises rate limits. See note. |
| `IMAGE_OWNER` | `.env` | quick start `mdmesh-app`; from source `local` | GHCR owner (lowercase). `local` means built from the clone. |
| `SERVER_VERSION`, `WEB_VERSION` | `.env` | the installed release | Image tags, without the `v` (`0.3.2`). Updates set them. |
| `CURRENT_VERSION` | `.env`; native: `supervisor.env` | `0.0.0` | Running release, compared with GitHub's latest. Updates and rollbacks set it. |
| `SUPERVISOR_VERSION` | `.env` | quick start `latest` | Supervisor image tag. Updates never change it. |
| `APPLY_SUPPORTED` | `.env` (native: always `0`) | `1` with a registry `IMAGE_OWNER`, else `0` | `1` shows one-click **Update**, `0` shows manual steps. |
| `AUTO_UPDATE` | `.env` | `0` | `1` applies releases unattended ([details](UPGRADING.md#quick-start-installs)). |

`./setup.sh` rewrites the version variables and `APPLY_SUPPORTED` from the clone on every run
([Upgrading](UPGRADING.md#from-source-docker-installs)).

**`GITHUB_TOKEN` note:** use a read-only token, either fine-grained with **Contents: read** on the repo or classic
with `repo` scope. On native the `mdmesh` user can read it. Pass it once, off the command line; later runs keep it
(and `GITHUB_REPO`) until you pass a new value, or `GITHUB_TOKEN=` to remove it:

```bash
read -rs GITHUB_TOKEN && export GITHUB_TOKEN
sudo --preserve-env=GITHUB_TOKEN ./setup.sh --native     # add GITHUB_REPO=owner/repo before ./setup.sh if you use one
unset GITHUB_TOKEN
```

### Email on native installs

Keep the password off the command line, where `ps` and shell history would show it:

```bash
read -rs SMTP_PASSWORD && export SMTP_PASSWORD     # type it; nothing is echoed
sudo --preserve-env=SMTP_PASSWORD SMTP_HOST=smtp.example.com SMTP_PORT=587 SMTP_STARTTLS=true SMTP_USERNAME=mdm \
  ./setup.sh --native
unset SMTP_PASSWORD
```

Later runs keep these settings unless you pass them again. An empty value clears a setting. `SMTP_PORT` must be a
number, and `SMTP_SSL` / `SMTP_STARTTLS` take `true`/`1` or `false`/`0`.

## Base URL and hostname rules

The installers check `BASE_URL` before they change anything. The rule is an allowlist
([`install/lib/url.sh`](../install/lib/url.sh)):

- `http://` or `https://`, in any case (stored in lowercase);
- a host name or IPv4 address (letters, digits, `.`, `-`; no leading or trailing `.` or `-`; no `..`), or a
  `[bracketed]` IPv6 address, then an optional `:port`;
- an optional path of letters, digits and `. _ : / + = , -`.

Nothing else is accepted: no spaces, quotes, `$`, `\`, `;` or `~`, no `user@`, no `?` or `#`, and no `%` escapes.

The hostname prompts take the host alone, with an optional port, such as `mdm.example.com` or
`mdm.example.com:8443`. In own-domain mode, Caddy also gets its certificate for that name. When you re-run
`./setup.sh`, it checks the `BASE_URL` already in `.env` against the same rule.

## Agent APK trust chain

`setup.sh` and the native installer trust the release's agent APK only after three checks pass:

- its `manifest.json` verifies with `minisign` against `release/minisign.pub`;
- the manifest names that same release;
- the APK matches the manifest's SHA-256.

Then they put the APK's checksum in the enrollment QR. The native installer also hosts the APK at `/files/agent.apk`.

If a check fails, or `minisign` is missing, the install still completes and prints the reason. The console keeps its
debug enrollment defaults. Either host an APK at `/files/agent.apk` yourself, or re-run once a verified release
exists.

For `setup.sh`, a missing `python3` or `curl` has the same effect. When it runs as root on Debian/Ubuntu, setup.sh
installs `minisign` itself; otherwise it uses the copy inside the supervisor image.

## Server logs

The server logs to stdout at INFO:

- **Docker:** `docker compose logs -f server`
- **Native:** `journalctl -u mdmesh-server -f`

Audit events (sign-ins, password changes, edits) are logged by `AuditLogger`, for example
`docker compose logs server | grep AuditLogger`. They are also stored in the `plugin_audit_log` table.

Tomcat's own files (`catalina.<date>.log` and the access log) are in `/usr/local/tomcat/logs` (container) or
`/opt/mdmesh-tc/logs` (native).

Docker keeps container logs without a size limit. To cap them, set
`"log-opts": {"max-size": "10m", "max-file": "5"}` in `/etc/docker/daemon.json`, then recreate the containers.

Installs upgraded from v0.2.1–v0.3.x may still have `/opt/mdmesh/log4j-mdmesh.xml` and `/opt/mdmesh/logs/`. Both are
unused and safe to delete.

### Temporary DEBUG log

Write a log4j 1.2 XML config, `log4j-debug.xml`, then point the JVM at it.

**Docker:**

1. Copy it into the container: `docker compose cp log4j-debug.xml server:/opt/mdmesh/`
2. Add `SERVER_JAVA_OPTS=-Dlog4j.configuration=file:///opt/mdmesh/log4j-debug.xml` to `.env`. If the value holds
   several flags, quote it: `SERVER_JAVA_OPTS="-Xmx1g -Dlog4j.configuration=file:///opt/mdmesh/log4j-debug.xml"`.
3. `docker compose up -d server`

A quick-start install made before v0.3.2 also needs `JAVA_OPTS: ${SERVER_JAVA_OPTS:-}` under `server:` →
`environment:` in its `docker-compose.yml`.

**Native:**

1. Put it at `/opt/mdmesh/log4j-debug.xml`, readable by the `mdmesh` user.
2. Run `systemctl edit mdmesh-server`, and under `[Service]` add
   `Environment=JAVA_OPTS=-Dlog4j.configuration=file:///opt/mdmesh/log4j-debug.xml`.
3. `systemctl restart mdmesh-server`

Undo it afterwards: DEBUG logs every SQL statement.

## Health checks

Docker installs have two unauthenticated probes for uptime monitors. Each answers `200 ok`, or `503` with a reason.
A trailing slash works too.

| Probe | `503` means |
|-------|-------------|
| `https://<host>/healthz` | `server unavailable`: the API server is stopped, starting, or erroring. This includes the minute or so an update takes. |
| `https://<host>/healthz/supervisor` | `supervisor unavailable`: devices and the console still work, but updates, `/recovery` and the `/files/agent.apk` mirror don't. |

No answer means the edge is down. For the database, `docker compose ps` shows whether `postgres` is healthy.

**Native installs** answer `404` on both paths. Point the monitor at `https://<host>/rest/public/name`, and on the
host check `curl -fsS 127.0.0.1:9000/healthz` or `systemctl is-active mdmesh-supervisor`. A native install from
before v0.3.2 serves the console on these paths until you
[re-run the installer](UPGRADING.md#native-installs).

## Backups and restore

**Docker.** One-click updates dump the database first, and a rollback restores that dump
([Upgrading](UPGRADING.md#recovery-and-rollback)). `./setup.sh` takes no dump, so before a from-source upgrade make
one yourself:

```bash
docker compose exec -T postgres pg_dump -U mdmesh -Fc mdmesh > mdmesh-pre-upgrade.dump
```

To go back to it, stop the server, restore, then build the release the dump was taken on:

```bash
docker compose stop server
docker compose exec -T postgres pg_restore -U mdmesh -d mdmesh -c --if-exists < mdmesh-pre-upgrade.dump
git checkout vX.Y.Z && ./setup.sh      # the release you upgraded from
```

With a registry `IMAGE_OWNER`, add `--allow-downgrade` to that `./setup.sh`.

**Native.** The installer writes a dump to `/opt/mdmesh/backups/` before each upgrade. The uninstaller writes one to
`/root`. Restore **as the `mdmesh` role, never as `postgres`**: a superuser restore runs any function defined in the
dump, so a tampered database could gain superuser rights. With the install present, as root:

```bash
PGPASSWORD="$(setpriv --reuid=mdmesh --regid=mdmesh --init-groups \
  sed -n 's/.*name="JDBC.password" value="\([^"]*\)".*/\1/p' \
  /opt/mdmesh-tc/conf/Catalina/localhost/ROOT.xml | head -n1)" \
  pg_restore -h 127.0.0.1 -p 5432 -U mdmesh -d mdmesh -c --if-exists <dump-file>
```

The installer and uninstaller print this command next to each dump they write. To restore an uninstall dump, install
again first.

## Moving to a new BASE_URL

Enrolled devices keep polling the old URL until you factory-reset or re-provision them. So keep the old address
reachable (or point its DNS at the new host). Library APKs keep the address they were uploaded under, and agents from
v0.3.2 on download from exactly that address. After a move, re-upload them.

## Uninstalling

### Docker (`setup.sh` or the quick start)

```bash
docker compose exec -T postgres pg_dump -U mdmesh -Fc mdmesh > mdmesh-final.dump
docker compose down -v --remove-orphans     # stops containers and DELETES the volumes (database, uploads, certs, backups)
rm -f .env                                  # secrets; the directory itself can go too for a quick-start install
docker image rm $(docker image ls 'ghcr.io/mdmesh-app/mdmesh-*' -q) 2>/dev/null   # optional: free the images
```

To keep the data volumes instead, run `docker compose down` without `-v`. A later `./setup.sh` picks up where you
left off.

### Native

```bash
sudo ./install/uninstall-native.sh
```

It lists what it will remove and writes a final dump to `/root`. It waits until you type `UNINSTALL`. It removes:

- `/opt/mdmesh-tc`, `/opt/mdmesh` and `/opt/mdmesh-runtime`;
- the `mdmesh-server` and `mdmesh-supervisor` units, and `/etc/mdmesh`;
- the `mdmesh` user, the install log, and the `mdmesh` database and role.

It never touches apt packages, your proxy or the git checkout.

- `--keep-data` keeps the database, `/opt/mdmesh/files`, `/opt/mdmesh/backups` and the `mdmesh` user.
- `-y` skips the prompt.
- `--no-backup` skips the dump. You need it if `ROOT.xml` is gone or unreadable (for example, a pre-0.2.9 install
  that ran Tomcat as root), because the dump is taken as the `mdmesh` role.

## Security notes

- Each install generates its own secrets. `.env` is mode 600.
- Traffic uses TLS: on Docker through Cloudflare or Caddy, on native through your own proxy. Docker never publishes
  the database and server ports. The agent speaks HTTPS only.
- The admin must set a new password at first sign-in. A database the server created without an installer run has an
  admin no password works for, until an installer (or `scripts/dev-seed.sh`) sets one.
- The Headwind launcher endpoints are off by default (404). MQTT push is off; agents are woken over a WebSocket.
- The supervisor mounts the Docker socket, so it is trusted. It acts only on minisign-verified manifests and on
  authorized callers (an admin session or the recovery token). Apply and rollback only recreate `server` and `caddy`.

### Native installs

- Tomcat and the supervisor run as the unprivileged `mdmesh` user, which can read `GITHUB_TOKEN`
  ([use a read-only one](#updater-supervisor)).
- The installer and uninstaller use the `postgres` superuser only to create, alter or drop the role and database.
  All other database work runs as the `mdmesh` role. Your shell's `PGHOST` and similar variables are ignored.
- The installer stops if the `mdmesh` user has crontab or `at` jobs. Inspect them (`crontab -l -u mdmesh`, `atq`),
  remove them (`crontab -r -u mdmesh`, `atrm <id>`), then re-run.

### API signing key (JWT)

This key signs tokens for REST API clients (`/rest/public/jwt/login`); the console uses a session cookie. It is
generated once and kept, so tokens survive restarts and upgrades.

- **Docker:** the key is kept in the server's data volume at `/opt/mdmesh/jwt.secret`, so `docker compose down -v`
  deletes it and API clients have to sign in again. To pin a key, set
  `SERVER_JWT_SECRET=<output of openssl rand -hex 64>` in `.env`; it takes priority over the file. A quick-start
  install made before v0.3.2 also needs `JWT_SECRET: ${SERVER_JWT_SECRET:-}` under `server:` → `environment:` in its
  `docker-compose.yml`.
- **Native:** the key is `jwt.secretkey` in `ROOT.xml`, kept across re-runs.
- The key must be hex, a multiple of 4 characters, and at least 128 long. The Docker server refuses to start with any
  other `SERVER_JWT_SECRET`, and the native installer replaces an invalid `jwt.secretkey`.
