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

## Transport

Commands ride the existing push channels (MQTT topic = device id, or HTTP long-poll) with a
sync/poll fallback — the agent reconciles on every check-in regardless of push. The matrix
is posted on the sync/enrollment call. Nothing here assumes push is reliable; push is an
optimization, the sync loop is the source of truth.

## Capability tiers for remote control

`capabilities.remoteControl.tier`:
- `none` — no capture/inject available.
- `view` — MediaProjection screen capture only (per-session consent). Universal Tier 0.
- `control` — capture + AccessibilityService input injection. Universal Tier 1.
- (`oem` / `system` tiers are future; advertised via `capabilities.oem`.)
