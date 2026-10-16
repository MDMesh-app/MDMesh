<!-- The images in docs/readme/ are generated from the console build: see scripts/readme/README.md. -->
<div align="center">

<p align="center">
<picture>
  <source media="(prefers-color-scheme: dark)" srcset="docs/readme/banner-dark.svg">
  <img src="docs/readme/banner-light.svg" alt="MDMesh, self-hosted Android MDM" width="100%">
</picture>
</p>

**Self-hosted Android MDM, with a console that doesn't feel like 2012.**

[![Release](https://img.shields.io/github/v/release/MDMesh-app/MDMesh?sort=semver)](https://github.com/MDMesh-app/MDMesh/releases)
[![License](https://img.shields.io/badge/license-Apache--2.0-blue.svg)](LICENSE)
[![PRs welcome](https://img.shields.io/badge/PRs-welcome-brightgreen.svg)](CONTRIBUTING.md)
![Deploy](https://img.shields.io/badge/deploy-Docker%20%7C%20native-2496ed)

[Quick start](#-quick-start) · [Features](#-features) · [How it works](#-how-it-works) · [Install guide](DEPLOY.md) · [Architecture](#-architecture) · [Roadmap](#-roadmap)

<p align="center">
<picture>
  <source media="(prefers-color-scheme: dark)" srcset="docs/readme/hero-dark.webp">
  <img src="docs/readme/hero-light.webp" alt="The MDMesh console's Overview: fleet status, the devices that need attention, and sync per configuration" width="100%">
</picture>
</p>

</div>

MDMesh is an open-source Mobile Device Management platform for Android. Enroll devices as Device Owner with a QR
code, then manage them from a web console: push apps, lock devices into kiosk mode, track location and run remote
actions. The server and the agent update themselves from signed releases that roll back on failure.

### Why MDMesh

- **Open-source core, no paid edition.** Kiosk, app delivery, location and one-command deploy are all Apache-2.0.
- **Signed auto-updates with rollback.** Every release is signed. The server verifies it, backs up the database and
  rolls back if the update fails.
- **A from-scratch Device Owner agent.** Kotlin, capability-gated commands and a versioned protocol, so older agents
  keep working.
- **One-command deploy.** Docker Compose with auto-HTTPS or a Cloudflare Tunnel, or a native install on Debian and
  Ubuntu.

## ■ Features

<p align="center">
<picture>
  <source media="(prefers-color-scheme: dark)" srcset="docs/readme/features-dark.webp">
  <img src="docs/readme/features-light.webp" alt="Six console details: devices that need attention, sync per configuration, QR enrollment, tri-state settings with changes marked, remote actions, and a staged agent rollout" width="100%">
</picture>
</p>

- **Fleet:** live status, search and filters, with the devices that need attention first.
- **Configurations:** desired state for 22 settings, from kiosk to Wi-Fi, with per-setting sync status.
- **Kiosk:** single- or multi-app lock task, a themed launcher and a crash-loop guard.
- **Apps:** a library, custom APK uploads and F-Droid, installed silently.
- **Remote actions:** message, ring, lock, reboot, passcode reset and wipe, each gated on what the device supports.
- **Location:** passive or GPS tracking, with history on a map.
- **Staged rollouts:** new agent versions go to canary devices first, then to the fleet.

## ■ Quick start

On a Linux host with Docker, Docker Compose v2, `openssl` and `curl`:

```bash
bash <(curl -fsSL https://raw.githubusercontent.com/MDMesh-app/MDMesh/main/quickstart.sh)
```

From source, clone the repository and run `./setup.sh`. Without Docker, run `sudo ./setup.sh --native` on Debian
12/13 or Ubuntu 24.04 ([native install](DEPLOY.md#option-c--native-no-docker)). Each installer prints the console URL
and a temporary admin password. Then enroll a device:

1. Sign in and set your own password.
2. Go to **Enroll** and generate a QR code.
3. Factory-reset an Android device and tap the welcome screen six times to open the QR scanner.
4. Scan the code. The device enrolls as Device Owner and checks in.

Hosting modes, the native install and updates: **[DEPLOY.md](DEPLOY.md)**.

## ■ How it works

<p align="center">
<picture>
  <source media="(prefers-color-scheme: dark)" srcset="docs/readme/how-it-works-dark.svg">
  <img src="docs/readme/how-it-works-light.svg" alt="A configuration change goes from the console's Save and apply to an Android device, which applies it and reports back" width="85%">
</picture>
</p>

1. **You change a configuration.** Each edit is marked until you save and apply it.
2. **The server wakes the devices** on their wake channel and sends `config.apply`.
3. **Each agent applies it and reports back** setting by setting, so the console shows what applied, failed or isn't
   supported.

## ■ Architecture

<p align="center">
<picture>
  <source media="(prefers-color-scheme: dark)" srcset="docs/readme/architecture-dark.svg">
  <img src="docs/readme/architecture-light.svg" alt="Architecture: the console and the agents reach the server through the Caddy or Cloudflare Tunnel edge; the server keeps its state in PostgreSQL; the updater verifies minisign-signed releases from GitHub and applies them" width="100%">
</picture>
</p>

A Java (Jersey) server on PostgreSQL sits behind Caddy or a Cloudflare Tunnel and serves the React console. Agents
check in over REST and keep a WebSocket open so the server can wake them. A separate updater polls GitHub Releases,
verifies the minisign signature and applies the update, with a database backup and rollback. More in
[STRUCTURE.md](STRUCTURE.md).

## ■ Roadmap

- ✅ Console, Device Owner agent, kiosk, app delivery, location, signed updates, staged rollouts
- 🔜 Live remote control: screen view and input over WebRTC, with a self-hosted TURN server
- 🔭 An OEM-privileged tier (Knox, Zebra), behind the capability layer

## ■ Contributing

Code, docs, bug reports and ideas are all welcome. Start with **[CONTRIBUTING.md](CONTRIBUTING.md)**, then
[report a bug](https://github.com/MDMesh-app/MDMesh/issues/new?template=bug_report.yml) or
[suggest a feature](https://github.com/MDMesh-app/MDMesh/issues/new?template=feature_request.yml). Small, focused PRs
merge fastest. Security issues go to [SECURITY.md](SECURITY.md).

## ■ License & credits

MDMesh is licensed under the **[Apache License 2.0](LICENSE)**. It is a fork of **[Headwind MDM](https://h-mdm.com)**
by Headwind Solutions LLC, also Apache-2.0 ([why and how: ADR-0008](docs/adr/0008-licensing-and-rebrand.md)).
Trademarks and brand names belong to their owners.

<div align="center"><sub><a href="DEPLOY.md">Install</a> · <a href="docs/UPGRADING.md">Upgrading</a> · <a href="docs/OPERATIONS.md">Operations</a> · <a href="STRUCTURE.md">Structure</a> · <a href="docs/DEV.md">Development</a> · <a href="docs/agent-architecture.md">Agent internals</a> · <a href="RELEASING.md">Releasing</a></sub></div>
