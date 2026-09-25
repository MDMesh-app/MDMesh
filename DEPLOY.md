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

**Upgrading a native install** is the same command after `git pull`. The installer detects existing data and
asks **Keep** (default, just press Enter) or **Erase** (requires typing `ERASE`). Keep redeploys the code, runs
migrations, and leaves configurations, devices, users and the enrollment secret untouched; a `pg_dump` is written
to `/opt/mdmesh/backups/` first. Unattended: `sudo ./setup.sh --native -y` never erases; set `REPLACE_DATA=yes` to
opt into a wipe, `HTTP_PORT=9090` to pick the port. Only missing packages are installed, and a JDK 17 found via
`JAVA17_HOME` or under `/opt` is used as-is (Debian 13 ships no `openjdk-17-jdk`).

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
the app dir `/opt/mdmesh`, the `mdmesh-server` and `mdmesh-supervisor` units, the `mdmesh` system user, the install
log, and the `mdmesh` database + role),
writes a final `pg_dump` to `/root`, and only proceeds when you type `UNINSTALL`. `--keep-data` removes the code
and services but leaves the database, `/opt/mdmesh/files` and `/opt/mdmesh/backups` in place; `-y` skips the
prompt for scripted use. Packages installed by apt, your reverse proxy and the git checkout are never touched.

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
| `GITHUB_TOKEN` | Optional — raises the API rate limit / reads a private repo. |
| `IMAGE_OWNER` | GHCR owner (lowercase) the versioned images live under. |
| `SERVER_VERSION` / `WEB_VERSION` | Running image tags **without the `v`** (`0.2.6`, not `v0.2.6`); bumped automatically on apply. |
| `CURRENT_VERSION` | The running release, compared with GitHub's latest to decide "update available". Bumped on apply; `./setup.sh` rewrites it on every run from the checkout's latest tag (`git describe --tags`), like the native installer. |
| `SUPERVISOR_VERSION` | The supervisor's image tag. Apply never changes it (the supervisor never updates itself). The quick start tracks `latest`, so `docker compose pull && docker compose up -d` delivers supervisor fixes; pin it only if you want to freeze it (then bump it by hand to pick up fixes). |
| `APPLY_SUPPORTED` | `1` shows one-click **Update**, `0` shows the manual steps instead. `./setup.sh` rewrites it on every run from `IMAGE_OWNER` (`local` → `0`); the source compose file defaults to `0`, the release compose to `1`. |
| `AUTO_UPDATE` | `1` to apply verified releases unattended (also toggleable in **Settings**). |

- **One-click:** when a verified update is available, a banner appears in the console; an admin clicks
  **Update**, watches the live progress, and the stack rolls back on its own if anything fails.
- **Unattended:** turn on **Automatic updates** in Settings (or `AUTO_UPDATE=1`) to apply each verified
  release without a prompt. A release that fails its rollback is never auto-retried.
- **Recovery:** `https://<host>/recovery` shows live apply state and, on quick-start installs, a **Roll back**
  button. While signed in, no token is needed. If the server is down, paste the break-glass recovery token, read with:
  `docker compose exec supervisor cat /backups/recovery.token`. From-source Docker and native installs
  (`APPLY_SUPPORTED=0`) can't roll back one-click: their recovery page hides Roll back and shows the manual steps
  instead: `git pull && ./setup.sh` (Docker from source) or `git pull && sudo ./install/install-native.sh` (native).
- **Source (build) deploys** can't auto-pull, so setup.sh hides one-click Update (`APPLY_SUPPORTED=0`); update with
  `git pull && ./setup.sh`. Re-running `./setup.sh` (rather than `docker compose up -d --build` alone) is what refreshes
  `CURRENT_VERSION` and `APPLY_SUPPORTED`; without a readable tag (no git, or tags not fetched) it keeps the old
  `CURRENT_VERSION` and warns.
- Older agents keep working across server updates (versioned `/agent/v1` contract; see
  `docs/adr/0009-agent-v1-contract-stability.md`).

## Security notes

- Secrets (`DB_PASSWORD`, `HASH_SECRET`, admin password) are generated per install; `.env` is `chmod 600`.
- TLS everywhere (Cloudflare or Caddy/Let's Encrypt). DB + server ports are never published.
- The agent talks HTTPS only. Set `SECURE_ENROLLMENT=1` (and the matching secret on the agent) to
  require signed enrollment.
- The admin starts with a generated password and is **required to set its own on first login** (the
  console routes the first sign-in to a "set your password" screen). Configure SMTP in `.env` to enable
  email-based password recovery thereafter.
- The supervisor mounts the Docker socket (to drive updates) and is trusted: it acts only on
  **minisign-verified** manifests and **authorized** callers (admin session, or the recovery token).
  Apply/rollback only ever recreate `server`/`caddy` — never `postgres` or the supervisor itself.
