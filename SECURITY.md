# Security policy

MDMesh is a device-management control plane: a vulnerability here can reach every enrolled device. Please report
security issues privately rather than in a public issue.

## Reporting

- Preferred: GitHub private vulnerability reporting on this repository (**Security → Report a vulnerability**).
- If that is not available to you, open an issue titled "Security contact request" with **no details** and a
  maintainer will reach out with a private channel.

Please include the affected component (server, web console, Android agent, supervisor, installer), the version or
commit, reproduction steps, and the impact you believe it has. You will get an acknowledgement within 7 days.

## Supported versions

Only the latest release line receives security fixes. Upgrading is a `git pull` + re-run of the installer, or a
supervisor apply on Docker deployments; the agent ↔ server wire contract is additive-only, so older agents keep
working against a patched server (see `docs/adr/0009-agent-v1-contract-stability.md`).

## Scope notes

- The agent is a Device Owner app. Anything that lets an unauthenticated caller queue a device command, read device
  telemetry, or enroll a device into another customer's configuration is in scope and high severity.
- Dependency advisories are tracked via Dependabot; routine bumps land on `main` without a report.
