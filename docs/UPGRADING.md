# Upgrading MDMesh

<sub>[← README](../README.md) · [Deploy](../DEPLOY.md) · **Upgrading** · [Operations](OPERATIONS.md) · [Structure](../STRUCTURE.md) · [Contributing](../CONTRIBUTING.md) · [Releasing](../RELEASING.md)</sub>

| Install | Update with | One-click rollback |
|---------|-------------|--------------------|
| Quick start (Option A) | **Update** in the console, or automatic updates | Yes, from `/recovery` |
| From source (Option B) | `./setup.sh --update` (from 0.4.x, first [move to 0.5.0 once](#v050)) | Only with a registry `IMAGE_OWNER` ([below](#from-source-docker-installs)) |
| Native (Option C) | `sudo ./setup.sh --update --native` (from 0.4.x, first [move to 0.5.0 once](#v050)) | No |

Before you update, read the [version notes](#version-notes) for each release you are moving past.

## How updates work

The **supervisor** offers only GitHub releases whose signed manifest verifies. On quick-start installs it also
applies them: it backs up the database, then rolls back if the new version fails its health check (from 0.6.0's
updater on, the edge must also stay up)
([settings](OPERATIONS.md#updater-supervisor)). If no update shows up, see the `[verify]` line in
`docker compose logs supervisor` (native: `journalctl -u mdmesh-supervisor`).

If the update on offer skips over a release that fixed security issues (say this server runs 0.5.0, 0.5.1 fixed one,
and 0.5.2 is on offer), the console says so in amber: the sidebar and Overview cards say the update "includes security
fixes from 0.5.1 that this server is missing", the Settings list marks Updates "Security fixes", and Settings →
Updates names the releases and links their notes. The list is the signed manifest's `securityReleases` (every release
whose changelog has a Security section), read only from a manifest that verified; the supervisor reports the skipped
ones as `missedSecurity` in `/update/status`. Installing the offered release brings those fixes. It needs a 0.5.0 or
later supervisor and releases from 0.5.0 on, so a 0.4.x server sees no warning on its way to 0.5.0.

## Quick-start installs

The install's admin (a super-admin, or the org admin of a single-customer install) selects **Review update** on the
console's server-update card, then **Update server and console**. A failed update rolls back by itself.

**Automatic updates** (Settings, or `AUTO_UPDATE=1`) apply each release without a prompt. Once `/backups/auto.json`
exists (the Settings switch, or any failed or rolled-back update, creates it), it wins over `AUTO_UPDATE`; change it
in Settings. A release that failed, or that you rolled back
from, is never applied automatically again (`autoSkipped` in `/update/status`). **Update** still applies it by hand.

An update runs exactly the images its signed manifest names: from 0.5.0 the supervisor pins `SERVER_VERSION` and
`WEB_VERSION` in `.env` to their digests (`0.5.0@sha256:…`). A release whose manifest pins no digests, or pins them
under another owner than `IMAGE_OWNER` (a fork's install following upstream releases), is refused with that reason,
and nothing changes.

Updates never recreate the supervisor itself. To pick up its fixes after an update:

```bash
docker compose pull && docker compose up -d
```

When `SUPERVISOR_VERSION` is `latest` (quick starts before 0.5.0) this brings the newest supervisor. Once a 0.5.0 or
newer supervisor applies an update, it pins `SUPERVISOR_VERSION` to that release's supervisor by digest, and
`docker compose up -d` starts exactly that one. A plain version you set by hand is left alone: bump it yourself.

The supervisor you run decides whether updates are pinned, and how a rollback restores the database: one from before
0.5.0 applies the 0.5.0 update by tag and can't roll it back. So on 0.4.x, get the 0.5.0 supervisor *before* you
update ([v0.5.0](#v050)); every update from then on is pinned. If you set `SUPERVISOR_VERSION` yourself, see
[v0.5.0](#v050).

## From-source Docker installs

```bash
./setup.sh --update
```

`--update` moves the clone to the newest signed release tag and runs its setup.sh (see
[DEPLOY](../DEPLOY.md#option-b--from-source-clone--build)). It needs `git`, `curl` and `openssl` 3.0+ (or an older
`openssl` and `minisign`), and says which are missing before it does anything (Debian/Ubuntu:
`sudo apt-get install git curl openssl`); as root with `--native` it installs them itself. It comes with 0.5.0: a clone from 0.4.x moves to it once
with `git fetch --tags && git checkout v0.5.0 && ./setup.sh --update` ([v0.5.0](#v050)). A clone that is on `main` and
already has `--update` can be ahead of the newest release: over an install, `--update` then stops rather than run
older code. Wait for the next release and run it then, or pass `--allow-downgrade` to go to the release now. A clone
still on a release branch (`release/vX.Y.Z`, squash-merged into the release) is not ahead: `--update` moves it on.

setup.sh takes no backup, so dump the database first ([command](OPERATIONS.md#backups-and-restore)). Don't use
`docker compose up -d --build` alone: only setup.sh updates `CURRENT_VERSION` and the image tags from the
checkout's release tag. Without a release tag, it keeps the old values and warns.

With a registry `IMAGE_OWNER`, one-click Update and rollback work as on the quick start, and setup.sh refuses to
build in two cases:

- **Older checkout.** The checkout is older than the running `CURRENT_VERSION`, for example
  `running 0.4.0, checkout is 0.3.1 — run ./setup.sh --update, or re-run with --allow-downgrade`. Run `./setup.sh --update`.
  `--allow-downgrade` runs the older code on purpose, against the current database, which is not rolled back.
- **No version.** setup.sh can't read a release tag (a source tarball, or tags not fetched). Build from a tagged
  checkout, or pass `--allow-downgrade`. setup.sh then keeps the versions in `.env`; on a fresh install that is
  `0.0.0`, so every release shows as an update.

## Native installs

```bash
sudo ./setup.sh --update --native
```

From 0.4.x, move the clone to 0.5.0 once with `git fetch --tags && git checkout v0.5.0 && sudo ./setup.sh --update --native` ([v0.5.0](#v050)): 0.4.x has no `--update`.

Over an install, `--update` asks nothing. It shows `MDMesh · native update, <from> → <to>` and keeps the data, the
base URL and the port, as `-y` does. `<from>` is the release the install runs, as the install recorded it
(`/etc/mdmesh/installed-version`, or for installs from before that record `CURRENT_VERSION` in `supervisor.env`), never the release
the clone is on; `?` when it has neither. After a rollback with an older release's installer the record still names
the newer release until the next run; that is cosmetic. From 0.5.0 it still asks once, because 0.5.0's `--update`
doesn't tell the installer it is an update: it shows `This host runs v<from>; this clone is on v<to>` under its title.
Add `-y` to skip the questions. If you answer no there, the install is unchanged but the clone is already on the new
release: run `sudo ./setup.sh --update --native` again to finish.

Run without `--update` (`sudo ./setup.sh --native`), the installer asks you to type `yes`, then for the base URL and
the port, then to choose **Keep** (the default) or **Erase** (type `ERASE`). Keep runs migrations and leaves all data
and the enrollment secret alone, after a `pg_dump` to `/opt/mdmesh/backups/` ([restore](OPERATIONS.md#backups-and-restore)).

- **Base URL:** kept from the last run (`base.url` in `ROOT.xml`). An interactive run offers it as the default, and
  `--update` and `-y` use it. Set `BASE_URL` to change it.
- **Unattended (`--update`, `-y`):** never erases unless you set `REPLACE_DATA=yes`. It keeps the current port unless you set
  `HTTP_PORT`.
- **Interrupted run:** run the same command again. It picks up where the last run stopped.
- **Settings:** SMTP, `LEGACY_DEVICE_API`, `PROXY_ADDRESSES`, `GITHUB_REPO` and `GITHUB_TOKEN` carry over from earlier runs, Erase
  included. Pass one to change it; `GITHUB_TOKEN=` removes the token ([how](OPERATIONS.md#updater-supervisor)). On the
  first upgrade from v0.3.1 or older they are still kept, but that run's agent APK download can't read the old
  settings file in time: pass `GITHUB_TOKEN` and `GITHUB_REPO` once more if that download needs them.

## Changing the hosting mode

Updates, rollbacks and `setup.sh` re-runs keep the mode an install was set up with
([the three modes](../DEPLOY.md#how-phones-reach-mdmesh)); `--proxy` or `MDMESH_MODE` on a re-run doesn't change it.
To move an install, edit `.env` in the install directory, when no update or rollback is running. The database,
devices and secrets stay as they are.

**To behind your own proxy** (Docker, 0.6.0 or later: update to 0.6.0 first; the edge of an older release doesn't
know the mode):

1. Have `docker-compose.proxy.yml` in the install directory. A clone has it. A quick start set up with 0.6.0 or later
   has it too. An older one fetches it from the release it runs (`CURRENT_VERSION` in `.env`), checked against that
   release's signed manifest. The supervisor does the work, with the `minisign` and release key it already has, so the
   host needs neither `minisign` nor OpenSSL 3; the file lands in the install directory only once it checks out. From
   the install directory:

   ```bash
   docker compose exec -T supervisor sh -euc '
     V=$(sed -n "s/^CURRENT_VERSION=//p" /project/.env); G=$(sed -n "s/^GITHUB_REPO=//p" /project/.env)
     G=${G:-MDMesh-app/MDMesh}; R=https://github.com/$G/releases/download/v$V; T=$(mktemp -d); trap "rm -rf $T" EXIT; cd "$T"
     curl -fsSL -o manifest.json "$R/manifest.json"; curl -fsSL -o manifest.json.minisig "$R/manifest.json.minisig"
     minisign -Vq -m manifest.json -p /app/minisign.pub
     grep -qF "\"version\": \"$V\"" manifest.json
     curl -fsSL -o proxy.yml "https://raw.githubusercontent.com/$G/v$V/docker-compose.proxy.yml"
     grep -qF "\"docker-compose.proxy.yml\": \"$(sha256sum proxy.yml | cut -d" " -f1)\"" manifest.json
     cat proxy.yml' > docker-compose.proxy.yml.new &&
     mv docker-compose.proxy.yml.new docker-compose.proxy.yml && echo "docker-compose.proxy.yml verified" ||
     { rm -f docker-compose.proxy.yml.new; echo "not verified: nothing changed" >&2; }
   ```

2. In `.env`, set (and leave `TUNNEL_TOKEN` and `ACME_EMAIL` as they are; this mode doesn't read them):

   ```bash
   COMPOSE_FILE=docker-compose.yml:docker-compose.proxy.yml
   COMPOSE_PROFILES=
   MDMESH_BIND=127.0.0.1:8080
   TRUSTED_PROXIES="host"
   MDMESH_ORIGIN_TLS=0
   ```

   `MDMESH_BIND` and `TRUSTED_PROXIES` as in [DEPLOY, Behind your own proxy](../DEPLOY.md#3-behind-your-own-proxy).
3. Leaving the tunnel: `docker compose --profile cloudflare rm -sf cloudflared`.
4. `docker compose up -d`. It recreates the edge (now on `MDMESH_BIND`, no longer on 80/443) and the supervisor
   (whose updates and rollbacks then use the new `COMPOSE_FILE`). From source, run `./setup.sh` instead; it reads the
   mode from `.env`.
5. Point your proxy at `MDMESH_BIND` ([ready configurations](../DEPLOY.md#3-behind-your-own-proxy)).

**From behind your own proxy** back to your own domain: `COMPOSE_FILE=docker-compose.yml:docker-compose.domain.yml`
and `SITE_ADDRESS=<your hostname>`; to the tunnel: `COMPOSE_FILE=docker-compose.yml`,
`COMPOSE_PROFILES=cloudflare`, `SITE_ADDRESS=:80` and `TUNNEL_TOKEN`. In both, set `TRUSTED_PROXIES=` (empty: devices
reach the edge directly, so no peer is believed; `host` means nothing outside the proxy mode, and the edge ignores it,
saying so in `docker compose logs caddy`). `MDMESH_BIND` and `MDMESH_ORIGIN_TLS` can stay; nothing else reads them.
Then step 4.

A release before 0.6.0 doesn't know the proxy mode, so while an install runs behind your own proxy the updater (and
`./setup.sh --update`) refuses an update or a Roll back to one, saying so, and changes nothing. Move the install back
to its old mode first. The updater refuses one too while `TRUSTED_PROXIES` still says `host`: set `TRUSTED_PROXIES=`.

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
  | awk -v q="'" '!set && $0 == "SET lock_timeout = 0;" { $0 = "SET lock_timeout = " q "60s" q ";"; set = 1 }
      $0 == "-- PostgreSQL database dump complete" { complete = 1 }
      { print }
      END { if (complete) print "COMMIT;\n\\echo Restored."
            else print "DO $$ BEGIN RAISE EXCEPTION " q "the backup is incomplete" q "; END $$;" }' \
  | docker compose exec -T postgres psql -X -q -v ON_ERROR_STOP=1 -o /dev/null -U mdmesh -d mdmesh \
      -c BEGIN -c "SET lock_timeout = '60s'" \
      -c "SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE datname = current_database() AND pid <> pg_backend_pid()" \
      -c "DROP SCHEMA public CASCADE; CREATE SCHEMA public; GRANT ALL ON SCHEMA public TO PUBLIC" \
      -f -
```

Then:

```bash
# only if it printed Restored.: copy SERVER_VERSION, WEB_VERSION and CURRENT_VERSION from the .env snapshot above into .env
docker compose up -d --no-deps server caddy
```

The restore ends other sessions, empties the database and loads the dump, all in one transaction, and prints
`Restored.` when it committed. Anything else (an error, or no `Restored.`) means nothing changed: the database is as it
was. It gives up after 60 seconds without a lock. Keep the `awk`: it sends the `COMMIT` only after a backup that ends
like a complete `pg_dump` (one cut short fails with "the backup is incomplete"), and it rewrites the dump's own
`SET lock_timeout = 0;`, which would remove that limit. Keep the `DROP SCHEMA` line too: the dump's own DROPs only know
the tables from before the update, and one the update added that refers to them makes the restore fail.

## Agent compatibility

Older agents keep working across server updates ([ADR 0009](adr/0009-agent-v1-contract-stability.md)). Updating the
server doesn't update agents; roll them out from **Settings → Agent rollout** (shown once an agent APK is
available).

Agent 0.4.0 speaks protocol 1.5: it applies a configuration's connectivity mode, which older agents ignore. It also
works with a 0.3.9 server.

Agent 0.5.0 speaks protocol 1.11: it applies a configuration's Location services setting (1.6) and app permissions
(1.7), resumes an enrollment whose answer was lost (1.9: it sends an enroll attempt id, and gets back the device
it already created instead of a used-up code), draws a kiosk background image with its fit and focal point (1.10),
and takes **Clear PIN lockout** (1.11). Older agents report Location services unsupported, can't apply app
permissions (their devices show as out of sync while a configuration has a rule), ignore the background image, and
don't get Clear PIN lockout (the console disables it for a device on an older agent, and the server holds it from
older agents). It also works with a 0.4.0 server, which sends none of these and ignores the attempt id: an
enrollment there works as before, it just can't be resumed.

Downgrading the agent below 0.5.1 and upgrading again can bring back agent state removed meanwhile, such as a kiosk
exited or credentials cleared while the older agent ran.

## Version notes

### Unreleased

- **The database update deletes the history of deleted devices.** Before, deleting a device left its commands, state,
  events and locations behind, and a device added later with the same number showed them. On its first start the
  server deletes those rows (no screen showed them), then links each history table to its device, so deleting a device
  deletes its history from then on. Devices that still exist keep everything. To keep the old rows, back up the
  database first.
- **The first start reads each history table twice** (once to delete the old rows, once to check the links).
  Measured on a 6-core test server with 5 million events and 1 million locations (1.3 GB, 9% of it from deleted
  devices): 2.4 seconds, on top of the usual start. Expect longer on slow disks. The server answers once it is done,
  and agents retry their check-ins meanwhile.
- **Quick start rolls an update back when the new server isn't healthy within 180 seconds.** With a very large
  history (tens of GB) or very slow storage, this first start could take longer, and the update would be rolled back
  each time. Take a backup first. To give it longer, add `HEALTH_TIMEOUT=900` (seconds, at least 30) to `.env` in
  the install directory. The 0.5.x supervisor doesn't read it, so get this release's supervisor first: set
  `SUPERVISOR_VERSION=latest` in `.env`, then, when no update or rollback is running, run
  `docker compose pull supervisor && docker compose up -d supervisor`, then apply the update from **Settings →
  Updates**. That update pins `SUPERVISOR_VERSION` to the release's supervisor by digest again. With automatic
  updates on (`AUTO_UPDATE=1`), do this as soon as you can: until then the old supervisor may apply the release
  itself, with its 180-second limit.
- **The unused two-factor setting is removed.** MDMesh has no second sign-in step, so an organization whose settings
  had two-factor sign-in on (a database carried over from the upstream project, or edited by hand) couldn't sign in at all.
  On its first start the server drops the setting. Those accounts then sign in with their password alone (no
  authenticator code is asked for), and get API tokens again. Two-factor sign-in for each user is planned for a later
  release. **Roll back**
  restores the old database, setting included, so such an organization can't sign in again until it updates again.
- **Going back to 0.5.1 without a restore** (`--allow-downgrade`, or a native install's WAR swapped back) works: 0.5.1
  starts on the new database unchanged. It keeps the links, so in 0.5.1 too, deleting a device deletes its history and
  renaming one carries it. A check-in or command that races a delete gets a server error in 0.5.1 (this release
  answers it as for an unknown device). The two-factor setting stays off: 0.5.1 reads it as off once it is gone.
- **The server first, then its agent.** New devices and rollouts get the agent of the release the server runs, never
  a newer one. Update the server, then roll out its agent from **Settings → Agent rollout**. After a rollback, a
  rollout of the newer agent stops: cancel it, or update the server again. A server whose release isn't published
  (a development build), or has no agent in its manifest, gets the latest release's agent.
- **Existing installs keep their hosting mode.** Cloudflare Tunnel and own-domain installs, quick start or from
  source, keep their mode and the `.env` an installer wrote through the update. Answers files and `MDMESH_MODE=1` or
  `2` mean what they meant before; `3` (or `proxy`) is the new mode. Native is unchanged.
- **From source, with `COMPOSE_FILE` or `COMPOSE_PROFILES` edited by hand**: `./setup.sh` and `--update` now start the
  files `COMPOSE_FILE` names (without one, as before). 0.5.x, outside the tunnel, always started `docker-compose.yml` and
  `docker-compose.domain.yml`, whatever it said. So on your own domain, with a list that leaves
  `docker-compose.domain.yml` out and publishes neither port itself, the edge would lose ports 80 and 443. Both commands
  then stop, before any container is touched, and show the line for `.env`: your list with the domain file added, for
  example

  ```bash
  COMPOSE_FILE='docker-compose.yml:docker-compose.override.yml:docker-compose.domain.yml'
  ```

  Set it and run `./setup.sh` (or `./setup.sh --update`) again, before any update or rollback in the console: the
  supervisor keeps the old list until that run recreates it, and would close the ports. At the stop the clone is
  already on the new release, and the containers still run the old one. If the edge should give up its ports,
  `docker compose rm -sf caddy` and run it again (phones can't reach MDMesh until that run ends). With such a
  list, a run that ends with neither port found published says so in its last lines. With `COMPOSE_PROFILES` listing
  `cloudflare` and others (`cloudflare,backup`), 0.5.x published 80/443 as well as running the tunnel; now it is a
  tunnel install, and they close unless the files `COMPOSE_FILE` lists publish them.
- **New: behind your own proxy** (Docker). For a machine where nginx, Apache, Caddy or Traefik already does HTTPS
  ([DEPLOY](../DEPLOY.md#3-behind-your-own-proxy)). To move an existing install to it, update to 0.6.0 first, then
  [change the hosting mode](#changing-the-hosting-mode).
- **New: your own edge rules** in `site.d/*.caddy` ([DEPLOY](../DEPLOY.md#your-own-edge-rules)). From source, the
  first `./setup.sh` run after the update makes `site.d` and adds `MDMESH_SITE_DIR` to `.env`. A quick start keeps
  the compose file it was set up with, so on one from before 0.6.0 add the directory by hand, from the install
  directory: under `caddy:` → `volumes:` in `docker-compose.yml`, indented like the lines there, add
  `- ${MDMESH_SITE_DIR:-caddy_site}:/etc/caddy/site.d:ro`, and under the top-level `volumes:` add `caddy_site:`; then

  ```bash
  mkdir -p site.d && chmod 755 site.d && echo "MDMESH_SITE_DIR=$PWD/site.d" >> .env && docker compose up -d
  ```

  (`up -d` recreates the edge with the directory mounted; updates read both files, so they keep it.)
- **API: `applications` of a configuration.** `GET /configurations/{id}` and the lists now answer `null` where they
  answered an always empty `[]` (`GET /configurations/applications/{id}` has the apps, as before). In a
  `PUT /configurations` that updates one, `applications` left out or `null` keeps the stored apps; before, leaving it
  out removed them all and `null` was refused. Send `[]` to remove them.
- **A rollback below 0.6.0** of an install you moved to the proxy mode is refused by the updater: move it back first
  ([how](#changing-the-hosting-mode)).
- **PostgreSQL and cloudflared run exact images.** Both compose files name them by tag and digest:
  `postgres:14.25-trixie` (the same PostgreSQL 14 and Debian release that `postgres:14` is) and `cloudflared`
  2026.11.2. From source, `./setup.sh --update` pulls them and restarts the database for a few seconds; the data is
  not touched. A quick start keeps the compose file it was set up with: one from before 0.6.0 keeps `postgres:14` and
  `cloudflared:latest`, and one from 0.6.0 on keeps its pinned cloudflared until you change that line. Cloudflare
  supports cloudflared releases up to a year old, so at least once a year copy the `cloudflared` and `postgres`
  `image:` lines from the release's `docker-compose.release.yml` (on GitHub, at the release's tag) into
  `docker-compose.yml`, then run `docker compose up -d`. Native installs use neither image.

### v0.6.0 for forks

For forks that publish their own releases with `.github/workflows/release.yml` (RELEASING.md). Neither of these
tags or publishes anything, but either may leave untagged images in your GHCR packages:

- **Record your release certificate.** `release/agent-signature-checksum` holds MDMesh's release certificate, and
  the release stops with an error on a signed APK with another one. Put your own there in a PR to your main branch before you tag:
  `release/apk-checksum.sh` prints it for an APK signed with your key, and it is `components.apk.signatureChecksum`
  in your earlier releases' signed manifests.
- **Arm64 runners.** The images are built for linux/amd64 and linux/arm64, each on its own runner; Arm64 on
  `ubuntu-24.04-arm`, which GitHub provides free for public repositories only. Without one, the release doesn't fail:
  its Arm64 `images` jobs wait for a runner that never comes, and GitHub cancels them after 24 hours. A private fork
  needs a paid Arm runner under that label, or drops Arm64: remove `arm64` from the `images` job's `matrix.arch` and
  `linux/arm64` from the release job's `PLATFORMS`, in the same commit. Its releases then serve x86_64 only, and its
  quick start and updater say so on Arm64.

For forks with their own server code: `Settings.twoFactor`, `User.twoFactor`, `UnsecureDAO.isTwoFactorRequired` and
`AuthFilter.twoFactorNeeded` are gone, so code that used them no longer compiles. There is no replacement until
two-factor sign-in is built.

`Configuration.getApplications()` is null, not an empty list, on a configuration read from the database or saved
without the list. `ConfigurationDAO` now drops the permission rules for apps a configuration doesn't install, whoever
saves it.

### v0.6.0 for forks that change the console

New text in core console files should be a react-intl message; `npm test` fails on most text that isn't
(`web/src/i18n/literals.test.ts` names the file and lists its strings). That includes text you added before merging
this release: make it a message ([web/README.md](../web/README.md#translatable-text)), or raise that file's count in
`web/src/i18n/literals-baseline.json`. If you removed core text, it fails with "fewer literals": run
`npm run i18n:baseline` in `web/`. Extensions in `web/src/extensions/` are not checked.

`Configuration.applications` is typed `ConfigApp[] | null`: the server answers null where it answered an empty list
(read the apps with `getConfigurationApps`). Code that checks it only against `undefined` must also handle `null`.

A test that ends with a `stubApi` call still unanswered now fails, and `stubApi`'s answers arrive a moment later: wait
with `findBy…` or `settled()` (in `web/src/test/console.tsx`), not `act(async () => {})`.

### v0.5.1

- **Account changes take your own password.** Scripts and API clients that create or delete users or customers,
  change another account's password or email, change a customer's email (`PUT /rest/private/customers`), or call
  `PUT /rest/private/users/superadmin/password` must now send `oldPassword`: the MD5 (hex) of the caller's own
  password, as the console does. `DELETE /rest/private/users/other/{id}`
  and `DELETE /rest/private/customers/{id}` now take a JSON body, `{"oldPassword": "..."}`. A wrong one counts as a
  failed sign-in for the caller's account, toward the lockout ([Sign-in limits](OPERATIONS.md#sign-in-limits)), so stop
  at the first `error.password.wrong`. A session signed in as another account can't make these changes at all.
- **Admins limited to some device groups or configurations** can't give anyone access beyond their own, and can't
  change, delete or sign in as an account that reaches more than they do.
- **New accounts start with no access** in the console: choose their device groups and configurations.
- **Everyone signs in to the console again once** after the update: sessions opened by an older release (which native
  installs keep across a restart) end at their first request. Scripts and API clients using a JWT are not affected.
- **A session signed in as another account** ends as soon as the admin who opened it could no longer open it: their
  account is deleted, demoted or its password changed, or the account they signed in as now reaches more than they do.
  From such a session, you can't sign in as yet another account. The audit log records the admin for what is done in
  it (`impersonatedBy`, in the database and the audit API).
- **Audit records hold no secrets.** A configuration or device change records only the fields that hold none, and
  names the rest (`"wifiPassword": "<set>"`, `"applicationSettings": "<3 items>"`). Every other record has its
  passwords, tokens, PINs and signed-URL parameters masked, and a body that isn't valid JSON, or is over 1 MB, is not
  recorded.
- **Old audit records are scrubbed in the background** the same way, starting 30 seconds after the server starts: the
  server answers (and the update's health check passes) while it runs, and after a restart it goes on where it
  stopped. On a test machine, 1 million records took about 2 minutes of work, so about 4 minutes in the background (it
  works 3 seconds in every 5). The server logs "Audit records from before this release scrubbed" when it is done.
  - Each record keeps its method, URI and User-Agent. Its body is stored again as a new record's would be: masked, or,
    for a configuration or device change, only the fields that hold no secret. A body that isn't JSON, or is over 1 MB,
    and the body of a request whose record no longer keeps one, is replaced with a note.
  - Most old records are rewritten, so allow free disk space of about the size of `plugin_audit_log` until autovacuum
    reclaims the old row versions (`VACUUM plugin_audit_log` does it at once).
  - With several servers on one database, stop all of them before the update, then start them on 0.5.1: a server
    still on the old release keeps writing unmasked records.
  - If you go back to 0.5.0 without restoring its backup (`--allow-downgrade`, or a native install's WAR swapped back),
    0.5.0 writes unmasked records again, and the scrub, once done, doesn't look at them. With the server stopped
    (`docker compose stop server`, native: `systemctl stop mdmesh-server`), whether still 0.5.0 or already 0.5.1,
    run this, then start 0.5.1, so the scrub takes them in:

    ```bash
    # Docker, in the install directory
    docker compose exec -T postgres psql -U mdmesh -d mdmesh -c "UPDATE plugin_audit_scrub SET upToId = (SELECT COALESCE(MAX(id), 0) FROM plugin_audit_log) WHERE id = 1;"
    # Native
    sudo -u postgres psql -d mdmesh -c "UPDATE plugin_audit_scrub SET upToId = (SELECT COALESCE(MAX(id), 0) FROM plugin_audit_log) WHERE id = 1;"
    ```

    If you changed `DB_USER` or `DB_NAME`, use your values in place of `mdmesh`.
- **Copies made before the update still hold those secrets:** the pre-update database dump the update took
  (`/backups/<time>.sql` in the `backups` volume on Docker, `/opt/mdmesh/backups/` on native installs), older backups,
  and the server logs (`docker compose logs server`, `journalctl -u mdmesh-server`). Change the provisioning Wi-Fi
  password and any secrets kept in managed app settings, then delete the old dumps once you no longer need them for a
  rollback, and prune the old logs. The access logs of Tomcat and of a reverse proxy also record the enrollment QR's
  key, which is part of its URL (`/rest/public/qr/<key>`) and fetches the QR with the Wi-Fi password in it: prune
  those too.
- **With `LEGACY_DEVICE_API=1`, keep legacy launcher devices and MDMesh agent devices in separate
  configurations.** The launcher's routes no longer serve a configuration that an agent device uses, or that an
  agent enrollment code can still enroll devices into, nor create launcher devices in it: the launcher gets the error
  `error.configuration.agent.managed`, and the server log says which configuration. A code enrolls into the
  configuration it names or, if it names none, into the default configuration for new devices (**Settings**), which
  then counts too. A configuration named by its QR code key counts the same way, and the server no longer makes its
  legacy launcher QR code. The routes answer requests for an agent device with the launcher's device-not-found error. To fix
  a launcher device that gets the error, move it to a configuration no agent device or enrollment code uses. With
  `LEGACY_DEVICE_API` off (the default), nothing changes.
- With `LEGACY_DEVICE_API=1`, the launcher's sync now works for a configuration without a kiosk exit PIN. Before, it
  failed with a server error.
- **CVE-2026-114938 (OpenJDK).** The Base64 code of JDK 17.0.6 to 17.0.21 is affected on x86_64 CPUs with AVX-512.
  0.5.1 runs the server with `-XX:-UseBASE64Intrinsics`, which turns that code off. To protect a server before you
  update:
  - **Docker (quick start and from source):** add `SERVER_JAVA_OPTS=-XX:-UseBASE64Intrinsics` to `.env` (if the line
    is already there, add the flag to its value, quoted), then run `docker compose up -d server` to recreate the
    container. A quick-start install made before v0.3.2 also needs the `JAVA_OPTS` line in its compose file
    ([OPERATIONS](OPERATIONS.md#temporary-debug-log)).
  - **Native:** `sudo ./setup.sh --update --native`. It sets the flag, and upgrades a JDK 17 from your distribution's
    packages when apt has a newer one. Until you can update, add the flag in a drop-in, then restart. If you already
    set `JAVA_OPTS` there, add the flag to that line, and quote the whole setting when it holds several flags
    (unquoted, systemd keeps only the first):

    ```bash
    sudo systemctl edit mdmesh-server
    # in the editor, under [Service]:
    #   Environment="JAVA_OPTS=-XX:-UseBASE64Intrinsics"
    # or, with flags you already had:
    #   Environment="JAVA_OPTS=-Xmx1g -XX:-UseBASE64Intrinsics"
    sudo systemctl restart mdmesh-server
    ```

  On Docker, going back to a release before 0.5.1 (`--allow-downgrade`, or a rollback) drops the flag again, so keep
  the `.env` line if you might.

  To check a running server, look for `= false` and `{command line}`:

  ```bash
  # Docker, in the install directory (before v0.2.7 the server ran as root: leave out -u mdmesh)
  docker compose exec -u mdmesh server jcmd org.apache.catalina.startup.Bootstrap VM.flags -all | grep UseBASE64Intrinsics
  # Native, as root (or with sudo -u mdmesh in place of runuser -u mdmesh --)
  runuser -u mdmesh -- "$(systemctl show -p Environment --value mdmesh-server | tr ' ' '\n' | sed -n 's/^JAVA_HOME=//p')/bin/jcmd" \
    org.apache.catalina.startup.Bootstrap VM.flags -all | grep UseBASE64Intrinsics
  ```

### v0.5.0

- **Quick start: update the supervisor before you apply 0.5.0.** In the install directory, run

  ```bash
  docker compose pull supervisor && docker compose up -d supervisor
  ```

  when no update or rollback is running (Settings → Updates, or `/recovery`), then apply 0.5.0 from **Settings →
  Updates** (**Update server and console**). The quick start set `SUPERVISOR_VERSION=latest`, so this starts the 0.5.0 supervisor (if you set a version in `.env` yourself, change it
  to `0.5.0` first). It works with your 0.4.x server and console as before, and it is the one that then applies 0.5.0,
  pinned by digest, and rolls it back if needed: by itself if 0.5.0 fails its health check, or from **Roll back** on
  `/recovery`. A supervisor from before 0.5.0 can't roll 0.5.0 back (see *Going back to 0.4.0, if an old supervisor
  applied 0.5.0* below). With automatic updates on, run the command as soon as you can: until then the old supervisor
  may apply 0.5.0 itself.
- **From source with a registry `IMAGE_OWNER`: don't apply 0.5.0 from the console.** Your supervisor is tied to
  your checkout, so the command above keeps the old one. Move the clone to 0.5.0 instead (below), which updates the
  supervisor and the server together. Turn automatic updates off until you have.
- **The database update is automatic.** On start, the server adds `devices.wipedAt` and
  `devices.agentEnrollAttemptHash`; `configurations.camera`, `configurations.locationServices` and the kiosk background
  image columns (`backgroundImageFileId`, `backgroundImageSha256`, `backgroundImageFit`, `backgroundImageFocalX`,
  `backgroundImageFocalY`); `device_state.kioskGeneration` and `device_state.kioskReturnGeneration`;
  `applicationVersions.requestedPermissions`; `maxUses`, `useCount` and `revokedAt` on enrollment tokens; the
  `configurationAppPermissions` and `agentEnrollmentKey` tables; and three indexes (on
  `devices.agentEnrollAttemptHash`, on each customer's devices by enrollment time, and on
  `configurations.backgroundImageFileId`). It also widens `users.password` to 100 characters, for bcrypt. Every new
  column is empty for existing configurations, so no device re-applies its configuration until its agent is updated.
  Devices wiped before the update aren't marked Wiped.
- **PostgreSQL older than 11: existing enrollment codes stop working.** The update hashes existing codes with a
  function PostgreSQL 11 added; on an older server it marks them used instead. Make a new code on the Enroll page.
- **Native on PostgreSQL older than 15: there is no way back to 0.4.0.** Native has no one-click rollback, and the
  [restore by hand](OPERATIONS.md#backups-and-restore) needs PostgreSQL 15 or newer. If you may need to go back, move
  to PostgreSQL 15 or newer before you update.
- **From source and native: move the clone to 0.5.0 once.** 0.4.x has no `./setup.sh --update`. Run
  `git fetch --tags && git checkout v0.5.0 && ./setup.sh --update` (native:
  `git fetch --tags && git checkout v0.5.0 && sudo ./setup.sh --update --native`). It works on a clone of `main` and on one checked
  out at a release tag (where `git pull` fails). The checkout leaves the clone on the v0.5.0 tag, which is where
  `--update` starts; `--update` then checks the release's signature and runs its setup.sh (or moves on to a newer
  release, if there is one). Git refuses the checkout if it would overwrite local changes, and `--update` stops on any
  it would carry along. Then use `./setup.sh --update` (native: `sudo ./setup.sh --update --native`) for every later
  release. Don't run `--update` on a 0.4.x checkout: 0.4.x's setup.sh refuses it as an unknown option, and with
  `--native` it ignores it and installs 0.4.x again.
- **Update the supervisor once, or updates stay unpinned.** Updates now run the images the signed release names, by
  digest ([how](#quick-start-installs)). The supervisor you run does that, and server updates never replace it, so one
  from before 0.5.0 applies 0.5.0 by tag and goes on applying every later release by tag, without a message.
  - **Quick start:** the command in the first point, before the update. If an old supervisor already applied 0.5.0
    and you keep 0.5.0, run `docker compose pull && docker compose up -d` in the install directory instead.
  - **From source with a registry `IMAGE_OWNER`:** the move to 0.5.0 above, even if you already applied 0.5.0 from
    the console. setup.sh builds the supervisor and ties `SUPERVISOR_VERSION` to the checkout's release, so
    `docker compose pull` alone keeps the old one.
  - **Nothing to do:** a new 0.5.0 quick start is pinned from the start, and from-source installs with
    `IMAGE_OWNER=local` and native installs don't update from release images.
- **New agent: 0.5.0.** Once the supervisor has downloaded it, **Settings → Agent rollout** offers it: pick a few
  canary devices, then promote it to everyone else. Most of this release's device features need it (see
  [Agent compatibility](#agent-compatibility)); devices on older agents keep working. A device that takes it applies
  its configuration once more, as after every agent update.
- **Live connection can take up to 5 minutes to come back after an outage (agent 0.5.0).** When the server has been
  unreachable for a while, a device's live connection can take up to 5 minutes to come back (it was up to 1 minute), so
  Sync now may say the device isn't connected a little longer. The connection comes back at once when the device's
  network returns or its next check-in gets through. Check-ins are not affected.
- **Native installer and answers.** The installer stops on a command-line option it doesn't take, instead of
  ignoring it. `REPLACE_DATA` must be `yes` or `no`, and `ASSUME_YES` `1` or `0`; empty means not set (the default).
  Any other value stops it before it changes anything; before 0.5.0, another `REPLACE_DATA` meant Keep and another
  `ASSUME_YES` meant ask. An answers file (`MDMESH_ANSWERS`, new in 0.5.0) must be owned by you or root and give no
  access to group or others (for example `chmod 600`). Check scripts that run the installer unattended.
- **Forks.** Once a 0.5.0 supervisor runs, it refuses a release whose manifest has no digests, and one that pins them
  under another owner than `IMAGE_OWNER`, with that reason, and nothing changes. A fork that publishes its own releases
  must use the current release workflow ([RELEASING.md](../RELEASING.md)). An install that follows upstream releases
  with its own `IMAGE_OWNER` must set `GITHUB_REPO` to the repository that publishes its images, or `IMAGE_OWNER` to
  `mdmesh-app`.
- **Going back to 0.4.0, if an old supervisor applied 0.5.0.** A supervisor from before 0.5.0 can't roll 0.5.0
  back: its restore stops at `cannot drop constraint configurations_pr_key ... because other objects depend on it` (0.5.0 adds a
  table that refers to configurations). Nothing changes: the database stays as it was, and `server` is left stopped.
  The 0.5.0 supervisor restores into an emptied schema instead. So, on a quick start, run
  `docker compose pull supervisor && docker compose up -d supervisor`, then **Roll back** from `/recovery`. If a
  rollback, from **Roll back** or after a failed update, already stopped there, the same command and **Roll back**
  again finish it with the same backup. To stay on 0.5.0 instead, run `docker compose up -d server`. By hand:
  [Restoring by hand](#restoring-by-hand).
- **Going back to 0.4.0: enrollment codes.** The update stores enrollment codes hashed, the existing ones included
  (on PostgreSQL older than 11 it marks them used instead, see above). A one-click rollback, and the restores in
  [Backups and restore](OPERATIONS.md#backups-and-restore), bring back the database from before the update, with its
  codes as they were. A 0.4.0 server started on the 0.5.0 database instead, without a restore (`--allow-downgrade`,
  say), finds none of them: every code made before the downgrade is refused as unknown, including one already printed
  or in a QR. Make a new code on the Enroll page. If you then update that same database to 0.5.0 again, codes made
  while 0.4.0 ran don't work either: make new ones.
- **Going back to 0.4.0: passwords.** 0.5.0 stores passwords as bcrypt; an existing password is converted at its
  user's next sign-in, and 0.4.0 can't check a converted one. A one-click rollback, and the restores in
  [Backups and restore](OPERATIONS.md#backups-and-restore), bring back the database from before the update, with every
  password as it was then (one changed after the update is back to the old one). A 0.4.0 server started on the 0.5.0
  database instead, without a restore, refuses every user who signed in or changed their password on 0.5.0, the admin
  who applied the update included. Restore the backup. If you can't, set a password 0.4.0 can check for each of those
  users, then sign in and change it (the 0.4.0 console has no page for setting another user's password). From the
  clone, `. install/lib/db.sh; mdm_pwhash 'NEW-PASSWORD'` prints the hash; then run
  `UPDATE users SET password = '<the hash>' WHERE login = '<login>';` in psql
  (`docker compose exec -T postgres psql -U mdmesh -d mdmesh`, native: `sudo -u postgres psql -d mdmesh`).
- **App permissions (agent 0.5.0):** a configuration can Grant or Deny an installed app's runtime permissions. Devices
  on an older agent, or not Device Owner, can't apply them: they get the rest of the configuration and show as out of
  sync while it has a rule, until their agent is updated. Configurations without rules stay in sync on every agent.
  API clients: `PUT /rest/private/configurations` takes `managedAppPermissions` (`[{applicationId, permission, state}]`,
  state `grant` or `deny`); leaving it out keeps the stored rules, and rules for apps the save doesn't install are
  dropped.
- **API changes.** `POST /rest/private/agent/v1/token` takes `maxUses` and `validHours` (left out: one device, 24 hours,
  as before); `POST /rest/private/agent/v1/token/revoke-all` stops every code;
  `GET /rest/private/agent/v1/devices/enrolled?since=<ms>` lists the devices enrolled since then. Enrollment codes are
  stored hashed, so the database no longer holds them in clear. `PUT /rest/private/roles` and
  `DELETE /rest/private/roles/{id}` now refuse the super-admin and org-admin roles (`error.role.protected`).
- **Return to kiosk on the device (agent 0.5.0):** after someone leaves a kiosk with the exit PIN, the MDMesh app
  offers **Return to kiosk**, offline too, while the device's configuration enables kiosk. A console **Exit kiosk**
  (`kiosk.exit`) leaves that option on the device; to remove it, turn kiosk off in the configuration. Older agents
  don't show it.
- **The agent is called MDMesh on devices (agent 0.5.0):** it was MDM Agent, and its icon is a white square on dark
  (was a white shield on blue). It's the same app, so it updates in place and keeps Device Owner. Update staff
  instructions that name it or show its icon.
- **Kiosk exit PINs are 6 to 12 digits:** the console and the API (`PUT /rest/private/configurations`) refuse a new or
  changed PIN that isn't, with HTTP 400. A `kiosk.enter` command whose `password` isn't is refused too (an ERROR).
  Return to configured kiosk sends no PIN: its payload has `"configuredPin": true`, and the server puts in each
  device's own configuration PIN as stored (refused, or skipped in bulk, for a device without a configuration), so it
  keeps working on configurations with an older PIN, and agents of any version get an ordinary `kiosk.enter`. A PIN saved before keeps working on devices and keeps its configuration
  saveable while it stays as it is; the console marks it **Old PIN** and `legacyKioskPin` is true in the API. Set new
  PINs before staff need them: a device on agent 0.5.0 shows a number pad for a PIN of digits.
- **Wrong exit PINs (agent 0.5.0):** 5 wrong PINs lock the exit for 1 minute, then each further 3 for 5 minutes, then
  15 minutes, then 1 hour; after that every wrong PIN locks it for another hour (agents 0.3.6 to 0.4.x: 3 wrong PINs,
  then every one, up to 1 hour). A new PIN, or **Clear PIN
  lockout** on the device page, ends a lockout. A lockout under way when the agent updates is kept.
- **Retry apply** sends the configuration again right away (`POST /rest/private/agent/v1/devices/{number}/retryConfig`,
  in the audit log), instead of making the device check in, which after a failed apply waited an hour.
- **Kiosk text colour (agent 0.5.0):** a kiosk text colour with less than 4.5:1 contrast on its background is
  replaced on the device by a readable one, so some brand pairs show dark text (white on a bright red, for example).
  The kiosk background is always drawn solid; its transparency is ignored. Check kiosk configurations whose text and
  background colours are close.

### v0.4.0

The console is new: every page was rebuilt. This part is for everyone who runs MDMesh. Forks that change the console
or add server plugins also read [v0.4.0 for forks](#v040-for-forks) below.

- **Updating.** As the [table](#upgrading-mdmesh) at the top says: quick start, **Settings → Updates**; from source,
  `./setup.sh --update`; native, `sudo ./setup.sh --update --native`. There is nothing new to set: no new
  `.env` setting or server parameter, and no supervisor update.
- **The database update is automatic.** On start, the server adds:
  - `configurations.extensions`, set to `{}` for every existing configuration;
  - `configurations.connectivityMode`, empty for every existing configuration ("—" in the editor: each device keeps
    its own mode);
  - `device_state.appsRevision`, filled for each device on a configuration with the apps that configuration installs
    now, so the update sends no app installs;
  - an index on `configurationApplications (configurationId)`.

  No device re-applies its configuration: the empty columns leave every configuration's revision as it was.
- **New agent: 0.4.0.** It shows alerts on devices enrolled by QR code, and applies a configuration's connectivity
  mode (Configuration → Network), which older agents ignore. Once the updater has downloaded it,
  **Settings → Agent rollout** offers it: pick a few canary devices, then promote it to everyone else. Only devices
  whose agent can install silently (Device Owner, which a QR-code enrollment sets up) update this way. A device that
  takes the new agent applies its configuration once more, as after every agent update; it changes nothing unless the
  configuration sets a connectivity mode.
- **Configuration apps install at check-in.** An app added to a configuration, in the editor or with Deploy's "Add to
  a configuration", now installs on its devices at their next check-in, without **Install configuration apps**, also on a
  device that was offline. A device moved to another configuration gets that configuration's apps it doesn't have. A
  failed install is tried up to three times (after 15 minutes, then an hour), then left to Install configuration apps. Apps
  a configuration had before the update are not sent again: use Install configuration apps for a device still missing one.
  It needs no new agent: every agent that can install silently (Device Owner) does it.
- **Rolling back to 0.3.9 is safe.** A one-click rollback, and the restores in
  [Backups and restore](OPERATIONS.md#backups-and-restore), bring back the database from before the update,
  without the new columns and index. A 0.3.9 server started on the 0.4.0 database works too:
  - it never reads or writes the new columns;
  - a configuration it creates gets the `{}` default and no connectivity mode;
  - it doesn't send a configuration's connectivity mode, so devices keep the mode they last applied;
  - what a fork's plugin or extension stored stays for the next 0.4.0;
  - devices already on agent 0.4.0 keep working ([ADR 0009](adr/0009-agent-v1-contract-stability.md)).

  If you then update that same database to 0.4.0 again, apps added to a configuration while 0.3.9 ran install at
  the next check-in. (A database restored from a backup has none.)
- **Pages and actions follow the role**, below.
- **Old bookmarks redirect.** The v0.3 console's `/dashboard` goes to `/`, and `/configs` to `/configurations`. The
  device page's tab is in its address now (`/devices/WH-12?tab=activity`; the tabs are `control`, `telemetry`,
  `apps`, `activity` and `location`).
- **What's new.** After the update, each browser shows 0.4.0's notes once, after sign-in, and Settings → Server opens
  them again. A browser that never opened the console before doesn't show them. A tab left open on the v0.3 console
  during the update is signed out when the server restarts: reload it and sign in, and the notes show.
- **API changes.** `POST /rest/public/passwordReset/reset` answers OK with no data. It returned the account, with the
  new password's stored hash in `newPassword`. A configuration carries a new `extensions` object, which a save that
  leaves it out keeps; the configuration lists leave it out. It also carries `connectivityMode` (`adaptive`,
  `alwaysOn` or null): a save that leaves it out clears it, like the other columns, and any other value gets `400`.

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
installs apply it from **Settings → Updates**, from-source installs run `./setup.sh --update`, and native installs
`sudo ./setup.sh --update --native`.

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
  removing a legacy launcher content app no longer turns kiosk off.

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
  changes in kiosk. Android mutes a locked device, so **Ring** is silent on it.
- **Lock hardware buttons** blocks only the power menu.
- **Legacy launcher QR routes:** `/rest/public/qr/*` returns 404 unless `LEGACY_DEVICE_API=1`; the console doesn't use
  them. The three installer-created configurations get a new QR key on first start. With `LEGACY_DEVICE_API=1`, the
  legacy launcher QR codes printed for them before stop working.
- **Kiosk PINs:** new installs give each installer-created configuration a random 8-digit exit PIN, shown in the
  configuration editor. Existing PINs are kept. The console flags any still set to the old default `12345678`;
  change it, since anyone who knows the default can leave kiosk.
- **Observer role:** loses the push and messaging send permissions and device-description editing.
- **`SECURE_ENROLLMENT=1`** still makes `/files/*` require the legacy launcher's signature, which also blocks the
  agent's APK and app downloads. With `LEGACY_DEVICE_API` off, the server now warns about it at startup. Set it to
  `0` unless you need launcher-signed downloads.

### v0.3.3

- The legacy launcher's device routes (`/rest/public/sync/*`, notifications, plugin device uploads, usage stats)
  return 404 unless `LEGACY_DEVICE_API=1`.
- App installs now carry the APK's SHA-256, and current agents refuse an install without one. Hosted APKs are hashed
  automatically. For a Library app with an external URL, add the hash with **Add checksum**, or **SHA-256 checksum**
  in the app's row, or upload the APK instead.

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
