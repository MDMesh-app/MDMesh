#!/usr/bin/env bash
# Create a restorable native-install snapshot before a JDK/Tomcat application migration.
# This deliberately backs up MDMesh application state, not the PostgreSQL server binaries/data directory.
set -euo pipefail
umask 077

BASE_DIR="${MDMESH_BASE_DIR:-/opt/mdmesh}"
CATALINA="${MDMESH_CATALINA:-/opt/mdmesh-tc}"
DB_NAME="${MDMESH_DB_NAME:-mdmesh}"
DB_OS_USER="${MDMESH_DB_OS_USER:-postgres}"
SERVICE_UNIT="${MDMESH_SERVICE_UNIT:-mdmesh-server}"
OUTPUT=""
STOP_SERVICE=0

usage() {
  cat <<'EOF'
Usage: sudo install/backup-native-state.sh [--stop-service] [--output DIR]

Creates a timestamped, owner-only snapshot containing a PostgreSQL custom dump, uploaded files,
the native Tomcat runtime, and MDMesh/systemd configuration. --stop-service stops the MDMesh
systemd unit before copying mutable files. The PostgreSQL server version and data directory are
not copied or changed.
EOF
}

while [ "$#" -gt 0 ]; do
  case "$1" in
    --stop-service) STOP_SERVICE=1 ;;
    --output) shift; OUTPUT="${1:-}" ;;
    -h|--help) usage; exit 0 ;;
    *) echo "Unknown option: $1" >&2; usage >&2; exit 2 ;;
  esac
  shift
done

[ "$(id -u)" = 0 ] || { echo "Run as root (sudo)." >&2; exit 1; }
[ -d "$BASE_DIR" ] || { echo "MDMesh base directory does not exist: $BASE_DIR" >&2; exit 1; }
[ -d "$CATALINA" ] || { echo "Tomcat directory does not exist: $CATALINA" >&2; exit 1; }
command -v pg_dump >/dev/null || { echo "pg_dump is required." >&2; exit 1; }
command -v runuser >/dev/null || { echo "runuser is required to run pg_dump as $DB_OS_USER." >&2; exit 1; }

if [ "$STOP_SERVICE" = 1 ]; then
  if command -v systemctl >/dev/null && systemctl list-unit-files 2>/dev/null | grep -q "^${SERVICE_UNIT}\.service"; then
    systemctl stop "$SERVICE_UNIT"
  fi
fi

if command -v systemctl >/dev/null && systemctl is-active --quiet "$SERVICE_UNIT" 2>/dev/null; then
  echo "$SERVICE_UNIT is still running; stop it before snapshotting mutable state." >&2
  exit 1
fi
if pgrep -f "catalina.base=${CATALINA}" >/dev/null 2>&1; then
  echo "A Tomcat using $CATALINA is still running; stop it before snapshotting mutable state." >&2
  exit 1
fi

if [ -z "$OUTPUT" ]; then
  OUTPUT="$BASE_DIR/backups/native-pre-upgrade-$(date -u +%Y%m%dT%H%M%SZ)"
fi
[ ! -e "$OUTPUT" ] || { echo "Snapshot destination already exists: $OUTPUT" >&2; exit 1; }
install -d -m 700 "$OUTPUT"

runuser -u "$DB_OS_USER" -- pg_dump -Fc "$DB_NAME" > "$OUTPUT/database.dump"
chmod 600 "$OUTPUT/database.dump"

# Store paths relative to / so the restore guide can inspect/extract them without guessing names.
tar -C / --xattrs --numeric-owner -czf "$OUTPUT/files.tar.gz" "${BASE_DIR#/}/files"
tar -C / --xattrs --numeric-owner -czf "$OUTPUT/runtime.tar.gz" "${CATALINA#/}"

config_paths=()
for path in \
  "$CATALINA/conf/Catalina/localhost/ROOT.xml" \
  "$BASE_DIR/supervisor.env" \
  "/etc/systemd/system/${SERVICE_UNIT}.service" \
  "/etc/systemd/system/mdmesh-supervisor.service"; do
  [ -e "$path" ] && config_paths+=("${path#/}")
done
archive_names=(database.dump files.tar.gz runtime.tar.gz)
if [ "${#config_paths[@]}" -gt 0 ]; then
  tar -C / --xattrs --numeric-owner -czf "$OUTPUT/configuration.tar.gz" "${config_paths[@]}"
  archive_names+=(configuration.tar.gz)
fi

cat > "$OUTPUT/README.txt" <<EOF
MDMesh native migration snapshot
Created (UTC): $(date -u +%FT%TZ)
Database: $DB_NAME
Base directory: $BASE_DIR
Tomcat directory: $CATALINA

This snapshot is for rollback to the pre-upgrade runtime. Do not start both old and new
MDMesh runtimes against the same database/files. See docs/native-upgrade.md for restore steps.
EOF
(cd "$OUTPUT" && sha256sum "${archive_names[@]}" > SHA256SUMS)
chmod 600 "$OUTPUT"/*
printf '%s\n' "$OUTPUT"
