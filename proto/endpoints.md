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
| `policy[]` entry | `policy.<key>` | `policy.wifi`, `policy.camera`, `policy.factoryReset` |
| `appManagement[]` entry | `app.<key>` | `app.silentInstall` |
| `device[]` entry | `device.<key>` | `device.lock`, `device.wipe`, `device.configApply` |
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
  "enrollToken": "<server-issued enrollment token>",
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

`enrollAttempt` (optional, protocol 1.9): a random id (16-128 of `A-Z a-z 0-9 _ -`) the agent generates and stores
before its first enroll request, sends again on every retry of that enrollment, and deletes once its first check-in
succeeds. When a response is lost, the retry returns the device the first request created instead of
`error.agent.token.used` or a second device from a batch code, with the same secret: a device enrolled with an attempt
gets a secret derived from it (HMAC-SHA256 keyed with a server-only key, over a version label, the device number and
the attempt id), so every answer for one attempt carries the same credential, and a late duplicate of the request
can't replace a secret the agent already saved. The key is 32 random bytes the server makes on first use and keeps in
its database (table `agentEnrollmentKey`); it is in no setting. This works until that device's first check-in and for
24 hours, and not for a stopped token (checked under the token's lock, so a stop that commits while a retry waits is
seen). The server keeps only SHA-256(token id ":" attempt) and SHA-256(secret). Older servers ignore the field.

`resumeOnly` (optional, protocol 1.9): `true` on the agent's credential recovery. The server then only resumes the
device the attempt already made (not checked in yet, within the window); otherwise it answers
`error.agent.enrollment.notResumable` and creates nothing, taking no use of the code. The enroll response carries
`resumeSupported: true` from servers that do this; the agent recovers only against those. Recovery: if the server
refuses the saved secret (`error.agent.unauthorized`) before the first check-in succeeds, the agent sends a
resume-only enroll with the same token and attempt, once per refused check-in and at most 3 times per run, and saves
what it gets. A final answer (`notResumable`, `token.used`, `token.invalid`) stops recovering: the agent drops the
attempt and its status screen says the device has to be enrolled again (a persistent state, cleared by a successful
enrollment). A retryable answer (`inProgress`, `failed`) or a network failure keeps the attempt for the next refused
check-in.

Each enrollment is one database transaction that first locks its token's row, so enrollments with one token run one
after another: a retry sent while its first request still runs waits for it, then resumes it. The token is claimed
before the customer's device limit is checked, so a used-up, stopped or expired token always gets its final error.
When a limit applies, the customer's devices are counted under a transaction-scoped advisory lock: device inserts
through the console, on-demand creation and enrollment all count under it, so concurrent adds through them can't
together pass the limit (they wait for each other, by design). No row is locked: other writes, and sign-ins, don't wait
for it. A new customer's default devices are capped at its limit when it sets one.

Errors: `Response.ERROR("error.agent.token.invalid")`, `...token.used`, `...token.expired` and
`...enrollment.disabled` are final for that token: retrying cannot succeed. These are retryable, and the agent keeps
trying:
- `...enrollment.failed`: the server failed and rolled the enrollment back (no device kept, no use spent);
- `...enrollment.inProgress`: the enrollment couldn't get its locks in time (15 s: the token's, held by another
  enrollment with it, typically the request this one retries; the customer's, held while another device is added;
  or a row a console transaction held), or 8 enrollments with that token are already running on that server instance;
  nothing was kept, and the next retry resumes it or goes through;
- `...enrollment.notResumable` is final for a resume-only request (nothing of that attempt to resume);
- `...enrollment.deviceLimit`: on an install with several customers, the token's customer has as many devices as its
  limit allows (as in the console: none for a master customer, 0 = no limit, a negative limit blocks); the device
  enrolls once there is room.

## POST /checkin

Authenticated by `Authorization: Bearer <deviceSecret>` (the secret from `/enroll`); the
server verifies it against the stored hash for `deviceId` **before any state change** and
rejects mismatches with `error.agent.unauthorized` (a body without `deviceId` gets `error.agent.device.unknown`). Posts the current capability matrix +
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

`installIntegrity: "required"` (protocol 1.3) says every `app.install` this server queues carries the APK's
`sha256`; the agent keeps the last value and, while it is set, refuses an `app.install` without one. An older server
omits it and the agent installs as before.

`results` acks let the server mark queued commands `done`/`failed`/`unsupported`. `capabilities`
is re-posted every check-in (cheap, keeps the token set fresh as the OS/agent changes).

`resultsAccepted` (protocol 1.3) is how many of the request's `results`, counted from the first, the server took (it
takes at most 1000 per check-in). The agent drops those and keeps the rest for its next check-in. Absent (an older
server) means all of them were taken.

An agent runs its commands inside a check-in, one check-in at a time, and reports each result in a later check-in. So
when a check-in arrives without the result of a `config.apply` delivered before it, and the server would send the
configuration again (the device's applied revision differs from the current one, or its agent version changed), the
server takes it that the agent stopped mid-apply (Android ended it, a restart, an update): it records that `config.apply` as `expired` and sends the current configuration in the same response. A
result that arrives later still counts. Agents must keep this property: never send a check-in while a delivered
`config.apply` is still running.

## Auth posture (v1)

- `/enroll` is gated by the `enrollToken` (minted by an authenticated admin via a private
  endpoint; carried into the device through the QR provisioning bundle). Each token enrolls up to
  its `maxUses` devices (1 by default: single use) before it expires (24 h by default, 30 days at
  most); every enroll uses one, atomically. An admin can stop every token of the customer at once
  (`POST /rest/private/agent/v1/token/revoke-all`); a stopped token is answered `error.agent.token.used`.
  Tokens are stored as their SHA-256 hash. No global shared secret baked into the APK (ADR 0002 /
  0005).
- `/checkin` is authenticated by a per-device secret (`Authorization: Bearer <deviceSecret>`),
  minted at `/enroll`, stored server-side only as a SHA-256 hash, verified before any state
  change. Bare `deviceId` is not sufficient — this prevents IDOR / device spoofing.
