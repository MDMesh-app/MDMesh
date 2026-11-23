#!/usr/bin/env bash
# Runs tests/installer-answers/run.sh and tests/setup-update/run.sh inside a throwaway container of IMAGE, as an
# unprivileged user, with this checkout mounted read-only: the distribution's own tools (mawk on Debian 12 and Ubuntu,
# its sed, grep, coreutils), not the host's.
#
# Usage: tests/installer-answers/in-container.sh IMAGE [busybox-awk]
#   IMAGE        a Debian or Ubuntu image, e.g. debian:12, debian:13, ubuntu:24.04
#   busybox-awk  also put busybox's awk first on PATH
#
# Needs docker. The container is named mdmesh-installer-tests-<random>, started with --rm, and removed when it ends; it
# mounts nothing but this checkout, read-only. curl is not installed in it (the tests stub it).
set -euo pipefail
unset CDPATH
REPO=$(cd -P -- "$(dirname -- "$0")/../.." && pwd)
IMAGE=${1:?usage: in-container.sh IMAGE [busybox-awk]}
SHIM=${2:-}
case "$SHIM" in ''|busybox-awk) ;; *) echo "unknown option: $SHIM" >&2; exit 2 ;; esac
NAME="mdmesh-installer-tests-$(od -An -N4 -tx1 /dev/urandom | tr -d ' \n')"
docker run --rm --name "$NAME" -v "$REPO:/src:ro" -e SHIM="$SHIM" "$IMAGE" bash -euc '
  export DEBIAN_FRONTEND=noninteractive
  apt-get update -qq >/dev/null
  apt-get install -y -qq --no-install-recommends jq git minisign openssl python3 ca-certificates ${SHIM:+busybox} >/dev/null
  shim=""
  if [ -n "$SHIM" ]; then mkdir -p /usr/local/awkshim; ln -s "$(command -v busybox)" /usr/local/awkshim/awk; shim=/usr/local/awkshim:; fi
  if [ -n "$SHIM" ]; then awk_is="busybox awk ($(busybox 2>&1 | head -n 1 || true))"; else awk_is=$(awk -W version 2>&1 | head -n 1 || true); fi
  echo "== $(. /etc/os-release && echo "$PRETTY_NAME"), awk: $awk_is"
  ! command -v curl >/dev/null || { echo "curl is installed in the container; the tests expect it absent" >&2; exit 1; }
  runuser -u nobody -- env HOME=/tmp PATH="$shim$PATH" bash -c "cd /tmp && bash /src/tests/installer-answers/run.sh && bash /src/tests/setup-update/run.sh"
'
