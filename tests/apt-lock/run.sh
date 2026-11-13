#!/usr/bin/env bash
# The installers' apt-get (install/lib/apt.sh) while another apt run holds apt's locks, as on a new VM whose first-boot
# apt-daily / unattended-upgrades runs hold them: in a throwaway root container of IMAGE that starts with only git (and
# empty package lists), with recommends off.
#
# Usage: tests/apt-lock/run.sh IMAGE [show]
#   IMAGE  a Debian or Ubuntu image, e.g. debian:12, ubuntu:24.04
#   show   also print each case's output
#
# Needs docker and network access (the container installs packages from the distribution's mirrors). The container is
# named mdmesh-apt-lock-tests-<random>, started with --rm, and mounts only this checkout, read-only. The cases
# (tests/apt-lock/cases.sh) refuse to run anywhere but in that container: they run ./setup.sh --update --native and
# the native installer's apt steps as root.
set -euo pipefail
unset CDPATH
REPO=$(cd -P -- "$(dirname -- "$0")/../.." && pwd)
IMAGE=${1:?usage: tests/apt-lock/run.sh IMAGE [show]}
case "${2:-}" in '') SHOW=0 ;; show) SHOW=1 ;; *) echo "unknown option: $2" >&2; exit 2 ;; esac
NAME="mdmesh-apt-lock-tests-$(od -An -N4 -tx1 /dev/urandom | tr -d ' \n')"
docker run --rm --name "$NAME" -v "$REPO:/src:ro" -e MDMESH_APT_LOCK_TESTS=1 -e MDMESH_APT_LOCK_SHOW="$SHOW" "$IMAGE" bash /src/tests/apt-lock/cases.sh
