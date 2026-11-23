#!/bin/sh
# Points an Ubuntu test container's apt at MDMESH_TEST_UBUNTU_MIRROR (archive and security pockets alike), when it is
# set; otherwise, and on Debian, changes nothing. Run as root inside the container, before its first apt-get.
#
# Why: the stock ubuntu image fetches from archive.ubuntu.com and security.ubuntu.com. When those are slow (30 kB/s at
# times, against seconds from the runner's own mirror), every Ubuntu suite in CI outruns its timeout and looks hung. A
# GitHub-hosted runner has Azure's Ubuntu mirror next to it, as an Azure VM does; CI passes it in (t0-fast.yml,
# t2-native.yml). tests/native/Dockerfile carries the same rewrite inline (its build context is tests/native).
set -eu
m=${MDMESH_TEST_UBUNTU_MIRROR:-}
[ -n "$m" ] || exit 0
re='https?://(archive|security)\.ubuntu\.com/ubuntu/?'
for f in /etc/apt/sources.list.d/ubuntu.sources /etc/apt/sources.list; do
  [ -f "$f" ] && grep -Eq "$re" "$f" || continue
  sed -i -E "s#$re#${m%/}/#g" "$f"
  echo "apt: $f now uses $m"
done
