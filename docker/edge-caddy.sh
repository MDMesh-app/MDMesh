#!/bin/sh
# The edge's caddy (/usr/local/bin/caddy, ahead of /usr/bin/caddy on PATH): every caddy command in the web container
# (the server itself, and a reload, validate or adapt through `docker compose exec caddy caddy …`) first reads the
# settings docker/edge-peers.sh computed at the container's start (/run/mdmesh-edge.env, root's), so each one reads
# docker/Caddyfile as the running edge did: the same trusted peers, HTTPS origin and checked copy of site.d.
# MDMESH_RAW_SITE_D=1, for validate and adapt only: read site.d as mounted, to check a rule added since the start
# (DEPLOY.md, Your own edge rules). A reload or run always loads the checked copy.
if [ -r /run/mdmesh-edge.env ]; then
  set -a
  # shellcheck source=/dev/null  # written by docker/edge-peers.sh
  . /run/mdmesh-edge.env
  set +a
elif [ "${1:-}" = reload ]; then
  # Not saved this start (the edge's log says why): a reload would read the Caddyfile without them. The running edge
  # got them from its start's environment; a restart saves them again.
  echo "MDMesh edge: its settings were not saved at start (see docker compose logs caddy): restart it (docker compose restart caddy) instead of a reload." >&2
  exit 1
fi
if [ "${MDMESH_RAW_SITE_D:-}" = 1 ]; then
  case "${1:-}" in validate|adapt) EDGE_SITE_D=/etc/caddy/site.d; export EDGE_SITE_D ;; esac
fi
exec /usr/bin/caddy "$@"
