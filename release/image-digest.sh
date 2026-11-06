#!/usr/bin/env sh
# Prints the registry digest (sha256:<hex>) a pushed image reference resolves to, the one `docker pull repo@digest`
# fetches; fails unless it is exactly that shape. Used by the release workflow to pin the images in the manifest.
set -eu
REF="${1:?usage: image-digest.sh registry/repo:tag}"
D=$(docker buildx imagetools inspect --format '{{.Manifest.Digest}}' "$REF")
case "$D" in
  sha256:*) ;;
  *) echo "image-digest.sh: no sha256 digest for $REF (got '$D')" >&2; exit 1 ;;
esac
HEX=${D#sha256:}
if [ "${#HEX}" -ne 64 ] || [ -n "$(printf '%s' "$HEX" | tr -d '0123456789abcdef')" ]; then
  echo "image-digest.sh: malformed digest for $REF: '$D'" >&2; exit 1
fi
printf '%s\n' "$D"
