#!/usr/bin/env bash
#
# Smoke test of a BUILT updater/recovery supervisor image, run the way docker-compose.yml and
# docker-compose.release.yml run it: working dir /project with a project dir bind-mounted there (and
# no server.js in it), a /backups volume, no GitHub repo configured. Guards #27 — a relative
# entrypoint resolved against a compose `working_dir` crash-looped the supervisor on every Docker
# deploy — and checks the routes Caddy proxies to it plus the tools apply.sh/rollback.sh need.
# Needs only a Docker daemon (no host ports published; probes run inside the container).
#
# Usage: scripts/supervisor-smoke.sh <image>      e.g. scripts/supervisor-smoke.sh mdmesh-supervisor:ci
set -euo pipefail
IMG="${1:?usage: scripts/supervisor-smoke.sh <image>}"
NAME="mdmesh-supervisor-smoke-$$"
PROJ="$(mktemp -d)"
cleanup() { docker rm -f "$NAME" >/dev/null 2>&1 || true; rm -rf "$PROJ"; }
trap cleanup EXIT
printf 'COMPOSE_PROJECT_NAME=mdmesh\n' > "$PROJ/.env"   # a deploy dir holds .env + compose files, never server.js

PASS=0
pass() { echo "  ok   $*"; PASS=$((PASS + 1)); }
fail() {
  echo "  FAIL $*"
  echo "--- container logs ---"; docker logs "$NAME" 2>&1 | tail -20
  exit 1
}
running() { [ "$(docker inspect -f '{{.State.Running}}' "$NAME" 2>/dev/null)" = true ]; }
in_c() { docker exec "$NAME" "$@"; }
code() { in_c curl -s -o /dev/null -w '%{http_code}' -m 5 "$@"; }

docker run -d --name "$NAME" -w /project -v "$PROJ:/project" --tmpfs /backups \
  -e GITHUB_REPO= -e CURRENT_VERSION=0.0.0 -e COMPOSE_PROJECT_NAME=mdmesh "$IMG" >/dev/null

up=0
for _ in $(seq 1 30); do
  running || fail "container exited (code $(docker inspect -f '{{.State.ExitCode}}' "$NAME")) — entrypoint must not depend on the working dir"
  if in_c curl -fsS -m 2 http://127.0.0.1:9000/healthz >/dev/null 2>&1; then up=1; break; fi
  sleep 1
done
[ "$up" = 1 ] || fail "/healthz never answered on :9000"
pass "starts under -w /project and answers /healthz"

check() { local what="$1"; shift; if "$@"; then pass "$what"; else fail "$what"; fi; }
status_json()  { in_c curl -fsS -m 5 http://127.0.0.1:9000/update/status | grep -q '"applySupported":true'; }
recovery_page() { in_c curl -fsS -m 5 http://127.0.0.1:9000/recovery | grep -q 'MDMesh — Recovery'; }
apk_route()    { [ "$(code http://127.0.0.1:9000/update/agent.apk)" = 404 ]; }
apply_gated()  { [ "$(code -X POST http://127.0.0.1:9000/update/apply)" = 403 ]; }
toolchain()    { in_c sh -c 'command -v bash && command -v minisign && command -v pg_dump && command -v psql && docker compose version' >/dev/null; }

check "/update/status serves JSON (apply supported)" status_json
check "/recovery serves the recovery page" recovery_page
check "/update/agent.apk answers (404: no verified release yet)" apk_route
check "/update/apply is CSRF-gated (403 without the console header)" apply_gated
check "recovery token generated on /backups" in_c test -s /backups/recovery.token
check "apply.sh/rollback.sh executable, release pubkey baked" \
  in_c sh -c 'test -x /app/apply.sh && test -x /app/rollback.sh && test -s /app/minisign.pub'
check "bash, minisign, pg_dump/psql, docker compose present" toolchain

sleep 3
check "still running after the first poll" running
echo "supervisor smoke: $PASS passed"
