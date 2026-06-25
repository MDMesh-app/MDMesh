# ADR 0005 — Play-Protect permission-minimization policy

**Status:** Accepted (2026-06-21)

## Context

The loudest, worsening signal in hmdm's 2026 issue tracker is **Google Play Protect blocking
sideloaded DPCs** (`#36`, `#44`, `#46`). hmdm had to *remove* its Accessibility service and
`READ_SMS` permission (`e8638fdb7`) purely to dodge Play Protect heuristics. Broad permissions
(`QUERY_ALL_PACKAGES`, `READ_SMS`, always-on Accessibility) are exactly what trips it.

## Decision

The **base agent ships the minimum permission surface** and looks benign:
- No `READ_SMS`, no `QUERY_ALL_PACKAGES` in the base agent (use scoped `<queries>` instead).
- **Accessibility is not in the base agent.** Input-injection for remote control lives in the
  `:remote` module and is only present/enabled when remote-control is provisioned — ideally as
  a separate, user-consented capability, not a default.
- Every permission must be justified in code review against "does this trip Play Protect?".
- Privileged operations lean on **Device Owner** APIs (supported, benign-looking) rather than
  consumer-app workarounds.

## Consequences

- (+) Lower chance of Play Protect flagging; smaller attack surface.
- (+) Modular permissions: deployments that don't use remote control never ship its perms.
- (−) Some features (full input injection) require an explicit extra step/module rather than
  being always-on. Accepted trade-off.
- This is a living constraint: re-evaluate the permission list every Android release.
