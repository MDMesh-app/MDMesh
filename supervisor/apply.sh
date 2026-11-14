#!/usr/bin/env bash
# apply.sh <target-version> <server-ref> <web-ref> <supervisor-ref>
#
# The refs are the signed manifest's components.images, ghcr.io/<owner>/mdmesh-<name>@sha256:<digest> (server.js passes
# them after verifying the manifest). The images are pinned by digest: .env gets
# SERVER_VERSION/WEB_VERSION=<version>@<digest>, so compose's ghcr.io/${IMAGE_OWNER}/mdmesh-server:${SERVER_VERSION}
# becomes repo:tag@digest, which docker pulls and runs by the digest alone (the tag is ignored). A release whose refs are
# missing, malformed or under another owner than IMAGE_OWNER is refused before anything changes.
#
# This is the copy baked into the RUNNING supervisor image (server.js runs its own /app/apply.sh), so an update is
# applied by the supervisor already installed, never by the release being installed: a supervisor before 0.5.0 applies
# by tag as it always did, and pinning starts once a 0.5.0+ supervisor runs.
#
# Runs inside the supervisor container (project mounted at /project, docker.sock mounted). Applies a VERIFIED
# update to the `server` + `caddy` services with a pre-update DB backup and AUTOMATIC ROLLBACK on any
# failure. Emits `PHASE <name>` / `ERR <msg>` / `OK <version>` lines on stdout — server.js parses these
# to drive /update/status. It NEVER touches `supervisor` or `postgres` (no self-destruct, no data loss).
#
# Sequence: check the pins + compose → backup → pull (+ digest check) → recreate → healthcheck → done (+ pin the supervisor).
# A failed pull or digest check → rollback of .env only (no container changed yet). A later failure → rollback (restore
# versions, stop the server, restore the DB, recreate the old images, re-health-check) → rolled_back, or failed
# (non-zero, with ERR lines saying what state it is in) if the rollback itself can't recover.
#
# supervisor/test.js drives it with a stub docker (order, exit codes, messages). tests/rollback-e2e runs the update and
# the rollback for real (docker:dind, postgres:14): across a schema change, from v0.4.0 and from main, with a restore
# that fails at its very end and a backup cut short (both must leave the database as it was).
set -uo pipefail   # deliberately NOT -e: failures are handled explicitly so we can roll back.
# Everything these scripts create is private: the DB dump holds password hashes, and the .env snapshot, the pointer
# and restore.log sit beside it. (The host .env is only rewritten in place: sed -i and >> keep its mode and owner.)
umask 077

VERSION="${1:?usage: apply.sh <version> <server-ref> <web-ref> <supervisor-ref>}"
SERVER_REF="${2:-}"
WEB_REF="${3:-}"
SUPERVISOR_REF="${4:-}"
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

# --- restore_db <dump> <log>: put the database back to the pre-update dump. Returns non-zero after ERR lines. ---
# The dump is plain SQL from `pg_dump` of the database before the update. So:
#  1. the server is STOPPED first: it holds pooled connections whose locks would block (or race) the drops, and
#     whatever runs next must not see a half-restored schema. caddy keeps running, so /recovery stays up;
#  2. ONE psql session, ONE transaction, in order: BEGIN, a bounded lock_timeout, every other session on the database
#     ended (a stray client or a stuck backend holding a lock), the schema reset, the dump, COMMIT. Same session, so no
#     gap for a new lock to slip in between. The COMMIT is the last line of the stream, and only when the whole dump
#     went in: awk adds it after the dump's closing `-- PostgreSQL database dump complete` line. A dump cut short gets
#     a RAISE instead (a loud failure), and a stream that just stops (awk failed) ends the session without a COMMIT,
#     which rolls back (pipefail then fails the step). Never --single-transaction: it commits whatever arrived, and
#     after the reset that would be an emptied database;
#  2b. the schema reset: `public` is dropped with everything in it and made again, empty, with the same owner and
#     grants, so the dump restores into a clean schema (its own --clean DROPs then find nothing). The DROPs alone
#     only know the objects from before the update: one the update added that depends on them (0.5.0's foreign key
#     to configurations) made them fail, and the whole restore with it. The role must own `public`: on the quick
#     start's postgres it is the bootstrap user (DB_USER), on PostgreSQL 15+ the database owner (pg_database_owner);
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
  local reset="DO \$reset\$ DECLARE o regrole; a aclitem[]; g record; BEGIN SELECT nspowner::regrole, nspacl INTO o, a FROM pg_namespace WHERE nspname = 'public'; DROP SCHEMA public CASCADE; EXECUTE format('CREATE SCHEMA public AUTHORIZATION %s', o); FOR g IN SELECT grantee, privilege_type FROM aclexplode(a) WHERE grantee <> o LOOP EXECUTE format('GRANT %s ON SCHEMA public TO %s', g.privilege_type, CASE g.grantee WHEN 0 THEN 'PUBLIC' ELSE g.grantee::regrole::text END); END LOOP; END \$reset\$"
  running="$(grep -E '^CURRENT_VERSION=' "$ENV_FILE" 2>/dev/null | head -1 | cut -d= -f2-)"
  if ! dc stop server; then
    errln "could not stop the server, so the database was NOT restored and nothing was changed: .env still names ${running:-the running version}, which may still be running (a failed recreate can leave it stopped). Fix the cause (see the log above), then Roll back again from /recovery."
    return 1
  fi
  # Rewrite the preamble's `SET lock_timeout = 0;` (first match only, and only while still in the preamble: blank,
  # `--`, `\` meta-command, SET and set_config lines). A COPY row or function-body line with the same text is data.
  # Then COMMIT, or the RAISE for a dump cut short (see 2).
  if ! awk -v q="'" -v lt="$lt" '
         !body && $0 == "SET lock_timeout = 0;" { print "SET lock_timeout = " q lt q ";"; body = 1; next }
         !body && !($0 == "" || /^--/ || /^\\/ || /^SET / || /^SELECT pg_catalog\.set_config\(/) { body = 1 }
         $0 == "-- PostgreSQL database dump complete" { complete = 1 }
         { print }
         END { if (complete) print "COMMIT;"
               else print "DO $x$ BEGIN RAISE EXCEPTION " q "the backup is incomplete (it does not end like a pg_dump)" q "; END $x$;" }' "$sql" \
     | dc exec -T postgres psql -X -q -v ON_ERROR_STOP=1 -U "$DB_USER" -d "$DB_NAME" -o /dev/null \
         -c BEGIN -c "SET lock_timeout = '$lt'" \
         -c "DO \$restore\$ DECLARE n int; BEGIN SELECT count(pg_terminate_backend(pid)) INTO n FROM pg_stat_activity WHERE datname = current_database() AND pid <> pg_backend_pid(); RAISE NOTICE 'ended % other session(s)', n; END \$restore\$" \
         -c "$reset" -f - > "$log" 2>&1; then
    # The psql text goes to the supervisor log as a plain line (and stays in $log); the ERR line reaches the public
    # /update/status, so it only points at the file.
    echo "psql: $(grep -m1 -E 'ERROR|FATAL' "$log" || tail -n1 "$log")" >&2
    errln "database restore failed: see $log"
    errln "the server is stopped (on purpose: the old version must not run on a database it was not restored for); the database is unchanged (the restore runs in one transaction); .env still names ${running:-the running version}, the version this database belongs to; caddy and /recovery are up. Fix the cause (full psql output: docker compose exec supervisor cat $log), then Roll back again from /recovery; to restore by hand see docs/UPGRADING.md (Restoring by hand)."
    return 1
  fi
}

# --- the digest pins: checked before anything is touched (same rule as supervisor/lib.js imagePins) ---
# The owner compose pulls from: IMAGE_OWNER in .env (quotes and a CR removed), else compose's default.
IMAGE_OWNER_NOW="$(get_env IMAGE_OWNER | tr -d '\r"'"'"'')"
IMAGE_OWNER_NOW="${IMAGE_OWNER_NOW:-mdmesh-app}"
pin_problem() {  # pin_problem <name> <ref>: prints why <ref> can't be applied as the <name> image; nothing when it can
  local name="$1" ref="$2" want="ghcr.io/${IMAGE_OWNER_NOW}/mdmesh-$1"
  if [ -z "$ref" ]; then
    echo "the signed release ${VERSION} names no digest for its ${name} image (its release workflow predates digest pinning), so it is not applied. Publish it with the current release workflow, or update by hand (docs/UPGRADING.md)."
  elif ! [[ "$ref" =~ ^[a-z0-9][a-z0-9._-]*(/[a-z0-9][a-z0-9._-]*)+@sha256:[0-9a-f]{64}$ ]]; then
    echo "the signed release ${VERSION} has no valid digest for its ${name} image, so it is not applied."
  elif [ "${ref%@*}" != "$want" ]; then
    echo "the signed release ${VERSION} pins its ${name} image at ${ref%@*}, but this install pulls ${want} (IMAGE_OWNER=${IMAGE_OWNER_NOW} in .env), so it is not applied. Set GITHUB_REPO to the repository that publishes ${IMAGE_OWNER_NOW}'s images, or IMAGE_OWNER to the owner this release names."
  fi
}
_why="$(pin_problem server "$SERVER_REF")"
[ -n "$_why" ] || _why="$(pin_problem web "$WEB_REF")"
[ -n "$_why" ] || _why="$(pin_problem supervisor "$SUPERVISOR_REF")"
if [ -n "$_why" ]; then errln "$_why Nothing was changed."; phase failed; exit 1; fi
SERVER_PIN="${VERSION}@${SERVER_REF#*@}"   # SERVER_VERSION / WEB_VERSION / SUPERVISOR_VERSION values: <version>@sha256:…
WEB_PIN="${VERSION}@${WEB_REF#*@}"
SUPERVISOR_PIN="${VERSION}@${SUPERVISOR_REF#*@}"

# compose must resolve server + caddy to exactly the pinned refs (a compose file whose image lines don't follow
# IMAGE_OWNER/SERVER_VERSION/WEB_VERSION would run something else). Checked before the backup, with the pins passed in
# the environment (compose prefers it over .env), so a mismatch changes nothing: no dump, no new /backups/latest.
if ! IMAGES_NOW="$(SERVER_VERSION="$SERVER_PIN" WEB_VERSION="$WEB_PIN" dc config --images server caddy)"; then
  errln "could not read the compose configuration (docker compose config failed, see the log above). Nothing was changed."
  phase failed
  exit 1
fi
for want in "${SERVER_REF%@*}:${SERVER_PIN}" "${WEB_REF%@*}:${WEB_PIN}"; do
  # A here-string, not a pipe: under pipefail, grep -q exiting at the first match can fail the writer with SIGPIPE.
  if ! grep -qxF -- "$want" <<< "$IMAGES_NOW"; then
    errln "this install's compose file does not resolve to the signed image ${want} (it resolves to: $(printf '%s' "$IMAGES_NOW" | tr '\n' ' ')). Its image lines must be ghcr.io/\${IMAGE_OWNER}/mdmesh-server:\${SERVER_VERSION} and ghcr.io/\${IMAGE_OWNER}/mdmesh-web:\${WEB_VERSION}, as in docker-compose.release.yml. Nothing was changed."
    phase failed
    exit 1
  fi
done

OLD_SERVER="$(get_env SERVER_VERSION)"
OLD_WEB="$(get_env WEB_VERSION)"
OLD_CURRENT="$(get_env CURRENT_VERSION)"
[ -n "$OLD_SERVER" ]  || OLD_SERVER="${CURRENT_VERSION:-latest}"
[ -n "$OLD_WEB" ]     || OLD_WEB="$OLD_SERVER"
# CURRENT_VERSION is the release the supervisor reports; it can differ from the image tag (a :latest quick start
# runs SERVER_VERSION=latest with CURRENT_VERSION=0.0.0), so it is snapshotted and restored on its own.
[ -n "$OLD_CURRENT" ] || OLD_CURRENT="${CURRENT_VERSION:-$OLD_SERVER}"

STAMP="$(date +%Y%m%d-%H%M%S)"
BACKUP_SQL="$BACKUP_DIR/$STAMP.sql"
BACKUP_ENV="$BACKUP_DIR/$STAMP.env"

# --- rollback [env-only]: stop the server, restore the DB, then point .env back at the previous versions, start the
# old server and re-health-check. env-only just resets .env. env-only is for a failed pull: no container changed yet, so there is nothing
# to undo but .env (no downtime, and no writes since the backup are discarded). ---
restore_env() {
  set_env SERVER_VERSION "$OLD_SERVER"
  set_env WEB_VERSION "$OLD_WEB"
  set_env CURRENT_VERSION "$OLD_CURRENT"
}
rollback() {
  phase rollback
  if [ "${1:-}" = env-only ]; then restore_env; phase rolled_back; return; fi
  if [ -s "$BACKUP_SQL" ]; then
    restore_db "$BACKUP_SQL" "$BACKUP_DIR/$STAMP.restore.log" || { phase failed; return; }
  fi
  restore_env   # only now: .env must keep naming the version the database belongs to until the restore succeeded
  if ! dc up -d --no-deps server caddy; then
    errln "could not start the old server (see the log above); .env names the old versions and the database is restored: run 'docker compose up -d server caddy' in the install directory, or Roll back again from /recovery."
    phase failed
    return
  fi
  if healthy; then phase rolled_back; else errln "still unhealthy after rollback (${HEALTH_TIMEOUT}s) — manual intervention needed"; phase failed; fi
}

# ---------------- backup ----------------
phase backup
mkdir -p "$BACKUP_DIR"
{ echo "SERVER_VERSION=$OLD_SERVER"; echo "WEB_VERSION=$OLD_WEB"; echo "CURRENT_VERSION=$OLD_CURRENT"; } > "$BACKUP_ENV"
if ! dc exec -T postgres pg_dump --clean --if-exists -U "$DB_USER" "$DB_NAME" > "$BACKUP_SQL"; then
  errln "pg_dump failed — aborting before any change"
  rm -f "$BACKUP_SQL"
  phase failed
  exit 1
fi
echo "$STAMP" > "$BACKUP_DIR/latest"   # pointer the recovery page / rollback read

# ---------------- pull ----------------
phase pull
set_env SERVER_VERSION "$SERVER_PIN"
set_env WEB_VERSION "$WEB_PIN"
set_env CURRENT_VERSION "$VERSION"
if ! dc pull --quiet server caddy; then   # --quiet: no per-layer progress; errors still print
  errln "image pull failed"
  rollback env-only
  exit 1
fi
# Pulled by digest, so docker checked the content against it; confirm the local images carry exactly the signed digests.
for ref in "$SERVER_REF" "$WEB_REF"; do
  digests="$(docker image inspect --format '{{range .RepoDigests}}{{println .}}{{end}}' "$ref" 2>/dev/null)" || digests=""
  if ! grep -qxF -- "$ref" <<< "$digests"; then
    errln "the pulled image does not carry the signed digest ${ref}, so it is not used"
    rollback env-only
    exit 1
  fi
done

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
  # The supervisor itself is never recreated here (this script runs inside it). Its pin moves to this release's signed
  # digest, so the next `docker compose up -d` brings that exact image. Only when it follows releases: `latest` (the old
  # quick-start default), unset, or an earlier pin; a version set by hand or by setup.sh (which builds it) is kept.
  case "$(get_env SUPERVISOR_VERSION)" in
    ''|latest|*@sha256:*) set_env SUPERVISOR_VERSION "$SUPERVISOR_PIN"
                          echo "supervisor pinned to ${SUPERVISOR_REF} in .env; 'docker compose up -d' starts it" ;;
  esac
  phase "done"   # quoted: shellcheck SC1010 reads a bare done as the keyword
  echo "OK $VERSION"
  exit 0
fi
errln "health check failed after ${HEALTH_TIMEOUT}s"
rollback
exit 1
