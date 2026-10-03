#!/usr/bin/env bash
# T2 native rig: install and upgrade MDMesh NATIVELY (DEPLOY.md Option C, no Docker for MDMesh itself) inside a
# throwaway systemd container that looks like a fresh VPS, with the documented commands, then check the result.
#
# Usage: tests/native/run.sh <distro> <scenario>
#   distro:   debian-12 | debian-13 | ubuntu-24.04
#   scenario: fresh    the code under test (T2_TO_REF) on a clean host
#             upgrade  T2_FROM_REF (the last release) installed, then T2_TO_REF over it (`git pull` + the same command)
#             killed   the same upgrade, SIGKILLed at each kill point (tests/native/lib/guest.sh), then re-run: it must
#                      converge and pass every check
#
# Environment (all optional):
#   T2_FROM_REF   the release to upgrade from (default v0.3.1). Name it explicitly: this clone also carries upstream
#                 Headwind tags (v5.x), so "the newest tag" is not the last MDMesh release. CI passes the latest GitHub
#                 release.
#   T2_TO_REF     the code under test (default HEAD)
#   T2_KILL_POINTS  comma-separated kill points for `killed` (default: all; `bash tests/native/lib/guest.sh kill-points`)
#   T2_OUT        where logs and results go (default ${TMPDIR:-/tmp}/mdmesh-t2); each run writes <T2_OUT>/<container name>/
#   T2_CACHE=1    share Maven/npm download caches between runs (named Docker volumes mdmesh-t2-cache-m2/-npm)
#   T2_STRICT=1   build the image without the workarounds for undocumented prerequisites (see docs/DEV.md)
#   T2_HTTP_PORT  the port given to the installer (default 9090); T2_BASE_URL its public URL (default http://mdm.t2.test:<port>)
#   T2_ORIGIN_URL the clone's origin (default the real repo: the installer reads releases and the agent APK from it)
#   T2_MEMORY     the container's memory limit (default 6g); T2_MIN_FREE_GB free space required to start (default 8)
#
# Needs: docker (cgroup v2 host), git, curl, python3, md5sum. Every container and image it creates is named
# mdmesh-t2-<distro>-<scenario>-<random>[...] and removed on exit, whatever happens; nothing else is touched, and no
# host path is mounted into a container.
set -euo pipefail
unset CDPATH
HERE=$(cd -P -- "$(dirname -- "$0")" && pwd)
REPO=$(cd -P -- "$HERE/../.." && pwd)

usage() { sed -n '/^# Usage:/,/^# Needs:/p' "$0" | sed 's/^# \{0,1\}//' >&2; exit 2; }
DISTRO=${1:-}; SCENARIO=${2:-}
if [ -z "$DISTRO" ] || [ -z "$SCENARIO" ]; then usage; fi
case "$SCENARIO" in fresh|upgrade|killed) ;; *) usage ;; esac

T2_FROM_REF=${T2_FROM_REF:-v0.3.1}
T2_TO_REF=${T2_TO_REF:-HEAD}
T2_ORIGIN_URL=${T2_ORIGIN_URL:-https://github.com/MDMesh-app/MDMesh.git}
RAND=$(python3 -c 'import secrets; print(secrets.token_hex(3))')
NAME="mdmesh-t2-$DISTRO-$SCENARIO-$RAND"
IMAGE="mdmesh-t2-$DISTRO:$SCENARIO-$RAND"
SNAPSHOT="mdmesh-t2-$DISTRO:$SCENARIO-$RAND-snapshot"
OUT="${T2_OUT:-${TMPDIR:-/tmp}/mdmesh-t2}/$NAME"
mkdir -p "$OUT"
: > "$OUT/results.tsv"
WORK=$(mktemp -d)
CONTAINERS=(); IMAGES=(); CTR=""; COLLECTED=""
ADMIN_PW=$(python3 -c 'import secrets; print(secrets.token_hex(16))')   # what the "admin" sets at first login
LOG_TAG="$DISTRO/$SCENARIO"

# shellcheck source=tests/native/lib/host.sh
. "$HERE/lib/host.sh"

cleanup() {
  local rc=$? c i
  trap - EXIT INT TERM
  if [ "$rc" -ne 0 ] && [ -n "$CTR" ] && [ "$COLLECTED" != "$CTR" ] && docker inspect "$CTR" > /dev/null 2>&1; then
    collect "failure-$CTR" || true
  fi
  for c in "${CONTAINERS[@]}"; do remove_container "$c"; done
  for i in "${IMAGES[@]}"; do docker rmi "$i" > /dev/null 2>&1 || true; done   # by exact tag only
  rm -rf "$WORK"
  log "cleaned up: removed ${#CONTAINERS[@]} container(s) and ${#IMAGES[@]} image tag(s) of this run; results in $OUT"
  exit "$rc"
}
trap cleanup EXIT
trap 'exit 130' INT TERM

command -v docker > /dev/null || die "docker is required"
for c in git curl python3 md5sum; do command -v "$c" > /dev/null || die "$c is required"; done
TO_SHA=$(git -C "$REPO" rev-parse --verify "$T2_TO_REF^{commit}") || die "T2_TO_REF=$T2_TO_REF is not a commit here"
FROM_SHA=""
if [ "$SCENARIO" != fresh ]; then
  FROM_SHA=$(git -C "$REPO" rev-parse --verify "$T2_FROM_REF^{commit}") \
    || die "T2_FROM_REF=$T2_FROM_REF is not a commit here (fetch the release tags: git fetch --tags origin)"
fi
T0=$(date +%s)
minutes() { echo "$(( ($(date +%s) - $1 + 30) / 60 ))"; }
log "run $NAME: $SCENARIO on $DISTRO; to=$T2_TO_REF (${TO_SHA:0:12})${FROM_SHA:+ from=$T2_FROM_REF (${FROM_SHA:0:12})}; logs in $OUT"

distro_setup "$DISTRO"
IMAGES+=("$IMAGE")
build_image
make_bundle "$FROM_SHA" "$TO_SHA"

# install_and_sign_in LABEL REF: the documented install of REF on the current container, the admin's first sign-in,
# then the check step. Fatal when the install itself fails (nothing after it would mean anything).
install_and_sign_in() {
  local label=$1 ref=$2 temp
  run_install "$label" \
    || die "the $label install failed: $(grep -m1 '✗' "$OUT/$label.out" | sed 's/^ *//') (see $OUT/$label.out)"
  temp=$(guest admin-temp-password "/t2/out/$label.out")
  [ -n "$temp" ] || die "the $label install printed no temporary admin password (see $OUT/$label.out)"
  first_login "$temp" || die "the admin's first sign-in failed"
  check_install "$label" "$ref"
}

case "$SCENARIO" in
  fresh)
    start_container "$NAME" "$IMAGE"
    prepare_checkout "$TO_SHA"
    install_and_sign_in fresh "$TO_SHA"
    verdict=$([ "$CHECK_FAILS" -eq 0 ] && echo PASS || echo FAIL)
    result - "$verdict" "e2e ${E2E_RESULT}; $(minutes "$T0") min"
    ;;

  upgrade)
    start_container "$NAME" "$IMAGE"
    prepare_checkout "$FROM_SHA"
    install_and_sign_in "install-$T2_FROM_REF" "$FROM_SHA"
    before_e2e=$E2E_RESULT
    [ "$CHECK_FAILS" -eq 0 ] || die "the $T2_FROM_REF install failed its checks; not upgrading it"
    continuity_before
    guest checkout "$TO_SHA" | while IFS= read -r l; do log "$l"; done
    rc=0; run_install upgrade || rc=$?
    check "the upgrade's installer exits 0" "$rc" 0
    continuity_after upgrade
    check_install upgrade "$TO_SHA"
    verdict=$([ "$CHECK_FAILS" -eq 0 ] && echo PASS || echo FAIL)
    result - "$verdict" "before: e2e $before_e2e; after: e2e ${E2E_RESULT}; $(minutes "$T0") min"
    ;;

  killed)
    # One install of the release, snapshotted with its services stopped (as at a clean shutdown); each kill point then
    # starts from that snapshot: the host boots back into the release, and the upgrade is killed and re-run there.
    start_container "$NAME" "$IMAGE"
    prepare_checkout "$FROM_SHA"
    install_and_sign_in "install-$T2_FROM_REF" "$FROM_SHA"
    [ "$CHECK_FAILS" -eq 0 ] || die "the $T2_FROM_REF install failed its checks; not upgrading it"
    continuity_before
    docker exec "$CTR" systemctl stop mdmesh-supervisor mdmesh-server postgresql
    IMAGES+=("$SNAPSHOT")
    docker commit "$CTR" "$SNAPSHOT" > /dev/null
    remove_container "$CTR"
    log "snapshot $SNAPSHOT taken ($T2_FROM_REF installed, device enrolled)"

    IFS=',' read -r -a points <<< "${T2_KILL_POINTS:-$(bash "$HERE/lib/guest.sh" kill-points | paste -sd, -)}"
    failed=0
    for point in "${points[@]}"; do
      LOG_TAG="$DISTRO/killed/$point"; t0=$(date +%s); fails0=$CHECK_FAILS; verdict=""; note=""
      start_container "$NAME-$point" "$SNAPSHOT"
      wait_api 300 || true
      check "the snapshot boots back into $T2_FROM_REF" "$(http_code /rest/public/name)" 200
      guest checkout "$TO_SHA" | while IFS= read -r l; do log "$l"; done
      rc=0; run_install "kill-$point" "$point" || rc=$?
      if [ "$rc" -eq 3 ]; then
        verdict="NOT-REACHED"; note="$(tail -n 1 "$OUT/kill-$point.result")"
      elif [ "$rc" -ne 0 ]; then
        verdict="RIG-ERROR"; note="kill step rc=$rc"
      else
        guest state > "$OUT/kill-$point.state" 2>&1 || true
        note="killed at: $(sed -n 's/.*last installer line: *//p' "$OUT/kill-$point.result" | head -n 1)"
        rc=0; run_install "rerun-$point" || rc=$?
        if [ "$rc" -ne 0 ]; then
          note="$note; re-run FAILED (rc=$rc): $(grep -v '^[[:space:]]*$' "$OUT/rerun-$point.out" | grep -m1 '✗' | sed 's/^ *//')"
          collect "rerun-failed-$point"; COLLECTED=$CTR
          rc=0; run_install "rerun2-$point" || rc=$?
          note="$note; a second re-run $([ "$rc" -eq 0 ] && echo succeeded || echo "failed too (rc=$rc)")"
          verdict="NOT-CONVERGED"
          CHECK_FAILS=$((CHECK_FAILS + 1))
        fi
        continuity_after "rerun-$point"
        check_install "rerun-$point" "$TO_SHA"
        if [ -z "$verdict" ]; then
          verdict=$([ "$CHECK_FAILS" -eq "$fails0" ] && echo CONVERGED || echo NOT-CONVERGED)
        fi
        [ "$CHECK_FAILS" -eq "$fails0" ] || note="$note; $((CHECK_FAILS - fails0)) check(s) failed after the re-run"
      fi
      [ "$verdict" = CONVERGED ] || failed=$((failed + 1))
      if [ "$verdict" != CONVERGED ] && [ "$COLLECTED" != "$CTR" ]; then collect "$point-$CTR"; fi
      COLLECTED=$CTR
      result "$point" "$verdict" "$note; $(minutes "$t0") min"
      log "kill point $point: $verdict ($(minutes "$t0") min)"
      remove_container "$CTR"; CTR=""
    done
    LOG_TAG="$DISTRO/$SCENARIO"
    ;;
esac

log "results ($(minutes "$T0") min):"
{ column -t -s $'\t' "$OUT/results.tsv" 2>/dev/null || cat "$OUT/results.tsv"; } | while IFS= read -r l; do log "  $l"; done
if [ "$SCENARIO" = killed ]; then
  [ "$failed" -eq 0 ] || die "$failed kill point(s) did not converge"
else
  [ "$CHECK_FAILS" -eq 0 ] || die "$CHECK_FAILS check(s) failed"
fi
log "OK"
