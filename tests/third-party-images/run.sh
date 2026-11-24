#!/usr/bin/env bash
# scripts/third-party-images.sh against fixture compose files, offline:
#   - lint passes on this checkout, and refuses a tag-only or floating ref, compose files that run different images,
#     a postgres of another major, and a postgres without (or with another) Debian release in its tag;
#   - verify (docker buildx stubbed with each case's inspect output) accepts an index holding one image per platform
#     whose postgres images are 14.<minor> on trixie, and refuses an index missing a platform, a single-platform image,
#     an index under another digest, a postgres image of another major, minor or Debian release, and says so plainly
#     when Docker Hub rate-limits the inspect.
# Needs bash, python3 and docker with compose (no daemon, no network). Run from anywhere: tests/third-party-images/run.sh
set -uo pipefail
ROOT=$(cd -P -- "$(dirname -- "$0")/../.." && pwd)
S="$ROOT/scripts/third-party-images.sh"
T=$(mktemp -d)
trap 'find "$T" -mindepth 1 -delete; rmdir "$T"' EXIT
PASSED=0 FAILED=0
ok()   { echo "  ok   $*"; PASSED=$((PASSED + 1)); }
bad()  { echo "  FAIL $*"; FAILED=$((FAILED + 1)); }

PG_D=sha256:$(printf '1%.0s' $(seq 64)) CF_D=sha256:$(printf '2%.0s' $(seq 64))
PG="postgres:14.25-trixie@$PG_D" CF="cloudflare/cloudflared:2026.11.2@$CF_D"

# compose DIR PG_REF CF_REF [PG_REF_IN_THE_CLONE_FILE]: the two compose files, cut down to the image lines.
compose() {
  mkdir -p "$1"
  for f in docker-compose.release.yml docker-compose.yml; do
    local pg=$2
    [ "$f" = docker-compose.yml ] && [ -n "${4:-}" ] && pg=$4
    cat > "$1/$f" <<EOF
services:
  postgres:
    image: $pg
  server:
    image: ghcr.io/\${IMAGE_OWNER:-mdmesh-app}/mdmesh-server:\${SERVER_VERSION:-latest}
  cloudflared:
    image: $3
    profiles: ["cloudflare"]
EOF
  done
}
lint() { MDM_COMPOSE_DIR="$1" "$S" lint > "$T/out" 2>&1; }

echo "== lint"
if "$S" lint > "$T/out" 2>&1; then ok "this checkout's compose files"; else bad "this checkout's compose files: $(cat "$T/out")"; fi
compose "$T/good" "$PG" "$CF"
if lint "$T/good"; then ok "pinned refs"; else bad "pinned refs: $(cat "$T/out")"; fi
if [ "$(MDM_COMPOSE_DIR="$T/good" "$S" list)" = "$(printf '%s\n%s' "$CF" "$PG")" ]; then ok "list: both refs, MDMesh's own left out"
else bad "list printed: $(MDM_COMPOSE_DIR="$T/good" "$S" list 2>&1)"; fi
# refuse NAME PG CF [PG_IN_CLONE] PATTERN: lint fails, saying PATTERN.
refuse() {
  local name=$1 pattern=${*: -1}
  compose "$T/$name" "${@:2:$#-2}"
  if lint "$T/$name"; then bad "$name: accepted"
  elif grep -q -- "$pattern" "$T/out"; then ok "$name: refused"
  else bad "$name: refused, but without '$pattern': $(cat "$T/out")"; fi
}
refuse tag-only postgres:14.25-trixie "$CF" 'not pinned'
refuse floating "$PG" cloudflare/cloudflared:latest 'not pinned'
refuse digest-only "postgres@$PG_D" "$CF" 'not pinned'
refuse files-differ "$PG" "$CF" "postgres:14.24-trixie@$PG_D" 'docker-compose.yml runs'
refuse major "postgres:16.11-trixie@$PG_D" "$CF" 'postgres:14.<minor>-trixie'
refuse no-suite "postgres:14.25@$PG_D" "$CF" 'postgres:14.<minor>-trixie'
refuse other-suite "postgres:14.25-bookworm@$PG_D" "$CF" 'postgres:14.<minor>-trixie'

echo "== verify (docker stubbed)"
mkdir -p "$T/bin"
REAL_DOCKER=$(command -v docker)
# The stub answers `buildx imagetools inspect --format '{{json .Manifest}}'|'{{json .Image}}' REF` from
# $T/case/<manifest|image>-<repo>.json (or fails as $T/case/fail says), and hands everything else to docker.
cat > "$T/bin/docker" <<EOF
#!/usr/bin/env bash
[ "\$1 \$2 \$3" = "buildx imagetools inspect" ] || exec "$REAL_DOCKER" "\$@"
case "\$5" in *Manifest*) kind=manifest ;; *) kind=image ;; esac
repo=\${6%%[:@]*}; repo=\${repo##*/}
if [ -s "$T/case/fail" ]; then cat "$T/case/fail" >&2; exit 1; fi
cat "$T/case/\$kind-\$repo.json"
EOF
chmod +x "$T/bin/docker"
plat() { printf '{"digest":"sha256:%064d","platform":{"os":"linux","architecture":"%s"%s}}' 0 "$1" "${2:+,\"variant\":\"$2\"}"; }
att='{"digest":"sha256:'"$(printf %064d 9)"'","platform":{"os":"unknown","architecture":"unknown"}}'
# setup CASE: the inspect output for a good pair, which each case then spoils.
setup() {
  mkdir -p "$T/case"; find "$T/case" -mindepth 1 -delete
  printf '{"digest":"%s","manifests":[%s,%s,%s,%s]}' "$PG_D" "$(plat amd64)" "$att" "$(plat arm64 v8)" "$(plat arm v7)" > "$T/case/manifest-postgres.json"
  printf '{"digest":"%s","manifests":[%s,%s]}' "$CF_D" "$(plat amd64)" "$(plat arm64)" > "$T/case/manifest-cloudflared.json"
  pg_image "PG_MAJOR=14" "PG_VERSION=14.25-1.pgdg13+1" "PG_MAJOR=14" "PG_VERSION=14.25-1.pgdg13+1"
}
pg_image() {  # pg_image AMD64_MAJOR AMD64_VERSION ARM64_MAJOR ARM64_VERSION: each platform's config env
  printf '{"linux/amd64":{"config":{"Env":["LANG=en_US.utf8","%s","%s"]}},"linux/arm64":{"config":{"Env":["%s","%s"]}},"linux/arm/v7":{"config":{"Env":["PG_MAJOR=13"]}}}' \
    "$@" > "$T/case/image-postgres.json"
}
verify() { PATH="$T/bin:$PATH" MDM_COMPOSE_DIR="$T/good" "$S" verify "$@" > "$T/out" 2>&1; }
setup
if verify; then ok "good indexes, postgres 14.25 on trixie (release.yml's platforms)"; else bad "good indexes: $(cat "$T/out")"; fi
if grep -q 'linux/arm64' "$T/out"; then ok "verify names the platforms it checked"; else bad "no platforms in: $(cat "$T/out")"; fi
# spoiled NAME PATTERN: verify fails, saying PATTERN (each case set up after setup and its spoiling).
spoiled() {
  if verify "${@:3}"; then bad "$1: accepted"
  elif grep -q -- "$2" "$T/out"; then ok "$1: refused"
  else bad "$1: refused, but without '$2': $(cat "$T/out")"; fi
}
setup; printf '{"digest":"%s","manifests":[%s]}' "$CF_D" "$(plat amd64)" > "$T/case/manifest-cloudflared.json"
spoiled "an index without arm64" 'linux/arm64'
setup; printf '{"digest":"%s","mediaType":"application/vnd.oci.image.manifest.v1+json"}' "$CF_D" > "$T/case/manifest-cloudflared.json"
spoiled "one image, not an index" 'not one'
setup; spoiled "a platform the index lacks, asked for" 'linux/riscv64' linux/amd64 linux/riscv64
setup; sed -i "s/$PG_D/sha256:$(printf '3%.0s' $(seq 64))/" "$T/case/manifest-postgres.json"
spoiled "the tag's index is under another digest" 'resolves to'
setup; pg_image "PG_MAJOR=14" "PG_VERSION=14.25-1.pgdg13+1" "PG_MAJOR=16" "PG_VERSION=16.11-1.pgdg13+1"
spoiled "an arm64 image of postgres 16" 'linux/arm64.*PG_MAJOR=16'
setup; pg_image "PG_MAJOR=14" "PG_VERSION=14.25-1.pgdg12+1" "PG_MAJOR=14" "PG_VERSION=14.25-1.pgdg12+1"
spoiled "images on bookworm" 'trixie'
setup; pg_image "PG_MAJOR=14" "PG_VERSION=14.24-1.pgdg13+2" "PG_MAJOR=14" "PG_VERSION=14.24-1.pgdg13+2"
spoiled "14.24 behind a 14.25 tag" '14.25'
setup; printf 'ERROR: unexpected status from HEAD request to https://registry-1.docker.io/v2/library/postgres/manifests/14: 429 Too Many Requests\n' > "$T/case/fail"
spoiled "rate-limited" 'rate-limited'

echo; echo "$PASSED passed, $FAILED failed"
[ "$FAILED" -eq 0 ]
