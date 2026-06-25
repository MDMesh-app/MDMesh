# ADR 0009 — Agent v1 wire-contract stability

**Status:** Accepted (2026-06-25)

## Context

MDMesh deployments auto-update (CI/CD epic): a new server rolls out while devices in the field still
run older APKs, and Device-Owner devices **cannot be force-updated** (the user must approve; some may
lag indefinitely). So at any moment a deployment serves a mix of agent versions. If a server update
silently breaks the agent-facing wire contract, those older devices fall off management — the worst
possible failure for an MDM. ADR 0006 (capability handshake) and ADR 0004 (kotlinx.serialization,
`ignoreUnknownKeys`) set this up; this ADR makes the **stability rule explicit and enforced**.

## Decision

The `/agent/v1` request/response contract is **additive-only**:

- New fields are **optional and defaulted**. Never remove, rename, repurpose, or change the type of an
  existing field; never add a **required** request field (old agents won't send it).
- Agent **request** DTOs carry `@JsonIgnoreProperties(ignoreUnknown = true)` so a *newer* agent's
  extra fields don't 400 a not-yet-updated server. The agent already ignores unknown response keys.
- A genuine breaking change ships as a **new versioned namespace** (`/agent/v2`); `/agent/v1` keeps
  serving older agents until they're all gone.

**Enforcement (not discipline):** `AgentV1ContractTest` (in `:common`, DB-free, run in CI) replays
recorded oldest-supported v1 payloads against the current DTOs, asserts the load-bearing fields, and
asserts a future-agent payload with unknown fields still deserializes — plus that response field
names the agent parses are unchanged. The fleet-update **manifest** also carries a compatible-agent
protocol range so the server never offers a device an incompatible APK.

## Consequences

- Old APKs keep working across server updates by construction; a contract break fails the build.
- The cost is forward-only schema evolution + maintaining golden payloads for the oldest supported
  agent (extend them when the floor moves).
- A real break is possible but deliberate and visible (a new `/agent/vN` + its own contract tests),
  never accidental.
