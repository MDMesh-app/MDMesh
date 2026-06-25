#!/usr/bin/env sh
# Verifies a release manifest against the committed public key. Used locally + by the fleet updater.
set -eu
M="${1:?usage: verify-manifest.sh manifest.json}"
minisign -V -p "$(dirname "$0")/minisign.pub" -m "$M"
