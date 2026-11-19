#!/usr/bin/env bash
#
# Edge check: does the edge config the stack ships actually load, and do its health routes tell the truth?
#   1. `caddy validate` docker/Caddyfile in every hosting mode, using the SAME caddy image the web image is built
#      FROM (read from docker/web.Dockerfile, so CI and production cannot disagree). validate, not adapt: adapt
#      accepts values (e.g. a bad TRUSTED_PROXIES) that only fail when the config is provisioned. Your own rules
#      (site.d/*.caddy) are imported, and a broken one is an error. Behind your own proxy, the TRUSTED_PROXIES list
#      docker/edge-peers.sh makes (`host`, single addresses) and the lists it refuses.
#   2. `docker compose config -q` for every compose file / overlay / profile combination the installers use; what the
#      proxy overlay publishes and passes to the edge.
#   2b. A digest pin comes out of `docker compose config --images` whole, as supervisor/apply.sh requires of it.
#   3. /healthz and /healthz/supervisor (with or without a trailing slash), served by that caddy against stub upstreams:
#      503 + reason while the upstream is down or failing (not the SPA's or the recovery page's catch-all 200), 200 "ok"
#      once it answers.
#   3b. The client address the server is told, in each hosting mode (docker/edge-peers.sh picks the :80 rule): exactly
#      Caddy's view of the client, whatever forwarding headers the client sends (echo upstream). Cloudflare mode
#      publishes no port and gets its mode from COMPOSE_PROFILES; every other setup, the dev stack included, does not.
#   3c. Ambiguous server paths (";", encoded ";", "/" or "\") are refused with 400, and every response carries the
#      browser security headers (HSTS only over HTTPS).
#   3d. Behind your own proxy (:8080, plain HTTP and the HTTPS origin): the client address from your proxy only, path
#      checks, headers, and the origin certificate with and without a server name. Uses a throwaway internal network.
#   4. The dev stack (docker-compose.dev.yml over docker-compose.yml) with docker/dev.env alone, and that the overlay
#      refuses to load without it.
# Guards the v0.3.1 Cloudflare-mode break: `email {$ACME_EMAIL}` with the empty ACME_EMAIL that setup.sh and
# quickstart.sh write made the edge refuse to start, and nothing in CI parsed the Caddyfile.
# Needs only a Docker daemon (+ compose plugin). Throwaway --rm containers, no network, no ports (3b and 3d add test
# addresses to their own container's loopback, so they run with NET_ADMIN; 3d's two containers share an --internal
# network named mdmesh-edgecheck-<pid>, removed at the end).
#
# Usage: scripts/edge-check.sh [caddyfile]      (default: docker/Caddyfile)
set -euo pipefail
# CDPATH='' on every relative cd: with CDPATH exported, cd prints the directory it found, which would end up in the
# captured path.
ROOT="$(CDPATH='' cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CADDYFILE="${1:-$ROOT/docker/Caddyfile}"
[ -f "$CADDYFILE" ] || { echo "no such Caddyfile: $CADDYFILE" >&2; exit 2; }
# Absolute path (it is bind-mounted, and we cd below); cd+pwd rather than realpath, which stock macOS lacks.
CADDYFILE="$(CDPATH='' cd "$(dirname "$CADDYFILE")" && pwd)/$(basename "$CADDYFILE")"
cd "$ROOT"   # compose -f paths below are relative to the repo root

CADDY_IMAGE="$(awk 'tolower($1) == "from" && $2 ~ /^caddy[:@]/ { print $2; exit }' "$ROOT/docker/web.Dockerfile")"
[ -n "$CADDY_IMAGE" ] || { echo "could not find the caddy base image in docker/web.Dockerfile" >&2; exit 2; }

FAILED=0
pass() { echo "  ok   $*"; }
fail() { echo "  FAIL $*"; FAILED=$((FAILED + 1)); }

# --- 1. Caddyfile, one run per hosting mode. Each mode is the exact environment the caddy container gets, put through
# docker/edge-peers.sh as the image's entrypoint does. ---
EDGE_PEERS="$ROOT/docker/edge-peers.sh"
echo "Caddyfile: $CADDYFILE  (caddy image: $CADDY_IMAGE)"
MODES=(
  "own-domain|SITE_ADDRESS=mdm.example.com ACME_EMAIL=ops@example.com COMPOSE_PROFILES="
  "cloudflare|SITE_ADDRESS=:80 ACME_EMAIL= COMPOSE_PROFILES=cloudflare"
  "cloudflare, compose file from before v0.3.5|SITE_ADDRESS=:80 ACME_EMAIL= CLOUDFLARED_WAIT=0"
  "own-domain, no email|SITE_ADDRESS=mdm.example.com ACME_EMAIL= COMPOSE_PROFILES="
  "behind a proxy|SITE_ADDRESS=:80 ACME_EMAIL= COMPOSE_PROFILES= TRUSTED_PROXIES=private_ranges"
  "own-domain, empty TRUSTED_PROXIES (as the compose files pass it)|SITE_ADDRESS=mdm.example.com ACME_EMAIL=ops@example.com COMPOSE_PROFILES= TRUSTED_PROXIES="
  ":80, empty TRUSTED_PROXIES|SITE_ADDRESS=:80 ACME_EMAIL= COMPOSE_PROFILES= TRUSTED_PROXIES="
  # Behind your own proxy, as docker-compose.proxy.yml passes it (TRUSTED_PROXIES reaches the edge as
  # EDGE_TRUSTED_PROXIES; `host` needs a gateway, which --network none lacks: it has its own check below).
  "behind your own proxy|SITE_ADDRESS=:8080 ACME_EMAIL= COMPOSE_PROFILES= TRUSTED_PROXIES= MDMESH_EXPOSURE=proxy EDGE_TRUSTED_PROXIES=192.0.2.10,2001:db8::/32 BASE_URL=https://mdm.example.com MDMESH_ORIGIN_TLS=0"
  "behind your own proxy, HTTPS origin|SITE_ADDRESS=:8080 ACME_EMAIL= COMPOSE_PROFILES= TRUSTED_PROXIES= MDMESH_EXPOSURE=proxy EDGE_TRUSTED_PROXIES=10.0.0.0/8 BASE_URL=https://mdm.example.com:8443 MDMESH_ORIGIN_TLS=1"
)
for mode in "${MODES[@]}"; do
  label="${mode%%|*}"
  env_args=()
  read -r -a pairs <<< "${mode#*|}"
  for kv in "${pairs[@]}"; do env_args+=(-e "$kv"); done
  if out="$(docker run --rm --network none "${env_args[@]}" \
      -v "$CADDYFILE:/etc/caddy/Caddyfile:ro" -v "$EDGE_PEERS:/edge-peers.sh:ro" "$CADDY_IMAGE" \
      sh -c '. /edge-peers.sh && caddy validate --config /etc/caddy/Caddyfile --adapter caddyfile' 2>&1)"; then
    pass "caddy validate: $label (${mode#*|})"
  else
    fail "caddy validate: $label (${mode#*|})"
    printf '%s\n' "$out" | grep -v '^{"level":"info"' | tail -5 | sed 's/^/         /'
  fi
done

# Your own rules: each site.d/*.caddy is imported into the site (its header is in the adapted config), and a broken one
# is an error, not ignored. The directory is mounted read-only, as the compose files mount it.
SITE_D="$(mktemp -d)"
chmod 755 "$SITE_D"
printf 'header X-Own-Rule "from site.d"\n' > "$SITE_D/own.caddy"; chmod 644 "$SITE_D/own.caddy"
if out="$(docker run --rm --network none -e SITE_ADDRESS=:80 -e ACME_EMAIL= -v "$CADDYFILE:/etc/caddy/Caddyfile:ro" \
    -v "$SITE_D:/etc/caddy/site.d:ro" "$CADDY_IMAGE" caddy adapt --config /etc/caddy/Caddyfile --adapter caddyfile 2>&1)" \
   && grep -qF 'X-Own-Rule' <<<"$out"; then
  pass "site.d/*.caddy is imported into the site"
else
  fail "site.d/*.caddy is not imported into the site"; printf '%s\n' "$out" | tail -3 | sed 's/^/         /'
fi
printf 'no_such_directive here\n' > "$SITE_D/own.caddy"
if docker run --rm --network none -e SITE_ADDRESS=:80 -e ACME_EMAIL= -v "$CADDYFILE:/etc/caddy/Caddyfile:ro" \
    -v "$SITE_D:/etc/caddy/site.d:ro" "$CADDY_IMAGE" caddy validate --config /etc/caddy/Caddyfile --adapter caddyfile >/dev/null 2>&1; then
  fail "a broken site.d/*.caddy passes caddy validate"
else
  pass "a broken site.d/*.caddy fails caddy validate"
fi
rm -f "$SITE_D/own.caddy"; rmdir "$SITE_D"

# Behind your own proxy, docker/edge-peers.sh turns TRUSTED_PROXIES into Caddy's list: `host` is this container's
# default gateway(s) (`ip` stubbed here: --network none has none), a lone address a /32 or /128; and it stops the edge on
# what would trust nobody or far more than a proxy, saying why.
echo "Behind your own proxy: TRUSTED_PROXIES as the edge reads it (docker/edge-peers.sh):"
# shellcheck disable=SC2016  # expanded by the container's shell
PEERS_PROBE='ip() { case "$*" in "-4 route show default") echo "default via 172.19.0.1 dev eth0" ;; "-6 route show default") [ -z "${V6:-}" ] || echo "default via fd00::1 dev eth0 metric 1024" ;; esac; }
. /edge-peers.sh && printf "%s|%s|%s|%s\n" "$PROXY_TRUSTED_PEERS" "$SITE_ADDRESS" "$EDGE_TLS" "${EDGE_ORIGIN_NAME:-}"'
# EDGE_TRUSTED_PROXIES | extra env | want ("refused" or the probe's output)
PEER_CASES=(
  "host||172.19.0.1/32|:8080|off|"
  "host|V6=1|172.19.0.1/32 fd00::1/128|:8080|off|"
  "192.0.2.10, 2001:db8::1 10.0.0.0/8|MDMESH_ORIGIN_TLS=1|192.0.2.10/32 2001:db8::1/128 10.0.0.0/8|https://mdm.example.com:8080|internal|mdm.example.com"
  "host||refused"
  "0.0.0.0/0||refused"
  "::/0||refused"
  "10.0.0.0/7||refused"
  "private_ranges||refused"
  "proxy.example.com||refused"
  "192.0.2.10/033||refused"
  "||refused"
)
for c in "${PEER_CASES[@]}"; do
  IFS='|' read -r list extra want <<< "$c"
  envs=(-e MDMESH_EXPOSURE=proxy -e "EDGE_TRUSTED_PROXIES=$list" -e BASE_URL=https://mdm.example.com -e SITE_ADDRESS=:8080)
  [ -z "$extra" ] || envs+=(-e "$extra")
  probe=$PEERS_PROBE
  # The "host" case that must be refused: no gateway at all (`ip` answers nothing).
  if [ "$list|$want" = "host|refused" ]; then probe=${probe/default via 172.19.0.1 dev eth0/}; fi
  rc=0; out="$(docker run --rm --network none "${envs[@]}" -v "$EDGE_PEERS:/edge-peers.sh:ro" "$CADDY_IMAGE" sh -c "$probe" 2>&1)" || rc=$?
  if [ "$want" = refused ]; then
    if [ "$rc" != 0 ] && grep -qF 'MDMesh edge:' <<<"$out" && grep -qF 'Not starting' <<<"$out"; then pass "TRUSTED_PROXIES='$list': the edge refuses to start"
    else fail "TRUSTED_PROXIES='$list': not refused (rc $rc: $out)"; fi
  elif [ "$rc" = 0 ] && [ "$out" = "${c#*|*|}" ]; then pass "TRUSTED_PROXIES='$list'${extra:+ $extra}: $out"
  else fail "TRUSTED_PROXIES='$list'${extra:+ $extra}: got '$out', want '${c#*|*|}'"; fi
done

# --- 2. Compose files, each combination setup.sh / quickstart.sh / DEPLOY.md / DEV.md use. ---
# A fixed env file with the required (:?) variables, so a developer's own .env or shell cannot change the result.
ENVF="$(mktemp)"
ENVF_CF="$(mktemp)"   # the same, plus the COMPOSE_PROFILES line setup.sh / quickstart.sh write for tunnel mode
ENVF_PX="$(mktemp)"   # the same, plus the lines they write behind your own proxy (install/lib/modes.sh, mdm_mode_env)
ENVF_NOTP="$(mktemp)"   # that, without TRUSTED_PROXIES
trap 'rm -f "$ENVF" "$ENVF_CF" "$ENVF_PX" "$ENVF_NOTP"' EXIT
printf 'DB_PASSWORD=x\nBASE_URL=https://mdm.example.com\nHASH_SECRET=x\n' > "$ENVF"
{ cat "$ENVF"; echo COMPOSE_PROFILES=cloudflare; } > "$ENVF_CF"
{ cat "$ENVF"; echo MDMESH_BIND=127.0.0.1:8080; echo 'TRUSTED_PROXIES="host"'; echo MDMESH_ORIGIN_TLS=0
  echo MDMESH_SITE_DIR=/srv/mdmesh/site.d; } > "$ENVF_PX"
grep -v '^TRUSTED_PROXIES=' "$ENVF_PX" > "$ENVF_NOTP"
unset COMPOSE_FILE COMPOSE_PROFILES COMPOSE_PROJECT_NAME MDMESH_BIND TRUSTED_PROXIES MDMESH_ORIGIN_TLS MDMESH_SITE_DIR

echo "Compose (docker compose config -q):"
COMBOS=(
  "-f docker-compose.yml"
  "-f docker-compose.yml --profile cloudflare"
  "-f docker-compose.yml -f docker-compose.domain.yml"
  "-f docker-compose.release.yml"
  "-f docker-compose.release.yml --profile cloudflare"
  "-f docker-compose.release.yml -f docker-compose.domain.yml"
  "PX -f docker-compose.yml -f docker-compose.proxy.yml"
  "PX -f docker-compose.release.yml -f docker-compose.proxy.yml"
)
for combo in "${COMBOS[@]}"; do
  envf=$ENVF; case "$combo" in "PX "*) envf=$ENVF_PX; combo=${combo#PX } ;; esac
  read -r -a args <<< "$combo"
  if out="$(docker compose --env-file "$envf" "${args[@]}" config -q 2>&1)"; then
    pass "compose $combo"
  else
    fail "compose $combo"
    printf '%s\n' "$out" | tail -5 | sed 's/^/         /'
  fi
done
# Behind your own proxy, the edge publishes exactly MDMESH_BIND (127.0.0.1:8080 by default) to its :8080, believes
# TRUSTED_PROXIES under another name (an older web image, after a rollback, sees an empty TRUSTED_PROXIES), and mounts
# site.d from MDMESH_SITE_DIR. Without TRUSTED_PROXIES the overlay refuses to load.
for file in docker-compose.yml docker-compose.release.yml; do
  if ! out="$(docker compose --env-file "$ENVF_PX" -f "$file" -f docker-compose.proxy.yml config --format json caddy 2>&1)"; then
    fail "compose -f $file -f docker-compose.proxy.yml config caddy"; printf '%s\n' "$out" | tail -5 | sed 's/^/         /'; continue
  fi
  got="$(python3 -c 'import json,sys; c=json.load(sys.stdin)["services"]["caddy"]; e=c["environment"]
print(" ".join(sorted("%s:%s->%s" % (p.get("host_ip",""), p["published"], p["target"]) for p in c.get("ports", []))),
      e.get("MDMESH_EXPOSURE"), e.get("EDGE_TRUSTED_PROXIES"), repr(e.get("TRUSTED_PROXIES")), e.get("SITE_ADDRESS"),
      [v["source"] for v in c["volumes"] if v["target"] == "/etc/caddy/site.d"])' <<<"$out" 2>&1)"
  want="127.0.0.1:8080->8080 proxy host '' :8080 ['/srv/mdmesh/site.d']"
  if [ "$got" = "$want" ]; then pass "$file + proxy overlay: caddy $got"; else fail "$file + proxy overlay: caddy '$got', want '$want'"; fi
  out="$(docker compose --env-file "$ENVF_NOTP" -f "$file" -f docker-compose.proxy.yml config -q 2>&1)" && rc=0 || rc=$?
  if [ "$rc" = 0 ]; then
    fail "$file + proxy overlay loads without TRUSTED_PROXIES"
  elif grep -qF 'TRUSTED_PROXIES' <<<"$out"; then pass "$file + proxy overlay refuses to load without TRUSTED_PROXIES"
  else fail "$file + proxy overlay failed without TRUSTED_PROXIES, but not naming it: $(tail -1 <<<"$out")"; fi
done

echo "Pinned images (docker compose config --images, as supervisor/apply.sh asks it):"
PIN="0.0.0@sha256:$(printf 'a%.0s' {1..64})"
for file in docker-compose.yml docker-compose.release.yml; do
  if ! out="$(IMAGE_OWNER=example SERVER_VERSION="$PIN" WEB_VERSION="$PIN" \
    docker compose --env-file "$ENVF" -f "$file" config --images server caddy 2>&1)"; then
    fail "compose -f $file config --images"
    printf '%s\n' "$out" | tail -5 | sed 's/^/         /'
    continue
  fi
  for want in "ghcr.io/example/mdmesh-server:$PIN" "ghcr.io/example/mdmesh-web:$PIN"; do
    if grep -qxF -- "$want" <<<"$out"; then pass "$file: ${want%%:*}"; else fail "$file: no line $want in: $out"; fi
  done
done

# --- 3. Health routes, run under the same caddy image. /healthz and /healthz/supervisor, with or without a trailing
# slash, must answer 503 + reason (never the SPA's or the recovery page's catch-all 200) when their upstream is down or
# failing, and 200 "ok" when it answers.
# Hermetic like the rest: one --rm container, --network none, with `server` and `supervisor` pinned to loopback where
# throwaway caddy stubs (admin off) stand in for them. Output uses the ok/FAIL format above. curl, not the image's
# busybox wget: wget discards the body of a non-2xx response, and the 503 reasons are part of the contract.
HEALTH_PROBE="$(cat <<'PROBE'
set -u
F=0
SPA='<!doctype html><title>spa</title>'
DOWN='server unavailable'
SUP_DOWN='supervisor unavailable'
command -v curl >/dev/null || { echo "  FAIL no curl in the caddy image (the probe reads 503 bodies with it)"; exit 1; }
mkdir -p /srv /tmp/stub && echo "$SPA" > /srv/index.html
http_code() { # http_code <url> [body file]: the status code, or nothing when there is no response (curl's 000)
  c="$(curl -s -m 5 -o "${2:-/dev/null}" -w '%{http_code}' "$1" || true)"; [ "$c" = 000 ] || printf '%s' "$c"
}
stub() {   # stub <port> <site body>: start a throwaway upstream on :<port> and wait until it answers
  printf '{\n\tadmin off\n}\n:%s {\n%s\n}\n' "$1" "$2" > "/tmp/stub/$1"
  caddy run --config "/tmp/stub/$1" --adapter caddyfile >/dev/null 2>&1 &
  echo $! > "/tmp/stub/$1.pid"
  for _ in $(seq 1 50); do [ -n "$(http_code "http://127.0.0.1:$1/")" ] && return 0; sleep 0.1; done
  echo "  FAIL stub :$1 never answered"; exit 1
}
unstub() { kill "$(cat "/tmp/stub/$1.pid")"; wait "$(cat "/tmp/stub/$1.pid")" 2>/dev/null || true; }
expect() { # expect <label> <path> <status> [body]
  rm -f /tmp/body
  code="$(http_code "http://127.0.0.1$2" /tmp/body)"; body="$(cat /tmp/body 2>/dev/null || true)"
  if [ "$code" = "$3" ] && { [ -z "${4:-}" ] || [ "$body" = "$4" ]; }; then echo "  ok   $1"
  else echo "  FAIL $1 (expected $3${4:+ $4}, got ${code:-no response}${body:+: $(printf '%.40s' "$body")})"; F=$((F + 1)); fi
}
caddy start --config /etc/caddy/Caddyfile --adapter caddyfile >/dev/null 2>&1 || { echo "  FAIL edge did not start"; exit 1; }
expect "/healthz: 503 while the server is down"                    /healthz             503 "$DOWN"
expect "/healthz/: 503 while the server is down"                   /healthz/            503 "$DOWN"
expect "/healthz/supervisor: 503 while the supervisor is down"     /healthz/supervisor  503 "$SUP_DOWN"
expect "/healthz/supervisor/: 503 while the supervisor is down"    /healthz/supervisor/ 503 "$SUP_DOWN"
expect "/: still the SPA"                                           /                    200 "$SPA"
expect "/healthz/x: still the SPA (health paths match exactly)"     /healthz/x           200 "$SPA"
stub 9000 '	respond /healthz "ok" 200
	respond /healthz/supervisor* "not rewritten" 404
	respond "recovery page" 200'
expect "/healthz: 503 while the server is down, not the recovery page's 200"  /healthz  503 "$DOWN"
expect "/healthz/: 503 while the server is down, not the recovery page's 200" /healthz/ 503 "$DOWN"
expect "/healthz/supervisor: 200 ok once the supervisor answers"   /healthz/supervisor  200 ok
expect "/healthz/supervisor/: 200 ok once the supervisor answers"  /healthz/supervisor/ 200 ok
expect "/rest/*: server down still falls back to the recovery page" /rest/public/name   200 'recovery page'
stub 8080 '	respond /rest/public/name "{\"status\":\"OK\"}" 200
	respond "not found" 404'
expect "/healthz: 200 ok once the server answers /rest/public/name"  /healthz  200 ok
expect "/healthz/: 200 ok once the server answers /rest/public/name" /healthz/ 200 ok
unstub 8080
stub 8080 '	respond "boom" 500'
expect "/healthz: 503 while the server answers 5xx"                 /healthz             503 "$DOWN"
unstub 9000
stub 9000 '	respond "boom" 500'
expect "/healthz/supervisor: 503 while the supervisor answers 5xx"  /healthz/supervisor  503 "$SUP_DOWN"
exit "$F"
PROBE
)"
echo "Health routes (SITE_ADDRESS=:80, stub upstreams):"
out="$(docker run --rm --network none --add-host server:127.0.0.1 --add-host supervisor:127.0.0.1 \
    -e SITE_ADDRESS=:80 -e ACME_EMAIL= -v "$CADDYFILE:/etc/caddy/Caddyfile:ro" "$CADDY_IMAGE" \
    sh -c "$HEALTH_PROBE" 2>&1)" && rc=0 || rc=$?
printf '%s\n' "$out"
n="$(grep -c '^  FAIL' <<<"$out" || true)"
FAILED=$((FAILED + n))
if [ "$rc" -ne 0 ] && [ "$n" -eq 0 ]; then fail "health routes: probe container exited $rc"; fi

# --- 3b. The client address the server is told. The server records a device's public IP from the X-Forwarded-For
# (and X-Real-IP) the edge sends, so every request to it must carry exactly Caddy's own view of the client, whatever
# CF-Connecting-IP / X-Forwarded-For / X-Real-IP the client sent. Each setup is one --rm container with the env the caddy
# container gets, put through docker/edge-peers.sh like the image's entrypoint. A stub `server` echoes the headers it
# receives, and answers /rest/public/name (the /healthz probe) with 200 only when they are the expected ones. Clients
# connect from extra addresses on the container's loopback (NET_ADMIN, still --network none):
#   127.0.0.2    own-domain: a device on the internet (outside the default TRUSTED_PROXIES of 127.0.0.1/32)
#   172.18.0.1   a private peer: cloudflared, a proxy of your own, or Docker's port proxy (the dev stack's 8088).
#                Added as 172.18.0.1/16, the stack network's shape, so a `cloudflared` at 172.18.0.3 is on it.
#   203.0.113.1  a public peer
# own-domain runs SITE_ADDRESS=localhost: the own-domain shape (HTTPS on :443, from Caddy's internal CA for localhost).
CLIENT_PROBE="$(cat <<'PROBE'
set -u
F=0
command -v curl >/dev/null || { echo "  FAIL no curl in the caddy image"; exit 1; }
ip addr add 172.18.0.1/16 dev lo && ip addr add 203.0.113.1/32 dev lo || { echo "  FAIL could not add test addresses"; exit 1; }
. /edge-peers.sh
case "$KIND" in   # the address the /healthz request below must reach the server with
  own-domain) HZ=127.0.0.2 ;; tunnel) HZ=203.0.113.9 ;; proxy) HZ=198.51.100.4 ;; *) HZ=172.18.0.1 ;;
esac
mkdir -p /srv /tmp/stub && echo spa > /srv/index.html
cat > /tmp/stub/echo <<STUB
{
	admin off
}
:8080 {
	@right {
		path /rest/public/name
		header X-Forwarded-For $HZ
		header X-Real-IP $HZ
		header !CF-Connecting-IP
	}
	route {
		respond @right "ok" 200
		respond /rest/public/name "wrong forwarding headers" 500
		respond "xff=[{header.X-Forwarded-For}] xri=[{header.X-Real-IP}] cf=[{header.CF-Connecting-IP}]" 200
	}
}
STUB
caddy run --config /tmp/stub/echo --adapter caddyfile >/dev/null 2>&1 &
caddy start --config /etc/caddy/Caddyfile --adapter caddyfile >/dev/null 2>&1 || { echo "  FAIL edge did not start"; exit 1; }
if [ "$KIND" = own-domain ]; then
  BASE=https://localhost; CURL="curl -sk -m 5 --resolve localhost:443:127.0.0.1"
else
  BASE=http://127.0.0.1; CURL="curl -s -m 5"
fi
# The edge (for own-domain, also its local certificate) and the stub take a moment to come up.
for _ in $(seq 1 100); do case "$($CURL "$BASE/rest/echo" 2>/dev/null || true)" in xff=*) break ;; esac; sleep 0.1; done
expect() { # expect <label> <from address> <path> <want> [curl args...]
  label="$1"; from="$2"; path="$3"; want="$4"; shift 4
  got="$($CURL --interface "$from" "$@" "$BASE$path" 2>/dev/null || true)"
  if [ "$got" = "$want" ]; then echo "  ok   $MODE: $label"
  else echo "  FAIL $MODE: $label (expected '$want', got '${got:-no response}')"; F=$((F + 1)); fi
}
echoed() { echo "xff=[$1] xri=[$1] cf=[]"; }
case "$KIND" in
  own-domain)
    expect "no forwarding headers: the server is told the device's address" 127.0.0.2 /rest/echo "$(echoed 127.0.0.2)"
    expect "forged CF-Connecting-IP / X-Forwarded-For / X-Real-IP: replaced or dropped" 127.0.0.2 /rest/echo \
      "$(echoed 127.0.0.2)" -H 'CF-Connecting-IP: 6.6.6.6' -H 'X-Forwarded-For: 7.7.7.7' -H 'X-Real-IP: 8.8.8.8'
    expect "a forged X-Forwarded-For list: replaced, not appended to" 127.0.0.2 /rest/echo "$(echoed 127.0.0.2)" \
      -H 'X-Forwarded-For: 7.7.7.7, 127.0.0.1'
    expect "/healthz: the same headers reach the server" 127.0.0.2 /healthz ok \
      -H 'CF-Connecting-IP: 6.6.6.6' -H 'X-Forwarded-For: 7.7.7.7' -H 'X-Real-IP: 8.8.8.8' ;;
  tunnel)
    expect "from cloudflared with CF-Connecting-IP: the server is told that address" 172.18.0.1 /rest/echo \
      "$(echoed 203.0.113.9)" -H 'CF-Connecting-IP: 203.0.113.9' -H 'X-Forwarded-For: 6.6.6.6, 203.0.113.9'
    expect "from cloudflared without CF-Connecting-IP: the peer, not X-Forwarded-For" 172.18.0.1 /rest/echo \
      "$(echoed 172.18.0.1)" -H 'X-Forwarded-For: 6.6.6.6' -H 'X-Real-IP: 8.8.8.8'
    expect "a CF-Connecting-IP that is not an address: the peer" 172.18.0.1 /rest/echo "$(echoed 172.18.0.1)" \
      -H 'CF-Connecting-IP: 6.6.6.6.6'
    expect "from a public peer: its CF-Connecting-IP is not believed" 203.0.113.1 /rest/echo "$(echoed 203.0.113.1)" \
      -H 'CF-Connecting-IP: 6.6.6.6'
    expect "/healthz: the same headers reach the server" 172.18.0.1 /healthz ok \
      -H 'CF-Connecting-IP: 203.0.113.9' -H 'X-Real-IP: 8.8.8.8' ;;
  http)
    expect "from a private peer: its CF-Connecting-IP / X-Forwarded-For are not believed" 172.18.0.1 /rest/echo \
      "$(echoed 172.18.0.1)" -H 'CF-Connecting-IP: 6.6.6.6' -H 'X-Forwarded-For: 7.7.7.7' -H 'X-Real-IP: 8.8.8.8'
    expect "from a public peer: the same" 203.0.113.1 /rest/echo "$(echoed 203.0.113.1)" \
      -H 'CF-Connecting-IP: 6.6.6.6'
    expect "/healthz: the same headers reach the server" 172.18.0.1 /healthz ok -H 'CF-Connecting-IP: 6.6.6.6' ;;
  proxy)
    expect "from the proxy: the last X-Forwarded-For entry, not a CF-Connecting-IP" 172.18.0.1 /rest/echo \
      "$(echoed 198.51.100.4)" -H 'CF-Connecting-IP: 6.6.6.6' -H 'X-Forwarded-For: 6.6.6.6, 198.51.100.4'
    expect "from any other peer: its headers are not believed" 203.0.113.1 /rest/echo "$(echoed 203.0.113.1)" \
      -H 'X-Forwarded-For: 6.6.6.6'
    expect "/healthz: the same headers reach the server" 172.18.0.1 /healthz ok \
      -H 'X-Forwarded-For: 6.6.6.6, 198.51.100.4' ;;
esac
exit "$F"
PROBE
)"
# label | kind | env the caddy container gets (space-separated) | extra docker run arguments
SETUPS=(
  "own-domain|own-domain|SITE_ADDRESS=localhost COMPOSE_PROFILES=|"
  "cloudflare|tunnel|SITE_ADDRESS=:80 COMPOSE_PROFILES=cloudflare|"
  "cloudflare, compose file from before v0.3.5|tunnel|SITE_ADDRESS=:80|--add-host cloudflared:172.18.0.3"
  ":80, compose file from before v0.3.5, a cloudflared off caddy's network|http|SITE_ADDRESS=:80 CLOUDFLARED_WAIT=1|--add-host cloudflared:198.51.100.7"
  "dev stack (:80, no tunnel)|http|SITE_ADDRESS=:80 COMPOSE_PROFILES=|"
  ":80, compose file from before v0.3.5, no cloudflared|http|SITE_ADDRESS=:80 CLOUDFLARED_WAIT=1|"
  "a proxy in front of :80 at 172.18.0.1 (TRUSTED_PROXIES, no proxy mode)|proxy|SITE_ADDRESS=:80 COMPOSE_PROFILES= TRUSTED_PROXIES=172.18.0.1/32|"
  "own-domain, empty TRUSTED_PROXIES (as the compose files pass it)|own-domain|SITE_ADDRESS=localhost COMPOSE_PROFILES= TRUSTED_PROXIES=|"
  "dev stack, empty TRUSTED_PROXIES|http|SITE_ADDRESS=:80 COMPOSE_PROFILES= TRUSTED_PROXIES=|"
)
for setup in "${SETUPS[@]}"; do
  IFS='|' read -r label kind envs extra <<< "$setup"
  env_args=(-e "MODE=$label" -e "KIND=$kind" -e ACME_EMAIL=)
  read -r -a pairs <<< "$envs"
  for kv in "${pairs[@]}"; do env_args+=(-e "$kv"); done
  read -r -a extra_args <<< "$extra"
  echo "Client address sent to the server ($label: $envs):"
  out="$(docker run --rm --network none --cap-add NET_ADMIN --add-host server:127.0.0.1 --add-host supervisor:127.0.0.1 \
      "${extra_args[@]}" "${env_args[@]}" -v "$CADDYFILE:/etc/caddy/Caddyfile:ro" -v "$EDGE_PEERS:/edge-peers.sh:ro" \
      "$CADDY_IMAGE" sh -c "$CLIENT_PROBE" 2>&1)" && rc=0 || rc=$?
  printf '%s\n' "$out"
  n="$(grep -c '^  FAIL' <<<"$out" || true)"
  FAILED=$((FAILED + n))
  if [ "$rc" -ne 0 ] && [ "$n" -eq 0 ]; then fail "client address ($label): probe container exited $rc"; fi
done

# --- 3c. Ambiguous server paths and browser security headers. A ";" path parameter, or an encoded ";", "/" or "\"
# (or a raw "\"), in a /rest, /files or /agent path is refused with 400 and never proxied (the query string is not
# looked at); plain paths still reach the server. Every response carries nosniff, no-framing and Referrer-Policy, the
# upstream's own values replaced, the error fallback (recovery page) included; HSTS only over HTTPS (own domain) or
# when a proxy in front says X-Forwarded-Proto: https. Stub server and supervisor, as in 3.
EDGE_PROBE="$(cat <<'PROBE'
set -u
F=0
command -v curl >/dev/null || { echo "  FAIL no curl in the caddy image"; exit 1; }
mkdir -p /srv /tmp/stub && echo spa > /srv/index.html
stub() {   # stub <port> <site body>
  printf '{\n\tadmin off\n}\n:%s {\n%s\n}\n' "$1" "$2" > "/tmp/stub/$1"
  caddy run --config "/tmp/stub/$1" --adapter caddyfile >/dev/null 2>&1 &
  echo $! > "/tmp/stub/$1.pid"
  for _ in $(seq 1 50); do [ "$(curl -s -m 2 -o /dev/null -w '%{http_code}' "http://127.0.0.1:$1/")" != 000 ] && return 0; sleep 0.1; done
  echo "  FAIL stub :$1 never answered"; exit 1
}
unstub() { kill "$(cat "/tmp/stub/$1.pid")"; wait "$(cat "/tmp/stub/$1.pid")" 2>/dev/null || true; }
caddy start --config /etc/caddy/Caddyfile --adapter caddyfile >/dev/null 2>&1 || { echo "  FAIL edge did not start"; exit 1; }
if [ "$KIND" = https ]; then
  BASE=https://localhost; CURL="curl -sk -m 5 --path-as-is --resolve localhost:443:127.0.0.1"
else
  BASE=http://127.0.0.1; CURL="curl -s -m 5 --path-as-is"
fi
for _ in $(seq 1 100); do [ "$($CURL -o /dev/null -w '%{http_code}' "$BASE/" 2>/dev/null)" = 200 ] && break; sleep 0.1; done
stub 8080 '	header X-Frame-Options SAMEORIGIN
	respond "server" 200'
stub 9000 '	respond "supervisor" 200'
status() { # status <label> <path> <want status> [want body]
  rm -f /tmp/body
  got="$($CURL -o /tmp/body -w '%{http_code}' "$BASE$2" 2>/dev/null || true)"; body="$(cat /tmp/body 2>/dev/null || true)"
  if [ "$got" = "$3" ] && { [ -z "${4:-}" ] || [ "$body" = "$4" ]; }; then echo "  ok   $MODE: $1"
  else echo "  FAIL $MODE: $1 (expected $3${4:+ $4}, got $got${body:+: $(printf '%.40s' "$body")})"; F=$((F + 1)); fi
}
headers() { # headers <label> <path> <want HSTS: yes|no> [curl args...]
  label="$1"; path="$2"; hsts="$3"; shift 3
  h="$($CURL -D - -o /dev/null "$@" "$BASE$path" 2>/dev/null | tr -d '\r')"
  bad=""
  printf '%s\n' "$h" | grep -qix 'x-content-type-options: nosniff' || bad="$bad nosniff"
  printf '%s\n' "$h" | grep -qix 'x-frame-options: DENY' || bad="$bad x-frame-options"
  [ "$(printf '%s\n' "$h" | grep -ic '^x-frame-options:')" = 1 ] || bad="$bad one-x-frame-options"
  printf '%s\n' "$h" | grep -qix "content-security-policy: frame-ancestors 'none'" || bad="$bad csp"
  printf '%s\n' "$h" | grep -qix 'referrer-policy: same-origin' || bad="$bad referrer-policy"
  if [ "$hsts" = yes ]; then printf '%s\n' "$h" | grep -qix 'strict-transport-security: max-age=15552000' || bad="$bad hsts"
  else printf '%s\n' "$h" | grep -qi '^strict-transport-security:' && bad="$bad unexpected-hsts"; fi
  if [ -z "$bad" ]; then echo "  ok   $MODE: headers on $label"
  else echo "  FAIL $MODE: headers on $label:$bad"; F=$((F + 1)); fi
}
for p in '/rest/private;x=y/users/current' '/rest;x/private/users/current' '/rest/private/users/current;x' \
    '/rest/private%3Bx/users/current' '/rest/private%3bx/users/current' '/rest%2Fprivate/users/current' \
    '/rest/private%2fusers/current' '/rest/private%5Cusers/current' '/rest\private/users/current' \
    '/files;x/app.apk' '/files/app.apk;x' '/files/a%2F..%2Fb.apk' '/agent/ws/dev-1;x' '/REST/private;x/users/current'; do
  status "$p: refused at the edge" "$p" 400 ""
done
status "/rest/private/users/current: proxied" /rest/private/users/current 200 server
status "a query string with ; and escapes: proxied" '/rest/public/name?x=;%3B%2F%5C' 200 server
status "/files/my%20app.apk: proxied" '/files/my%20app.apk' 200 server
status "/files/agent.apk: the supervisor's mirror" /files/agent.apk 200 supervisor
status "/devices/a;b: still the SPA" '/devices/a;b' 200 spa
if [ "$KIND" = https ]; then HSTS=yes; else HSTS=no; fi
headers "/ (the console)" / "$HSTS"
headers "/rest (the server's own X-Frame-Options replaced)" /rest/public/name "$HSTS"
headers "/recovery" /recovery "$HSTS"
headers "a refused path" '/rest/private;x/users/current' "$HSTS"
if [ "$KIND" != https ]; then
  headers "/ behind a proxy that says X-Forwarded-Proto: https" / yes -H 'X-Forwarded-Proto: https'
fi
unstub 8080
headers "the error fallback while the server is down" /rest/public/name "$HSTS"
exit "$F"
PROBE
)"
for setup in "own domain (HTTPS)|https|SITE_ADDRESS=localhost" ":80 (tunnel, LAN, dev stack, own proxy)|http|SITE_ADDRESS=:80"; do
  IFS='|' read -r label kind envs <<< "$setup"
  echo "Ambiguous paths and security headers ($label):"
  out="$(docker run --rm --network none --add-host server:127.0.0.1 --add-host supervisor:127.0.0.1 \
      -e "MODE=$label" -e "KIND=$kind" -e "$envs" -e ACME_EMAIL= -v "$CADDYFILE:/etc/caddy/Caddyfile:ro" \
      "$CADDY_IMAGE" sh -c "$EDGE_PROBE" 2>&1)" && rc=0 || rc=$?
  printf '%s\n' "$out"
  n="$(grep -c '^  FAIL' <<<"$out" || true)"
  FAILED=$((FAILED + n))
  if [ "$rc" -ne 0 ] && [ "$n" -eq 0 ]; then fail "ambiguous paths and headers ($label): probe container exited $rc"; fi
done

# --- 3d. Behind your own proxy (docker-compose.proxy.yml): the edge on :8080, in plain HTTP and as the HTTPS origin
# (Caddy's own CA). The edge listens on :8080 like the server, so the stub server (echoing the forwarding headers it gets,
# and the supervisor) runs in a second container, on an internal network of this check's own. Clients connect from
# addresses on the edge container's loopback: 192.0.2.1 is your proxy (TRUSTED_PROXIES), 203.0.113.1 anyone else.
PROXY_PROBE="$(cat <<'PROBE'
set -u
F=0
command -v curl >/dev/null || { echo "  FAIL no curl in the caddy image"; exit 1; }
ip addr add 192.0.2.1/32 dev lo && ip addr add 203.0.113.1/32 dev lo || { echo "  FAIL could not add test addresses"; exit 1; }
. /edge-peers.sh
mkdir -p /srv && echo spa > /srv/index.html
caddy start --config /etc/caddy/Caddyfile --adapter caddyfile >/dev/null 2>&1 || { echo "  FAIL edge did not start"; exit 1; }
if [ "$KIND" = https ]; then
  CA=/data/caddy/pki/authorities/local/root.crt
  for _ in $(seq 1 100); do [ -s "$CA" ] && break; sleep 0.1; done
  BASE=https://mdm.example.com:8080; CURL="curl -s -m 5 --path-as-is --cacert $CA --resolve mdm.example.com:8080:127.0.0.1"
else
  BASE=http://127.0.0.1:8080; CURL="curl -s -m 5 --path-as-is"
fi
for _ in $(seq 1 100); do case "$($CURL "$BASE/rest/echo" 2>/dev/null || true)" in xff=*) break ;; esac; sleep 0.1; done
expect() { # expect <label> <from address> <path> <want> [curl args...]
  label="$1"; from="$2"; path="$3"; want="$4"; shift 4
  got="$($CURL --interface "$from" "$@" "$BASE$path" 2>/dev/null || true)"
  if [ "$got" = "$want" ]; then echo "  ok   $MODE: $label"
  else echo "  FAIL $MODE: $label (expected '$want', got '${got:-no response}')"; F=$((F + 1)); fi
}
echoed() { echo "xff=[$1] xri=[$1] cf=[]"; }
expect "from your proxy: the last X-Forwarded-For entry, not a CF-Connecting-IP or X-Real-IP" 192.0.2.1 /rest/echo \
  "$(echoed 198.51.100.4)" -H 'CF-Connecting-IP: 6.6.6.6' -H 'X-Real-IP: 7.7.7.7' -H 'X-Forwarded-For: 6.6.6.6, 198.51.100.4'
expect "from anyone else: the peer, whatever it sends" 203.0.113.1 /rest/echo "$(echoed 203.0.113.1)" \
  -H 'X-Forwarded-For: 198.51.100.4' -H 'CF-Connecting-IP: 6.6.6.6'
expect "/healthz: the same headers reach the server" 192.0.2.1 /healthz ok -H 'X-Forwarded-For: 6.6.6.6, 198.51.100.4'
code="$($CURL --interface 192.0.2.1 -o /dev/null -w '%{http_code}' "$BASE/rest/private;x/users/current" 2>/dev/null || true)"
if [ "$code" = 400 ]; then echo "  ok   $MODE: a ; path: 400"; else echo "  FAIL $MODE: a ; path: $code, want 400"; F=$((F + 1)); fi
h="$($CURL --interface 192.0.2.1 -D - -o /dev/null -H 'X-Forwarded-Proto: https' "$BASE/" 2>/dev/null | tr -d '\r')"
if printf '%s\n' "$h" | grep -qix 'x-frame-options: DENY' && printf '%s\n' "$h" | grep -qix 'strict-transport-security: max-age=15552000'; then
  echo "  ok   $MODE: security headers, HSTS behind HTTPS"
else echo "  FAIL $MODE: security headers missing: $(printf '%s' "$h" | tr '\n' ' ')"; F=$((F + 1)); fi
if [ "$KIND" = https ]; then
  got="$(curl -s -m 5 --cacert "$CA" --interface 192.0.2.1 --connect-to mdm.example.com:8080:127.0.0.1:8080 \
    https://mdm.example.com:8080/rest/echo 2>/dev/null || true)"
  [ -n "$got" ] && echo "  ok   $MODE: the certificate is for mdm.example.com and chains to Caddy's root" \
    || { echo "  FAIL $MODE: no answer for mdm.example.com with Caddy's root"; F=$((F + 1)); }
  # A proxy that sends no server name (nginx without proxy_ssl_server_name) still gets the certificate (default_sni).
  # Like every proxy in front of this origin it sends the public Host, which the site matches.
  got="$(curl -sk -m 5 --interface 192.0.2.1 -H 'Host: mdm.example.com' -H 'X-Forwarded-For: 198.51.100.4' \
    https://127.0.0.1:8080/rest/echo 2>/dev/null || true)"
  if [ "$got" = "$(echoed 198.51.100.4)" ]; then echo "  ok   $MODE: no server name sent: still served"
  else echo "  FAIL $MODE: no server name sent: '${got:-no response}'"; F=$((F + 1)); fi
  code="$(curl -s -m 5 -o /dev/null -w '%{http_code}' http://127.0.0.1:80/ 2>/dev/null || true)"
  [ "$code" = 000 ] && echo "  ok   $MODE: no HTTP listener on :80 (no redirect server)" \
    || { echo "  FAIL $MODE: something answers on :80 ($code)"; F=$((F + 1)); }
fi
exit "$F"
PROBE
)"
STUB_NET="mdmesh-edgecheck-$$"
STUB_CT="mdmesh-edgecheck-stub-$$"
STUB_FILE="$(mktemp)"
cleanup_stub() { docker rm -f "$STUB_CT" >/dev/null 2>&1 || true; docker network rm "$STUB_NET" >/dev/null 2>&1 || true; rm -f "$STUB_FILE"; }
trap 'cleanup_stub; rm -f "$ENVF" "$ENVF_CF" "$ENVF_PX" "$ENVF_NOTP"' EXIT
cat > "$STUB_FILE" <<'STUB'
{
	admin off
}
:8080 {
	@right {
		path /rest/public/name
		header X-Forwarded-For 198.51.100.4
		header X-Real-IP 198.51.100.4
		header !CF-Connecting-IP
	}
	route {
		respond @right "ok" 200
		respond /rest/public/name "wrong forwarding headers" 500
		respond "xff=[{header.X-Forwarded-For}] xri=[{header.X-Real-IP}] cf=[{header.CF-Connecting-IP}]" 200
	}
}
:9000 {
	respond "supervisor" 200
}
STUB
chmod 644 "$STUB_FILE"
if docker network create --internal "$STUB_NET" >/dev/null \
   && docker run -d --name "$STUB_CT" --network "$STUB_NET" --network-alias server --network-alias supervisor \
        -v "$STUB_FILE:/etc/caddy/Caddyfile:ro" "$CADDY_IMAGE" >/dev/null; then
  for setup in "behind your own proxy|http|0" "behind your own proxy, HTTPS origin|https|1"; do
    IFS='|' read -r label kind tls <<< "$setup"
    echo "Behind your own proxy ($label):"
    out="$(docker run --rm --network "$STUB_NET" --cap-add NET_ADMIN -e "MODE=$label" -e "KIND=$kind" -e ACME_EMAIL= \
        -e MDMESH_EXPOSURE=proxy -e SITE_ADDRESS=:8080 -e EDGE_TRUSTED_PROXIES=192.0.2.1 -e TRUSTED_PROXIES= \
        -e BASE_URL=https://mdm.example.com -e "MDMESH_ORIGIN_TLS=$tls" -e COMPOSE_PROFILES= \
        -v "$CADDYFILE:/etc/caddy/Caddyfile:ro" -v "$EDGE_PEERS:/edge-peers.sh:ro" "$CADDY_IMAGE" sh -c "$PROXY_PROBE" 2>&1)" \
      && rc=0 || rc=$?
    printf '%s\n' "$out"
    n="$(grep -c '^  FAIL' <<<"$out" || true)"
    FAILED=$((FAILED + n))
    if [ "$rc" -ne 0 ] && [ "$n" -eq 0 ]; then fail "behind your own proxy ($label): probe container exited $rc"; fi
  done
else
  fail "behind your own proxy: could not start the stub server on network $STUB_NET"
fi
cleanup_stub

# What the caddy service gets from each compose combination. Tunnel mode believes CF-Connecting-IP from any private
# peer, which holds only because it publishes no port (a device reaches Caddy through cloudflared alone) and because
# only it passes COMPOSE_PROFILES=cloudflare. Everything that publishes a port, the dev stack included, must not.
echo "Edge ports and mode (docker compose config caddy):"
caddy_block() { awk '/^  caddy:$/ { p = 1; next } /^  [^ ]/ || /^[^ ]/ { p = 0 } p'; }
# env file | compose arguments | published ports | COMPOSE_PROFILES the caddy container gets
PORT_COMBOS=(
  "$ENVF_CF|-f docker-compose.yml --profile cloudflare|no|cloudflare"
  "$ENVF_CF|-f docker-compose.release.yml --profile cloudflare|no|cloudflare"
  "$ENVF|-f docker-compose.yml -f docker-compose.domain.yml|some|"
  "$ENVF|-f docker-compose.release.yml -f docker-compose.domain.yml|some|"
  "$ENVF_PX|-f docker-compose.yml -f docker-compose.proxy.yml|some|"
  "$ENVF_PX|-f docker-compose.release.yml -f docker-compose.proxy.yml|some|"
  "docker/dev.env||some|"
)
for combo in "${PORT_COMBOS[@]}"; do
  IFS='|' read -r envf cargs want_ports want_profiles <<< "$combo"
  read -r -a args <<< "$cargs"
  what="${cargs:-the dev stack (docker/dev.env)}"
  if ! out="$(docker compose --env-file "$envf" "${args[@]}" config caddy 2>&1)"; then
    fail "compose $what config caddy"; printf '%s\n' "$out" | tail -5 | sed 's/^/         /'; continue
  fi
  block="$(caddy_block <<<"$out")"
  if grep -qE '^    ports:' <<<"$block"; then has=some; else has=no; fi
  if ! grep -qE '^      COMPOSE_PROFILES:' <<<"$block"; then profiles='(not passed)'
  else profiles="$(awk '$1 == "COMPOSE_PROFILES:" { v = $2; gsub(/"/, "", v); print v; exit }' <<<"$block")"; fi
  if [ "$has" = "$want_ports" ] && [ "$profiles" = "$want_profiles" ]; then
    pass "compose $what: caddy publishes $has ports, COMPOSE_PROFILES='$profiles'"
  else
    fail "compose $what: caddy publishes $has ports, COMPOSE_PROFILES='$profiles'; want $want_ports ports, '$want_profiles'"
  fi
done

# --- 4. The dev stack, configured by docker/dev.env ALONE (not the fixed env file above), so a variable
# docker-compose.yml requires but dev.env lacks fails here. dev.env pins the project name: without it the project
# would be named after the checkout directory ("MDMesh" -> mdmesh, the production project). And `config`/`up` of the
# overlay must refuse to run without dev.env, or a setup.sh .env in the same checkout (COMPOSE_PROJECT_NAME=mdmesh)
# would point the dev stack at the production containers and database volume. The guard only covers commands that
# interpolate the files (`config`, `up`); `down -v`, `stop`, `rm`, `exec`, `logs` and `ps` still run against the
# project that .env names, so docs/DEV.md passes --env-file docker/dev.env on every command. ---
echo "Dev stack (docker compose --env-file docker/dev.env):"
# Shell variables beat --env-file, so unset every variable the compose files require (${VAR:?...}, which includes the
# MDMESH_DEV guard): only dev.env may supply them here, whatever the developer's shell exports.
# shellcheck disable=SC2046  # word splitting intended: one variable name per word
unset $(grep -ohE '\$\{[A-Za-z_][A-Za-z0-9_]*:\?' docker-compose.yml docker-compose.dev.yml | sed 's/^\${//; s/:?$//' | sort -u)
if out="$(docker compose --env-file docker/dev.env config 2>&1)"; then
  proj="$(printf '%s\n' "$out" | awk '$1 == "name:" { print $2; exit }')"
  if [ "$proj" = mdmesh-dev ]; then pass "compose --env-file docker/dev.env (project $proj)"
  else fail "compose --env-file docker/dev.env: project is '$proj', want mdmesh-dev"; fi
else
  fail "compose --env-file docker/dev.env"
  printf '%s\n' "$out" | tail -5 | sed 's/^/         /'
fi
# Refused by the guard itself: an unrelated config error must not count as the refusal.
if out="$(docker compose --env-file "$ENVF" -f docker-compose.yml -f docker-compose.dev.yml config -q 2>&1)"; then
  fail "the dev overlay's config ran without docker/dev.env"
elif grep -qF 'run the dev stack with --env-file docker/dev.env' <<<"$out"; then
  pass "the dev overlay refuses config without docker/dev.env"
else
  fail "the dev overlay failed without docker/dev.env, but not on its MDMESH_DEV guard"
  printf '%s\n' "$out" | tail -5 | sed 's/^/         /'
fi

if [ "$FAILED" -ne 0 ]; then echo "edge-check: $FAILED check(s) failed"; exit 1; fi
echo "edge-check: all checks passed"
