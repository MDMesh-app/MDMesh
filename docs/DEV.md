# Dev environment

Three planes run independently. The Java control plane can be built and unit-tested without
Docker; Docker is required for the running server and its database-backed integration tests.

## Prerequisites

| Plane | Needs |
|-------|-------|
| Control plane unit build/test | OpenJDK 21 or 25 + the checked-in Maven Wrapper |
| Control plane integration/deployment | OpenJDK 21 or 25, Docker + Docker Compose |
| Admin frontend (web)   | Node 22 (Vite 8 needs ≥ 20.19), npm |
| Device agent           | JDK 17, Android SDK (cmdline-tools), an AOSP emulator or a factory-reset device |

## 1. Control plane unit build and coverage baseline

The supported baseline is built on JDK 21; JDK 25 is also tested in CI. From the repository root:

```bash
cp server/build.properties.example server/build.properties
./mvnw -B -ntp verify
```

This runs all current Maven unit tests, packages the WAR, and writes per-module Surefire reports
under `*/target/surefire-reports/`. JaCoCo reports are generated under
`*/target/site/jacoco/`; they are informational while the modernization work establishes its
baseline. `server/build.properties` is generated locally and must not be committed.

The Maven compiler uses `--release 21`, so JDK 17 cannot build or run the resulting WAR. The
deployment fixture is Apache Tomcat 10.1, matching the Jakarta Servlet 6 application API.

## 2. Control plane (Postgres + Tomcat server)

### Local disposable integration harness (no Docker)

The deployed-WAR harness can run without Docker when PostgreSQL server tools and JDK 21 or 25 are
available. It initializes PostgreSQL in a new temporary directory, downloads a checksum-verified
stock Tomcat 10.1.60 into that directory, deploys the real WAR, waits for the application
initializer's Liquibase-completion marker, and checks login/authenticated REST plus file serving.
It never uses the system PostgreSQL cluster or installs Tomcat as a service.

```bash
sudo apt-get install -y postgresql postgresql-client
scripts/local-tomcat-postgres-integration.sh
```

The script uses `MDMESH_JAVA_HOME`, then `JAVA_HOME`, then the `javac` found on `PATH`; each must
identify a JDK 21 or 25 installation. On failure it retains its temporary directory and prints its log
locations. Set `KEEP_WORKDIR=1` to retain it after a successful run. This is a development
integration harness, not a replacement for the Docker/Compose test below, which remains required
for deployment parity.

#### Enterprise TLS-inspection CA

If the Maven build reports a Java `PKIX path building failed` error for a repository that WSL
itself can reach, provide the **single root CA PEM file** trusted by the organization through the
opt-in Compose override. Keep the PEM file outside the repository; it is passed as a BuildKit secret
only to the Maven build stage and is not copied to the final Tomcat image.

```bash
MDMESH_BUILD_CA=/absolute/path/to/organization-root-ca.pem \
  scripts/docker-wsl-rootless.sh compose \
    -f docker-compose.dev.yml -f docker-compose.dev.tls.yml up --build
```

Obtain the root certificate through the organization's approved process. Do not disable TLS
verification, add the PEM to Git, or use a leaf certificate such as `jitpack.io`'s intercepted
certificate. The normal Compose command below needs no CA path and remains unchanged.

If host PostgreSQL already listens on port 5432, select unused host ports for the disposable
development stack. Keep `MDMESH_DEV_BASE_URL` aligned with the HTTP port so generated URLs work:

```bash
MDMESH_DEV_POSTGRES_PORT=15432 \
MDMESH_DEV_HTTP_PORT=18080 \
MDMESH_DEV_BASE_URL=http://localhost:18080 \
  scripts/docker-wsl-rootless.sh compose -f docker-compose.dev.yml up --build
```

```bash
docker compose -f docker-compose.dev.yml up --build
```

- First boot runs **Liquibase**, which creates the schema and the default `admin` user.
- Panel: <http://localhost:8080>  ·  default login **admin / admin**.
- Optional seed (display names, role descriptions, system app list) — run **after** the app has
  finished initializing (i.e. after the schema exists):

  ```bash
  # hmdm_init.en.sql has an _ADMIN_EMAIL_ placeholder; substitute then apply
  sed 's/_ADMIN_EMAIL_/admin@localhost/' install/sql/hmdm_init.en.sql \
    | docker compose -f docker-compose.dev.yml exec -T postgres psql -U hmdm -d hmdm
  ```

The development Compose file supplies disposable database credentials and server configuration as
container environment variables. Production configuration is supplied through `.env` and
`docker-compose.yml`.

## 3. Admin frontend (React)

```bash
cd web
npm install
npm run dev          # Vite dev server; proxies /rest -> http://localhost:8080
```

See `web/README.md` for env vars and the endpoints it targets.

## 4. Device agent (Kotlin)

```bash
cd agent-android
gradle wrapper --gradle-version 8.10   # one-time: generates the wrapper jar (not committed)
./gradlew :app:assembleDebug
```

### ADB Device-Owner enrollment loop (dev)

Device Owner can only be set on a device with **no accounts** (fresh / factory-reset). Use an
**AOSP** emulator image (not a Google APIs image — those add a Google account and block DO).

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell dpm set-device-owner com.mdmesh.agent/.AdminReceiver
# verify
adb shell dumpsys device_policy | grep -i "Device Owner"
```

To unwind during testing:

```bash
adb shell dpm remove-active-admin com.mdmesh.agent/.AdminReceiver   # if removable
# otherwise wipe the emulator / factory-reset the device
```

Point the agent at your local server via the agent's `BASE_URL` BuildConfig (defaults documented
in `agent-android/README.md`). For a hardware device, the server URL must be reachable from the
device (use your LAN IP, not localhost).

## End-to-end agent loop (Agent v1)

`scripts/agent-v1-e2e.sh` drives the whole protocol against a running server with `curl`
playing the device: enroll → mint token → queue command → authenticated, capability-gated
check-in → ack. It also opens a real, authenticated WebSocket to `/agent/ws/{deviceNumber}` and
verifies that queueing a command delivers the `{"wake":"commands"}` signal. It verifies the
per-device-secret auth, REST capability gate, and Tomcat WebSocket wiring without changing the
Android agent.

A fresh Liquibase-only DB needs four one-time setups (normally done via the admin UI on first
run; here applied directly for a scripted run):

```bash
# 1. seed base data (configurations, settings, system apps, roles)
sed 's/_ADMIN_EMAIL_/admin@localhost/' install/sql/hmdm_init.en.sql | psql ... -d hmdm
# 2-4. enable scripted enrollment
psql ... -d hmdm -c "UPDATE users    SET passwordreset=false        WHERE id=1;"  # else 403 on /rest/private/*
psql ... -d hmdm -c "UPDATE settings SET createnewdevices=true      WHERE id=1;"  # allow on-demand device creation
psql ... -d hmdm -c "UPDATE settings SET newdeviceconfigurationid=1 WHERE id=1;"  # devices.configurationId is NOT NULL
```

Then:

```bash
scripts/agent-v1-e2e.sh http://localhost:8080   # expect "RESULT: PASS=18 FAIL=0"
```

For the disposable local harness, run the same contract suite automatically with:

```bash
MDMESH_AGENT_V1_E2E=1 scripts/local-tomcat-postgres-integration.sh
```

The current T0 workflow runs the full Maven reactor on JDK 21 and 25. Promotion of this
disposable Tomcat 10.1/PostgreSQL contract suite into a maintainer-approved T1 integration
workflow remains follow-up work; run it locally before a release candidate.
The remaining step that needs a provisioned box is the **real on-device run**: build the agent,
enroll an AOSP emulator as Device Owner via ADB, and watch a `policy.apply` apply on the device.

## Tooling-gap note

The server `docker compose` build, database-backed integration tests, the agent Gradle build, and
ADB enrollment require the corresponding Docker/Android tooling. They are exercised in CI
(`.github/workflows/`) and on provisioned developer machines. The web build and Java unit baseline
run locally.
