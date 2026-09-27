#!/usr/bin/env bash
# Run Docker commands against the project-local rootless WSL daemon without using
# Rancher Desktop's Docker client configuration or credential helpers.
set -euo pipefail

runtime_dir="${XDG_RUNTIME_DIR:-/run/user/$(id -u)}"
socket="${runtime_dir}/docker.sock"
config_dir="${MDMESH_DOCKER_CONFIG:-${XDG_STATE_HOME:-${HOME}/.local/state}/mdmesh-docker}"
safe_path="/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"

if [[ ! -S "$socket" ]]; then
    echo "Rootless Docker socket is unavailable: $socket" >&2
    echo "Start it with: systemctl --user start docker.service" >&2
    exit 1
fi

# Keep Docker client configuration separate from Rancher Desktop. The sanitized PATH
# also prevents Docker from auto-discovering Rancher's unusable secretservice helper.
mkdir -p -m 700 "$config_dir"
exec env \
    "PATH=$safe_path" \
    "DOCKER_CONFIG=$config_dir" \
    "DOCKER_HOST=unix://$socket" \
    /usr/bin/docker "$@"
