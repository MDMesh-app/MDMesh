#!/usr/bin/env sh
# Prints the registry digest (sha256:<hex>) a pushed image reference resolves to, the one `docker pull repo@digest`
# fetches; fails unless it is exactly that shape. With platforms (os/arch, e.g. linux/amd64 linux/arm64) it also fails
# unless the reference is an image index holding exactly one image for each of them; docker picks the host's from it.
# A platform given as os/arch=sha256:<hex> must be exactly that image; when every one is given so, the index must hold
# those images and nothing else (the release workflow passes the builds it tested).
# Used by the release workflow to pin the images in the manifest, and by T0 to check the compose files' third-party pins.
set -eu
REF="${1:?usage: image-digest.sh registry/repo:tag [os/arch[=sha256:<hex>]...]}"
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
entries = [("%s/%s" % ((x.get("platform") or {}).get("os"), (x.get("platform") or {}).get("architecture")), x.get("digest"))
           for x in m.get("manifests", [])]
got = [p for p, _ in entries]
has = ", ".join(got) or "no index, one image"
asked = [w.partition("=")[0] for w in want]
if len(set(asked)) != len(asked):
    args = " ".join(want)
    sys.exit(f"image-digest.sh: a platform is given more than once ({args})")
pinned = 0
for w in want:
    plat, _, child = w.partition("=")
    if child and not re.fullmatch(r"sha256:[0-9a-f]{64}", child):
        sys.exit(f"image-digest.sh: {w!r} is not os/arch=sha256:<64 hex>")
    if got.count(plat) != 1:
        sys.exit(f"image-digest.sh: {ref} holds {got.count(plat)} images for {plat}, not one (it has: {has})")
    if child:
        pinned += 1
        have = next(c for p, c in entries if p == plat)
        if have != child:
            sys.exit(f"image-digest.sh: {ref} holds {have} for {plat}, not the expected {child}")
if want and pinned == len(want) and len(entries) != len(want):
    sys.exit(f"image-digest.sh: {ref} holds {len(entries)} manifests, not only the {len(want)} expected (it has: {has})")
print(d)
' "$REF" "$@"
