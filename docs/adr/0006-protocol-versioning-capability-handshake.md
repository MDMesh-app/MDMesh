# ADR 0006 — Protocol versioning + capability handshake

**Status:** Accepted (2026-06-21)

## Context

Android removes/limits an API every release, so a given agent's *real* capabilities vary by OS
version and across the fleet. A fixed feature contract guarantees breakage. The user's
"dynamically typed / easy to migrate / future-forward" requirement maps to a loosely-coupled,
evolvable protocol (not a dynamic language — Android is Kotlin).

## Decision

Two complementary mechanisms:

1. **Capability handshake** (`proto/`): the device advertises a capability matrix on every
   check-in; the server only issues commands whose `requiresCapability` the device advertises.
   Capabilities/commands are **open string registries**.
2. **Capability-abstraction layer** (`agent-android/policy/`): every privileged op sits behind
   a versioned interface with `SDK_INT`-gated strategy implementations. New OS behavior = add/
   swap one strategy; the rest of the app is untouched.

Versioning rules (additive MINOR, breaking MAJOR; unknown-tolerant both ways) are in
`proto/VERSIONING.md`.

## Consequences

- (+) Old and new agents coexist with one server; features degrade, never crash.
- (+) The version treadmill is contained to small strategy classes, with a CI API matrix to
  catch breakage early (ADR-implied; see `.github/workflows`).
- (−) More upfront indirection than hardcoding calls. Justified by the multi-year maintenance horizon.
