# Contributing to MDMesh

Thanks for being here. Code, docs, bug reports and ideas all help. This guide gets you from clone to a green PR.

- 🐞 **Bug?** → [open a bug report](https://github.com/MDMesh-app/MDMesh/issues/new?template=bug_report.yml)
- 💡 **Idea?** → [open a feature request](https://github.com/MDMesh-app/MDMesh/issues/new?template=feature_request.yml)
- 🔧 **Code?** → read on, then send a focused PR.
- **Security issue?** → see [SECURITY.md](SECURITY.md). Please don't file it publicly.

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

Read the relevant ADR in [`docs/adr/`](docs/adr) before changing a load-bearing area (signing, device IDs, the
agent contract, etc.).

---

## Build and test

You only need the toolchain for the plane you're touching; [docs/DEV.md](docs/DEV.md) sets it up and runs the dev
stack.

| Plane | Run from | Test | Type-check / build |
|-------|----------|------|--------------------|
| Console | `web/` | — (see [the runtime check](#dependency-updates)) | `npx tsc -b --noEmit` · `npm run build` |
| Server | repo root | `mvn -pl common test` (fast, DB-free) | `mvn -pl server -am compile` · `mvn -pl server -am package -DskipTests` (the WAR) |
| Agent | `agent-android/` | `./gradlew :proto:test :core:test` · `./gradlew detekt` | `./gradlew :app:compileDebugKotlin` · `./gradlew :app:assembleDebug` (debug APK) |
| Supervisor | `supervisor/` | `node --test` | the image + smoke test, below |
| Whole stack | repo root | `docker compose --env-file docker/dev.env up -d --build` and `scripts/dev-seed.sh`, then `scripts/agent-v1-e2e.sh http://localhost:8080` | — (the bring-up builds the images) |

- **Console:** run `npm install` first.
- **Server:** run `cp -n server/build.properties.example server/build.properties` once. A full server run needs
  PostgreSQL: use the dev stack.
- **Supervisor image**, from the repo root:

  ```bash
  docker build -f docker/supervisor.Dockerfile -t mdmesh-supervisor:dev . \
    && scripts/supervisor-smoke.sh mdmesh-supervisor:dev   # boots the image the way compose runs it
  ```

- **Whole stack** is the dev stack. `./setup.sh` is the production installer instead (see [DEPLOY.md](DEPLOY.md)).

---

## Workflow

1. **Branch** off `main` (`feat/…`, `fix/…`, `docs/…`). Don't commit to `main` directly.
2. **Make focused changes** — one logical change per PR. Match the style of the code around you.
3. **Test what you touched**; add or update tests for behavior changes.
4. **Update docs** when you change behavior, config, or the API.
5. **Add a changelog line** under Unreleased in [CHANGELOG.md](CHANGELOG.md), in its section: one short bullet for the
   people running MDMesh, or "none" in the PR if they won't notice. Releases go out every three weeks
   ([release cycle](RELEASING.md#release-cycle)). Conflicts under Unreleased are expected: keep both lines.
6. **Open a PR** against `main` using the [PR template](.github/pull_request_template.md).

### CI

| Tier | Workflow | What it runs | When |
|------|----------|--------------|------|
| **T0** | `t0-fast.yml` | Build + DB-free tests for each plane you touched (agent: detekt too), the supervisor smoke test, the edge (Caddy + compose) check | Every PR and push to `main` |
| **T1** | `t1-e2e.yml` | The [Agent v1 end-to-end loop](docs/DEV.md#end-to-end-agent-loop-agent-v1) against a real server + Postgres, isolated from your dev stack | PRs and pushes to `main` that touch the server, `proto/`, `install/` or the dev stack |
| **T2** | `t2-native.yml` | The [native rig](docs/DEV.md#t2-native-rig-native-install--upgrade): fresh install, upgrade, killed upgrade, on Debian 12, Debian 13 and Ubuntu 24.04 | PRs that touch the installers or the rig; nightly; by hand; before every release ([RELEASING.md](RELEASING.md)) |

The exact triggers are the path filters in each workflow under `.github/workflows/` (the `paths` job in T0/T1,
`on.pull_request.paths` in T2). In T0 and T1, a change under `.github/`, to a compose file, a lockfile or a `Dockerfile*` runs everything.
New Kotlin must pass detekt; old findings are in `agent-android/config/detekt/baseline.xml`. A failed T1 uploads its
logs as the `t1-e2e-logs` artifact, and a red T1 on `main` opens a tracking issue.

### Commit messages

Short, imperative, prefixed: `feat:`, `fix:`, `docs:`, `refactor:`, `test:`, `chore:`. Explain the
*why* in the body when it isn't obvious.

### The one hard rule: the agent ↔ server contract is additive-only

The `/agent/v1` contract must keep older agents working: **new request fields are optional and defaulted; never
remove, rename or repurpose a field, and never add a required one.** A real break means a new `/agent/v2`. A golden
contract test enforces this in CI ([ADR-0009](docs/adr/0009-agent-v1-contract-stability.md)).

---

## Dependency updates

Dependabot opens grouped PRs every Monday ([`.github/dependabot.yml`](.github/dependabot.yml)).

- **T0 green is not enough** for a runtime library crossing a minor/major. Also run that plane's runtime check:

  | Plane | Runtime check |
  |-------|---------------|
  | Web | `cd web && npm run build && cd ../scripts/shots && npm install && npx playwright install chromium && node capture.mjs --check` |
  | Server | the `t1-e2e` workflow (`agent-e2e` job) green on the PR |
  | Agent | install the APK on a device/emulator and complete a check-in |

- **Ignored lines wait for their migration.** Each `ignore` names the migration that lifts it; remove it in that
  migration's PR, never piecemeal. The `agent-toolchain` group (AGP 9 and everything coupled to it) stays red until
  the AGP 9 migration and is never merged piecemeal.
- **License changes** (e.g. Liquibase 5 → FSL) are blocked until reviewed against
  [ADR-0008](docs/adr/0008-licensing-and-rebrand.md).
- **One JDK, one Node line.** The server is **JDK 17** everywhere. Node is the current LTS (today **24**); every place
  that names it (`web/package.json` `engines`, `@types/node`, both Node images, the native installer's `NODE_VER`)
  moves in one PR, a few months after the next even line becomes LTS.
- **Base images float**, so rebuilds pick up patches. The edge's `caddy` image is pinned, so the Caddyfile parser only
  changes through a Dependabot PR that T0's `edge` check has passed.
- **Vendored CI actions** (`.github/actions/ci-kit/`) change only by re-running ci-kit's `scripts/vendor.sh`.

---

## What makes a PR easy to merge

- It's small and does one thing.
- Tests pass for the affected plane; new behavior has a test.
- UI changes include a screenshot or short clip.
- Docs are updated alongside the code.
- It respects the agent contract rule above and any relevant ADR.

New here? Look for **good first issue**, or open a discussion before a large change.

### What to expect after you open something

- A first maintainer reply within a week. If it's been longer, a polite ping is welcome.
- CI doesn't run automatically on a **first-time contributor's** PR (GitHub's default); a maintainer approves it.
- `main` is protected against force-pushes and deletion. Maintainers land small fixes on `main` directly; everything
  else goes through a PR.

---

## Regenerating the docs screenshots

The README screenshots come from the real SPA against sample data:

```bash
cd web && npm run build          # build the SPA first
cd ../scripts/shots && npm install && npx playwright install chromium && node capture.mjs
```

Output lands in `docs/screenshots/`; the harness and fixtures are in `scripts/shots/`.

---

By contributing, you agree that your contributions are licensed under the project's
[Apache-2.0 license](LICENSE).
