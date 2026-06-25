# Dev environment

Three planes run independently. This box (the authoring sandbox) has only Node + Python +
a Java runtime, so the **server and agent first build on a provisioned machine / CI**, not here.

## Prerequisites

| Plane | Needs |
|-------|-------|
| Control plane (server) | Docker + Docker Compose |
| Admin frontend (web)   | Node 20+, npm |
| Device agent           | JDK 17, Android SDK (cmdline-tools), an AOSP emulator or a factory-reset device |

## 1. Control plane (Postgres + Tomcat server)

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

Config is supplied by `docker/context.xml` (mounted as Tomcat's ROOT context). Edit it to change
DB creds, base URL, MQTT, etc.

## 2. Admin frontend (React)

```bash
cd web
npm install
npm run dev          # Vite dev server; proxies /rest -> http://localhost:8080
```

See `web/README.md` for env vars and the endpoints it targets.

## 3. Device agent (Kotlin)

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
check-in → ack. It verifies the per-device-secret auth and the capability gate.

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
scripts/agent-v1-e2e.sh http://localhost:8080   # expect "RESULT: PASS=8 FAIL=0"
```

Verified locally: server built on JDK 17, run on Tomcat 9 + Postgres 17, all 8 checks pass.
The remaining step that needs a provisioned box is the **real on-device run**: build the agent,
enroll an AOSP emulator as Device Owner via ADB, and watch a `policy.apply` apply on the device.

## Tooling-gap note

The server `docker compose` build, the agent Gradle build, and the ADB enrollment cannot run in
the authoring sandbox (no Docker/Android SDK/adb). They are exercised in CI (`.github/workflows/`)
and on developer machines. The web build **does** run locally.
