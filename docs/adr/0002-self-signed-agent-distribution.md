# ADR 0002 — Self-signed agent + key custody

**Status:** Accepted (2026-06-21)

## Context

A Device-Owner app's signing key is a one-way door: a DO app signed with the wrong key
**cannot be updated** over an existing install — the device must be factory-reset. hmdm hit
exactly this (`#25`, `#26`). Distribution choice also drives Play Protect posture (Google now
flags sideloaded DPCs).

## Decision

**Self-sign** the agent; enroll owned devices via QR / ADB / zero-touch (non-Play
distribution). The signing key is a long-lived secret with documented custody:
- One release keystore, backed up out-of-band, access-controlled.
- The QR provisioning payload pins `PROVISIONING_DEVICE_ADMIN_SIGNATURE_CHECKSUM` to this key.
- CI signs release builds from a secret-managed keystore; debug builds use a throwaway debug key.

## Consequences

- (+) Full control; no Play review loop; fits self-hosted owned-fleet model.
- (+) Sidesteps Play Protect by not depending on Play distribution.
- (−) Losing the keystore = no upgrade path for deployed devices (factory reset). Custody is
  therefore a first-class operational concern, not an afterthought.
- (−) Sideloaded installs may still trip Play Protect heuristics → see ADR 0005.
- Revisit if we ever target broad managed-Google-Play distribution.
