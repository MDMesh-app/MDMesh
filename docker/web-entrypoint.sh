#!/bin/sh
# Root only long enough to make the cert/config volumes writable by the unprivileged caddy user.
set -e
chown -R caddy:caddy /data /config 2>/dev/null || true
# Which peers the :80 listener believes about a client's address, by hosting mode (see the file). Without it the
# Caddyfile's own defaults apply (loopback only), so a missing script never takes the edge down.
if [ -r /edge-peers.sh ]; then
  # shellcheck source=docker/edge-peers.sh  # copied to / by docker/web.Dockerfile
  . /edge-peers.sh
else
  echo "web-entrypoint: /edge-peers.sh is missing; the :80 listener believes loopback peers only" >&2
fi
exec su-exec caddy "$@"
