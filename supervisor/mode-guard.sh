# shellcheck shell=bash
# Sourced by apply.sh and rollback.sh: the one rule for the versions an install's hosting mode allows.
#
# mode_problem VERSION: prints why this install can't run VERSION (nothing when it can). Behind your own proxy (its
# COMPOSE_FILE, in the supervisor's environment or .env, lists docker-compose.proxy.yml) the edge must be 0.6.0 or
# later: an older web image doesn't know the mode, so it would believe no proxy (every client one address for the
# sign-in limits) and serve no HTTPS origin (MDMESH_ORIGIN_TLS=1). A version that isn't X.Y.Z (latest) is let through:
# there is nothing to compare.
mode_problem() {
  local v=${1%%@*} files
  files="${COMPOSE_FILE:-}:$(grep -E '^COMPOSE_FILE=' "${ENV_FILE:-/project/.env}" 2>/dev/null | head -1 | cut -d= -f2- | tr -d "\"'\r")"
  case ":$files:" in *:docker-compose.proxy.yml:*) ;; *) return 0 ;; esac
  [[ "$v" =~ ^([0-9]+)\.([0-9]+)\.[0-9]+ ]] || return 0
  if [ "$((10#${BASH_REMATCH[1]}))" -eq 0 ] && [ "$((10#${BASH_REMATCH[2]}))" -lt 6 ]; then
    echo "this install runs behind your own proxy (docker-compose.proxy.yml), which came in 0.6.0, so it can't go to ${v}: that version's edge doesn't know the mode. Move the install back to its earlier hosting mode first (docs/UPGRADING.md, Changing the hosting mode), then try again. Nothing was changed."
  fi
}
