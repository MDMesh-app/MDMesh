# Java Server Modernization Plan

**Status**: Tasks 4–6 are implemented and verified through Tomcat 10.1 on JDK 21; fresh native-install and JDK 25 CI verification remain pending

**Scope**: The Java control plane only: `common`, `jwt`, `notification`, `plugins`, `swagger/ui`, `server`, Java build tooling, server CI, Docker, and native deployment. The Kotlin Android Device Owner agent, its Android SDK/build, permissions, and policy behaviour are explicitly out of scope. Existing-agent compatibility is a release gate.

## Target

| Item | Minimum supported | Preferred / CI-tested |
| --- | --- | --- |
| JDK | OpenJDK 21 | OpenJDK 25 |
| Servlet container | Apache Tomcat 10.1 | Apache Tomcat 10.1 current reviewed patch |
| Application API line | Jakarta EE 10 (`jakarta.*`) | Jakarta EE 10 (`jakarta.*`) |
| Java bytecode/API target | `--release 21` | Built on JDK 21 and JDK 25 |

Compiling on JDK 25 with `--release 21` keeps the stated minimum truthful. Do not raise the minimum merely because the build host is newer. Pin container image digests or explicit patch versions in a separate maintenance policy; never leave production on an unreviewed moving Java/Tomcat tag.

## Non-goals

- Rewriting the service in Spring Boot, Quarkus, or another framework.
- Replacing Tomcat with Jetty, Undertow, or an embedded server in this migration.
- Changing the public REST API, database schema semantics, `/agent/v1` protocol, or Android agent.
- Combining unrelated dependency refreshes with Jakarta changes.

The server reads deployment settings from Tomcat `Context` XML and registers an agent wake WebSocket endpoint through the servlet container. Replacing or embedding Tomcat is viable only after this plan succeeds and configuration has been made application-owned.

## Test Floor And Rules

The current CI runs only DB-free `common` tests on JDK 17 and packages the rest with tests skipped. The following floor must be established before production deployment changes begin.

### Required checks for every Java modernization pull request

1. `mvn -B -ntp verify` passes on JDK 21 and JDK 25. No module may be packaged with `-DskipTests` in the modernization CI workflow.
2. Tests are added or extended for every changed behaviour. A refactor that changes only imports still requires the affected existing tests to run; a previously untested behaviour gets a focused regression test in the same pull request.
3. The Tomcat 10 integration smoke suite passes on both JDKs when a change affects servlet, REST, WebSocket, persistence, configuration, packaging, Docker, or native deployment.
4. `scripts/agent-v1-e2e.sh` passes against the built server before a release candidate. Its additive-only contract remains unchanged unless a separately approved protocol version is introduced.
5. Dependency changes include a dependency-tree review and a short note of deliberately retained legacy libraries or exclusions.

### Minimum test suites to establish

| Suite | Required assertions | Execution target |
| --- | --- | --- |
| Unit | Existing `common` tests plus new focused tests for config parsing, REST resource/service logic, and serialization changed by this work | Maven `test`/`verify`; JDK 21 and 25 |
| WAR/container smoke | WAR deploys to stock Tomcat 10.1; Liquibase completes against disposable PostgreSQL; unauthenticated/public route and authenticated admin route respond; uploads/files path is writable | CI container job; JDK 21 and 25 |
| Agent protocol integration | Enrollment/token issue, authenticated check-in, capability-gated command, acknowledgement, and response JSON compatibility | Existing `scripts/agent-v1-e2e.sh`, promoted into CI |
| WebSocket integration | `/agent/ws/{deviceNumber}` upgrades, accepts a registered device, and a queued wake signal reaches it | New automated test before `javax.websocket` becomes `jakarta.websocket` |

This uses behavioural gates rather than an arbitrary repository-wide coverage percentage: current coverage is uneven and a percentage would encourage testing unrelated lines. Add JaCoCo reporting during Task 0, record the baseline, and require each modernization pull request not to reduce it. Adopt numeric module thresholds only after the baseline is visible and maintainable.

### Baseline record — 2026-09-22

- JDK 17/Maven 3.9.9 full-reactor `./mvnw -B -ntp verify`: **passed** — 16 modules built, 42 unit tests passed, and `server/target/launcher.war` was produced.
- JaCoCo recorded 1,329 of 24,608 instructions covered (5.40%). Current tests exercise only `common`; this is a measurement, not a quality threshold.
- **Superseded by Task 3 implementation:** JDK 21 compilation previously failed in Lombok 1.18.20 with `NoSuchFieldError` on `JCTree$JCImport`. Lombok 1.18.40 is the selected JDK 21/25-compatible replacement; Task 3 verification records the result below.
- The non-Docker Step 1 harness passed on WSL: JDK 17 whole-reactor build (42 tests), a newly initialized PostgreSQL 17 cluster, stock Tomcat 9.0.113, Liquibase completion, deterministic seed data, public file serving, admin login, and authenticated REST. It leaves no running service or test database on success.
- Docker/Tomcat/PostgreSQL integration passed through the dedicated rootless Docker Engine. This network TLS-inspects `jitpack.io` with an organizational CA trusted by WSL but not by the stock Maven image; the opt-in `docker-compose.dev.tls.yml` BuildKit-secret path supplied a locally managed root-CA PEM only while Maven packaged the WAR, then removed the temporary trust entry before the build stage finalized. The resulting Compose stack passed PostgreSQL health, Liquibase/server startup, default-admin login, authenticated device-summary REST, and writable file serving. Its host ports are configurable as `MDMESH_DEV_POSTGRES_PORT` and `MDMESH_DEV_HTTP_PORT` to coexist with the WSL PostgreSQL service.
- **Task 3 verification — 2026-09-23:** The full 16-module reactor and its 42 existing tests pass under the locally installed OpenJDK 21 using `--release 21`. The JDK 21 Docker build and disposable PostgreSQL/Tomcat 9 agent/WebSocket suite also pass. During that deployment check, Jersey 2.25 failed to scan Java 21 class files; Jersey 2.48 fixes the scanner while retaining the `javax.ws.rs` API line. The workflow now schedules the same build and agent-contract integration jobs on JDK 21 and 25. JDK 25 is not installed locally and its CI result must be observed after branch publication.
- **Task 4/required Task 5 companion verification — 2026-09-23:** Jersey, Guice, MyBatis-Guice, Swagger, Mail, JAXB, Servlet, Validation, JAX-RS, JMS, and WebSocket coordinates now use their Jakarta-compatible lines. The application had 114 server-side Java files importing Java EE APIs, so dependency-only Task 4 could not compile; the plan's permitted Task 4/5 compatibility branch was used to migrate those source imports and `web.xml` together. `./mvnw -q -B -ntp -U -Dmaven.repo.local=/tmp/mdmesh-jakarta-m2 verify` passed on OpenJDK 21, including three new audit servlet-wrapper tests. A focused dependency-tree review finds no `javax.servlet`, `javax.ws.rs`, or `javax.websocket` API. It deliberately retains `javax.annotation-api` through ActiveMQ 5.19.7, an old non-servlet annotation dependency that needs a separate messaging-library review. Tomcat 9 cannot execute the Jakarta WAR, so the deployed integration gate moves to Task 6's Tomcat 10.1 fixture.
- **Task 6 verification — 2026-09-23:** The disposable harness now downloads checksum-pinned Apache Tomcat 10.1.60 and passed on OpenJDK 21 with PostgreSQL: Liquibase, seeded login, authenticated REST, public file serving, and all 18 agent-v1/WebSocket checks. This runtime check exposed a missing Jersey 3 injection provider; adding the matching `jersey-hk2` dependency fixed the `InjectionManagerFactory not found` startup failure. The rootless Docker Compose image is pinned to `tomcat:10.1.60-jdk21-temurin`; its build and the same seeded 18-check contract passed on alternate host ports using the opt-in BuildKit CA secret. Containers, network, and volume were removed afterwards. The native installer now pins and verifies Tomcat 10.1.60 and replaces an existing Tomcat 9 installation, but a fresh native install remains untested to avoid modifying this workstation.

## Pull-request-sized Tasks

Each task has its own branch/worktree, one coherent purpose, and independent verification. Later tasks may not be started until their predecessor has merged or is available as an explicit temporary dependency branch.

### 0. Reproducible baseline and CI inventory

**Purpose**: Turn the existing JDK 17 / Tomcat 9 state into a documented comparison point.

**Changes**:

- Add a Maven Wrapper and pin its Maven version.
- Make server CI run the whole reactor's existing tests, not only `common`.
- Add JaCoCo report generation without an initially enforced percentage gate.
- Document exact local commands, required PostgreSQL/Docker prerequisites, and known test gaps in `docs/DEV.md`.

**Tests**: Existing unit suite succeeds on the current baseline; WAR still packages; CI uploads test and JaCoCo reports.

**Done**: Maven Wrapper 3.9.9, whole-reactor JDK 17 CI, JaCoCo report artifacts, and local build instructions are in place. The baseline command passed locally as recorded above; the remaining Docker-backed integration gap is deliberately Task 1.

### 1. Disposable database and Tomcat integration harness

**Purpose**: Establish the integration floor before changing framework APIs.

**Changes**:

- Add a repository-owned shell integration harness using disposable PostgreSQL and stock Tomcat 9 only for this baseline task. It intentionally exercises the deployed-WAR lifecycle outside Maven; Docker/Compose verifies the deployment image separately. Step 2 promotes the relevant harnesses into CI with agent-protocol coverage.
- Load only deterministic minimum seed data; secrets and generated files stay under the test temp directory.
- Add startup/readiness handling that observes Liquibase completion rather than sleep-based waits.
- Add an opt-in BuildKit-secret path for locally managed TLS-inspection root CAs so the real
  Docker/Compose build can run without weakening certificate verification. It must accept a PEM
  from outside the repository, affect only the Maven build stage, and leave the final runtime
  image unchanged.

**Tests**: WAR deployment, Liquibase schema creation, login/authenticated REST access, and file-storage smoke test.

**Done when**: The integration suite can fail for a broken WAR, migration, or database configuration—not merely for a process that fails to start.

**Done**: The non-Docker harness and the rootless Docker/Compose smoke suite both passed on the JDK 17/Tomcat 9 baseline. The Docker run used a BuildKit CA secret on a TLS-inspecting network and verified PostgreSQL readiness, server/Liquibase startup, login, authenticated REST, and the writable file route.

### 2. Protect existing device-facing contracts

**Purpose**: Lock down behaviour that an already-enrolled Android agent depends on.

**Changes**:

- Promote `scripts/agent-v1-e2e.sh` into CI against the Task 1 disposable environment.
- Convert important response-shape checks into JUnit integration tests where practical, retaining the script as a readable diagnostic.
- Add a WebSocket test for `/agent/ws/{deviceNumber}` and wake fan-out using a real container connection; do not mock the servlet WebSocket container.

**Tests**: Enrollment, token minting, capability-gated check-in, command acknowledgement, and WebSocket wake delivery.

**Done when**: A failure in legacy servlet/WebSocket wiring or the agent-v1 JSON contract is caught automatically before the Tomcat 10 migration begins.

**Done**: `scripts/agent-v1-e2e.sh` now opens a real authenticated WebSocket before queueing a
command and verifies the command wake signal. The existing JSON contract tests now parse and assert
the response fields rather than relying on substring matches. The `agent-contract-integration` CI
job runs the disposable deployed-WAR harness with this suite enabled. Local Docker verification
passed all 18 HTTP/WebSocket checks; the GitHub Actions execution remains to be observed after the
branch is pushed.

### 3. JDK 21/25 build-tool readiness

**Purpose**: Make source compilation and tests reliable on target JDKs without changing the servlet API line yet.

**Changes**:

- Replace Lombok 1.18.20 with a release that supports JDK 25; update annotation-processing configuration if needed.
- Update Maven compiler, Surefire/Failsafe, WAR, resource, clean, and other plugins that block JDK 21/25 or make test execution inconsistent.
- Upgrade Jersey within the still-`javax.ws.rs` 2.x line when its bundled bytecode scanner cannot
  read Java 21/25 classes. This is a compatibility bridge only; the Jersey 3/Jakarta API migration
  remains Task 4.
- Change source/target settings to `maven.compiler.release=21`; do not retain Java 8 bytecode claims after this task.
- Run JDK 21 and 25 CI while retaining Tomcat 9 only as a temporary test fixture. Move the temporary Docker/native Tomcat 9 runtime to JDK 21 because Java 21 bytecode cannot run on JDK 17; Tomcat 10.1 remains Task 6.

**Tests**: Full unit suite and Tasks 1–2 integration suites on both JDKs; dependency-tree review for duplicate Java EE APIs and conflicting Jackson versions.

**Done when**: The complete server reactor builds and all established tests pass on both JDKs, with JDK 21 documented as the minimum.

**Done locally / CI pending**: Maven compiler target, encoding, build plugins, Lombok, the temporary Tomcat 9 JDK, and CI matrix now use the JDK 21 minimum. The standalone Swagger UI module has explicit current resource/Surefire plugins because it does not inherit the root build. The complete reactor and Docker agent-contract suite pass on JDK 21. The JDK 25 matrix is configured but remains unobserved until this branch is pushed; do not claim Task 3 fully complete until then.

### 4. Jakarta dependency migration

**Purpose**: Move dependency coordinates and framework versions onto a coherent Jakarta EE 10 line before mechanically changing all application imports.

**Changes**:

- Upgrade Jersey 2.x to a compatible Jersey 3.1.x Jakarta EE 10 release, including Guice/HK2 bridge and multipart/Jackson components.
- Replace Javax Mail, JAXB, Activation, Servlet, JAX-RS, Validation, and WebSocket API artifacts with compatible Jakarta equivalents.
- Replace Swagger 1.x integration with Jakarta-compatible Swagger Core v3 artifacts; preserve the existing documentation endpoint or explicitly document a migration.
- Remove duplicate/transitive Javax APIs and capture a reviewed dependency tree.

**Tests**: Unit serialization/resource tests expanded for every upgraded framework boundary; Tasks 1–2 suites continue to pass on the temporary pre-Tomcat-10 fixture only if technically possible. Otherwise this task and Task 5 land together in a short-lived compatibility branch, but remain separate commits and reviews.

**Done when**: Maven resolves no runtime `javax.servlet`, `javax.ws.rs`, or `javax.websocket` APIs for the modernized server path, and every selected Jakarta dependency has a tested purpose.

**Implemented and JDK 21 runtime-verified**: A dependency-only update was not compilable because the server source still imported Java EE APIs. The permitted Task 4/5 compatibility branch therefore updates dependencies, source namespaces, and the deployment descriptor together, while retaining separate reviewable commits. The JDK 21 unit suite, dependency-tree review, Tomcat 10.1 deployed-WAR suite, and Docker Compose contract pass. ActiveMQ 5.19.7 still contributes `javax.annotation-api`; it is documented as an intentional, non-servlet legacy dependency pending a dedicated messaging upgrade.

### 5. Application namespace and deployment-descriptor migration

**Purpose**: Convert the application from Java EE to Jakarta EE without changing business/API semantics.

**Changes**:

- Convert application imports and fully qualified references from `javax.*` to `jakarta.*` across all Java modules, including servlet listeners, filters, JAX-RS resources, validation, and mail.
- Update `web.xml` to a current Jakarta Servlet schema/version and change Jersey initialization parameters where required.
- Update programmatic WebSocket bootstrap to the Jakarta server-container attribute/API.
- Address framework-specific incompatibilities as focused follow-up commits, not broad rewrites.

**Tests**: All Tasks 0–2 tests, plus explicit tests for login/session, multipart upload/download, JSON serialization, OpenAPI output, and WebSocket connection/wake delivery.

**Done when**: The WAR runs on stock Tomcat 10.1 and no production source imports `javax.*` (excluding intentionally retained non-Jakarta third-party package names documented in review).

**Implemented and JDK 21 runtime-verified**: All production Java source in the server scope now uses `jakarta.*` for JAX-RS, Servlet, WebSocket, Injection, Validation, Mail, and JMS; `web.xml` uses the Servlet 6.0 Jakarta schema and Jersey's Jakarta application parameter. Servlet stream wrappers implement the Servlet 6 non-blocking methods, with focused tests. The deployed WAR and agent WebSocket wake contract pass on stock Tomcat 10.1.

### 6. Tomcat 10.1 deployment migration

**Purpose**: Make Docker and native installation use the distribution-compatible deployment floor.

**Changes**:

- Change Docker build/runtime images to JDK 21 and Tomcat 10.1 using explicit reviewed versions; add a JDK 25 build/test variant rather than silently making it production-only.
- Replace the native installer's Tomcat 9 download with Tomcat 10.1 and revise generated configuration from a clean Tomcat 10.1 baseline. Do not copy an old Tomcat configuration forward.
- Revalidate `ROOT.xml`, rewrite handling, startup signalling, ownership, and systemd lifecycle.
- Update deployment documentation with the JDK 21 minimum, JDK 25 preference, and upgrade/rollback requirements.

**Tests**: Docker integration suite on JDK 21 and JDK 25; a fresh native install in a disposable Debian 13 or Ubuntu 24.04 container; upgrade smoke test from a backed-up pre-migration database.

**Done when**: Fresh Docker and native deployments reach ready state on Tomcat 10.1, agent-v1 and WebSocket suites pass, and no deployment script downloads or invokes Tomcat 9.

**Implemented and partially verified**: Docker, the disposable harness, native installer, deployment documentation, and CI harness now target Tomcat 10.1.60 on JDK 21. The native installer verifies the downloaded archive and replaces old Tomcat installations rather than attempting to reuse a Tomcat 9 directory. The JDK 21 Tomcat 10.1 harness and Docker Compose image pass the full agent-v1/WebSocket contract. Still required: fresh native install and upgrade smoke in a disposable Debian 13 or Ubuntu 24.04 container, plus the configured JDK 25 CI matrix.

### 7. Release qualification and operational handoff

**Purpose**: Prove an upgrade is safe for an existing self-hosted instance.

**Changes**:

- Publish an administrator migration guide with backup, supported source versions, dry-run, rollback, and recovery instructions.
- Add CI artifacts: WAR checksum/SBOM, test reports, dependency tree, and container image metadata.
- Exercise upgrade and rollback against a sanitized representative database and uploaded-file volume; preserve Android agent binary and agent-v1 protocol unchanged.

**Tests**: Fresh install, upgrade, restart, rollback, and full API/agent/WebSocket suite on both JDK 21 and 25.

**Done when**: A maintainer can reproduce the release candidate, upgrade a realistic test installation, and recover from a failed rollout with documented commands.

## Follow-on Architecture Work

After Task 7, extract Tomcat `Context` parameters into a typed application configuration layer. Only then evaluate an executable embedded Tomcat or Jetty deployment. That effort must retain this integration suite and be proposed as a new plan; it is not a justification to widen this migration.
