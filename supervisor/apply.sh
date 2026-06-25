#!/usr/bin/env bash
# apply.sh <target-version>
#
# Runs inside the supervisor container (working_dir /project, docker.sock mounted). Applies a VERIFIED
# update to the `server` + `caddy` services with a pre-update DB backup and AUTOMATIC ROLLBACK on any
# failure. Emits `PHASE <name>` / `ERR <msg>` / `OK <version>` lines on stdout — server.js parses these
# to drive /update/status. It NEVER touches `supervisor` or `postgres` (no self-destruct, no data loss).
#
# Sequence: backup → pull → recreate → healthcheck → done. Any failure → rollback (restore versions +
# DB, recreate old images, re-health-check) → rolled_back, or failed if rollback itself can't recover.
#
# NOT exercised in CI/sandbox (needs a live Docker daemon). Validate on a staging deploy.
set -uo pipefail   # deliberately NOT -e: failures are handled explicitly so we can roll back.

VERSION="${1:?usage: apply.sh <version>}"
PROJECT_DIR="${COMPOSE_PROJECT_DIR:-/project}"
ENV_FILE="$PROJECT_DIR/.env"
BACKUP_DIR="${BACKUP_DIR:-/backups}"
DB_USER="${DB_USER:-hmdm}"
DB_NAME="${DB_NAME:-hmdm}"
HEALTH_URL="${HEALTH_URL:-http://server:8080/rest/public/name}"
HEALTH_TIMEOUT="${HEALTH_TIMEOUT:-180}"

phase() { echo "PHASE $1"; }
errln() { echo "ERR $1" >&2; }
dc()    { docker compose "$@"; }

cd "$PROJECT_DIR" || { errln "cannot cd $PROJECT_DIR"; phase failed; exit 1; }

# --- .env helpers (the file is the bind-mounted host .env) ---
get_env() { grep -E "^$1=" "$ENV_FILE" 2>/dev/null | head -1 | cut -d= -f2-; }
set_env() {
  local k="$1" v="$2"
  if grep -qE "^$k=" "$ENV_FILE" 2>/dev/null; then
    sed -i "s|^${k}=.*|${k}=${v}|" "$ENV_FILE"
  else
    echo "${k}=${v}" >> "$ENV_FILE"
  fi
}

# --- health: poll the server's unauthenticated /public/name until 200 or timeout ---
healthy() {
  local deadline=$(( $(date +%s) + HEALTH_TIMEOUT ))
  while [ "$(date +%s)" -lt "$deadline" ]; do
    if curl -fsS -m 5 "$HEALTH_URL" >/dev/null 2>&1; then return 0; fi
    sleep 5
  done
  return 1
}

OLD_SERVER="$(get_env SERVER_VERSION)"
OLD_WEB="$(get_env WEB_VERSION)"
[ -n "$OLD_SERVER" ] || OLD_SERVER="${CURRENT_VERSION:-latest}"
[ -n "$OLD_WEB" ]    || OLD_WEB="$OLD_SERVER"

STAMP="$(date +%Y%m%d-%H%M%S)"
BACKUP_SQL="$BACKUP_DIR/$STAMP.sql"
BACKUP_ENV="$BACKUP_DIR/$STAMP.env"

# --- rollback: restore previous versions + DB, recreate, re-health-check ---
rollback() {
  phase rollback
  set_env SERVER_VERSION "$OLD_SERVER"
  set_env WEB_VERSION "$OLD_WEB"
  set_env CURRENT_VERSION "$OLD_SERVER"
  dc up -d --no-deps server caddy || errln "rollback recreate failed"
  if [ -s "$BACKUP_SQL" ]; then
    if ! dc exec -T postgres psql -U "$DB_USER" "$DB_NAME" < "$BACKUP_SQL" >/dev/null 2>&1; then
      errln "db restore reported errors (see $BACKUP_SQL)"
    fi
  fi
  if healthy; then phase rolled_back; else phase failed; fi
}

# ---------------- backup ----------------
phase backup
mkdir -p "$BACKUP_DIR"
{ echo "SERVER_VERSION=$OLD_SERVER"; echo "WEB_VERSION=$OLD_WEB"; } > "$BACKUP_ENV"
if ! dc exec -T postgres pg_dump --clean --if-exists -U "$DB_USER" "$DB_NAME" > "$BACKUP_SQL"; then
  errln "pg_dump failed — aborting before any change"
  rm -f "$BACKUP_SQL"
  phase failed
  exit 1
fi
echo "$STAMP" > "$BACKUP_DIR/latest"   # pointer the recovery page / rollback read

# ---------------- pull ----------------
phase pull
set_env SERVER_VERSION "$VERSION"
set_env WEB_VERSION "$VERSION"
set_env CURRENT_VERSION "$VERSION"
if ! dc pull server caddy; then
  errln "image pull failed"
  rollback
  exit 1
fi

# ---------------- recreate ----------------
phase recreate
if ! dc up -d --no-deps server caddy; then
  errln "recreate failed"
  rollback
  exit 1
fi

# ---------------- healthcheck ----------------
phase healthcheck
if healthy; then
  phase done
  echo "OK $VERSION"
  exit 0
fi
errln "health check failed after ${HEALTH_TIMEOUT}s"
rollback
exit 1
