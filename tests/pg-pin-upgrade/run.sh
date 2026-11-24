#!/usr/bin/env bash
# The pinned postgres on the databases installs already have. A 0.5.1 quick start (its own compose file and server
# image) runs on an older postgres:14 image; then this checkout's docker-compose.release.yml comes in as
# `./setup.sh --update` brings it (`docker compose up -d`: postgres is recreated on the pinned image, same volume).
# For each older image:
#   - the pinned image is the one running, on the same data directory (same major), and the 0.5.1 server answers on it;
#   - the database's collation and ctype, and the C library's version of that collation
#     (pg_collation_actual_version), are what they were: no glibc sort-order change under the text indexes, which
#     PostgreSQL 14 would not notice;
#   - amcheck finds every B-tree index consistent with its table (users_login_unique included), after rows whose
#     order differs between C libraries.
# Usage: tests/pg-pin-upgrade/run.sh [IMAGE...]   default: postgres:14 (what the 0.5.1 file says, as pulled today) and
#        postgres:14.18-trixie (the first trixie build, 2025-08-11; MDMesh's first release came after it)
# Needs docker with compose, git (the v0.5.1 tag) and network (Docker Hub, GHCR). Runs on this host's daemon as compose
# project pgpin-<pid>, no published ports; `docker compose down -v` of that project removes it.
set -uo pipefail
ROOT=$(cd -P -- "$(dirname -- "$0")/../.." && pwd)
FROM=("$@")
[ ${#FROM[@]} -gt 0 ] || FROM=(postgres:14 postgres:14.18-trixie)
PIN=$("$ROOT/scripts/third-party-images.sh" list | grep '^postgres:') || { echo "no pinned postgres"; exit 1; }
WORK=$(mktemp -d)
PROJECT=pgpin-$$
PASSED=0 FAILED=0
pass() { echo "  ok   $*"; PASSED=$((PASSED + 1)); }
fail() { echo "  FAIL $*"; FAILED=$((FAILED + 1)); }
check() { local label=$1; shift; if "$@"; then pass "$label"; else fail "$label"; fi; }
dc()   { docker compose -p "$PROJECT" --project-directory "$WORK" --env-file "$WORK/.env" "$@"; }
q()    { dc exec -T postgres psql -X -q -U mdmesh -d mdmesh -tAc "$1" | tr -d '\r'; }
down() { dc down -v --remove-orphans >>"$WORK/log" 2>&1; }
cleanup() {
  down
  [ "$FAILED" = 0 ] || tail -40 "$WORK/log"
  find "$WORK" -mindepth 1 -delete; rmdir "$WORK"
}
trap cleanup EXIT

git -C "$ROOT" show v0.5.1:docker-compose.release.yml > "$WORK/v051.yml" || { echo "no v0.5.1 tag (git fetch --tags)"; exit 1; }
cp "$ROOT/docker-compose.release.yml" "$WORK/new.yml"
printf '%s\n' DB_NAME=mdmesh DB_USER=mdmesh "DB_PASSWORD=pin-$$" BASE_URL=http://mdm.internal "HASH_SECRET=pin-$$" \
  SECURE_ENROLLMENT=0 SITE_ADDRESS=:80 IMAGE_OWNER=mdmesh-app SERVER_VERSION=0.5.1 COMPOSE_PROJECT_NAME="$PROJECT" > "$WORK/.env"

# up FILE...: postgres and the server from those compose files, until the server answers (its first start migrates).
up() {
  local args=() f _
  for f in "$@"; do args+=(-f "$f"); done
  dc "${args[@]}" up -d postgres server >>"$WORK/log" 2>&1 || return 1
  for _ in $(seq 1 90); do
    dc "${args[@]}" exec -T server curl -fsS -m 5 http://localhost:8080/rest/public/name >>"$WORK/log" 2>&1 && return 0
    sleep 4
  done
  dc "${args[@]}" logs --tail 60 server >>"$WORK/log" 2>&1
  return 1
}
# The collation facts the text indexes depend on.
collation() {
  q "SELECT datcollate, datctype, (SELECT pg_collation_actual_version(oid) FROM pg_collation
       WHERE collprovider = 'c' AND collcollate = d.datcollate LIMIT 1) FROM pg_database d WHERE datname = 'mdmesh'"
}

for from in "${FROM[@]}"; do
  echo "== 0.5.1 on $from, then $PIN"
  down
  printf 'services:\n  postgres:\n    image: %s\n' "$from" > "$WORK/from.yml"
  if ! up "$WORK/v051.yml" "$WORK/from.yml"; then fail "the 0.5.1 stack did not start on $from"; continue; fi
  # Strings whose order glibc releases have changed: punctuation, case, accents, digits, spaces.
  q "CREATE TABLE pin_probe (t text PRIMARY KEY);
     INSERT INTO pin_probe SELECT DISTINCT unnest(ARRAY['a-b','ab','a b','a_b','A-B','Ab','äb','ab1','ab-1','1-a','-a','a.b',
       'a''b','ä','a','b','co-op','coop','co op','Coop','ﬁ','fi','résumé','resume','Resume']) || g::text
       FROM generate_series(1, 200) g" >>"$WORK/log" 2>&1
  before=$(collation); major_before=$(q "SHOW server_version_num")
  echo "    before: ${major_before:0:2}, collation $before"
  if ! up "$WORK/new.yml"; then fail "$from: the 0.5.1 server did not come back after the pinned compose file"; continue; fi
  running=$(docker inspect -f '{{.Config.Image}}' "$(dc ps -q postgres)")
  check "$from: postgres runs the pinned image (it runs $running)" [ "$running" = "$PIN" ]
  after=$(collation); major_after=$(q "SHOW server_version_num")
  echo "    after:  ${major_after:0:2}, collation $after"
  check "$from: the same major (${major_before:0:2} -> ${major_after:0:2})" [ "${major_after:0:2}" = "${major_before:0:2}" ]
  check "$from: the same collation and C library collation version, so no REINDEX ('$before' -> '$after')" \
    [ "${after:-none}" = "$before" ]
  # Every B-tree index of the schema (users_login_unique among them) and the probe's, checked against its table.
  n=$(q "SELECT count(*) FROM pg_index i JOIN pg_class c ON c.oid = i.indexrelid JOIN pg_am am ON am.oid = c.relam
         JOIN pg_namespace n ON n.oid = c.relnamespace WHERE am.amname = 'btree' AND n.nspname = 'public'")
  checked=$(q "CREATE EXTENSION IF NOT EXISTS amcheck;
           SELECT count(*) FROM (SELECT bt_index_check(c.oid, true) FROM pg_index i JOIN pg_class c ON c.oid = i.indexrelid
             JOIN pg_am am ON am.oid = c.relam JOIN pg_namespace n ON n.oid = c.relnamespace
             WHERE am.amname = 'btree' AND n.nspname = 'public') x" 2>&1)
  login=$(q "SELECT count(*) FROM pg_class WHERE relname = 'users_login_unique' AND relkind = 'i'")
  check "$from: amcheck: every B-tree index consistent ($checked of $n checked; users_login_unique: $login)" \
    [ "$checked $login" = "$n 1" ]
done
echo; echo "pg-pin-upgrade: $PASSED passed, $FAILED failed"
[ "$FAILED" = 0 ]
