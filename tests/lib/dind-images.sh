# shellcheck shell=bash
# Sourced by the docker:dind suites (tests/proxy-e2e, tests/rollback-e2e) once they have set ROOT, DIND and LOG.
#
# Their dind is offline and gets its images by `docker save | docker load`. A loaded image has no registry digest, and
# docker can't give it one, so an image line pinned as repo:tag@sha256:<digest> (the compose files' postgres and
# cloudflared) never resolves there. So the suites pull a pinned image here, where docker checks the digest, load it by
# its tag, and drop the digest from the compose files they copy in: the dind runs the very bytes the pin names for this
# platform. T0's edge job checks that each digest holds every release platform.

# pinned_image SERVICE: the image docker-compose.release.yml runs SERVICE from, as compose resolves it.
pinned_image() {
  DB_PASSWORD=x BASE_URL=x HASH_SECRET=x docker compose -f "$ROOT/docker-compose.release.yml" config --images "$1"
}

# load_images IMAGE...: each one here first (a clean host has pulled none of the public ones yet), a pinned one also
# by its tag, then all of them into the dind by tag.
load_images() {
  local img
  for img in "$@"; do
    docker image inspect "$img" >>"$LOG" 2>&1 || docker pull -q "$img" >>"$LOG" 2>&1 || { echo "could not pull $img"; return 1; }
    [ "$img" = "${img%@*}" ] || docker tag "$img" "${img%@*}" >>"$LOG" 2>&1 || return 1
  done
  docker save "${@%@*}" | docker exec -i "$DIND" docker load >>"$LOG" 2>&1
}

# unpin FILE: the compose file FILE in the dind runs its pinned images by their tags, as load_images loaded them.
unpin() { docker exec -i "$DIND" sed -i -E 's/^( *image: [^@ ]+)@sha256:[0-9a-f]{64}$/\1/' "$1"; }
