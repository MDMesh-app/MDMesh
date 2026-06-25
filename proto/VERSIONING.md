# Protocol versioning

`protocolVersion` is `MAJOR.MINOR` (semver-ish).

- **MINOR bump** = additive, backward-compatible: new optional fields, new capability keys,
  new command types, new enum members in open registries. Old peers ignore what they don't
  know. No coordinated deploy needed.
- **MAJOR bump** = breaking: removing/renaming a required field, changing a field's meaning,
  removing a command type. Requires a migration window where the server supports both majors.

## Compatibility rules (enforced, not aspirational)

1. **Device ignores unknown command `type`** → replies `status: "unsupported"`. Never crashes.
2. **Server ignores unknown fields** in capability matrices and results.
3. **Server gates on capability**: never sends a command whose `requiresCapability` is missing
   from the device's latest matrix. A device that can't do something simply never advertises it.
4. **Both sides tolerate a peer one MAJOR behind** during migration windows.

## Why this shape

This is the concrete form of the "evolvable / easy-to-migrate" requirement. Because Android
forces a permanent version treadmill (each OS release removes/limits an API), the agent's set
of real capabilities changes over time and across the fleet. Encoding capabilities in the
protocol — rather than assuming a fixed feature set — means a 3-year-old agent on Android 9
and a fresh agent on Android 16 talk to the same server without special-casing.
