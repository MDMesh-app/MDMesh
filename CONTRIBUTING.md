# Contributing to MDMesh

Thanks for being here. MDMesh aims to be a **genuinely complete, robust, modernized** open-source
Android MDM, and that only happens with good contributions — code, docs, bug reports, and ideas all
count. This guide gets you from clone to a green PR.

- 🐞 **Bug?** → [open a bug report](https://github.com/MDMesh-app/MDMesh/issues/new?template=bug_report.yml)
- 💡 **Idea?** → [open a feature request](https://github.com/MDMesh-app/MDMesh/issues/new?template=feature_request.yml)
- 🔧 **Code?** → read on, then send a focused PR.

---

## Repository layout

MDMesh is a monorepo with four planes (full map in **[STRUCTURE.md](STRUCTURE.md)**):

| Path | Plane | Stack |
|------|-------|-------|
| `common/`, `server/`, `jwt/`, `notification/`, `plugins/` | Control plane (server / REST API) | Java · Jersey · Guice · MyBatis · PostgreSQL |
| `web/` | Admin console | React · TypeScript · Vite |
| `agent-android/` | Device agent | Kotlin · coroutines · WorkManager (Gradle) |
| `supervisor/` | Updater / recovery service | Node (zero deps) |
| `proto/`, `docs/adr/` | Protocol source of truth + decision records | — |

Architecture decisions live in [`docs/adr/`](docs/adr); read the relevant ADR before changing a
load-bearing area (signing, device IDs, the agent contract, etc.).

---

## Dev setup & commands

You only need the toolchain for the plane you're touching.

### Console (`web/`)
```bash
cd web
npm install
npm run dev          # Vite dev server (proxies the API; point VITE_API_BASE at a running server)
npm run build        # production build
npx tsc --noEmit     # type-check
```

### Server (`common/`, `server/`)
Requires **JDK 17** and Maven.
```bash
mvn -pl common test          # fast, DB-free unit + contract tests
mvn -pl server -am compile   # type-check the server + its modules
mvn -pl server -am package -DskipTests   # build the WAR (full build)
```
The full server run needs PostgreSQL — easiest is the Docker stack below.

### Agent (`agent-android/`)
Requires **JDK 17** + the **Android SDK** (set `ANDROID_SDK_ROOT`). A `local.properties` with `sdk.dir`
also works.
```bash
cd agent-android
./gradlew :proto:test :core:test          # unit tests
./gradlew :app:compileDebugKotlin          # type-check the app + its modules
./gradlew :app:assembleDebug               # build a debug APK
```
Install on an emulator and promote to Device Owner for testing:
`adb install app-debug.apk && adb shell dpm set-device-owner com.mdmesh.agent.debug/com.mdmesh.agent.admin.AdminReceiver`

### Updater/recovery (`supervisor/`)
```bash
cd supervisor
node --test          # pure-logic unit tests (no deps)
```

### The whole stack, locally
```bash
./setup.sh           # Docker Compose: Postgres + server + Caddy + supervisor (+ optional tunnel)
```
See **[DEPLOY.md](DEPLOY.md)** for hosting modes, the update pipeline, and recovery.

---

## Workflow

1. **Branch** off `main` (`feat/…`, `fix/…`, `docs/…`). Don't commit to `main` directly.
2. **Make focused changes** — one logical change per PR. Match the style of the code around you.
3. **Test what you touched** with the commands above; add or update tests for behavior changes.
4. **Update docs** when you change behavior, config, or the API.
5. **Open a PR** against `main` using the [PR template](.github/pull_request_template.md).

### Commit messages
Short, imperative, prefixed: `feat:`, `fix:`, `docs:`, `refactor:`, `test:`, `chore:`. Explain the
*why* in the body when it isn't obvious.

### The one hard rule: the agent ↔ server contract is additive-only
The `/agent/v1` REST contract must stay backward-compatible so older agents keep working across server
updates: **new request fields are optional + defaulted; never remove, rename, or repurpose a field, and
never add a required request field.** A genuine break means a new `/agent/v2` (v1 stays). This is
enforced by a golden contract test in CI — see [ADR-0009](docs/adr/0009-agent-v1-contract-stability.md).

---

## What makes a PR easy to merge

- It's small and does one thing.
- Tests pass for the affected plane; new behavior has a test.
- UI changes include a screenshot or short clip.
- Docs are updated alongside the code.
- It respects the agent contract rule above and any relevant ADR.

Not sure where to start? Look for issues labelled **good first issue**, or open a discussion before a
large change so we can agree on the approach.

---

## Regenerating the docs screenshots

The console screenshots in the README are produced from the real SPA against sample data:
```bash
cd web && npm run build          # build the SPA first
cd ../scripts/shots && npm install && node capture.mjs
```
Output lands in `docs/screenshots/`. See `scripts/shots/` for the harness + fixtures.

---

By contributing, you agree that your contributions are licensed under the project's
[Apache-2.0 license](LICENSE).
