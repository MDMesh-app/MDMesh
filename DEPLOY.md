# Deploying MDMesh

<sub>[← README](README.md) · **Deploy** · [Upgrading](docs/UPGRADING.md) · [Operations](docs/OPERATIONS.md) · [Structure](STRUCTURE.md) · [Contributing](CONTRIBUTING.md) · [Releasing](RELEASING.md)</sub>

Each install generates its own secrets and admin login. There are no default passwords, and the admin must set its own
password on first login.

## Which install is for me?

Most people want the **quick start**: one command, Docker, published images, one-click updates and rollback. It asks
one question, how your phones will reach MDMesh:

| Your situation | Pick | What MDMesh does |
|---|---|---|
| Your domain is on Cloudflare, and you'd rather open no ports | [Quick start](#option-a--quick-start-no-clone-published-images), **1 Cloudflare Tunnel** | Phones reach it through the tunnel; Cloudflare does HTTPS. |
| A domain points at this machine, and ports 80/443 are free | [Quick start](#option-a--quick-start-no-clone-published-images), **2 Your own domain** | Gets its own Let's Encrypt certificate and answers on 80/443. |
| nginx, Apache, Caddy or Traefik already does HTTPS here (ports 80/443 are taken) | [Quick start](#option-a--quick-start-no-clone-published-images), **3 Behind your own proxy** | Listens on `127.0.0.1:8080`; your proxy forwards to it. |
| No Docker | [Native](#option-c--native-no-docker) | Tomcat as a systemd service, behind your own proxy (unchanged). |

[From source](#option-b--from-source-clone--build) is the quick start's modes with images you build yourself.

| Install | Needs |
|---|---|
| Quick start | Docker with Compose v2, `curl`, `minisign` to check the script, `openssl` 3.0+ (or an older `openssl` and `minisign`) |
| From source | Docker with Compose v2, a clone, and for `./setup.sh --update`: `git`, `curl`, `openssl` 3.0+ (or an older `openssl` and `minisign`). `python3` to verify the release for the QR (optional) |
| Native | Debian 12/13 or Ubuntu 24.04, root, `git` (`--update` installs `curl` and `openssl` when they are missing) |

The quick start and from source ask for the public hostname and set `BASE_URL=https://<hostname>`. Native asks for the
full base URL (`https://…`; `http://` is accepted too). See the [rules](docs/OPERATIONS.md#base-url-and-hostname-rules).

## Option A — quick start, no clone (published images)

Run it from where you want `./mdmesh` created. This downloads `quickstart.sh` and the signed manifest from the latest
release, checks the manifest's signature with `minisign` against the release key (the key line of
`release/minisign.pub`), checks that the manifest is that release's and that it holds `quickstart.sh`'s SHA-256, and
only then runs the script with `MDMESH_VERSION` set to that release, so it installs exactly the release it checked:

```bash
(
  R=https://github.com/MDMesh-app/MDMesh/releases
  V=$(curl -fsSLI -o /dev/null -w '%{url_effective}' "$R/latest" | sed -n 's#^https://github\.com/MDMesh-app/MDMesh/releases/tag/v\([0-9][0-9]*\.[0-9][0-9]*\.[0-9][0-9]*\)$#\1#p')
  d=$(mktemp -d) || exit 1; trap 'rm -f -- "$d"/*; rmdir -- "$d"' EXIT
  for f in quickstart.sh manifest.json manifest.json.minisig; do
    [ -n "$V" ] && curl -fsSL -o "$d/$f" "$R/download/v$V/$f" || V=
  done
  [ -n "$V" ] && minisign -V -m "$d/manifest.json" -P RWQbf0pIhddaWnDsFknIG4X325wHR05pUxEQJmuuQRdBlQm3zi8/Eqcd &&
    grep -qxF "  \"version\": \"$V\"," "$d/manifest.json" &&
    grep -qF "\"quickstart.sh\": \"$(sha256sum "$d/quickstart.sh" | cut -d' ' -f1)\"" "$d/manifest.json" ||
    { echo "Not running quickstart.sh: a download or check failed (see above). Nothing was installed." >&2; exit 1; }
  MDMESH_VERSION="$V" bash "$d/quickstart.sh"
)
```

If a download or check fails, it says so and nothing runs. It runs in a subshell and removes its temporary
directory afterwards. This needs releases from 0.5.0 on (earlier ones don't publish `quickstart.sh`). The README's
one-line command runs the same release asset without these checks.

**Unchecked shortcut.** Without `minisign`, this looks up the latest release's tag and runs `quickstart.sh` from
`refs/tags/<tag>` in the repository, **without checking it** (the script still verifies everything it downloads, but
not itself). It accepts only a `vX.Y.Z` release tag and stops otherwise; what protects that tag is in
[RELEASING.md](RELEASING.md#how-a-deployment-trusts-a-release):

```bash
(
  t=$(curl -fsSLI -o /dev/null -w '%{url_effective}' https://github.com/MDMesh-app/MDMesh/releases/latest | sed -n 's#^https://github\.com/MDMesh-app/MDMesh/releases/tag/\(v[0-9][0-9]*\.[0-9][0-9]*\.[0-9][0-9]*\)$#\1#p')
  [ -n "$t" ] && q=$(curl -fsSL "https://raw.githubusercontent.com/MDMesh-app/MDMesh/refs/tags/$t/quickstart.sh") ||
    { echo "Could not get the latest release's quickstart.sh. Nothing was installed." >&2; exit 1; }
  bash -c "$q" quickstart.sh
)
```

It needs Docker with Compose v2, curl, and OpenSSL 3.0 or newer to check the release signature. On a host with
OpenSSL 1.1.1 (Ubuntu 20.04, RHEL/Alma/Rocky 8, Amazon Linux 2), install `minisign` first; the quick start then uses
it instead.

This needs a published release whose GHCR packages (`mdmesh-server`, `mdmesh-web`, `mdmesh-supervisor`) are public.
If they aren't, run `docker login ghcr.io` first ([RELEASING.md](RELEASING.md)).

It asks how your phones will reach MDMesh ([the three modes](#how-phones-reach-mdmesh) below), the GitHub repo and
GHCR owner to pull from, and the hostname. Then it creates `./mdmesh`, downloads the pull-only compose file and the
seed, generates secrets, pulls and starts the stack, seeds the database, and prints the console URL and a temporary
admin password. To skip the first question, add the mode after the command (`--tunnel`, `--domain` or `--proxy`,
as in `bash <(curl …/quickstart.sh) --proxy`), or `export MDMESH_MODE=proxy` before the checked block.

If the quick start stops after it wrote `.env` (an image pull, a port in use), fix the cause and run it again with
`MDMESH_DIR` set to that directory, as it says: it finishes the install with the same `.env`. Don't delete that
`.env` while the install's volumes exist: the database keeps the password in it.

Run every `docker compose` command from `./mdmesh`, where `.env` lives. The install is pinned to the latest
release: `CURRENT_VERSION` names it, and `SERVER_VERSION`, `WEB_VERSION` and `SUPERVISOR_VERSION` name its images by
digest (`0.5.0@sha256:…`). Updates are one click ([Upgrading](docs/UPGRADING.md#quick-start-installs)).

Before using anything from the release, the quick start checks its `manifest.json` against the release signing key
(minisign format, checked with `openssl` 3.0+, or with `minisign` when that is installed). The signed manifest holds
the image digests and the SHA-256 of the compose files, the seed and `install/lib/db.sh`, which come from the
release's tag; a file that doesn't match stops the install. The trust root is `quickstart.sh` itself, which carries
a copy of `release/minisign.pub`: checked against the signed manifest by the block above, or taken on trust (from the
release's tag by the shortcut, or as the release asset by the README's command). A fork with its own signing key sets
`MDMESH_RELEASE_PUBKEY` to the key line of its `release/minisign.pub`.

If the latest release can't be looked up (GitHub API unreachable or rate-limited), the quick start stops and says so;
it no longer falls back to `main` and `:latest`. Run it again later, or name the release:
`MDMESH_VERSION=X.Y.Z bash <(curl -fsSL https://raw.githubusercontent.com/MDMesh-app/MDMesh/refs/tags/vX.Y.Z/quickstart.sh)`
(unchecked, like the shortcut).

## How phones reach MDMesh

The quick start and from source set up one of three modes. The mode is kept by updates, rollbacks and `setup.sh`
re-runs; to change it later, see [Upgrading, Changing the hosting mode](docs/UPGRADING.md#changing-the-hosting-mode).
In every mode MDMesh's edge (Caddy) is in front of the server: it serves the console, routes the API, the agent's
wake socket and the recovery page, and adds the security headers. Postgres and the server publish no host ports.

```text
How will your phones reach MDMesh?
  1) Cloudflare Tunnel       easiest: no ports to open (domain on Cloudflare)
  2) Your own domain         MDMesh gets its own HTTPS (ports 80/443 must reach this machine)
  3) Behind your own proxy   your nginx/Apache/Caddy/Traefik does HTTPS; MDMesh listens on 127.0.0.1:8080
```

If something on the machine already listens on port 80 or 443, the question says so: that is usually a web server
of yours, and 3 is the mode for it.

### 1. Cloudflare Tunnel

No open ports, and Cloudflare manages TLS. You need a domain in a Cloudflare account. Create a tunnel (Zero Trust →
Networks → Tunnels), route its public hostname to `http://caddy:80`, and paste the tunnel token when asked. The
stack runs `cloudflared` too. `.env`: `COMPOSE_PROFILES=cloudflare`.

### 2. Your own domain

Point the domain's DNS at this machine and open ports 80 and 443 first. Caddy gets a Let's Encrypt certificate (the
email it asks for gets the certificate notices) and answers on both ports.
`.env`: `COMPOSE_FILE=docker-compose.yml:docker-compose.domain.yml`.

### 3. Behind your own proxy

For a machine where nginx, Apache, Caddy or Traefik already does HTTPS for other sites. Your proxy keeps ports 80/443
and its certificate, and forwards one hostname to MDMesh. MDMesh's edge gets no certificate of its own and listens on
one address, `127.0.0.1:8080` by default. It asks:

- **The public hostname** your proxy serves, e.g. `mdm.example.com` (`BASE_URL=https://mdm.example.com`).
- **Where MDMesh listens** (`MDMESH_BIND`, default `127.0.0.1:8080`). Keep the default for a proxy on this machine.
  For a proxy on another machine, an address of this one it reaches, e.g. `10.0.0.5:8080`, and keep that port off the
  internet (a firewall rule that lets only your proxy in).
- **Your proxy's address, as MDMesh sees it** (`TRUSTED_PROXIES`, default `host`). MDMesh believes `X-Forwarded-For`
  (the client's address, for sign-in limits, the audit log and a device's public IP) and `X-Forwarded-Proto` from
  these addresses only. `host` means a proxy on this machine: Docker delivers its connections from the stack
  network's gateway, which `host` stands for. It is accepted only with a loopback `MDMESH_BIND` (`127.0.0.1` or
  `[::1]`): on any other address, other machines' connections can arrive from that same gateway (Docker Desktop,
  rootless Docker, IPv6), so the installers and the edge refuse it there. For a proxy elsewhere, its IP address (or a
  range, no wider than an IPv4 /8 or IPv6 /16; IPv4 addresses as IPv4, not `::ffff:…`), several separated by spaces.
  Anyone else's `X-Forwarded-For` and `X-Forwarded-Proto` are ignored.

`.env` then holds `COMPOSE_FILE=docker-compose.yml:docker-compose.proxy.yml`, `MDMESH_BIND`, `TRUSTED_PROXIES` and
`MDMESH_ORIGIN_TLS`; the overlay publishes the edge on `MDMESH_BIND` and nothing else.

**Your proxy's configuration.** Ready ones, each tested in front of MDMesh by `tests/proxy-e2e`:

| Proxy | File |
|---|---|
| nginx | [`docs/proxies/nginx.conf`](docs/proxies/nginx.conf) |
| Apache httpd 2.4.47+ | [`docs/proxies/apache.conf`](docs/proxies/apache.conf) |
| Caddy | [`docs/proxies/Caddyfile`](docs/proxies/Caddyfile) |
| Traefik v3 | [`docs/proxies/traefik.yml`](docs/proxies/traefik.yml) and [`traefik-dynamic.yml`](docs/proxies/traefik-dynamic.yml) |

Replace `mdm.example.com` and the certificate paths. Whatever the proxy, it must:

- forward every path unchanged (no prefix stripped, nothing decoded), with the public `Host`;
- set `X-Forwarded-For` to the address it sees (overwriting, or appending a plain IP: MDMesh reads it from the right)
  and send `X-Forwarded-Proto`;
- pass WebSocket upgrades: the agent's wake socket at `/agent/ws/…` stays open while idle (the agent pings every 50
  seconds), so allow at least 60 seconds of quiet, an hour in the examples;
- accept request bodies of 500 MB (APK uploads in the console) and not time out a long upload.

**HTTPS between your proxy and MDMesh** (a proxy on another machine). Set `MDMESH_ORIGIN_TLS=1` in `.env` (or answer
it in advance), then `docker compose up -d caddy`. MDMesh's edge then speaks HTTPS on `MDMESH_BIND`, with a
certificate for your public hostname from its own CA (Caddy's internal CA, kept in the `caddy_data` volume). Copy the
CA's root certificate to your proxy and have it check MDMesh's certificate against it:

```bash
docker compose cp caddy:/data/caddy/pki/authorities/local/root.crt ./mdmesh-origin-ca.crt
```

Each example has the lines for it, commented out (nginx: `proxy_ssl_name` and `proxy_ssl_trusted_certificate`). Your
proxy must send the public hostname as `Host`; a proxy that sends no TLS server name still gets the certificate.
Keep the `caddy_data` volume: a new one makes a new CA, which your proxy would have to trust again.

Notes:

- On a Docker version before 28, a port published on `127.0.0.1` can be reached by other machines on the same local
  network ([moby#45610](https://github.com/moby/moby/issues/45610)); they could then name their own address. Update
  Docker, or drop such traffic in the firewall.
- With rootless Docker or Docker Desktop, every connection to `MDMESH_BIND` arrives from Docker's gateway, so MDMesh
  can't see a proxy on another machine by its address: it then believes no one's `X-Forwarded-For`, and every client
  counts as one address for the sign-in limits. The installers warn about it. Keep the proxy on the same machine and
  MDMesh on `127.0.0.1` there.
- The installers check that nothing listens on `MDMESH_BIND` before they write `.env`. If the edge still can't
  publish it, set another one in `.env` and run `docker compose up -d` (a quick start: run the quick start again, as
  it says; it finishes the install with the same `.env`).
- Behind a CDN or a load balancer in front of your proxy, have your proxy take the client's address from it first
  (nginx: `set_real_ip_from` and `real_ip_header`), so the address it forwards is the client's.
- Traefik with the Docker provider: run Traefik with `network_mode: host`, or point its file provider at
  `MDMESH_BIND`, as in the example.

### Your own edge rules

The edge imports every `*.caddy` file in `site.d/` in the install directory into its site, after MDMesh's own
settings: an IP allowlist for the console, an extra header, a redirect. Updates and rollbacks never touch `site.d`,
and the installers create it (with a short README). For example, `site.d/office-only.caddy`:

```caddyfile
# The console's API only from the office (client_ip: the client as the edge believes it, also behind your proxy).
@outside {
	path /rest/private/*
	not client_ip 203.0.113.0/24
}
handle @outside {
	respond 403
}
```

Wrap a rule that answers a request in `handle` (or `route`), as here: Caddy runs MDMesh's own `handle` blocks before
a bare `respond`, so a bare one would never answer the paths MDMesh routes. `scripts/edge-check.sh` runs this example.

Make the file readable by everyone (`chmod 644`), check it, then restart the edge:

```bash
docker compose exec caddy caddy validate --config /etc/caddy/Caddyfile && docker compose restart caddy
```

A rule that doesn't parse stops the edge, and with it the console and `/recovery`, until it is fixed. `.env`'s
`MDMESH_SITE_DIR` names the directory (an absolute path, so the updater mounts the same one). An install from
before 0.6.0 gets it at its first `./setup.sh` run (from source), or as in
[Upgrading, v0.6.0](docs/UPGRADING.md#v060) (quick start).

## Option B — from source (clone + build)

```bash
git clone https://github.com/MDMesh-app/MDMesh.git && cd MDMesh
./setup.sh --update      # checks out the newest signed release, then sets it up
```

`--update` looks up the release tags, checks the newest release's signed manifest (as the quick start does, with the
clone's `release/minisign.pub`) and that the commit it names is the one the tag points to, then fetches and checks
out that commit and runs that release's setup.sh. A newest tag whose release isn't published yet (GitHub answers 404)
is skipped, saying so; over an install it never goes below the release the clone is on, and it tries at most the 5
newest tags. If GitHub
can't be reached (a timeout, DNS, TLS or a server error, each retried twice), it stops and changes nothing. It also
stops on a manifest that doesn't verify (or, from before 0.5.0, names no commit) and on local changes. Run the same
command to update later; the clone stays on a release tag, so don't `git pull`.

A clone from 0.4.x has no `--update` yet. Move it to 0.5.0 once, whether it is on `main` or on a release tag:
`git fetch --tags && git checkout v0.5.0 && ./setup.sh --update` (native: `git fetch --tags && git checkout v0.5.0 && sudo ./setup.sh --update --native`). Git refuses the checkout if it
would overwrite local changes, and `--update` stops on any it would carry along. From then on, `./setup.sh --update`
is enough.

The wizard asks how your phones will reach MDMesh, as the quick start does ([the three modes](#how-phones-reach-mdmesh));
`./setup.sh --tunnel`, `--domain` or `--proxy` answers it. It writes `.env` (mode 600), builds the images, starts the stack, seeds the database, and prints the console URL and
the **admin** password. The password is shown only once, so save it.

If the release's signed manifest verifies, setup.sh puts the agent's signing checksum in the enrollment QR. If it
doesn't, the console keeps its debug enrollment defaults ([trust chain](docs/OPERATIONS.md#agent-apk-trust-chain)).

The stack runs `postgres`, `server` (Tomcat), `caddy` (the edge, serving the console), `supervisor` (updates and the
recovery page) and, in tunnel mode, `cloudflared`. Postgres and the server publish **no** host ports.

```bash
docker compose --profile cloudflare up -d                                   # cloudflare mode
docker compose -f docker-compose.yml -f docker-compose.domain.yml up -d     # own-domain mode
docker compose -f docker-compose.yml -f docker-compose.proxy.yml up -d      # behind your own proxy
docker compose logs -f server
docker compose down
```

Re-runs reuse `.env` and never re-seed a database that holds data. `./setup.sh --reset` regenerates `.env` and its
secrets. It is only safe on an empty database, so run `docker compose down -v` first, which deletes all data.

## Option C — Native (no Docker)

Native is unchanged in 0.6.0: the same commands as before, to install and to update.

```bash
sudo ./setup.sh --update --native    # newest signed release, then install/install-native.sh
```

Run it as root from a clone. It is tested on Debian 12, Debian 13 and Ubuntu 24.04 (`tests/native`). The
installer never calls `sudo`, so on a root-only host (a Proxmox LXC container, a minimal Debian image) run
`./setup.sh --update --native` as root.

The only prerequisite is `git`; the rest comes from apt (`sudo ./setup.sh --update --native` first installs `curl`
and `openssl` when they are missing, to check the release). Two runtimes the distros don't all ship are downloaded into
`/opt/mdmesh-runtime`, at pinned versions checked against SHA-256s:

- **Node 24**, unless a Node 24 that the `mdmesh` service account can run is already on `PATH`. Your system Node is left alone.
- **JDK 17**, unless one is found under `/usr/lib/jvm` or `/opt`, or at `JAVA17_HOME`, or apt has `openjdk-17-jdk`.
  Where apt has none (Debian 13), the installer downloads Temurin 17.

Both downloads cover x86_64 and arm64. On other architectures, install both runtimes yourself first.

Use PostgreSQL 15 or newer, as on the tested distros. With an older one already installed, the installer
warns: the install works, but [restoring a backup](docs/OPERATIONS.md#backups-and-restore) needs 15 or newer.

The installer asks you to type `yes`, then asks for the base URL and the HTTP port. `-y` skips these
questions (and answers Keep on an upgrade), and `-v` shows the full output:

- **Base URL:** set `MDMESH_BASE_URL=https://mdm.example.com` to answer it in advance. `-y` requires it on a fresh
  install; re-runs keep the current one unless you set it ([Upgrading](docs/UPGRADING.md#native-installs)).
- **Port:** set `MDMESH_HTTP_PORT=9090` to answer it in advance. The default is the port the install already uses
  (8080 on a fresh install), and `-y` takes that default.

`--reset` and `--allow-downgrade` are Docker-only, and `--native` ignores them. To wipe a native install, choose
Erase or set `MDMESH_REPLACE_DATA=yes`. In Docker mode, setup.sh rejects options it doesn't know. The older names
`BASE_URL`, `HTTP_PORT`, `REPLACE_DATA` and `ASSUME_YES` still work ([all the answers](#answering-the-questions-in-advance)).

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

To upgrade, run the same command again ([Upgrading](docs/UPGRADING.md#native-installs)). A clone from 0.4.x has no
`--update` yet: move it to 0.5.0 once with `git fetch --tags && git checkout v0.5.0 && sudo ./setup.sh --update --native`.

## Answering the questions in advance

Every question the installers ask can be answered before they run, with an environment variable or a line in an
answers file. A question with an answer isn't asked; one without is asked as usual. Each answer, given in advance or
typed at the question, is checked with the
same rule as the question, and a wrong one stops the installer before it changes anything. The tunnel token is never
printed.

| Variable | Method | Answer | If not given |
|---|---|---|---|
| `MDMESH_MODE` | A, B | `tunnel` (Cloudflare Tunnel), `domain` (your own domain) or `proxy` (behind your own proxy); `1`, `2` and `3` work too, as do the flags `--tunnel`, `--domain` and `--proxy` | asked |
| `MDMESH_HOST` | A, B | the public hostname only, e.g. `mdm.example.com` (no `https://`), optionally `:port` | asked |
| `MDMESH_TUNNEL_TOKEN` | A, B | the Cloudflare Tunnel token (tunnel mode) | asked |
| `MDMESH_ACME_EMAIL` | A, B | the email for Let's Encrypt (domain mode); empty for none | asked |
| `MDMESH_BIND` | A, B | where MDMesh listens for your proxy, `IP:port` (proxy mode) | asked, default `127.0.0.1:8080` |
| `MDMESH_TRUSTED_PROXIES` | A, B | your proxy's addresses or ranges, or `host` for a proxy on this machine (proxy mode) | asked, default `host` |
| `MDMESH_ORIGIN_TLS` | A, B | `1`: HTTPS from your proxy to MDMesh, with MDMesh's own CA (proxy mode) | not asked: `0` |
| `MDMESH_REPO` | A | the GitHub repo to install from, `owner/name` | asked, default `MDMesh-app/MDMesh` |
| `MDMESH_IMAGE_OWNER` | A | the GHCR owner of the images, lowercase | asked, default `mdmesh-app` |
| `MDMESH_ASSUME_YES` | C | `1`: the same as `-y` | you're asked to type `yes` |
| `MDMESH_BASE_URL` | C | the public base URL, `https://…` (`http://` works too) | asked; a re-run keeps the current one |
| `MDMESH_HTTP_PORT` | C | the HTTP port, 1-65535 | asked, default 8080 or the current port |
| `MDMESH_REPLACE_DATA` | C | `no` (Keep) or `yes` (Erase), when the database holds data | asked; `-y` keeps the data |

setup.sh (B) asks only when it writes a new `.env`; a re-run reuses its `.env` and ignores these answers.

- `MDMESH_ANSWERS=/full/path/answers.env` reads answers from a file of `KEY=VALUE` lines (`#` comments allowed;
  never run as a script). A variable set in the environment wins over the file. The file chooses what gets installed
  and may hold the tunnel token, so it must belong to you (or root) with mode 600, or the installer refuses to read
  it. Under `sudo`, `chown root` it.
- `MDMESH_NONINTERACTIVE=1` never asks: a missing answer with a default takes it, and any other stops the installer,
  naming its variable. Use it whenever there is no terminal to answer on. The native installer also needs
  `MDMESH_ASSUME_YES=1` (or `-y`).
- `MDMESH_DRY_RUN=1` checks the answers, prints what a run would use and ask, and exits. It needs no root or Docker,
  and changes nothing. With `./setup.sh --update` it also looks up and checks the newest release (network), but
  fetches and checks out nothing.

A quick start on your own domain, with no questions: set these, then run the checked install block in
[Option A](#option-a--quick-start-no-clone-published-images). It passes them on to `quickstart.sh` (and sets
`MDMESH_VERSION` to the release it checked, which wins over one in an answers file).

```bash
export MDMESH_MODE=domain MDMESH_HOST=mdm.example.com MDMESH_ACME_EMAIL=ops@example.com MDMESH_NONINTERACTIVE=1
```

With Cloudflare Tunnel, keep the token out of `export` and the command line, where shell history and other users
(through the process list) can see it. Put it in a private answers file with an editor:

```bash
touch answers.env && chmod 600 answers.env
nano answers.env    # MDMESH_MODE=tunnel, MDMESH_HOST=mdm.example.com and MDMESH_TUNNEL_TOKEN=<your token>, one per line
export MDMESH_ANSWERS="$PWD/answers.env" MDMESH_NONINTERACTIVE=1
```

Then run the same Option A block. To see what it would do first, add `MDMESH_DRY_RUN=1` to the exports.

The installer never prints the token (nor with `bash -x`), and no program it starts sees it in its arguments or
environment. `./setup.sh --update` hands it only to the release's setup.sh it runs next, which takes it out at once;
the native installer never gets it. Delete the file once the install is done.

Native, from a clone: `sudo MDMESH_ASSUME_YES=1 MDMESH_BASE_URL=https://mdm.example.com ./setup.sh --update --native`.

[`install/options.json`](install/options.json) lists each answer's variable, rule, default and question. The install
guide on the website reads it from the release, and CI checks it against the installers.

## First login and enrolling devices

Sign in as **admin** with the temporary password the installer printed, then set your own.

One prebuilt agent APK works for every deployment. The server URL travels in the enrollment QR
(`com.mdmesh.SERVER_URL`), not in the APK. The latest verified release's APK is served at `/files/agent.apk`; if it
isn't, host one there yourself. Generate the QR on the console's **Enroll** page, with the console open at your
`BASE_URL`: the QR holds the address the console is open at (the page warns when that is localhost, `http://`, a local
address or not `BASE_URL`, since devices keep it until a factory reset), the APK location and an enrollment token: single-use and valid for 24 hours by default, or, to set up a batch, for up to 100
devices and 30 days (the API allows up to 1000 devices). **Stop all codes** on the same page ends every code that
still works.

## Updates, recovery and logs

- **Updates** and version notes: [docs/UPGRADING.md](docs/UPGRADING.md).
- **Recovery** (the `/recovery` page, rollback, restoring by hand):
  [docs/UPGRADING.md, Recovery and rollback](docs/UPGRADING.md#recovery-and-rollback).
- **Server logs** and a temporary DEBUG log: [docs/OPERATIONS.md, Server logs](docs/OPERATIONS.md#server-logs).
- **Settings**: [configuration reference](docs/OPERATIONS.md#configuration-reference).
- **Console accounts and roles**: [docs/USER_MANAGEMENT.md](docs/USER_MANAGEMENT.md).
- **Device configurations** and **kiosk mode**: [docs/CONFIGURATIONS.md](docs/CONFIGURATIONS.md) and
  [docs/KIOSK.md](docs/KIOSK.md).
- [Health checks](docs/OPERATIONS.md#health-checks), [backups](docs/OPERATIONS.md#backups-and-restore),
  [moving to a new BASE_URL](docs/OPERATIONS.md#moving-to-a-new-base_url),
  [uninstalling](docs/OPERATIONS.md#uninstalling) and [security](docs/OPERATIONS.md#security-notes).
