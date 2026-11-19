#!/usr/bin/env bash
# shellcheck disable=SC2034  # the variables these functions set are their callers' (the installers')
# The Docker installers' hosting modes, in one table (setup.sh sources this file; quickstart.sh keeps a verbatim copy of
# the functions between the markers below, which CI checks). Source it after install/lib/url.sh and
# install/lib/answers.sh; do not execute it. bash 3.2 compatible (quickstart may run under macOS's bash).
#
# A mode is a number (the menu's, and MDMESH_MODE's older form) and a name (MDMESH_MODE, the --<name> flag):
#   1 tunnel  Cloudflare Tunnel: no port published; cloudflared reaches the edge (Caddy) on the stack's network.
#   2 domain  Your own domain: the edge publishes 80/443 and gets its certificate from Let's Encrypt.
#   3 proxy   Behind your own proxy: the edge publishes plain HTTP (or HTTPS from its internal CA) on MDMESH_BIND,
#             127.0.0.1:8080 by default, and believes X-Forwarded-For only from TRUSTED_PROXIES
#             (docker-compose.proxy.yml, docker/edge-peers.sh).
# Everything a mode decides is here: its menu line, its compose files and profiles, the edge's site address, the answers
# only it asks, and what to do next. The installers ask the questions themselves (install/options.json marks each one).

# >>> modes.sh functions (quickstart.sh keeps a verbatim copy) >>>
# number|name|menu label|who should pick it
_MDM_MODES='1|tunnel|Cloudflare Tunnel|easiest: no ports to open (domain on Cloudflare)
2|domain|Your own domain|MDMesh gets its own HTTPS (ports 80/443 must reach this machine)
3|proxy|Behind your own proxy|your nginx/Apache/Caddy/Traefik does HTTPS; MDMesh listens on 127.0.0.1:8080'
# The answers only one mode asks: number|answer id|its default in the dry-run plan.
_MDM_MODE_ANSWERS='1|tunnel-token|
2|acme-email|
3|bind|127.0.0.1:8080
3|trusted-proxies|host'
MDM_PROXY_BIND_DEFAULT=127.0.0.1:8080
MDM_PROXY_TRUSTED_DEFAULT=host

# mdm_mode_name NUMBER: the mode's name (tunnel, domain, proxy).
mdm_mode_name() { printf '%s\n' "$_MDM_MODES" | awk -F'|' -v n="$1" '$1 == n { print $2 }'; }

# mdm_mode_menu FOOTER: the hosting-mode question's text (the caller reads the answer). When something on this host
# already listens on port 80 or 443 (often a web server of your own), it says so and points at the proxy mode.
mdm_mode_menu() {
  echo "How will your phones reach MDMesh?"
  printf '%s\n' "$_MDM_MODES" | awk -F'|' '{ printf "  %s) %-23s %s\n", $1, $3, $4 }'
  if mdm_web_ports_busy; then
    echo "  Ports 80/443 are already in use on this machine. If that is your own web server, pick 3."
  fi
  echo "$1"
}

# mdm_web_ports_busy: succeeds when a TCP listener on this host holds port 80 or 443 (ss from iproute2; without it,
# nothing is said).
mdm_web_ports_busy() {
  command -v ss > /dev/null 2>&1 || return 1
  ss -Hltn 2> /dev/null | awk '{ n = split($4, a, ":"); if (a[n] == "80" || a[n] == "443") f = 1 } END { exit !f }'
}

# mdm_mode_from_flag NAME: the mode flag given on the command line (--NAME: --tunnel, --domain or --proxy; the
# installers' argument loops take only these) answers MDMESH_MODE, as the environment or an answers file would. Called
# after mdm_answers_load. Stops the installer when MDMESH_MODE names another mode. No NAME: nothing to do.
mdm_mode_from_flag() {
  local given=${MDMESH_MODE-} n
  [ -n "$1" ] || return 0
  if [ -n "$given" ]; then
    n=$(printf '%s\n' "$_MDM_MODES" | awk -F'|' -v v="$given" '$1 == v || $2 == v { print $2 }')
    [ "$n" = "$1" ] || _mdm_answer_fail "--$1 and MDMESH_MODE=$given name different hosting modes: give one."
  fi
  MDMESH_MODE=$1
}

# mdm_mode_answers_unused: once the mode is known, an answer given for another mode's question is ignored, saying so.
mdm_mode_answers_unused() {
  local m id
  while IFS='|' read -r m id _; do
    [ "$m" = "$MODE" ] || mdm_unused "$id" "in $(mdm_mode_name "$MODE") mode"
  done <<EOF
$_MDM_MODE_ANSWERS
EOF
}

# mdm_mode_plan: the dry-run plan's lines for the answers only one mode asks: asked (or answered) in the mode given,
# "unused" in the others, and all of them when the mode isn't known yet.
mdm_mode_plan() {
  local m id def
  while IFS='|' read -r m id def; do
    if ! mdm_given mode || [ "$m" = "$MODE" ]; then mdm_plan_line "$id" "$def"; else mdm_plan_row "$id" - unused; fi
  done <<EOF
$_MDM_MODE_ANSWERS
EOF
}

# mdm_mode_of_env: the mode of an existing .env (already loaded): tunnel when COMPOSE_PROFILES lists cloudflare, proxy
# when COMPOSE_FILE lists docker-compose.proxy.yml, else domain.
mdm_mode_of_env() {
  case ",$(printf '%s' "${COMPOSE_PROFILES:-}" | tr -d ' ')," in *,cloudflare,*) echo 1; return ;; esac
  case ":${COMPOSE_FILE:-}:" in *:docker-compose.proxy.yml:*) echo 3; return ;; esac
  echo 2
}

# mdm_mode_settings NUMBER [rerun]: the mode's settings, from HOST (and, behind your proxy, PROXY_BIND and ORIGIN_TLS):
# COMPOSE_ARGS (its compose files and profile, for a command line) and EXTRA_NOTE (what to do next); and, for a new .env,
# SITE_ADDRESS (the edge's site; behind your proxy the edge sets its own), COMPOSE_FILE and COMPOSE_PROFILES (compose and
# the updater read them from .env), with another mode's answers cleared so they never reach .env. With rerun (an
# existing .env, already loaded and exported) only the first two: its own values stay as they are.
mdm_mode_settings() {
  local f site file profiles=''
  case "$1" in
    1) site=':80'; file='docker-compose.yml'; profiles='cloudflare'
       EXTRA_NOTE="In Cloudflare, route the tunnel's public hostname ($HOST) to http://caddy:80." ;;
    2) site=$HOST; file='docker-compose.yml:docker-compose.domain.yml'
       EXTRA_NOTE="Make sure ${HOST} resolves to this server and ports 80/443 are open." ;;
    3) site=':8080'; file='docker-compose.yml:docker-compose.proxy.yml'
       if [ "${ORIGIN_TLS:-0}" = 1 ]; then
         EXTRA_NOTE="Point your proxy at https://${PROXY_BIND} (certificate name $(mdm_host_name "$HOST"), issued by MDMesh's own CA; DEPLOY.md, Behind your own proxy)."
       else
         EXTRA_NOTE="Point your proxy at http://${PROXY_BIND} (ready configurations: DEPLOY.md, Behind your own proxy)."
       fi ;;
    *) _mdm_answer_fail "internal: unknown hosting mode $1" ;;
  esac
  COMPOSE_ARGS=''
  # compose's own default file is left to COMPOSE_FILE (.env), as setup.sh always did in tunnel mode.
  [ "$file" = docker-compose.yml ] || for f in $(printf '%s' "$file" | tr ':' ' '); do COMPOSE_ARGS="$COMPOSE_ARGS -f $f"; done
  [ -z "$profiles" ] || COMPOSE_ARGS="$COMPOSE_ARGS --profile $profiles"
  COMPOSE_ARGS=${COMPOSE_ARGS# }
  [ "${2:-}" != rerun ] || return 0
  SITE_ADDRESS=$site; COMPOSE_FILE=$file; COMPOSE_PROFILES=$profiles
  [ "$1" = 1 ] || TUNNEL_TOKEN=''
  [ "$1" = 2 ] || ACME_EMAIL=''
}

# mdm_host_name HOSTPORT: the host of a checked host[:port] (an [IPv6] keeps its brackets).
mdm_host_name() {
  case "$1" in
    \[*) printf '%s]\n' "${1%%]*}" ;;
    *) printf '%s\n' "${1%%:*}" ;;
  esac
}

# mdm_site_dir: makes ./site.d, for your own Caddy rules (the edge imports site.d/*.caddy into its site; updates never
# touch it), with a short README, and sets MDMESH_SITE_DIR to its absolute path for .env: compose mounts it from there,
# also when the updater runs compose from inside its own container. A directory whose path .env can't hold as it is
# (a space, a quote, a $) is left out, saying so: the edge then mounts an empty volume instead.
mdm_site_dir() {
  local d
  MDMESH_SITE_DIR=''
  mkdir -p site.d && chmod 755 site.d || { warn "Could not make site.d here: your own edge rules can't be added."; return 0; }
  if [ ! -e site.d/README ]; then
    printf '%s\n' "Your own rules for MDMesh's edge (Caddy). Every *.caddy file here is imported into the edge's site," \
      "after MDMesh's own settings. Updates never change this directory. Make each file readable by everyone" \
      "(chmod 644), check it, then restart the edge (from the install directory):" \
      "  docker compose exec caddy caddy validate --config /etc/caddy/Caddyfile && docker compose restart caddy" \
      "See DEPLOY.md, Your own edge rules." > site.d/README
    chmod 644 site.d/README
  fi
  d=$(pwd -P)
  case "$d" in
    *[!${_MDM_ALNUM}._/+@-]*) warn "site.d is not connected to the edge: its path ($d) has a character .env can't hold as it is (DEPLOY.md, Your own edge rules)." ;;
    *) MDMESH_SITE_DIR="$d/site.d" ;;
  esac
}

# mdm_mode_env: the .env lines only this mode writes (behind your proxy: where the edge listens, whom it believes, and
# whether it speaks HTTPS to your proxy).
mdm_mode_env() {
  [ "$MODE" = 3 ] || return 0
  printf '%s\n' "MDMESH_BIND=${PROXY_BIND}" "TRUSTED_PROXIES=\"${PROXY_TRUSTED}\"" "MDMESH_ORIGIN_TLS=${ORIGIN_TLS:-0}"
}
# <<< modes.sh functions <<<
