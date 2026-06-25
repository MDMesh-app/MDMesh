# ADR 0003 — Server-issued device IDs (never IMEI/IMSI/serial)

**Status:** Accepted (2026-06-21)

## Context

Hardware identifiers (IMEI, IMSI, serial) are **restricted/unavailable** to non-platform apps
since Android 10 — `getSubscriberId()` throws, serial needs privilege. hmdm anchored identity
on these and repeatedly broke (`#4`, `c42486d69`: wrong IMEI/serial at first start).

## Decision

The server issues an **opaque device id** at enrollment; the agent stores it (DataStore) and
uses it as the sole identity in every protocol message (`device.id`). Hardware identifiers, if
ever read, are best-effort *telemetry* only — never identity, never required.

## Consequences

- (+) Works on every Android version without privileged identifiers or scary permissions.
- (+) Decouples identity from SIM/hardware; survives SIM swaps and re-imaging.
- (−) The id must survive app data across reboots and updates (DataStore + enrollment token);
  a fresh enrollment yields a new id (handled by duplicate-enrollment prevention server-side).
