#!/usr/bin/env bash
# End-to-end test of "Behind your own proxy" (Docker), with the ready configurations in docs/proxies in front, in
# throwaway containers.
#
# Usage: tests/proxy-e2e/run.sh [nginx] [tls] [proxies] [update]      (default: all four, in this order)
#   nginx    A quick start in proxy mode: the release compose file, docker-compose.proxy.yml and the .env lines
#            install/lib/modes.sh writes (MDMESH_BIND=127.0.0.1:8080, TRUSTED_PROXIES=host), seeded as the quick start
#            seeds. docs/proxies/nginx.conf in front, on the same machine. From a client on another machine: the
#            console, sign-in, the client's own address in the audit log (a forged X-Forwarded-For not believed), the
#            agent's wake socket held open past nginx's default 60 s (and an unauthorised one closed), a 64 MiB upload
#            not refused, /recovery and /healthz; the edge itself not reachable from outside. Then MDMESH_BIND=0.0.0.0:8080:
#            with TRUSTED_PROXIES=host the edge refuses to start; with a proxy address the client reaches the edge
#            directly, and its own X-Forwarded-For (it is not in TRUSTED_PROXIES) is not believed. A rule in site.d applies.
#   tls      MDMESH_ORIGIN_TLS=1 and nginx.conf's HTTPS-origin lines (MDMesh's CA, from `docker compose cp`): the same
#            checks through HTTPS from nginx to the edge.
#   proxies  docs/proxies' Apache, Caddy and Traefik configurations in front instead of nginx: console, sign-in, the
#            client's address, the wake socket.
#   update   The supervisor refuses an update to a release before 0.6.0; its own apply.sh updates to the next release, then Roll back (POST /update/rollback with the
#            recovery token, through nginx, as /recovery sends it): the mode, the published port, .env and the site.d
#            rule stay, and the nginx checks pass on both versions.
#
# Everything runs in a privileged docker:dind container (its own daemon: nothing reaches this host's stacks) next to a TLS
# registry that answers as ghcr.io, on an internal network; the client is a container on that network. Images (on this
# host): ghcr.io/local/mdmesh-{server,web,supervisor}:$E2E_NEW_TAG (this branch, default px-e2e: docker build -f
# docker/<name>.Dockerfile -t ... .), postgres:14, registry:3, docker:28.5.2-dind, curlimages/curl, node:24-alpine, nginx:1.26-alpine,
# httpd:2.4-alpine, the caddy image docker/web.Dockerfile builds from, and traefik:$E2E_TRAEFIK (pulled when missing).
# Containers and networks are named $E2E_PREFIX-* (default px-e2e) and removed by name at the end. E2E_KEEP=1 keeps
# the work directory.
set -uo pipefail
umask 077
ROOT="$(CDPATH='' cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
HERE="$ROOT/tests/proxy-e2e"
NEW_TAG=${E2E_NEW_TAG:-px-e2e}
TRAEFIK=traefik:${E2E_TRAEFIK:-v3.3.7}
CADDY_IMAGE="$(awk 'tolower($1) == "from" && $2 ~ /^caddy[:@]/ { print $2; exit }' "$ROOT/docker/web.Dockerfile")"
P=${E2E_PREFIX:-px-e2e}
NET=$P-net REG=$P-registry DIND=$P-dind CLIENT=$P-client WSCLIENT=$P-wsclient FRONT=$P-front
OWNER=px-e2e
D=/root/px          # the install directory, in dind
HOSTNAME_=mdm.test  # the public name; the client resolves it to dind
DATA_BASE="${E2E_DATA_DIR:-$HERE/.e2e-data}"
mkdir -p "$DATA_BASE" && DATA="$(mktemp -d "$DATA_BASE/run.XXXXXX")" || exit 1
WORK="$(mktemp -d "${TMPDIR:-/tmp}/px-e2e.XXXXXX")"
chmod 755 "$WORK"   # mounted into containers that run as other users
LOG="$WORK/docker.log"
PASSED=0 FAILED=0
FAILURES=()
PHASES=("$@")
[ "${#PHASES[@]}" -gt 0 ] || PHASES=(nginx tls proxies update)

pass() { echo "  ok   $*"; PASSED=$((PASSED + 1)); }
fail() { echo "  FAIL $*"; FAILED=$((FAILED + 1)); FAILURES+=("$*"); }
say()  { echo; echo "== $*"; }
check() { local label=$1; shift; if "$@"; then pass "$label"; else fail "$label"; fi; }

cleanup() {
  local rc=$?
  echo; echo "== cleanup"
  # A failure: the stack's own logs (the edge's says why a site.d rule was left out, say) go with the work directory.
  if [ "$FAILED" != 0 ] || [ "$rc" != 0 ]; then
    docker exec -i -w "$D" "$DIND" docker compose logs --no-color > "$WORK/compose.log" 2>&1 || true
  fi
  {
    docker rm -f -v "$CLIENT" "$WSCLIENT" "$DIND" "$REG"
    docker network rm "$NET"
    find "$DATA" -mindepth 1 -delete; rmdir "$DATA"
  } >>"$LOG" 2>&1
  local left
  left="$(docker ps -a --format '{{.Names}}' | grep "^$P-" | tr '\n' ' ')"
  [ -z "$left" ] && echo "  no $P containers left" || echo "  LEFT: containers $left"
  if [ "${E2E_KEEP:-0}" = 1 ]; then echo "  work directory kept: $WORK"
  else find "$WORK" -mindepth 1 -delete; rmdir "$WORK"; fi
  echo; echo "== proxy-e2e: $PASSED passed, $FAILED failed"
  for f in "${FAILURES[@]}"; do echo "  FAIL $f"; done
  [ "$FAILED" = 0 ] && [ "$rc" = 0 ]
  exit $?
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

in_dind() { docker exec -i "$DIND" "$@"; }
dc()      { docker exec -i -w "$D" "$DIND" docker compose "$@"; }
q()       { dc exec -T postgres psql -X -U mdmesh -d mdmesh -tAc "$1" 2>>"$LOG" | tr -d '\r'; }
set_env() { in_dind sed -i "s#^$1=.*#$1=$2#" "$D/.env"; }   # set_env KEY VALUE: a line the install already has
image_of() { in_dind docker inspect -f '{{.Config.Image}}' "$(dc ps -q "$1" 2>>"$LOG")" 2>>"$LOG"; }

# The client, on another machine: curl with the test CA, mdm.test resolving to dind.
# web METHOD PATH [JSON [curl args...]]: through the proxy in front (https://mdm.test); prints "<status> <body>".
# edge METHOD PATH [JSON [curl args...]]: straight to the edge's published port on dind (http://<dind>:8080).
# Each keeps the session cookie of its own jar.
web()  { _req "https://$HOSTNAME_" web "$@"; }
edge() { _req "http://$DIND_IP:8080" edge "$@"; }
# anon METHOD PATH ...: as web, with no session (the audit log names a signed-in session's account, not the login tried).
anon() { _req "https://$HOSTNAME_" "anon$RANDOM" "$@"; }
_req() {
  local base=$1 jar=$2 m=$3 p=$4 body=${5:-}
  shift 4; [ $# -eq 0 ] || shift
  # shellcheck disable=SC2016  # expanded by the client's shell
  docker exec -i "$CLIENT" sh -c 'base=$1 jar=$2 m=$3 p=$4 body=$5; shift 5
    c=$(curl -sS -m 30 --cacert /ca.pem -b "/tmp/$jar" -c "/tmp/$jar" -X "$m" -H "Content-Type: application/json" \
      ${body:+--data "$body"} "$@" -D /tmp/hdr -o /tmp/body -w "%{http_code}" "$base$p" 2>/tmp/err) || c="000 $(head -c 200 /tmp/err)"
    echo "$c $(head -c 400 /tmp/body)"' sh "$base" "$jar" "$m" "$p" "$body" "$@" 2>>"$LOG" | tr -d '\r'
}
last_headers() { docker exec -i "$CLIENT" cat /tmp/hdr 2>>"$LOG" | tr -d '\r'; }
ADMIN_PW="px-e2e-$(openssl rand -hex 6)"
ADMIN_MD5="$(printf '%s' "$ADMIN_PW" | md5sum | awk '{ print toupper($1) }')"

# wait_web: the console answers through the proxy in front.
wait_web() {
  local _
  for _ in $(seq 1 60); do case "$(web GET /healthz)" in '200 ok'*) return 0 ;; esac; sleep 2; done
  return 1
}
wait_init() {
  local _
  for _ in $(seq 1 90); do sleep 4; dc exec -T server test -s /opt/mdmesh/initialized.txt >>"$LOG" 2>&1 && return 0; done
  dc logs --tail 60 server >>"$LOG" 2>&1; return 1
}

# audit_ip LOGIN: the address the audit log recorded for the last sign-in attempt as LOGIN.
audit_ip() {
  local _ ip
  for _ in $(seq 1 10); do
    ip="$(q "SELECT ipaddress FROM plugin_audit_log WHERE login = '$1' ORDER BY id DESC LIMIT 1")"
    [ -n "$ip" ] && { echo "$ip"; return; }
    sleep 1
  done
}

# ws SECRET DEVICE SECONDS [via]: the agent's wake socket, opened as the agent opens it (tests/proxy-e2e/ws-probe.js, in
# the node client) through the proxy, or with via=edge straight to the edge, and kept SECONDS. 4 seconds in, a command is
# queued for the device (signed in as the admin), so the server's wake signal must come down the socket. Prints the
# probe's line: "status=101 texts=[...] close=... open=1" for a socket the server took.
ws() {
  local base="https://$HOSTNAME_" n
  [ "${4:-}" != edge ] || base="http://$DIND_IP:8080"
  n="ws$RANDOM"
  docker exec -d "$WSCLIENT" sh -c 'node /probe/ws-probe.js "$1" "$2" "$3" > "/tmp/$4" 2>&1' sh \
    "$base/agent/ws/$2" "$1" "$3" "$n" >>"$LOG" 2>&1
  sleep 4
  web POST "/rest/private/agent/v1/devices/$2/commands" '{"type":"apps.scan"}' >>"$LOG"
  sleep $(($3 - 2))
  docker exec "$WSCLIENT" sh -c 'for i in 1 2 3 4 5 6 7 8 9 10; do grep -q "^status=" "/tmp/$1" && break; sleep 1; done; tail -1 "/tmp/$1"' sh "$n" 2>>"$LOG"
}
WS_OK='^status=101 texts=\[.*wake.*commands.*\] close=- open=1$'

# The checks every proxy in front must pass. chain_checks LABEL [WS_SECONDS]
DEVICE='' SECRET=''
chain_checks() {
  local l=$1 hold=${2:-10} r nonce ip h
  r="$(web GET /)"
  check "$l: the console through the proxy (${r:0:40})" grep -q '^200 ' <<< "$r"
  h="$(last_headers)"
  check "$l: the edge's security headers and HSTS (the proxy says https)" \
    bash -c 'grep -qix "x-frame-options: DENY" <<< "$1" && grep -qix "strict-transport-security: max-age=15552000" <<< "$1"' _ "$h"
  r="$(web POST /rest/public/auth/login "{\"login\":\"admin\",\"password\":\"$ADMIN_MD5\"}")"
  check "$l: admin signs in (${r:0:60})" grep -q '^200 .*"status":"OK"' <<< "$r"
  nonce="px$(openssl rand -hex 4)"
  anon POST /rest/public/auth/login "{\"login\":\"$nonce\",\"password\":\"x\"}" -H 'X-Forwarded-For: 6.6.6.6' \
    -H 'X-Real-IP: 7.7.7.7' -H 'CF-Connecting-IP: 8.8.8.8' >>"$LOG"
  ip="$(audit_ip "$nonce")"
  check "$l: the server sees the client's own address ($ip, want $CLIENT_IP), not a forged one" test "$ip" = "$CLIENT_IP"
  r="$(ws "$SECRET" "$DEVICE" "$hold")"
  check "$l: the agent's wake socket opens (101), gets the wake signal and stays open ${hold}s ($r)" grep -qE "$WS_OK" <<< "$r"
  r="$(web GET /recovery)"
  check "$l: /recovery (${r:0:20})" grep -q '^200 ' <<< "$r"
}

setup() {
  say "setup: registry (as ghcr.io) + docker:dind + a client, on an internal network"
  local img n
  for n in server web supervisor; do
    docker image inspect "ghcr.io/local/mdmesh-$n:$NEW_TAG" >>"$LOG" 2>&1 \
      || { echo "missing image ghcr.io/local/mdmesh-$n:$NEW_TAG (see the header)"; return 1; }
  done
  docker image inspect "$TRAEFIK" >>"$LOG" 2>&1 || docker pull -q "$TRAEFIK" >>"$LOG" 2>&1 \
    || { echo "could not pull $TRAEFIK"; return 1; }
  openssl req -x509 -newkey rsa:2048 -nodes -days 1 -subj "/CN=$P CA" -keyout "$WORK/ca.key" -out "$WORK/ca.pem" \
    -addext "basicConstraints=critical,CA:TRUE" -addext "keyUsage=keyCertSign" >>"$LOG" 2>&1
  for n in ghcr.io "$HOSTNAME_"; do
    openssl req -newkey rsa:2048 -nodes -subj "/CN=$n" -keyout "$WORK/$n.key" -out "$WORK/$n.csr" >>"$LOG" 2>&1
    printf 'subjectAltName=DNS:%s\nbasicConstraints=CA:FALSE\n' "$n" > "$WORK/$n.ext"
    openssl x509 -req -in "$WORK/$n.csr" -CA "$WORK/ca.pem" -CAkey "$WORK/ca.key" -CAcreateserial -days 1 \
      -extfile "$WORK/$n.ext" -out "$WORK/$n.pem" >>"$LOG" 2>&1
    chmod 644 "$WORK/$n.pem" "$WORK/$n.key"
  done
  chmod 644 "$WORK/ca.pem"
  docker network create --internal "$NET" >>"$LOG" || return 1
  mkdir -p "$DATA/registry" "$DATA/docker"
  docker run -d --name "$REG" --network "$NET" -v "$DATA/registry:/var/lib/registry" \
    -v "$WORK/ghcr.io.pem:/certs/ghcr.pem:ro" -v "$WORK/ghcr.io.key:/certs/ghcr.key:ro" \
    -e REGISTRY_HTTP_ADDR=0.0.0.0:443 -e REGISTRY_HTTP_TLS_CERTIFICATE=/certs/ghcr.pem -e REGISTRY_HTTP_TLS_KEY=/certs/ghcr.key \
    registry:3 >>"$LOG" || return 1
  local reg_ip _
  reg_ip="$(docker inspect -f "{{(index .NetworkSettings.Networks \"$NET\").IPAddress}}" "$REG")"
  docker run -d --privileged --name "$DIND" --network "$NET" --add-host "ghcr.io:$reg_ip" -e DOCKER_TLS_CERTDIR= \
    -v "$DATA/docker:/var/lib/docker" -v "$WORK/ca.pem:/etc/docker/certs.d/ghcr.io/ca.crt:ro" \
    docker:28.5.2-dind dockerd --host=unix:///var/run/docker.sock >>"$LOG" || return 1
  for _ in $(seq 1 30); do in_dind docker info >>"$LOG" 2>&1 && break; sleep 2; done
  DIND_IP="$(docker inspect -f "{{(index .NetworkSettings.Networks \"$NET\").IPAddress}}" "$DIND")"
  docker run -d --name "$CLIENT" --network "$NET" --add-host "$HOSTNAME_:$DIND_IP" -u 0 --entrypoint sh \
    -v "$WORK/ca.pem:/ca.pem:ro" curlimages/curl:8.23.0 -c 'sleep 86400' >>"$LOG" || return 1
  CLIENT_IP="$(docker inspect -f "{{(index .NetworkSettings.Networks \"$NET\").IPAddress}}" "$CLIENT")"
  docker run -d --name "$WSCLIENT" --network "$NET" --add-host "$HOSTNAME_:$DIND_IP" -e NODE_EXTRA_CA_CERTS=/ca.pem \
    -v "$WORK/ca.pem:/ca.pem:ro" -v "$HERE/ws-probe.js:/probe/ws-probe.js:ro" --entrypoint sh node:24-alpine \
    -c 'sleep 86400' >>"$LOG" || return 1
  echo "-- dind $DIND_IP, client $CLIENT_IP; loading images"
  local imgs=()
  for n in server web supervisor; do imgs+=("ghcr.io/local/mdmesh-$n:$NEW_TAG"); done
  imgs+=(postgres:14 nginx:1.26-alpine httpd:2.4-alpine "$CADDY_IMAGE" "$TRAEFIK")
  # Each one here first (a clean host has pulled none of the public ones yet).
  for img in "${imgs[@]}"; do
    docker image inspect "$img" >>"$LOG" 2>&1 || docker pull -q "$img" >>"$LOG" 2>&1 || { echo "could not pull $img"; return 1; }
  done
  docker save "${imgs[@]}" | docker exec -i "$DIND" docker load >>"$LOG" 2>&1 || return 1
  echo "-- releases 0.6.0 and 0.6.1 (this branch's images, each its own digest) in the registry"
  push 0.6.0 || return 1
  push 0.6.1 || return 1
}

# push VERSION: this branch's images as ghcr.io/$OWNER/mdmesh-<name>:VERSION (a label makes each version's digest its
# own); records REF_<version>_<name>.
push() {
  local v=$1 n img ref var
  for n in server web supervisor; do
    img="ghcr.io/$OWNER/mdmesh-$n:$v"
    printf 'FROM ghcr.io/local/mdmesh-%s:%s\nLABEL org.opencontainers.image.version=%s\n' "$n" "$NEW_TAG" "$v" \
      | docker exec -i -e DOCKER_BUILDKIT=0 "$DIND" docker build -q -t "$img" - >>"$LOG" 2>&1 || return 1
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

# The .env lines of a proxy-mode quick start, as install/lib/modes.sh makes them.
mode_env() {
  (
    warn() { :; }
    # shellcheck source=install/lib/url.sh
    . "$ROOT/install/lib/url.sh"
    # shellcheck source=install/lib/answers.sh
    . "$ROOT/install/lib/answers.sh"
    # shellcheck source=install/lib/modes.sh
    . "$ROOT/install/lib/modes.sh"
    MODE=3 HOST=$HOSTNAME_ PROXY_BIND=127.0.0.1:8080 PROXY_TRUSTED=host ORIGIN_TLS=0
    mdm_mode_settings 3
    printf '%s\n' "SITE_ADDRESS=$SITE_ADDRESS" "COMPOSE_FILE=$COMPOSE_FILE" "COMPOSE_PROFILES=$COMPOSE_PROFILES"
    mdm_mode_env
  )
}

# nginx_front [tls]: docs/proxies/nginx.conf in front, on dind's own network (as on the same machine), with only its
# placeholders replaced; with tls, its HTTPS-origin lines in use instead of the plain proxy_pass.
render() {  # render SRC DEST: the placeholders: the name, the certificate paths, the other machine's address
  sed -e "s#mdm\.example\.com#$HOSTNAME_#g" -e "s#/etc/letsencrypt/live/$HOSTNAME_/fullchain.pem#/certs/fullchain.pem#g" \
    -e "s#/etc/letsencrypt/live/$HOSTNAME_/privkey.pem#/certs/privkey.pem#g" -e 's#192\.0\.2\.20:8080#127.0.0.1:8080#g' \
    "$1" > "$2"
  chmod 644 "$2"
}
front_files() {
  in_dind mkdir -p /root/front
  docker cp "$WORK/$HOSTNAME_.pem" "$DIND:/root/front/fullchain.pem" >>"$LOG"
  docker cp "$WORK/$HOSTNAME_.key" "$DIND:/root/front/privkey.pem" >>"$LOG"
}
stop_front() { in_dind docker rm -f "$FRONT" >>"$LOG" 2>&1; true; }
nginx_front() {
  stop_front
  render "$ROOT/docs/proxies/nginx.conf" "$WORK/nginx.conf"
  if [ "${1:-}" = tls ]; then
    sed -i -E 's#^( *)proxy_pass http://127\.0\.0\.1:8080;#\1\# (plain HTTP origin, replaced)#; s#^( *)\# (proxy_(pass https|ssl_)[^;]*;)#\1\2#' "$WORK/nginx.conf"
    grep -q '^ *proxy_ssl_verify on;' "$WORK/nginx.conf" || { fail "nginx.conf has no HTTPS-origin lines to use"; return 1; }
  fi
  docker cp "$WORK/nginx.conf" "$DIND:/root/front/default.conf" >>"$LOG"
  in_dind docker run -d --name "$FRONT" --network host -v /root/front/default.conf:/etc/nginx/conf.d/default.conf:ro \
    -v /root/front:/certs:ro -v /root/front/origin-ca.crt:/etc/nginx/mdmesh-origin-ca.crt:ro nginx:1.26-alpine >>"$LOG" 2>&1
}

install_proxy_mode() {
  say "install: a quick start in proxy mode (release compose file, docker-compose.proxy.yml, install/lib/modes.sh's .env lines)"
  in_dind mkdir -p "$D/site.d" /root/front
  in_dind touch /root/front/origin-ca.crt
  docker cp "$ROOT/docker-compose.release.yml" "$DIND:$D/docker-compose.yml" >>"$LOG"
  docker cp "$ROOT/docker-compose.proxy.yml" "$DIND:$D/docker-compose.proxy.yml" >>"$LOG"
  {
    printf '%s\n' DB_NAME=mdmesh DB_USER=mdmesh "DB_PASSWORD=$(openssl rand -hex 12)" "BASE_URL=https://$HOSTNAME_" \
      "HASH_SECRET=$(openssl rand -hex 12)" SECURE_ENROLLMENT=0 ACME_EMAIL= TUNNEL_TOKEN= "IMAGE_OWNER=$OWNER" \
      "SERVER_VERSION=$(pin_of 0.6.0 server)" "WEB_VERSION=$(pin_of 0.6.0 web)" "SUPERVISOR_VERSION=$(pin_of 0.6.0 supervisor)" \
      "GITHUB_REPO=$OWNER/mdmesh-e2e-none" UPDATE_CHANNEL=stable POLL_INTERVAL_HOURS=6 CURRENT_VERSION=0.6.0 GITHUB_TOKEN= \
      AUTO_UPDATE=0 COMPOSE_PROJECT_NAME=mdmesh "MDMESH_SITE_DIR=$D/site.d"
    mode_env
  } > "$WORK/env"
  echo "    .env: $(grep -E '^(SITE_ADDRESS|COMPOSE_FILE|MDMESH_BIND|TRUSTED_PROXIES|MDMESH_ORIGIN_TLS)=' "$WORK/env" | tr '\n' ' ')"
  docker cp "$WORK/env" "$DIND:$D/.env" >>"$LOG"; in_dind chmod 600 "$D/.env"
  printf 'header X-E2E-Own-Rule "kept"\n' > "$WORK/own.caddy"; chmod 644 "$WORK/own.caddy"
  docker cp "$WORK/own.caddy" "$DIND:$D/site.d/e2e.caddy" >>"$LOG"
  # docker cp keeps the file's uid (the runner's, 1001 on GitHub's runners), and the edge loads only files of site.d's
  # owner or root: made root's, like the directory, as an install's own files are its owner's.
  in_dind chown 0:0 "$D/site.d/e2e.caddy"
  dc pull -q server caddy supervisor >>"$LOG" 2>&1 || { fail "pulling release 0.6.0"; return 1; }
  dc up -d >>"$LOG" 2>&1 || { fail "docker compose up"; return 1; }
  wait_init || { fail "the server did not initialize"; return 1; }
  (
    # shellcheck disable=SC2034  # PSQL is consumed by db.sh
    PSQL=(docker exec -i -w "$D" "$DIND" docker compose exec -T postgres psql -U mdmesh -d mdmesh)
    # shellcheck source=install/lib/db.sh
    . "$ROOT/install/lib/db.sh"
    mdm_seed "admin@$HOSTNAME_" "$ROOT/install/sql/hmdm_init.en.sql" "$ADMIN_PW" "$(openssl rand -hex 12)" \
      && mdm_post_seed "$ROOT/install/sql/post_seed.sql"
  ) >>"$LOG" 2>&1 || { fail "seeding"; return 1; }
  q "UPDATE users SET passwordreset = false WHERE login = 'admin'" >>"$LOG"
  pass "installed and seeded"
  front_files
  nginx_front
  wait_web || { fail "the console never answered through nginx"; return 1; }
  # A device, for the wake socket.
  local r t
  web POST /rest/public/auth/login "{\"login\":\"admin\",\"password\":\"$ADMIN_MD5\"}" >>"$LOG"
  r="$(web POST /rest/private/agent/v1/token '{}')"; t="$(grep -o '"token":"[^"]*"' <<< "$r" | head -1 | cut -d'"' -f4)"
  r="$(web POST /rest/public/agent/v1/enroll "{\"enrollToken\":\"$t\"}")"
  DEVICE="$(grep -o '"deviceId":"[^"]*"' <<< "$r" | head -1 | cut -d'"' -f4)"
  SECRET="$(grep -o '"deviceSecret":"[^"]*"' <<< "$r" | head -1 | cut -d'"' -f4)"
  check "a device enrolls through nginx (${DEVICE:-${r:0:80}})" test -n "$DEVICE" -a -n "$SECRET"
}

phase_nginx() {
  say "nginx: docs/proxies/nginx.conf in front of the edge on 127.0.0.1:8080"
  local r ip nonce h
  check "the edge publishes 127.0.0.1:8080 only ($(in_dind docker port "$(dc ps -q caddy)" | tr '\n' ' '))" \
    test "$(in_dind docker port "$(dc ps -q caddy)" | tr -d '\r')" = "8080/tcp -> 127.0.0.1:8080"
  chain_checks nginx 70
  r="$(ws not-the-secret "$DEVICE" 15)"
  check "nginx: a wake socket with a wrong secret is closed by the server ($r)" grep -q 'close=1008:unauthorized' <<< "$r"
  # shellcheck disable=SC2016  # expanded by the client's shell
  r="$(docker exec -i "$CLIENT" sh -c 'yes 0123456789abcdef | head -c 67108864 | curl -sS -m 120 --cacert /ca.pem \
    -X POST --data-binary @- -o /dev/null -w "%{http_code}" https://'"$HOSTNAME_"'/rest/public/name' 2>>"$LOG")"
  check "nginx: a 64 MiB request body is not refused by the proxy (HTTP $r, not 413)" bash -c '[ -n "$1" ] && [ "$1" != 413 ] && [ "$1" != 000 ]' _ "$r"
  r="$(web GET /healthz)"; check "nginx: /healthz ($r)" test "$r" = "200 ok"
  web GET / >>"$LOG"; h="$(last_headers)"
  check "nginx: the site.d rule applies (X-E2E-Own-Rule)" grep -qix 'x-e2e-own-rule: kept' <<< "$h"
  r="$(edge GET /)"
  check "the edge is not reachable from another machine on 127.0.0.1:8080 (${r:0:60})" grep -q '^000 ' <<< "$r"

  echo "-- MDMESH_BIND=0.0.0.0:8080 with TRUSTED_PROXIES=host: the edge refuses to start (others could arrive as host)"
  set_env MDMESH_BIND 0.0.0.0:8080
  dc up -d caddy >>"$LOG" 2>&1
  sleep 5
  r="$(edge GET /healthz)"
  check "0.0.0.0:8080 with host: the edge does not answer (${r:0:40})" grep -q '^000 ' <<< "$r"
  check "0.0.0.0:8080 with host: the edge says why in its log" \
    bash -c 'docker exec -i -w "$1" "$2" docker compose logs caddy 2>&1 | grep -qF "host needs a loopback MDMESH_BIND"' _ "$D" "$DIND"
  echo "-- MDMESH_BIND=0.0.0.0:8080, TRUSTED_PROXIES=192.0.2.10: the edge reachable directly; the client is not that proxy"
  set_env TRUSTED_PROXIES '"192.0.2.10"'
  dc up -d caddy >>"$LOG" 2>&1
  for _ in $(seq 1 30); do case "$(edge GET /healthz)" in '200 ok'*) break ;; esac; sleep 2; done
  nonce="px$(openssl rand -hex 4)"
  edge POST /rest/public/auth/login "{\"login\":\"$nonce\",\"password\":\"x\"}" -H 'X-Forwarded-For: 6.6.6.6' >>"$LOG"
  ip="$(audit_ip "$nonce")"
  check "direct to the edge, a forged X-Forwarded-For is not believed ($ip, want $CLIENT_IP)" test "$ip" = "$CLIENT_IP"
  r="$(ws "$SECRET" "$DEVICE" 8 edge)"
  check "direct to the edge: the wake socket opens and gets the wake signal ($r)" grep -qE "$WS_OK" <<< "$r"
  set_env MDMESH_BIND 127.0.0.1:8080
  set_env TRUSTED_PROXIES '"host"'
  dc up -d caddy >>"$LOG" 2>&1
  wait_web || fail "the console did not come back through nginx after MDMESH_BIND=127.0.0.1:8080"
}

phase_tls() {
  say "tls: MDMESH_ORIGIN_TLS=1, nginx.conf's HTTPS-origin lines (MDMesh's own CA)"
  set_env MDMESH_ORIGIN_TLS 1
  dc up -d caddy >>"$LOG" 2>&1
  local _ ok=0
  for _ in $(seq 1 30); do
    dc cp caddy:/data/caddy/pki/authorities/local/root.crt "/root/front/origin-ca.crt" >>"$LOG" 2>&1 && { ok=1; break; }
    sleep 2
  done
  check "docker compose cp caddy:/data/caddy/pki/authorities/local/root.crt (as DEPLOY.md says)" test "$ok" = 1
  in_dind chmod 644 /root/front/origin-ca.crt
  nginx_front tls
  wait_web || fail "the console never answered through nginx with the HTTPS origin"
  chain_checks "nginx, HTTPS origin" 10
  set_env MDMESH_ORIGIN_TLS 0
  dc up -d caddy >>"$LOG" 2>&1
  nginx_front
  wait_web || fail "the console did not come back through nginx with the plain origin"
}

phase_proxies() {
  local name
  for name in apache caddy traefik; do
    say "proxies: docs/proxies' $name configuration in front"
    stop_front
    case "$name" in
      apache)
        render "$ROOT/docs/proxies/apache.conf" "$WORK/mdmesh.conf"
        # The image's httpd.conf, with the modules the example names, and the example itself.
        in_dind docker run --rm httpd:2.4-alpine cat /usr/local/apache2/conf/httpd.conf > "$WORK/httpd.conf"
        sed -i -E 's/^#(LoadModule (ssl|proxy|proxy_http|headers|rewrite|socache_shmcb)_module )/\1/' "$WORK/httpd.conf"
        printf 'Listen 443\nInclude conf/mdmesh.conf\n' >> "$WORK/httpd.conf"
        chmod 644 "$WORK/httpd.conf"
        docker cp "$WORK/httpd.conf" "$DIND:/root/front/httpd.conf" >>"$LOG"
        docker cp "$WORK/mdmesh.conf" "$DIND:/root/front/mdmesh.conf" >>"$LOG"
        in_dind docker run -d --name "$FRONT" --network host -v /root/front/httpd.conf:/usr/local/apache2/conf/httpd.conf:ro \
          -v /root/front/mdmesh.conf:/usr/local/apache2/conf/mdmesh.conf:ro -v /root/front:/certs:ro httpd:2.4-alpine >>"$LOG" 2>&1 ;;
      caddy)
        # The one addition: this Caddy uses the test certificate instead of getting its own.
        render "$ROOT/docs/proxies/Caddyfile" "$WORK/Caddyfile"
        sed -i "s#^$HOSTNAME_ {#$HOSTNAME_ {\n\ttls /certs/fullchain.pem /certs/privkey.pem#" "$WORK/Caddyfile"
        docker cp "$WORK/Caddyfile" "$DIND:/root/front/Caddyfile" >>"$LOG"
        in_dind docker run -d --name "$FRONT" --network host -v /root/front/Caddyfile:/etc/caddy/Caddyfile:ro \
          -v /root/front:/certs:ro "$CADDY_IMAGE" >>"$LOG" 2>&1 ;;
      traefik)
        in_dind mkdir -p /root/front/traefik
        render "$ROOT/docs/proxies/traefik.yml" "$WORK/traefik.yml"
        render "$ROOT/docs/proxies/traefik-dynamic.yml" "$WORK/traefik-dynamic.yml"
        docker cp "$WORK/traefik.yml" "$DIND:/root/front/traefik/traefik.yml" >>"$LOG"
        docker cp "$WORK/traefik-dynamic.yml" "$DIND:/root/front/traefik/traefik-dynamic.yml" >>"$LOG"
        in_dind docker run -d --name "$FRONT" --network host -v /root/front/traefik:/etc/traefik:ro \
          -v /root/front:/certs:ro "$TRAEFIK" >>"$LOG" 2>&1 ;;
    esac
    if wait_web; then chain_checks "$name" 10
    else fail "$name: the console never answered through it"; in_dind docker logs --tail 20 "$FRONT" >>"$LOG" 2>&1; fi
  done
  nginx_front
  wait_web || fail "the console did not come back through nginx"
}

phase_update() {
  say "update: the supervisor's apply.sh to 0.6.1, then Roll back through nginx (as /recovery does)"
  local before r tok st _
  before="$(in_dind grep -vE '^(SERVER|WEB|SUPERVISOR)_VERSION=|^CURRENT_VERSION=' "$D/.env" | sha256sum)"
  # A release before 0.6.0 doesn't know the mode: the running supervisor refuses it and changes nothing.
  dc exec -T supervisor /app/apply.sh 0.5.9 "$(ref_of 0.6.1 server)" "$(ref_of 0.6.1 web)" "$(ref_of 0.6.1 supervisor)" \
    > "$WORK/apply-old.out" 2>&1
  check "an update to 0.5.9 is refused behind your own proxy" grep -q '^ERR this install runs behind your own proxy' "$WORK/apply-old.out"
  check "... and nothing changed (the edge still runs 0.6.0)" test "$(image_of caddy)" = "ghcr.io/$OWNER/mdmesh-web:$(pin_of 0.6.0 web)"
  dc exec -T supervisor /app/apply.sh 0.6.1 "$(ref_of 0.6.1 server)" "$(ref_of 0.6.1 web)" "$(ref_of 0.6.1 supervisor)" \
    > "$WORK/apply.out" 2>&1
  sed 's/^/      apply: /' "$WORK/apply.out" | cut -c1-200 | tail -12
  check "0.6.0 -> 0.6.1: OK" grep -qx 'OK 0.6.1' "$WORK/apply.out"
  check "after the update the edge runs 0.6.1 ($(image_of caddy))" test "$(image_of caddy)" = "ghcr.io/$OWNER/mdmesh-web:$(pin_of 0.6.1 web)"
  after_step "after the update"
  check "the update changed nothing in .env but the versions" \
    test "$(in_dind grep -vE '^(SERVER|WEB|SUPERVISOR)_VERSION=|^CURRENT_VERSION=' "$D/.env" | sha256sum)" = "$before"

  tok="$(dc exec -T supervisor cat /backups/recovery.token | tr -d '\r\n')"
  r="$(web POST /update/rollback '' -H 'X-MDMesh-Console: 1' -H "X-Recovery-Token: $tok")"
  check "Roll back through nginx accepted (${r:0:40})" grep -q '^202' <<< "$r"
  st=''
  for _ in $(seq 1 60); do
    sleep 5
    st="$(web GET /update/status | grep -o '"phase":"[a-z_]*"' | head -1)"
    case "$st" in *rolled_back*|*failed*) break ;; esac
  done
  check "Roll back finished: $st" test "$st" = '"phase":"rolled_back"'
  check "after Roll back the edge runs 0.6.0 again ($(image_of caddy))" test "$(image_of caddy)" = "ghcr.io/$OWNER/mdmesh-web:$(pin_of 0.6.0 web)"
  after_step "after Roll back"
}
after_step() {  # after_step LABEL: the mode and the edge are as installed, and the chain works
  local l=$1 h
  wait_web || { fail "$l: the console never answered through nginx"; return; }
  check "$l: COMPOSE_FILE still has the proxy overlay" in_dind grep -qx 'COMPOSE_FILE=docker-compose.yml:docker-compose.proxy.yml' "$D/.env"
  check "$l: the edge publishes 127.0.0.1:8080 only" test "$(in_dind docker port "$(dc ps -q caddy)" | tr -d '\r')" = "8080/tcp -> 127.0.0.1:8080"
  web GET / >>"$LOG"; h="$(last_headers)"
  check "$l: the site.d rule still applies" grep -qix 'x-e2e-own-rule: kept' <<< "$h"
  chain_checks "$l" 10
}

echo "proxy-e2e: work directory $WORK; phases: ${PHASES[*]}"
setup || { fail "setup"; exit 1; }
install_proxy_mode || exit 1
for p in "${PHASES[@]}"; do
  case "$p" in
    nginx) phase_nginx ;;
    tls) phase_tls ;;
    proxies) phase_proxies ;;
    update) phase_update ;;
    *) echo "unknown phase $p"; exit 2 ;;
  esac
done
