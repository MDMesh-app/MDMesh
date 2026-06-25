#!/usr/bin/env sh
# CI signs the manifest with the release minisign key. Reads the secret key from $MINISIGN_SECRET_KEY
# (the .key file contents) and its password from $MINISIGN_PASSWORD. Emits <manifest>.minisig.
set -eu
M="${1:?usage: sign-manifest.sh manifest.json}"
KEYFILE=$(mktemp); printf '%s\n' "$MINISIGN_SECRET_KEY" > "$KEYFILE"
printf '%s\n' "${MINISIGN_PASSWORD:-}" | minisign -S -s "$KEYFILE" -m "$M"
rm -f "$KEYFILE"
