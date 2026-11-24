# shellcheck shell=bash
# Sourced by the docker:dind suites (tests/proxy-e2e, tests/rollback-e2e); needs their ROOT and LOG.
# docker-compose*.yml run postgres and cloudflared as repo:tag@sha256:<index digest>. The suites' dind is offline and
# gets its images by `docker save | docker load`, and a save from the runner's (classic) image store keeps no registry
# digest: compose in the dind could never match such a ref. So the suites run the very image the pin names, under its
# tag: pinned_tags pulls it here and tags it, and unpin_sed (applied to a compose file copied into the dind) drops the
# digest from its image lines. scripts/third-party-images.sh verify covers the digests themselves (T0, release gate).

# pinned_tags NAME...: for each named service's pinned ref (postgres, cloudflared), pull it (unless here) and tag it
# repo:tag; prints the tags, for the suite's save list.
pinned_tags() {
  local n ref
  for n in "$@"; do
    ref=$("$ROOT/scripts/third-party-images.sh" list | grep -E "^([^/]+/)?$n:") || { echo "no pinned $n image" >&2; return 1; }
    docker image inspect "$ref" >>"$LOG" 2>&1 || docker pull -q "$ref" >>"$LOG" 2>&1 || { echo "could not pull $ref" >&2; return 1; }
    docker tag "$ref" "${ref%@*}" >>"$LOG" 2>&1 || return 1
    printf '%s\n' "${ref%@*}"
  done
}

# The sed -E expression that drops a pinned digest from a compose file's image lines (only third-party ones have a
# literal one; MDMesh's own come from .env).
# shellcheck disable=SC2034  # used by the suites that source this
UNPIN_SED='s#^( *image: [^$@ ]+)@sha256:[0-9a-f]{64} *$#\1#'
