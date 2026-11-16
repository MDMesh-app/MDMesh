# Remote Screen View POC

## Purpose and status

This branch is a deliberately constrained proof of concept for an authorized
MDMesh console user to view one consenting Android device on the same routed
LAN. It is **view-only**: it has no input injection, audio, recording,
screenshot retention, file transfer, unattended access, reconnect, or
Internet traversal.

The core path has been demonstrated on a Galaxy S8 (Android 9) and Firefox:
the device user accepts Android's screen-share consent prompt, the browser
receives a live screen stream, and neither server nor database carries media.
The work is not production-ready. See [Important design constraints](#important-design-constraints)
and [Next phase](#next-phase).

## What the POC implements

```text
console user ── authenticated REST ──> MDMesh server ── agent command ──> device
     │                                      │                                │
     │                              signaling only                    Android consent
     │                                      │                                │
     └──────────── direct WebRTC video: selected host <-> host pair ─────────┘
```

- Durable session and append-only audit records, with a dedicated
  `remote_view` permission.
- A one-time, device-bound agent signaling credential. The database stores its
  hash in the session record.
- Separate authenticated WebSocket roles for agent and browser. The relay
  permits bounded offer/answer/candidate envelopes only and does not persist
  SDP, ICE candidates, or media.
- A device-side Android `MediaProjection` consent request and foreground
  capture service. API 28 uses the compatible foreground-service path; API 29+
  uses the `mediaProjection` service type.
- Video-only WebRTC. The agent is offerer and the browser has an explicit
  receive-only video transceiver.
- A console modal with terminal/denial text, an End view control, media stats,
  and selected candidate-pair diagnostics.
- Browser transport deliberately has no STUN or TURN servers. The console
  accepts only a selected `host <-> host` pair and ends a non-host path.

## Why the implementation evolved

The initial implementation intentionally used small, isolated experiments
because the S8/API-28 WebRTC stack behaved differently from a modern browser
reference flow. The experiments found and corrected several real integration
issues:

1. Android WebRTC operations needed one serialized executor; callback-thread
   teardown could crash the native stack.
2. The S8 needed explicit default video encoder/decoder factories before a
   video sender gathered usable ICE candidates.
3. Agent-as-offerer plus an explicit browser receive-only transceiver avoids
   an Android-9 native crash while applying a browser-originated offer.
4. The agent must use a named media stream / Unified Plan sender path.
5. Complete SDP after a bounded ICE gathering window is more reliable on this
   device than relying on candidate callbacks alone.
6. `Restrictions -> Screenshots -> Block` calls Android's screen-capture
   restriction and intentionally creates black MediaProjection frames. This is
   expected policy behavior, not a codec or kiosk bug.

The retained debug-only probes make MediaProjection and platform failures
observable during POC development. They are not part of the release APK. The
main capture path retains bounded lifecycle and failure logging; the temporary
global native WebRTC INFO logger was removed because it can expose network
details.

## Validation performed

| Area | Result |
| --- | --- |
| Android consent over kiosk | S8 prompt displayed and was accepted/denied locally. |
| Direct projection | Works with Screenshots set to Allow; black frames with it set to Block. |
| LAN media | Browser receives and decodes frames with zero observed drops. |
| Browser stop | Ends agent session and local status updates. |
| Device denial | Durable terminal state is returned; console explains denial. |
| Browser cleanup | Remote terminal close explicitly closes the browser peer. |
| Host-only enforcement | Automated parser tests pass; final live display of selected host pair remains a required acceptance check. |
| Server install | Repeated native deployments preserved the test database and passed health checks. |

Relevant automated checks already run include focused Java signaling/session
tests, Android `:remote:test`, `:app:testDebugUnitTest`, Detekt and debug
assembly on the Android build host, and console Vitest/typecheck/production
build checks. The full required CI gate remains necessary before any PR.

## Important design constraints

### Consent and user awareness

Device Owner does not bypass `MediaProjection` consent. The person holding the
device approves every view session.

The POC's kiosk-visible status is captured in the shared image and has no local
End view control. Android 9 lacks the newer system capture indicator, and the
foreground notification is not reliably visible when kiosk suppresses SystemUI.
A production-ready local awareness/end-control design is required; do not add a
normal overlay without considering that MediaProjection captures it.

### Screenshot policy

Remote viewing must remain device-specific. A configuration shared by several
devices must not be changed merely to let one device stream. A future policy
needs to report that a device is ineligible when its screenshot restriction is
blocked, or provide a narrow, audited per-device exception.

### Secret delivery

The POC's stored session record keeps only a SHA-256 hash. However, the raw
short-lived join credential currently appears in the queued agent command until
delivery. That is acceptable only for this POC and must be replaced before a
production feature.

## Remaining POC acceptance checks

The following must be exercised and recorded before calling the POC complete:

1. Final live viewer status reports `ICE path: host <-> host` after the
   host-only browser deployment.
2. Consent timeout, explicit device stop, device network loss, browser logout,
   server restart, configuration application, and device lock/screen timeout
   each end the session without reconnecting.
3. Inspect `remoteViewSession` and `remoteViewSessionAudit` rows for requester,
   device, state, timestamps, and reason; confirm they contain no SDP, ICE,
   frames, or raw credentials.
4. During an active session, inspect Android notification state to determine
   whether the foreground notification is created but hidden by kiosk/SystemUI.

## Next phase

The next phase is a **controlled LAN pilot**, still view-only and
consent-required. It should include:

1. Complete and automate the remaining acceptance matrix.
2. Add an explicit per-device remote-view eligibility state; do not mutate a
   shared configuration to enable a session.
3. Design a non-obscuring local active/ended indication and a local end action
   usable from kiosk.
4. Replace persisted raw command credential delivery with a delivery-time-only
   or server-key-protected design, with database/log regression tests.
5. Add a feature flag and documented prerequisites/failure states.
6. Test the S8/API-28 baseline and at least one current Android release.

Still excluded: TURN/STUN, public Internet traversal, remote input or
Accessibility, audio, recording, screenshots, files, multi-viewer use,
unattended access, automatic reconnect, and persistent screen-on behavior.

## Source map

| Area | Main code |
| --- | --- |
| Agent contract | `agent-android/proto`, `agent-android/core` |
| Capture and WebRTC | `agent-android/remote` |
| Server session and relay | `common/.../RemoteViewSession*`, `server/.../remote`, `server/.../RemoteView*` |
| Database | `server/src/main/resources/liquibase/db.changelog.xml` |
| Console | `web/src/api/remoteView.ts`, `web/src/pages/RemoteViewDialog.tsx` |

