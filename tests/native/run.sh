#!/usr/bin/env bash
# T2 native rig: install and upgrade MDMesh NATIVELY (DEPLOY.md Option C, no Docker for MDMesh itself) inside a
# throwaway systemd container that looks like a fresh VPS, with the documented commands, then check the result.
#
# Usage: tests/native/run.sh <distro> <scenario>
#   distro:   debian-12 | debian-13 | ubuntu-24.04
#   scenario: fresh    the code under test (T2_TO_REF) on a clean host
#             upgrade  T2_FROM_REF (the last release) installed with GITHUB_REPO (and a dummy GITHUB_TOKEN put in its
#                      supervisor.env, which GitHub refuses), then T2_TO_REF over it (`git pull` + the same command,
#                      without HTTP_PORT, BASE_URL, GITHUB_REPO or GITHUB_TOKEN:
#                      the upgrade must keep the port, the base URL and the updater settings the install has)
#             killed   the same upgrade, SIGKILLed at each kill point (tests/native/lib/guest.sh), then re-run: it must
#                      converge, keep the same settings and pass every check (the re-run also leaves HTTP_PORT and
#                      BASE_URL out)
#             update   the code under test installed, then `sudo ./setup.sh --update --native` over it as a user runs
#                      it, without -y (stdin closed: a question stops it): it must ask nothing, say it is an update and
#                      keep the port, the base URL, the data and the updater settings. The release it finds is the code
#                      under test as v99.0.0, from a local origin, signed with a throwaway key (guest.sh update-release)
#             update-resumed  the same update after one that stopped halfway: the clone already on v99.0.0 (as an older
#                      setup.sh's --update leaves it) and "no" answered to the installer's question, which must leave
#                      the install as it was and say so. The update then names the release the install runs, not the
#                      clone's ("A → 99.0.0", never "99.0.0 → 99.0.0"). Its v99.0.0 carries the rig's release key
#                      (a clone on it verifies the release with its own key), so its agent APK is not verified
#
# Environment (all optional):
#   T2_FROM_REF   the release to upgrade from (default v0.3.1). CI passes the latest GitHub release.
#   T2_TO_REF     the code under test (default HEAD)
#   T2_KILL_POINTS  comma-separated kill points for `killed` (default: all; `bash tests/native/lib/guest.sh kill-points`)
#   T2_OUT        where logs and results go (default ${TMPDIR:-/tmp}/mdmesh-t2); each run writes <T2_OUT>/<container name>/
#   T2_CACHE=1    share Maven/npm download caches between runs (named Docker volumes mdmesh-t2-cache-m2/-npm)
#   T2_M2_REPO    a dedicated host directory holding a Maven repository (the rig rewrites it: never a real ~/.m2):
#                 copied (not mounted) into the first container as root's ~/.m2/repository before any install, and
#                 replaced by the guest's repository after a converged install, which also writes <dir>.t2-saved
#                 (CI keeps the directory with actions/cache, so a warm guest rarely needs Maven Central).
#                 Best-effort both ways (tests/native/lib/host.sh)
#   T2_M2_SAVE=0  with T2_M2_REPO: seed, but never save (CI: the directory came from an exact cache hit)
#   T2_STRICT=1   build an upgrade's image without the workarounds its from-ref installer needs
#                 (see tests/native/Dockerfile, layer 3)
#   T2_HTTP_PORT  the port given to the installer (default 9090); T2_BASE_URL its public URL (default http://mdm.t2.test:<port>)
#   T2_ORIGIN_URL the clone's origin (default the real repo: the installer reads releases and the agent APK from it)
#   T2_MEMORY     the container's memory limit (default 6g); T2_MIN_FREE_GB free space required to start (default 8)
#   T2_STEP_TIMEOUT  seconds one installer run may take (default 3600)
#   MDMESH_TEST_UBUNTU_MIRROR  an Ubuntu archive mirror the ubuntu-24.04 guest's apt uses instead of archive.ubuntu.com
#                 and security.ubuntu.com (default none; CI sets Azure's, http://azure.archive.ubuntu.com/ubuntu/)
#
# Needs: docker (cgroup v2 host), git, curl, python3, md5sum. Every container and image it creates is named
# mdmesh-t2-<distro>-<scenario>-<random>[...] and removed on exit, whatever happens; nothing else is touched (but
# T2_M2_REPO, when set), and no host path is mounted into a container.
set -euo pipefail
unset CDPATH
HERE=$(cd -P -- "$(dirname -- "$0")" && pwd)
REPO=$(cd -P -- "$HERE/../.." && pwd)

usage() { sed -n '/^# Usage:/,/^# Needs:/p' "$0" | sed 's/^# \{0,1\}//' >&2; exit 2; }
DISTRO=${1:-}; SCENARIO=${2:-}
if [ -z "$DISTRO" ] || [ -z "$SCENARIO" ]; then usage; fi
case "$SCENARIO" in fresh|upgrade|killed|update|update-resumed) ;; *) usage ;; esac

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
if [ "$SCENARIO" = upgrade ] || [ "$SCENARIO" = killed ]; then
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

# Updater settings an upgrade (and a killed upgrade's re-run) must keep. The repo is the origin's in other letter case
# (GitHub ignores case, so release fetches still work, and it differs from the origin the installer would follow). The
# token is a dummy in GitHub's characters, put into supervisor.env after the install (guest.sh set-token says why):
# GitHub refuses it (401), and the installer must say so and fetch the release without it.
origin_repo=$(printf '%s' "$T2_ORIGIN_URL" | sed -E 's#(git@|https?://)[^/:]+[/:]##; s#\.git$##')
SET_REPO=${origin_repo,,}; [ "$SET_REPO" != "$origin_repo" ] || SET_REPO=${origin_repo^^}
SET_TOKEN="t2_dummy_token_$RAND"
SET_PROXY="192.0.2.10,192.0.2.11"   # proxy.addresses, added to ROOT.xml by hand as docs/OPERATIONS.md says

# install_with_settings LABEL REF: install_and_sign_in with GITHUB_REPO=SET_REPO, then SET_TOKEN in its supervisor.env;
# the settings are recorded in $OUT/settings.before.
install_with_settings() {
  INSTALL_GITHUB_REPO=$SET_REPO install_and_sign_in "$1" "$2"
  guest set-token "$SET_TOKEN"
  guest set-proxy "$SET_PROXY"
  guest settings > "$OUT/settings.before"
  log "  settings: $(tr '\n' ' ' < "$OUT/settings.before")"
  check "the $1 install wrote GITHUB_REPO" "$(sed -n 's/^GITHUB_REPO=//p' "$OUT/settings.before")" "$SET_REPO"
  check "the $1 install wrote base.url" "$(sed -n 's/^base.url=//p' "$OUT/settings.before")" "$T2_BASE_URL"
  check "proxy.addresses set in ROOT.xml" "$(sed -n 's/^proxy.addresses=//p' "$OUT/settings.before")" "$SET_PROXY"
  check "GITHUB_TOKEN set in supervisor.env" "$(sed -n 's/^GITHUB_TOKEN=//p' "$OUT/settings.before")" \
    "$(printf '%s' "$SET_TOKEN" | sha256sum | cut -c1-16)"
}

# settings_kept LABEL: after the run LABEL, base.url, proxy.addresses, GITHUB_REPO and GITHUB_TOKEN are what they were
# before, the repo is marked as a setting, supervisor.env is root-only in /etc/mdmesh, and that run fetched the release
# APK (without the refused token, which it reported when it had read it in time). GitHub's anonymous rate limit is not
# the change's doing: when the run says it hit it, the fetch check is skipped with a WARN instead of failing.
settings_kept() {
  local label=$1 k
  guest settings > "$OUT/$label.settings"
  for k in base.url proxy.addresses GITHUB_REPO GITHUB_TOKEN; do
    check "$k kept by $label" "$(sed -n "s/^$k=//p" "$OUT/$label.settings")" "$(sed -n "s/^$k=//p" "$OUT/settings.before")"
  done
  check "GITHUB_REPO_FROM=setting after $label" "$(sed -n 's/^GITHUB_REPO_FROM=//p' "$OUT/$label.settings")" setting
  check "supervisor.env in /etc/mdmesh, root-only, after $label" "$(sed -n 's/^supervisor.env=//p' "$OUT/$label.settings")" "root:root 600"
  check "installed-version in /etc/mdmesh, root-only, after $label" "$(sed -n 's/^installed-version=//p' "$OUT/$label.settings")" "root:root 600"
  if [ "${rel_key:-}" = rig-key ] && [ "$label" = update ]; then
    log "  SKIP $label fetched and verified the release APK: the release carries the rig's key, not the one GitHub's APK is signed with"
  elif grep -q 'release agent APK fetched' "$OUT/$label.out"; then
    check "$label fetched and verified the release APK" yes yes
  elif grep -q 'rate limit' "$OUT/$label.out"; then
    log "  WARN SKIP $label fetched and verified the release APK: GitHub's rate limit stopped the lookup ($(grep -m1 'rate limit' "$OUT/$label.out" | sed 's/^ *//'))"
  else
    check "$label fetched and verified the release APK" "no: $(grep -m1 -E 'No published release|Could not|not trusted|refused the release' "$OUT/$label.out" | sed 's/^ *//')" yes
  fi
  if grep -q 'GitHub refused GITHUB_TOKEN' "$OUT/$label.out"; then log "  INFO $label reported the refused token"; fi
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
    install_with_settings "install-$T2_FROM_REF" "$FROM_SHA"
    before_e2e=$E2E_RESULT
    [ "$CHECK_FAILS" -eq 0 ] || die "the $T2_FROM_REF install failed its checks; not upgrading it"
    continuity_before
    guest checkout "$TO_SHA" | while IFS= read -r l; do log "$l"; done
    # Without HTTP_PORT, BASE_URL, GITHUB_REPO and GITHUB_TOKEN: the install is on T2_HTTP_PORT (not 8080), and the
    # upgrade must keep it, the base URL in ROOT.xml and the updater settings (-y never prompts).
    rc=0; OMIT_PORT=1 OMIT_BASE_URL=1 run_install upgrade || rc=$?
    check "the upgrade's installer exits 0" "$rc" 0
    settings_kept upgrade
    continuity_after upgrade
    check_install upgrade "$TO_SHA"
    verdict=$([ "$CHECK_FAILS" -eq 0 ] && echo PASS || echo FAIL)
    result - "$verdict" "before: e2e $before_e2e; after: e2e ${E2E_RESULT}; $(minutes "$T0") min"
    ;;

  update|update-resumed)
    # The clone ends one commit past the code under test (the rig's release key), on its release; --update moves it to
    # v99.0.0, a separate commit holding the code under test, as a squash merge of it would be. (update-resumed: an
    # earlier update moved it there already and stopped at the installer's question.)
    start_container "$NAME" "$IMAGE"
    prepare_checkout "$TO_SHA"
    install_with_settings install "$TO_SHA"
    [ "$CHECK_FAILS" -eq 0 ] || die "the install failed its checks; not updating it"
    continuity_before
    # update-resumed: the clone is put ON the release, so the release must carry the key it is signed with (the rig's).
    rel_key=''; [ "$SCENARIO" != update-resumed ] || rel_key=rig-key
    guest update-release "$TO_SHA" 99.0.0 ${rel_key:+"$rel_key"} > "$OUT/update-release.out" 2>&1 \
      || die "could not make the v99.0.0 release: $(tail -n 1 "$OUT/update-release.out")"
    while IFS= read -r l; do log "$l"; done < "$OUT/update-release.out"
    rel_sha=$(sed -n 's/^release //p' "$OUT/update-release.out")
    # The release the install runs: what the installer records (installed-version), the code under test's release.
    from=$(git -C "$REPO" describe --tags --abbrev=0 --match 'v[0-9]*' "$TO_SHA" 2>/dev/null | sed 's/^v//' || true)
    check "the install records the release it runs" "$(docker exec "$CTR" cat /etc/mdmesh/installed-version)" "$from"
    if [ "$SCENARIO" = update-resumed ]; then
      [ -n "$from" ] || die "update-resumed needs the code under test on a release tag (fetch the tags: git fetch --tags origin)"
      guest move-clone 99.0.0 | while IFS= read -r l; do log "$l"; done
      rc=0; guest decline /t2/out/decline.out > "$OUT/decline.result" 2>&1 || rc=$?
      docker cp "$CTR:/t2/out/decline.out" "$OUT/decline.out" > /dev/null 2>&1 || true
      check "answering no stops the install" "$rc" 1
      check "answering no says the install is unchanged and the clone moved" \
        "$(grep -cF "The install is unchanged: this host still runs v${from}. This clone is on v99.0.0, newer than the install." "$OUT/decline.out" || true)" 1
      check "answering no leaves the record" "$(docker exec "$CTR" cat /etc/mdmesh/installed-version)" "$from"
    fi
    rc=0; run_update update || rc=$?
    check "the update exits 0" "$rc" 0
    check "the update asks nothing" \
      "$(grep -cE 'Type "yes"|Public base URL|HTTP port \[|Choice \[|Type ERASE' "$OUT/update.out" || true)" 0
    check "the update says it is one, from ${from:-?} to 99.0.0" \
      "$(grep -cF "MDMesh · native update, ${from:-?} → 99.0.0" "$OUT/update.out" || true)" 1
    check "the update ends as one" "$(grep -cF '✓ MDMesh updated (native)' "$OUT/update.out" || true)" 1
    check "the update records 99.0.0" "$(docker exec "$CTR" cat /etc/mdmesh/installed-version)" 99.0.0
    check "the clone is on v99.0.0" "$(docker exec -u t2user "$CTR" git -C /home/t2user/MDMesh rev-parse HEAD)" "$rel_sha"
    # (With the rig's key, the release differs from the code under test in release/minisign.pub alone.)
    check "v99.0.0 holds the code under test" \
      "$(docker exec -u t2user "$CTR" git -C /home/t2user/MDMesh diff --name-only "$TO_SHA" HEAD | paste -sd,)" \
      "${rel_key:+release/minisign.pub}"
    settings_kept update
    continuity_after update
    check_install update "$TO_SHA"
    verdict=$([ "$CHECK_FAILS" -eq 0 ] && echo PASS || echo FAIL)
    result - "$verdict" "e2e ${E2E_RESULT}; $(minutes "$T0") min"
    ;;

  killed)
    # One install of the release, snapshotted with its services stopped (as at a clean shutdown); each kill point then
    # starts from that snapshot: the host boots back into the release, and the upgrade is killed and re-run there.
    start_container "$NAME" "$IMAGE"
    prepare_checkout "$FROM_SHA"
    install_with_settings "install-$T2_FROM_REF" "$FROM_SHA"
    [ "$CHECK_FAILS" -eq 0 ] || die "the $T2_FROM_REF install failed its checks; not upgrading it"
    continuity_before
    # The release the install reports: what the upgrade's installer reads as the one it updates from, read with the
    # installer's own reader (install/lib/envfile.sh) from a copy of /etc/mdmesh/supervisor.env (a release has no
    # installed-version record yet). Empty before v0.3.2, which kept that file elsewhere. A killed upgrade must keep it.
    mkdir -p "$WORK/from-etc"
    docker cp "$CTR:/etc/mdmesh/supervisor.env" "$WORK/from-etc/supervisor.env" 2>> "$WORK/rig-stderr.log" || true
    # shellcheck disable=SC2016  # the inner shell's positionals
    from_ver=$(bash -c '. "$1"; mdm_installed_version "$2"' _ "$REPO/install/lib/envfile.sh" "$WORK/from-etc")
    to_ver=$(git -C "$REPO" describe --tags --abbrev=0 --match 'v[0-9]*' "$TO_SHA" 2>> "$WORK/rig-stderr.log" | sed 's/^v//') || to_ver=''
    if [ -n "$from_ver" ] && [ "$from_ver" = "$to_ver" ]; then
      log "  note: $T2_FROM_REF and the code under test are both $from_ver: the host-line check can't tell the releases apart"
    fi
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
        # The installer records the release it started from right after its confirmation, before its first change and
        # so before every kill point (the first, db-role, is well after it); the final write comes after the last. So
        # at every point the record must be there and name it, even after the run rewrote supervisor.env. Without the
        # early write it is absent here at every point, and this fails. That covers the early write only when
        # T2_FROM_REF is a release without the record (v0.5.1 or older; CI pins one killed job to v0.5.1): from a
        # release that writes it, the record is already there before the run starts.
        rec=$(docker exec "$CTR" sh -c 'if [ -e /etc/mdmesh/installed-version ]; then cat /etc/mdmesh/installed-version; else echo absent; fi') || rec=error
        check "the killed upgrade still records the release it started from" "$rec" "$from_ver"
        rc=0; OMIT_PORT=1 OMIT_BASE_URL=1 run_install "rerun-$point" || rc=$?   # the re-run must find the port and URL itself
        if [ "$rc" -ne 0 ]; then
          note="$note; re-run FAILED (rc=$rc): $(grep -v '^[[:space:]]*$' "$OUT/rerun-$point.out" | grep -m1 '✗' | sed 's/^ *//')"
          collect "rerun-failed-$point"; COLLECTED=$CTR
          rc=0; OMIT_PORT=1 OMIT_BASE_URL=1 run_install "rerun2-$point" || rc=$?
          note="$note; a second re-run $([ "$rc" -eq 0 ] && echo succeeded || echo "failed too (rc=$rc)")"
          verdict="NOT-CONVERGED"
          CHECK_FAILS=$((CHECK_FAILS + 1))
        fi
        # ... and the re-run (with -y: not an update) says the host still runs it. (This one tells a regression apart
        # only when the release and the code under test have different versions; the record check above always does.)
        if [ -n "$from_ver" ]; then
          check "the re-run after the kill says the host runs v$from_ver" \
            "$(grep -cF "This host runs v${from_ver};" "$OUT/rerun-$point.out" || true)" 1
        fi
        continuity_after "rerun-$point"
        settings_kept "rerun-$point"
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
      if [ "$verdict" = CONVERGED ]; then m2_save_or_warn; fi
      remove_container "$CTR"; CTR=""
    done
    LOG_TAG="$DISTRO/$SCENARIO"
    ;;
esac

if [ "$SCENARIO" != killed ] && [ "$CHECK_FAILS" -eq 0 ]; then m2_save_or_warn; fi
log "results ($(minutes "$T0") min):"
{ column -t -s $'\t' "$OUT/results.tsv" 2>/dev/null || cat "$OUT/results.tsv"; } | while IFS= read -r l; do log "  $l"; done
if [ "$SCENARIO" = killed ]; then
  [ "$failed" -eq 0 ] || die "$failed kill point(s) did not converge"
else
  [ "$CHECK_FAILS" -eq 0 ] || die "$CHECK_FAILS check(s) failed"
fi
log "OK"
