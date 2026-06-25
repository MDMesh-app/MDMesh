# Agent v1 HTTP endpoints

Base path `/rest/public/agent/v1` (public filter chain). All bodies are JSON wrapped in the
server's `Response{status,message,data}` envelope on the response side. Requests are the raw
objects below. Mirrors the schemas in this directory.

The server treats `capabilities`, command `type`, and command `payload` as **opaque** — it
stores/forwards them and gates only on capability *tokens* (below). No command-type enum lives
on the server; new command types need zero server changes.

## Capability tokens (the gating key space)

Both sides flatten a `CapabilityMatrix.capabilities` object into a flat `Set<String>` of tokens:

| Source | Token form | Example |
|--------|-----------|---------|
| `policy[]` entry | `policy.<key>` | `policy.wifi`, `policy.camera`, `policy.kioskLockTask` |
| `appManagement[]` entry | `app.<key>` | `app.silentInstall` |
| `remoteControl.tier` (if not `none`) | `remote.<tier>` | `remote.view`, `remote.control` |
| `oem.knox` (if true) | `oem.knox` | `oem.knox` |

A `CommandEnvelope.requiresCapability` (when present) MUST be one of these tokens. The server
sends a command to a device only if `requiresCapability` is null or ∈ the device's latest token
set. This is the entire gate — dynamic and type-agnostic.

## POST /enroll

Token-gated. Creates (or re-attaches) the device and returns the server-issued id.

Request (`agent-enroll-request.schema.json`):
```json
{
  "protocolVersion": "1.0",
  "enrollToken": "<single-use server-issued token>",
  "agent": { "version": "0.1.0", "package": "com.mdmesh.agent" },
  "device": { "androidSdkInt": 34, "androidRelease": "14", "manufacturer": "samsung", "model": "SM-X510", "isDeviceOwner": true },
  "capabilities": { "policy": ["wifi","camera"], "appManagement": [], "remoteControl": {"tier":"none"}, "oem": {"vendor":"samsung","knox":false} }
}
```
Response `data` (`agent-enroll-response.schema.json`):
```json
{ "protocolVersion": "1.0", "deviceId": "<server-issued opaque id>", "configurationName": "default", "deviceSecret": "<per-device secret, returned once>" }
```
The server mints a per-device secret, stores only its SHA-256 hash, and returns the plaintext
**once** here. The agent persists it and presents it on every `/checkin`.
Errors: `Response.ERROR("error.agent.token.invalid")`, `...token.used`, `...token.expired`.

## POST /checkin

Authenticated by `Authorization: Bearer <deviceSecret>` (the secret from `/enroll`); the
server verifies it against the stored hash for `deviceId` **before any state change** and
rejects mismatches with `error.agent.unauthorized`. Posts the current capability matrix +
results of previously-received commands; receives the next batch of capability-gated commands.

Request (`agent-checkin-request.schema.json`):
```json
{
  "protocolVersion": "1.0",
  "deviceId": "<server-issued opaque id>",
  "capabilities": { "policy": ["wifi","camera"], "appManagement": [], "remoteControl": {"tier":"none"}, "oem": {"vendor":"samsung","knox":false} },
  "results": [ { "protocolVersion":"1.0","commandId":"...","status":"done","completedAt":"..." } ]
}
```
Response `data` (`agent-checkin-response.schema.json`):
```json
{ "protocolVersion": "1.0", "commands": [ { "protocolVersion":"1.0","commandId":"...","type":"policy.apply","issuedAt":"...","requiresCapability":"policy.wifi","payload":{"policy":"wifi","value":false} } ] }
```

`results` acks let the server mark queued commands `done`/`failed`/`unsupported`. `capabilities`
is re-posted every check-in (cheap, keeps the token set fresh as the OS/agent changes).

## Auth posture (v1)

- `/enroll` is gated by the single-use `enrollToken` (minted by an authenticated admin via a
  private endpoint; carried into the device through the QR provisioning bundle). No global shared
  secret baked into the APK (ADR 0002 / 0005).
- `/checkin` is authenticated by a per-device secret (`Authorization: Bearer <deviceSecret>`),
  minted at `/enroll`, stored server-side only as a SHA-256 hash, verified before any state
  change. Bare `deviceId` is not sufficient — this prevents IDOR / device spoofing.
