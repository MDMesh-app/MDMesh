# Deploying MDMesh

<sub>[← README](README.md) · **Deploy** · [Structure](STRUCTURE.md) · [Contributing](CONTRIBUTING.md) · [Releasing](RELEASING.md)</sub>

Three ways to run it. All generate secrets and a working admin login — no default passwords, and the
admin is forced to set its own password on first login.

## Option A — one line, no clone (published images)

The fastest path: pull the released images from GHCR — no clone, no build. Needs only Docker + `curl`.

```bash
bash <(curl -fsSL https://raw.githubusercontent.com/MDMesh-app/MDMesh/main/quickstart.sh)
```

It creates `./mdmesh`, downloads the pull-only compose (`docker-compose.release.yml`) + seed, generates
secrets, `docker compose pull && up -d`, seeds, and prints the console URL + a temporary admin password.

It pins the latest published release: `SERVER_VERSION`, `WEB_VERSION` and `CURRENT_VERSION` in `.env` all name that
version (e.g. `0.3.1`), so the console doesn't offer the release you just installed as an update, and the compose file +
seed are downloaded from that release's tag (`v0.3.1`) so they match the images. The supervisor
tracks `SUPERVISOR_VERSION=latest`, so `docker compose pull` keeps delivering its fixes (updates never touch it); pin it
only if you want to freeze it. If the GitHub API can't be reached (or is rate-limited) the quick start falls back to the
`:latest` images and the `main` compose + seed with `CURRENT_VERSION=0.0.0`: the install works, but the console shows "Update available" until the
first update, which pins the versions (or set `SERVER_VERSION`/`WEB_VERSION`/`CURRENT_VERSION` to the running release by hand).

> **Requires a published release**, and the GHCR packages (`mdmesh-server`/`-web`/`-supervisor`) must be
> **public** — or run `docker login ghcr.io` first. See [RELEASING.md](RELEASING.md).

> **Upgrading a from-source Docker install made with `./setup.sh` between v0.2.2 and v0.2.6?** A bug in the
> seed gate meant those installs kept the stock `admin` / `admin` login and never enabled QR/token enrollment
> defaults. After `git pull`, re-run `./setup.sh` (it applies the repairs idempotently) and **change the admin
> password** from the console if you never did. Quick-start (`quickstart.sh`) installs got a random password
> but also missed the enrollment defaults; re-running `./setup.sh` in a clone fixes that too. Fixed in v0.2.7.

> **Upgrading to v0.3.0?** Desired-state configuration ships in this release: on its first check-in after the
> upgrade, every device whose agent supports it applies its assigned configuration's managed policies, and
> any device on a configuration with kiosk mode on enters kiosk. The upgrade itself never lifts kiosk on a
> device — only turning kiosk off in that device's configuration does. Older agents are unaffected and show
> "agent too old" in the console instead of receiving the new command. Location capture mode also follows the
> configuration after the upgrade (GPS → active, otherwise passive), so an ad-hoc `device.locationMode` override
> is replaced.

> **Docker: supervisor restarting with `Cannot find module '/project/server.js'`?** Every Docker install from v0.1.0
> through v0.3.0 hit this (#27), so Settings → Updates, the `/recovery` page and the Docker `/files/agent.apk` mirror
> (the APK the enrollment QR points to) never worked. Fixed in v0.3.1 in the image itself — your existing compose file
> works unchanged. Quick-start installs: `docker compose pull && docker compose up -d` (if `.env` pins
> `SUPERVISOR_VERSION`, set it to `0.3.1` first). From-source installs: `git pull` and re-run `./setup.sh` (if you
> removed `working_dir` by hand, `git checkout -- docker-compose.yml` first). The supervisor never updates itself, so
> this manual pull is how it picks up fixes. What you get back depends on the install: quick-start installs get the
> update banner, one-click **Update** and the `/recovery` **Roll back** button; from-source Docker installs
> (`APPLY_SUPPORTED=0`) get the update banner (its **Details** link leads to the manual steps in Settings) and a `/recovery` page that shows status and the manual update steps
> instead of Roll back, since one-click apply and rollback aren't supported there.

## Option B — from source (clone + build)

Prereqs: Docker + Compose v2, and `openssl`.

```bash
git clone https://github.com/MDMesh-app/MDMesh.git && cd MDMesh
./setup.sh
```

The wizard asks how you want to expose it:

- **Cloudflare Tunnel** — no open ports; Cloudflare manages TLS. You need a domain in a Cloudflare
  account. Create a tunnel (Zero Trust → Networks → Tunnels), route its public hostname to
  `http://caddy:80`, and paste the tunnel token when prompted.
- **Your own domain** — opens 80/443; Caddy auto-provisions a Let's Encrypt cert. Point the domain's
  DNS at the host first.

The hostname you enter becomes `BASE_URL=https://<hostname>`, the public address that devices and the console use.
It is checked as soon as it is entered or read: `setup.sh` checks it before writing `.env`, the quick start (Option A)
before downloading anything (it has only created its `./mdmesh` directory), and the native installer (Option C) before
it installs or changes anything on the host (it has only started its log file). The rule is an allowlist
(`install/lib/url.sh`):

- `http://` or `https://`, in any letter case (it is stored in lowercase);
- then a host name or IPv4 address (letters, digits, `.` and `-`, not starting or ending with `.` or `-`, no `..`), or
  a `[bracketed]` IPv6 address, optionally with `:port`;
- then optionally a path made of letters, digits and `. _ : / + = , -`.

Nothing else is accepted: no spaces, quotes, `$`, `\`, `;`, `~` or other shell characters, no `user@` part, no `?` query or
`#` fragment, and no `%` escapes.

The hostname prompts of `setup.sh` and the quick start take the host alone: a name or IPv4 address (letters, digits,
`.` and `-`, not starting or ending with `.` or `-`), or a `[bracketed]` IPv6 address, optionally with `:port` (e.g.
`mdm.example.com` or `mdm.example.com:8443`), and no `https://`, path, `user@`, `?` or `#`. In own-domain mode it is
also the address Caddy serves and gets a certificate for. A re-run of `./setup.sh` checks the `BASE_URL` already in
`.env` by the rule above, and rewrites an upper-case scheme there in lowercase.

It writes `.env` (gitignored), builds the images, brings the stack up, seeds the database, and prints
the console URL and the generated **admin** password (shown once — save it, then change it in the UI).

Stack: `postgres` + `server` (Tomcat) + `caddy` (serves the SPA, proxies `/rest`, `/files`,
`/agent/ws`) + optional `cloudflared`. Postgres and the server publish **no** host ports.

Manage it:
```bash
docker compose --profile cloudflare up -d                                   # cloudflare mode
docker compose -f docker-compose.yml -f docker-compose.domain.yml up -d     # own-domain mode
docker compose logs -f server
docker compose down
```

## Option C — Native (no Docker)

Debian/Ubuntu, as root. The leaner path: Postgres + Tomcat on the host; you terminate TLS yourself
(your reverse proxy/cert, or Caddy in front).

```bash
sudo ./setup.sh --native      # → install/install-native.sh
```

`sudo` is only how you become root. The installer itself never calls it, so on a root-only host without sudo (a
Proxmox LXC container, a minimal Debian image) run `./setup.sh --native` as root. It and the uninstaller reach Postgres
without sudo, isolated from root's environment (a `PGHOST` and the like in your shell have no effect). Superuser access
(the `postgres` account) is used only in the `postgres` database, and only for what needs it: creating, altering or
dropping the role and database. Everything that touches the `mdmesh` database — the pre-upgrade and final `pg_dump`, the
device/user counts — connects **as the `mdmesh` role** over `127.0.0.1`, with the role password read from `ROOT.xml`, so
objects owned by that role never run with superuser rights.

It asks for the public base URL, or takes it from `BASE_URL=https://mdm.example.com` (required with `-y`). The value
must follow the same rule as in Option B.

**Upgrading a native install** is the same command after `git pull`. The installer detects existing data and
asks **Keep** (default, just press Enter) or **Erase** (requires typing `ERASE`). Keep redeploys the code, runs
migrations, and leaves configurations, devices, users and the enrollment secret untouched; a `pg_dump` is written
to `/opt/mdmesh/backups/` first. Unattended: `sudo ./setup.sh --native -y` never erases; set `REPLACE_DATA=yes` to
opt into a wipe, `HTTP_PORT=9090` to pick the port. Only missing packages are installed, and a JDK 17 found via
`JAVA17_HOME` or under `/opt` is used as-is (Debian 13 ships no `openjdk-17-jdk`).

**The agent APK.** The installer fetches the latest release's agent APK, hosts it at `/files/agent.apk` and bakes its
signing checksum into the console's enrollment QR. It trusts the APK only through the release's signed manifest, like
the supervisor does: `manifest.json` must verify with `minisign` against the repo's `release/minisign.pub` before its
checksum and SHA-256 are read, the manifest's version must be the release's own tag (so an older release's signed
manifest cannot stand in for it), and the downloaded APK must match that SHA-256. If there is no release yet, the
release has no `manifest.json.minisig`, `minisign` or the `release/minisign.pub` key is missing, the signature does not
verify, the manifest belongs to another release, a download fails or the APK does not match, the install still
completes. It prints which of these happened, and the console keeps its debug defaults: host an APK at
`/files/agent.apk` yourself, or re-run the installer once a verified release exists.

Tomcat runs as the unprivileged `mdmesh` system user under systemd (`mdmesh-server.service`, enabled at boot).
Manage it like any other service:

```bash
systemctl status mdmesh-server        # health, PID, recent log lines
systemctl restart mdmesh-server       # after editing conf/Catalina/localhost/ROOT.xml
journalctl -u mdmesh-server -f        # follow Tomcat's stdout/stderr
```

The installer stops whatever it started before (the unit, or a pre-0.2.9 root Tomcat launched with `catalina.sh`) and
refuses to continue if the chosen port is held by anything else, so it never kills a process it does not own. The JDK
does not run as root, and the installer does not open ports 80/443; front it with your own TLS proxy.

## Uninstalling

**Docker (`setup.sh` or the quick start).** Everything lives in the compose project `mdmesh` plus the directory
you ran it from (`./mdmesh` for the quick start). Take a dump first if you want one:

```bash
docker compose exec -T postgres pg_dump -U mdmesh -Fc mdmesh > mdmesh-final.dump
docker compose down -v --remove-orphans     # stops containers and DELETES the volumes (database, uploads, certs, backups)
rm -f .env                                  # secrets; the directory itself can go too for a quick-start install
docker image rm $(docker image ls 'ghcr.io/mdmesh-app/mdmesh-*' -q) 2>/dev/null   # optional: free the images
```

`docker compose down` without `-v` keeps the data volumes, so a later `./setup.sh` picks up where you left off.

**Native.** `sudo ./install/uninstall-native.sh` shows exactly what it will remove (Tomcat under `/opt/mdmesh-tc`,
the app dir `/opt/mdmesh`, the `mdmesh-server` and `mdmesh-supervisor` units and the supervisor's settings in
`/etc/mdmesh`, the `mdmesh` system user, the install log, and the `mdmesh` database + role),
writes a final `pg_dump` to `/root`, and only proceeds when you type `UNINSTALL`. `--keep-data` removes the code
and services but leaves the database, `/opt/mdmesh/files` and `/opt/mdmesh/backups` in place; `-y` skips the
prompt for scripted use. Packages installed by apt, your reverse proxy and the git checkout are never touched.

The final dump is taken as the `mdmesh` role, so it needs the role password from `ROOT.xml`. If `ROOT.xml` is gone or
unreadable — including a pre-0.2.9 install whose Tomcat ran as root, which has no `mdmesh` service user — pass
`--no-backup` to uninstall without a dump (take one yourself first if you want one).

Restore that dump (or a pre-upgrade one from `/opt/mdmesh/backups/`) **as the `mdmesh` role, never as `postgres`**: a
restore run by a superuser executes any function the dump's own objects define, so a tampered database could escalate
through its own restore. With an install present (its `ROOT.xml` and `mdmesh` role), from the host as root:

```bash
PGPASSWORD="$(setpriv --reuid=mdmesh --regid=mdmesh --init-groups \
  sed -n 's/.*name="JDBC.password" value="\([^"]*\)".*/\1/p' \
  /opt/mdmesh-tc/conf/Catalina/localhost/ROOT.xml | head -n1)" \
  pg_restore -h 127.0.0.1 -p 5432 -U mdmesh -d mdmesh -c --if-exists <dump-file>
```

It reads the role password from `ROOT.xml` as the service user (no password on any command line) and connects as
`mdmesh` over TCP. The installer and uninstaller print this same command next to each dump they write.

Devices that are still enrolled keep polling the old server URL until they are factory-reset or re-provisioned;
if you are migrating rather than retiring, keep `BASE_URL` reachable (or point DNS at the new host) so they
follow.

## Enrolling devices

One prebuilt agent APK works for **every** deployment — the server URL is delivered in the
enrollment QR (`com.mdmesh.SERVER_URL`), not baked into the APK. Host the APK on your server and
generate the QR from the console's **Enroll** page; it embeds your `BASE_URL`, the APK location, and
a single-use token.

## Updates & recovery

A decoupled **supervisor** service polls your GitHub releases, verifies the minisign-signed manifest,
and can apply updates to the `server` + `caddy` images — backing the database up first and rolling
back automatically if the new version fails its health check. It stays up even while the server is
mid-restart, so "update available" and the recovery page are always reachable.

Set these in `.env` (the wizard seeds them; add by hand for an existing deploy):

| Variable | Meaning |
|----------|---------|
| `GITHUB_REPO` | `owner/repo` to poll for releases (required to enable updates). |
| `UPDATE_CHANNEL` | `stable` (default) or `beta` (allows prereleases). |
| `POLL_INTERVAL_HOURS` | How often to check (default `6`). |
| `GITHUB_TOKEN` | Optional for a public `GITHUB_REPO` (raises the API rate limit); **required** for a private one. With a token the supervisor downloads the manifest, its signature and the agent APK through the GitHub asset API, the only way a private repo serves them, and never sends the token to the download host GitHub redirects to. Use a read-only token: fine-grained with **Contents: read** on that repo, or a classic token with `repo` scope. If no update shows up, the supervisor log gives the reason on its `[verify]` line: `docker compose logs supervisor`, or `journalctl -u mdmesh-supervisor` on a native install. |
| `IMAGE_OWNER` | GHCR owner (lowercase) the versioned images live under. |
| `SERVER_VERSION` / `WEB_VERSION` | Running image tags **without the `v`** (`0.2.6`, not `v0.2.6`); bumped automatically on apply. `./setup.sh` builds every image from the checkout, so on every run it sets them to the checkout's version (whatever `IMAGE_OWNER` is): the images are named after the code they hold. |
| `CURRENT_VERSION` | The running release, compared with GitHub's latest to decide "update available". Bumped on apply and set back on rollback; the supervisor reads it from this `.env` at start and after each apply or rollback, so a restart never re-offers a release that is already running. `./setup.sh` rewrites it on every run from the checkout's nearest release tag (`vX.Y.Z` or `vX.Y.Z-pre`; other tags are skipped), like the native installer, and with a registry `IMAGE_OWNER` refuses a checkout older than it, or one without a release tag (see below). |
| `SUPERVISOR_VERSION` | The supervisor's image tag. Apply never changes it (the supervisor never updates itself). The quick start tracks `latest`, so `docker compose pull && docker compose up -d` delivers supervisor fixes; pin it only if you want to freeze it (then bump it by hand to pick up fixes). `./setup.sh` builds the supervisor from the checkout and sets it to the checkout's version on every run. |
| `APPLY_SUPPORTED` | `1` shows one-click **Update**, `0` shows the manual steps instead. `./setup.sh` rewrites it on every run from `IMAGE_OWNER` (`local` or unset → `0`); the source compose file defaults to `0`, the release compose to `1`. |
| `AUTO_UPDATE` | `1` to apply verified releases unattended (also toggleable in **Settings**). |

- **One-click:** when a verified update is available, a banner appears in the console; an admin clicks
  **Update**, watches the live progress, and the stack rolls back on its own if anything fails.
- **Unattended:** turn on **Automatic updates** in Settings (or `AUTO_UPDATE=1`) to apply each verified
  release without a prompt. A release whose apply fails (automatic or by hand), or that you roll back from, is never
  auto-applied again, even after a restart: it is kept as `skipVersion` in `/backups/auto.json` and shown as
  `autoSkipped` in `/update/status`. **Update** still applies it by hand, and a newer release auto-applies as usual.
- **Recovery:** `https://<host>/recovery` shows live apply state and, on quick-start installs, a **Roll back**
  button. While signed in, no token is needed. If the server is down, paste the break-glass recovery token, read with:
  `docker compose exec supervisor cat /backups/recovery.token`. From-source Docker and native installs
  (`APPLY_SUPPORTED=0`) can't roll back one-click: their recovery page hides Roll back and shows the manual steps
  instead: `git pull && ./setup.sh` (Docker from source) or `git pull && sudo ./install/install-native.sh` (native).
  Native installs don't proxy it: the supervisor listens on loopback only, so open it from the host with
  `curl 127.0.0.1:9000/recovery` (not `https://<host>/recovery`).
- **What a rollback does** (the automatic one after a failed update, and **Roll back**): it stops `server`, restores the
  database dump taken just before the update, then sets the image tags and `CURRENT_VERSION` in `.env` back to the
  pre-update release, starts the old `server` + `caddy` and waits for health. Anything written to the database after
  the update is discarded. The restore runs in one transaction and stops at the first error, so a failed restore
  changes nothing: the rollback then ends as **failed**, `.env` still names the version the database belongs to, and
  `server` is left **stopped** on purpose (the old version must not run on a database that wasn't restored for it).
  caddy and `/recovery` stay up. Fix the cause (the full psql output is in the supervisor:
  `docker compose exec supervisor cat /backups/<stamp>.restore.log`), then press **Roll back** again: it uses the same
  backup. To restore by hand instead, from the install directory:
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
  In one transaction this ends every other session, then restores with a 60-second lock bound: a lock nothing can end
  (a prepared transaction) fails with "lock timeout" instead of waiting forever. The `sed` matters: the dump's own
  header says `SET lock_timeout = 0;`, which would cancel the bound, so its first such line is rewritten (only that
  one; a data row with the same text is left alone). Then:
  ```bash
  # only if that succeeded: copy SERVER_VERSION, WEB_VERSION and CURRENT_VERSION from the .env snapshot above into .env
  docker compose up -d --no-deps server caddy
  ```
- **Source (build) deploys** can't auto-pull, so setup.sh hides one-click Update (`APPLY_SUPPORTED=0`); update with
  `git pull && ./setup.sh`. Re-running `./setup.sh` (rather than `docker compose up -d --build` alone) is what refreshes
  `CURRENT_VERSION`, the image tags and `APPLY_SUPPORTED`; without a release tag (no git, tags not fetched, or only
  non-release tags) it keeps the old values and warns.
- **`./setup.sh` with a registry `IMAGE_OWNER`** (one-click Update on) still builds the stack from the checkout, and
  apply may since have moved it to a newer release. If the checkout is older than the running `CURRENT_VERSION`,
  setup.sh stops before changing anything (`running 0.4.0, checkout is 0.3.1 — git pull first, or re-run with
  --allow-downgrade`). `git pull` first; `./setup.sh --allow-downgrade` builds and runs the older code on purpose
  (against the current database, which is not rolled back). It also stops when it can't tell which version the
  checkout is (no readable release tag: a source tarball, or tags not fetched); build from a tagged git checkout, or
  pass `--allow-downgrade` to build that code anyway. It then keeps the image tags and `CURRENT_VERSION` that `.env`
  already holds: on a fresh install that is `0.0.0`, so every release shows as an update.
- `./setup.sh` rejects an unknown option with a usage error (Docker mode). With `--native` it passes its other flags
  (such as `-y`), wherever they stand, to the native installer; `--reset` and `--allow-downgrade` are Docker-mode
  flags, and `--native` ignores them.
- Older agents keep working across server updates (versioned `/agent/v1` contract; see
  `docs/adr/0009-agent-v1-contract-stability.md`).

## Server logs

The server logs to stdout only, at INFO: read it with `docker compose logs -f server` (Docker) or
`journalctl -u mdmesh-server -f` (native). Audit events (sign-ins, password changes, edits to devices, configurations,
applications and groups) are the lines of the `AuditLogger` logger, for example
`docker compose logs server | grep AuditLogger`; the audit plugin also stores them in the `plugin_audit_log` table.
Tomcat also writes its own files (`catalina.<date>.log`, which repeats its console lines and the database migrations,
and the HTTP access log) under `/usr/local/tomcat/logs` in the container and `/opt/mdmesh-tc/logs` on native installs.

Docker's default `json-file` log driver keeps container logs without a size limit. To cap them, set `log-opts` in
`/etc/docker/daemon.json` (for example `"log-opts": {"max-size": "10m", "max-file": "5"}`) and recreate the
containers. journald caps the journal on its own.

An install upgraded from v0.2.1–v0.3.x keeps `/opt/mdmesh/log4j-mdmesh.xml` (and `/opt/mdmesh/logs/`, if a development build
created it). The server no longer reads or writes them; delete them if you like.

For a temporary DEBUG log, put a log4j 1.2 XML config at `/opt/mdmesh/log4j-debug.xml` and start the server with
the JVM flag `-Dlog4j.configuration=file:///opt/mdmesh/log4j-debug.xml`:
- Docker: `docker compose cp log4j-debug.xml server:/opt/mdmesh/`, add
  `SERVER_JAVA_OPTS=-Dlog4j.configuration=file:///opt/mdmesh/log4j-debug.xml` to `.env` (the server gets it as
  `JAVA_OPTS`), then `docker compose up -d server`. Quote a value that holds several flags
  (`SERVER_JAVA_OPTS="-Xmx1g -Dlog4j.configuration=file:///opt/mdmesh/log4j-debug.xml"`): `setup.sh` reads `.env` as
  shell, and an unquoted space stops it. A quick-start install made before this release also needs the line
  `JAVA_OPTS: ${SERVER_JAVA_OPTS:-}` under `server:` → `environment:` in its `docker-compose.yml` for that.
- Native: copy the file there (readable by the `mdmesh` user), run `systemctl edit mdmesh-server`, add
  `Environment=JAVA_OPTS=-Dlog4j.configuration=file:///opt/mdmesh/log4j-debug.xml` under `[Service]`, then
  `systemctl restart mdmesh-server`.

Undo it the same way afterwards: DEBUG logs every SQL statement.

## Health checks

Docker installs answer two unauthenticated probes at the edge, for uptime monitors. Each returns `200 ok` or a
`503` with a short reason, never the console page. A trailing slash (`/healthz/`, `/healthz/supervisor/`) works too.

| Probe | `200 ok` when | `503` when |
|-------|---------------|------------|
| `https://<host>/healthz` | Caddy is up **and** the API server answers `GET /rest/public/name` with a 2xx (the probe an update uses to decide the new version is healthy) | `server unavailable`: the server is stopped, still starting, or answering with errors |
| `https://<host>/healthz/supervisor` | the updater/recovery supervisor answers its own `/healthz` | `supervisor unavailable`: the supervisor is down. The console, the API and enrolled devices keep working, but update checks, the recovery page and the `/files/agent.apk` mirror that new enrollments download do not |

No answer at all means the edge itself is down. Neither probe queries the database: `docker compose ps` shows
`postgres` as `healthy` from its own `pg_isready` check. `/healthz` also fails, as it should, for the minute or so
an update takes to recreate the server.

**Native installs** have no edge probes: `/healthz` and `/healthz/supervisor` answer `404`, never the console page
(an install from before this release serves the console there until its next installer run, `sudo ./setup.sh
--native -y`). Point the monitor at `https://<host>/rest/public/name` through your proxy, and check the supervisor,
which listens on loopback `:9000` only, on the host with `curl -fsS 127.0.0.1:9000/healthz` or
`systemctl is-active mdmesh-supervisor`.

## Security notes

- Secrets (`DB_PASSWORD`, `HASH_SECRET`, admin password, JWT signing key) are generated per install; `.env` is
  `chmod 600`.
- The JWT signing key signs the tokens of REST API clients that sign in through `/rest/public/jwt/login`; the console
  itself uses a session cookie. It is generated once and kept, so those tokens survive restarts and upgrades.
  **Docker:** the server generates it on its first start into its data volume (`/opt/mdmesh/jwt.secret`, mode 600)
  and reuses it on every start; an install made before it existed gets one on its first start of the new image, with
  no manual step. It is not in `.env`, so `docker compose down -v` deletes it with the volume and API clients sign in
  again. To pin it, set `SERVER_JWT_SECRET=<output of openssl rand -hex 64>` in `.env` (the server gets it as
  `JWT_SECRET`); it wins over the file. A quick-start install made before this release also needs the line
  `JWT_SECRET: ${SERVER_JWT_SECRET:-}` under `server:` → `environment:` in its `docker-compose.yml` for that.
  **Native:** the installer writes it as `jwt.secretkey` in Tomcat's `ROOT.xml` (mode 600, next to `hash.secret`) and
  keeps it across re-runs and upgrades; an install made before it existed gets one on its next installer run. Use
  only a hex value that is a multiple of 4 characters and at least 128 long: the JWT library silently drops other
  characters, so the Docker server refuses to start with any other `SERVER_JWT_SECRET` (and replaces a key file that
  holds one), and the native installer replaces such a `jwt.secretkey`.
- TLS everywhere (Cloudflare or Caddy/Let's Encrypt). DB + server ports are never published.
- The agent talks HTTPS only. Set `SECURE_ENROLLMENT=1` (and the matching secret on the agent) to
  require signed enrollment.
- The admin starts with a generated password and is **required to set its own on first login** (the
  console routes the first sign-in to a "set your password" screen). Configure SMTP in `.env` to enable
  email-based password recovery thereafter.
- The supervisor mounts the Docker socket (to drive updates) and is trusted: it acts only on
  **minisign-verified** manifests and **authorized** callers (admin session, or the recovery token).
  Apply/rollback only ever recreate `server`/`caddy` — never `postgres` or the supervisor itself.
- On native installs the supervisor runs as the unprivileged `mdmesh` user (like Tomcat), with its settings in the
  root-owned `/etc/mdmesh/supervisor.env`. A `GITHUB_TOKEN` there reaches the supervisor's environment, which that user
  can read, so use a read-only token.
- The native installer stops if the `mdmesh` account has a crontab or `at` jobs (it never needs any): inspect them
  (`crontab -l -u mdmesh`, `atq`), remove them (`crontab -r -u mdmesh`, `atrm <id>`) and re-run.
