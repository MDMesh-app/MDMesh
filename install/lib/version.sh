#!/usr/bin/env bash
# Shared "which version is running" rule for the from-source installers (setup.sh and
# install/install-native.sh). Source this file; do not execute it.
#
# A source install runs whatever the checkout holds, so its version is the checkout's latest
# reachable release tag, without the leading "v" (v0.3.1 -> 0.3.1). The supervisor compares it to
# GitHub's latest release to decide "update available", so a stale or placeholder value (0.0.0)
# shows a false banner. Prints nothing when it cannot tell (no git, no .git dir, no tags), and
# nothing for a tag with characters that do not belong in a .env value.
mdm_repo_version() {
  local v
  v=$(git -C "${1:-.}" describe --tags --abbrev=0 2>/dev/null | sed 's/^v//' || true)
  case "$v" in
    ''|*[!0-9A-Za-z.+_-]*) return 0 ;;
  esac
  printf '%s\n' "$v"
}
