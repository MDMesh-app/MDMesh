#!/usr/bin/env bash
#
# Fails when a published image's Dockerfile (docker/*.Dockerfile) starts from an image or installs a package
# (apk add, apt-get install) that docs/licenses/images.md doesn't name: the page says what the images redistribute and
# under which licences, and NOTICE's source offer covers the GPL software among it (docs/licenses/policy.md).
# Names, not versions: a tag or package bump doesn't need the page changed. Run from anywhere; needs no Docker.
#
# A cheap pull-request nudge, not the record: each release's per-image SBOM (release.yml) is what lists everything an
# image holds. Known blind spots: software that arrives another way (COPY --from=<an outside image>, pip install,
# npm install -g, curl | tar, a downloaded binary), and the packages an installed package pulls in.
#
# Usage: scripts/licenses/check-images.sh
set -euo pipefail
ROOT=$(cd -P -- "$(dirname -- "$0")/../.." && pwd)
PAGE=docs/licenses/images.md

# names FILE: "image NAME" for each FROM (the image without registry path, tag or digest; build-stage aliases skipped)
# and "package NAME" for each package installed, one per line.
names() {
  local stages=" "
  # Joins continuation lines, so a package list split over several lines is one command.
  sed -e ':a' -e '/\\$/N; s/\\\n/ /; ta' "$1" | while read -r line; do
    case $line in
      FROM\ *|from\ *)
        # FROM [--platform=...] IMAGE [AS NAME]
        local ref="" alias="" word words
        read -ra words <<< "$line"
        for word in "${words[@]:1}"; do
          case $word in --*|AS|as) ;; *) if [ -z "$ref" ]; then ref=$word; else alias=$word; fi ;; esac
        done
        ref=${ref%%@*}; ref=${ref%%:*}; ref=${ref##*/}
        case $stages in *" $ref "*) ;; *) echo "image $ref" ;; esac
        [ -z "$alias" ] || stages="$stages$alias "
        ;;
    esac
    # Every "apk add ..." or "apt-get install ..." up to the end of its command (&&, ;, |).
    { grep -oE '(apk add|apt-get( -[^ ]+)* install)[^&;|]*' <<< "$line" || true; } | while read -r cmd; do
      local skip=""
      for word in ${cmd#*apk add}; do
        # --virtual / -t NAME names a group of packages, not a package.
        if [ -n "$skip" ]; then skip=""; continue; fi
        case $word in --virtual|-t) skip=1; continue ;; -*|install|apt-get) continue ;; esac
        echo "package ${word%%=*}"
      done
    done
  done
}

rc=0
for file in "$ROOT"/docker/*.Dockerfile; do
  rel=${file#"$ROOT"/}
  while read -r kind name; do
    if ! grep -qF "\`$name\`" "$ROOT/$PAGE"; then
      echo "::error file=$rel::$rel uses the $kind $name, which $PAGE doesn't name: add it, with its licence"
      rc=1
    fi
  done < <(names "$file" | sort -u)
done
[ "$rc" -ne 0 ] || echo "Every image and package in docker/*.Dockerfile is in $PAGE"
exit "$rc"
