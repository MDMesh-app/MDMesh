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
manifest** and is merged into the final APK only through `:remote`, which `:app`
currently always includes. The base `:app` manifest never mentions accessibility. The component ships
`android:enabled="false"`. A later control feature may expose that component
after an authorized request, but Android still requires the local user to enable
the service in Settings; the app cannot grant or toggle Accessibility consent by
itself.

## What real remote control adds later

- **Screen capture:** `MediaProjection` (per-session user consent) -> encoder.
- **Transport/signaling:** WebRTC (`webrtc` transport) with a websocket signaling
  fallback; populates `capabilities.remoteControl.transport`.
- **Input injection:** `InputInjectionService.dispatchGesture(...)` driven by the
  WebRTC data channel after the local user has explicitly enabled the service in
  Android Settings. Session lifecycle only controls whether an already-enabled
  service accepts input; it does not grant Accessibility consent.

Tiers map to `proto/README.md`: `view` = capture only (Tier 0), `control` =
capture + injection (Tier 1).
