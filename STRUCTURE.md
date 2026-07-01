# Repository Structure

<sub>[← README](README.md) · [Deploy](DEPLOY.md) · **Structure** · [Contributing](CONTRIBUTING.md) · [Releasing](RELEASING.md) · [ADRs](docs/adr)</sub>

This is a **monorepo** for an MDM product forked from Headwind MDM. It contains four
cooperating planes plus shared protocol and docs.

```
.
├── server/  common/  jwt/  notification/  plugins/   # PLANE 1 — Control plane (forked Headwind, Java/Jersey/PostgreSQL)
│   pom.xml                                            #   (existing Maven multi-module project, builds launcher.war)
├── web/                                               # PLANE 2 — Admin frontend (new, React + Vite + TypeScript)
├── agent-android/                                     # PLANE 3 — Device agent (new, Kotlin Device-Owner app)
│   └── (remote-control transport lives in :remote)    # PLANE 4 — Remote control (WebRTC + coturn, signaling over MQTT/long-poll)
├── proto/                                             # Shared protocol: capability matrix + command model (source of truth)
├── docs/adr/                                          # Architecture Decision Records (one-way-door choices)
├── reference/hmdm-android/                            # gitignored study clone of upstream agent (Apache-2.0, NOT shipped)
└── infra/ (docker-compose.dev.yml at root)            # Dev environment
```

## The four planes

| Plane | Path | Tech | Status |
|-------|------|------|--------|
| 1. Control plane | `server/` + `common/ jwt/ notification/ plugins/` | JDK 17, Jersey/JAX-RS, Guice, MyBatis, PostgreSQL, Liquibase | Forked from Headwind + extended; REST-only (legacy AngularJS UI removed) |
| 2. Admin frontend | `web/` | React 18, Vite, TypeScript | New, from scratch — the console |
| 3. Device agent | `agent-android/` | Kotlin, coroutines, Hilt, Room, WorkManager, kotlinx.serialization | New, from scratch |
| 4. Remote control | `agent-android/remote/` + server signaling | MediaProjection + AccessibilityService, WebRTC, coturn | Stubbed |

## Two load-bearing architecture patterns

- **Capability-abstraction layer** (`agent-android/policy/`): every privileged op behind a
  versioned interface with `SDK_INT`-gated strategies. Survives the Android version treadmill
  by swapping one strategy instead of rewriting.
- **Server-driven command model + capability handshake** (`proto/`): the device advertises a
  capability matrix on check-in; the server only issues commands the device advertises. The
  protocol is versioned and schema-evolvable so old agents degrade gracefully.

## Why the existing server stays at repo root

The Headwind Maven project (root `pom.xml` + `common/ jwt/ notification/ plugins/ server/`) is
mature and its build/install scripts assume these paths. Nesting it under `server/` is a
deferred, optional cleanup — not worth the churn during foundation. New components get their
own top-level directories.

## Build & verify

See `docs/DEV.md` for the local dev loop. Server builds with Maven, web with npm/Vite, agent
with Gradle + the Android SDK. CI (`.github/workflows/`) builds all three; the agent runs
against an Android API-level matrix.
