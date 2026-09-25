#!/usr/bin/env bash
#
# Edge check: does the edge config the stack ships actually load?
#   1. `caddy validate` docker/Caddyfile in every hosting mode, using the SAME caddy image the web image is built
#      FROM (read from docker/web.Dockerfile, so CI and production cannot disagree). validate, not adapt: adapt
#      accepts values (e.g. a bad TRUSTED_PROXIES) that only fail when the config is provisioned.
#   2. `docker compose config -q` for every compose file / overlay / profile combination the installers use.
# Guards the v0.3.1 Cloudflare-mode break: `email {$ACME_EMAIL}` with the empty ACME_EMAIL that setup.sh and
# quickstart.sh write made the edge refuse to start, and nothing in CI parsed the Caddyfile.
# Needs only a Docker daemon (+ compose plugin). Throwaway --rm containers, no network, no ports.
#
# Usage: scripts/edge-check.sh [caddyfile]      (default: docker/Caddyfile)
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CADDYFILE="$(realpath "${1:-$ROOT/docker/Caddyfile}")"
[ -f "$CADDYFILE" ] || { echo "no such Caddyfile: $CADDYFILE" >&2; exit 2; }
cd "$ROOT"   # compose -f paths below are relative to the repo root

CADDY_IMAGE="$(awk 'tolower($1) == "from" && $2 ~ /^caddy[:@]/ { print $2; exit }' "$ROOT/docker/web.Dockerfile")"
[ -n "$CADDY_IMAGE" ] || { echo "could not find the caddy base image in docker/web.Dockerfile" >&2; exit 2; }

FAILED=0
pass() { echo "  ok   $*"; }
fail() { echo "  FAIL $*"; FAILED=$((FAILED + 1)); }

# --- 1. Caddyfile, one run per hosting mode. Each mode is the exact environment the caddy container gets. ---
echo "Caddyfile: $CADDYFILE  (caddy image: $CADDY_IMAGE)"
MODES=(
  "own-domain|SITE_ADDRESS=mdm.example.com ACME_EMAIL=ops@example.com"
  "cloudflare|SITE_ADDRESS=:80 ACME_EMAIL="
  "own-domain, no email|SITE_ADDRESS=mdm.example.com ACME_EMAIL="
  "behind a proxy|SITE_ADDRESS=:80 ACME_EMAIL= TRUSTED_PROXIES=private_ranges"
)
for mode in "${MODES[@]}"; do
  label="${mode%%|*}"
  env_args=()
  read -r -a pairs <<< "${mode#*|}"
  for kv in "${pairs[@]}"; do env_args+=(-e "$kv"); done
  if out="$(docker run --rm --network none "${env_args[@]}" \
      -v "$CADDYFILE:/etc/caddy/Caddyfile:ro" "$CADDY_IMAGE" \
      caddy validate --config /etc/caddy/Caddyfile --adapter caddyfile 2>&1)"; then
    pass "caddy validate: $label (${mode#*|})"
  else
    fail "caddy validate: $label (${mode#*|})"
    printf '%s\n' "$out" | grep -v '^{"level":"info"' | tail -5 | sed 's/^/         /'
  fi
done

# --- 2. Compose files, each combination setup.sh / quickstart.sh / DEPLOY.md / DEV.md use. ---
# A fixed env file with the required (:?) variables, so a developer's own .env or shell cannot change the result.
ENVF="$(mktemp)"
trap 'rm -f "$ENVF"' EXIT
printf 'DB_PASSWORD=x\nBASE_URL=https://mdm.example.com\nHASH_SECRET=x\n' > "$ENVF"
unset COMPOSE_FILE COMPOSE_PROFILES COMPOSE_PROJECT_NAME

echo "Compose (docker compose config -q):"
COMBOS=(
  "-f docker-compose.yml"
  "-f docker-compose.yml --profile cloudflare"
  "-f docker-compose.yml -f docker-compose.domain.yml"
  "-f docker-compose.release.yml"
  "-f docker-compose.release.yml --profile cloudflare"
  "-f docker-compose.release.yml -f docker-compose.domain.yml"
  "-f docker-compose.dev.yml"
)
for combo in "${COMBOS[@]}"; do
  read -r -a args <<< "$combo"
  if out="$(docker compose --env-file "$ENVF" "${args[@]}" config -q 2>&1)"; then
    pass "compose $combo"
  else
    fail "compose $combo"
    printf '%s\n' "$out" | tail -5 | sed 's/^/         /'
  fi
done

if [ "$FAILED" -ne 0 ]; then echo "edge-check: $FAILED check(s) failed"; exit 1; fi
echo "edge-check: all checks passed"
