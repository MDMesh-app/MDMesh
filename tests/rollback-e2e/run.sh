#!/usr/bin/env bash
# End-to-end test of the one-click rollback and the by-hand restores across a schema change, in throwaway containers.
#
# Usage: tests/rollback-e2e/run.sh [first] [040] [main] [native]      (default: all four)
#   first   what UPGRADING asks of a 0.4.x quick start: update the supervisor first (pull + up -d supervisor, :latest
#           = this branch's), then update. The new supervisor on the 0.4.0 stack: /update/status, /recovery, an update
#           check signed in as the admin. Then (a) an update to a release whose server never gets healthy (0.5.2, its
#           Tomcat on another port: it migrates the database, then fails the health check) rolls back by itself to a
#           working 0.4.0, and (b) the update to 0.5.1, then Roll back (POST /update/rollback with the recovery token,
#           as /recovery sends it) is back on a working 0.4.0.
#   040     v0.4.0 quick start (its published images, its supervisor) updated to this branch (0.5.1) by v0.4.0's own
#           apply.sh. v0.4.0's rollback.sh fails over the new schema (configurations_pr_key: a 0.5.0 table refers to
#           configurations) and must change nothing; then the supervisor is updated as UPGRADING says and the rollback
#           finishes: the 0.4.0 console, sign-in, the devices and an enrollment code made before the update all work,
#           what was written after the update is gone. Then the same database is updated again (no leftovers), and the
#           docs' Docker restores (OPERATIONS "Backups and restore", UPGRADING "Restoring by hand") are run as written.
#   main    origin/main (0.5.0) updated to this branch (0.5.1); the new supervisor rolls back. A restore that fails at its
#           very end, and a backup cut short, must leave the database exactly as it was; then the real rollback works.
#   native  the native restore command from docs/OPERATIONS.md, as the mdmesh role (not a superuser) on PostgreSQL 16,
#           onto a 0.5.1 database (dumps taken by the 040 run), and with a damaged dump.
#
# Everything runs in a privileged docker:dind container (its own daemon) next to a TLS registry that answers as ghcr.io,
# on an internal network. Images (on this host): ghcr.io/local/mdmesh-{server,web,supervisor}:$E2E_NEW_TAG (this
# branch, default rr-e2e) and :$E2E_OLD_TAG (origin/main, default rr-main), ghcr.io/mdmesh-app/mdmesh-*:0.4.0,
# postgres:14, postgres:16, registry:3, docker:28.5.2-dind. Containers and networks are named $E2E_PREFIX-* (default
# rr-e2e) and removed by name at the end. E2E_KEEP=1 keeps the work directory.
set -uo pipefail
umask 077
ROOT="$(CDPATH='' cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
HERE="$ROOT/tests/rollback-e2e"
NEW_TAG=${E2E_NEW_TAG:-rr-e2e}
OLD_TAG=${E2E_OLD_TAG:-rr-main}
P=${E2E_PREFIX:-rr-e2e}
NET=$P-net REG=$P-registry DIND=$P-dind PG16=$P-pg16
OWNER=rr-e2e
# dind + registry storage: a new directory of this run's own under E2E_DATA_DIR. Cleanup deletes only that one.
DATA_BASE="${E2E_DATA_DIR:-$HERE/.e2e-data}"
mkdir -p "$DATA_BASE" && DATA="$(mktemp -d "$DATA_BASE/run.XXXXXX")" || exit 1
WORK="$(mktemp -d "${TMPDIR:-/tmp}/rr-e2e.XXXXXX")"
chmod 755 "$WORK"
LOG="$WORK/docker.log"
PASSED=0 FAILED=0
FAILURES=()
PHASES=("$@")
[ "${#PHASES[@]}" -gt 0 ] || PHASES=(first 040 main native)

pass() { echo "  ok   $*"; PASSED=$((PASSED + 1)); }
fail() { echo "  FAIL $*"; FAILED=$((FAILED + 1)); FAILURES+=("$*"); }
note() { echo "  note $*"; }
say()  { echo; echo "== $*"; }
check() { local label=$1; shift; if "$@"; then pass "$label"; else fail "$label"; fi; }

cleanup() {
  local rc=$?
  echo; echo "== cleanup"
  docker rm -f -v "$DIND" "$REG" "$PG16" >>"$LOG" 2>&1
  docker network rm "$NET" >>"$LOG" 2>&1
  find "$DATA" -mindepth 1 -delete >>"$LOG" 2>&1; rmdir "$DATA" >>"$LOG" 2>&1
  local left
  left="$(docker ps -a --format '{{.Names}}' | grep "^$P-" | tr '\n' ' ')"
  [ -z "$left" ] && echo "  no $P containers left" || echo "  LEFT: containers $left"
  if [ "${E2E_KEEP:-0}" = 1 ]; then echo "  work directory kept: $WORK"
  else find "$WORK" -mindepth 1 -delete; rmdir "$WORK"; fi
  echo; echo "== rollback-e2e: $PASSED passed, $FAILED failed"
  for f in "${FAILURES[@]}"; do echo "  FAIL $f"; done
  [ "$FAILED" = 0 ] && [ "$rc" = 0 ]
  exit $?
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

in_dind() { docker exec -i "$DIND" "$@"; }
in_dir()  { local d=$1; shift; docker exec -i -w "$d" "$DIND" "$@"; }
dc()      { local d=$1; shift; in_dir "$d" docker compose "$@"; }
q()       { dc "$1" exec -T postgres psql -X -U mdmesh -d mdmesh -tAc "$2" 2>>"$LOG" | tr -d '\r'; }
# The database's content, comparable across runs: pg_dump without its per-run \restrict key.
fingerprint() { dc "$1" exec -T postgres pg_dump -U mdmesh mdmesh 2>>"$LOG" | grep -v '^\\\(un\)\?restrict ' | sha256sum | cut -c1-16; }
server_running() { [ -n "$(dc "$1" ps --status running -q server 2>>"$LOG")" ]; }
server_stopped() { ! server_running "$1"; }
server_image() { in_dind docker inspect -f '{{.Config.Image}}' "$(dc "$1" ps -aq server)" 2>>"$LOG"; }

# HTTP from inside the supervisor container (it has curl), through caddy as a browser or an agent would come. The
# session id is kept by hand: the curl in the current supervisor image does not send a cookie jar's session back to
# http://caddy (the 0.4.0 image's curl did).
# http DIR METHOD PATH [JSON [HEADER]]: prints "<status> <body>".
http() {
  local d=$1 m=$2 p=$3 body=${4:-} hdr=${5:-}
  # shellcheck disable=SC2016  # expanded by the container's shell
  dc "$d" exec -T supervisor sh -c 'touch /tmp/rr-sid
    b=$(curl -sS -m 30 -X "$1" -H "Content-Type: application/json" -H "Cookie: JSESSIONID=$(cat /tmp/rr-sid)" ${3:+--data "$3"} ${4:+-H "$4"} \
      -D /tmp/rr-hdr -o /tmp/rr-body -w "%{http_code}" "http://caddy:80$2" 2>&1)
    s=$(sed -n "s/^[Ss]et-[Cc]ookie: JSESSIONID=\([^;]*\).*/\1/p" /tmp/rr-hdr | tr -d "\r")
    [ -z "$s" ] || echo "$s" > /tmp/rr-sid
    echo "$b $(head -c 600 /tmp/rr-body)"' sh "$m" "$p" "$body" "$hdr" 2>>"$LOG" | tr -d '\r'
}
ADMIN_PW="rr-e2e-$(openssl rand -hex 6)"
ADMIN_MD5="$(printf '%s' "$ADMIN_PW" | md5sum | awk '{ print toupper($1) }')"
login()   { http "$1" POST /rest/public/auth/login "{\"login\":\"admin\",\"password\":\"$ADMIN_MD5\"}"; }
mint()    { local r; r="$(http "$1" POST /rest/private/agent/v1/token '{}')"; echo "mint: $r" >>"$LOG"
            grep -o '"token":"[^"]*"' <<< "$r" | head -1 | cut -d'"' -f4; }
enroll()  { http "$1" POST /rest/public/agent/v1/enroll "{\"enrollToken\":\"$2\"}"; }
device_of() { grep -o '"deviceId":"[^"]*"' <<< "$1" | head -1 | cut -d'"' -f4; }

wait_init() {  # wait_init DIR: the server wrote its initialized marker
  local _
  for _ in $(seq 1 90); do
    sleep 4
    dc "$1" exec -T server test -s /opt/mdmesh/initialized.txt >>"$LOG" 2>&1 && return 0
  done
  dc "$1" logs --tail 60 server >>"$LOG" 2>&1
  return 1
}
wait_healthy() {  # wait_healthy DIR: /rest/public/name answers 200 through caddy
  local _
  for _ in $(seq 1 90); do
    sleep 4
    case "$(http "$1" GET /rest/public/name)" in 200*) return 0 ;; esac
  done
  return 1
}

# push VERSION SRC_IMAGE_PREFIX SRC_TAG [relabel]: ghcr.io/$OWNER/mdmesh-<name>:VERSION in the registry; records REF_<v>_<name>.
push() {
  local v=$1 src=$2 tag=$3 relabel=${4:-} n img ref var
  for n in server web supervisor; do
    img="ghcr.io/$OWNER/mdmesh-$n:$v"
    if [ -n "$relabel" ]; then
      # broken: the server listens on 8081, so it migrates the database and starts, but never answers the health check.
      { printf 'FROM %s-%s:%s\nLABEL org.opencontainers.image.version=%s\n' "$src" "$n" "$tag" "$v"
        if [ "$relabel" = broken ] && [ "$n" = server ]; then
          printf '%s\n' "RUN sed -i 's/port=\"8080\"/port=\"8081\"/' /usr/local/tomcat/conf/server.xml"
        fi; } \
        | docker exec -i -e DOCKER_BUILDKIT=0 "$DIND" docker build -q -t "$img" - >>"$LOG" 2>&1 || return 1
    else
      in_dind docker tag "$src-$n:$tag" "$img" >>"$LOG" 2>&1 || return 1
    fi
    in_dind docker push -q "$img" >>"$LOG" 2>&1 || { echo "push of $img failed"; return 1; }
    ref="$(in_dind docker image inspect -f '{{range .RepoDigests}}{{println .}}{{end}}' "$img" | grep -m1 "^ghcr.io/$OWNER/mdmesh-$n@sha256:")"
    [ -n "$ref" ] || return 1
    var="REF_${v//./_}_$n"; printf -v "$var" '%s' "$ref"
    in_dind docker rmi "$img" >>"$LOG" 2>&1
    echo "    $v $n: $ref"
  done
}
ref_of() { local var="REF_${1//./_}_$2"; printf '%s' "${!var}"; }
pin_of() { printf '%s@%s' "$1" "$(ref_of "$1" "$2" | cut -d@ -f2)"; }
push_latest_supervisor() {  # push_latest_supervisor SRC_IMAGE: what `docker compose pull supervisor` brings on :latest
  {
    in_dind docker image inspect "$1" || in_dind docker pull -q "$1"
    in_dind docker tag "$1" "ghcr.io/$OWNER/mdmesh-supervisor:latest"
    in_dind docker push -q "ghcr.io/$OWNER/mdmesh-supervisor:latest"
    in_dind docker rmi "ghcr.io/$OWNER/mdmesh-supervisor:latest"
  } >>"$LOG" 2>&1
}

# install DIR VERSION SRC_TREE SV WV PV: the release's compose file and a quick start's .env, pull, start, seed as the
# quick start does (SRC_TREE's install/lib/db.sh and SQL), then a known admin password without a forced change.
install() {
  local d=$1 ver=$2 src=$3 sv=$4 wv=$5 pv=$6
  in_dind mkdir -p "$d"
  docker cp "$src/docker-compose.release.yml" "$DIND:$d/docker-compose.yml" >>"$LOG"
  cat > "$WORK/env" <<EOF
DB_NAME=mdmesh
DB_USER=mdmesh
DB_PASSWORD=$(openssl rand -hex 12)
BASE_URL=http://mdm.internal
HASH_SECRET=$(openssl rand -hex 12)
SECURE_ENROLLMENT=0
SITE_ADDRESS=:80
ACME_EMAIL=
TUNNEL_TOKEN=
IMAGE_OWNER=${OWNER}
SERVER_VERSION=${sv}
WEB_VERSION=${wv}
SUPERVISOR_VERSION=${pv}
GITHUB_REPO=${OWNER}/mdmesh-e2e-none
UPDATE_CHANNEL=stable
POLL_INTERVAL_HOURS=6
CURRENT_VERSION=${ver}
GITHUB_TOKEN=
AUTO_UPDATE=0
COMPOSE_PROJECT_NAME=mdmesh
COMPOSE_FILE=docker-compose.yml
COMPOSE_PROFILES=
EOF
  docker cp "$WORK/env" "$DIND:$d/.env" >>"$LOG"; in_dind chmod 600 "$d/.env"
  dc "$d" pull server caddy supervisor >>"$LOG" 2>&1 || { fail "pulling release $ver"; return 1; }
  dc "$d" run --rm --no-deps -T -u mdmesh --entrypoint rm server -f /opt/mdmesh/initialized.txt >>"$LOG" 2>&1
  dc "$d" up -d >>"$LOG" 2>&1 || { fail "docker compose up ($ver)"; return 1; }
  wait_init "$d" || { fail "the $ver server did not initialize"; return 1; }
  (
    # shellcheck disable=SC2034  # PSQL is consumed by db.sh
    PSQL=(docker exec -i -w "$d" "$DIND" docker compose exec -T postgres psql -U mdmesh -d mdmesh)
    # shellcheck source=install/lib/db.sh
    . "$src/install/lib/db.sh"
    mdm_seed admin@mdm.internal "$src/install/sql/hmdm_init.en.sql" "$ADMIN_PW" "$(openssl rand -hex 12)" \
      && mdm_post_seed "$src/install/sql/post_seed.sql"
  ) >>"$LOG" 2>&1 || { fail "seeding $ver"; return 1; }
  q "$d" "UPDATE users SET passwordreset = false WHERE login = 'admin'" >>"$LOG"
  pass "release $ver installed as a quick start and seeded"
}

teardown() { dc "$1" down -v --remove-orphans >>"$LOG" 2>&1; }

# The old server's view after a rollback: console, sign-in, devices.
assert_old_works() {  # assert_old_works DIR LABEL DEVICE_KEPT DEVICE_GONE DEVICE_COUNT
  local d=$1 l=$2
  check "$l console / through caddy: 200" grep -q '^200 ' <<< "$(http "$d" GET /)"
  local r; r="$(login "$d")"
  check "$l admin signs in (${r:0:60})" grep -q '^200 .*"status":"OK"' <<< "$r"
  check "$l device enrolled before the update is there" test "$(q "$d" "SELECT count(*) FROM devices WHERE number = '$3'")" = 1
  check "$l device enrolled after the update is gone (the database is the pre-update one)" \
    test "$(q "$d" "SELECT count(*) FROM devices WHERE number = '$4'")" = 0
  check "$l device count as before the update ($5)" test "$(q "$d" "SELECT count(*) FROM devices")" = "$5"
}

setup_dind() {
  say "setup: registry (as ghcr.io) + docker:dind on an internal network"
  openssl req -x509 -newkey rsa:2048 -nodes -days 1 -subj "/CN=rr-e2e CA" -keyout "$WORK/ca.key" -out "$WORK/ca.pem" \
    -addext "basicConstraints=critical,CA:TRUE" -addext "keyUsage=keyCertSign" >>"$LOG" 2>&1
  openssl req -newkey rsa:2048 -nodes -subj "/CN=ghcr.io" -keyout "$WORK/ghcr.key" -out "$WORK/ghcr.csr" >>"$LOG" 2>&1
  printf 'subjectAltName=DNS:ghcr.io\nbasicConstraints=CA:FALSE\n' > "$WORK/ghcr.ext"
  openssl x509 -req -in "$WORK/ghcr.csr" -CA "$WORK/ca.pem" -CAkey "$WORK/ca.key" -CAcreateserial -days 1 \
    -extfile "$WORK/ghcr.ext" -out "$WORK/ghcr.pem" >>"$LOG" 2>&1
  chmod 644 "$WORK/ca.pem" "$WORK/ghcr.pem" "$WORK/ghcr.key"
  docker network create --internal "$NET" >>"$LOG" || return 1
  mkdir -p "$DATA/registry" "$DATA/docker"
  docker run -d --name "$REG" --network "$NET" -v "$DATA/registry:/var/lib/registry" \
    -v "$WORK/ghcr.pem:/certs/ghcr.pem:ro" -v "$WORK/ghcr.key:/certs/ghcr.key:ro" \
    -e REGISTRY_HTTP_ADDR=0.0.0.0:443 -e REGISTRY_HTTP_TLS_CERTIFICATE=/certs/ghcr.pem -e REGISTRY_HTTP_TLS_KEY=/certs/ghcr.key \
    registry:3 >>"$LOG" || return 1
  local reg_ip _
  reg_ip="$(docker inspect -f "{{(index .NetworkSettings.Networks \"$NET\").IPAddress}}" "$REG")"
  docker run -d --privileged --name "$DIND" --network "$NET" --add-host "ghcr.io:$reg_ip" -e DOCKER_TLS_CERTDIR= \
    -v "$DATA/docker:/var/lib/docker" -v "$WORK/ca.pem:/etc/docker/certs.d/ghcr.io/ca.crt:ro" \
    docker:28.5.2-dind dockerd --host=unix:///var/run/docker.sock >>"$LOG" || return 1
  for _ in $(seq 1 30); do in_dind docker info >>"$LOG" 2>&1 && break; sleep 2; done
  echo "-- loading images"
  local imgs=() n t
  for n in server web supervisor; do
    for t in "$NEW_TAG" "$OLD_TAG"; do imgs+=("ghcr.io/local/mdmesh-$n:$t"); done
    imgs+=("ghcr.io/mdmesh-app/mdmesh-$n:0.4.0")
  done
  imgs+=(postgres:14)
  docker save "${imgs[@]}" | docker exec -i "$DIND" docker load >>"$LOG" 2>&1 || return 1
  echo "-- releases in the registry"
  push 0.4.0 ghcr.io/mdmesh-app/mdmesh 0.4.0 || return 1
  push 0.5.0 ghcr.io/local/mdmesh "$OLD_TAG" || return 1
  push 0.5.1 ghcr.io/local/mdmesh "$NEW_TAG" relabel || return 1
  push 0.5.2 ghcr.io/local/mdmesh "$NEW_TAG" broken || return 1
  mkdir -p "$WORK/v040"
  git -C "$ROOT" archive v0.4.0 docker-compose.release.yml install/lib/db.sh install/sql | tar -x -C "$WORK/v040"
}

# ---------------------------------------------------------------------------------------------------------------------
phase_first() {
  local d=/root/rr-first r t1 t2 t3 d1 d2 n0 stamp tok st _
  say "first: v0.4.0 quick start, supervisor updated first (UPGRADING v0.5.0), then updates and rollbacks"
  push_latest_supervisor ghcr.io/mdmesh-app/mdmesh-supervisor:0.4.0
  install "$d" 0.4.0 "$WORK/v040" 0.4.0 0.4.0 latest || { teardown "$d"; return; }
  login "$d" >>"$LOG"; t1="$(mint "$d")"; t2="$(mint "$d")"
  r="$(enroll "$d" "$t1")"; d1="$(device_of "$r")"
  check "0.4.0: a device enrolls (${d1:-$r})" test -n "$d1"
  n0="$(q "$d" "SELECT count(*) FROM devices")"

  echo "-- UPGRADING v0.5.0: docker compose pull supervisor && docker compose up -d supervisor (:latest is this branch's)"
  push_latest_supervisor "$(ref_of 0.5.1 supervisor)"
  in_dir "$d" sh -c 'docker compose pull supervisor && docker compose up -d supervisor' >>"$LOG" 2>&1
  check "the running supervisor is this branch's" dc "$d" exec -T supervisor grep -q 'DROP SCHEMA public CASCADE' /app/rollback.sh
  sleep 5
  r="$(http "$d" GET /update/status)"
  check "new supervisor on 0.4.0: /update/status 200, current 0.4.0 (${r:0:120})" grep -q '^200 .*"current":"0.4.0"' <<< "$r"
  check "new supervisor on 0.4.0: /recovery 200" grep -q '^200 ' <<< "$(http "$d" GET /recovery)"
  check "new supervisor on 0.4.0: console / 200" grep -q '^200 ' <<< "$(http "$d" GET /)"
  login "$d" >>"$LOG"
  r="$(http "$d" POST /update/check '{}' 'X-MDMesh-Console: 1')"
  check "new supervisor on 0.4.0: an update check signed in as the admin is authorized by the 0.4.0 server (${r:0:120})" \
    grep -q '^200 .*"current":"0.4.0"' <<< "$r"

  echo "-- (a) update to 0.5.2, whose server never answers the health check: automatic rollback"
  dc "$d" exec -T -e HEALTH_TIMEOUT=150 supervisor /app/apply.sh 0.5.2 "$(ref_of 0.5.2 server)" "$(ref_of 0.5.2 web)" \
    "$(ref_of 0.5.2 supervisor)" > "$WORK/first-apply-broken.out" 2>&1
  sed 's/^/      apply: /' "$WORK/first-apply-broken.out" | cut -c1-220
  check "the failed update rolled back by itself (PHASE rolled_back)" grep -qx 'PHASE rolled_back' "$WORK/first-apply-broken.out"
  stamp="$(dc "$d" exec -T supervisor cat /backups/latest | tr -d '\r')"
  dc "$d" exec -T supervisor cat "/backups/$stamp.restore.log" > "$WORK/first-broken-restore.log" 2>>"$LOG"
  check "0.5.2 had migrated the database before the rollback (the restore dropped configurationAppPermissions)" \
    grep -qi 'drop cascades to table configurationapppermissions' "$WORK/first-broken-restore.log"
  check "after the automatic rollback: the server image is 0.4.0 ($(server_image "$d"))" \
    test "$(server_image "$d")" = "ghcr.io/$OWNER/mdmesh-server:0.4.0"
  check ".env names 0.4.0 again" in_dind grep -qx CURRENT_VERSION=0.4.0 "$d/.env"
  assert_old_works "$d" "after the automatic rollback:" "$d1" none "$n0"
  check "after the automatic rollback: no 0.5.0 tables left" \
    test "$(q "$d" "SELECT count(*) FROM pg_tables WHERE schemaname = 'public' AND tablename IN ('configurationapppermissions', 'agentenrollmentkey')")" = 0

  echo "-- (b) update to 0.5.1, then Roll back as /recovery does it"
  dc "$d" exec -T supervisor /app/apply.sh 0.5.1 "$(ref_of 0.5.1 server)" "$(ref_of 0.5.1 web)" "$(ref_of 0.5.1 supervisor)" \
    > "$WORK/first-apply.out" 2>&1
  check "0.4.0 -> 0.5.1: OK" grep -qx 'OK 0.5.1' "$WORK/first-apply.out"
  login "$d" >>"$LOG"; t3="$(mint "$d")"; r="$(enroll "$d" "$t3")"; d2="$(device_of "$r")"
  check "0.5.1: a device enrolls after the update (${d2:-$r})" test -n "$d2"
  tok="$(dc "$d" exec -T supervisor cat /backups/recovery.token | tr -d '\r\n')"
  # shellcheck disable=SC2016  # expanded by the container's shell
  dc "$d" exec -T supervisor sh -c 'curl -sS -X POST -H "X-MDMesh-Console: 1" -H "X-Recovery-Token: $1" -o /tmp/rr-rb -w "%{http_code}" http://caddy:80/update/rollback' \
    sh "$tok" > "$WORK/first-rb.code" 2>>"$LOG"
  check "Roll back accepted (HTTP $(cat "$WORK/first-rb.code"))" grep -qx 202 "$WORK/first-rb.code"
  st=""
  for _ in $(seq 1 60); do
    sleep 5
    st="$(http "$d" GET /update/status | grep -o '"phase":"[a-z_]*"' | head -1)"
    case "$st" in *rolled_back*|*failed*) break ;; esac
  done
  check "Roll back finished: $st" test "$st" = '"phase":"rolled_back"'
  check "after Roll back: the server image is 0.4.0 ($(server_image "$d"))" test "$(server_image "$d")" = "ghcr.io/$OWNER/mdmesh-server:0.4.0"
  assert_old_works "$d" "after Roll back:" "$d1" "$d2" "$n0"
  r="$(enroll "$d" "$t2")"
  check "after Roll back: a code made before the updates enrolls a device ($(device_of "$r"))" test -n "$(device_of "$r")"
  teardown "$d"
}

# ---------------------------------------------------------------------------------------------------------------------
phase_040() {
  local d=/root/rr-040 r t1 t2 t3 d1 d2 n0 f1 f2 stamp
  say "040: v0.4.0 quick start -> 0.5.1 (this branch) by v0.4.0's supervisor -> rollback"
  push_latest_supervisor ghcr.io/mdmesh-app/mdmesh-supervisor:0.4.0
  install "$d" 0.4.0 "$WORK/v040" 0.4.0 0.4.0 latest || { teardown "$d"; return; }
  r="$(login "$d")"; check "0.4.0: admin signs in" grep -q '^200 .*"status":"OK"' <<< "$r"
  t1="$(mint "$d")"; t2="$(mint "$d")"
  check "0.4.0: two enrollment codes made" test -n "$t1" -a -n "$t2"
  r="$(enroll "$d" "$t1")"; d1="$(device_of "$r")"
  check "0.4.0: a device enrolls (${d1:-$r})" test -n "$d1"
  n0="$(q "$d" "SELECT count(*) FROM devices")"
  # The OPERATIONS.md pre-upgrade dump, as written there.
  dc "$d" exec -T postgres pg_dump -U mdmesh -Fc mdmesh > "$WORK/pre040.dump" 2>>"$LOG"
  docker cp "$WORK/pre040.dump" "$DIND:$d/mdmesh-pre-upgrade.dump" >>"$LOG"

  echo "-- update: v0.4.0's /app/apply.sh 0.5.1 (by tag, as a supervisor before 0.5.0 applies)"
  dc "$d" exec -T supervisor /app/apply.sh 0.5.1 > "$WORK/040-apply.out" 2>&1
  sed 's/^/      apply: /' "$WORK/040-apply.out" | cut -c1-220
  if grep -qx "OK 0.5.1" "$WORK/040-apply.out"; then pass "0.4.0 -> 0.5.1 applied"; else fail "0.4.0 -> 0.5.1 apply"; teardown "$d"; return; fi
  check "0.5.1: configurationAppPermissions exists (the table that blocks the old restore)" \
    test "$(q "$d" "SELECT to_regclass('configurationapppermissions') IS NOT NULL")" = t
  login "$d" >>"$LOG"; t3="$(mint "$d")"; r="$(enroll "$d" "$t3")"; d2="$(device_of "$r")"
  check "0.5.1: a device enrolls after the update (${d2:-$r})" test -n "$d2"
  q "$d" "INSERT INTO configurationAppPermissions SELECT c.id, a.id, 'android.permission.CAMERA', 'grant' FROM configurations c, applications a LIMIT 1" >>"$LOG"
  dc "$d" exec -T postgres pg_dump -U mdmesh -Fc mdmesh > "$WORK/post051.dump" 2>>"$LOG"

  echo "-- rollback with v0.4.0's own /app/rollback.sh (what a 0.4.0 supervisor runs): must fail and change nothing"
  dc "$d" stop server >>"$LOG" 2>&1; f1="$(fingerprint "$d")"
  dc "$d" exec -T supervisor /app/rollback.sh > "$WORK/040-rb-old.out" 2>&1
  sed 's/^/      rollback(0.4.0): /' "$WORK/040-rb-old.out" | cut -c1-220
  check "v0.4.0's rollback.sh fails (does not report OK)" bash -c "! grep -q '^OK ' '$WORK/040-rb-old.out'"
  stamp="$(dc "$d" exec -T supervisor cat /backups/latest | tr -d '\r')"
  dc "$d" exec -T supervisor cat "/backups/$stamp.restore.log" > "$WORK/040-rb-old.log" 2>>"$LOG"
  check "root cause: $(grep -m1 ERROR "$WORK/040-rb-old.log" | cut -c1-150)" grep -q 'cannot drop constraint configurations_pr_key' "$WORK/040-rb-old.log"
  f2="$(fingerprint "$d")"
  check "the failed old restore left the database exactly as it was ($f1)" test "$f1" = "$f2"
  check "the server is left stopped" server_stopped "$d"
  check "/recovery is up through caddy" grep -q '^200 ' <<< "$(http "$d" GET /recovery)"
  check ".env still names 0.5.1" in_dind grep -qx CURRENT_VERSION=0.5.1 "$d/.env"

  echo "-- the UPGRADING remedy: docker compose pull supervisor && docker compose up -d supervisor, then Roll back"
  push_latest_supervisor "$(ref_of 0.5.1 supervisor)"
  in_dir "$d" sh -c 'docker compose pull supervisor && docker compose up -d supervisor' >>"$LOG" 2>&1
  check "the running supervisor is this branch's (its rollback.sh resets the schema)" \
    dc "$d" exec -T supervisor grep -q 'DROP SCHEMA public CASCADE' /app/rollback.sh
  dc "$d" exec -T supervisor /app/rollback.sh > "$WORK/040-rb-new.out" 2>&1
  sed 's/^/      rollback(new): /' "$WORK/040-rb-new.out" | cut -c1-220
  check "the new rollback.sh: OK 0.4.0" grep -qx 'OK 0.4.0' "$WORK/040-rb-new.out"
  check "the server runs v0.4.0's image ($(server_image "$d"))" test "$(server_image "$d")" = "ghcr.io/$OWNER/mdmesh-server:0.4.0"
  assert_old_works "$d" "rolled back to 0.4.0:" "$d1" "$d2" "$n0"
  r="$(enroll "$d" "$t2")"
  check "rolled back to 0.4.0: a code made before the update enrolls a device ($(device_of "$r"))" test -n "$(device_of "$r")"
  check "rolled back to 0.4.0: no 0.5.0 tables left (configurationAppPermissions, agentEnrollmentKey)" \
    test "$(q "$d" "SELECT count(*) FROM pg_tables WHERE schemaname = 'public' AND tablename IN ('configurationapppermissions', 'agentenrollmentkey')")" = 0
  check "rolled back to 0.4.0: public has its owner and grants ($(q "$d" "SELECT nspowner::regrole || ' ' || nspacl::text FROM pg_namespace WHERE nspname = 'public'"))" \
    test "$(q "$d" "SELECT nspowner::regrole || ' ' || nspacl::text FROM pg_namespace WHERE nspname = 'public'")" = 'mdmesh {mdmesh=UC/mdmesh,=UC/mdmesh}'

  echo "-- the same database updated again (now by the new supervisor, pinned): nothing left over from the first time"
  dc "$d" exec -T supervisor /app/apply.sh 0.5.1 "$(ref_of 0.5.1 server)" "$(ref_of 0.5.1 web)" "$(ref_of 0.5.1 supervisor)" \
    > "$WORK/040-apply2.out" 2>&1
  check "0.4.0 -> 0.5.1 again: OK" grep -qx 'OK 0.5.1' "$WORK/040-apply2.out"
  check "0.5.1 again: admin signs in" grep -q '^200 .*"status":"OK"' <<< "$(login "$d")"

  echo "-- docs/OPERATIONS.md, Docker restore, as written (the 0.4.0 pre-upgrade dump onto the 0.5.1 database)"
  awk '/^To go back to it, stop the server, restore/{f=1} f && /^```bash$/{b=1; next} b && /^```$/{exit} b' "$ROOT/docs/OPERATIONS.md" \
    | grep -v '^git checkout' > "$WORK/ops-docker.sh"
  sed 's/^/      | /' "$WORK/ops-docker.sh"
  docker cp "$WORK/ops-docker.sh" "$DIND:$d/ops-docker.sh" >>"$LOG"
  in_dir "$d" sh ops-docker.sh > "$WORK/ops-docker.out" 2>&1
  sed 's/^/      out: /' "$WORK/ops-docker.out" | grep -v 'drop cascades to' | head -8
  check "OPERATIONS Docker restore prints Restored." grep -qx 'Restored.' "$WORK/ops-docker.out"
  check "OPERATIONS Docker restore: the 0.4.0 database is back (no configurationAppPermissions; $n0 device(s))" \
    test "$(q "$d" "SELECT (to_regclass('configurationapppermissions') IS NULL)::text || (SELECT count(*) FROM devices)")" = "true$n0"
  dc "$d" up -d server >>"$LOG" 2>&1
  check "the 0.5.1 server migrates that restored database up again" wait_healthy "$d"

  echo "-- docs/UPGRADING.md, Restoring by hand, as written (the latest backup: 0.4.0, taken by the second update)"
  awk '/^### Restoring by hand/{f=1} f && /^```bash$/{b=1; next} f && b && /^```$/{exit} f && b' "$ROOT/docs/UPGRADING.md" > "$WORK/by-hand.sh"
  sed 's/^/      | /' "$WORK/by-hand.sh"
  docker cp "$WORK/by-hand.sh" "$DIND:$d/by-hand.sh" >>"$LOG"
  in_dir "$d" sh by-hand.sh > "$WORK/by-hand.out" 2>&1
  sed 's/^/      out: /' "$WORK/by-hand.out" | grep -v 'drop cascades to' | head -8
  check "Restoring by hand prints Restored." grep -qx 'Restored.' "$WORK/by-hand.out"
  # "Then": the versions from the snapshot into .env, and start the old server.
  stamp="$(dc "$d" exec -T supervisor cat /backups/latest | tr -d '\r')"
  dc "$d" exec -T supervisor cat "/backups/$stamp.env" | tr -d '\r' > "$WORK/snap.env"
  local k v
  for k in SERVER_VERSION WEB_VERSION CURRENT_VERSION; do
    v="$(grep "^$k=" "$WORK/snap.env" | cut -d= -f2-)"
    in_dind sed -i "s|^$k=.*|$k=$v|" "$d/.env"
  done
  dc "$d" up -d --no-deps server caddy >>"$LOG" 2>&1
  check "after Restoring by hand, v0.4.0 runs and answers" wait_healthy "$d"
  check "after Restoring by hand: the server image is 0.4.0 ($(server_image "$d"))" test "$(server_image "$d")" = "ghcr.io/$OWNER/mdmesh-server:0.4.0"
  check "after Restoring by hand: admin signs in" grep -q '^200 .*"status":"OK"' <<< "$(login "$d")"
  teardown "$d"
}

# ---------------------------------------------------------------------------------------------------------------------
phase_main() {
  local d=/root/rr-main r t1 t2 t3 d1 d2 n0 f1 f2 f3 stamp
  say "main: origin/main (0.5.0) -> 0.5.1 (this branch) -> rollback by the new supervisor"
  install "$d" 0.5.0 "$ROOT" "$(pin_of 0.5.0 server)" "$(pin_of 0.5.0 web)" "$(pin_of 0.5.0 supervisor)" || { teardown "$d"; return; }
  r="$(login "$d")"; check "0.5.0: admin signs in" grep -q '^200 .*"status":"OK"' <<< "$r"
  t1="$(mint "$d")"; t2="$(mint "$d")"
  r="$(enroll "$d" "$t1")"; d1="$(device_of "$r")"
  check "0.5.0: a device enrolls (${d1:-$r})" test -n "$d1"
  n0="$(q "$d" "SELECT count(*) FROM devices")"
  echo "-- update: main's /app/apply.sh 0.5.1 <refs>"
  dc "$d" exec -T supervisor /app/apply.sh 0.5.1 "$(ref_of 0.5.1 server)" "$(ref_of 0.5.1 web)" "$(ref_of 0.5.1 supervisor)" \
    > "$WORK/main-apply.out" 2>&1
  sed 's/^/      apply: /' "$WORK/main-apply.out" | cut -c1-220
  if grep -qx "OK 0.5.1" "$WORK/main-apply.out"; then pass "0.5.0 -> 0.5.1 applied"; else fail "0.5.0 -> 0.5.1 apply"; teardown "$d"; return; fi
  dc "$d" up -d supervisor >>"$LOG" 2>&1   # UPGRADING: picks up the supervisor apply.sh pinned
  check "the running supervisor is this branch's" dc "$d" exec -T supervisor grep -q 'DROP SCHEMA public CASCADE' /app/rollback.sh
  login "$d" >>"$LOG"; t3="$(mint "$d")"; r="$(enroll "$d" "$t3")"; d2="$(device_of "$r")"
  check "0.5.1: a device enrolls after the update (${d2:-$r})" test -n "$d2"
  stamp="$(dc "$d" exec -T supervisor cat /backups/latest | tr -d '\r')"
  dc "$d" exec -T supervisor cp "/backups/$stamp.sql" "/backups/$stamp.sql.orig"

  echo "-- a restore that fails at its very end (after the reset and the whole dump): nothing may change"
  dc "$d" stop server >>"$LOG" 2>&1; f1="$(fingerprint "$d")"
  dc "$d" exec -T supervisor sh -c "echo 'SELECT rr_e2e_no_such_function();' >> /backups/$stamp.sql"
  dc "$d" exec -T supervisor /app/rollback.sh > "$WORK/main-rb-fail.out" 2>&1
  sed 's/^/      rollback: /' "$WORK/main-rb-fail.out" | cut -c1-220
  check "the failing restore is reported (PHASE failed)" grep -qx 'PHASE failed' "$WORK/main-rb-fail.out"
  f2="$(fingerprint "$d")"
  check "the failing restore left the database exactly as it was ($f1 = $f2)" test "$f1" = "$f2"
  check ".env still names 0.5.1" in_dind grep -qx CURRENT_VERSION=0.5.1 "$d/.env"
  check "/recovery is up through caddy" grep -q '^200 ' <<< "$(http "$d" GET /recovery)"

  echo "-- a backup cut short: never committed"
  dc "$d" exec -T supervisor sh -c "head -n 300 /backups/$stamp.sql.orig > /backups/$stamp.sql"
  dc "$d" exec -T supervisor /app/rollback.sh > "$WORK/main-rb-short.out" 2>&1
  sed 's/^/      rollback: /' "$WORK/main-rb-short.out" | cut -c1-220
  check "the short backup is refused (PHASE failed)" grep -qx 'PHASE failed' "$WORK/main-rb-short.out"
  dc "$d" exec -T supervisor cat "/backups/$stamp.restore.log" > "$WORK/main-rb-short.log" 2>>"$LOG"
  check "restore.log says the backup is incomplete" grep -q 'the backup is incomplete' "$WORK/main-rb-short.log"
  f3="$(fingerprint "$d")"
  check "the short backup left the database exactly as it was ($f1 = $f3)" test "$f1" = "$f3"

  echo "-- the real backup: Roll back"
  dc "$d" exec -T supervisor cp "/backups/$stamp.sql.orig" "/backups/$stamp.sql"
  dc "$d" exec -T supervisor /app/rollback.sh > "$WORK/main-rb.out" 2>&1
  sed 's/^/      rollback: /' "$WORK/main-rb.out" | cut -c1-220
  check "rollback.sh: OK 0.5.0" grep -qx 'OK 0.5.0' "$WORK/main-rb.out"
  check "the server runs 0.5.0's pinned image" test "$(server_image "$d")" = "ghcr.io/$OWNER/mdmesh-server:$(pin_of 0.5.0 server)"
  assert_old_works "$d" "rolled back to 0.5.0:" "$d1" "$d2" "$n0"
  r="$(enroll "$d" "$t2")"
  check "rolled back to 0.5.0: a code made before the update enrolls a device ($(device_of "$r"))" test -n "$(device_of "$r")"
  teardown "$d"
}

# ---------------------------------------------------------------------------------------------------------------------
phase_native() {
  say "native: docs/OPERATIONS.md's native restore, as the mdmesh role (not a superuser) on PostgreSQL 16"
  if [ ! -s "$WORK/pre040.dump" ] || [ ! -s "$WORK/post051.dump" ]; then fail "native needs the 040 run's dumps"; return; fi
  docker run -d --name "$PG16" --network "$NET" -e POSTGRES_PASSWORD=pg --tmpfs /var/lib/postgresql/data postgres:16 >>"$LOG" || { fail "postgres:16"; return; }
  local _
  for _ in $(seq 1 30); do docker exec "$PG16" pg_isready -U postgres -h 127.0.0.1 >>"$LOG" 2>&1 && break; sleep 2; done
  docker exec "$PG16" psql -X -q -U postgres -c "CREATE ROLE mdmesh LOGIN PASSWORD 'rr'" -c "CREATE DATABASE mdmesh OWNER mdmesh" >>"$LOG" 2>&1
  nq() { docker exec -e PGPASSWORD=rr "$PG16" psql -X -h 127.0.0.1 -U mdmesh -d mdmesh -tAc "$1" 2>>"$LOG"; }
  nfp() { docker exec -e PGPASSWORD=rr "$PG16" pg_dump -h 127.0.0.1 -U mdmesh mdmesh 2>>"$LOG" | grep -v '^\\\(un\)\?restrict ' | sha256sum | cut -c1-16; }
  check "mdmesh is not a superuser" test "$(nq "SELECT rolsuper FROM pg_roles WHERE rolname = current_user")" = f
  # A 0.5.1 database, as the mdmesh role.
  docker exec -i -e PGPASSWORD=rr "$PG16" pg_restore -h 127.0.0.1 -U mdmesh -d mdmesh --no-owner < "$WORK/post051.dump" >>"$LOG" 2>&1
  check "a 0.5.1 database (configurationAppPermissions present)" test "$(nq "SELECT to_regclass('configurationapppermissions') IS NOT NULL")" = t
  # The command from the docs, as written, with only the parts that need a native install swapped: the dump file, and
  # PGPASSWORD (from ROOT.xml there) and the binaries run in the container. The systemctl lines have nothing to stop.
  awk '/^\*\*Native\.\*\* The installer writes a dump/{f=1} f && /^```bash$/{b=1; next} f && b && /^```$/{exit} f && b' "$ROOT/docs/OPERATIONS.md" > "$WORK/native-doc.sh"
  sed 's/^/      | /' "$WORK/native-doc.sh"
  run_native() {  # run_native DUMP: the doc's restore pipeline with DUMP
    { docker exec -i "$PG16" pg_restore -f - < "$1" && printf 'COMMIT;\n\\echo Restored.\n'; } \
      | docker exec -i -e PGPASSWORD=rr "$PG16" \
        psql -h 127.0.0.1 -p 5432 -U mdmesh -d mdmesh -X -q -v ON_ERROR_STOP=1 -o /dev/null -c BEGIN \
          -c "$(grep -o '\-c "DROP SCHEMA[^"]*"' "$WORK/native-doc.sh" | sed 's/^-c "//; s/"$//')" -f -
  }
  check "the doc's reset statement was found" grep -q 'DROP SCHEMA public CASCADE; CREATE SCHEMA public AUTHORIZATION pg_database_owner' "$WORK/native-doc.sh"
  check "the doc's pipeline is the one run here" grep -qF "{ pg_restore -f - <dump-file> && printf 'COMMIT;\n\\\\echo Restored.\n'; }" "$WORK/native-doc.sh"
  local f1 f2
  f1="$(nfp)"
  head -c 20000 "$WORK/pre040.dump" > "$WORK/pre040-short.dump"
  run_native "$WORK/pre040-short.dump" > "$WORK/native-short.out" 2>&1
  sed 's/^/      out: /' "$WORK/native-short.out" | grep -v 'drop cascades to' | head -4
  check "a damaged dump: no Restored." bash -c "! grep -qx 'Restored.' '$WORK/native-short.out'"
  f2="$(nfp)"
  check "a damaged dump left the database exactly as it was ($f1 = $f2)" test "$f1" = "$f2"
  run_native "$WORK/pre040.dump" > "$WORK/native.out" 2>&1
  sed 's/^/      out: /' "$WORK/native.out" | grep -v 'drop cascades to' | head -4
  check "the 0.4.0 dump onto the 0.5.1 database: Restored." grep -qx 'Restored.' "$WORK/native.out"
  check "the 0.4.0 database is back (no configurationAppPermissions)" test "$(nq "SELECT to_regclass('configurationapppermissions') IS NULL")" = t
  check "public: owner pg_database_owner, USAGE for PUBLIC, as PostgreSQL 16 makes it ($(nq "SELECT nspowner::regrole || ' ' || nspacl::text FROM pg_namespace WHERE nspname = 'public'"))" \
    test "$(nq "SELECT nspowner::regrole || ' ' || nspacl::text FROM pg_namespace WHERE nspname = 'public'")" = 'pg_database_owner {pg_database_owner=UC/pg_database_owner,=U/pg_database_owner}'
  docker rm -f -v "$PG16" >>"$LOG" 2>&1
}

echo "rollback-e2e: work directory $WORK; phases: ${PHASES[*]}"
setup_dind || { fail "setup"; exit 1; }
for p in "${PHASES[@]}"; do
  case "$p" in
    first) phase_first ;;
    040) phase_040 ;;
    main) phase_main ;;
    native) phase_native ;;
    *) echo "unknown phase $p"; exit 2 ;;
  esac
done
