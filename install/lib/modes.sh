#!/usr/bin/env bash
# shellcheck disable=SC2034  # the variables these functions set are their callers' (the installers')
# The Docker installers' hosting modes, in one table (setup.sh sources this file; quickstart.sh keeps a verbatim copy of
# the functions between the markers below, which CI checks). Source it after install/lib/url.sh and
# install/lib/answers.sh; do not execute it. The installers define say, warn and err. bash 3.2 compatible (quickstart
# may run under macOS's bash).
#
# A mode is a number (the menu's, and MDMESH_MODE's older form) and a name (MDMESH_MODE, the --<name> flag):
#   1 tunnel  Cloudflare Tunnel: no port published; cloudflared reaches the edge (Caddy) on the stack's network.
#   2 domain  Your own domain: the edge publishes 80/443 and gets its certificate from Let's Encrypt.
#   3 proxy   Behind your own proxy: the edge publishes plain HTTP (or HTTPS from its internal CA) on MDMESH_BIND,
#             127.0.0.1:8080 by default, and believes X-Forwarded-For only from TRUSTED_PROXIES
#             (docker-compose.proxy.yml, docker/edge-peers.sh).
# Everything a mode decides is here: its menu line and questions, its compose files and profiles, the edge's site
# address, the answers only it asks, and what to do next. install/options.json lists each question (CI checks it
# against this file and the installers).

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
# Where the docs are for someone with no clone (the quick start): the main branch's pages on GitHub.
MDM_DOCS=https://github.com/MDMesh-app/MDMesh/blob/main

# mdm_mode_name NUMBER: the mode's name (tunnel, domain, proxy).
mdm_mode_name() { printf '%s\n' "$_MDM_MODES" | awk -F'|' -v n="$1" '$1 == n { print $2 }'; }

# mdm_mode_choose FOOTER: asks the hosting-mode question (MODE: 1, 2 or 3), unless it was answered in advance. When
# something on this host already listens on port 80 or 443 (often a web server of your own), it says so and points at
# the proxy mode.
mdm_mode_choose() {
  mdm_answered mode && return 0
  echo "How will your phones reach MDMesh?"
  printf '%s\n' "$_MDM_MODES" | awk -F'|' '{ printf "  %s) %-23s %s\n", $1, $3, $4 }'
  if mdm_listening 80 || mdm_listening 443; then
    echo "  Ports 80/443 are already in use on this machine. If that is your own web server, pick 3."
  fi
  echo "$1"
  MODE=""
  while :; do
    read -rp "Choose [1/2/3]: " MODE || { err "No selection (non-interactive run?). Aborting."; exit 1; }   # answer: mode
    case "$MODE" in 1|2|3) break ;; *) warn "Please enter 1, 2 or 3." ;; esac
  done
}

# mdm_listening PORT [IP]: succeeds when a TCP listener on this host holds PORT on IP, or on every address (with no IP:
# on any). From ss (iproute2), else netstat, else lsof; with none of them, nothing is found.
mdm_listening() {
  local out
  if command -v ss > /dev/null 2>&1; then out=$(ss -ltn 2> /dev/null | awk 'NR > 1 { print $4 }')
  elif command -v netstat > /dev/null 2>&1; then out=$(netstat -ltn 2> /dev/null | awk '$6 == "LISTEN" { print $4 }')
  elif command -v lsof > /dev/null 2>&1; then out=$(lsof -nP -iTCP -sTCP:LISTEN 2> /dev/null | awk 'NR > 1 { print $9 }')
  else return 1; fi
  printf '%s\n' "$out" | awk -v port="$1" -v ip="${2:-}" '
    { n = split($0, a, ":"); if (a[n] != port) next
      addr = substr($0, 1, length($0) - length(port) - 1); gsub(/[][]/, "", addr); sub(/%.*/, "", addr)
      if (ip == "" || addr == ip || addr == "*" || addr == "0.0.0.0" || addr == "::" || addr == "" ||
          ip == "0.0.0.0" || ip == "::") f = 1 }
    END { exit !f }'
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

# mdm_mode_check_answers: once the answers are loaded, the rules that span them: an answer given for another mode's
# question is ignored, saying so (MDMESH_ORIGIN_TLS too); behind your own proxy, `host` is refused with an address other
# machines reach (mdm_proxy_trust_problem).
mdm_mode_check_answers() {
  local m id why
  mdm_given mode || return 0
  while IFS='|' read -r m id _; do
    [ "$m" = "$MODE" ] || mdm_unused "$id" "in $(mdm_mode_name "$MODE") mode"
  done <<EOF
$_MDM_MODE_ANSWERS
EOF
  if [ "$MODE" != 3 ] && [ "${MDMESH_ORIGIN_TLS:-0}" = 1 ]; then
    printf 'MDMesh installer: MDMESH_ORIGIN_TLS is not used in %s mode: ignoring it.\n' "$(mdm_mode_name "$MODE")" >&2
  fi
  # Only when the proxies won't be asked (given, no questions, or a dry run): a run at the terminal asks first.
  if [ "$MODE" = 3 ] && mdm_given bind && { mdm_given trusted-proxies || [ "${MDMESH_NONINTERACTIVE:-0}" = 1 ] \
     || [ "${MDMESH_DRY_RUN:-0}" = 1 ]; }; then
    why=$(mdm_proxy_trust_problem "${PROXY_BIND:-$MDM_PROXY_BIND_DEFAULT}" "${PROXY_TRUSTED:-$MDM_PROXY_TRUSTED_DEFAULT}")
    [ -z "$why" ] || _mdm_answer_fail "MDMESH_BIND and MDMESH_TRUSTED_PROXIES: $why"
  fi
}

# mdm_bind_loopback IP:PORT: the address is this machine's own loopback (127.x.x.x or [::1]): only programs on this
# machine reach it.
mdm_bind_loopback() { case "$1" in 127.*|\[::1\]:*) return 0 ;; esac; return 1; }

# mdm_proxy_trust_problem BIND LIST: prints why LIST can't be trusted with the edge on BIND; nothing when it can. `host`
# (this machine, as Docker delivers its connections: the stack network's gateway) is safe only on a loopback BIND: other
# machines reaching a non-loopback BIND can arrive from that same gateway (Docker Desktop, rootless Docker, a docker-proxy
# for IPv6), and would then be believed. docker/edge-peers.sh applies the same rule when the edge starts.
mdm_proxy_trust_problem() {
  case " $2 " in *" host "*) ;; *) return 0 ;; esac
  mdm_bind_loopback "$1" && return 0
  echo "host (a proxy on this machine) needs MDMesh on a loopback address, 127.0.0.1 or [::1]; with $1, list your proxy's own address instead"
}

# mdm_docker_keeps_sources: succeeds unless this Docker hides where connections come from: rootless Docker and Docker
# Desktop deliver every connection to a published port from their gateway.
mdm_docker_keeps_sources() {
  case "$(docker info --format '{{.OperatingSystem}} {{json .SecurityOptions}}' 2> /dev/null)" in
    *rootless*|*'Docker Desktop'*) return 1 ;;
  esac
}

# mdm_mode_questions: asks what the mode needs and was not answered in advance (the public hostname, then the mode's own
# questions), with their defaults, and sets BASE_URL and the mode's settings (mdm_mode_settings). Stops the installer on
# a wrong hostname. Behind your own proxy it checks the address MDMesh listens on is free, and that `host` fits it.
mdm_mode_questions() {
  local why
  if ! mdm_answered host; then
    case "$MODE" in
      1) read -rp "Public hostname devices will use (e.g. mdm.example.com): " HOST ;;   # answer: host
      2) read -rp "Your domain (DNS already pointing here, e.g. mdm.example.com): " HOST ;;   # answer: host
      3) read -rp "Public hostname your proxy serves (e.g. mdm.example.com): " HOST ;;   # answer: host
    esac
  fi
  mdm_check_host HOST || { err "Enter the hostname only, e.g. mdm.example.com, then re-run."; exit 1; }
  BASE_URL="https://${HOST}"
  mdm_check_base_url BASE_URL || { err "Check the hostname you entered (the name only, e.g. mdm.example.com), then re-run."; exit 1; }
  case "$MODE" in
    1) while :; do
         mdm_answered tunnel-token || read -rp "Cloudflare Tunnel token (Zero Trust → Tunnels → your tunnel): " TUNNEL_TOKEN   # answer: tunnel-token
         mdm_prompt_ok tunnel-token TUNNEL_TOKEN && break
       done ;;
    2) while :; do
         mdm_answered acme-email || read -rp "Email for Let's Encrypt: " ACME_EMAIL   # answer: acme-email
         mdm_prompt_ok acme-email ACME_EMAIL && break
       done ;;
    3) while :; do
         mdm_answered bind || read -rp "Address MDMesh listens on for your proxy [127.0.0.1:8080]: " PROXY_BIND   # answer: bind
         mdm_prompt_ok bind PROXY_BIND || continue
         PROXY_BIND=${PROXY_BIND:-$MDM_PROXY_BIND_DEFAULT}
         _mdm_bind_free "$PROXY_BIND" && break
         if mdm_given bind || [ "${MDMESH_NONINTERACTIVE:-0}" = 1 ]; then
           _mdm_answer_fail "MDMESH_BIND=$PROXY_BIND: something on this machine already listens there. Set MDMESH_BIND to a free address and port."
         fi
         warn "Something on this machine already listens on $PROXY_BIND: choose another address or port."
         PROXY_BIND=''
       done
       while :; do
         mdm_answered trusted-proxies || read -rp "Your proxy's IP address as MDMesh sees it (host = a proxy on this machine) [host]: " PROXY_TRUSTED   # answer: trusted-proxies
         mdm_prompt_ok trusted-proxies PROXY_TRUSTED || continue
         PROXY_TRUSTED=${PROXY_TRUSTED:-$MDM_PROXY_TRUSTED_DEFAULT}
         why=$(mdm_proxy_trust_problem "$PROXY_BIND" "$PROXY_TRUSTED")
         [ -n "$why" ] || break
         mdm_given trusted-proxies && _mdm_answer_fail "MDMESH_TRUSTED_PROXIES: $why."
         [ "${MDMESH_NONINTERACTIVE:-0}" != 1 ] || _mdm_answer_fail "MDMESH_TRUSTED_PROXIES (default host): $why."
         warn "That answer: $why."
         PROXY_TRUSTED=''
       done
       if ! mdm_bind_loopback "$PROXY_BIND" && ! mdm_docker_keeps_sources; then
         warn "This Docker (rootless, or Docker Desktop) delivers every connection to $PROXY_BIND from its own gateway, so"
         warn "MDMesh can't see your proxy's address: it then believes no one's X-Forwarded-For, and every client counts as one"
         warn "address for the sign-in limits. With a proxy on this machine, keep MDMesh on 127.0.0.1 ($MDM_DOCS/DEPLOY.md#3-behind-your-own-proxy)."
       fi ;;
  esac
  ORIGIN_TLS=${MDMESH_ORIGIN_TLS:-0}
  mdm_mode_settings "$MODE"
}
# _mdm_bind_free IP:PORT: nothing on this machine listens where the edge would.
_mdm_bind_free() {
  local ip=${1%:*}
  ip=${ip#\[}; ip=${ip%\]}
  ! mdm_listening "${1##*:}" "$ip"
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
  if mdm_compose_has_proxy "${COMPOSE_FILE:-}"; then echo 3; return; fi
  echo 2
}

# mdm_compose_has_proxy LIST: the COMPOSE_FILE list LIST names docker-compose.proxy.yml (as the installers write it, or
# ./docker-compose.proxy.yml or a path ending in it, as a hand edit might).
mdm_compose_has_proxy() {
  case ":$1:" in *:docker-compose.proxy.yml:*|*/docker-compose.proxy.yml:*) return 0 ;; esac
  return 1
}

# mdm_mode_settings NUMBER [rerun]: the mode's settings, from HOST (and, behind your proxy, PROXY_BIND and ORIGIN_TLS):
# COMPOSE_ARGS (an array: its compose files and profile, for a command line), EXTRA_NOTE (what to do next) and ENTRY (where
# requests come in); and, for a new .env, SITE_ADDRESS (the edge's site; behind your proxy the edge sets its own),
# COMPOSE_FILE and COMPOSE_PROFILES (compose and the updater read them from .env), with another mode's answers cleared
# so they never reach .env. With rerun (an existing .env, already loaded and exported) only the first three: its own
# values stay as they are.
mdm_mode_settings() {
  local f site file profiles='' at files
  case "$1" in
    1) site=':80'; file='docker-compose.yml'; profiles='cloudflare'; ENTRY="Cloudflare Tunnel"
       EXTRA_NOTE="In Cloudflare, route the tunnel's public hostname ($HOST) to http://caddy:80." ;;
    2) site=$HOST; file='docker-compose.yml:docker-compose.domain.yml'; ENTRY="ports 80/443"
       EXTRA_NOTE="Make sure ${HOST} resolves to this server and ports 80/443 are open." ;;
    3) site=':8080'; file='docker-compose.yml:docker-compose.proxy.yml'; ENTRY="${PROXY_BIND}, behind your proxy"
       # On every address: the one your proxy uses is one of this machine's.
       case "$PROXY_BIND" in 0.0.0.0:*|\[::\]:*) at="<this machine's address>:${PROXY_BIND##*:}" ;; *) at=$PROXY_BIND ;; esac
       if [ "${ORIGIN_TLS:-0}" = 1 ]; then
         EXTRA_NOTE="Point your proxy at https://${at}, trusting MDMesh's own CA, certificate name $(mdm_host_name "$HOST"): $MDM_DOCS/DEPLOY.md#3-behind-your-own-proxy"
       else
         EXTRA_NOTE="Point your proxy at http://${at}. Ready configurations: $MDM_DOCS/DEPLOY.md#3-behind-your-own-proxy"
       fi ;;
    *) _mdm_answer_fail "internal: unknown hosting mode $1" ;;
  esac
  COMPOSE_ARGS=()
  # A re-run uses the list its .env saved (an override of the operator's own included), else the mode's.
  [ "${2:-}" != rerun ] || [ -z "${COMPOSE_FILE:-}" ] || file=$COMPOSE_FILE
  # compose's own default file is left to COMPOSE_FILE (.env), as setup.sh always did in tunnel mode. Split on ":"
  # only: a path may hold blanks.
  if [ "$file" != docker-compose.yml ]; then
    IFS=: read -r -a files <<< "$file"
    for f in "${files[@]}"; do [ -z "$f" ] || COMPOSE_ARGS+=(-f "$f"); done
  fi
  [ -z "$profiles" ] || COMPOSE_ARGS+=(--profile "$profiles")
  [ "${2:-}" != rerun ] || return 0
  SITE_ADDRESS=$site; COMPOSE_FILE=$file; COMPOSE_PROFILES=$profiles
  [ "$1" = 1 ] || TUNNEL_TOKEN=''
  [ "$1" = 2 ] || ACME_EMAIL=''
}

# mdm_up_hint: what to check when `docker compose up` fails, by mode.
mdm_up_hint() {
  case "$MODE" in
    2) echo "If the edge could not publish ports 80/443, stop what holds them, or pick mode 3 behind that web server." ;;
    3) echo "If the edge could not publish MDMESH_BIND (${PROXY_BIND:-in .env}), set a free address in .env (e.g. MDMESH_BIND=127.0.0.1:8081)." ;;
  esac
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
  # Made private from the start (mkdir -m ignores the umask). One that already exists is used only when it is a directory
  # of this user's (or root's) that nobody else may write: its rules go into the edge, so it is never made private and
  # then trusted.
  if [ -e site.d ] || [ -L site.d ]; then
    if [ -L site.d ] || [ ! -d site.d ]; then
      warn "site.d is not connected to the edge: it is a link or not a directory. Make it a directory of your own"
      warn "(mkdir -m 755 site.d, after moving that one away), then run this again ($MDM_DOCS/DEPLOY.md#your-own-edge-rules)."
      return 0
    fi
    if [ -z "$(find site.d -prune \( -user "$EUID" -o -user 0 \) -print 2> /dev/null)" ]; then
      warn "site.d is not connected to the edge: it belongs to another user. Make it a directory of your own, then run"
      warn "this again ($MDM_DOCS/DEPLOY.md#your-own-edge-rules)."
      return 0
    fi
    if [ -n "$(find site.d -prune \( -perm -020 -o -perm -002 \) -print 2> /dev/null)" ]; then
      warn "site.d is not connected to the edge: other users may write it. If nobody else could have changed it, run"
      warn "chmod go-w site.d, then run this again ($MDM_DOCS/DEPLOY.md#your-own-edge-rules)."
      return 0
    fi
  elif ! mkdir -m 755 site.d; then
    warn "Could not make site.d here: your own edge rules can't be added."; return 0
  fi
  # A site.d this run can't write (root's, on a run as another user; or made read-only): connected (nobody else may
  # write it), its README left out.
  if [ ! -w site.d ]; then
    if [ -O site.d ]; then warn "site.d isn't writable (its mode): its README is left out."
    else warn "site.d belongs to root: add your own edge rules there as root."; fi
  elif [ ! -e site.d/README ]; then
    printf '%s\n' "Your own rules for MDMesh's edge (Caddy). Every *.caddy file here (but hidden ones, .*.caddy) is" \
      "imported into the edge's site," \
      "after MDMesh's own settings. Updates never change this directory. Each file must be yours (or root's), not a" \
      "link, and writable by you only (chmod 644), or the edge leaves it out, saying so in its log. Check it (the" \
      "check fails if a restart would leave a file out, or finds no *.caddy file: site.d not mounted), then restart" \
      "the edge (from the install directory):" \
      "  docker compose exec -e MDMESH_RAW_SITE_D=1 caddy caddy validate --config /etc/caddy/Caddyfile && docker compose restart caddy" \
      "Each file stands alone (no snippet definitions, no import of another file): it is pasted into MDMesh's site." \
      "Your files run after MDMesh's path checks and before its own routes, the first matching handle answering:" \
      "wrap a rule that answers requests (respond, redir) in handle { }; a bare one answers every request." \
      "How, with an example: $MDM_DOCS/DEPLOY.md#your-own-edge-rules" > site.d/README
    chmod 644 site.d/README
  fi
  d=$(pwd -P)
  case "$d" in
    *[!${_MDM_ALNUM}._/+@-]*) warn "site.d is not connected to the edge: its path ($d) has a character .env can't hold as it is ($MDM_DOCS/DEPLOY.md#your-own-edge-rules)." ;;
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
