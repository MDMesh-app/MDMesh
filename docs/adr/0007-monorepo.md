# ADR 0007 — Monorepo layout

**Status:** Accepted (2026-06-21)

## Context

The product spans four planes (server, web, agent, remote-control transport) plus a shared
protocol. They change together — especially the protocol, which touches agent and server at once.

## Decision

Single repo. New components get top-level dirs (`agent-android/`, `web/`, `proto/`,
`docs/`, `reference/`). The existing Headwind Maven project stays at repo root (`pom.xml` +
`common/ jwt/ notification/ plugins/ server/`) to avoid churning its path-dependent build and
install scripts; nesting under `server/` is a deferred option. See `STRUCTURE.md`.

## Consequences

- (+) Atomic cross-cutting changes (a protocol bump lands in agent + server in one commit).
- (+) One CI, one issue tracker, shared `proto/` as source of truth.
- (−) Larger checkout; mixed toolchains (Maven + Gradle + npm) in one repo — handled by
  per-component CI jobs.
- (−) The forked server keeps Headwind's history at root; upstream pulls are manual (hard fork).
