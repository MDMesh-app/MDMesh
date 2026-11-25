# Protocol (v1) — source of truth

Language-neutral contract between the **device agent** (Kotlin) and the **control plane**
(Java server). The admin frontend never speaks this directly — it goes through the server's
REST API.

This directory is the canonical definition. Implementations mirror it:
- Agent: `agent-android/proto/` (kotlinx.serialization `@Serializable` data classes).
- Server: Java DTOs under `common/src/main/java/com/hmdm/rest/json/` (extends existing `SyncResponse` / `DeviceInfo`).

## Design goals (the "evolvable / dynamically-typed" requirement)

1. **Versioned.** Every message carries `protocolVersion` (semver). See `VERSIONING.md`.
2. **Unknown-tolerant.** A device MUST ignore command `type`s it doesn't recognize and reply
   `status: "unsupported"`. The server MUST ignore unknown fields in reports.
3. **Capability-gated.** The server MUST NOT send a command whose `requiresCapability` is
   absent from the device's most recent capability matrix. This is what lets old agents and
   new servers coexist — features degrade instead of crashing.
4. **Open registries.** Capability keys and command types are open string registries
   (`registry.md`), not closed enums. Adding one is an additive, backward-compatible change.

## Three message shapes

| Direction | Message | Schema | When |
|-----------|---------|--------|------|
| device → server | **Capability matrix** | `capability-matrix.schema.json` | every check-in / enrollment |
| server → device | **Command envelope** | `command-envelope.schema.json` | when admin acts |
| device → server | **Command result** | `command-result.schema.json` | after handling a command |

`config.apply` is a fourth, declarative complement to the three imperative commands above: instead of
telling the device what to do, it carries the whole desired-state document (policies/kiosk/location) plus
a content-addressed `revision`, and the server re-sends it only when the revision the device last applied
differs from the one it just computed. See `payloads/config-apply.schema.json` for the command payload and
`payloads/config-apply-result.schema.json` for the per-key outcome shape in the command result's `detail`.

### Configuration sections

`config-sections.json` lists the document's sections (its top-level keys other than `revision` and
`configurationId`), in the order agents apply them, one row each:

| field | meaning |
|-------|---------|
| `name` | the section's key in the document |
| `since` | the release whose agent first applies it (a release in `VERSIONING.md`) |
| `capability` | optional: the capability an agent advertises when it applies the section. An agent before 0.6.0 applies a section with `capability` when it advertises it, and one without when it runs `since` or later |
| `label` | the console's message id for the section's name |

The server sends a section with a `capability` only to an agent that advertises it, and every other section to every
agent. It never decides by the agent's version: leaving a section out can release it on the device (it does for kiosk).

A new section adds one row, with the release it ships in, and its wire change in that release's `VERSIONING.md` row.
Rows are never removed. A server plugin's policy keys (`policies.x.<pluginId>.<key>`) are part of `policies` and have
no row of their own.

## Transport

Commands are delivered in the `/checkin` response (see `endpoints.md`). To get them there quickly,
the agent holds a WebSocket wake channel, `/agent/ws/{deviceNumber}` (`AgentWakeEndpoint` on the
server, `TransportManager` in the agent): when a command is queued the server sends a small wake
signal and the agent checks in. The socket carries no commands. A periodic WorkManager check-in is
the fallback when it is down. The matrix is posted on the enrollment and check-in calls. Nothing here
assumes the wake channel is reliable; it is an optimization, the check-in loop is the source of truth.

## Capability tiers for remote control

`capabilities.remoteControl.tier`:
- `none` — no capture/inject available.
- `view` — MediaProjection screen capture only (per-session consent). Universal Tier 0.
- `control` — capture + AccessibilityService input injection. Universal Tier 1.
- (`oem` / `system` tiers are future; advertised via `capabilities.oem`.)
