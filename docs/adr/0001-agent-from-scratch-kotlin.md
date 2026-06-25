# ADR 0001 — Build the device agent from scratch in Kotlin

**Status:** Accepted (2026-06-21)

## Context

The Headwind Android agent (`h-mdm/hmdm-android`, Apache-2.0) is mature but, on teardown:
Java-only, `MainActivity` is 2856 lines, concurrency is four overlapping models
(AsyncTask/Thread/executors/WorkManager), no DI, hand-rolled SQLite, SHA-1 signing, vendored
abandoned Paho. Its real COSU kiosk logic is closed-source "Pro" and stubbed in OSS. We need
net-new live remote control regardless, and a base we can maintain across the Android version
treadmill for years.

## Decision

Build a new agent from scratch in **Kotlin** (coroutines/Flow, Hilt, Room, DataStore,
WorkManager, kotlinx.serialization). Treat hmdm-android as a **reference for patterns**, not
code to copy. Reuse (reimplemented): the server endpoint contract, primary/secondary server
failover, MQTT resilience patterns (hangup monitor, reconnect worker, ping-death, connection-loop
guard), crash-loop protection, and the kiosk-without-overlay safety guard.

## Consequences

- (+) Clean, testable, modular base; modern async; a real (open) kiosk implementation.
- (+) Capability-abstraction layer isolates the version treadmill (see ADR 0006).
- (−) We re-implement ~a year of undifferentiated plumbing (enrollment, policy surface,
  install pipeline, survival) before reaching parity. Mitigated by mining hmdm's solved
  edge-cases via its issue/commit history.
- (−) We own all OEM-quirk handling. Mitigated by an `:oem` adapter seam.
