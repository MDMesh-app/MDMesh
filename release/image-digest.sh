#!/usr/bin/env sh
# Prints the registry digest (sha256:<hex>) a pushed image reference resolves to, the one `docker pull repo@digest`
# fetches; fails unless it is exactly that shape. With platforms (os/arch, e.g. linux/amd64 linux/arm64) it also fails
# unless the reference is an image index holding exactly one image for each of them; docker picks the host's from it.
# Used by the release workflow to pin the images in the manifest.
set -eu
REF="${1:?usage: image-digest.sh registry/repo:tag [os/arch...]}"
shift
# One inspect gives both the digest and the platforms, so they describe the same manifest.
docker buildx imagetools inspect --format '{{json .Manifest}}' "$REF" | python3 -c '
import json, re, sys
ref, want = sys.argv[1], sys.argv[2:]
try:
    m = json.load(sys.stdin)
except ValueError:
    sys.exit(f"image-digest.sh: could not inspect {ref}")
d = m.get("digest", "")
if not re.fullmatch(r"sha256:[0-9a-f]{64}", d):
    sys.exit(f"image-digest.sh: no well-formed sha256 digest for {ref} (got {d!r})")
plats = [x.get("platform") or {} for x in m.get("manifests", [])]
got = ["%s/%s" % (p.get("os"), p.get("architecture")) for p in plats]
for w in want:
    if got.count(w) != 1:
        has = ", ".join(got) or "no index, one image"
        sys.exit(f"image-digest.sh: {ref} holds {got.count(w)} images for {w}, not one (it has: {has})")
print(d)
' "$REF" "$@"
