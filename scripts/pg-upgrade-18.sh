#!/usr/bin/env bash
#
# Moves a Docker install's database from PostgreSQL 14 (volume pgdata) to PostgreSQL 18 (volume pgdata18), once.
# PostgreSQL 14 is end of life (November 2026), and a new major can't open an older one's data directory, so the data
# goes over with pg_dump / pg_restore:
#   1. checks everything it can before touching anything (Docker, the volumes, the compose file it may have to edit);
#   2. stops the stack (`docker compose stop`);
#   3. starts a throwaway postgres:14 on the old volume (no network) and dumps the database (pg_dump -Fc) into
#      ./pg-upgrade-18/ — the dump stays there;
#   4. creates the pgdata18 volume, initializes PostgreSQL 18 in it with a throwaway container (no network), restores
#      the dump in one transaction, gives the database role back its password (the same hash as before) and runs
#      ANALYZE;
#   5. counts the rows of every table on both sides and stops if any count differs (pgdata18 is then removed and the
#      stack stays stopped on 14: `docker compose start` starts it again as it was); only then does it mark pgdata18
#      as moved (MARKER below), and pgdata18 without that mark next to 14 data is an unfinished move: the postgres
#      container refuses to start on it, --check reports it, and a run of this script starts the move over;
#   6. a compose file from before 0.6.0 (a quick start: postgres:14 on pgdata) gets its postgres service moved to
#      postgres:18 on pgdata18 — the original is kept as ./pg-upgrade-18/docker-compose.yml.pg14;
#   7. starts the stack again (`docker compose up -d`). A from-source install (./setup.sh is there) is left stopped:
#      re-run ./setup.sh, which builds and starts it.
# The PostgreSQL 14 volume is never changed or removed: going back is putting the old compose file back (or checking
# out the old release) and `docker compose up -d`, losing what was written since. Remove it by hand once the install
# has run well on 18 for a while (docs/UPGRADING.md, PostgreSQL 18).
#
# Run it in the install directory (the one with .env and docker-compose.yml), as the user who runs docker compose there.
# Usage: scripts/pg-upgrade-18.sh [-y]       -y: don't ask before stopping the stack
#        scripts/pg-upgrade-18.sh --check    exit 10 when this install still has to move (setup.sh asks), else 0
set -euo pipefail

NEW_IMAGE=postgres:18
WORKDIR=pg-upgrade-18
# In pgdata18's root (next to 18/docker): written once the database is restored and checked. docker-compose*.yml's
# postgres guard reads it too.
MARKER=.mdmesh-moved-from-pg14
YES=0; CHECK=0
for a in "$@"; do
  case "$a" in
    -y|--yes) YES=1 ;;
    --check)  CHECK=1 ;;
    -h|--help) sed -n '2,/^set -euo/p' "$0" | sed -e '$d' -e 's/^# \{0,1\}//'; exit 0 ;;
    *) echo "unknown argument: $a (see --help)" >&2; exit 2 ;;
  esac
done

say()  { printf '▸ %s\n' "$*"; }
ok()   { printf '  ✓ %s\n' "$*"; }
die()  { printf '✗ %s\n' "$*" >&2; exit 1; }

[ -f .env ] && [ -f docker-compose.yml ] || die "run this in the install directory (the one with .env and docker-compose.yml)"
command -v docker >/dev/null || die "docker is not on PATH"
docker compose version >/dev/null 2>&1 || die "docker compose is not available"

# envval KEY DEFAULT: KEY as docker compose resolves it for this install (.env as compose parses it, quotes and
# comments included, and the shell's own environment over it), else DEFAULT. A compose without
# `config --environment` (before 2.24): the last line of .env that sets KEY, surrounding quotes removed.
COMPOSE_ENV=$(docker compose config --environment 2>/dev/null) && COMPOSE_ENV_OK=1 || COMPOSE_ENV_OK=0
envval() {
  local v
  if [ "$COMPOSE_ENV_OK" = 1 ]; then
    v=$( { printf '%s\n' "$COMPOSE_ENV" | grep -E "^$1=" || true; } | tail -n1 | sed 's/^[^=]*=//')
  else
    v=$( { grep -E "^[[:space:]]*(export[[:space:]]+)?$1=" .env 2>/dev/null || true; } | tail -n1 \
         | sed -E "s/^[^=]*=//; s/^\"(.*)\"\$/\\1/; s/^'(.*)'\$/\\1/")
  fi
  printf '%s' "${v:-$2}"
}


PROJECT=$(docker compose config 2>/dev/null | sed -n 's/^name: //p' | head -n1)
PROJECT=${PROJECT:-$(envval COMPOSE_PROJECT_NAME mdmesh)}
OLD_VOL="${PROJECT}_pgdata"; NEW_VOL="${PROJECT}_pgdata18"
DB_USER=$(envval DB_USER mdmesh); DB_NAME=$(envval DB_NAME mdmesh)

vol_exists() { docker volume inspect "$1" >/dev/null 2>&1; }
# vol_pgver VOLUME PATH: the major version of the data directory at PATH in VOLUME, or nothing.
vol_pgver() {
  docker run --rm --network none -v "$1:/v:ro" --entrypoint sh "$NEW_IMAGE" -c "cat /v/$2/PG_VERSION 2>/dev/null || true" \
    | tr -dc '0-9'
}

vol_moved() {
  docker run --rm --network none -v "$1:/v:ro" --entrypoint sh "$NEW_IMAGE" -c "test -e /v/$MARKER"
}

OLD_VER=; NEW_VER=; MOVED=0
if vol_exists "$OLD_VOL"; then OLD_VER=$(vol_pgver "$OLD_VOL" .); fi
if vol_exists "$NEW_VOL"; then
  NEW_VER=$(vol_pgver "$NEW_VOL" 18/docker)
  vol_moved "$NEW_VOL" && MOVED=1
fi

# Done: pgdata18 is marked as moved, or there is no older data to move (a new install, or the 14 volume was removed).
if [ "$CHECK" = 1 ]; then
  [ -n "$OLD_VER" ] && [ "$MOVED" = 0 ] && exit 10
  exit 0
fi

say "MDMesh · PostgreSQL 14 → 18 (project ${PROJECT}, database ${DB_NAME}, role ${DB_USER})"
if [ "$MOVED" = 1 ]; then
  ok "${NEW_VOL} already holds the moved database (PostgreSQL ${NEW_VER:-18}): nothing to move."
  exit 0
fi
if [ -n "$NEW_VER" ] && [ -z "$OLD_VER" ]; then
  ok "${NEW_VOL} already holds a PostgreSQL ${NEW_VER} database and there is no older one: nothing to move."
  exit 0
fi
[ -n "$OLD_VER" ] || die "no PostgreSQL data in volume ${OLD_VOL}: nothing to move (a new install starts on 18 by itself)."
[ "$OLD_VER" -lt 18 ] || die "${OLD_VOL} holds PostgreSQL ${OLD_VER} data, not an older version: not moving it."
OLD_IMAGE="postgres:${OLD_VER}"
# pgdata18 without the mark next to the old data: a run of this script that stopped part way (the postgres container
# refuses to start on it, so nothing was written to it since). Started over below, from the untouched old volume.
REDO=0
if [ -n "$NEW_VER" ]; then
  REDO=1
  ok "${OLD_VOL}: PostgreSQL ${OLD_VER}; ${NEW_VOL}: an unfinished move, which is removed and done again"
else
  ok "${OLD_VOL}: PostgreSQL ${OLD_VER}; ${NEW_VOL}: $(vol_exists "$NEW_VOL" && echo 'empty' || echo 'not created yet')"
fi

# The compose file: from 0.6.0 on it already runs postgres:18 on pgdata18; a quick start's older copy needs its postgres
# service moved, which only works on the lines as we shipped them. Checked now, before anything stops.
EDIT_COMPOSE=0
if grep -qxF '    image: postgres:18' docker-compose.yml && grep -qxF '      - pgdata18:/var/lib/postgresql' docker-compose.yml; then
  ok "docker-compose.yml already runs ${NEW_IMAGE} on pgdata18"
else
  for l in "    image: postgres:${OLD_VER}" '      - pgdata:/var/lib/postgresql/data' '  pgdata:'; do
    [ "$(grep -cxF -- "$l" docker-compose.yml)" = 1 ] \
      || die "docker-compose.yml doesn't have the postgres lines this script knows (\"${l#"${l%%[! ]*}"}\" once): nothing was changed. Take the postgres service from the docker-compose.release.yml of a 0.6.0 or later release (or edit it by hand as there), then run this again."
  done
  EDIT_COMPOSE=1
  ok "docker-compose.yml will be moved to ${NEW_IMAGE} on pgdata18 (the original is kept)"
fi
[ -w . ] || die "can't write in $(pwd) (the dump and the compose backup go there): run as the install's owner"
[ "$COMPOSE_ENV_OK" = 1 ] || say "(this docker compose has no \`config --environment\`: DB_USER, DB_NAME and DB_PASSWORD are read from .env directly)"

# What to do after a failure that left the old database and the compose file as they were. `start` (not `up`) starts
# the stopped containers as they were, on PostgreSQL ${OLD_VER}: on a from-source install the compose file already runs
# 18, so `up -d` would recreate postgres from it, and its guard would refuse to start next to the old data.
AS_IT_WAS="docker compose start starts the stack again as it was, on PostgreSQL ${OLD_VER}"

say "Pulling ${OLD_IMAGE} and ${NEW_IMAGE}"
{ docker pull -q "$OLD_IMAGE" && docker pull -q "$NEW_IMAGE"; } >/dev/null || die "docker pull failed (above): nothing was changed"

if [ "$YES" != 1 ]; then
  printf '\nThis stops the whole stack (devices and the console are offline meanwhile: a few minutes for a small\ndatabase), copies the database to PostgreSQL 18 and starts it again. The PostgreSQL %s volume is left as it is.\nContinue? [y/N] ' "$OLD_VER"
  read -r ans || ans=
  case "$ans" in y|Y|yes|YES|o|O|oui) ;; *) echo "Nothing was changed."; exit 1 ;; esac
fi

STAMP=$(date +%Y%m%d-%H%M%S)
umask 077
mkdir -p "$WORKDIR"
DUMP="$WORKDIR/${DB_NAME}-pg${OLD_VER}-${STAMP}.dump"
C_OLD="${PROJECT}-pgupgrade-old"; C_NEW="${PROJECT}-pgupgrade-new"
ENVF="$WORKDIR/.init-env"
NEW_OURS=0; DONE=0
cleanup() {
  docker stop -t 60 "$C_OLD" "$C_NEW" >/dev/null 2>&1 || true
  docker rm "$C_OLD" "$C_NEW" >/dev/null 2>&1 || true
  rm -f -- "$ENVF"
  # Stopped part way (an error, Ctrl-C): pgdata18 is unfinished and unmarked, so remove it. If this doesn't run (the
  # machine went down), the missing mark still keeps it from being used, and the next run starts over.
  if [ "$NEW_OURS" = 1 ] && [ "$DONE" = 0 ] && docker volume rm "$NEW_VOL" >/dev/null 2>&1; then
    printf '  (%s, unfinished, was removed)\n' "$NEW_VOL" >&2
  fi
}
trap cleanup EXIT
trap 'exit 130' INT; trap 'exit 143' TERM

say "Stopping the stack"
docker compose stop
# Left over by a run that was killed: this script's own throwaway containers.
docker rm -f "$C_OLD" "$C_NEW" >/dev/null 2>&1 || true
[ -z "$(docker ps -q --filter "volume=${OLD_VOL}")" ] \
  || die "a running container still uses ${OLD_VOL} ($(docker ps --filter "volume=${OLD_VOL}" --format '{{.Names}}' | tr '\n' ' ')): stop it, then run this again. Nothing was changed: ${AS_IT_WAS}."

# wait_ready CONTAINER: until the server answers after the image's start-up. On an empty volume the image first runs
# a temporary server to initialize it (pg_isready would answer during that), so wait for the line that ends it.
wait_ready() {
  local _
  for _ in $(seq 1 180); do
    if docker logs "$1" 2>&1 | grep -qE 'init process complete|Skipping initialization' \
       && docker exec "$1" pg_isready -q -U "$DB_USER" -d "$DB_NAME" 2>/dev/null; then
      return 0
    fi
    [ "$(docker inspect -f '{{.State.Running}}' "$1" 2>/dev/null)" = true ] || break
    sleep 1
  done
  docker logs --tail 30 "$1" >&2; return 1
}
psql_in() { local c=$1; shift; docker exec -i "$c" psql -X -q -v ON_ERROR_STOP=1 -U "$DB_USER" -d "$DB_NAME" "$@"; }
# The exact row count of every table: one "schema.table count" line each, sorted.
COUNTS_SQL="SELECT n.nspname || '.' || c.relname || ' ' ||
  (xpath('/row/c/text()', query_to_xml(format('SELECT count(*) AS c FROM %I.%I', n.nspname, c.relname), false, true, '')))[1]::text
FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
WHERE c.relkind IN ('r', 'p') AND n.nspname NOT IN ('pg_catalog', 'information_schema') AND n.nspname NOT LIKE 'pg_toast%'
ORDER BY 1;"

say "Dumping the PostgreSQL ${OLD_VER} database"
docker run -d --name "$C_OLD" --network none -v "${OLD_VOL}:/var/lib/postgresql/data" "$OLD_IMAGE" >/dev/null
wait_ready "$C_OLD" || die "PostgreSQL ${OLD_VER} did not start on ${OLD_VOL} (above). Nothing was changed: ${AS_IT_WAS}."
docker exec "$C_OLD" pg_dump -U "$DB_USER" -d "$DB_NAME" -Fc > "$DUMP" \
  || die "pg_dump failed (above). Nothing was changed: ${AS_IT_WAS}."
OLD_COUNTS=$(psql_in "$C_OLD" -tA -c "$COUNTS_SQL")
ROLE_HASH=$(psql_in "$C_OLD" -tA -v r="$DB_USER" <<'SQL'
SELECT rolpassword FROM pg_authid WHERE rolname = :'r';
SQL
)
docker stop -t 60 "$C_OLD" >/dev/null; docker rm "$C_OLD" >/dev/null
ok "dump: $DUMP ($(du -h "$DUMP" | cut -f1)), $(printf '%s\n' "$OLD_COUNTS" | grep -c .) tables"

say "Creating PostgreSQL 18 in ${NEW_VOL}"
if [ "$REDO" = 1 ]; then
  docker ps -aq --filter "volume=${NEW_VOL}" | xargs -r docker rm >/dev/null \
    || die "could not remove the stopped containers that use the unfinished ${NEW_VOL}. ${AS_IT_WAS}."
  docker volume rm "$NEW_VOL" >/dev/null || die "could not remove the unfinished ${NEW_VOL} (above). ${AS_IT_WAS}."
fi
NEW_OURS=1
vol_exists "$NEW_VOL" || docker volume create \
  --label "com.docker.compose.project=${PROJECT}" --label com.docker.compose.volume=pgdata18 \
  --label "com.docker.compose.version=$(docker compose version --short 2>/dev/null || echo 2)" "$NEW_VOL" >/dev/null
fail_new() {
  local gone="was removed"
  docker stop -t 60 "$C_NEW" >/dev/null 2>&1 || true; docker rm "$C_NEW" >/dev/null 2>&1 || true
  docker volume rm "$NEW_VOL" >/dev/null 2>&1 || gone="is unfinished and unmarked (a container still uses it), so nothing will use it; the next run starts over"
  NEW_OURS=0
  die "$1 ${NEW_VOL} ${gone}; the PostgreSQL ${OLD_VER} database and the compose file are unchanged: ${AS_IT_WAS}. The dump is kept: $DUMP"
}
# A throwaway password, in a file only this user reads (not on the command line): the role gets its own password back
# from the old database right after the restore.
printf 'POSTGRES_USER=%s\nPOSTGRES_DB=%s\nPOSTGRES_PASSWORD=%s\n' "$DB_USER" "$DB_NAME" "$(od -An -N24 -tx1 /dev/urandom | tr -d ' \n')" > "$ENVF"
docker run -d --name "$C_NEW" --network none --env-file "$ENVF" -v "${NEW_VOL}:/var/lib/postgresql" "$NEW_IMAGE" >/dev/null
: > "$ENVF"
wait_ready "$C_NEW" || fail_new "PostgreSQL 18 did not start (above)."

say "Restoring into PostgreSQL 18"
docker exec -i "$C_NEW" pg_restore -U "$DB_USER" -d "$DB_NAME" --single-transaction --exit-on-error < "$DUMP" \
  || fail_new "pg_restore failed (above)."
case "$ROLE_HASH" in
  SCRAM-SHA-256\$*)
    psql_in "$C_NEW" -v r="$DB_USER" -v h="$ROLE_HASH" <<'SQL' || fail_new "could not set the role's password (above)."
ALTER ROLE :"r" PASSWORD :'h';
SQL
    ok "role ${DB_USER}: same password as before" ;;
  *)
    # md5 (or none): PostgreSQL 18's image checks passwords with SCRAM, so set the one .env gives the server.
    pw=$(envval DB_PASSWORD '')
    [ -n "$pw" ] || fail_new "the old role's password is not a SCRAM hash, and .env has no DB_PASSWORD to set instead."
    psql_in "$C_NEW" -v r="$DB_USER" -v p="$pw" <<'SQL' || fail_new "could not set the role's password (above)."
SET password_encryption = 'scram-sha-256';
ALTER ROLE :"r" PASSWORD :'p';
SQL
    ok "role ${DB_USER}: password set from .env (DB_PASSWORD), stored as SCRAM" ;;
esac
docker exec "$C_NEW" vacuumdb -q -U "$DB_USER" -d "$DB_NAME" --analyze-only || fail_new "ANALYZE failed (above)."

NEW_COUNTS=$(psql_in "$C_NEW" -tA -c "$COUNTS_SQL")
if [ "$OLD_COUNTS" != "$NEW_COUNTS" ]; then
  diff <(printf '%s\n' "$OLD_COUNTS") <(printf '%s\n' "$NEW_COUNTS") >&2 || true
  fail_new "the row counts differ between PostgreSQL ${OLD_VER} and 18 (above: < is ${OLD_VER}, > is 18)."
fi
ok "row counts match ($(printf '%s\n' "$NEW_COUNTS" | grep -c .) tables, $(printf '%s\n' "$NEW_COUNTS" | awk '{s+=$2} END {print s+0}') rows)"
docker stop -t 60 "$C_NEW" >/dev/null || fail_new "PostgreSQL 18 did not stop cleanly (above)."
docker rm "$C_NEW" >/dev/null
# The mark goes last, once the database is complete and checked (see MARKER).
docker run --rm --network none -v "${NEW_VOL}:/v" --entrypoint sh "$NEW_IMAGE" \
  -c "printf 'PostgreSQL %s, %s\n' \"\$1\" \"\$2\" > /v/$MARKER" sh "$OLD_VER" "$STAMP" \
  || fail_new "could not mark ${NEW_VOL} as moved (above)."
DONE=1

if [ "$EDIT_COMPOSE" = 1 ]; then
  say "Moving docker-compose.yml's postgres service to ${NEW_IMAGE}"
  BAK="$WORKDIR/docker-compose.yml.pg${OLD_VER}"
  cp -p docker-compose.yml "$BAK"
  awk -v old="    image: postgres:${OLD_VER}" '
    $0 == old                                       { print "    image: postgres:18"; next }
    $0 == "      - pgdata:/var/lib/postgresql/data" { print "      - pgdata18:/var/lib/postgresql"; next }
    $0 == "  pgdata:"                               { print "  pgdata18:"; print; next }
                                                    { print }' "$BAK" > docker-compose.yml.new
  chmod 644 docker-compose.yml.new
  mv -f docker-compose.yml.new docker-compose.yml
  if ! docker compose config -q; then
    cp -p "$BAK" docker-compose.yml
    die "the edited compose file does not load (above); the original is back. ${NEW_VOL} holds the moved database: edit the postgres service by hand (image postgres:18, volume pgdata18:/var/lib/postgresql, pgdata18 under volumes:), then docker compose up -d."
  fi
  ok "docker-compose.yml: postgres:18 on pgdata18 (the original: $BAK)"
fi

if [ -f setup.sh ]; then
  say "Done. This is a from-source install: re-run ./setup.sh (it reuses .env) to build and start the stack."
else
  say "Starting the stack"
  docker compose up -d
  say "Done: the database runs on PostgreSQL 18."
fi
if [ "$EDIT_COMPOSE" = 1 ]; then back="cp $WORKDIR/docker-compose.yml.pg${OLD_VER} docker-compose.yml"
else back="check out the release you ran before"; fi
cat <<EOF

  Kept: the PostgreSQL ${OLD_VER} volume ${OLD_VOL} (unchanged) and the dump ${DUMP}.
  Once the install has run well on 18 for a while, remove them (the postgres container mounts the old volume
  read-only, so take the stack down first, without -v; compose then creates an empty pgdata again, as on a new install):
      docker compose down && docker volume rm ${OLD_VOL} && docker compose up -d
      rm -r ${WORKDIR}
  To go back to ${OLD_VER} meanwhile (losing what was written since): docker compose stop, then ${back},
  then docker compose up -d.
EOF
