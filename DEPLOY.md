# Deploying MDMesh

<sub>[← README](README.md) · **Deploy** · [Upgrading](docs/UPGRADING.md) · [Operations](docs/OPERATIONS.md) · [Structure](STRUCTURE.md) · [Contributing](CONTRIBUTING.md) · [Releasing](RELEASING.md)</sub>

There are three ways to run MDMesh. Each one generates its own secrets and admin login. There are no default
passwords, and the admin must set its own password on first login.

## Pick a method

| Method | Needs | Choose it when |
|--------|-------|----------------|
| [A. Quick start](#option-a--one-line-no-clone-published-images) | Docker with Compose v2, `curl`, `openssl` | You want the fastest path, with one-click updates and rollback. |
| [B. From source](#option-b--from-source-clone--build) | Docker with Compose v2, `openssl`, `python3`, `curl`, a clone | You want to build the images yourself. |
| [C. Native](#option-c--native-no-docker) | Debian 12/13 or Ubuntu 24.04, root, `git` | You don't want Docker, and you run your own TLS proxy. |

Options A and B ask for the public hostname and set `BASE_URL=https://<hostname>`. Option C asks for the full base
URL (`https://…`; `http://` is accepted too). See the [rules](docs/OPERATIONS.md#base-url-and-hostname-rules).

## Option A — one line, no clone (published images)

```bash
bash <(curl -fsSL https://raw.githubusercontent.com/MDMesh-app/MDMesh/main/quickstart.sh)
```

This needs a published release whose GHCR packages (`mdmesh-server`, `mdmesh-web`, `mdmesh-supervisor`) are public.
If they aren't, run `docker login ghcr.io` first ([RELEASING.md](RELEASING.md)).

It asks for the hosting mode (as in [Option B](#option-b--from-source-clone--build)), the GitHub repo and GHCR owner
to pull from, and the hostname. Then it creates `./mdmesh`, downloads the pull-only compose file and the seed,
generates secrets, pulls and starts the stack, seeds the database, and prints the console URL and a temporary admin
password.

Run every `docker compose` command from `./mdmesh`, where `.env` lives. The install is pinned to the latest
release: `SERVER_VERSION`, `WEB_VERSION` and `CURRENT_VERSION` all name it, and the compose file and seed come from
its tag. Updates are one click ([Upgrading](docs/UPGRADING.md#quick-start-installs)).

If the GitHub API can't be reached, the quick start falls back to the `:latest` images and the `main` compose file,
with `CURRENT_VERSION=0.0.0`. It still works, but the console shows "Update available" until the first update.
To stop that, set `SERVER_VERSION`, `WEB_VERSION` and `CURRENT_VERSION` to the running release by hand.

## Option B — from source (clone + build)

```bash
git clone https://github.com/MDMesh-app/MDMesh.git && cd MDMesh
./setup.sh
```

The wizard asks how to expose the server:

- **Cloudflare Tunnel.** No open ports, and Cloudflare manages TLS. You need a domain in a Cloudflare account.
  Create a tunnel (Zero Trust → Networks → Tunnels), route its public hostname to `http://caddy:80`, and paste the
  tunnel token when asked.
- **Your own domain.** Opens ports 80/443, and Caddy gets a Let's Encrypt certificate. Point the domain's DNS at the
  host first.

It writes `.env` (mode 600), builds the images, starts the stack, seeds the database, and prints the console URL and
the **admin** password. The password is shown only once, so save it.

If the release's signed manifest verifies, setup.sh puts the agent's signing checksum in the enrollment QR. If it
doesn't, the console keeps its debug enrollment defaults ([trust chain](docs/OPERATIONS.md#agent-apk-trust-chain)).

The stack runs `postgres`, `server` (Tomcat), `caddy` (the edge, serving the console), `supervisor` (updates and the
recovery page) and, in tunnel mode, `cloudflared`. Postgres and the server publish **no** host ports.

```bash
docker compose --profile cloudflare up -d                                   # cloudflare mode
docker compose -f docker-compose.yml -f docker-compose.domain.yml up -d     # own-domain mode
docker compose logs -f server
docker compose down
```

Re-runs reuse `.env` and never re-seed a database that holds data. `./setup.sh --reset` regenerates `.env` and its
secrets. It is only safe on an empty database, so run `docker compose down -v` first, which deletes all data.

## Option C — Native (no Docker)

```bash
sudo ./setup.sh --native      # → install/install-native.sh
```

Run it as root from a clone. It is tested on Debian 12, Debian 13 and Ubuntu 24.04 (`tests/native`). The
installer never calls `sudo`, so on a root-only host (a Proxmox LXC container, a minimal Debian image) run
`./setup.sh --native` as root.

The only prerequisite is `git`; the rest comes from apt. Two runtimes the distros don't all ship are downloaded into
`/opt/mdmesh-runtime`, at pinned versions checked against SHA-256s:

- **Node 24**, unless one is already on `PATH`. Your system Node is left alone.
- **JDK 17**, unless one is found under `/usr/lib/jvm` or `/opt`, or at `JAVA17_HOME`, or apt has `openjdk-17-jdk`.
  Where apt has none (Debian 13), the installer downloads Temurin 17.

Both downloads cover x86_64 and arm64. On other architectures, install both runtimes yourself first.

The installer asks you to type `yes`, then asks for the base URL and the HTTP port. `-y` skips these
questions (and answers Keep on an upgrade), and `-v` shows the full output:

- **Base URL:** set `BASE_URL=https://mdm.example.com` to answer it in advance. `-y` requires it on a fresh
  install; re-runs keep the current one unless you set it ([Upgrading](docs/UPGRADING.md#native-installs)).
- **Port:** set `HTTP_PORT=9090` to answer it in advance. The default is the port the install already uses (8080 on a
  fresh install), and `-y` takes that default.

`--reset` and `--allow-downgrade` are Docker-only, and `--native` ignores them. To wipe a native install, choose
Erase or set `REPLACE_DATA=yes`. In Docker mode, setup.sh rejects options it doesn't know.

Pass other settings, such as SMTP and `LEGACY_DEVICE_API`, in the installer's environment
([configuration reference](docs/OPERATIONS.md#configuration-reference)).

What gets installed:

- **Server:** Tomcat in `/opt/mdmesh-tc`, run by the unprivileged `mdmesh` user as `mdmesh-server.service`. Its
  settings are in `/opt/mdmesh-tc/conf/Catalina/localhost/ROOT.xml`, and uploaded files and backups go in
  `/opt/mdmesh`.
- **Supervisor:** `mdmesh-supervisor.service`, listening on loopback `:9000`, with its settings in
  `/etc/mdmesh/supervisor.env`.

```bash
systemctl status mdmesh-server        # health, PID, recent log lines
systemctl restart mdmesh-server       # after editing conf/Catalina/localhost/ROOT.xml
journalctl -u mdmesh-server -f        # follow Tomcat's stdout/stderr
```

The installer doesn't open ports 80/443, so put your own TLS proxy in front. If the chosen port is held by a process
the installer didn't start, it stops rather than kill that process.

To upgrade, `git pull` and run the same command again ([Upgrading](docs/UPGRADING.md#native-installs)).

## First login and enrolling devices

Sign in as **admin** with the temporary password the installer printed, then set your own.

One prebuilt agent APK works for every deployment. The server URL travels in the enrollment QR
(`com.mdmesh.SERVER_URL`), not in the APK. The latest verified release's APK is served at `/files/agent.apk`; if it
isn't, host one there yourself. Generate the QR on the console's **Enroll** page. It holds your `BASE_URL`, the APK
location and a single-use token.

## Updates, recovery and logs

- **Updates** and version notes: [docs/UPGRADING.md](docs/UPGRADING.md).
- **Recovery** (the `/recovery` page, rollback, restoring by hand):
  [docs/UPGRADING.md, Recovery and rollback](docs/UPGRADING.md#recovery-and-rollback).
- **Server logs** and a temporary DEBUG log: [docs/OPERATIONS.md, Server logs](docs/OPERATIONS.md#server-logs).
- **Settings**: [configuration reference](docs/OPERATIONS.md#configuration-reference).
- [Health checks](docs/OPERATIONS.md#health-checks), [backups](docs/OPERATIONS.md#backups-and-restore),
  [moving to a new BASE_URL](docs/OPERATIONS.md#moving-to-a-new-base_url),
  [uninstalling](docs/OPERATIONS.md#uninstalling) and [security](docs/OPERATIONS.md#security-notes).

## External reverse proxy with an HTTPS origin (Docker)

Choose **3) External reverse proxy** in `setup.sh` or `quickstart.sh`. The proxy owns
and renews the public certificate. Caddy uses its persistent internal CA and publishes
only TCP 443; it does not request public certificates or listen for HTTP redirects.
The native installer already expects an external proxy and is unchanged.

The wizard separates the public URL from the origin TLS name, for example:

```dotenv
BASE_URL=https://mdm.example.com
CADDY_HOST=mdmesh.internal
SITE_ADDRESS='https://mdmesh.internal:443, https://mdm.example.com:443'
TRUSTED_PROXIES='192.0.2.10/32'
COMPOSE_FILE=docker-compose.yml:docker-compose.reverse-proxy.yml
COMPOSE_PROFILES=
```

Use the proxy's source IP/CIDR in `TRUSTED_PROXIES` (multiple peers are space-separated).
`CADDY_HOST` defaults to the public hostname. A separate internal name need only resolve
on the proxy, or the proxy can connect to the origin IP with that name as TLS SNI.
Caddy accepts both names so the proxy can preserve the public `Host` header.
It also uses `CADDY_HOST` as the default certificate name for older proxies that
omit TLS SNI, including legacy WSS connections.
`BASE_URL` remains public for enrollment, APK downloads and links sent to devices.

After starting the stack, export the **public root certificate** and trust it in
**the proxy** (Android devices use the public proxy certificate):

```bash
docker compose cp caddy:/data/caddy/pki/authorities/local/root.crt ./mdmesh-origin-ca.crt
```

Preserve `caddy_data`: replacing its CA requires updating the proxy's trust. Configure
upstream HTTPS on port 443 with SNI `mdmesh.internal`, certificate verification and
this CA. Forward `Host`, `X-Forwarded-For`, `X-Forwarded-Proto` and WebSocket upgrades.
For an existing Nginx public TLS site:

```nginx
# In http {}:
map $http_upgrade $mdmesh_connection {
    default upgrade;
    ''      close;
}
# In the public site's location / {}:
proxy_pass https://192.0.2.20:443;
proxy_ssl_server_name on;
proxy_ssl_name mdmesh.internal;
proxy_ssl_verify on;
proxy_ssl_trusted_certificate /etc/nginx/mdmesh-origin-ca.crt;
proxy_set_header Host $host;
proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
proxy_set_header X-Forwarded-Proto $scheme;
proxy_http_version 1.1;
proxy_set_header Upgrade $http_upgrade;
proxy_set_header Connection $mdmesh_connection;
```

Use the reverse-proxy overlay alone, without `docker-compose.domain.yml`. Restrict
origin access to the proxy at your firewall. Setup re-runs and the updater retain
`COMPOSE_FILE`. Quickstart mounts the origin config over published web images;
its opt-in overlay and edge config come from the wizard's branch, while compose,
seed and images stay release-pinned.
