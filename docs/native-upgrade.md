# Native JDK 21 / Tomcat 10 Upgrade and Recovery

This guide applies to a native MDMesh installation made by `install/install-native.sh`. It moves
the Java application runtime from the pre-Jakarta JDK 17/Tomcat 9 deployment to JDK 21/Tomcat
10.1. PostgreSQL is **not** upgraded as part of this procedure. The existing distro-provided
PostgreSQL server stays in place; the MDMesh application schema may advance through Liquibase when
the new server first starts.

## Before the maintenance window

1. Confirm the source installation is supported by the release notes and that its public base URL
   is the URL enrolled phones already use.
2. Confirm enough local storage exists for a PostgreSQL dump, a copy of `/opt/mdmesh/files`, and a
   copy of `/opt/mdmesh-tc`. Move the completed snapshot off-host before relying on it for disaster
   recovery.
3. Keep the public reverse-proxy hostname and TLS configuration unchanged. If using Nginx Proxy
   Manager, either retain its backend port or plan one proxy switch at cutover. Ensure WebSocket
   upgrades for `/agent/ws` remain enabled.
4. Do not run two MDMesh application instances against the production database or uploaded-file
   directory.

## Upgrade

Run the installer as root from the checked-out release:

```bash
cd /path/to/mdmesh
sudo BASE_URL=https://mdm.example.example HTTP_PORT=8080 ./install/install-native.sh
```

For an existing native installation, the installer first stops `mdmesh-server` and creates an
owner-only snapshot under `/opt/mdmesh/backups/native-pre-upgrade-<UTC timestamp>/`. It contains:

- `database.dump` — PostgreSQL custom-format dump;
- `files.tar.gz` — uploaded files, including any locally hosted agent APK;
- `runtime.tar.gz` — the prior Tomcat runtime;
- `configuration.tar.gz` — MDMesh context, supervisor, and systemd configuration when present;
- `SHA256SUMS` — integrity hashes.

The installer then provisions/selects JDK 21, uses a clean Tomcat 10.1 directory, deploys the
Jakarta WAR, and starts it. It preserves the old `hash.secret`; do not replace this value. The
database retains every enrolled device's per-device credential hash, so existing phones continue
to authenticate to agent-v1 and its WebSocket endpoint. Preserve a configured `jwt.secretkey` as
well if retaining administrator browser sessions matters; changing it does not invalidate phones,
but signs users out.

After startup, verify the web UI, an existing device check-in/command, and the WebSocket path. The
repository integration gate is:

```bash
MDMESH_AGENT_V1_E2E=1 scripts/local-tomcat-postgres-integration.sh
```

It uses disposable state; it supplements rather than replaces an existing-device production check.

## Recovery after a failed rollout

Do not deploy the old WAR/Tomcat 9 against a database that the new release has used. Restore the
complete pre-upgrade snapshot instead:

1. Stop `mdmesh-server` and `mdmesh-supervisor`.
2. Move the failed `/opt/mdmesh-tc` and `/opt/mdmesh/files` aside; do not delete them until recovery
   is verified.
3. Extract `runtime.tar.gz`, `files.tar.gz`, and `configuration.tar.gz` at `/` with root ownership.
4. Recreate/empty the `mdmesh` database and restore `database.dump` with the local PostgreSQL tools.
5. Run `systemctl daemon-reload`, start the restored `mdmesh-server` service, and verify the old
   public URL plus an existing device check-in.

For example, with `SNAPSHOT` set to the selected snapshot directory:

```bash
sudo systemctl stop mdmesh-server mdmesh-supervisor
sudo tar -C / -xzf "$SNAPSHOT/runtime.tar.gz"
sudo tar -C / -xzf "$SNAPSHOT/files.tar.gz"
sudo tar -C / -xzf "$SNAPSHOT/configuration.tar.gz"
sudo -u postgres dropdb mdmesh
sudo -u postgres createdb -O mdmesh mdmesh
sudo -u postgres pg_restore -d mdmesh "$SNAPSHOT/database.dump"
sudo systemctl daemon-reload
sudo systemctl start mdmesh-server
```

Adjust the role/database names if the source instance used non-default names. Test these recovery
commands on a sanitized copy before using a release in production. A direct downgrade is unsupported
because Liquibase changes are forward-only unless a separately tested rollback changelog exists.
