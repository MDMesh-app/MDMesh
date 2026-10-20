# Sourced (POSIX sh) by docker/web-entrypoint.sh, and by scripts/edge-check.sh to test it. Sets what docker/Caddyfile's
# :80 listener believes about a client's address ({client_ip}, which the server records as a device's public IP):
#   HTTP_TRUSTED_PEERS     the peers whose header is believed
#   HTTP_CLIENT_IP_HEADER  that header
#   Cloudflare tunnel  any private peer, CF-Connecting-IP. cloudflared's address on the stack's network is not fixed,
#                      and the mode publishes no port, so only the stack's own containers reach the listener.
#   anything else      TRUSTED_PROXIES (default loopback, which no Docker peer is), X-Forwarded-For. A forged
#                      CF-Connecting-IP is never believed. This covers the dev stack and a proxy of your own in front.
# Tunnel mode is COMPOSE_PROFILES listing "cloudflare": the compose files pass it from .env, which setup.sh and
# quickstart.sh write. A compose file from before v0.3.5 passes no COMPOSE_PROFILES (updates keep a quick-start
# install's compose file, and in a quick start only tunnel mode serves :80). Then, for a :80 site only, tunnel mode is
# a `cloudflared` container on the stack's network: the name resolves to an IPv4 address inside one of this
# container's own interface subnets (so a LAN DNS server's "cloudflared" doesn't count). It waits up to
# CLOUDFLARED_WAIT seconds (default 15) because cloudflared starts after caddy. Not found: the loopback rule, so the
# recorded address is the tunnel's, never forged.

# ipv4_int A.B.C.D: the address as an integer; fails for anything else (leading zeros too: sh reads them as octal).
ipv4_int() {
  case "$1" in *[!0-9.]*) return 1 ;; esac
  _ifs=$IFS; IFS=.
  # shellcheck disable=SC2086  # split on the dots on purpose
  set -- $1
  IFS=$_ifs
  [ "$#" -eq 4 ] || return 1
  for _o in "$@"; do
    case "$_o" in '' | 0[0-9]*) return 1 ;; esac
    [ "$_o" -le 255 ] || return 1
  done
  echo $(( ($1 << 24) | ($2 << 16) | ($3 << 8) | $4 ))
}

# on_own_subnet A.B.C.D: succeeds when the address is inside a subnet of one of this container's interfaces.
on_own_subnet() {
  _ip=$(ipv4_int "$1") || return 1
  # Global-scope addresses only: loopback is not the stack's network.
  for _cidr in $(ip -o -4 addr show scope global 2>/dev/null | awk '{ print $4 }'); do
    _net=$(ipv4_int "${_cidr%/*}") || continue
    _len=${_cidr#*/}
    # A prefix length must be 1..32; anything else (or /0, which matches everything) is skipped.
    case "$_len" in ''|*[!0-9]*) continue ;; esac
    if [ "$_len" -lt 1 ] || [ "$_len" -gt 32 ]; then continue; fi
    _mask=$(( (0xFFFFFFFF << (32 - _len)) & 0xFFFFFFFF ))
    [ $(( _ip & _mask )) -eq $(( _net & _mask )) ] && return 0
  done
  return 1
}

# cloudflared_on_own_network: `cloudflared` resolves to an address on one of this container's subnets.
cloudflared_on_own_network() {
  for _addr in $(getent ahostsv4 cloudflared 2>/dev/null | awk '{ print $1 }' | sort -u); do
    on_own_subnet "$_addr" && return 0
  done
  return 1
}

edge_tunnel_mode() {
  if [ "${COMPOSE_PROFILES+set}" = set ]; then
    case ",$(printf '%s' "$COMPOSE_PROFILES" | tr -d ' ')," in
      *,cloudflare,*) return 0 ;;
      *) return 1 ;;
    esac
  fi
  [ "${SITE_ADDRESS:-}" = ":80" ] || return 1
  _wait=${CLOUDFLARED_WAIT:-15}
  while :; do
    cloudflared_on_own_network && return 0
    [ "$_wait" -gt 0 ] || return 1
    _wait=$((_wait - 1))
    sleep 1
  done
}

if edge_tunnel_mode; then
  HTTP_TRUSTED_PEERS=private_ranges
  HTTP_CLIENT_IP_HEADER=CF-Connecting-IP
else
  HTTP_TRUSTED_PEERS=${TRUSTED_PROXIES:-127.0.0.1/32 ::1/128}
  HTTP_CLIENT_IP_HEADER=X-Forwarded-For
fi
export HTTP_TRUSTED_PEERS HTTP_CLIENT_IP_HEADER
