# Dev environment

Three planes run independently; you only need the toolchain for the one you are changing.

## Prerequisites

| Plane | Needs |
|-------|-------|
| Control plane (server) | Docker + Docker Compose; the scripts also need bash, curl, python3, openssl and md5sum (GNU coreutils); the fast Java loop needs JDK 17 + Maven |
| Admin frontend (web)   | Node 22 (Vite 8 needs ≥ 20.19), npm |
| Device agent           | JDK 17, Android SDK (cmdline-tools), an AOSP emulator or a factory-reset device |

## 1. Control plane (the dev stack)

The dev stack is the production stack (`docker-compose.yml`: Postgres, server, Caddy with the console, supervisor)
plus a thin overlay, `docker-compose.dev.yml`, that publishes loopback-only ports and a debugger port. Its settings
live in `docker/dev.env`: the project name `mdmesh-dev`, dev-only passwords, `:dev` image tags and an inert
supervisor. Pass that file on **every** compose command for the dev stack. It replaces `.env`, so a production `.env`
in the same checkout is never read, and the overlay refuses to load without it.

```bash
docker compose --env-file docker/dev.env up -d --build    # build + start; the first boot runs Liquibase
scripts/dev-seed.sh                                        # first run: seed the database like the installers do
```

| What | Where |
|------|-------|
| Console | <http://localhost:8088>, login **admin / admin** (after `scripts/dev-seed.sh`) |
| API (direct to Tomcat) | <http://localhost:8080/rest/> |
| Postgres | `localhost:5432`, database, user and password `mdmesh` |
| Debugger (JDWP) | attach your IDE to `localhost:5005` |

Every port binds to `127.0.0.1`. If one is taken, export `DEV_WEB_PORT`, `DEV_API_PORT`, `DEV_PG_PORT` or
`DEV_DEBUG_PORT` before running compose (shell variables override `docker/dev.env`). If you move the console, also
export `BASE_URL=http://localhost:<port>`.

Configuration is environment only: `docker/entrypoint.sh` renders Tomcat's `ROOT.xml` from it at every start. Change a
value in `docker/dev.env` (or export it) and run the `up -d` command again.

`scripts/dev-seed.sh` seeds through `install/lib/db.sh`, the same code the installers run, then sets the admin
password through the real first-login flow: `admin`, or the value of `DEV_ADMIN_PASSWORD` if you export one. On a
database that is already seeded it only re-applies the post-seed repairs, and finishes the admin password reset if an
earlier run was interrupted before it. It refuses to run unless the stack's containers were created with
`docker-compose.dev.yml`, so it never seeds a production install.

```bash
docker compose --env-file docker/dev.env logs -f server              # server log
docker compose --env-file docker/dev.env up -d --build server        # rebuild + restart the server image
docker compose --env-file docker/dev.env down                        # stop; keeps the data
docker compose --env-file docker/dev.env down -v                     # reset: also deletes the dev database and files
```

The server logs to stdout at INFO, from `server/src/main/resources/log4j.xml` (its only log config). For DEBUG while
you work, raise a level there locally and use the fast Java loop below. Do not commit it: `LoggingConfigTest` (the
server tests, which T0 runs) fails if the root or `AuditLogger` level is not INFO. A dev stack created before this
change keeps the old external config across WAR reloads: recreate the server once (`docker compose --env-file
docker/dev.env up -d --force-recreate server`).

`down -v` deletes the server's data volume too (`/opt/mdmesh`: uploaded files and the JWT signing key in
`jwt.secret`), so the next start generates a new key. With `--env-file docker/dev.env`, `down -v` deletes the
`mdmesh-dev` project's volumes, unless your shell exports `COMPOSE_PROJECT_NAME`: shell variables beat the env file.
Before a `down -v`, check which project it will hit: `docker compose --env-file docker/dev.env config | head -1` must
print `name: mdmesh-dev`.

Do not run the dev stack in a checkout that also runs a real install (`./setup.sh` writes a `.env` there). The
`MDMESH_DEV` guard in `docker-compose.dev.yml` stops `up` and `config` without `--env-file docker/dev.env`, but not
`down -v`, `stop`, `rm`, `exec`, `logs` or `ps`. Run without it there, those act on the production project, even when
they name the dev file, because compose falls back to the project name in `.env`.

**Fast Java loop.** Rebuilding the server image runs the whole Maven build inside Docker (over a minute). For quicker
turns, build on the host (JDK 17) and copy the WAR into the running container; Tomcat reloads it within about 20 s:

```bash
cp -n server/build.properties.example server/build.properties     # once
mvn -pl server -am package -DskipTests
docker compose --env-file docker/dev.env cp server/target/launcher.war server:/usr/local/tomcat/webapps/ROOT.war
```

The copied WAR lasts until the container is recreated (`down`, or `up -d --build` after a source change); then the
image's own build is back.

API reference: the server publishes its Swagger 2.0 description at `/rest/swagger.json` (no UI is bundled). To browse it,
`curl --create-dirs -o /tmp/api/swagger.json http://localhost:8080/rest/swagger.json` then
`docker run --rm -p 8081:8080 -e SWAGGER_JSON=/api/swagger.json -v /tmp/api:/api swaggerapi/swagger-ui` → <http://localhost:8081>
(or open the file in editor.swagger.io).

## 2. Admin frontend (React)

```bash
cd web
npm install
npm run dev          # http://localhost:5173 with hot reload, proxied to the dev stack
```

The Vite dev server proxies `/rest`, `/files`, `/agent/ws`, `/update` and `/recovery` to the dev stack's Caddy
(<http://localhost:8088>), which routes them the way production does. Point it elsewhere with
`VITE_DEV_PROXY_TARGET` (see `web/README.md`). It listens on every interface, so you can open it from a phone on your
LAN; run it only on a network you trust.

## 3. Device agent (Kotlin)

```bash
cd agent-android
./gradlew :app:assembleDebug
```

To enroll an emulator or test device as Device Owner over ADB, follow "ADB Device-Owner dev enrollment loop" in
`agent-android/README.md` (debug builds are `com.mdmesh.agent.debug`). Without a QR code, the agent uses the
`MDM_BASE_URL` its build type bakes in (`agent-android/app/build.gradle.kts`). The agent has no cleartext-HTTP
exception, so a device needs a server it can reach over HTTPS (see `DEPLOY.md`). The loopback dev stack serves the
console, the API and the scripted agent loop below.

## End-to-end agent loop (Agent v1)

`scripts/agent-v1-e2e.sh` drives the whole protocol against a running server with `curl` playing the device: enroll →
mint token → queue command → authenticated, capability-gated check-in → ack, plus the command and rollout scenarios
built on them. On the dev stack, after `scripts/dev-seed.sh`:

```bash
scripts/agent-v1-e2e.sh http://localhost:8080      # expect "RESULT: PASS=<n> FAIL=0"
```

It signs in as `admin` with `ADMIN_PW` (default `admin`, what `dev-seed.sh` sets). If you seeded with
`DEV_ADMIN_PASSWORD`, pass the same value as `ADMIN_PW`. For another server, seed it the way `scripts/dev-seed.sh`
does and pass `ADMIN_PW=<password>`.

CI runs the same loop as tier T1 (`.github/workflows/t1-e2e.yml`): the dev stack's `postgres` and `server` services,
`scripts/dev-seed.sh` with a random admin password, then `scripts/agent-v1-e2e.sh`. Each run gets its own compose
project, server image tag and ephemeral loopback ports, so it never collides with your dev stack or another run. It
runs on pull requests and pushes to `main` that touch the server modules, `proto/`, `install/`, the server image,
the dev stack files or the suite (the `e2e` filter in the workflow has the exact list), and on any change under
`.github/`, to a compose file, a lockfile or a file named `Dockerfile*`. A failed run uploads the compose and Tomcat
logs as the `t1-e2e-logs` artifact.

The remaining step that needs a provisioned box is the **real on-device run**: build the agent, enroll an AOSP
emulator as Device Owner via ADB, and watch a `policy.apply` apply on the device.

## T2 native rig (native install + upgrade)

`tests/native/run.sh` installs and upgrades MDMesh **natively** (DEPLOY.md Option C, no Docker for MDMesh itself) in a
throwaway container that looks like a fresh VPS: systemd as PID 1, a passwordless-sudo user, and only what a stock
cloud image has plus `git`. It clones the repo there from a git bundle (origin set to the real repo, so the release and
agent APK lookups are real) and runs the documented command exactly as a user would:
`sudo BASE_URL=… HTTP_PORT=… ./setup.sh --native -y`; an upgrade moves the checkout to the new commit (what
`git pull` does) and runs the same command again.

```bash
tests/native/run.sh debian-12 fresh       # distros: debian-12, debian-13, ubuntu-24.04
tests/native/run.sh debian-12 upgrade     # scenarios: fresh, upgrade, killed
T2_KILL_POINTS=mid-deploy,migrating tests/native/run.sh debian-12 killed
```

| Scenario | What it proves |
|----------|----------------|
| `fresh` | The change installs on a clean host with the documented command, and the admin's first sign-in works. |
| `upgrade` | The last release (`T2_FROM_REF`, default `v0.3.1`) installed, then the change over it: the upgrade succeeds **and keeps what it must** — a device enrolled through `/agent/v1` before the upgrade checks in afterwards with its original secret, `hash.secret` and `jwt.secretkey` are unchanged, the configuration/device/user counts are unchanged and admin still signs in with its password. |
| `killed` | The same upgrade, SIGKILLed (the installer's whole process group) at each kill point, then re-run once: it must converge and pass every check, continuity included. The kill points are named moments of the installer's run (`bash tests/native/lib/guest.sh kill-points`): right after the role password is rotated, mid Maven build, mid console build, while systemd stops the old server, mid deploy, after the deploy, mid pre-upgrade dump, while the supervisor is rewritten, and while the new server migrates the database. A kill that would land later than its point fails the run as `NOT-REACHED`, so a point never silently turns into another. |

After every install or upgrade the same check step runs: `mdmesh-server` (and `mdmesh-supervisor`, when that version
installs it) is active, the server's `/opt/mdmesh/initialized.txt` says `OK`, `/rest/public/name` answers 200, admin
signs in, and **that version's own** `scripts/agent-v1-e2e.sh` passes (`FAIL=0`) against the container.

Always name the release to upgrade from: this clone also carries upstream Headwind tags (`v5.x`), so "the newest tag"
is not the last MDMesh release. CI passes the latest GitHub release.

On a 4-core box `fresh` and `upgrade` take 3–5 minutes each and `killed` about 20 (one minute or two per kill point).
A run needs a few GB of free space under the Docker root; it refuses to start below `T2_MIN_FREE_GB` (default 8). `T2_CACHE=1` shares the
Maven and npm download caches between runs in the named volumes `mdmesh-t2-cache-m2` and `mdmesh-t2-cache-npm`
(remove them with `docker volume rm` when you are done). Logs, the installer output of every run, the e2e logs and,
on a failure, the guest's install log, journals and Tomcat logs land in `T2_OUT` (default `/tmp/mdmesh-t2/<run>/`), with
a `results.tsv`. The other settings are in the header of `run.sh`.

**Safety.** The container is privileged (systemd needs that on cgroup v2) but has its own network namespace and a
private cgroup namespace, and no host path is mounted. The image masks every unit that would act on the host kernel
(sysctl, modules, binfmt, pstore, clock, TRIM). Everything a run creates is named `mdmesh-t2-<distro>-<scenario>-<random>`
and removed on exit (images by their exact tag); the base images it pulls (`debian:12`, …) stay.

**Workarounds for undocumented prerequisites.** The image adds something beyond the stock baseline only when the
documented install cannot work without it, each one a finding to fix in the installer or DEPLOY.md
(`T2_STRICT=1` builds without them, to reproduce the failure):
- Debian 12 and Ubuntu 24.04 ship Node 18, and the console build (Vite 8) needs Node ≥ 20.19: the installer's
  `apt-get install nodejs npm` gets 18 and `npm run build` fails. The image adds Node 22 under `/usr/local`.
- Debian 13 has no `openjdk-17-jdk`; DEPLOY.md says a JDK 17 under `/opt` is used but not how to get one. The image
  adds Temurin 17 under `/opt/jdk-17`.

CI runs the rig as tier T2 (`.github/workflows/t2-native.yml`): the 3 distros × 3 scenarios on pull requests that touch
`install/`, `setup.sh`, `quickstart.sh` or the rig, nightly, and by hand (one distro, scenario or from-ref). A server
change (a Liquibase changeset, a dependency) can break a native upgrade without touching the installer, so the whole
matrix also runs by hand on `main` before every release (see [RELEASING.md](../RELEASING.md)).

**Extending it** (e.g. for a JDK or Tomcat change): a new distro is a case in `distro_setup` (`tests/native/lib/host.sh`);
a new kill point is a row in `KILL_POINTS` (`tests/native/lib/guest.sh`); a new scenario is a branch in `run.sh` built
from the same steps (`start_container`, `prepare_checkout`, `run_install`, `check_install`, `continuity_before/after`).
