# shellcheck shell=bash
# The one rule for the versions an install's hosting mode allows. Sourced by apply.sh and rollback.sh (the updater) and
# by setup.sh (--update); quickstart.sh keeps a verbatim copy of the functions between the markers below, which CI
# checks (it runs without a clone).
# >>> mode-guard.sh functions (quickstart.sh keeps a verbatim copy) >>>
# mode_problem VERSION: prints why this install can't run VERSION (nothing when it can). A version that isn't X.Y.Z
# (latest) is let through: there is nothing to compare. Before 0.6.0 (the web image's edge doesn't know these):
#   - behind your own proxy (COMPOSE_FILE, in the supervisor's environment or .env, lists docker-compose.proxy.yml): the
#     older edge would believe no proxy (every client one address for the sign-in limits) and serve no HTTPS origin;
#   - TRUSTED_PROXIES (.env) still saying `host`, as an install moved out of the proxy mode may have left it: the
#     0.6.0 edge ignores it, an older one hands it to Caddy, which refuses its whole configuration.
mode_problem() {
  local v=${1%%@*} files proxies
  [[ "$v" =~ ^([0-9]+)\.([0-9]+)\.[0-9]+ ]] || return 0
  [ "$((10#${BASH_REMATCH[1]}))" -eq 0 ] && [ "$((10#${BASH_REMATCH[2]}))" -lt 6 ] || return 0
  files="${COMPOSE_FILE:-}:$(_mode_env COMPOSE_FILE)"
  # As written by the installers, or ./docker-compose.proxy.yml or a path ending in it, as a hand edit might.
  case ":$files:" in
    *:docker-compose.proxy.yml:*|*/docker-compose.proxy.yml:*)
      echo "this install runs behind your own proxy (docker-compose.proxy.yml), which came in 0.6.0, so it can't go to ${v}: that version's edge doesn't know the mode. Move the install back to its earlier hosting mode first (docs/UPGRADING.md, Changing the hosting mode), then try again. Nothing was changed."
      return 0 ;;
  esac
  proxies=" $(_mode_env TRUSTED_PROXIES | tr ',' ' ') "
  case "$proxies" in
    *" host "*)
      echo "TRUSTED_PROXIES in .env still says host (left from the proxy mode), which ${v}'s edge can't read: it would not start. Set TRUSTED_PROXIES= in .env (docs/UPGRADING.md, Changing the hosting mode), then try again. Nothing was changed." ;;
  esac
}
# _mode_env KEY: KEY's value in .env, read as compose reads the usual forms: the last KEY line (blanks and "export "
# before it, KEY=v, KEY = v or KEY: v); quoted, the text between the quotes; else up to a blank and "#", trailing blanks
# trimmed. Unlike compose: no escapes in quotes, no multi-line values, no ${VAR}, and a tab before "#" also starts a
# comment.
_mode_env() {
  local f=${ENV_FILE:-/project/.env} v
  [ -f "$f" ] && [ -r "$f" ] || return 0
  # (each match printed as "=value", so a last "KEY=" with no newline after it still counts)
  v=$(sed -n -E "s/^[[:space:]]*(export[[:space:]]+)?$1[[:space:]]*[=:][[:space:]]*/=/p" "$f" | tail -1 | tr -d '\r')
  v=${v#=}
  case "$v" in
    \"*) v=${v#\"}; v=${v%%\"*} ;;
    \'*) v=${v#\'}; v=${v%%\'*} ;;
    *) v=${v%%[[:space:]]#*}; v=${v%"${v##*[![:space:]]}"} ;;
  esac
  printf '%s\n' "$v"
}
# <<< mode-guard.sh functions <<<
