# What the container images contain

MDMesh publishes three images (`release.yml`). Besides MDMesh's own code, each holds a base image and packages that
MDMesh redistributes unmodified, under their own licences ([policy.md](policy.md) says why the allowlist doesn't apply
to them). Some of that software is under the GPL or the LGPL: NOTICE says where its source is, and has a written offer
for it.

**Each release records what its images hold.** The release workflow's `packages` job pulls every pushed image by its
digest and lists what it holds with the image's own package manager (`scripts/licenses/image-packages.sh`), and the
release attaches the list as `mdmesh-<image>-linux-<arch>.packages.tsv`: every OS package with its exact version, the
source package it was built from and, for Alpine, its licence; then the runtimes that don't come as packages (the JDK
and Tomcat, Node, Caddy). That is what the source pointers in NOTICE resolve against:

- Alpine packages (mdmesh-web, mdmesh-supervisor): the recipe and patches in
  [aports](https://gitlab.alpinelinux.org/alpine/aports), on the branch of the image's Alpine release, and the source
  archives at <https://distfiles.alpinelinux.org/distfiles/>. GPL-3.0 among them: `bash` and `readline` in
  mdmesh-supervisor.
- Ubuntu packages (mdmesh-server): `https://launchpad.net/ubuntu/+source/<source package>/<version>`, or
  `apt-get source <source package>=<version>`. GPL-3.0 among them: bash, coreutils, tar, and others the list names.
- The Temurin JDK: <https://github.com/adoptium/jdk17u>, at the tag of the version the list names.

The list is made from each pushed image itself, so it is the image as shipped, whether its base is pinned by digest
(tomcat) or named by a tag (caddy, node). The tables below are kept by hand: a pull request that changes what an image
is built from updates them.

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
