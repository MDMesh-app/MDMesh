#!/usr/bin/env bash
# Hosting settings shared by the Docker installers. Requires install/lib/url.sh.

# >>> proxy.sh functions (quickstart.sh keeps a verbatim copy) >>>
# Turn the validated public host[:port] into a certificate name (the origin always listens on 443).
mdm_proxy_hostname() {
  case "$1" in
    \[*) printf '%s]\n' "${1%%]*}" ;;
    *) printf '%s\n' "${1%%:*}" ;;
  esac
}

# Validate addresses before writing a shell-readable .env. Trust only explicit IPs/CIDRs,
# never all private networks. Caddy validates the final IP syntax when loading its config.
mdm_proxy_settings() {
  local public_host token ip prefix octet candidate
  local -a proxies octets
  mdm_check_host HOST || return 1
  public_host=$(mdm_proxy_hostname "$HOST")
  CADDY_HOST=${CADDY_HOST:-$public_host}
  mdm_check_host CADDY_HOST || return 1
  [ "$(mdm_proxy_hostname "$CADDY_HOST")" = "$CADDY_HOST" ] || {
    echo 'CADDY_HOST must be a hostname or IP without a port (the origin uses 443).' >&2; return 1;
  }
  read -r -a proxies <<< "${TRUSTED_PROXIES:-}"
  [ "${#proxies[@]}" -gt 0 ] || { echo 'Set the external proxy IP/CIDR in TRUSTED_PROXIES.' >&2; return 1; }
  for token in "${proxies[@]}"; do
    ip=${token%%/*}; prefix=${token#*/}
    case "$ip" in
      *:*)
        candidate="[$ip]"
        mdm_check_host candidate || return 1
        if [ "$prefix" != "$token" ]; then
          [[ "$prefix" =~ ^[0-9]{1,3}$ ]] && [ "$((10#$prefix))" -le 128 ] || {
            echo "Invalid proxy prefix: $token" >&2; return 1;
          }
        fi ;;
      *)
        [[ "$ip" =~ ^[0-9]{1,3}(\.[0-9]{1,3}){3}$ ]] || {
          echo "Use a proxy IP or CIDR, not a hostname: $token" >&2; return 1;
        }
        IFS=. read -r -a octets <<< "$ip"
        for octet in "${octets[@]}"; do
          [ "$((10#$octet))" -le 255 ] || { echo "Invalid proxy IP: $token" >&2; return 1; }
        done
        if [ "$prefix" != "$token" ]; then
          [[ "$prefix" =~ ^[0-9]{1,2}$ ]] && [ "$((10#$prefix))" -le 32 ] || {
            echo "Invalid proxy prefix: $token" >&2; return 1;
          }
        fi ;;
    esac
  done
  TRUSTED_PROXIES="${proxies[*]}"
  SITE_ADDRESS="https://${CADDY_HOST}:443"
  [ "$CADDY_HOST" = "$public_host" ] || SITE_ADDRESS+=", https://${public_host}:443"
  ACME_EMAIL=''; TUNNEL_TOKEN=''; COMPOSE_PROFILES=''
  COMPOSE_FILE='docker-compose.yml:docker-compose.reverse-proxy.yml'
}
# <<< proxy.sh functions <<<
