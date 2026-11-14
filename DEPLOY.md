# Deploying MDMesh

<sub>[← README](README.md) · **Deploy** · [Upgrading](docs/UPGRADING.md) · [Operations](docs/OPERATIONS.md) · [Structure](STRUCTURE.md) · [Contributing](CONTRIBUTING.md) · [Releasing](RELEASING.md)</sub>

There are three ways to run MDMesh. Each one generates its own secrets and admin login. There are no default
passwords, and the admin must set its own password on first login.

## Pick a method

| Method | Needs | Choose it when |
|--------|-------|----------------|
| [A. Quick start](#option-a--quick-start-no-clone-published-images) | Docker with Compose v2, `curl`, `minisign` to check the script, `openssl` 3.0+ (or an older `openssl` and `minisign`) | You want the fastest path, with one-click updates and rollback. |
| [B. From source](#option-b--from-source-clone--build) | Docker with Compose v2, a clone, and for `./setup.sh --update`: `git`, `curl`, `openssl` 3.0+ (or an older `openssl` and `minisign`). `python3` to verify the release for the QR (optional) | You want to build the images yourself. |
| [C. Native](#option-c--native-no-docker) | Debian 12/13 or Ubuntu 24.04, root, `git` (`--update` installs `curl` and `openssl` when they are missing) | You don't want Docker, and you run your own TLS proxy. |

Options A and B ask for the public hostname and set `BASE_URL=https://<hostname>`. Option C asks for the full base
URL (`https://…`; `http://` is accepted too). See the [rules](docs/OPERATIONS.md#base-url-and-hostname-rules).

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

It asks for the hosting mode (as in [Option B](#option-b--from-source-clone--build)), the GitHub repo and GHCR owner
to pull from, and the hostname. Then it creates `./mdmesh`, downloads the pull-only compose file and the seed,
generates secrets, pulls and starts the stack, seeds the database, and prints the console URL and a temporary admin
password.

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
| `MDMESH_MODE` | A, B | `tunnel` (Cloudflare Tunnel) or `domain` (your own domain); `1` and `2` work too | asked |
| `MDMESH_HOST` | A, B | the public hostname only, e.g. `mdm.example.com` (no `https://`), optionally `:port` | asked |
| `MDMESH_TUNNEL_TOKEN` | A, B | the Cloudflare Tunnel token (tunnel mode) | asked |
| `MDMESH_ACME_EMAIL` | A, B | the email for Let's Encrypt (domain mode); empty for none | asked |
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
- [Health checks](docs/OPERATIONS.md#health-checks), [backups](docs/OPERATIONS.md#backups-and-restore),
  [moving to a new BASE_URL](docs/OPERATIONS.md#moving-to-a-new-base_url),
  [uninstalling](docs/OPERATIONS.md#uninstalling) and [security](docs/OPERATIONS.md#security-notes).
