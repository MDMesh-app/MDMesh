#!/usr/bin/env bash
# The third-party images the Docker stack runs (postgres, cloudflared). Both compose files pin each one as
# repo:tag@sha256:<index digest>: docker pulls the digest, the tag is for people and Dependabot. The release compose
# file is in the signed manifest's files, so the pins are signed with it.
#
# Usage: scripts/third-party-images.sh list                 the refs, one per line (every profile; MDMesh's own
#                                                           images left out)
#        scripts/third-party-images.sh lint                 offline: every ref is pinned so, docker-compose.yml runs
#                                                           the same ones as docker-compose.release.yml, and postgres
#                                                           is 14.<minor>-trixie (see PG_MAJOR below)
#        scripts/third-party-images.sh verify [os/arch...]  online, nothing pulled: each digest is the tag's
#                                                           multi-platform index, holding one image for each platform
#                                                           (default: release.yml's PLATFORMS, the ones a release
#                                                           publishes), and every postgres image in it is the major,
#                                                           minor and Debian release its tag says
# Bumping a pin by hand: CONTRIBUTING.md. Needs docker (compose, buildx) and python3. MDM_COMPOSE_DIR (the tests)
# reads the compose files from another directory.
set -euo pipefail
ROOT=$(cd -P -- "$(dirname -- "$0")/.." && pwd)
DIR=${MDM_COMPOSE_DIR:-$ROOT}

# PostgreSQL's major and Debian release belong to the data, so neither is a routine bump:
#  - another major can't open the data directory (dump and restore, or pg_upgrade);
#  - another Debian release brings another glibc, whose sort order can differ, and PostgreSQL 14 doesn't notice: text
#    indexes (users_login_unique among them) go silently wrong until a REINDEX. postgres:14 has been trixie since
#    2025-08-11, before MDMesh's first release, so trixie is what installs run.
# Change them only in a planned database migration, with its UPGRADING steps. PG_DEBIAN is PG_SUITE's Debian version,
# as the images' PG_VERSION names it (14.25-1.pgdg13+1).
PG_MAJOR=14 PG_SUITE=trixie PG_DEBIAN=13

ENV_FILE=$(mktemp)
trap 'rm -f -- "$ENV_FILE"' EXIT
# refs FILE: the third-party refs FILE runs, sorted. An empty env file, so a clone's own .env never counts.
refs() {
  DB_PASSWORD=x BASE_URL=x HASH_SECRET=x docker compose --env-file "$ENV_FILE" -f "$DIR/$1" --profile '*' config --images \
    | grep -Ev '^ghcr\.io/[^/]+/mdmesh-(server|web|supervisor)[:@]' | LC_ALL=C sort
}

lint() {
  local rel clone ref rc=0
  rel=$(refs docker-compose.release.yml) && clone=$(refs docker-compose.yml) || return 1
  [ -n "$rel" ] || { echo "no third-party images in docker-compose.release.yml" >&2; return 1; }
  for ref in $rel; do
    if ! [[ $ref =~ ^[a-z0-9./-]+:[A-Za-z0-9._-]+@sha256:[0-9a-f]{64}$ ]]; then
      echo "$ref is not pinned as repo:tag@sha256:<index digest>" >&2; rc=1
    elif [[ ${ref%%:*} =~ ^(docker\.io/)?(library/)?postgres$ ]] && ! [[ $ref =~ :$PG_MAJOR\.[0-9]+-$PG_SUITE@ ]]; then
      echo "$ref: postgres must be postgres:$PG_MAJOR.<minor>-$PG_SUITE (another major or Debian release is a database migration: see PG_MAJOR in $0)" >&2
      rc=1
    fi
  done
  if [ "$rel" != "$clone" ]; then
    printf 'docker-compose.yml runs\n%s\nnot the same third-party images as docker-compose.release.yml:\n%s\n' "$clone" "$rel" >&2
    rc=1
  fi
  [ "$rc" = 0 ] && echo "third-party images pinned: ${rel//$'\n'/ }"
  return "$rc"
}

# rate_limited REF OUTPUT: whether a registry error is Docker Hub's rate limit (not a bad pin), said plainly.
rate_limited() {
  grep -qiE 'toomanyrequests|429 Too Many Requests' <<< "$2" && echo "$1: the registry rate-limited the inspect; run it again later" >&2
}

verify() {
  local plats=("$@") ref out rc=0
  if [ ${#plats[@]} -eq 0 ]; then
    IFS=, read -ra plats <<< "$(sed -n 's/^ *PLATFORMS: *\([^ #]*\).*/\1/p' "$ROOT/.github/workflows/release.yml")"
    [ ${#plats[@]} -gt 0 ] || { echo "no PLATFORMS in .github/workflows/release.yml" >&2; return 1; }
  fi
  for ref in $(refs docker-compose.release.yml); do
    # An index holding one image for each platform: docker picks the host's from it. A single-platform digest would
    # have an Arm64 host pull an amd64 image (or the reverse) and fail with an exec format error.
    if ! out=$("$ROOT/release/image-digest.sh" "$ref" "${plats[@]}" 2>&1); then
      rate_limited "$ref" "$out" || echo "$ref: $out" >&2
      rc=1; continue
    fi
    [ "$out" = "${ref##*@}" ] || { echo "$ref resolves to $out, not its pinned digest" >&2; rc=1; continue; }
    if [[ $ref =~ ^(docker\.io/)?(library/)?postgres: ]]; then
      # The tag is only a label: docker runs the digest. Each platform's image must say the major, the minor and the
      # Debian release the tag does (PG_VERSION 14.25-1.pgdg13+1 is 14.25 built for Debian 13).
      out=$(docker buildx imagetools inspect --format '{{json .Image}}' "$ref" 2>&1) || { rate_limited "$ref" "$out" || echo "$ref: $out" >&2; rc=1; continue; }
      python3 -c '
import json, re, sys
ref, major, debian, suite, plats = sys.argv[1], sys.argv[2], sys.argv[3], sys.argv[4], sys.argv[5:]
minor = re.search(r":(\d+\.\d+)-", ref).group(1)
images, bad = json.loads(sys.stdin.read()), []
for p in plats:
    env = dict(e.partition("=")[::2] for e in ((images.get(p) or {}).get("config") or {}).get("Env") or [])
    got = "PG_MAJOR=%s PG_VERSION=%s" % (env.get("PG_MAJOR"), env.get("PG_VERSION"))
    if env.get("PG_MAJOR") != major or not re.match(re.escape(minor) + r"-[^.]+\.pgdg" + debian + r"\+", env.get("PG_VERSION") or ""):
        bad.append("%s has %s, not %s on %s (Debian %s)" % (p, got, minor, suite, debian))
sys.exit("%s: %s" % (ref, "; ".join(bad)) if bad else 0)
' "$ref" "$PG_MAJOR" "$PG_DEBIAN" "$PG_SUITE" "${plats[@]}" <<< "$out" || { rc=1; continue; }
    fi
    echo "ok $ref (${plats[*]})"
  done
  return "$rc"
}

case "${1:-}" in
  list) refs docker-compose.release.yml ;;
  lint) lint ;;
  verify) shift; verify "$@" ;;
  *) sed -n '/^# Usage:/,/^# Bumping/p' "$0" | sed 's/^# \{0,1\}//' >&2; exit 2 ;;
esac
