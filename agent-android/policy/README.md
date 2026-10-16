# :policy

The **capability-abstraction layer**. Every `DevicePolicyManager` call lives behind
an interface here; feature code never touches DPM directly.

## Shape

- `DeviceControl` — the narrow surface feature code may use. Exposes policy areas
  (currently `wifi`).
- Policies, one directory each: `wifi/`, `bluetooth/`, `camera/`, `usb/` (`usbStorage`),
  `screenshots/`, `factoryreset/` (`factoryReset`), `safeboot/` (`safeBoot`). These are the keys `CapabilityRegistry` can
  advertise.
- `PolicyStrategy` — base for SDK-gated implementations. `isSupported()` carries the
  `Build.VERSION.SDK_INT` (+ Device-Owner) check, evaluated once at selection.
- `TogglePolicy` — the sub-interface for every on/off policy (Wi-Fi, camera, Bluetooth,
  screenshots, USB storage, `factoryReset`, `safeBoot`, ...). `config.apply` and `policy.apply` route these
  generically by `capabilityKey`, so there is no per-policy `when` anywhere.
- `CapabilityRegistry` — probes each policy's factory and reports the supported
  capability keys (from `../../proto/registry.md`). Its output feeds
  `capabilities.policy` in the `CapabilityMatrix`.
- `UserRestrictions` — pure capability-key -> `DISALLOW_*` key mapping, kept free of Android types
  so the decision is unit-testable on the JVM. Strategies only apply the returned set.

## Worked example: Wi-Fi (`wifi/`)

The policy with more than one strategy, showing the whole pattern:

```
WifiPolicy (interface)
 ├─ ModernWifiPolicy   (API 30+: setConfiguredNetworksLockdownState + restriction)
 ├─ LegacyWifiPolicy   (API 24–29: user-restriction fallback)
 └─ WifiPolicyFactory  (picks the first isSupported() strategy)
```

`wifi` only locks or allows the Wi-Fi *settings*. Switching the radio is the separate `wifiRadio` toggle
(`WifiRadioPolicy`, protocol 1.4): `WifiManager.setWifiEnabled` (Device Owners are exempt from the API 29
restriction), deferred while Wi-Fi is the only validated non-VPN network, plus `DISALLOW_CHANGE_WIFI_STATE` on API 33+
(lifted again when the configuration stops managing the key).
Keeping them apart means a configuration stored with `wifi = false` never starts turning Wi-Fi off on upgrade.

Adding a new policy (mobileData, gps, ...) means: define its
interface, write its strategies, add a factory, and register the probe in
`CapabilityRegistry`. Nothing in `:core`/`:app` needs to know the SDK details.

For an on/off policy the shortest path is three files (`XPolicy`, `XStrategy`/`XRestrictionPolicy`,
`XPolicyFactory`) plus one probe line in `CapabilityRegistry`'s probe map (the `TogglePolicyResolver`) —
that single registration is enough for both the capability advertisement and the generic command routing.
Probes are resolved per call until a strategy is supported (Device Owner arrives partway through QR
provisioning, after the process started), and that instance is then kept. Consumers take
`registry::togglePolicies` and read it once per operation; never inject or store the resulting map.
