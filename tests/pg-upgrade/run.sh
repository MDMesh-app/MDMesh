#!/usr/bin/env bash
# The move of a Docker install's database from PostgreSQL 14 to 18 (scripts/pg-upgrade-18.sh), the compose files'
# postgres guard and setup.sh's part in it, on real PostgreSQL 14 and 18 containers.
#
# Usage: tests/pg-upgrade/run.sh [static] [quick] [source] [errors]      (default: all four)
#   static  the image the script moves to is the compose files' (tag and digest), and both compose files run the same
#           postgres service.
#   quick   a quick start from before 0.7.0 (a compose file of its own, "image: postgres:14" on pgdata, no setup.sh): the
#           question before the stack stops, answered no; a move killed part way, then run again; the data in 18 (row
#           counts, the role's password); the compose file the script writes runs the release's postgres service, guard
#           included; going back to 14, the guard refusing the out-of-date pgdata18, and the move done again from what
#           14 wrote since. Then the same move from 0.6.0's pinned image line.
#   source  a from-source install (setup.sh, a release tag, this checkout's docker-compose.yml) that ran 0.6.0's pinned
#           PostgreSQL 14: the guard refuses to start 18 next to the 14 data; ./setup.sh stops before it writes,
#           starts or backs up anything; a move that fails says how to start 14 again, and that works; after the move,
#           ./setup.sh backs up the PostgreSQL 18 database without starting the old 14 container, and goes on to build.
#   errors  on a moved install with data written since: a docker that fails while the script looks at the volumes, and a
#           pgdata18 that is not the script's own work, stop the script with the volume and its data as they were.
#
# Runs on this machine's Docker daemon (with the compose plugin), which must be able to pull postgres:14 and the
# compose files' postgres image. Each install is a compose project of its own ($PGU_PREFIX-quick, -pinned, -source and
# -errors; PGU_PREFIX defaults to pgu-<pid>) in a temporary directory; only those projects' containers, volumes and
# networks are created, and they are removed by name at the end. docker is wrapped (a stub in front of the real one)
# only to inject one fault at a time and to stop setup.sh at its build. PGU_KEEP=1 keeps the work directory.
# Needs: bash, docker, git, python3.
# shellcheck disable=SC2016,SC2034  # check's `eval '…'` conditions: expanded when they run, with the phase's variables
set -uo pipefail
unset CDPATH
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
SCRIPT="$ROOT/scripts/pg-upgrade-18.sh"
for c in docker git python3; do command -v "$c" >/dev/null || { echo "$c is required" >&2; exit 2; }; done
P=${PGU_PREFIX:-pgu-$$}
case "$P" in *[!a-z0-9-]*|'') echo "PGU_PREFIX: lower-case letters, digits and - only" >&2; exit 2 ;; esac
WORK="$(mktemp -d "${TMPDIR:-/tmp}/pg-upgrade.XXXXXX")"
LOG="$WORK/docker.log"; OUT="$WORK/out"
PASSED=0 FAILED=0
FAILURES=()
PHASES=("$@")
[ "${#PHASES[@]}" -gt 0 ] || PHASES=(static quick source errors)
PROJECTS=("$P-quick" "$P-pinned" "$P-source" "$P-errors")

pass() { echo "  ok   $*"; PASSED=$((PASSED + 1)); }
fail() { echo "  FAIL $*"; FAILED=$((FAILED + 1)); FAILURES+=("$*"); }
say()  { echo; echo "== $*"; }
check() { local label=$1; shift; if "$@"; then pass "$label"; else fail "$label"; fi; }
show() { sed 's/^/       | /' "$OUT"; }

# Only what this run made: each project's containers (the script's throwaway ones by their names), its two volumes
# and its network. Says so if any of it is still there.
cleanup() {
  local rc=$? p ids
  echo; echo "== cleanup"
  for p in "${PROJECTS[@]}"; do
    ids=$(docker ps -aq --filter "label=com.docker.compose.project=$p" 2>>"$LOG")
    # shellcheck disable=SC2086
    {
      docker rm -f -v $ids "$p-pgupgrade-old" "$p-pgupgrade-new"
      docker volume rm "${p}_pgdata" "${p}_pgdata18"
      docker network rm "${p}_default"
    } >>"$LOG" 2>&1
  done
  ids=$(docker ps -aq --filter "name=^$P-" 2>/dev/null; docker volume ls -q --filter "name=^$P-" 2>/dev/null)
  [ -z "$ids" ] || echo "  left behind (remove by hand): $(tr '\n' ' ' <<< "$ids")"
  if [ "${PGU_KEEP:-}" = 1 ]; then echo "  kept $WORK"; else rm -rf -- "$WORK"; fi
  exit "$rc"
}
trap cleanup EXIT
trap 'exit 130' INT; trap 'exit 143' TERM

# The compose files' postgres image, as compose resolves it (tests/lib/dind-images.sh).
# shellcheck source=tests/lib/dind-images.sh
. "$ROOT/tests/lib/dind-images.sh"
PG18=$(pinned_image postgres) || { echo "docker compose can't read docker-compose.release.yml" >&2; exit 2; }
# 0.6.0's image line; releases before it said "postgres:14".
PG14_PINNED=postgres:14.25-trixie@sha256:c0e9c0e9bac7e4c1834b698217a03ee5c708de30d313c84a506972de0e24b059
MARK=.mdmesh-moved-from-pg14

# docker, with one fault at a time (PGU_FAULT): the script's look at pgdata18 fails as a daemon error would (state),
# Docker can't say whether pgdata18 exists (volume), pg_restore fails (restore), or setup.sh's build is logged and
# stopped (build). Everything else is the real docker.
REAL_DOCKER=$(command -v docker)
mkdir "$WORK/stub"
cat > "$WORK/stub/docker" <<'EOF'
#!/bin/sh
case "${PGU_FAULT:-}:$*" in
  state:run*:/n:ro*) echo "docker: Error response from daemon: a fault tests/pg-upgrade injects" >&2; exit 125 ;;
  volume:volume\ inspect*_pgdata18*|volume:volume\ ls*_pgdata18*)
    echo "Cannot connect to the Docker daemon (a fault tests/pg-upgrade injects)" >&2; exit 1 ;;
  restore:exec*pg_restore*) cat > /dev/null; echo "pg_restore: error: a fault tests/pg-upgrade injects" >&2; exit 1 ;;
  build:*" up -d --build"*) echo up >> "$PGU_STUB_LOG"; exit 99 ;;
esac
exec "$PGU_REAL_DOCKER" "$@"
EOF
chmod +x "$WORK/stub/docker"
export PGU_REAL_DOCKER=$REAL_DOCKER PGU_STUB_LOG="$WORK/stub.log"

# --- an install directory (the current directory from install on) ---
# old_compose IMAGE: docker-compose.yml with the postgres service as releases before 0.7.0 shipped it.
old_compose() {
  cat > docker-compose.yml <<EOF
services:
  postgres:
    image: $1
    restart: unless-stopped
    environment:
      POSTGRES_DB: \${DB_NAME:-mdmesh}
      POSTGRES_USER: \${DB_USER:-mdmesh}
      POSTGRES_PASSWORD: \${DB_PASSWORD:?set DB_PASSWORD in .env}
    volumes:
      - pgdata:/var/lib/postgresql/data
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U \${DB_USER:-mdmesh} -d \${DB_NAME:-mdmesh}"]
      interval: 5s
      timeout: 5s
      retries: 20

volumes:
  pgdata:
EOF
}
# install PROJECT IMAGE: a new directory for PROJECT with that compose file and an .env, PostgreSQL 14 started and
# seeded: 2000 rows, 2 rows, an empty table, an index and a sequence.
install() {
  PROJECT=$1; D="$WORK/$1"
  mkdir "$D" && cd "$D" || return 1
  old_compose "$2"
  printf '%s\n' "COMPOSE_PROJECT_NAME=$1" DB_PASSWORD=pgu-secret HASH_SECRET=y BASE_URL=https://mdm.example.com \
    IMAGE_OWNER=local CURRENT_VERSION=9.8.0 > .env
  up_pg || return 1
  q "CREATE TABLE big (id serial PRIMARY KEY, v text); INSERT INTO big (v) SELECT 'row ' || g FROM generate_series(1, 2000) g;
     CREATE INDEX big_v ON big (v); CREATE TABLE small (v text); INSERT INTO small VALUES ('a'), ('b'); CREATE TABLE none (v int)" >/dev/null
}
dc()    { docker compose "$@" >>"$LOG" 2>&1; }
up_pg() { dc up -d --wait postgres; }
# started: the stopped containers started as they are (`start`, not `up`), and postgres answers.
started() {
  local _
  dc start
  for _ in $(seq 1 60); do [ "$(q 'SELECT 1')" != 1 ] || return 0; sleep 1; done
  return 1
}
q()     { docker compose exec -T postgres psql -X -q -v ON_ERROR_STOP=1 -U mdmesh -d mdmesh -tAc "$1" 2>>"$LOG" | tr -d '\r'; }
# counts: "rows of big/rows of small/rows of none/next id of big's sequence", read from the running postgres.
counts() { q "SELECT (SELECT count(*) FROM big) || '/' || (SELECT count(*) FROM small) || '/' || (SELECT count(*) FROM none) || '/' || (SELECT last_value FROM big_id_seq)"; }
has()   { [ "$(q "SELECT count(*) FROM small WHERE v = '$1'")" = 1 ]; }
major() { q "SELECT current_setting('server_version_num')::int / 10000"; }
# pw_ok: the role signs in with .env's password over the network (the image trusts its own socket and loopback).
pw_ok() {
  docker compose exec -T -e PGPASSWORD=pgu-secret postgres sh -c \
    'psql -X -h "$(hostname -i | cut -d" " -f1)" -U mdmesh -d mdmesh -tAc "SELECT 1"' 2>>"$LOG" | grep -qx 1
}
# move [NAME=VALUE...] -- [ARGS...]: the script with ARGS in this directory, with the stub as docker. Sets RC; output in $OUT.
move() {
  local envs=(); while [ "$#" -gt 0 ] && [ "$1" != -- ]; do envs+=("$1"); shift; done; [ "$#" = 0 ] || shift
  RC=0; env PATH="$WORK/stub:$PATH" ${envs[@]+"${envs[@]}"} bash "$SCRIPT" "$@" < /dev/null > "$OUT" 2>&1 || RC=$?
  cat "$OUT" >> "$LOG"
}
need() { move -- --check; [ "$RC" = "$1" ]; }       # need 10: --check says the move is due; need 0: it isn't
in_vol() { docker run --rm --network none -v "$1:/v:ro" --entrypoint sh "$PG18" -c "$2" 2>>"$LOG"; }
marked() { in_vol "${PROJECT}_pgdata18" "test -s /v/$MARK"; }
vol()    { docker volume ls -q --filter "name=$1" | grep -qxF "$1"; }
# refused: the postgres container of this directory's compose file does not start PostgreSQL, and says why.
# Its restart policy starts it again and again, and nothing here depends on when: the wait is for the guard's exit
# (status 1) as Docker records it; the query must not get psql's answer (a `docker exec` that loses its container
# prints the daemon's error on stdout, which is not one); and the log is read once the container is stopped: every
# line of it, from every start, is the guard's. What was seen goes to the log.
refused() {
  local _ r=1 id st='' logs
  local said='Not starting PostgreSQL 18 next to it: run scripts/pg-upgrade-18.sh'
  dc up -d postgres
  id=$(docker compose ps -aq postgres 2>>"$LOG")
  for _ in $(seq 1 300); do
    st=$(docker inspect -f '{{.State.Status}} {{.State.ExitCode}}' "$id" 2>>"$LOG")
    case "$st" in 'restarting 1'|'exited 1') r=0; break ;; esac
    sleep 0.2
  done
  [ "$(q 'SELECT 1')" != 1 ] || r=1
  dc stop postgres
  logs=$(docker logs "$id" 2>&1)
  { [ -n "$logs" ] && ! grep -qvF "$said" <<< "$logs"; } || r=1
  printf 'refused: container %s, last seen "%s", result %s, its log:\n%s\n' "$id" "$st" "$r" "$logs" >>"$LOG"
  return "$r"
}
# pg_service FILE: FILE's postgres service and database volumes as compose resolves them, under one project name.
pg_service() {
  DB_PASSWORD=x BASE_URL=x HASH_SECRET=x docker compose --env-file /dev/null -p pgu -f "$1" config --format json 2>>"$LOG" \
    | python3 -c 'import json, sys
d = json.load(sys.stdin)
print(json.dumps([d["services"]["postgres"], {k: v for k, v in d["volumes"].items() if k.startswith("pgdata")}], sort_keys=True))'
}
same_service() { local a b; a=$(pg_service "$1") && b=$(pg_service "$2") && [ -n "$a" ] && [ "$a" = "$b" ]; }
# moved_ok COUNTS: this directory's stack runs PostgreSQL 18 with those counts and the role's password, pgdata18 is
# marked, and --check has nothing left to ask.
moved_ok() { [ "$(major)" = 18 ] && [ "$(counts)" = "$1" ] && pw_ok && marked && need 0; }

phase_static() {
  say "static: one postgres image and one postgres service"
  check "the script moves to the compose files' image ($PG18)" grep -qxF "NEW_IMAGE=$PG18" "$SCRIPT"
  check "docker-compose.yml and docker-compose.release.yml run the same postgres service" \
    same_service "$ROOT/docker-compose.yml" "$ROOT/docker-compose.release.yml"
}

phase_quick() {
  local c0 c1 pid _
  say "quick: a quick start on \"image: postgres:14\""
  install "$P-quick" postgres:14 || { fail "PostgreSQL 14 with data, to move"; return; }
  c0=$(counts)
  check "PostgreSQL 14 runs with the seeded data ($c0)" [ "$(major):$c0" = "14:2000/2/0/2000" ]
  check "--check: the move is due (10)" need 10
  RC=0; echo n | env PATH="$WORK/stub:$PATH" bash "$SCRIPT" > "$OUT" 2>&1 || RC=$?
  cat "$OUT" >> "$LOG"
  check "asked first, and answered no, the script changes nothing: PostgreSQL 14 still runs" \
    eval '[ "$RC" = 1 ] && grep -q "Nothing was changed" "$OUT" && [ "$(major):$(counts)" = "14:$c0" ] && ! vol "${PROJECT}_pgdata18"'

  # Killed (as when the machine goes down) once PostgreSQL 18 is being made in pgdata18: nothing cleans up.
  env PATH="$WORK/stub:$PATH" bash "$SCRIPT" -y < /dev/null > "$OUT" 2>&1 &
  pid=$!
  for _ in $(seq 1 600); do docker inspect "$PROJECT-pgupgrade-new" >/dev/null 2>&1 && break; sleep 0.2; done
  kill -9 "$pid" 2>/dev/null; wait "$pid" 2>/dev/null
  cat "$OUT" >> "$LOG"
  for _ in $(seq 1 60); do
    docker exec "$PROJECT-pgupgrade-new" pg_isready -q -U mdmesh -d mdmesh >/dev/null 2>&1 \
      && in_vol "${PROJECT}_pgdata18" 'test -s /v/18/docker/PG_VERSION' && break
    sleep 1
  done
  check "a move killed part way leaves pgdata18 with PostgreSQL 18 in it and no mark" \
    eval 'in_vol "${PROJECT}_pgdata18" "test -s /v/18/docker/PG_VERSION" && ! marked'
  check "--check: the move is still due (10)" need 10
  move -- -y
  { [ "$RC" = 0 ] && grep -q 'an unfinished move' "$OUT"; } || show
  check "run again, the script starts the unfinished move over and finishes" eval '[ "$RC" = 0 ] && grep -q "an unfinished move" "$OUT"'
  up_pg
  check "PostgreSQL 18 runs with the same rows, sequence and password; --check has nothing to ask" moved_ok "$c0"
  check "the compose file the script wrote runs the release's postgres service, guard included" \
    same_service docker-compose.yml "$ROOT/docker-compose.release.yml"
  check "the dump and the old compose file are kept" \
    eval '[ -s pg-upgrade-18/docker-compose.yml.pg14 ] && ls pg-upgrade-18/mdmesh-pg14-*.dump >/dev/null 2>&1'
  move -- -y
  check "run once more, there is nothing to move" eval '[ "$RC" = 0 ] && grep -q "nothing to move" "$OUT"'

  # Going back to 14: what 18 wrote is left behind, and pgdata18 is out of date from then on.
  q "INSERT INTO small VALUES ('on 18')" >/dev/null
  dc stop; cp docker-compose.yml "$WORK/quick-18.yml"; cp pg-upgrade-18/docker-compose.yml.pg14 docker-compose.yml
  up_pg
  check "back on the old compose file, PostgreSQL 14 runs the data as it was moved" eval '[ "$(major):$(counts)" = "14:$c0" ] && ! has "on 18"'
  q "INSERT INTO small VALUES ('on 14 again')" >/dev/null; c1=$(counts)
  check "--check: the move is due again (10)" need 10
  dc stop; cp "$WORK/quick-18.yml" docker-compose.yml
  check "the 18 compose file's postgres refuses to start on the out-of-date pgdata18" refused
  cp pg-upgrade-18/docker-compose.yml.pg14 docker-compose.yml; up_pg
  move -- -y
  { [ "$RC" = 0 ] && grep -q 'out of date' "$OUT"; } || show
  check "the script moves the database again, saying pgdata18 was out of date" eval '[ "$RC" = 0 ] && grep -q "out of date" "$OUT"'
  up_pg
  check "PostgreSQL 18 runs what 14 wrote after going back, not what 18 had ($c1)" \
    eval 'moved_ok "$c1" && has "on 14 again" && ! has "on 18"'

  say "quick: a quick start on 0.6.0's pinned image line"
  install "$P-pinned" "$PG14_PINNED" || { fail "PostgreSQL 14 (pinned) with data, to move"; return; }
  c0=$(counts)
  move -- -y
  [ "$RC" = 0 ] || show
  up_pg
  check "the move finishes and PostgreSQL 18 runs the same data" eval '[ "$RC" = 0 ] && moved_ok "$c0"'
  check "the compose file the script wrote runs the release's postgres service, guard included" \
    same_service docker-compose.yml "$ROOT/docker-compose.release.yml"
}

# setup: ./setup.sh in this directory, its build logged and stopped by the stub. Sets RC; output in $OUT.
setup() {
  RC=0; : > "$PGU_STUB_LOG"
  env PATH="$WORK/stub:$PATH" PGU_FAULT=build bash ./setup.sh < /dev/null > "$OUT" 2>&1 || RC=$?
  cat "$OUT" >> "$LOG"
}
phase_source() {
  local c0 ctr env0 bk='' toc f
  local G=(git -c user.name=t -c user.email=t@example.com -c init.defaultBranch=main)
  say "source: a from-source install that ran 0.6.0 (pinned PostgreSQL 14), its clone now on a release with 18"
  install "$P-source" "$PG14_PINNED" || { fail "PostgreSQL 14 with data, to move"; return; }
  c0=$(counts)
  # The clone: the installer and this checkout's compose files, at release tag v9.9.0; .env says the install runs 9.8.0.
  cp docker-compose.yml "$WORK/source-14.yml"
  cp -R -- "$ROOT/setup.sh" "$ROOT/install" "$ROOT/docker-compose.domain.yml" .
  mkdir supervisor scripts; cp -- "$ROOT/supervisor/mode-guard.sh" supervisor/; cp -- "$SCRIPT" scripts/
  { "${G[@]}" init -q && "${G[@]}" add setup.sh && "${G[@]}" commit -qm release && "${G[@]}" tag v9.9.0; } >>"$LOG" 2>&1
  cp -- "$ROOT/docker-compose.yml" docker-compose.yml

  # Started some other way than setup.sh: compose makes the 18 container, which refuses. Then as it was, on 14.
  check "docker compose up: PostgreSQL 18 refuses to start next to the PostgreSQL 14 data" refused
  check "and made no database in pgdata18" eval '! in_vol "${PROJECT}_pgdata18" "test -e /v/18"'
  cp "$WORK/source-14.yml" docker-compose.yml; up_pg; cp -- "$ROOT/docker-compose.yml" docker-compose.yml
  ctr=$(docker compose ps -q postgres); env0=$(cat .env)

  setup
  [ "$RC" = 1 ] || show
  check "./setup.sh stops, naming scripts/pg-upgrade-18.sh" \
    eval '[ "$RC" = 1 ] && grep -q "Move it once with scripts/pg-upgrade-18.sh" "$OUT" && grep -q "The install was not changed" "$OUT"'
  check "before it wrote to .env, took a backup, or reached the build" \
    eval '[ "$(cat .env)" = "$env0" ] && [ ! -e backups ] && [ ! -s "$PGU_STUB_LOG" ]'
  check "with the PostgreSQL 14 container running as before" \
    eval '[ "$(docker compose ps -q --status running postgres)" = "$ctr" ] && [ "$(major):$(counts)" = "14:$c0" ]'

  # A move that fails after the stack stopped: the old containers are still there, and `start` runs them as they were.
  move PGU_FAULT=restore -- -y
  check "a move whose restore fails stops, and removes the pgdata18 it had begun" \
    eval '[ "$RC" != 0 ] && grep -q "pg_restore failed" "$OUT" && ! vol "${PROJECT}_pgdata18"'
  grep -q 'docker compose start starts the stack again as it was, on PostgreSQL 14' "$OUT" || show
  check "saying that docker compose start starts the stack again on PostgreSQL 14 (a container from a pinned image)" \
    grep -q 'docker compose start starts the stack again as it was, on PostgreSQL 14' "$OUT"
  started
  check "which it does, with the data" eval '[ "$(docker compose ps -q --status running postgres)" = "$ctr" ] && [ "$(major):$(counts)" = "14:$c0" ]'

  move -- -y
  [ "$RC" = 0 ] || show
  check "the move finishes, and leaves the start to ./setup.sh" \
    eval '[ "$RC" = 0 ] && grep -q "re-run ./setup.sh" "$OUT" && [ -z "$(docker compose ps -q --status running)" ] && marked'
  check "--check has nothing to ask (0)" need 0
  check "the stopped PostgreSQL 14 container is still there" eval '[ "$(docker compose ps -aq postgres)" = "$ctr" ]'

  setup
  grep -qx up "$PGU_STUB_LOG" || show
  check "./setup.sh now goes on to the build" grep -qx up "$PGU_STUB_LOG"
  for f in backups/pre-update-*-from-9.8.0.dump; do [ ! -s "$f" ] || bk=$f; done
  toc=''; [ -z "$bk" ] || toc=$(docker compose exec -T postgres pg_restore -l < "$bk" 2>>"$LOG")
  check "after a backup taken from PostgreSQL 18" eval 'case "$toc" in *"Dumped from database version: 18"*) true ;; *) false ;; esac'
  check "without starting the PostgreSQL 14 container: pgdata18 is still the moved database (--check 0)" \
    eval '[ "$(docker compose ps -aq postgres)" != "$ctr" ] && need 0'
  check "PostgreSQL 18 runs the same rows, sequence and password" moved_ok "$c0"
  check "and .env names the new version only now" grep -qx 'CURRENT_VERSION=9.9.0' .env
}

# still_there COUNTS: pgdata18 is the marked, moved database with those counts and the row written on 18, and runs.
still_there() { vol "${PROJECT}_pgdata18" && marked && up_pg && [ "$(major):$(counts)" = "18:$1" ] && has "on 18"; }
phase_errors() {
  local c0 mark
  say "errors: nothing the script can't account for is removed"
  install "$P-errors" postgres:14 || { fail "PostgreSQL 14 with data, to move"; return; }
  move -- -y
  up_pg
  q "INSERT INTO small VALUES ('on 18')" >/dev/null; c0=$(counts)
  check "an install moved to PostgreSQL 18, with a row written since ($c0)" eval '[ "$RC" = 0 ] && moved_ok "$c0" && has "on 18"'

  move PGU_FAULT=state -- --check
  [ "$RC" != 0 ] && [ "$RC" != 10 ] || show
  check "--check with a docker that fails while it reads pgdata18: an error, neither \"due\" nor \"done\" (exit $RC)" \
    eval '[ "$RC" != 0 ] && [ "$RC" != 10 ] && grep -q "a fault tests/pg-upgrade injects" "$OUT"'
  move PGU_FAULT=state -- -y
  [ "$RC" != 0 ] || show
  check "the move with that docker stops with the error" eval '[ "$RC" != 0 ] && grep -q "nothing was changed" "$OUT"'
  check "and pgdata18 is as it was, the row written on 18 included" still_there "$c0"

  move PGU_FAULT=volume -- -y
  [ "$RC" != 0 ] || show
  check "the move with a docker that can't say whether pgdata18 exists stops with the error" \
    eval '[ "$RC" != 0 ] && grep -q "nothing was changed" "$OUT"'
  check "and pgdata18 is as it was" still_there "$c0"

  # A PostgreSQL 18 database with no mark of the script's, next to the 14 data: not its own work.
  dc stop
  mark=$(in_vol "${PROJECT}_pgdata18" "cat /v/$MARK")
  docker run --rm --network none -v "${PROJECT}_pgdata18:/v" --entrypoint sh "$PG18" -c "rm /v/$MARK" >>"$LOG" 2>&1
  check "--check next to a pgdata18 that has no mark: the move is due (10)" need 10
  move -- -y
  [ "$RC" != 0 ] || show
  check "the move stops at a PostgreSQL 18 database it did not make, removing nothing" \
    eval '[ "$RC" != 0 ] && grep -q "not touching" "$OUT" && in_vol "${PROJECT}_pgdata18" "test -s /v/18/docker/PG_VERSION"'
  docker run --rm --network none -v "${PROJECT}_pgdata18:/v" --entrypoint sh "$PG18" -c 'printf "%s\n" "$1" > "/v/$2"' sh "$mark" "$MARK" >>"$LOG" 2>&1
  check "and with its mark back, pgdata18 is as it was" still_there "$c0"
}

for ph in "${PHASES[@]}"; do
  case "$ph" in
    static|quick|source|errors) "phase_$ph" ;;
    *) echo "unknown phase: $ph (static, quick, source, errors)" >&2; exit 2 ;;
  esac
  cd "$WORK" || exit 1
done

echo
echo "pg-upgrade: $PASSED passed, $FAILED failed ($(git -c safe.directory='*' -C "$ROOT" describe --always --dirty 2>/dev/null || echo '?'))"
if [ "$FAILED" != 0 ]; then
  printf '  FAIL %s\n' "${FAILURES[@]}"
  echo "  (docker's and the scripts' output: $LOG; PGU_KEEP=1 keeps it)"
  exit 1
fi
