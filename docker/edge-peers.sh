# shellcheck shell=sh
# Sourced (POSIX sh) by docker/web-entrypoint.sh, and by scripts/edge-check.sh to test it. Sets what docker/Caddyfile's
# :80 listener believes about a client's address ({client_ip}, which the server records as a device's public IP):
#   HTTP_TRUSTED_PEERS     the peers whose header is believed
#   HTTP_CLIENT_IP_HEADER  that header
# Behind your own proxy (MDMESH_EXPOSURE=proxy, which docker-compose.proxy.yml sets), the :8080 listener's instead:
#   PROXY_TRUSTED_PEERS    EDGE_TRUSTED_PROXIES (.env's TRUSTED_PROXIES) as Caddy's list: addresses and ranges, `host`
#                          made this container's default gateway(s), where Docker delivers every connection from this
#                          machine to a published port. Empty, a range wider than an IPv4 /8 or an IPv6 /16, a name or
#                          private_ranges stops the edge (it would trust nobody, or far more than your proxy).
#   SITE_ADDRESS, EDGE_TLS, EDGE_ORIGIN_NAME
#                          ":8080", or with MDMESH_ORIGIN_TLS=1 "https://<BASE_URL's host>:8080" with a certificate
#                          from Caddy's own CA (EDGE_TLS=internal; EDGE_ORIGIN_NAME is that host).
#   Cloudflare tunnel  any private peer, CF-Connecting-IP. cloudflared's address on the stack's network is not fixed,
#                      and the mode publishes no port, so only the stack's own containers reach the listener.
#   anything else      TRUSTED_PROXIES (unset or empty: loopback, which no Docker peer is), X-Forwarded-For. A forged
#                      CF-Connecting-IP is never believed. This covers the dev stack and a proxy of your own in front.
#                      `host` in it (left from the proxy mode) is dropped, saying so: only the proxy mode reads it.
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

edge_refuse() {
  echo "MDMesh edge: $* (DEPLOY.md, Behind your own proxy). Not starting." >&2
  exit 1
}

# edge_gateways: this container's default gateway(s), IPv4 and IPv6.
edge_gateways() {
  { ip -4 route show default; ip -6 route show default; } 2>/dev/null | awk '$1 == "default" && $2 == "via" { print $3 }'
}

# edge_proxy_list LIST [BIND]: LIST (addresses and ranges, separated by spaces or commas, and `host`), checked as the
# installers check it (install/lib/answers.sh, rule proxies; install/lib/modes.sh, mdm_proxy_trust_problem), as Caddy's
# static list: `host` only with a loopback BIND (MDMESH_BIND; unset: the default 127.0.0.1:8080), and no IPv4-mapped
# IPv6 address (it wouldn't match the IPv4 peer it means). Refuses (exits) on anything else.
edge_proxy_list() {
  _out=''
  set -f   # no pathname expansion of the entries (this runs in a command substitution: it ends with it)
  for _t in $(printf '%s' "$1" | tr ',' ' '); do
    case "$_t" in
      host)
        # Only with a loopback bind: other machines reaching any other address can arrive from the same gateway
        # (Docker Desktop, rootless Docker, docker-proxy for IPv6), and would then be believed.
        case "${2:-127.0.0.1:8080}" in
          127.*|\[::1\]:*) ;;
          *) edge_refuse "TRUSTED_PROXIES says host (a proxy on this machine), but MDMESH_BIND is ${2}: host needs a loopback MDMESH_BIND (127.0.0.1 or [::1]); list your proxy's own address instead" ;;
        esac
        _gw=$(edge_gateways)
        [ -n "$_gw" ] || edge_refuse "TRUSTED_PROXIES says host, but this container has no default gateway"
        for _g in $_gw; do case "$_g" in *:*) _out="$_out $_g/128" ;; *) _out="$_out $_g/32" ;; esac; done
        continue ;;
    esac
    _a=${_t%/*}; _l=''
    case "$_t" in */*) _l=${_t#*/}; case "$_l" in ''|*[!0-9]*|0?*) edge_refuse "TRUSTED_PROXIES: \"$_t\" has no valid prefix length" ;; esac ;; esac
    if ipv4_int "$_a" >/dev/null; then
      _min=8; _max=32
    else
      case "$_a" in
        *:*:*) case "$_a" in *[!0-9A-Fa-f:]*|*:::*|::[Ff][Ff][Ff][Ff]:*|0:0:0:0:0:[Ff][Ff][Ff][Ff]:*) edge_refuse "TRUSTED_PROXIES: \"$_t\" is not an address or range" ;; esac
               _min=16; _max=128 ;;
        *) edge_refuse "TRUSTED_PROXIES: \"$_t\" is not an address or range (no names, no private_ranges)" ;;
      esac
    fi
    if [ -n "$_l" ]; then
      { [ "${#_l}" -le 3 ] && [ "$_l" -ge "$_min" ] && [ "$_l" -le "$_max" ]; } \
        || edge_refuse "TRUSTED_PROXIES: \"$_t\": a range must be /$_min to /$_max (a wider one trusts far more than your proxy)"
    else
      _t="$_t/$_max"
    fi
    _out="$_out $_t"
  done
  _out=${_out# }
  [ -n "$_out" ] || edge_refuse "TRUSTED_PROXIES is empty: list your proxy's address (host for a proxy on this machine)"
  printf '%s\n' "$_out"
}

# edge_url_host URL: the host of URL, without scheme, port or path (an [IPv6] keeps its brackets).
edge_url_host() {
  _h=${1#*://}; _h=${_h%%/*}
  case "$_h" in \[*) printf '%s]\n' "${_h%%]*}" ;; *) printf '%s\n' "${_h%%:*}" ;; esac
}

if [ "${MDMESH_EXPOSURE:-}" = proxy ]; then
  PROXY_TRUSTED_PEERS=$(edge_proxy_list "${EDGE_TRUSTED_PROXIES:-}" "${EDGE_BIND:-}") || exit 1
  if [ "${MDMESH_ORIGIN_TLS:-0}" = 1 ]; then
    _host=$(edge_url_host "${BASE_URL:-}")
    case "$_host" in ''|*[!0-9A-Za-z.:\[\]-]*) edge_refuse "MDMESH_ORIGIN_TLS=1 needs BASE_URL's host for the certificate (BASE_URL=${BASE_URL:-unset})" ;; esac
    EDGE_TLS=internal
    # The site address keeps an IPv6 host's brackets; the name for default_sni (a TLS server name) has none.
    SITE_ADDRESS="https://$_host:8080"
    EDGE_ORIGIN_NAME=${_host#\[}; EDGE_ORIGIN_NAME=${EDGE_ORIGIN_NAME%\]}
  else
    EDGE_TLS=off
    SITE_ADDRESS=":8080"
  fi
  export PROXY_TRUSTED_PEERS EDGE_TLS EDGE_ORIGIN_NAME SITE_ADDRESS
fi

# Outside the proxy mode TRUSTED_PROXIES reaches Caddy as it is (docker/Caddyfile, :443), where `host` is no address:
# Caddy would refuse the whole configuration and the edge would never start. An install moved out of the proxy mode with
# its `host` left in .env: the entry is dropped, saying so, which trusts fewer peers, never more.
if [ "${MDMESH_EXPOSURE:-}" != proxy ]; then
  _kept=''; _dropped=''
  set -f
  for _t in $(printf '%s' "${TRUSTED_PROXIES:-}" | tr ',' ' '); do
    if [ "$_t" = host ]; then _dropped=1; else _kept="$_kept $_t"; fi
  done
  set +f
  if [ -n "$_dropped" ]; then
    echo "MDMesh edge: TRUSTED_PROXIES says host, which only the proxy mode (docker-compose.proxy.yml) reads: ignoring it, so no proxy on this machine is believed. Set TRUSTED_PROXIES= in .env (docs/UPGRADING.md, Changing the hosting mode)." >&2
    TRUSTED_PROXIES=${_kept# }
    export TRUSTED_PROXIES
  fi
fi

if edge_tunnel_mode; then
  HTTP_TRUSTED_PEERS=private_ranges
  HTTP_CLIENT_IP_HEADER=CF-Connecting-IP
else
  HTTP_TRUSTED_PEERS=${TRUSTED_PROXIES:-127.0.0.1/32 ::1/128}
  HTTP_CLIENT_IP_HEADER=X-Forwarded-For
fi
export HTTP_TRUSTED_PEERS HTTP_CLIENT_IP_HEADER

# Your own rules (DEPLOY.md, "Your own edge rules"): the install directory's site.d, mounted read-only at EDGE_SITE_SRC
# (/etc/caddy/site.d). Each *.caddy file goes into the edge's site, so only a file that its owner alone can change is
# loaded: a regular file (not a link), writable by nobody but its owner, owned by root or by site.d's own owner, in a
# site.d nobody else may write. (That site.d is no link is the installers' rule: Docker resolves a bind mount's source,
# so in here the mount point never is one; a link's target is checked like any directory.) Anything else is left out,
# saying why; the edge starts without it. The files that pass are copied into EDGE_SITE_D (root's, under /run), which
# docker/Caddyfile imports, so what was checked is what is loaded. Run at every start (docker compose restart caddy
# after a change). Nothing here can stop the edge: at worst none of your rules are loaded, and MDMesh's own routes are
# unaffected.
edge_site_log() { echo "MDMesh edge: $* (DEPLOY.md, Your own edge rules)." >&2; }
# edge_site_open UID MODE OWNERS: the octal MODE gives group or others write, or UID is not one of OWNERS.
edge_site_open() {
  [ $(( 0$2 & 022 )) -ne 0 ] && return 0
  case " $3 " in *" $1 "*) return 1 ;; esac
  return 0
}
edge_site_d() {
  _src=${EDGE_SITE_SRC:-/etc/caddy/site.d}
  EDGE_SITE_D=/run/mdmesh-site.d
  rm -rf "$EDGE_SITE_D" 2>/dev/null || true
  if ! mkdir -m 755 "$EDGE_SITE_D" 2>/dev/null; then
    # Fail closed: a path with nothing in it, never an earlier start's copy.
    EDGE_SITE_D=/run/mdmesh-site.d.none
    edge_site_log "could not prepare the checked copy of site.d: your own rules are not loaded"; return 0
  fi
  [ -d "$_src" ] || return 0
  _st=$(stat -c '%u %a' "$_src" 2>/dev/null) || { edge_site_log "could not read site.d: your own rules are not loaded"; return 0; }
  if edge_site_open "${_st%% *}" "${_st#* }" "0 ${_st%% *}"; then
    edge_site_log "site.d may be changed by other users: none of your own rules are loaded"; return 0
  fi
  _owners="0 ${_st%% *}"
  for _f in "$_src"/*.caddy; do
    [ -e "$_f" ] || [ -L "$_f" ] || continue
    _n=${_f##*/}
    if [ -L "$_f" ] || [ ! -f "$_f" ]; then edge_site_log "site.d/$_n is not loaded: it is a link or not a regular file"; continue; fi
    _st=$(stat -c '%u %a' "$_f" 2>/dev/null) || { edge_site_log "site.d/$_n is not loaded: it can't be read"; continue; }
    if edge_site_open "${_st%% *}" "${_st#* }" "$_owners"; then
      edge_site_log "site.d/$_n is not loaded: other users may change it (it must be site.d's owner's or root's, chmod 644; under rootless Docker or userns-remap a file of the host's root shows as uid 65534: make it yours)"; continue
    fi
    if cat "$_f" > "$EDGE_SITE_D/$_n" 2>/dev/null && chmod 644 "$EDGE_SITE_D/$_n"; then :; else
      rm -f "$EDGE_SITE_D/$_n" 2>/dev/null || true
      edge_site_log "site.d/$_n is not loaded: it can't be read"
    fi
  done
}
edge_site_d
export EDGE_SITE_D

# What this start computed, for every later caddy command in the container: docker compose exec runs with the
# container's configured environment, not this one, so a `caddy reload` (or validate, or adapt) there would read the
# Caddyfile with the raw settings (trusted peers back to loopback, the HTTPS origin gone, site.d unchecked). The edge's
# caddy (docker/edge-caddy.sh, /usr/local/bin/caddy) reads this root-owned file first, so every command sees what the
# running edge saw. Written whole, then renamed; not written, the wrapper falls back to the environment it gets.
edge_env_save() {
  _tmp=/run/mdmesh-edge.env.$$
  for _k in TRUSTED_PROXIES HTTP_TRUSTED_PEERS HTTP_CLIENT_IP_HEADER PROXY_TRUSTED_PEERS SITE_ADDRESS EDGE_TLS EDGE_ORIGIN_NAME EDGE_SITE_D; do
    _v=''; eval "_set=\${$_k+x}"
    [ -n "$_set" ] || continue
    eval "_v=\${$_k}"
    printf "%s='%s'\n" "$_k" "$(printf '%s' "$_v" | sed "s/'/'\\\\''/g")"
  done > "$_tmp" 2>/dev/null && chmod 644 "$_tmp" && mv -f "$_tmp" /run/mdmesh-edge.env && return 0
  rm -f "$_tmp" 2>/dev/null || true
  echo "MDMesh edge: could not save its settings to /run/mdmesh-edge.env: restart the edge (docker compose restart caddy), never caddy reload it." >&2
}
edge_env_save
