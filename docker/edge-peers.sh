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
# a `cloudflared` container on the stack's network, waiting up to CLOUDFLARED_WAIT seconds (default 15) because
# cloudflared starts after caddy. Not found: the loopback rule, so the recorded address is the tunnel's, never forged.

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
    getent hosts cloudflared >/dev/null 2>&1 && return 0
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
