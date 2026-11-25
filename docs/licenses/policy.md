# Licence policy

MDMesh is open source under the Apache License 2.0 ([LICENSE](../../LICENSE)), and derived from Headwind MDM
([NOTICE](../../NOTICE)). This page says which licences the third-party code MDMesh ships may be under, how the
builds check it, and what MDMesh has chosen where a component offers a choice.

## What it covers

The code MDMesh links or bundles into what it ships:

| Part | What the check reads | The list it ships |
|---|---|---|
| Server (`launcher.war`) | every third-party jar in `WEB-INF/lib` | `META-INF/THIRD-PARTY.txt` |
| Web console | every npm package in the built bundle, and the fonts its CSS loads | `/licenses/THIRD-PARTY.json` |
| Android agent | every library on the release runtime classpath | `assets/licenses/third-party.json` in the APK |

Each also ships `LICENSE` and `NOTICE` next to its list.

The container images also hold an operating system and runtimes that MDMesh doesn't link with: the Alpine or Ubuntu
packages, Tomcat, the JDK, Node and Caddy. They are shipped unmodified beside MDMesh, not combined with it, so they
keep their own licences and this allowlist doesn't apply to them. [images.md](images.md) lists what each image is built
from; each release attaches a package list per image, and NOTICE says where the source of the GPL and LGPL software among
them is, with a written offer for it.

## Allowed licences

[allowed.txt](allowed.txt) holds the list, as SPDX ids; all three builds read it.

- **Permissive:** Apache-2.0, MIT, MIT-0, BSD-2-Clause, BSD-3-Clause (the Eclipse Distribution License 1.0 is
  BSD-3-Clause), BSD-3-Clause-No-Nuclear-Warranty, ISC, 0BSD, Unlicense, CC0-1.0, and public-domain dedications
  (`LicenseRef-Public-Domain`: org.json and aopalliance in the server).
- **Fonts:** OFL-1.1.
- **Weak, file-level copyleft:** EPL-1.0, EPL-2.0, MPL-2.0, CDDL-1.0, CDDL-1.1. Allowed for libraries MDMesh ships
  unmodified, each in its own jar or package. Changing one of their files means publishing that file's source
  under its licence: don't, without asking first.

Everything else is refused, and in particular: GPL, LGPL and AGPL (with or without exceptions), SSPL, BUSL, FSL (so
Liquibase 5 and later), the Commons Clause, CC-BY-NC and CC-BY-ND, and a component with no licence at all.

## How the builds check it

| Build | Tool | Configured in |
|---|---|---|
| Server | [license-maven-plugin](https://www.mojohaus.org/license-maven-plugin/) `add-third-party` | `server/pom.xml`, `server/src/license/` |
| Web console | Vite's `build.license`, checked by `web/vite.licences.ts` with [spdx-satisfies](https://www.npmjs.com/package/spdx-satisfies) | `web/vite.config.ts` |
| Android agent | [Licensee](https://github.com/cashapp/licensee) | `agent-android/app/build.gradle.kts` |

Every build writes its list, and a component under a licence outside the list fails the build in CI (where the `CI`
variable is set, as GitHub Actions sets it). Elsewhere the server and console builds only report it, so building an
image or a native install never stops on licence policy; the agent's build fails anywhere.

The build tools pass a component offered under several licences when one of them is allowed (`(WTFPL OR MIT)`
passes on MIT, and a jar whose POM lists CDDL-1.1 and GPL-2.0 with the Classpath Exception passes on CDDL-1.1). So
MDMesh records which one it uses: the server in `server/src/license/override-THIRD-PARTY.properties` (then its list
names only that one), the console and the agent in [elections.tsv](elections.tsv).

In CI, `scripts/licenses/inventory.py` then reads each built artifact and fails when:

- the WAR, the console build or the APK lacks LICENSE, NOTICE or its list, or the WAR's list doesn't name exactly
  the third-party jars it holds;
- a listed component is under a licence `allowed.txt` doesn't list, or is offered under several with no recorded
  choice for exactly that offer. A new version of an elected jar whose POM still offers several licences fails here
  until its line is updated.

An expression that combines licences (`MIT AND Zlib`) always fails `inventory.py`, whatever it combines: a maintainer
has to read it. It also compares the lists with the inventory below, and only warns when they differ.

## When a build refuses a component

1. Use something else: another library, or a version under an allowed licence.
2. If the component offers an allowed licence the build didn't recognise (a licence name its POM spells in a new
   way, say), map it:
   - server: a `<licenseMerge>` in `server/pom.xml` from the POM's name to the SPDX id, or a line in
     `server/src/license/override-THIRD-PARTY.properties` for that one jar;
   - agent: `allowUrl(...)` or `allowDependency(...) { because("...") }` in the `licensee` block;
   - console: the package's own `license` field is read as an SPDX expression; fix it upstream, or choose another
     package.
3. If neither works, say so in the pull request: a maintainer decides, and records the decision here.

Never add a GPL-family licence to `allowed.txt`, or map one to an allowed licence.

## Choices made

Where a component is offered under several licences, MDMesh uses it under this one:

| Component | Offered under | MDMesh uses it under |
|---|---|---|
| Jersey 2.25.1, HK2 2.5 (api, locator, utils, aopalliance-repackaged, javax.inject), javax.ws.rs-api 2.0.1, JAXB 2.2.11 (api, core, impl), javax.annotation-api 1.2, mimepull | CDDL-1.1 or GPL-2.0 with the Classpath Exception | CDDL-1.1 |
| osgi-resource-locator 1.0.1 | CDDL-1.0 or GPL-2.0 with the Classpath Exception | CDDL-1.0 |
| HK2 guice-bridge 2.6.1, jakarta.inject 2.6.1 (HK2), Jakarta Messaging API 2.0.3 | EPL-2.0 or GPL-2.0 with the Classpath Exception | EPL-2.0 |
| Jakarta Mail 1.6.8 | EDL-1.0, EPL-2.0 or GPL-2.0 with the Classpath Exception | EDL-1.0 (BSD-3-Clause) |
| Javassist 3.20.0 | MPL-1.1, LGPL-2.1 or Apache-2.0 | Apache-2.0 |
| Reflections 0.10.2 | WTFPL or Apache-2.0 | Apache-2.0 |
| spark-md5 (console) | WTFPL or MIT | MIT |

The server's elections are keyed by version (`group--artifact--version`): a new version of one of these jars needs its
line updated. The build itself stays green (one allowed licence is enough for it), and `inventory.py` names the jar
and fails. Most are held back by `.github/dependabot.yml` until the move to Jakarta EE, which replaces them.

Other decisions:

- **SpotBugs annotations** (LGPL-2.1) came in with mybatis-guice, which needs them only to compile. The root
  `pom.xml` excludes them: nothing loads them at runtime.
- **JAXB 2.2.11 core and impl**: their POMs can't be read on JDK 9 and later, so license-maven-plugin leaves them out.
  `server/src/license/THIRD-PARTY.ftl` lists them by hand, and `inventory.py` fails if the WAR's jars and the list
  ever differ.
- **desugar_jdk_libs** (GPL-2.0 with the Classpath Exception) is compiled into the agent by core library desugaring,
  which Licensee doesn't see. The Classpath Exception allows that, and NOTICE names it and covers it with the source
  offer.

## The inventory

[inventory/](inventory/) has each part's components and licences, without versions: `server.tsv`, `web.tsv` and
`agent.tsv`. It changes when a component is added or removed or changes licence, not on version bumps, and it is how
a review sees a new component. CI warns when a build's components differ from it; update it with

```sh
scripts/licenses/inventory.py --write --war server/target/launcher.war --web web/dist \
  --apk agent-android/app/build/outputs/apk/debug/app-debug.apk
```

(it writes nothing when a check fails) after building the three (`mvn -DskipTests package`, `npm run build` in `web/`, `./gradlew :app:assembleDebug` in
`agent-android/`). A Dependabot pull request that brings in a new component shows the warning: review the component,
and update the inventory in the pull request, or in the next one.

## Code copied from elsewhere

No build sees source code copied into the repository. Don't copy code from a project under a licence this page
refuses. Code from an allowed licence keeps its copyright and licence header, and gets a line in NOTICE.
