#!/usr/bin/env bash
#
# Edge check: does the edge config the stack ships actually load, and do its health routes tell the truth?
#   1. `caddy validate` docker/Caddyfile in every hosting mode, using the SAME caddy image the web image is built
#      FROM (read from docker/web.Dockerfile, so CI and production cannot disagree). validate, not adapt: adapt
#      accepts values (e.g. a bad TRUSTED_PROXIES) that only fail when the config is provisioned. Your own rules
#      (site.d/*.caddy) are imported, and a broken one is an error. Behind your own proxy, the TRUSTED_PROXIES list
#      docker/edge-peers.sh makes (`host`, single addresses) and the lists it refuses.
#   1c. docs/proxies' nginx, Apache and Caddy configurations parse (nginx -t, httpd -t, caddy validate).
#   1e. docs/proxies' HTTPS-origin lines, in nginx, Apache, Caddy and Traefik in front of a real edge: they reach it with
#       MDMesh's CA and refuse it with another (each checks the edge's certificate).
#   2. `docker compose config -q` for every compose file / overlay / profile combination the installers use; what the
#      proxy overlay publishes and passes to the edge.
#   2a. The host ports a setup.sh re-run reads from `docker compose config` (install/lib/ports.sh), per compose list.
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
  # Moved out of the proxy mode with TRUSTED_PROXIES="host" left in .env: the edge still starts (host dropped).
  "own-domain, host left from the proxy mode|SITE_ADDRESS=mdm.example.com ACME_EMAIL= COMPOSE_PROFILES= TRUSTED_PROXIES=host"
  # Behind your own proxy, as docker-compose.proxy.yml passes it (TRUSTED_PROXIES reaches the edge as
  # EDGE_TRUSTED_PROXIES; `host` needs a gateway, which --network none lacks: it has its own check below).
  "behind your own proxy|SITE_ADDRESS=:8080 ACME_EMAIL= COMPOSE_PROFILES= TRUSTED_PROXIES= MDMESH_EXPOSURE=proxy EDGE_TRUSTED_PROXIES=192.0.2.10,2001:db8::/32 BASE_URL=https://mdm.example.com MDMESH_ORIGIN_TLS=0"
  "behind your own proxy, HTTPS origin|SITE_ADDRESS=:8080 ACME_EMAIL= COMPOSE_PROFILES= TRUSTED_PROXIES= MDMESH_EXPOSURE=proxy EDGE_TRUSTED_PROXIES=10.0.0.0/8 BASE_URL=https://mdm.example.com:8443 MDMESH_ORIGIN_TLS=1"
  "behind your own proxy, HTTPS origin on an IPv6 address|SITE_ADDRESS=:8080 ACME_EMAIL= COMPOSE_PROFILES= TRUSTED_PROXIES= MDMESH_EXPOSURE=proxy EDGE_TRUSTED_PROXIES=192.0.2.10 BASE_URL=https://[2001:db8::5] MDMESH_ORIGIN_TLS=1"
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

# Outside the proxy mode, `host` in TRUSTED_PROXIES is dropped with a warning; the other entries stay, on both listeners.
for tp in "host 192.0.2.10" "host,192.0.2.10"; do
  out="$(docker run --rm --network none -e "TRUSTED_PROXIES=$tp" -e COMPOSE_PROFILES= -e SITE_ADDRESS=mdm.example.com \
      -v "$EDGE_PEERS:/edge-peers.sh:ro" "$CADDY_IMAGE" sh -c '. /edge-peers.sh && echo "peers=$TRUSTED_PROXIES|$HTTP_TRUSTED_PEERS"' 2>&1)" || true
  if grep -qxF 'peers=192.0.2.10|192.0.2.10' <<<"$out" && grep -qF 'TRUSTED_PROXIES says host, which only the proxy mode' <<<"$out"; then
    pass "outside the proxy mode, host in TRUSTED_PROXIES='$tp' is dropped, saying so; 192.0.2.10 stays"
  else
    fail "outside the proxy mode, host in TRUSTED_PROXIES='$tp': $out"
  fi
done

# Your own rules are loaded only from files their owner alone can change (docker/edge-peers.sh, at the edge's start): a
# link, a group- or world-writable file, or one of another owner is left out, saying why; the rest is imported, and the
# edge starts either way. An open site.d loads none. Made inside the container (as root, so ownership can be set).
# shellcheck disable=SC2016  # expanded by the container's shell
SITE_FILES_PROBE='set -u; F=0
t() { l=$1; shift; if "$@"; then echo "  ok   site.d files: $l"; else echo "  FAIL site.d files: $l"; F=$((F + 1)); fi; }
mk() { printf "header X-%s \"1\"\n" "$1" > "/sd/$1.caddy"; chown "$2" "/sd/$1.caddy"; chmod "$3" "/sd/$1.caddy"; }
mkdir -p /sd && chmod 755 /sd && chown 1000:1000 /sd
mk good 1000 644; mk root 0 644; mk open 1000 666; mk group 1000 664; mk stranger 12345 644
ln -s /sd/good.caddy /sd/link.caddy
cp /sd/good.caddy /sd/.hidden.caddy; chown 1000 /sd/.hidden.caddy
printf "header X-Shared \"1\"\n" > /sd/shared.conf; chmod 666 /sd/shared.conf
printf "import /etc/caddy/site.d/shared.conf\n" > /sd/imports.caddy; chown 1000 /sd/imports.caddy; chmod 644 /sd/imports.caddy
# The forms Caddy also reads as an import: quoted, ended by the closing quote alone, after a BOM or a Unicode blank.
imp() { printf "$2/etc/caddy/site.d/shared.conf${3:-}\n" > "/sd/$1.caddy"; chown 1000 "/sd/$1.caddy"; chmod 644 "/sd/$1.caddy"; }
imp dq "  \"import\" "; imp bq "\`import\` "; imp dqnb "\"import\""; imp bqnb "\`import\`"; imp dqdq "\"import\"\"" "\""
imp bom "\357\273\277import "; imp nbsp "\302\240import "
loaded() { [ "$(ls "$EDGE_SITE_D" | tr "\n" " ")" = "$1" ]; }
logged() { grep -qF "$1" /tmp/log; }
adapted() { caddy adapt --config /etc/caddy/Caddyfile --adapter caddyfile > /tmp/adapted 2>&1 && grep -qi x-good /tmp/adapted && ! grep -qi x-open /tmp/adapted && ! grep -qi x-shared /tmp/adapted; }
valid() { caddy validate --config /etc/caddy/Caddyfile --adapter caddyfile > /tmp/valid 2>&1; }
EDGE_SITE_SRC=/sd; export EDGE_SITE_SRC
. /edge-peers.sh 2> /tmp/log
t "only the files their owner alone can change are loaded (good, root)" loaded "good.caddy root.caddy "
for n in open group stranger; do t "$n.caddy left out, saying why" logged "site.d/$n.caddy is not loaded: other users may change it"; done
t "link.caddy left out, saying why" logged "site.d/link.caddy is not loaded: it is a link"
t ".hidden.caddy left out, saying why" logged "site.d/.hidden.caddy is not loaded: a hidden file"
for n in imports dq bq dqnb bqnb dqdq bom nbsp; do t "$n.caddy left out, saying why" logged "site.d/$n.caddy is not loaded: it imports another file"; done
t "the edge imports the loaded ones only" adapted
t "the edge configuration is valid" valid
chmod 777 /sd
. /edge-peers.sh 2> /tmp/log
t "an open site.d loads none" loaded ""
t "an open site.d: saying so" logged "none of your own rules are loaded"
t "an open site.d: the edge configuration is still valid" valid
exit "$F"'
out="$(docker run --rm --network none -e SITE_ADDRESS=:80 -e ACME_EMAIL= -e COMPOSE_PROFILES= \
    -v "$CADDYFILE:/etc/caddy/Caddyfile:ro" -v "$EDGE_PEERS:/edge-peers.sh:ro" "$CADDY_IMAGE" sh -c "$SITE_FILES_PROBE" 2>&1)" && rc=0 || rc=$?
printf '%s\n' "$out" | grep -E '^  (ok|FAIL) '
n="$(grep -c '^  FAIL' <<<"$out" || true)"; FAILED=$((FAILED + n))
if [ "$rc" -ne 0 ] && [ "$n" -eq 0 ]; then fail "site.d files: probe exited $rc"; printf '%s\n' "$out" | tail -5; fi

# A caddy command run later through `docker compose exec` gets the container's configured environment, not the one
# docker/edge-peers.sh computed at start. The edge's caddy (docker/edge-caddy.sh, mounted where the web image installs it)
# reads that start's settings first, so an exec'd reload keeps them: here behind your own proxy with the HTTPS origin,
# a reload in a bare environment keeps the proxy's address as trusted peer and the internal-CA site, and loads no
# site.d file the start left out. MDMESH_RAW_SITE_D=1 lets validate and adapt (only) check site.d as it is now, through
# the start's own check, to try a new file; a reload with it still loads the start's copy. And with no saved settings at
# all (a failed save), nothing raw is imported and a reload is refused rather than run without them.
# shellcheck disable=SC2016  # expanded by the container's shell
RELOAD_PROBE='set -u; F=0
t() { l=$1; shift; if "$@"; then echo "  ok   exec reload: $l"; else echo "  FAIL exec reload: $l"; F=$((F + 1)); fi; }
mk() { printf "header X-%s \"1\"\n" "$1" > "/etc/caddy/site.d/$1.caddy"; chmod "$2" "/etc/caddy/site.d/$1.caddy"; }
mkdir -p /etc/caddy/site.d && chmod 755 /etc/caddy/site.d; mk good 644; mk open 666
. /edge-peers.sh 2> /tmp/log
caddy start --config /etc/caddy/Caddyfile --adapter caddyfile > /tmp/start 2>&1 || { echo "  FAIL exec reload: edge did not start"; cat /tmp/start; exit 1; }
bare() { env -i PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin HOME=/root SITE_ADDRESS=:8080 TRUSTED_PROXIES= "$@"; }
cfg() { rm -f /tmp/cfg; for _ in 1 2 3 4 5; do wget -qO /tmp/cfg http://127.0.0.1:2019/config/ 2> /dev/null && return 0; sleep 1; done; }
has() { grep -qi -- "$1" /tmp/cfg; }
bare caddy reload --config /etc/caddy/Caddyfile --adapter caddyfile > /tmp/reload 2>&1; t "succeeds" [ $? = 0 ]
cfg
t "keeps the proxy as trusted peer" has "192.0.2.10/32"
t "keeps the HTTPS origin (internal CA)" has "\"internal\""
t "loads the checked rule" has x-good
t "does not load a rule the start left out" sh -c "! grep -qi x-open /tmp/cfg"
mk new 644
bare env MDMESH_RAW_SITE_D=1 caddy adapt --config /etc/caddy/Caddyfile --adapter caddyfile > /tmp/raw 2>&1
t "MDMESH_RAW_SITE_D=1 adapt checks a file added since the start" grep -qi x-new /tmp/raw
t "  through the same check as the start (the open file stays out)" sh -c "! grep -qi x-open /tmp/raw"
bare env MDMESH_RAW_SITE_D=1 caddy reload --config /etc/caddy/Caddyfile --adapter caddyfile > /tmp/reload 2>&1; rrc=$?; cfg
t "MDMESH_RAW_SITE_D=1 reload still loads the copy checked at start only" sh -c "[ $rrc = 0 ] && grep -qi x-good /tmp/cfg && ! grep -qi x-open /tmp/cfg && ! grep -qi x-new /tmp/cfg"
caddy stop > /dev/null 2>&1
mv /run/mdmesh-edge.env /run/saved.env
bare caddy adapt --config /etc/caddy/Caddyfile --adapter caddyfile > /tmp/nosave 2>&1
t "no saved settings: still no raw rule" sh -c "! grep -qi x-open /tmp/nosave"
bare caddy reload --config /etc/caddy/Caddyfile --adapter caddyfile > /tmp/noreload 2>&1; rc=$?
t "no saved settings: a reload is refused, saying to restart" sh -c "[ $rc != 0 ] && grep -q \"restart it\" /tmp/noreload"
exit "$F"'
out="$(docker run --rm --network none -e MDMESH_EXPOSURE=proxy -e EDGE_TRUSTED_PROXIES=192.0.2.10 -e BASE_URL=https://mdm.example.com \
    -e MDMESH_ORIGIN_TLS=1 -e SITE_ADDRESS=:8080 -e ACME_EMAIL= -e TRUSTED_PROXIES= -e EDGE_BIND=127.0.0.1:8080 \
    -v "$CADDYFILE:/etc/caddy/Caddyfile:ro" -v "$EDGE_PEERS:/edge-peers.sh:ro" -v "$ROOT/docker/edge-caddy.sh:/usr/local/bin/caddy:ro" \
    "$CADDY_IMAGE" sh -c "$RELOAD_PROBE" 2>&1)" && rc=0 || rc=$?
printf '%s\n' "$out" | grep -E '^  (ok|FAIL) '
n="$(grep -c '^  FAIL' <<<"$out" || true)"; FAILED=$((FAILED + n))
if [ "$rc" -ne 0 ] && [ "$n" -eq 0 ]; then fail "exec reload: probe exited $rc"; printf '%s\n' "$out" | tail -5; fi

# Your own rules: each site.d/*.caddy is imported into the site (its header is in the adapted config), and a broken one
# is an error, not ignored. The directory is mounted read-only, as the compose files mount it, and imported as it is
# (EDGE_SITE_D; the edge itself imports only the copy docker/edge-peers.sh checked, below).
# The temporary directories (and the image-start block's container and image) are removed by cleanup_tmp, also from the
# exit traps, so an abort or Ctrl-C leaves nothing behind: SITE_D here, PX_D (a throwaway key) and IMG_D below.
SITE_D=''; PX_D=''; IMG_D=''; IMG_TAG="mdmesh-edgecheck-web:$$"; IMG_CT="mdmesh-edgecheck-web-$$"
# cleanup_img: the image-start block's container, image and directory. Docker's own output goes to the block's log,
# which goes with the directory.
cleanup_img() {
  [ -n "${IMG_D:-}" ] && [ -d "$IMG_D" ] || return 0
  docker rm -f "$IMG_CT" >> "$IMG_D/docker.log" 2>&1 || true
  docker rmi "$IMG_TAG" >> "$IMG_D/docker.log" 2>&1 || true
  rm -f "$IMG_D/site.d/good.caddy" "$IMG_D/site.d/open.caddy" "$IMG_D/site700/rule.caddy" "$IMG_D/site700/open.caddy" "$IMG_D/Dockerfile" \
    "$IMG_D/build.log" "$IMG_D/docker.log" "$IMG_D/validate.log" "$IMG_D/raw.log" "$IMG_D/reload.log"
  rmdir "$IMG_D/site.d" "$IMG_D/site700" "$IMG_D" 2>&1 | sed 's/^/         /' || true
  IMG_D=''
}
cleanup_tmp() {
  if [ "$(type -t cleanup_ot)" = function ]; then cleanup_ot; fi
  if [ -n "${SITE_D:-}" ] && [ -d "$SITE_D" ]; then rm -f "$SITE_D"/*.caddy; rmdir "$SITE_D" || true; SITE_D=''; fi
  if [ -n "${PX_D:-}" ] && [ -d "$PX_D" ]; then rm -rf -- "$PX_D"; PX_D=''; fi
  cleanup_img
}
trap cleanup_tmp EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
SITE_D="$(mktemp -d)"
chmod 755 "$SITE_D"
printf 'header X-Own-Rule "from site.d"\n' > "$SITE_D/own.caddy"; chmod 644 "$SITE_D/own.caddy"
if out="$(docker run --rm --network none -e SITE_ADDRESS=:80 -e ACME_EMAIL= -e EDGE_SITE_D=/etc/caddy/site.d -v "$CADDYFILE:/etc/caddy/Caddyfile:ro" \
    -v "$SITE_D:/etc/caddy/site.d:ro" "$CADDY_IMAGE" caddy adapt --config /etc/caddy/Caddyfile --adapter caddyfile 2>&1)" \
   && grep -qF 'X-Own-Rule' <<<"$out"; then
  pass "site.d/*.caddy is imported into the site"
else
  fail "site.d/*.caddy is not imported into the site"; printf '%s\n' "$out" | tail -3 | sed 's/^/         /'
fi
printf 'no_such_directive here\n' > "$SITE_D/own.caddy"
if docker run --rm --network none -e SITE_ADDRESS=:80 -e ACME_EMAIL= -e EDGE_SITE_D=/etc/caddy/site.d -v "$CADDYFILE:/etc/caddy/Caddyfile:ro" \
    -v "$SITE_D:/etc/caddy/site.d:ro" "$CADDY_IMAGE" caddy validate --config /etc/caddy/Caddyfile --adapter caddyfile >/dev/null 2>&1; then
  fail "a broken site.d/*.caddy passes caddy validate"
else
  pass "a broken site.d/*.caddy fails caddy validate"
fi
# A file can't define a snippet: each is pasted inside the site's route (DEPLOY.md says so; this keeps the docs honest).
printf '(common) {\n\theader X-Common "1"\n}\n' > "$SITE_D/own.caddy"
if docker run --rm --network none -e SITE_ADDRESS=:80 -e ACME_EMAIL= -e EDGE_SITE_D=/etc/caddy/site.d -v "$CADDYFILE:/etc/caddy/Caddyfile:ro" \
    -v "$SITE_D:/etc/caddy/site.d:ro" "$CADDY_IMAGE" caddy validate --config /etc/caddy/Caddyfile --adapter caddyfile >/dev/null 2>&1; then
  fail "a snippet definition in site.d passes caddy validate (DEPLOY.md says it can't be done)"
else
  pass "a snippet definition in site.d fails caddy validate, as DEPLOY.md says"
fi
rm -f "$SITE_D/own.caddy"
# DEPLOY.md's own example (the caddyfile block under "Your own edge rules"), run as written: from outside its office range
# the console's API is refused (403), from inside it reaches the server, and other paths are untouched. A client is an
# extra address on the container's loopback (NET_ADMIN); a stub server answers on :8080.
awk '/^### Your own edge rules/ { s = 1 } s && /^```caddyfile$/ { c = 1; next } c && /^```$/ { exit } c' "$ROOT/DEPLOY.md" > "$SITE_D/office.caddy"
chmod 644 "$SITE_D/office.caddy"
# And an operator's own handle with a path, which must not get ahead of MDMesh's path check (docker/Caddyfile's route).
printf 'handle /rest/x/* {\n\trespond "own" 200\n}\n' > "$SITE_D/own-path.caddy"; chmod 644 "$SITE_D/own-path.caddy"
# shellcheck disable=SC2016  # expanded by the container's shell
SITE_PROBE='set -u; F=0
ip addr add 203.0.113.7/32 dev lo && ip addr add 198.51.100.7/32 dev lo || { echo "  FAIL could not add test addresses"; exit 1; }
mkdir -p /srv /tmp/stub && echo spa > /srv/index.html
printf "{\n\tadmin off\n}\n:8080 {\n\trespond \"server\" 200\n}\n" > /tmp/stub/s
caddy run --config /tmp/stub/s --adapter caddyfile >/dev/null 2>&1 &
caddy start --config /etc/caddy/Caddyfile --adapter caddyfile >/dev/null 2>&1 || { echo "  FAIL edge did not start"; exit 1; }
for _ in $(seq 1 50); do [ "$(curl -s -m 2 http://127.0.0.1/rest/public/name)" = server ] && break; sleep 0.1; done
c() { curl -s -m 5 -o /dev/null -w "%{http_code}" --interface "$1" "http://127.0.0.1$2"; }
t() { got=$(c "$1" "$2"); if [ "$got" = "$3" ]; then echo "  ok   DEPLOY.md site.d example: $4 ($got)"; else echo "  FAIL DEPLOY.md site.d example: $4 (got $got, want $3)"; F=$((F + 1)); fi; }
t 198.51.100.7 /rest/private/users/current 403 "outside the office: the console API is refused"
t 203.0.113.7 /rest/private/users/current 200 "inside the office: the console API reaches the server"
t 198.51.100.7 /rest/public/name 200 "outside the office: other paths are untouched"
t 203.0.113.7 /rest/x/a 200 "a handle of your own with a path answers its paths"
got=$(curl -s -m 5 --interface 203.0.113.7 http://127.0.0.1/rest/x/a)
if [ "$got" = own ]; then echo "  ok   DEPLOY.md site.d example: the answer is that handle'"'"'s (own)"; else echo "  FAIL DEPLOY.md site.d example: /rest/x/a answered \"$got\", not the handle'"'"'s own"; F=$((F + 1)); fi
t 203.0.113.7 "/rest/x/a;jsessionid=1" 400 "a handle of your own with a path does not get ahead of the path check"
t 203.0.113.7 "/rest/x/a%2fb" 400 "an encoded / under your own handle is refused too"
exit "$F"'
if [ -s "$SITE_D/office.caddy" ]; then
  out="$(docker run --rm --network none --cap-add NET_ADMIN --add-host server:127.0.0.1 --add-host supervisor:127.0.0.1 \
      -e SITE_ADDRESS=:80 -e ACME_EMAIL= -e HTTP_TRUSTED_PEERS=127.0.0.1/32 -e EDGE_SITE_D=/etc/caddy/site.d -v "$CADDYFILE:/etc/caddy/Caddyfile:ro" \
      -v "$SITE_D:/etc/caddy/site.d:ro" "$CADDY_IMAGE" sh -c "$SITE_PROBE" 2>&1)" && rc=0 || rc=$?
  printf '%s\n' "$out"
  n="$(grep -c '^  FAIL' <<<"$out" || true)"; FAILED=$((FAILED + n))
  if [ "$rc" -ne 0 ] && [ "$n" -eq 0 ]; then fail "DEPLOY.md site.d example: probe container exited $rc"; fi
else
  fail "DEPLOY.md has no caddyfile example under Your own edge rules"
fi
cleanup_tmp

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
  "host|EDGE_BIND=127.0.0.1:8080|172.19.0.1/32|:8080|off|"
  "host|EDGE_BIND=[::1]:8080|172.19.0.1/32|:8080|off|"
  "192.0.2.10|EDGE_BIND=0.0.0.0:8080|192.0.2.10/32|:8080|off|"
  "fd00::1 2001:db8::/32,::1||fd00::1/128 2001:db8::/32 ::1/128|:8080|off|"
  "1:2:3:4:5:6:7:8||1:2:3:4:5:6:7:8/128|:8080|off|"
  "1::2:3:4:5:6:7:8||refused"
  "1:2:3:4:5:6:7:8:9||refused"
  "2001:db8:1||refused"
  "1::2::3||refused"
  "12345::1||refused"
  "192.0.2.10|MDMESH_ORIGIN_TLS=1 BASE_URL=https://[2001:db8::5]|192.0.2.10/32|https://[2001:db8::5]:8080|internal|2001:db8::5"
  "host||refused"
  "host|EDGE_BIND=0.0.0.0:8080|refused"
  "host|EDGE_BIND=10.0.0.5:8080|refused"
  "host|EDGE_BIND=[::]:8080|refused"
  "::ffff:10.0.0.1||refused"
  "::ffff:10.0.0.0/104||refused"
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
  read -r -a extras <<< "$extra"
  for e in ${extras[@]+"${extras[@]}"}; do envs+=(-e "$e"); done
  probe=$PEERS_PROBE
  # The "host" case that must be refused: no gateway at all (`ip` answers nothing).
  if [ "$list|$extra|$want" = "host||refused" ]; then probe=${probe/default via 172.19.0.1 dev eth0/}; fi
  rc=0; out="$(docker run --rm --network none "${envs[@]}" -v "$EDGE_PEERS:/edge-peers.sh:ro" "$CADDY_IMAGE" sh -c "$probe" 2>&1)" || rc=$?
  if [ "$want" = refused ]; then
    # `host` with an address other machines reach: refused for that reason (the gateway is there).
    why='MDMesh edge:'; case "$extra" in EDGE_BIND=*) why='host needs a loopback MDMESH_BIND' ;; esac
    if [ "$rc" != 0 ] && grep -qF "$why" <<<"$out" && grep -qF 'Not starting' <<<"$out"; then
      pass "TRUSTED_PROXIES='$list'${extra:+ $extra}: the edge refuses to start"
    else fail "TRUSTED_PROXIES='$list'${extra:+ $extra}: not refused for that (rc $rc: $out)"; fi
  elif [ "$rc" = 0 ] && [ "$out" = "${c#*|*|}" ]; then pass "TRUSTED_PROXIES='$list'${extra:+ $extra}: $out"
  else fail "TRUSTED_PROXIES='$list'${extra:+ $extra}: got '$out', want '${c#*|*|}'"; fi
done

# --- 1c. docs/proxies: the ready configurations parse with their own tool (nginx -t, httpd -t, caddy validate), as
# written, with a throwaway certificate at the paths they name. tests/proxy-e2e runs each one (Traefik's too) for real. ---
echo "docs/proxies (the ready configurations parse):"
PX_D="$(mktemp -d)"; chmod 755 "$PX_D"
mkdir -p "$PX_D/live/mdm.example.com"
openssl req -x509 -newkey rsa:2048 -nodes -days 1 -subj /CN=mdm.example.com -keyout "$PX_D/live/mdm.example.com/privkey.pem" \
  -out "$PX_D/live/mdm.example.com/fullchain.pem" >/dev/null 2>&1
chmod -R a+rX "$PX_D"
px_check() {  # px_check LABEL IMAGE COMMAND...: COMMAND in IMAGE, the configuration and the certificate mounted
  local label=$1 image=$2 out; shift 2
  if out="$(docker run --rm --network none -v "$ROOT/docs/proxies:/px:ro" -v "$PX_D:/etc/letsencrypt:ro" "$image" "$@" 2>&1)"; then
    pass "docs/proxies: $label"
  else
    fail "docs/proxies: $label"; printf '%s\n' "$out" | tail -5 | sed 's/^/         /'
  fi
}
px_check "nginx.conf: nginx -t" nginx:1.26-alpine sh -c 'cp /px/nginx.conf /etc/nginx/conf.d/mdmesh.conf && nginx -t'
# shellcheck disable=SC2016  # expanded by the container's shell
px_check "apache.conf: httpd -t" httpd:2.4-alpine sh -c 'c=/usr/local/apache2/conf/httpd.conf
  sed -i -E "s/^#(LoadModule (ssl|proxy|proxy_http|headers|rewrite|socache_shmcb)_module)/\1/" "$c"
  printf "Listen 443\nInclude /px/apache.conf\n" >> "$c" && httpd -t'
px_check "Caddyfile: caddy validate" "$CADDY_IMAGE" caddy validate --config /px/Caddyfile --adapter caddyfile
cleanup_tmp

# --- 1e. docs/proxies' HTTPS-origin lines, as code: each proxy, its recipe's HTTPS-origin lines in use (only the
# placeholders replaced), in front of a real edge with MDMESH_ORIGIN_TLS=1 at an IP address (as on another machine).
# With MDMesh's CA the console answers through it, with its headers reaching the edge; with another CA the proxy refuses
# the edge (no 200): each recipe verifies the certificate. ---
echo "docs/proxies' HTTPS-origin lines (each proxy against a real edge, with MDMesh's CA and with another):"
OT_D="$(mktemp -d)"; chmod 755 "$OT_D"; OT_NET="mdmesh-edgecheck-ot-$$"; OT_EDGE="mdmesh-edgecheck-ot-edge-$$"
OT_FRONT="mdmesh-edgecheck-ot-front-$$"
cleanup_ot() {
  [ -n "${OT_D:-}" ] && [ -d "$OT_D" ] || return 0
  docker rm -f "$OT_FRONT" "$OT_EDGE" >> "$OT_D/docker.log" 2>&1 || true
  docker network rm "$OT_NET" >> "$OT_D/docker.log" 2>&1 || true
  rm -rf -- "$OT_D"; OT_D=''
}
ot() {  # ot NAME WANT-CA(right|wrong): run the front, then a request through it from another container
  local name=$1 ca=$2 out conf
  docker rm -f "$OT_FRONT" >> "$OT_D/docker.log" 2>&1 || true
  cp "$OT_D/$ca-ca.crt" "$OT_D/front/mdmesh-origin-ca.crt"; cp "$OT_D/$ca-ca.crt" "$OT_D/front/traefik/mdmesh-origin-ca.crt"
  chmod 644 "$OT_D/front/mdmesh-origin-ca.crt" "$OT_D/front/traefik/mdmesh-origin-ca.crt"
  case "$name" in
    nginx) conf="-v $OT_D/front/nginx.conf:/etc/nginx/conf.d/default.conf:ro -v $OT_D/front/mdmesh-origin-ca.crt:/etc/nginx/mdmesh-origin-ca.crt:ro nginx:1.26-alpine" ;;
    apache) conf="-v $OT_D/front/httpd.conf:/usr/local/apache2/conf/httpd.conf:ro -v $OT_D/front/apache.conf:/usr/local/apache2/conf/mdmesh.conf:ro -v $OT_D/front/mdmesh-origin-ca.crt:/etc/apache2/mdmesh-origin-ca.crt:ro httpd:2.4-alpine" ;;
    caddy) conf="-v $OT_D/front/Caddyfile:/etc/caddy/Caddyfile:ro -v $OT_D/front/mdmesh-origin-ca.crt:/etc/caddy/mdmesh-origin-ca.crt:ro $CADDY_IMAGE" ;;
    traefik) conf="-v $OT_D/front/traefik:/etc/traefik:ro $OT_TRAEFIK" ;;
  esac
  # shellcheck disable=SC2086  # conf is a list of docker run words
  docker run -d --name "$OT_FRONT" --network "$OT_NET" -v "$OT_D/front/certs:/etc/letsencrypt/live/mdm.example.com:ro" \
    $conf >> "$OT_D/docker.log" 2>&1 || { echo "000 the proxy did not start"; return 0; }
  local ip _; ip="$(docker inspect -f "{{(index .NetworkSettings.Networks \"$OT_NET\").IPAddress}}" "$OT_FRONT")"
  # Prints "CODE BODY" (the body's first line): the console's stand-in page says "spa".
  local code
  for _ in $(seq 1 40); do
    out="$(docker run --rm --network "$OT_NET" curlimages/curl:8.23.0 -sk -m 5 -w '\n%{http_code}' \
      --resolve "mdm.example.com:443:$ip" https://mdm.example.com/ 2>> "$OT_D/docker.log")" || true
    code=${out##*$'\n'}
    # 404: Traefik answers it until its file provider has loaded the route.
    case "$code" in 000*|404|'') sleep 0.5 ;; *) break ;; esac
  done
  echo "$code $(printf '%s\n' "${out%$'\n'*}" | head -1)"
}
# The images first: a pull's progress (docker run's, on stderr) must never mix into a probe's answer.
OT_TRAEFIK=traefik:${E2E_TRAEFIK:-v3.3.7}
for i in curlimages/curl:8.23.0 nginx:1.26-alpine httpd:2.4-alpine "$OT_TRAEFIK"; do
  docker image inspect "$i" >> "$OT_D/docker.log" 2>&1 || docker pull -q "$i" >> "$OT_D/docker.log" 2>&1 || true
done
docker network create --internal "$OT_NET" >> "$OT_D/docker.log" 2>&1
OT_SUBNET="$(docker network inspect -f '{{(index .IPAM.Config 0).Subnet}}' "$OT_NET")"
mkdir -p "$OT_D/srv" "$OT_D/front/certs" "$OT_D/front/traefik"; echo spa > "$OT_D/srv/index.html"; chmod -R a+rX "$OT_D/srv"
# The edge: proxy mode, HTTPS origin for mdm.example.com from its own CA, trusting the stack network's addresses.
docker run -d --name "$OT_EDGE" --network "$OT_NET" -e MDMESH_EXPOSURE=proxy -e "EDGE_TRUSTED_PROXIES=$OT_SUBNET" \
  -e EDGE_BIND=0.0.0.0:8080 -e BASE_URL=https://mdm.example.com -e MDMESH_ORIGIN_TLS=1 -e SITE_ADDRESS=:8080 -e ACME_EMAIL= \
  -e TRUSTED_PROXIES= -v "$CADDYFILE:/etc/caddy/Caddyfile:ro" -v "$EDGE_PEERS:/edge-peers.sh:ro" -v "$OT_D/srv:/srv:ro" \
  --entrypoint sh "$CADDY_IMAGE" -c '. /edge-peers.sh && exec caddy run --config /etc/caddy/Caddyfile --adapter caddyfile' \
  >> "$OT_D/docker.log" 2>&1
OT_IP="$(docker inspect -f "{{(index .NetworkSettings.Networks \"$OT_NET\").IPAddress}}" "$OT_EDGE")"
for _ in $(seq 1 40); do docker exec "$OT_EDGE" test -s /data/caddy/pki/authorities/local/root.crt && break; sleep 0.5; done
docker exec "$OT_EDGE" cat /data/caddy/pki/authorities/local/root.crt > "$OT_D/right-ca.crt" 2>> "$OT_D/docker.log"
openssl req -x509 -newkey rsa:2048 -nodes -days 1 -subj "/CN=another CA" -keyout "$OT_D/wrong-ca.key" \
  -out "$OT_D/wrong-ca.crt" >> "$OT_D/docker.log" 2>&1
openssl req -x509 -newkey rsa:2048 -nodes -days 1 -subj /CN=mdm.example.com -addext subjectAltName=DNS:mdm.example.com \
  -keyout "$OT_D/front/certs/privkey.pem" -out "$OT_D/front/certs/fullchain.pem" >> "$OT_D/docker.log" 2>&1
chmod -R a+rX "$OT_D/front"
# Each recipe with its HTTPS-origin lines in use instead of the plain ones, 192.0.2.20 the edge's address.
ot_render() { sed -e "s#192\.0\.2\.20#$OT_IP#g" "$ROOT/docs/proxies/$1" > "$OT_D/front/$2"; chmod 644 "$OT_D/front/$2"; }
ot_render nginx.conf nginx.conf
sed -i -E 's#^( *)proxy_pass http://127\.0\.0\.1:8080;#\1\# plain origin replaced#; s#^( *)\# (proxy_(pass https|ssl_)[^;]*;)#\1\2#' "$OT_D/front/nginx.conf"
ot_render apache.conf apache.conf
sed -i -E 's#^( *)(ProxyPass(Reverse)? +/ http://127)#\1\# \2#; s#^( *)\# (SSLProxy[A-Za-z]+ |ProxyPass(Reverse)? +/ https://)#\1\2#' "$OT_D/front/apache.conf"
docker run --rm httpd:2.4-alpine cat /usr/local/apache2/conf/httpd.conf > "$OT_D/front/httpd.conf" 2>> "$OT_D/docker.log"
sed -i -E 's/^#(LoadModule (ssl|proxy|proxy_http|headers|rewrite|socache_shmcb)_module )/\1/' "$OT_D/front/httpd.conf"
printf 'Listen 443\nInclude conf/mdmesh.conf\n' >> "$OT_D/front/httpd.conf"
ot_render Caddyfile Caddyfile
sed -i -E 's#^(\t)reverse_proxy 127\.0\.0\.1:8080$#\1\# plain origin replaced#; s#^(\t)\# (reverse_proxy https|\t|\})#\1\2#' "$OT_D/front/Caddyfile"
sed -i 's#^mdm.example.com {#mdm.example.com {\n\ttls /etc/letsencrypt/live/mdm.example.com/fullchain.pem /etc/letsencrypt/live/mdm.example.com/privkey.pem#' "$OT_D/front/Caddyfile"
ot_render traefik.yml traefik/traefik.yml
ot_render traefik-dynamic.yml traefik/traefik-dynamic.yml
sed -i -E '/^        servers:$/,/^          - url: http:\/\/127/d; s#^( +)\# (servers:|  - url: https|serversTransport:|serversTransports:|  mdmesh-origin:|    serverName:|    rootCAs:|      - /etc/traefik)#\1\2#' "$OT_D/front/traefik/traefik-dynamic.yml"
chmod -R a+rX "$OT_D/front"
for name in nginx apache caddy traefik; do
  r="$(ot "$name" right)"
  if [ "$r" = "200 spa" ]; then pass "docs/proxies $name, HTTPS origin with MDMesh's CA: the console answers ($r)"
  else fail "docs/proxies $name, HTTPS origin with MDMesh's CA: the console answers (got $r, want 200 spa)"
    docker logs --tail 5 "$OT_FRONT" 2>&1 | sed 's/^/         /'; fi
  # Refused means the proxy's own error for the edge (5xx); a proxy that never started proves nothing.
  r="$(ot "$name" wrong)"
  case "$r" in 5[0-9][0-9]*) pass "docs/proxies $name, HTTPS origin with another CA: refused ($r)" ;;
    *) fail "docs/proxies $name, HTTPS origin with another CA: not refused by the proxy (got $r)" ;; esac
done
cleanup_ot

# --- 1d. The web image's own start: docker/web.Dockerfile's final stage (a stand-in page for the console, which needs
# the web build), run as the image runs: the entrypoint as root, edge-peers.sh, then su-exec caddy through the wrapper.
# Behind your own proxy with the HTTPS origin and a site.d holding a rule and an open file. Caddy runs as caddy, writes
# its own state (the internal CA) and can't change its configuration; a reload through docker exec keeps the peers, the
# origin and the checked rules. ---
echo "The web image's start (docker/web.Dockerfile, without the console build):"
IMG_D="$(mktemp -d)"; chmod 755 "$IMG_D"
awk 'tolower($1) == "from" && $2 ~ /^caddy[:@]/ { s = 1 } s' "$ROOT/docker/web.Dockerfile" \
  | sed 's#^COPY --from=web /web/dist /srv$#RUN mkdir -p /srv \&\& echo spa > /srv/index.html#' > "$IMG_D/Dockerfile"
mkdir -p "$IMG_D/site.d"; chmod 755 "$IMG_D/site.d"
printf 'header X-Good "1"\n' > "$IMG_D/site.d/good.caddy"; chmod 644 "$IMG_D/site.d/good.caddy"
printf 'header X-Open "1"\n' > "$IMG_D/site.d/open.caddy"; chmod 666 "$IMG_D/site.d/open.caddy"
# A site.d of mode 700 with a rule of mode 600 (the edge loads these: only write bits count): the raw check
# (MDMESH_RAW_SITE_D=1) must read it as the start does, not as caddy, which can't.
mkdir -p "$IMG_D/site700"; chmod 700 "$IMG_D/site700"
printf 'header X-Seven "1"\n' > "$IMG_D/site700/rule.caddy"; chmod 600 "$IMG_D/site700/rule.caddy"
if ! grep -q '^RUN mkdir -p /srv' "$IMG_D/Dockerfile"; then
  fail "web image start: could not derive the final stage from docker/web.Dockerfile"
elif ! docker build -q -f "$IMG_D/Dockerfile" -t "$IMG_TAG" "$ROOT" > "$IMG_D/build.log" 2>&1; then
  fail "web image start: the final stage did not build"; tail -5 "$IMG_D/build.log" | sed 's/^/         /'
elif ! docker run -d --name "$IMG_CT" --network none -e MDMESH_EXPOSURE=proxy -e EDGE_TRUSTED_PROXIES=192.0.2.10 \
    -e EDGE_BIND=127.0.0.1:8080 -e BASE_URL=https://mdm.example.com -e MDMESH_ORIGIN_TLS=1 -e SITE_ADDRESS=:8080 \
    -e ACME_EMAIL= -e TRUSTED_PROXIES= -v "$IMG_D/site.d:/etc/caddy/site.d:ro" -v "$IMG_D/site700:/etc/caddy/site700:ro" \
    "$IMG_TAG" >> "$IMG_D/docker.log" 2>&1; then
  fail "web image start: the container did not start"; tail -5 "$IMG_D/docker.log" | sed 's/^/         /'
else
  ex() { docker exec "$IMG_CT" "$@"; }
  cfg() { ex wget -qO- http://127.0.0.1:2019/config/ 2>&1; }
  cfg_has() { cfg | grep -qi -- "$1"; }
  # The live config lacks $1: fetched once, and only a real config counts (an unreachable admin API fails).
  cfg_lacks() { local c; c=$(cfg) || return 1; grep -q '"apps"' <<< "$c" && ! grep -qi -- "$1" <<< "$c"; }
  # The documented raw check on site700, its output kept for a failure.
  # raw_check [CMD [EXEC-OPTION...]]: the documented raw check (validate, or adapt) on site700, its output kept.
  raw_check() { local c=${1:-validate}; shift || true
    docker exec "$@" -e MDMESH_RAW_SITE_D=1 -e EDGE_SITE_SRC=/etc/caddy/site700 "$IMG_CT" \
      caddy "$c" --config /etc/caddy/Caddyfile --adapter caddyfile > "$IMG_D/raw.log" 2>&1; }
  raw_shows() { raw_check adapt && grep -qi -- "$1" "$IMG_D/raw.log" && return 0; tail -5 "$IMG_D/raw.log" | sed 's/^/         /'; return 1; }
  # raw_fails_on TEXT [EXEC-OPTION...]: the raw check fails, saying TEXT.
  raw_fails_on() { local t=$1; shift; ! raw_check validate "$@" && grep -qF -- "$t" "$IMG_D/raw.log" && return 0
    tail -5 "$IMG_D/raw.log" | sed 's/^/         /'; return 1; }
  reload_quiet() { ex caddy reload --config /etc/caddy/Caddyfile --adapter caddyfile > "$IMG_D/reload.log" 2>&1 \
    || { sed 's/^/         /' "$IMG_D/reload.log"; return 1; }; }
  # caddy can't write its configuration: the write fails for want of permission, not for any other reason.
  denied() { local out; out=$(docker exec -u caddy "$IMG_CT" touch /etc/caddy/x 2>&1) && return 1; grep -q 'Permission denied' <<< "$out"; }
  owner() { ex stat -c %U "$@" 2>&1 | sort -u; }
  for _ in $(seq 1 50); do cfg_has '"internal"' && break; sleep 0.2; done
  chk() { local l=$1; shift; if "$@"; then pass "web image start: $l"; else fail "web image start: $l"; fi; }
  chk "caddy runs as caddy" sh -c "docker exec '$IMG_CT' ps -o user,args | grep -q '^caddy .*caddy run'"
  chk "  the proxy as trusted peer" cfg_has 192.0.2.10/32
  chk "  the HTTPS origin" cfg_has '"internal"'
  chk "  the checked rule" cfg_has x-good
  chk "  not the open one" cfg_lacks x-open
  chk "caddy wrote its own state (the internal CA, its own)" test "$(owner /data/caddy/pki/authorities/local/root.crt)" = caddy
  chk "the Caddyfile and scripts are root's" test "$(owner /etc/caddy/Caddyfile /etc/caddy /usr/local/bin/caddy)" = root
  chk "caddy can't change its configuration (permission denied)" denied
  chk "a validate through docker exec (as root) runs as caddy: what it provisions is caddy's" sh -c \
    "docker exec -e XDG_DATA_HOME=/tmp/vd '$IMG_CT' caddy validate --config /etc/caddy/Caddyfile --adapter caddyfile > '$IMG_D/validate.log' 2>&1; rm -f '$IMG_D/validate.log'; [ \"\$(docker exec '$IMG_CT' stat -c %U /tmp/vd/caddy/pki/authorities/local/root.key 2>&1)\" = caddy ]"
  chk "the raw check passes a valid 600 rule in a 700 site.d" raw_check
  chk "  and that rule is in what it checks" raw_shows x-seven
  # A file the restart would leave out (others may change it): the raw check fails, saying so.
  printf 'header X-Open7 "1"\n' > "$IMG_D/site700/open.caddy"; chmod 664 "$IMG_D/site700/open.caddy"
  chk "the raw check fails when a restart would leave a 664 file out, naming it" raw_fails_on "site.d/open.caddy is not loaded"
  rm -f "$IMG_D/site700/open.caddy"
  printf 'no_such_directive here\n' > "$IMG_D/site700/rule.caddy"
  chk "the raw check fails a broken 600 rule in a 700 site.d, saying why" raw_fails_on no_such_directive
  chk "a raw check as caddy (which can't list a 700 site.d) fails, saying so, never Valid" raw_fails_on "could not read site.d" -u caddy
  # Root that the kernel refuses (userns-remap, or no DAC capabilities): a 700 site.d of another uid can't be listed,
  # whatever its mode bits say to `test`. A fresh container of the image, its own site.d, a broken rule in it.
  # shellcheck disable=SC2016  # expanded by the container's shell
  nodac() { docker run --rm --network none --cap-drop DAC_OVERRIDE --cap-drop DAC_READ_SEARCH -e SITE_ADDRESS=:80 \
      -e ACME_EMAIL= -e MDMESH_RAW_SITE_D=1 -e EDGE_SITE_SRC=/sd --entrypoint sh "$IMG_TAG" -c 'mkdir /sd
      printf "no_such_directive here\n" > /sd/rule.caddy; chmod 600 /sd/rule.caddy; chown 1000 /sd/rule.caddy /sd
      chmod 700 /sd; caddy validate --config /etc/caddy/Caddyfile --adapter caddyfile' > "$IMG_D/raw.log" 2>&1; }
  # It fails (caddy may have called the empty copy valid; the check's own verdict comes last).
  nodac_fails() { ! nodac && grep -qF "could not read site.d" "$IMG_D/raw.log" \
    && tail -n1 "$IMG_D/raw.log" | grep -qF 'a restart would leave out what is listed above' && return 0
    tail -5 "$IMG_D/raw.log" | sed 's/^/         /'; return 1; }
  chk "a raw check as root without DAC capabilities on a 700 site.d fails, saying so" nodac_fails
  # No *.caddy file to check (site.d not mounted: the image's own empty directory, as with a compose file from before
  # 0.6.0): the raw check fails with a hint, rather than say Valid for a rule it never saw.
  unmounted() { docker run --rm --network none -e SITE_ADDRESS=:80 -e ACME_EMAIL= -e MDMESH_RAW_SITE_D=1 --entrypoint caddy \
      "$IMG_TAG" validate --config /etc/caddy/Caddyfile --adapter caddyfile > "$IMG_D/raw.log" 2>&1; }
  unmounted_fails() { ! unmounted && grep -qF "is it mounted?" "$IMG_D/raw.log" && return 0; tail -5 "$IMG_D/raw.log" | sed 's/^/         /'; return 1; }
  chk "a raw check with no *.caddy file (site.d not mounted) fails, with a hint" unmounted_fails
  chk "a reload through docker exec succeeds" reload_quiet
  chk "  and keeps the proxy as trusted peer" cfg_has 192.0.2.10/32
  chk "  and the HTTPS origin" cfg_has '"internal"'
  chk "  and loads no rule the start left out" cfg_lacks x-open
fi
cleanup_img

# --- 2. Compose files, each combination setup.sh / quickstart.sh / DEPLOY.md / DEV.md use. ---
# A fixed env file with the required (:?) variables, so a developer's own .env or shell cannot change the result.
ENVF="$(mktemp)"
ENVF_CF="$(mktemp)"   # the same, plus the COMPOSE_PROFILES line setup.sh / quickstart.sh write for tunnel mode
ENVF_PX="$(mktemp)"   # the same, plus the lines they write behind your own proxy (install/lib/modes.sh, mdm_mode_env)
ENVF_NOTP="$(mktemp)"   # that, without TRUSTED_PROXIES
trap 'cleanup_tmp; rm -f "$ENVF" "$ENVF_CF" "$ENVF_PX" "$ENVF_NOTP"' EXIT
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

# install/lib/ports.sh's own reading of real `config` output: the installers' lists, and ports of the operator's own.
echo "Published host ports (docker compose config, as a setup.sh re-run reads it):"
# shellcheck source=install/lib/ports.sh
. install/lib/ports.sh
OVR="$(mktemp -d)"
printf '%s\n' 'services:' '  caddy: {ports: ["0.0.0.0:80:80", "[::]:443:443", "443:443/udp"]}' > "$OVR/ip.yml"
printf '%s\n' 'services:' '  traefik: {image: traefik, ports: ["80:80", "443:443"]}' > "$OVR/traefik.yml"
# the file after docker-compose.yml | host ports
for c in "|" "docker-compose.domain.yml|80 443" "$OVR/ip.yml|80 443" "$OVR/traefik.yml|80 443"; do
  f=${c%%|*}; want=${c#*|}
  COMPOSE_ARGS=(--env-file "$ENVF" -f docker-compose.yml); [ -z "$f" ] || COMPOSE_ARGS+=(-f "$f")
  if got="$(mdm_list_ports 2> "$OVR/err" | sort -nu | tr '\n' ' ')" && [ "${got% }" = "$want" ]; then pass "docker-compose.yml ${f##*/}: ${want:-none}"
  else fail "docker-compose.yml ${f##*/}: host ports '${got% }', want '$want'"; tail -5 "$OVR/err" | sed 's/^/         /'; fi
done
COMPOSE_ARGS=(--env-file "$ENVF_NOTP" -f docker-compose.yml -f docker-compose.proxy.yml)
if mdm_list_ports > "$OVR/out" 2> "$OVR/err"; then fail "host ports: no failure when config fails"; else pass "a config that fails is not read as no ports"; fi
rm -r -- "$OVR"

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
if [ "$KIND" = http ]; then
  h="$($CURL --interface 203.0.113.1 -D - -o /dev/null -H 'X-Forwarded-Proto: https' "$BASE/" 2>/dev/null | tr -d '\r')"
  if printf '%s\n' "$h" | grep -qi '^strict-transport-security:'; then
    echo "  FAIL $MODE: HSTS for an X-Forwarded-Proto from a peer that is not the proxy"; F=$((F + 1))
  else echo "  ok   $MODE: no HSTS for an X-Forwarded-Proto from anyone but the proxy"; fi
fi
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
trap 'cleanup_tmp; cleanup_stub; rm -f "$ENVF" "$ENVF_CF" "$ENVF_PX" "$ENVF_NOTP"' EXIT
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
