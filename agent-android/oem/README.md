# :oem

Pluggable OEM-extension surface for vendor-privileged controls beyond AOSP DPM.

- `OemAdapter` — vendor-neutral interface; selected at startup by `isAvailable()`.
- `GenericOemAdapter` — default no-op (pure AOSP). Always available; the safe fallback.
- `KnoxAdapter` — **PARKED**. Carries no Knox SDK dependency (keeps the base APK
  lean and Play-Protect-friendly); `isAvailable()` returns false so it is never
  selected. Revive behind a `knox` build flavor when there is a concrete need.
