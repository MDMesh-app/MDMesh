#!/bin/sh
# Root only long enough to make the cert/config volumes writable by the unprivileged caddy user.
set -e
chown -R caddy:caddy /data /config 2>/dev/null || true
exec su-exec caddy "$@"
