# Deploying MDMesh

<sub>[← README](README.md) · **Deploy** · [Structure](STRUCTURE.md) · [Contributing](CONTRIBUTING.md) · [Releasing](RELEASING.md)</sub>

Two ways to run the control plane. Both generate secrets and a working admin login — no default
passwords.

## Option A — Docker (recommended)

Prereqs: Docker + Compose v2, and `openssl`.

```bash
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

## Option B — Native (no Docker)

Debian/Ubuntu, as root. The leaner path: Postgres + Tomcat on the host; you terminate TLS yourself
(your reverse proxy/cert, or Caddy in front).

```bash
sudo ./setup.sh --native      # → install/install-native.sh
```

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
| `SERVER_VERSION` / `WEB_VERSION` | Running image tags; bumped automatically on apply. |
| `AUTO_UPDATE` | `1` to apply verified releases unattended (also toggleable in **Settings**). |

- **One-click:** when a verified update is available, a banner appears in the console; an admin clicks
  **Update**, watches the live progress, and the stack rolls back on its own if anything fails.
- **Unattended:** turn on **Automatic updates** in Settings (or `AUTO_UPDATE=1`) to apply each verified
  release without a prompt. A release that fails its rollback is never auto-retried.
- **Recovery:** `https://<host>/recovery` shows live apply state and a **Roll back** button. While signed
  in, no token is needed. If the server is down, paste the break-glass recovery token, read with:
  `docker compose exec supervisor cat /backups/recovery.token`.
- **Source (build) deploys** can't auto-pull — they run locally built images; redeploy from git to update.
- Older agents keep working across server updates (versioned `/agent/v1` contract; see
  `docs/adr/0009-agent-v1-contract-stability.md`).

## Security notes

- Secrets (`DB_PASSWORD`, `HASH_SECRET`, admin password) are generated per install; `.env` is `chmod 600`.
- TLS everywhere (Cloudflare or Caddy/Let's Encrypt). DB + server ports are never published.
- The agent talks HTTPS only. Set `SECURE_ENROLLMENT=1` (and the matching secret on the agent) to
  require signed enrollment.
- Change the admin password after first login; configure SMTP in `.env` for password recovery.
- The supervisor mounts the Docker socket (to drive updates) and is trusted: it acts only on
  **minisign-verified** manifests and **authorized** callers (admin session, or the recovery token).
  Apply/rollback only ever recreate `server`/`caddy` — never `postgres` or the supervisor itself.
