# What the container images contain

MDMesh publishes three images (`release.yml`). Besides MDMesh's own code, each holds a base image and packages that
MDMesh redistributes unmodified, under their own licences ([policy.md](policy.md) says why the allowlist doesn't apply
to them). Some of that software is under the GPL or the LGPL: NOTICE says where its source is, and has a written offer
for it.

**Each release records what its images hold.** The release workflow scans every pushed image with
[Syft](https://github.com/anchore/syft) and attaches the result to the GitHub Release as
`mdmesh-<image>-linux-<arch>.spdx.json` (SPDX JSON): every package with its exact version, and for distribution
packages the source package it was built from. That is what the source pointers in NOTICE resolve against:

- Alpine packages (mdmesh-web, mdmesh-supervisor): the recipe and patches in
  [aports](https://gitlab.alpinelinux.org/alpine/aports), on the branch of the image's Alpine release, and the source
  archives at <https://distfiles.alpinelinux.org/distfiles/>. GPL-3.0 among them: `bash` and `readline` in
  mdmesh-supervisor.
- Ubuntu packages (mdmesh-server): `https://launchpad.net/ubuntu/+source/<source package>/<version>`, or
  `apt-get source <source package>=<version>`. GPL-3.0 among them: bash, coreutils, tar, and others the SBOM names.
- The Temurin JDK: <https://github.com/adoptium/jdk17u>, at the tag of the version the SBOM names.

The base images are pinned by digest (tomcat, caddy) or are the Node LTS line (node), so the SBOM of a release is the
image as shipped.

`scripts/licenses/check-images.sh` (the licences workflow) fails when a `docker/*.Dockerfile` starts from an image or
installs a package this page doesn't name. Update the tables in the same pull request.

## mdmesh-server (`docker/server.Dockerfile`)

| Part | Licence |
|---|---|
| Base: `tomcat` (`tomcat:9.0.x-jdk17-temurin`, on Ubuntu) | Apache Tomcat: Apache-2.0. The Ubuntu packages: each under its own licence, many GPL or LGPL (bash, coreutils, glibc...); their copyright files are in `/usr/share/doc/<package>/copyright` |
| Eclipse Temurin JDK 17 (in the base) | GPL-2.0-only WITH Classpath-exception-2.0; its notices are in the JDK's `legal/` folder |
| MDMesh server (`/usr/local/tomcat/webapps/ROOT.war`) | Apache-2.0; `META-INF/THIRD-PARTY.txt` in the WAR lists its libraries |
| Build stage, not shipped: `maven` (`maven:3.9-eclipse-temurin-17`) | |

The Dockerfile installs no packages.

## mdmesh-web (`docker/web.Dockerfile`)

| Part | Licence |
|---|---|
| Base: `caddy` (`caddy:2.x-alpine`, on Alpine Linux) | Caddy: Apache-2.0. Alpine: BusyBox (GPL-2.0-only), musl (MIT), apk-tools (GPL-2.0-only), and the other base packages, each under its own licence |
| `libcap` | BSD-3-Clause OR GPL-2.0-only |
| `su-exec` | MIT |
| MDMesh console (`/srv`) | Apache-2.0; `/srv/licenses/THIRD-PARTY.json` lists its packages |
| Build stage, not shipped: `node` (`node:24-alpine`) | |

## mdmesh-supervisor (`docker/supervisor.Dockerfile`)

| Part | Licence |
|---|---|
| Base: `node` (`node:24-alpine`, on Alpine Linux) | Node.js: MIT. Alpine: as above |
| `minisign` | ISC |
| `docker-cli`, `docker-cli-compose` | Apache-2.0 |
| `postgresql-client` | PostgreSQL |
| `bash` (and `readline`, which it pulls in) | GPL-3.0-or-later |
| `curl` | curl |
| MDMesh supervisor (`/app`) | Apache-2.0; it has no npm dependencies |

`apk info -a <package>` inside an image shows a package's licence; Alpine's package sources are in
[aports](https://gitlab.alpinelinux.org/alpine/aports).
