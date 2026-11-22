#!/bin/sh
# The edge's caddy (/usr/local/bin/caddy, ahead of /usr/bin/caddy on PATH): every caddy command in the web container
# (the server itself, and a reload, validate or adapt through `docker compose exec caddy caddy …`) first reads the
# settings docker/edge-peers.sh computed at the container's start (/run/mdmesh-edge.env, root's), so each one reads
# docker/Caddyfile as the running edge did: the same trusted peers, HTTPS origin and checked copy of site.d.
# MDMESH_RAW_SITE_D=1, for validate and adapt only: check site.d as it is now, to try a rule added since the start
# (DEPLOY.md, Your own edge rules): its files go through the start's own check (edge-peers.sh's edge_site_d) into a
# scratch copy, which is what is validated, so the check reads exactly what a restart would load, and fails if the
# restart would leave a file out. A reload or run always loads the start's checked copy.
if [ -r /run/mdmesh-edge.env ]; then
  set -a
  # shellcheck disable=SC1091  # written by docker/edge-peers.sh at start
  . /run/mdmesh-edge.env
  set +a
elif [ "${1:-}" = reload ]; then
  # Not saved this start (the edge's log says why): a reload would read the Caddyfile without them. The running edge
  # got them from its start's environment; a restart saves them again.
  echo "MDMesh edge: its settings were not saved at start (see docker compose logs caddy): restart it (docker compose restart caddy) instead of a reload." >&2
  exit 1
fi

# validate and adapt provision what the Caddyfile names (the internal CA under /data, for the HTTPS origin): run as root
# (docker compose exec's default) they would leave root-owned keys the running edge (caddy) can't read. Run as caddy.
as_caddy=''
su_exec=$(command -v su-exec || true)
if [ "$(id -u)" = 0 ] && [ -n "$su_exec" ] && grep -q '^caddy:' /etc/passwd; then
  case "${1:-}" in validate|adapt) as_caddy=1 ;; esac
fi

case "${MDMESH_RAW_SITE_D:-}:${1:-}" in
  1:validate|1:adapt)
    # Root reads the mount as the start does (site.d may be 700, its files 600); caddy reads the copy. A check that
    # would leave any file out fails, after caddy's own result: `validate && restart` never restarts without a rule
    # you expect (the start itself loads the rest, as always).
    scratch=$(mktemp -d /tmp/mdmesh-site-check.XXXXXX) || exit 1
    trap 'rm -rf "$scratch"' EXIT
    trap 'exit 130' INT
    trap 'exit 143' TERM
    chmod 755 "$scratch"
    # shellcheck source=docker/edge-peers.sh
    EDGE_PEERS_FUNCTIONS_ONLY=1 . /edge-peers.sh
    edge_site_d "${EDGE_SITE_SRC:-/etc/caddy/site.d}" "$scratch/site.d" || exit 1
    EDGE_SITE_D=$scratch/site.d; export EDGE_SITE_D
    rc=0
    if [ -n "$as_caddy" ]; then "$su_exec" caddy /usr/bin/caddy "$@" || rc=$?; else /usr/bin/caddy "$@" || rc=$?; fi
    if [ "${EDGE_SITE_SKIPPED:-0}" != 0 ]; then
      echo "MDMesh edge: a restart would leave out what is listed above: fix it first (DEPLOY.md, Your own edge rules)." >&2
      [ "$rc" != 0 ] || rc=1
    elif [ -z "$(ls -A -- "$scratch/site.d")" ]; then
      # Nothing to check is not a rule checked: most likely site.d isn't mounted (a quick start from before 0.6.0
      # keeps its old compose file; an install whose site.d the installer left unconnected mounts an empty volume).
      echo "MDMesh edge: site.d has no *.caddy file here: is it mounted? See MDMESH_SITE_DIR in .env and the installer's message about site.d (DEPLOY.md, Your own edge rules; a quick start set up before 0.6.0 needs the mount added by hand)." >&2
      [ "$rc" != 0 ] || rc=1
    fi
    exit "$rc" ;;
esac
if [ -n "$as_caddy" ]; then exec "$su_exec" caddy /usr/bin/caddy "$@"; fi
exec /usr/bin/caddy "$@"
