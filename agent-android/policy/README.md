# :policy

The **capability-abstraction layer**. Every `DevicePolicyManager` call lives behind
an interface here; feature code never touches DPM directly.

## Shape

- `DeviceControl` — the narrow surface feature code may use. Exposes policy areas
  (currently `wifi`).
- `PolicyStrategy` — base for SDK-gated implementations. `isSupported()` carries the
  `Build.VERSION.SDK_INT` (+ Device-Owner) check, evaluated once at selection.
- `CapabilityRegistry` — probes each policy's factory and reports the supported
  capability keys (from `../../proto/registry.md`). Its output feeds
  `capabilities.policy` in the `CapabilityMatrix`.

## Worked example: Wi-Fi (`wifi/`)

The one fully end-to-end policy in the scaffold, showing the whole pattern:

```
WifiPolicy (interface)
 ├─ ModernWifiPolicy   (API 30+: setConfiguredNetworksLockdownState + restriction)
 ├─ LegacyWifiPolicy   (API 24–29: user-restriction fallback)
 └─ WifiPolicyFactory  (picks the first isSupported() strategy)
```

Adding a new policy (bluetooth, camera, kioskLockTask, ...) means: define its
interface, write its strategies, add a factory, and register the probe in
`CapabilityRegistry`. Nothing in `:core`/`:app` needs to know the SDK details.
