#!/bin/sh
# Root only long enough to make the cert/config volumes writable by the unprivileged caddy user.
set -e
chown -R caddy:caddy /data /config 2>/dev/null || true
# Which peers the :80 listener believes about a client's address, by hosting mode (see the file).
# shellcheck source=docker/edge-peers.sh  # copied to / by docker/web.Dockerfile
. /edge-peers.sh
exec su-exec caddy "$@"
