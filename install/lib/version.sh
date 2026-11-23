#!/usr/bin/env bash
# Shared "which version is running" rule for the from-source installers (setup.sh and
# install/install-native.sh). Source this file; do not execute it.
#
# A source install runs whatever the checkout holds, so its version is the checkout's latest
# reachable release tag, without the leading "v" (v0.3.1 -> 0.3.1). The supervisor compares it to
# GitHub's latest release to decide "update available", so a stale or placeholder value (0.0.0)
# shows a false banner, and setup.sh tags the images it builds with it. Only release-shaped tags count:
# X.Y.Z with an optional -prerelease (0.4.0-rc.1), never +build metadata (Docker image tags cannot hold
# a '+'). Other tags (wip, agent-v1) are skipped in favour of the release tag behind them. Prints
# nothing when it cannot tell (no git, no .git dir, no release tag, or the nearest one is not
# release-shaped, such as v0.3.1+build.5); callers treat that as an untagged checkout.
mdm_repo_version() {
  local v
  v=$(git -C "${1:-.}" describe --tags --abbrev=0 --match 'v[0-9]*' --match '[0-9]*' 2>/dev/null | sed 's/^v//' || true)
  [[ "$v" =~ ^[0-9]+\.[0-9]+\.[0-9]+(-[0-9A-Za-z.-]+)?$ ]] || return 0
  printf '%s\n' "$v"
}

# The major.minor.patch core of a version as "X Y Z" (leading "v" and any suffix such as -rc1 ignored); nothing when it
# has no such core ("latest", "", "0.3").
mdm_version_core() {
  printf '%s\n' "$1" | sed -nE 's/^v?([0-9]+)\.([0-9]+)\.([0-9]+).*$/\1 \2 \3/p'
}

# mdm_version_gt A B: succeeds when A is newer than B, compared on major.minor.patch as numbers (pre-release suffixes
# ignored; the supervisor's semverGt also orders those, and no pre-release is published). Equal, older, or either side
# without a core → fails.
mdm_version_gt() {
  local a b i
  read -r -a a <<< "$(mdm_version_core "$1")"
  read -r -a b <<< "$(mdm_version_core "$2")"
  [ "${#a[@]}" -eq 3 ] && [ "${#b[@]}" -eq 3 ] || return 1
  for i in 0 1 2; do
    if [ "$((10#${a[i]}))" -ne "$((10#${b[i]}))" ]; then [ "$((10#${a[i]}))" -gt "$((10#${b[i]}))" ]; return; fi
  done
  return 1
}

# The native installer's lines about the release the host runs (A: the install's own record, mdm_installed_version in
# install/lib/envfile.sh) and the one this clone holds (B: mdm_repo_version). Either may be empty: not known.
# mdm_native_title UPDATING A B: the title (UPDATING=1: `./setup.sh --update` handed over, over an install).
mdm_native_title() {
  if [ "$1" = 1 ]; then printf 'MDMesh · native update, %s → %s\n' "${2:-?}" "${3:-?}"
  else printf 'MDMesh · native install\n'; fi
}
# mdm_native_host_line A B: under the install title over a host with a recorded release, so a re-run that is really an
# update (or a downgrade) says so before it asks anything; nothing when the host has none.
mdm_native_host_line() {
  [ -n "$1" ] || return 0
  if [ -n "$2" ]; then printf 'This host runs v%s; this clone is on v%s.\n' "$1" "$2"
  else printf 'This host runs v%s; this clone is on no release tag.\n' "$1"; fi
}
# mdm_native_declined A B: what the installer says when its question is answered no. Nothing on the host has changed, but
# the clone may have moved ahead of the install (an older setup.sh's --update checks out the new tag before it hands
# over, and the installer then asks): only then does it point to --update, which goes to the newest release, not
# necessarily the clone's.
mdm_native_declined() {
  if [ -n "$1" ] && [ -n "$2" ] && mdm_version_gt "$2" "$1"; then
    printf 'Aborted. The install is unchanged: this host still runs v%s. This clone is on v%s, newer than the install.\n' "$1" "$2"
    printf 'To update this host, run: sudo ./setup.sh --update --native\n'
  else
    printf 'Aborted — nothing on this host was changed.\n'
  fi
}
