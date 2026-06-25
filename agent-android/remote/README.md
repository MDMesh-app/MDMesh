# :remote

Remote screen view + control. **The only module that references Accessibility.**

## Status: skeleton

| Type | State |
|------|-------|
| `RemoteControlSession` | interface defined; `StubRemoteControlSession` tracks state only |
| `RemoteControlTierDetector` | computes `none` / `view` / `control` from injected probes |
| `InputInjectionService` | `AccessibilityService` stub; `canPerformGestures` config present, dispatch deferred |

## Permission isolation (Play Protect)

Accessibility (`BIND_ACCESSIBILITY_SERVICE`, the accessibility `<service>` and its
`@xml/input_injection_service` config) is declared **only in this module's
manifest** and is merged into the final APK solely when `:remote` is included. The
base `:app` manifest never mentions accessibility. The service ships
`android:enabled="false"` and is toggled on only for an authorised control session.

## What real remote control adds later

- **Screen capture:** `MediaProjection` (per-session user consent) -> encoder.
- **Transport/signaling:** WebRTC (`webrtc` transport) with a websocket signaling
  fallback; populates `capabilities.remoteControl.transport`.
- **Input injection:** `InputInjectionService.dispatchGesture(...)` driven by the
  WebRTC data channel, enabling/disabling the accessibility service around the
  session lifecycle.

Tiers map to `proto/README.md`: `view` = capture only (Tier 0), `control` =
capture + injection (Tier 1).
