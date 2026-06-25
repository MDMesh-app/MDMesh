# ADR 0004 — kotlinx.serialization for the evolvable protocol

**Status:** Accepted (2026-06-21)

## Context

hmdm uses Jackson on-device, which forced `minSdk 26` when Jackson 2.21 dropped older-Android
support (`6d0336970`). Our protocol must be **forward/backward tolerant** (ignore unknown
fields/commands) per `proto/VERSIONING.md`.

## Decision

Use **kotlinx.serialization** in the agent for protocol JSON. Configure with
`ignoreUnknownKeys = true`, `encodeDefaults = false`, `explicitNulls = false`. Keep the agent's
`@Serializable` data classes a faithful mirror of `proto/*.schema.json`.

## Consequences

- (+) No reflection; smaller; lets us hold `minSdk 24` (Android 7) instead of 26.
- (+) `ignoreUnknownKeys` directly implements the unknown-tolerance compatibility rule.
- (+) Multiplatform-ready if we ever share protocol code.
- (−) Server side stays Jackson (Java); the JSON schemas in `proto/` are the contract both
  sides must honor — schema drift is the risk, mitigated by treating `proto/` as canonical.
