#!/usr/bin/env bash
# apply.sh <target-version> <server-ref> <web-ref> <supervisor-ref> [web-zip-url] [web-zip-sha256]
#
# The refs are the signed manifest's components.images, ghcr.io/<owner>/mdmesh-<name>@sha256:<digest> (server.js passes
# them after verifying the manifest). The images are pinned by digest: .env gets
# SERVER_VERSION/WEB_VERSION=<version>@<digest>, so compose's ghcr.io/${IMAGE_OWNER}/mdmesh-server:${SERVER_VERSION}
# becomes repo:tag@digest, which docker pulls and runs by the digest alone (the tag is ignored). A release whose refs are
# missing, malformed or under another owner than IMAGE_OWNER is refused before anything changes.
#
# In host-Nginx mode (HOST_NGINX=1) the optional web-zip-url is the release's mdmesh-web.zip asset and web-zip-sha256 is
# its SHA-256 from the same signed manifest (components.webZip); the archive is verified against that hash before its
# static files are staged into web_dist for the host Nginx. Without a verified zip, the files are copied out of the
# pinned web image. Either way the current web_dist is saved with the backup and restored on rollback, and the live
# directory is only replaced once the new files are complete.
#
# This is the copy baked into the RUNNING supervisor image (server.js runs its own /app/apply.sh), so an update is
# applied by the supervisor already installed, never by the release being installed: a supervisor before 0.5.0 applies
# by tag as it always did, and pinning starts once a 0.5.0+ supervisor runs.
#
# Runs inside the supervisor container (project mounted at /project, docker.sock mounted). Applies a VERIFIED
# update to the `server` + `caddy` services (or only `server` plus host-Nginx web assets when HOST_NGINX=1)
# with a pre-update DB backup and AUTOMATIC ROLLBACK on any
# failure. Emits `PHASE <name>` / `ERR <msg>` / `OK <version>` lines on stdout — server.js parses these
# to drive /update/status. It NEVER touches `supervisor` or `postgres` (no self-destruct, no data loss).
#
# Sequence: check the pins + compose → backup → pull (+ digest check) → recreate → healthcheck → done (+ pin the supervisor).
# A failed pull or digest check → rollback of .env only (no container changed yet). A later failure → rollback (restore
# versions, stop the server, restore the DB, recreate the old images, re-health-check) → rolled_back, or failed
# (non-zero, with ERR lines saying what state it is in) if the rollback itself can't recover.
#
# supervisor/test.js drives it with a stub docker (order, exit codes, messages). The restore was checked by hand
# against postgres:14 with a good and a deliberately broken dump; validate the full path on a staging deploy.
set -uo pipefail   # deliberately NOT -e: failures are handled explicitly so we can roll back.
# Everything these scripts create is private: the DB dump holds password hashes, and the .env snapshot, the pointer
# and restore.log sit beside it. (The host .env is only rewritten in place: sed -i and >> keep its mode and owner.)
umask 077

VERSION="${1:?usage: apply.sh <version> <server-ref> <web-ref> <supervisor-ref> [web-zip-url] [web-zip-sha256]}"
SERVER_REF="${2:-}"
WEB_REF="${3:-}"
SUPERVISOR_REF="${4:-}"
WEB_ZIP_URL="${5:-}"
WEB_ZIP_SHA256="${6:-}"
PROJECT_DIR="${COMPOSE_PROJECT_DIR:-/project}"
ENV_FILE="$PROJECT_DIR/.env"
BACKUP_DIR="${BACKUP_DIR:-/backups}"
WEB_DIR="$PROJECT_DIR/web_dist"
DB_USER="${DB_USER:-mdmesh}"
DB_NAME="${DB_NAME:-mdmesh}"
HEALTH_URL="${HEALTH_URL:-http://server:8080/rest/public/name}"
HEALTH_TIMEOUT="${HEALTH_TIMEOUT:-180}"

phase() { echo "PHASE $1"; }
errln() { echo "ERR $1" >&2; }
dc()    { docker compose "$@"; }
sha256_file() { sha256sum "$1" | awk '{print $1}'; }

# Make a prepared console directory servable. The host Nginx workers usually run as an unprivileged user, so every
# directory must be traversable and every file readable by others. This script runs under umask 077 (its backups stay
# private), which makes the staged tree and anything extracted into it 0700/0600; without this the swap would leave
# Nginx answering 403 while the server-only health check still reports success.
publish_web_dir() {  # publish_web_dir <dir>
  local bad
  chmod -R a+rX "$1" 2>/dev/null || { errln "could not make the console files readable ($1)"; return 1; }
  # Verify the bits rather than [ -r ]: the script runs as root, which can read anything.
  bad=$(find "$1" -type d ! -perm -005 -print -quit 2>/dev/null; find "$1" -type f ! -perm -004 -print -quit 2>/dev/null)
  if [ -n "$bad" ]; then
    errln "the console files would not be readable by the Nginx user ($bad)"
    return 1
  fi
}

# Replace web_dist with a fully prepared directory; on failure the previous contents are put back. The host Nginx
# serves WEB_DIR directly, so the old files stay in place until the new ones are complete.
swap_web_dir() {  # swap_web_dir <staged-dir>
  local stage="$1" old="$PROJECT_DIR/.web_dist.old"
  rm -rf "$old"
  if [ -d "$WEB_DIR" ] && ! mv "$WEB_DIR" "$old"; then
    errln "could not move the current console files aside ($WEB_DIR)"
    return 1
  fi
  if ! mv "$stage" "$WEB_DIR"; then
    errln "could not move the new console files into place ($WEB_DIR)"
    [ -d "$old" ] && mv "$old" "$WEB_DIR"
    return 1
  fi
  rm -rf "$old"
}

# In host-Nginx mode there is no caddy container: the host serves the built SPA and proxies to `server`.
recreate_apps() {
  if [ "$(get_env HOST_NGINX)" = "1" ]; then
    dc up -d --no-deps server
  else
    dc up -d --no-deps server caddy
  fi
}

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
WEB_BACKUP="$BACKUP_DIR/$STAMP.web.tar.gz"
HOST_NGINX="$(get_env HOST_NGINX)"

# --- rollback [env-only]: stop the server, restore the DB, then point .env back at the previous versions, start the
# old server and re-health-check. env-only just resets .env. env-only is for a failed pull: no container changed yet, so there is nothing
# to undo but .env (no downtime, and no writes since the backup are discarded). ---
restore_env() {
  set_env SERVER_VERSION "$OLD_SERVER"
  set_env WEB_VERSION "$OLD_WEB"
  set_env CURRENT_VERSION "$OLD_CURRENT"
}

# Put the pre-update console files back in web_dist, from the snapshot this update took. When no snapshot exists (an
# install from before host-Nginx updates recorded one), extract them from the previous pinned web image instead.
# Returns non-zero (after an ERR line) when neither source is available: the rollback must not claim success with the
# console still on the failed release.
restore_web() {
  local stage="$PROJECT_DIR/.web_dist.rollback" img cid
  rm -rf "$stage"
  if [ -s "$WEB_BACKUP" ]; then
    mkdir -p "$stage"
    if ! tar -C "$stage" -xzf "$WEB_BACKUP"; then
      errln "could not restore the console files from ${WEB_BACKUP}"
      rm -rf "$stage"
      return 1
    fi
  else
    img="ghcr.io/${IMAGE_OWNER_NOW}/mdmesh-web:${OLD_WEB:-latest}"
    cid=$(docker create "$img" 2>/dev/null || true)
    if [ -z "$cid" ]; then
      errln "no saved console files and the previous web image ${img} is not available, so the console was not restored"
      return 1
    fi
    mkdir -p "$stage"
    if ! docker cp "$cid:/srv/." "$stage" 2>/dev/null; then
      errln "could not copy the previous console files out of ${img}"
      docker rm "$cid" >/dev/null 2>&1 || true
      rm -rf "$stage"
      return 1
    fi
    docker rm "$cid" >/dev/null 2>&1 || true
  fi
  if ! publish_web_dir "$stage"; then
    rm -rf "$stage"
    return 1
  fi
  if ! swap_web_dir "$stage"; then
    rm -rf "$stage"
    return 1
  fi
}

rollback() {
  phase rollback
  if [ "${1:-}" = env-only ]; then restore_env; phase rolled_back; return; fi
  if [ -s "$BACKUP_SQL" ]; then
    restore_db "$BACKUP_SQL" "$BACKUP_DIR/$STAMP.restore.log" || { phase failed; return; }
  fi
  restore_env   # only now: .env must keep naming the version the database belongs to until the restore succeeded
  if [ "$HOST_NGINX" = "1" ] && ! restore_web; then
    errln "the old server and database are restored, but the console files could not be restored: fix the cause above, then Roll back again from /recovery."
    phase failed
    return
  fi
  if ! recreate_apps; then
    errln "could not start the old server (see the log above); .env names the old versions and the database is restored: run 'docker compose up -d' in the install directory, or Roll back again from /recovery."
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
# The host Nginx serves WEB_DIR directly: snapshot it before the update so a rollback can put the matching console back.
if [ "$HOST_NGINX" = "1" ] && [ -d "$WEB_DIR" ] && [ -n "$(ls -A "$WEB_DIR" 2>/dev/null)" ]; then
  if ! tar -C "$WEB_DIR" -czf "$WEB_BACKUP" .; then
    errln "could not save the current console files — aborting before any change"
    rm -f "$WEB_BACKUP"
    phase failed
    exit 1
  fi
fi
echo "$STAMP" > "$BACKUP_DIR/latest"   # pointer the recovery page / rollback read

# ---------------- pull ----------------
phase pull
set_env SERVER_VERSION "$SERVER_PIN"
set_env WEB_VERSION "$WEB_PIN"
set_env CURRENT_VERSION "$VERSION"
if [ "$(get_env HOST_NGINX)" = "1" ]; then
  if ! dc pull --quiet server; then   # --quiet: no per-layer progress; errors still print
    errln "image pull failed"
    rollback env-only
    exit 1
  fi
  # caddy is not run in host-Nginx mode, but its web image is still needed to extract the static assets when the
  # release has no mdmesh-web.zip. Pull it by the signed digest so the check below (and the extraction) uses it.
  if ! docker pull --quiet "$WEB_REF"; then
    errln "web image pull failed"
    rollback env-only
    exit 1
  fi
else
  if ! dc pull --quiet server caddy; then
    errln "image pull failed"
    rollback env-only
    exit 1
  fi
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
if [ "$HOST_NGINX" = "1" ]; then
  if ! dc up -d --no-deps server; then
    errln "recreate failed"
    rollback
    exit 1
  fi

  # Prepare the new console in a separate directory: web_dist keeps serving the current files until the replacement is
  # complete, so a failed download or a full disk cannot leave the host Nginx with an empty console.
  STAGE_DIR="$PROJECT_DIR/.web_dist.new"
  rm -rf "$STAGE_DIR"
  mkdir -p "$STAGE_DIR"
  UPDATED_WEB=0
  if [ -n "$WEB_ZIP_URL" ] && [ -n "$WEB_ZIP_SHA256" ]; then
    TMP_ZIP=$(mktemp)
    if curl -fsSL -m 300 "$WEB_ZIP_URL" -o "$TMP_ZIP" 2>/dev/null; then
      if [ "$(sha256_file "$TMP_ZIP")" = "$WEB_ZIP_SHA256" ]; then
        unzip -q -o "$TMP_ZIP" -d "$STAGE_DIR" 2>/dev/null && UPDATED_WEB=1
      else
        errln "mdmesh-web.zip does not match the sha256 in the signed manifest; using the pinned web image instead"
      fi
    fi
    rm -f "$TMP_ZIP"
  elif [ -n "$WEB_ZIP_URL" ]; then
    errln "no sha256 for mdmesh-web.zip from the signed manifest; using the pinned web image instead"
  fi

  if [ "$UPDATED_WEB" != 1 ]; then
    # Fallback: copy the static assets out of the pinned web image.
    CID=$(docker create "$WEB_REF" 2>/dev/null || true)
    if [ -n "$CID" ]; then
      docker cp "$CID:/srv/." "$STAGE_DIR" 2>/dev/null && UPDATED_WEB=1
      docker rm "$CID" >/dev/null 2>&1 || true
    fi
  fi

  if [ "$UPDATED_WEB" != 1 ] || [ ! -s "$STAGE_DIR/index.html" ]; then
    errln "could not prepare the console files (neither a verified mdmesh-web.zip nor the pinned web image produced them)"
    rm -rf "$STAGE_DIR"
    rollback
    exit 1
  fi
  if ! publish_web_dir "$STAGE_DIR"; then
    rm -rf "$STAGE_DIR"
    rollback
    exit 1
  fi
  if ! swap_web_dir "$STAGE_DIR"; then
    rm -rf "$STAGE_DIR"
    rollback
    exit 1
  fi
else
  if ! dc up -d --no-deps server caddy; then
    errln "recreate failed"
    rollback
    exit 1
  fi
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
