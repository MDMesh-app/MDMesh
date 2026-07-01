#!/usr/bin/env bash
# rollback.sh — restore the most recent pre-update backup (the .env version snapshot + DB dump that
# apply.sh wrote) and recreate `server` + `caddy`. The break-glass recovery action. Emits PHASE/ERR/OK
# lines for server.js, mirroring apply.sh. Never touches `supervisor` or `postgres` containers.
set -uo pipefail

PROJECT_DIR="${COMPOSE_PROJECT_DIR:-/project}"
ENV_FILE="$PROJECT_DIR/.env"
BACKUP_DIR="${BACKUP_DIR:-/backups}"
DB_USER="${DB_USER:-mdmesh}"
DB_NAME="${DB_NAME:-mdmesh}"
HEALTH_URL="${HEALTH_URL:-http://server:8080/rest/public/name}"
HEALTH_TIMEOUT="${HEALTH_TIMEOUT:-180}"

phase() { echo "PHASE $1"; }
errln() { echo "ERR $1" >&2; }
dc()    { docker compose "$@"; }

cd "$PROJECT_DIR" || { errln "cannot cd $PROJECT_DIR"; phase failed; exit 1; }

set_env() {
  local k="$1" v="$2"
  if grep -qE "^$k=" "$ENV_FILE" 2>/dev/null; then
    sed -i "s|^${k}=.*|${k}=${v}|" "$ENV_FILE"
  else
    echo "${k}=${v}" >> "$ENV_FILE"
  fi
}

healthy() {
  local deadline=$(( $(date +%s) + HEALTH_TIMEOUT ))
  while [ "$(date +%s)" -lt "$deadline" ]; do
    if curl -fsS -m 5 "$HEALTH_URL" >/dev/null 2>&1; then return 0; fi
    sleep 5
  done
  return 1
}

phase rollback
STAMP="$(cat "$BACKUP_DIR/latest" 2>/dev/null)"
if [ -z "$STAMP" ]; then errln "no backup recorded to roll back to"; phase failed; exit 1; fi

ENV_SNAP="$BACKUP_DIR/$STAMP.env"
SQL_SNAP="$BACKUP_DIR/$STAMP.sql"

# Restore the previous image versions.
if [ -f "$ENV_SNAP" ]; then
  OLD_SERVER="$(grep -E '^SERVER_VERSION=' "$ENV_SNAP" | head -1 | cut -d= -f2-)"
  OLD_WEB="$(grep -E '^WEB_VERSION=' "$ENV_SNAP" | head -1 | cut -d= -f2-)"
  [ -n "$OLD_SERVER" ] && { set_env SERVER_VERSION "$OLD_SERVER"; set_env CURRENT_VERSION "$OLD_SERVER"; }
  [ -n "$OLD_WEB" ] && set_env WEB_VERSION "$OLD_WEB"
else
  errln "version snapshot $ENV_SNAP missing — recreating current images"
fi

dc up -d --no-deps server caddy || errln "recreate failed"

# Restore the database dump (plain SQL with --clean; self-resets to the old schema).
if [ -s "$SQL_SNAP" ]; then
  if ! dc exec -T postgres psql -U "$DB_USER" "$DB_NAME" < "$SQL_SNAP" >/dev/null 2>&1; then
    errln "db restore reported errors (see $SQL_SNAP)"
  fi
else
  errln "db dump $SQL_SNAP missing/empty — restored versions only"
fi

if healthy; then phase rolled_back; echo "OK"; exit 0; fi
errln "still unhealthy after rollback (${HEALTH_TIMEOUT}s) — manual intervention needed"
phase failed
exit 1
