# Dev environment

How to run each plane locally. You only need the toolchain for the plane you are changing. Test and build commands,
and what CI runs, are in [CONTRIBUTING.md](../CONTRIBUTING.md#build-and-test).

## Prerequisites

| Plane | Needs |
|-------|-------|
| Control plane (server) | Docker + Docker Compose; bash, curl, python3, openssl and md5sum (GNU coreutils) for the scripts; JDK 17 + Maven to build on the host |
| Admin frontend (web)   | Node 24 (`engines` in `web/package.json`), npm |
| Device agent           | JDK 17, Android SDK (cmdline-tools; set `ANDROID_SDK_ROOT`, or `sdk.dir` in `local.properties`), an AOSP emulator or a factory-reset device |

## 1. Control plane (the dev stack)

```bash
docker compose --env-file docker/dev.env up -d --build    # build + start; the first boot runs Liquibase
scripts/dev-seed.sh                                        # first run: seed the database like the installers do
```

Pass `--env-file docker/dev.env` on **every** compose command for the dev stack. It replaces `.env`, so a
production `.env` in the same checkout is never read.

| What | Where |
|------|-------|
| Console | <http://localhost:8088>, login **admin / admin** (after `scripts/dev-seed.sh`) |
| API (direct to Tomcat) | <http://localhost:8080/rest/> |
| Postgres | `localhost:5432`, database, user and password `mdmesh` |
| Debugger (JDWP) | attach your IDE to `localhost:5005` |

Ports bind to `127.0.0.1`. If one is taken, export `DEV_WEB_PORT`, `DEV_API_PORT`, `DEV_PG_PORT` or
`DEV_DEBUG_PORT` first (shell variables override `docker/dev.env`). If you move the console, also export
`BASE_URL=http://localhost:<port>`.

### What the dev stack is

The production stack (`docker-compose.yml`) plus `docker-compose.dev.yml`, which publishes the loopback ports and the
debugger. `docker/dev.env` sets the project name `mdmesh-dev`, dev-only passwords and `:dev` image tags. Configuration
is environment only: change a value in `docker/dev.env` (or export it) and run the `up -d` command again.

### Everyday commands

```bash
docker compose --env-file docker/dev.env logs -f server              # server log
docker compose --env-file docker/dev.env up -d --build server        # rebuild + restart the server image
docker compose --env-file docker/dev.env down                        # stop; keeps the data
docker compose --env-file docker/dev.env down -v                     # reset: also deletes the dev database and files
```

### Before you reset or share a checkout

**`down -v` also deletes the server's data volume** (`/opt/mdmesh`: uploaded files and the JWT signing key), so the
next start generates a new key. If your shell exports `COMPOSE_PROJECT_NAME`, that beats the env file and `down -v`
deletes *that* project. Check first: `docker compose --env-file docker/dev.env config | head -1` must print
`name: mdmesh-dev`.

**Do not run the dev stack in a checkout that also runs a real install** (`./setup.sh` writes a `.env` there). The
`MDMESH_DEV` guard in `docker-compose.dev.yml` stops `up` and `config` without `--env-file docker/dev.env`, but not
`down -v`, `stop`, `rm`, `exec`, `logs` or `ps`. Without it, those act on the production project, even when they
name the dev file.

### Seeding

`scripts/dev-seed.sh` seeds with the installers' own code, then sets the admin password through the real first-login
flow: `admin`, or `DEV_ADMIN_PASSWORD` if you export it. It is safe to re-run. It refuses to run unless the containers
were created with `docker-compose.dev.yml`, so it never seeds a production install.

### Logging

The server logs to stdout at INFO, from `server/src/main/resources/log4j.xml`. For DEBUG, raise a level there locally
and use the fast Java loop. Don't commit it: `LoggingConfigTest` fails unless the root and `AuditLogger` levels are
INFO. If an older dev stack ignores the change, recreate the server once:
`docker compose --env-file docker/dev.env up -d --force-recreate server`.

### Fast Java loop

Rebuilding the image runs the whole Maven build in Docker (over a minute). Instead, build on the host and copy the WAR
in; Tomcat reloads it within about 20 s:

```bash
cp -n server/build.properties.example server/build.properties     # once
mvn -pl server -am package -DskipTests
docker compose --env-file docker/dev.env cp server/target/launcher.war server:/usr/local/tomcat/webapps/ROOT.war
```

The copied WAR lasts until the container is recreated (`down`, or `up -d --build`).

### API reference (Swagger)

The server publishes Swagger 2.0 at `/rest/swagger.json` (no UI is bundled). To browse it:

```bash
curl --create-dirs -o /tmp/api/swagger.json http://localhost:8080/rest/swagger.json
docker run --rm -p 8081:8080 -e SWAGGER_JSON=/api/swagger.json -v /tmp/api:/api swaggerapi/swagger-ui   # → http://localhost:8081
```

Or open the file in editor.swagger.io.

## 2. Admin frontend (React)

```bash
cd web
npm install
npm run dev          # http://localhost:5173 with hot reload, proxied to the dev stack
```

It proxies the API routes to the dev stack's Caddy (<http://localhost:8088>); point it elsewhere with
`VITE_DEV_PROXY_TARGET` (see `web/README.md`). It listens on every interface, so a phone on your LAN can open it.
Run it only on a network you trust.

## 3. Device agent (Kotlin)

```bash
cd agent-android
./gradlew :app:assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk && adb shell dpm set-device-owner com.mdmesh.agent.debug/com.mdmesh.agent.admin.AdminReceiver
```

The full ADB enrollment steps are in "ADB Device-Owner dev enrollment loop" in `agent-android/README.md`. Without a
QR code, the agent uses the `MDM_BASE_URL` baked into its build type (`agent-android/app/build.gradle.kts`). The agent
only talks HTTPS, so a real device needs a server it can reach over HTTPS (see [DEPLOY.md](../DEPLOY.md)); the
loopback dev stack is enough for the scripted loop below.

## End-to-end agent loop (Agent v1)

On the dev stack, after `scripts/dev-seed.sh`:

```bash
scripts/agent-v1-e2e.sh http://localhost:8080      # expect "RESULT: PASS=<n> FAIL=0"
```

It plays the device with `curl`: enroll, mint a token, queue a command, check in, ack, plus the command and rollout
scenarios. It signs in as `admin` with `ADMIN_PW` (default `admin`). If you seeded with `DEV_ADMIN_PASSWORD`, or
are testing another server, pass that password as `ADMIN_PW=<password>`.

CI runs this loop as tier T1 (see [CI](../CONTRIBUTING.md#ci)). The one step that needs a provisioned box is the
**real on-device run**: enroll an AOSP emulator via ADB and watch a `policy.apply` land.

## T2 native rig (native install + upgrade)

```bash
tests/native/run.sh debian-12 fresh       # distros: debian-12, debian-13, ubuntu-24.04
tests/native/run.sh debian-12 upgrade     # scenarios: fresh, upgrade, killed
T2_KILL_POINTS=mid-deploy,migrating tests/native/run.sh debian-12 killed
```

It runs the native install ([DEPLOY.md Option C](../DEPLOY.md#option-c--native-no-docker)) in a throwaway systemd
container that looks like a fresh VPS, with the documented command:
`sudo BASE_URL=… HTTP_PORT=… ./setup.sh --native -y`. An upgrade moves the checkout to the new commit and runs it
again without `BASE_URL` and `HTTP_PORT`, which it must keep.

| Scenario | What it proves |
|----------|----------------|
| `fresh` | The change installs on a clean host and the admin can sign in. |
| `upgrade` | The release in `T2_FROM_REF`, then the change over it. The upgrade succeeds and keeps the data: a device enrolled before checks in after with its original secret; secrets, counts and the admin password are unchanged. Run without `HTTP_PORT`, `BASE_URL`, `GITHUB_REPO` or `GITHUB_TOKEN`, it keeps the port, the base URL and the updater settings. |
| `killed` | The same upgrade, SIGKILLed at each kill point and re-run once without `HTTP_PORT` or `BASE_URL`: it must converge, keep the base URL and the updater settings, and pass every check. The points are listed in `tests/native/lib/guest.sh` (`bash tests/native/lib/guest.sh kill-points`). |

After every install the same checks run: the services are active, `/rest/public/name` answers 200, admin signs in,
and that version's `scripts/agent-v1-e2e.sh` passes.

**Always set `T2_FROM_REF`** to the release to upgrade from. The clone also carries upstream Headwind tags (`v5.x`),
so "the newest tag" is not the last MDMesh release.

| Setting | Effect |
|---------|--------|
| `T2_FROM_REF` | Release to upgrade from (default `v0.3.1`). |
| `T2_MIN_FREE_GB` | Refuses to start with less free space under the Docker root (default 8). |
| `T2_CACHE=1` | Shares Maven and npm caches between runs in the volumes `mdmesh-t2-cache-m2` and `mdmesh-t2-cache-npm` (remove them with `docker volume rm`). |
| `T2_OUT` | Logs and `results.tsv` (default `/tmp/mdmesh-t2`, or `$TMPDIR/mdmesh-t2`). Each run writes to `<T2_OUT>/<container>/`. |
| `T2_STRICT=1` | Builds an upgrade's image without the old-release workarounds (see `tests/native/Dockerfile`). |

The rest are in the header of `tests/native/run.sh`. On 4 cores, `fresh` and `upgrade` take 3–5 minutes each and
`killed` about 20.

The container is privileged (systemd needs it) but has its own network and cgroup namespaces and no host mounts.
Everything a run creates is named `mdmesh-t2-…` and removed on exit; see the headers of `tests/native/run.sh` and
`tests/native/Dockerfile`. To add a distro, kill point or scenario, see `distro_setup` in `tests/native/lib/host.sh`,
`KILL_POINTS` in `tests/native/lib/guest.sh`, and `run.sh`.

CI runs the rig as tier T2 (see [CI](../CONTRIBUTING.md#ci)).
