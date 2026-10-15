#!/usr/bin/env bash
# rollback.sh — restore the most recent pre-update backup (the .env version snapshot + DB dump that
# apply.sh wrote): stop `server`, restore the DB, recreate `server` + `caddy` on the old versions, health-check.
# The break-glass recovery action. Emits PHASE/ERR/OK lines for server.js, mirroring apply.sh, and exits non-zero on
# any failure. Never touches `supervisor` or `postgres` containers. Data written since the update is discarded.
set -uo pipefail
# Everything these scripts create is private: the DB dump holds password hashes, and the .env snapshot, the pointer
# and restore.log sit beside it. (The host .env is only rewritten in place: sed -i and >> keep its mode and owner.)
umask 077

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

# --- restore_db <dump> <log>: put the database back to the pre-update dump. Returns non-zero after ERR lines. ---
# The dump is plain SQL from `pg_dump --clean --if-exists`: it drops and recreates every object. So:
#  1. the server is STOPPED first: it holds pooled connections whose locks would block (or race) the drops, and
#     whatever runs next must not see a half-restored schema. caddy keeps running, so /recovery stays up;
#  2. ONE psql session, ONE transaction (--single-transaction wraps the -c's and the -f in a single BEGIN/COMMIT, in
#     order): first a bounded lock_timeout, then every other session on the database is ended (a stray client or a
#     stuck backend holding a lock), then the dump. Same session, so no gap for a new lock to slip in between;
#  3. the bound (60s) holds for the whole dump: its preamble says `SET lock_timeout = 0;`, which would cancel an earlier SET,
#     so that one line (the preamble's, never data) is rewritten on the way in. A lock that cannot be ended (e.g. a prepared transaction) is then a loud
#     "lock timeout" failure instead of a restore that hangs forever;
#  4. -o /dev/null: result rows (the dump's setval()s) are dropped, so restore.log holds only notices (the number of
#     sessions ended) and errors;
#  5. ON_ERROR_STOP=1: any error aborts the transaction with a non-zero exit and the database is left exactly as it
#     was (without it, psql exits 0 on SQL errors and a failed restore went unreported).
# The caller switches .env to the backup's versions only AFTER this succeeds, so on any failure .env still names the
# version the database belongs to (a later `docker compose up` never pairs old images with the new database). On a
# failed restore the server is left stopped on purpose. The operator retries Roll back (same backup) once the cause is
# fixed; docs/UPGRADING.md ("Restoring by hand") has the by-hand restore.
restore_db() {
  local sql="$1" log="$2" running lt=60s   # the lock bound: a constant, never taken from the environment (it goes into SQL)
  running="$(grep -E '^CURRENT_VERSION=' "$ENV_FILE" 2>/dev/null | head -1 | cut -d= -f2-)"
  if ! dc stop server; then
    errln "could not stop the server, so the database was NOT restored and nothing was changed: .env still names ${running:-the running version}, which may still be running (a failed recreate can leave it stopped). Fix the cause (see the log above), then Roll back again from /recovery."
    return 1
  fi
  # Rewrite the preamble's `SET lock_timeout = 0;` (first match only, and only while still in the preamble: blank,
  # `--`, `\` meta-command, SET and set_config lines). A COPY row or function-body line with the same text is data.
  if ! awk -v q="'" -v lt="$lt" '
         !body && $0 == "SET lock_timeout = 0;" { print "SET lock_timeout = " q lt q ";"; body = 1; next }
         !body && !($0 == "" || /^--/ || /^\\/ || /^SET / || /^SELECT pg_catalog\.set_config\(/) { body = 1 }
         { print }' "$sql" \
     | dc exec -T postgres psql -X -q -v ON_ERROR_STOP=1 --single-transaction -U "$DB_USER" -d "$DB_NAME" -o /dev/null \
         -c "SET lock_timeout = '$lt'" \
         -c "DO \$restore\$ DECLARE n int; BEGIN SELECT count(pg_terminate_backend(pid)) INTO n FROM pg_stat_activity WHERE datname = current_database() AND pid <> pg_backend_pid(); RAISE NOTICE 'ended % other session(s)', n; END \$restore\$" \
         -f - > "$log" 2>&1; then
    # The psql text goes to the supervisor log as a plain line (and stays in $log); the ERR line reaches the public
    # /update/status, so it only points at the file.
    echo "psql: $(grep -m1 -E 'ERROR|FATAL' "$log" || tail -n1 "$log")" >&2
    errln "database restore failed: see $log"
    errln "the server is stopped (on purpose: the old version must not run on a database it was not restored for); the database is unchanged (the restore runs in one transaction); .env still names ${running:-the running version}, the version this database belongs to; caddy and /recovery are up. Fix the cause (full psql output: docker compose exec supervisor cat $log), then Roll back again from /recovery; to restore by hand see docs/UPGRADING.md (Restoring by hand)."
    return 1
  fi
}

phase rollback
STAMP="$(cat "$BACKUP_DIR/latest" 2>/dev/null)"
if [ -z "$STAMP" ]; then errln "no backup recorded to roll back to"; phase failed; exit 1; fi

ENV_SNAP="$BACKUP_DIR/$STAMP.env"
SQL_SNAP="$BACKUP_DIR/$STAMP.sql"

# The previous image versions, from the snapshot. Written to .env only after the database restore succeeded.
OLD_SERVER="" OLD_WEB="" OLD_CURRENT=""
if [ -f "$ENV_SNAP" ]; then
  OLD_SERVER="$(grep -E '^SERVER_VERSION=' "$ENV_SNAP" | head -1 | cut -d= -f2-)"
  OLD_WEB="$(grep -E '^WEB_VERSION=' "$ENV_SNAP" | head -1 | cut -d= -f2-)"
  # Snapshots from before CURRENT_VERSION was recorded fall back to the server tag, as they always did.
  OLD_CURRENT="$(grep -E '^CURRENT_VERSION=' "$ENV_SNAP" | head -1 | cut -d= -f2-)"
  [ -n "$OLD_CURRENT" ] || OLD_CURRENT="$OLD_SERVER"
else
  errln "version snapshot $ENV_SNAP missing — recreating current images"
fi

# Restore the database dump with the server stopped; .env keeps naming the running version until that succeeded.
if [ -s "$SQL_SNAP" ]; then
  restore_db "$SQL_SNAP" "$BACKUP_DIR/$STAMP.restore.log" || { phase failed; exit 1; }
else
  errln "db dump $SQL_SNAP missing/empty — restored versions only"
fi

[ -n "$OLD_SERVER" ] && set_env SERVER_VERSION "$OLD_SERVER"
[ -n "$OLD_CURRENT" ] && set_env CURRENT_VERSION "$OLD_CURRENT"
[ -n "$OLD_WEB" ] && set_env WEB_VERSION "$OLD_WEB"

if ! dc up -d --no-deps server caddy; then
  errln "could not start the old server (see the log above); .env names the old versions and the database is restored: run 'docker compose up -d server caddy' in the install directory, or Roll back again."
  phase failed
  exit 1
fi

if healthy; then phase rolled_back; echo "OK ${OLD_CURRENT:-}"; exit 0; fi
errln "still unhealthy after rollback (${HEALTH_TIMEOUT}s) — manual intervention needed"
phase failed
exit 1
