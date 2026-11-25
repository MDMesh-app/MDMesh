#!/usr/bin/env bash
# `./setup.sh --update` against a fixture: a local "origin" repository built from this checkout's installer files, with
# release tags, and release manifests signed by a throwaway minisign key served by a curl stub.
#
# Usage: tests/setup-update/run.sh
#
# Checks that --update checks out the newest signed stable release tag (a detached HEAD) and then runs that release's
# setup.sh; skips a newest tag whose release has no published manifest, saying so; refuses a bad or foreign signature,
# a replayed manifest, a manifest naming another commit or none; refuses local changes, and going back from a newer
# checkout over an install unless --allow-downgrade, but not from an older release's branch the release squashed;
# moves on from a detached release checkout (an annotated tag);
# that MDMESH_DRY_RUN=1 (also from an answers file) fetches and changes nothing; and that the tunnel token reaches no
# program --update runs.
#
# Nothing is installed: every clone is in a temporary directory, curl and docker are stubs (the setup.sh that --update
# runs stops at the docker stub), and the test refuses to run as root. Needs: bash, git, minisign, openssl 3.0+, python3.
set -euo pipefail
unset CDPATH
HERE=$(cd -P -- "$(dirname -- "$0")" && pwd)
REPO=$(cd -P -- "$HERE/../.." && pwd)
for c in git minisign openssl python3; do command -v "$c" >/dev/null || { echo "$c is required" >&2; exit 2; }; done
[ "$(id -u)" != 0 ] || { echo "Run this as an unprivileged user, not root." >&2; exit 2; }
export LC_ALL=C

WORK=$(mktemp -d)
cleanup() { rm -r -- "$WORK" 2>/dev/null || true; }
trap cleanup EXIT
FAILS=0; CHECKS=0
fail() { FAILS=$((FAILS + 1)); printf 'FAIL: %s\n' "$*" >&2; }
ok() { CHECKS=$((CHECKS + 1)); }
G=(git -c user.name=t -c user.email=t@example.com -c init.defaultBranch=main -c advice.detachedHead=false)

# Stubs: curl serves https://github.com/test/repo/releases/download/<tag>/<name> from $REL_DIR/<tag>/<name>, answering
# -w '%{http_code}' (404 for a missing file; with CURL_FAILFLAG=1, as with `fail` in a .curlrc, also curl exit 22);
# CURL_FAULT=<tag>:28 makes it time out for that tag (curl exit 28, code 000), <tag>:7 refuse the connection (exit 7),
# <tag>:503 answer HTTP 503, <tag>:127 not run, <tag>:77 or <tag>:60 fail its TLS set-up (no CA certificates, an
# untrusted certificate); CURL_FLAKY=N makes its first N calls time out. docker (and anything else that could
# install) fails.
mkdir -p "$WORK/stubs"
cat > "$WORK/stubs/curl" <<'EOF'
#!/bin/sh
env >> "$WORK_ENV_LOG"
out=''; url=''; w=''
while [ $# -gt 0 ]; do case "$1" in -o) out=$2; shift ;; -w) w=$2; shift ;; https://*) url=$1 ;; esac; shift; done
code() { [ -z "$w" ] || printf '%s' "$1"; }
case "$url" in https://github.com/test/repo/releases/download/*) ;; *) code 000; exit 6 ;; esac
rel=${url#https://github.com/test/repo/releases/download/}
if [ -n "${CURL_FLAKY:-}" ]; then
  n=$(cat "$WORK_ENV_LOG.flaky" 2>/dev/null || echo 0); echo $((n + 1)) > "$WORK_ENV_LOG.flaky"
  [ "$n" -ge "$CURL_FLAKY" ] || { code 000; exit 28; }
fi
case "${CURL_FAULT:-}" in
  "${rel%%/*}":28) code 000; exit 28 ;; "${rel%%/*}":7) code 000; exit 7 ;; "${rel%%/*}":503) code 503; exit 0 ;;
  "${rel%%/*}":127) exit 127 ;; "${rel%%/*}":77) code 000; exit 77 ;; "${rel%%/*}":60) code 000; exit 60 ;;
esac
f="$REL_DIR/$rel"
if [ -f "$f" ] && [ -n "$out" ]; then cp "$f" "$out"; code 200; exit 0; fi
code 404; [ "${CURL_FAILFLAG:-}" != 1 ] || exit 22; exit 0
EOF
# shellcheck disable=SC2016  # the stub's own variable
for c in docker apt-get systemctl mvn; do printf '#!/bin/sh\nenv >> "$WORK_ENV_LOG"\nexit 99\n' > "$WORK/stubs/$c"; done
chmod +x "$WORK/stubs"/*

# The fixture origin: v9.0.0, v9.1.0, a pre-release v9.2.0-rc1, then main ahead of them all.
minisign -G -W -p "$WORK/k.pub" -s "$WORK/k.key" >/dev/null
minisign -G -W -p "$WORK/x.pub" -s "$WORK/x.key" >/dev/null
O="$WORK/origin"
mkdir -p "$O/release"
mkdir -p "$O/supervisor"; cp -- "$REPO/supervisor/mode-guard.sh" "$O/supervisor/"
cp -R -- "$REPO/setup.sh" "$REPO/quickstart.sh" "$REPO/install" "$REPO/docker-compose.release.yml" \
  "$REPO/docker-compose.domain.yml" "$O/"
cp -- "$WORK/k.pub" "$O/release/minisign.pub"
# The fixture's setup.sh looks for a native install in $WORK/native-tc instead of /opt/mdmesh-tc (the native tests
# below create it). The shipped one must keep the real path: a typo there would hide every native install.
grep -qF '[ -d /opt/mdmesh-tc ]' "$REPO/setup.sh" || { echo "FAIL: setup.sh no longer checks [ -d /opt/mdmesh-tc ]" >&2; exit 1; }
sed -i "s#\[ -d /opt/mdmesh-tc \]#[ -d $WORK/native-tc ]#" "$O/setup.sh"
grep -qF "[ -d $WORK/native-tc ]" "$O/setup.sh" || { echo "FAIL: could not point the fixture's setup.sh at a stand-in native install" >&2; exit 1; }
( cd "$O" && "${G[@]}" init -q && "${G[@]}" add -A && "${G[@]}" commit -qm r1 && "${G[@]}" tag -a -m r1 v9.0.0 \
  && echo 1 > note && "${G[@]}" add note && "${G[@]}" commit -qm r2 && "${G[@]}" tag v9.1.0 \
  && echo 2 > note && "${G[@]}" commit -qam rc && "${G[@]}" tag v9.2.0-rc1 \
  && echo 3 > note && "${G[@]}" commit -qam ahead )
c900=$(git -C "$O" rev-parse 'v9.0.0^{commit}'); c910=$(git -C "$O" rev-parse v9.1.0); cmain=$(git -C "$O" rev-parse main)
# A second origin whose newest tag, v9.1.1, has no published release yet; a third with six such tags.
"${G[@]}" clone -q "$O" "$WORK/origin2"; "${G[@]}" -C "$WORK/origin2" tag v9.1.1 "$cmain"
"${G[@]}" clone -q "$O" "$WORK/origin3"; for i in 0 1 2 3 4 5; do "${G[@]}" -C "$WORK/origin3" tag "v9.3.$i" "$cmain"; done

# manifest DIR TAG VERSION COMMIT KEY: a signed manifest for TAG in DIR/TAG/ (no commit when COMMIT is empty).
manifest() {
  local dir=$1/$2; mkdir -p "$dir"
  python3 - "$3" "$4" > "$dir/manifest.json" <<'PY'
import sys, json
ver, commit = sys.argv[1:3]
m = {"version": ver, "channel": "stable"}
if commit: m["commit"] = commit
print(json.dumps(m, indent=2))
PY
  minisign -S -s "$5" -m "$dir/manifest.json" >/dev/null
}
R="$WORK/rel"
manifest "$R/ok" v9.1.0 9.1.0 "$c910" "$WORK/k.key"
manifest "$R/ok" v9.0.0 9.0.0 "$c900" "$WORK/k.key"
manifest "$R/nobind" v9.1.0 9.1.0 '' "$WORK/k.key"
manifest "$R/wrongcommit" v9.1.0 9.1.0 "$c900" "$WORK/k.key"
manifest "$R/otherkey" v9.1.0 9.1.0 "$c910" "$WORK/x.key"
mkdir -p "$R/nosig/v9.1.0" "$R/replay/v9.1.0" "$R/tampered/v9.1.0"
cp "$R/ok/v9.1.0/manifest.json" "$R/nosig/v9.1.0/"
cp "$R/ok/v9.0.0/manifest.json" "$R/ok/v9.0.0/manifest.json.minisig" "$R/replay/v9.1.0/"
cp "$R/ok/v9.1.0/manifest.json" "$R/ok/v9.1.0/manifest.json.minisig" "$R/tampered/v9.1.0/"
sed -i 's/"channel": "stable"/"channel": "beta"/' "$R/tampered/v9.1.0/manifest.json"

# clone NAME [REF] [ORIGIN]: a fresh clone of the fixture origin (or ORIGIN), on main or detached at REF.
clone() {
  "${G[@]}" clone -q "${3:-$O}" "$WORK/$1"
  [ -z "${2:-}" ] || "${G[@]}" -C "$WORK/$1" checkout -q --detach "$2"
}
# upd NAME RELEASES [NAME=VALUE...] -- [ARGS...]: ./setup.sh --update ARGS in clone NAME, the releases served from
# $R/RELEASES. Sets RC; output in $WORK/out.
upd() {
  local name=$1 rel=$2; shift 2
  local envs=(); while [ "$#" -gt 0 ] && [ "$1" != -- ]; do envs+=("$1"); shift; done; [ "$#" = 0 ] || shift
  RC=0; : > "$WORK/env.log"; rm -f -- "$WORK/env.log.flaky"
  (cd "$WORK/$name" && env -i PATH="$WORK/stubs:$PATH" HOME="$WORK" GITHUB_REPO=test/repo REL_DIR="$R/$rel" WORK_ENV_LOG="$WORK/env.log" \
    "${envs[@]}" bash ./setup.sh --update "$@" < /dev/null > "$WORK/out" 2>&1) || RC=$?
}
head_is() { [ "$(git -C "$WORK/$1" rev-parse HEAD)" = "$2" ]; }
show() { sed 's/^/    | /' "$WORK/out" >&2; }
# expect_refused NAME RELEASES WHAT TEXT: --update stops, saying TEXT, and the clone stays where it was.
expect_refused() {
  local before; before=$(git -C "$WORK/$1" rev-parse HEAD)
  upd "$1" "$2"
  { [ "$RC" != 0 ] && grep -qF -- "$4" "$WORK/out" && head_is "$1" "$before"; } || { fail "$3 is not refused"; show; }
  ok
}

# A fresh clone on main (ahead of the release), nothing installed: the newest stable release, then its setup.sh.
clone fresh
upd fresh ok
{ head_is fresh "$c910" && grep -q 'Checked out v9.1.0' "$WORK/out" && grep -q 'Docker Compose v2 is required' "$WORK/out" \
  && [ -z "$(git -C "$WORK/fresh" symbolic-ref -q HEAD || true)" ]; } \
  || { fail "a fresh clone is not moved to v9.1.0 (detached) and handed to its setup.sh"; show; }
ok
# Dry run, from the environment or an answers file: verifies and says what it would do; nothing is fetched (a tag
# origin gained after the clone stays unknown to it) and the clone doesn't move.
clone dry
"${G[@]}" -C "$O" tag v9.0.1 "$c900"
upd dry ok MDMESH_DRY_RUN=1 --
{ [ "$RC" = 0 ] && head_is dry "$cmain" && grep -q 'v9.1.0 verifies' "$WORK/out" && [ -z "$(git -C "$WORK/dry" tag -l v9.0.1)" ]; } \
  || { fail "MDMESH_DRY_RUN=1 --update fetched, changed the clone or failed"; show; }
ok
(umask 077; printf 'MDMESH_DRY_RUN=1\n' > "$WORK/answers.env")
upd dry ok MDMESH_ANSWERS="$WORK/answers.env" --
{ [ "$RC" = 0 ] && head_is dry "$cmain" && grep -q 'v9.1.0 verifies' "$WORK/out"; } || { fail "MDMESH_DRY_RUN=1 from an answers file does not stop --update"; show; }
ok
upd dry ok MDMESH_DRY_RUN=yes --
{ [ "$RC" != 0 ] && head_is dry "$cmain" && grep -q 'MDMESH_DRY_RUN must be 1 or 0' "$WORK/out"; } || { fail "an invalid MDMESH_DRY_RUN does not stop --update before it changes anything"; show; }
ok
# The tunnel token: no program --update runs (curl, and the docker the next setup.sh runs) has it in its environment.
clone tok
upd tok ok MDMESH_TUNNEL_TOKEN=c0ffee0token0c0ffee MDMESH_MODE=tunnel MDMESH_HOST=mdm.example.com --
{ head_is tok "$c910" && [ -s "$WORK/env.log" ] && ! grep -q c0ffee0token0c0ffee "$WORK/env.log" "$WORK/out"; } \
  || { fail "the tunnel token reached a program --update ran, or the output"; show; }
ok
# A newest tag whose release isn't published yet (HTTP 404): skipped, saying so, for the newest that is. From an older
# release; and on a fresh clone of main, which sits on that unpublished tag (the documented first install); and with a
# curl that fails on HTTP errors (`fail` in a .curlrc: exit 22, still a 404).
clone fallback v9.0.0 "$WORK/origin2"
upd fallback ok
{ head_is fallback "$c910" && grep -q 'v9.1.1: no published release yet' "$WORK/out"; } \
  || { fail "a newest tag without a published release is not skipped for the newest signed one"; show; }
ok
clone fallback-fresh '' "$WORK/origin2"
upd fallback-fresh ok
{ head_is fallback-fresh "$c910" && grep -q 'v9.1.1: no published release yet' "$WORK/out"; } \
  || { fail "a fresh clone on an unpublished tag does not fall back to the newest signed release"; show; }
ok
clone fallback-fail v9.0.0 "$WORK/origin2"
upd fallback-fail ok CURL_FAILFLAG=1 --
head_is fallback-fail "$c910" || { fail "a 404 with curl exit 22 is not treated as a 404"; show; }
ok
# A network failure is not "not published": a timeout or a 5xx stops, saying GitHub could not be reached, and never
# falls back (nor reports an older release's manifest). Tags skipped before it are listed first.
for f in "v9.1.0:28|timed out" "v9.1.0:7|connection refused" "v9.1.0:503|HTTP 503"; do
  clone "net-${f%%:*}-${f#*:}" v9.0.0 "$WORK/origin2"
  upd "net-${f%%:*}-${f#*:}" ok CURL_FAULT="${f%%|*}" --
  { [ "$RC" != 0 ] && head_is "net-${f%%:*}-${f#*:}" "$c900" && grep -qF "Could not reach GitHub" "$WORK/out" \
    && grep -qF "${f#*|}" "$WORK/out" && ! grep -q 'records no commit' "$WORK/out" \
    && [ "$(grep -n 'v9.1.1: no published release yet' "$WORK/out" | cut -d: -f1)" -lt "$(grep -n 'Could not reach GitHub' "$WORK/out" | cut -d: -f1)" ]; } \
    || { fail "a ${f#*|} is not reported as a network failure after the skipped tags"; show; }
  ok
done
# A flaky link: retried, then it works.
clone flaky v9.0.0
upd flaky ok CURL_FLAKY=2 --
head_is flaky "$c910" || { fail "--update does not retry a timeout"; show; }
ok
# With an install, the walk stops at the release this clone is on: nothing newer published yet is said so, nothing older
# is tried.
clone older v9.1.0 "$WORK/origin2"; : > "$WORK/older/.env"
upd older nosig
{ [ "$RC" != 0 ] && head_is older "$c910" && grep -q 'No release newer than this clone' "$WORK/out"; } \
  || { fail "--update walks below the release the clone is on"; show; }
ok
# A native install counts as an install for both rules, with or without --native: its /opt/mdmesh-tc is visible to any
# user even though it is mode 700 (here a stand-in directory).
mkdir -p "$WORK/native-tc"
clone nat-ahead
expect_refused_env() {  # NAME RELEASES WHAT TEXT ENV...
  local n=$1 r=$2 what=$3 text=$4 before; shift 4; before=$(git -C "$WORK/$n" rev-parse HEAD)
  upd "$n" "$r" "$@" --
  { [ "$RC" != 0 ] && grep -qF -- "$text" "$WORK/out" && head_is "$n" "$before"; } || { fail "$what is not refused"; show; }
  ok
}
expect_refused_env nat-ahead ok "going back from main over a native install" "would run older code"
# --allow-downgrade counts with --native too (here in a dry run, which needs no root).
upd nat-ahead ok MDMESH_DRY_RUN=1 -- --native --allow-downgrade
{ [ "$RC" = 0 ] && grep -q 'v9.1.0 verifies' "$WORK/out"; } || { fail "--update --native --allow-downgrade does not go back"; show; }
ok
clone nat-older v9.1.0 "$WORK/origin2"
expect_refused_env nat-older nosig "walking below the release of a native install" "No release newer than this clone"
rmdir -- "$WORK/native-tc"
# --update --native changes this host's install: as a non-root user it stops at once, saying to use sudo, before it
# looks anything up or checks anything out. A dry run is fine without root.
clone nat-user
upd nat-user ok -- --native
{ [ "$RC" != 0 ] && head_is nat-user "$cmain" && grep -qF 'sudo ./setup.sh --update --native' "$WORK/out" \
  && ! grep -q 'Looking up' "$WORK/out"; } || { fail "--update --native as a non-root user does not stop at once"; show; }
ok
upd nat-user ok MDMESH_DRY_RUN=1 -- --native
{ [ "$RC" = 0 ] && head_is nat-user "$cmain" && grep -q 'v9.1.0 verifies' "$WORK/out"; } || { fail "a dry run of --update --native needs root"; show; }
ok
# The tools --update needs are checked before it does anything: without curl, or without openssl 3.0+ and minisign, it
# stops at once, naming them and how to install them, and never as "could not reach GitHub". (A PATH of every command
# on this host except those; the stubs without curl.) A curl that can't run (exit 127) isn't a network failure either.
nopath() {  # nopath DIR CMD...: DIR holds a link to every command on PATH except CMD...
  local d=$1 f n skip; shift; skip=" $* "; mkdir -p "$d"
  local IFS=:; for f in $PATH; do
    for n in "$f"/*; do
      { [ -f "$n" ] && [ -x "$n" ]; } || continue
      case "$skip" in *" ${n##*/} "*) continue ;; esac
      [ -e "$d/${n##*/}" ] || ln -s "$n" "$d/${n##*/}"
    done
  done
}
mkdir -p "$WORK/stubs-nocurl"; for c in "$WORK"/stubs/*; do [ "${c##*/}" = curl ] || cp -- "$c" "$WORK/stubs-nocurl/"; done
nopath "$WORK/bin-nocurl" curl
nopath "$WORK/bin-noverify" openssl minisign
clone tools
upd tools ok PATH="$WORK/stubs-nocurl:$WORK/bin-nocurl" --
{ [ "$RC" != 0 ] && head_is tools "$cmain" && grep -qF 'needs curl' "$WORK/out" && grep -qF 'apt-get install' "$WORK/out" \
  && ! grep -q 'Could not reach GitHub\|Looking up' "$WORK/out"; } || { fail "--update without curl does not stop at once naming it"; show; }
ok
clone tools2
upd tools2 ok PATH="$WORK/stubs:$WORK/bin-noverify" --
{ [ "$RC" != 0 ] && head_is tools2 "$cmain" && grep -qF 'openssl 3.0+ or minisign' "$WORK/out" && ! grep -q 'Looking up' "$WORK/out"; } \
  || { fail "--update without openssl or minisign does not stop at once naming them"; show; }
ok
clone tools3
upd tools3 ok CURL_FAULT=v9.1.0:127 --
{ [ "$RC" != 0 ] && head_is tools3 "$cmain" && grep -qF 'could not run curl' "$WORK/out" && ! grep -q 'Could not reach GitHub' "$WORK/out"; } \
  || { fail "a curl that can't run is reported as a network failure"; show; }
ok
# A curl without CA certificates (exit 77: what a curl installed without recommends does) is a TLS set-up problem, not
# GitHub out of reach, and is not retried.
upd tools3 ok CURL_FAULT=v9.1.0:77 --
{ [ "$RC" != 0 ] && head_is tools3 "$cmain" && grep -qF 'ca-certificates' "$WORK/out" && ! grep -q 'Could not reach GitHub' "$WORK/out"; } \
  || { fail "curl exit 77 is not reported as missing CA certificates"; show; }
ok
# A certificate curl doesn't trust (exit 60: CA certificates out of date, or a proxy intercepting TLS) is that too: it
# stops at once, with the first download.
upd tools3 ok CURL_FAULT=v9.1.0:60 --
{ [ "$RC" != 0 ] && head_is tools3 "$cmain" && grep -qF "does not trust GitHub's TLS certificate (curl exit 60)" "$WORK/out" \
    && ! grep -q 'Could not reach GitHub' "$WORK/out" && [ "$(grep -c '^REL_DIR=' "$WORK/env.log")" = 1 ]; } \
  || { fail "curl exit 60 is not reported as an untrusted certificate, or is retried"; show; }
ok
# A mistyped option stops before the clone moves (without --native; with it the native installer checks them).
clone typo
upd typo ok -- --alow-downgrade
{ [ "$RC" = 2 ] && head_is typo "$cmain" && grep -qF 'Unknown option: --alow-downgrade' "$WORK/out" && ! grep -q 'Looking up' "$WORK/out"; } \
  || { fail "--update with a mistyped option moves the clone before saying so"; show; }
ok
# ... and gives up after the 5 newest tags.
clone capped v9.0.0 "$WORK/origin3"
upd capped ok
{ [ "$RC" != 0 ] && head_is capped "$c900" && grep -q 'None of the 5 newest release tags' "$WORK/out" && ! grep -q 'v9.1.0' "$WORK/out"; } \
  || { fail "--update does not stop after the 5 newest tags"; show; }
ok
# From a detached release checkout with an install: forward to the newest, no git pull involved. The release's setup.sh
# is told it runs an update (MDMESH_UPDATING: the native installer then asks nothing), but not from which release: that
# is the install's own record, which the clone's tag can't tell (it may have moved before the run); a fresh clone's is
# not told it is an update. (Seen in the environment of the docker stub, which that setup.sh runs.)
clone detached v9.0.0; : > "$WORK/detached/.env"
upd detached ok
head_is detached "$c910" || { fail "--update from v9.0.0 (detached) did not move to v9.1.0"; show; }
ok
{ grep -qx 'MDMESH_UPDATING=1' "$WORK/env.log" && ! grep -q '^MDMESH_UPDATE_FROM=' "$WORK/env.log"; } \
  || { fail "--update over an install does not tell the release's setup.sh it updates, or still names a release from the clone"; show; }
ok
# The release a native update says it leaves is the install's record (install/lib/envfile.sh mdm_installed_version, read
# from a stand-in for /etc/mdmesh), never the clone's tag: the clone is on v9.1.0 now (as after an update stopped at the
# installer's question, or a git pull), the install still on 9.0.0, so the title reads 9.0.0 → 9.1.0, not 9.1.0 → 9.1.0.
# The installer's lines come from install/lib/version.sh (mdm_native_*), run here with what the libraries read. (The
# installer itself can't run unprivileged; this checks only that it reads the record and passes it to the title.)
! grep -rqF MDMESH_UPDATE_FROM "$REPO/setup.sh" "$REPO/install" || fail "MDMESH_UPDATE_FROM (the clone's tag) is still passed or read"
# shellcheck disable=SC2016  # the installer's code, word for word
if ! { grep -qF 'INSTALLED_VERSION=$(mdm_installed_version "$SUP_ENV_DIR")' "$REPO/install/install-native.sh" \
       && grep -qF '$(mdm_native_title "$UPDATING" "$INSTALLED_VERSION" "$CLONE_VERSION")' "$REPO/install/install-native.sh"; }; then
  fail "install-native.sh no longer titles an update with the install's record (mdm_installed_version)"
fi
ok
# lib FUNCTION ARGS...: FUNCTION from install/lib/envfile.sh and version.sh, as the installer runs it.
# shellcheck disable=SC2016  # the inner shell's variables
lib() { bash -c '. "$1/envfile.sh"; . "$1/version.sh"; shift; "$@"' _ "$REPO/install/lib" "$@"; }
# installed DIR: what mdm_installed_version prints for the stand-in DIR ("?" for nothing, as the title shows it).
installed() { local v; v=$(lib mdm_installed_version "$1"); printf '%s' "${v:-?}"; }
E="$WORK/etc-mdmesh"; mkdir -p "$E"
printf 'SUPERVISOR_PORT=9000\nCURRENT_VERSION=9.0.0\nAPPLY_SUPPORTED=0\n' > "$E/supervisor.env"
cv=$(cd "$WORK/detached" && lib mdm_repo_version .)
title=$(lib mdm_native_title 1 "$(lib mdm_installed_version "$E")" "$cv")
[ "$title" = "MDMesh · native update, 9.0.0 → 9.1.0" ] \
  || fail "a native update over 9.0.0 from a clone on v9.1.0 is titled '$title', not '… 9.0.0 → 9.1.0'"
ok
# expect_lib WANT WHAT FUNCTION ARGS...: FUNCTION's output is WANT.
expect_lib() { local want=$1 what=$2 got; shift 2; got=$(lib "$@"); [ "$got" = "$want" ] || fail "$what: got '$got', want '$want'"; ok; }
expect_lib "MDMesh · native update, ? → ?" "the title with neither release known" mdm_native_title 1 '' ''
expect_lib "MDMesh · native install" "the title of a run that is not an update" mdm_native_title 0 9.0.0 9.1.0
# A run that isn't an update (an older setup.sh's --update, or a plain re-run) over a recorded install says what it is.
expect_lib "This host runs v9.0.0; this clone is on v9.1.0." "the host line over 9.0.0" mdm_native_host_line 9.0.0 9.1.0
expect_lib "This host runs v9.0.0; this clone is on no release tag." "the host line, untagged clone" mdm_native_host_line 9.0.0 ''
expect_lib "" "the host line without a record" mdm_native_host_line '' 9.1.0
# Answered no: --update is suggested only when the clone is newer than the install; nothing changed in every case.
declined_newer=$'Aborted. The install is unchanged: this host still runs v9.0.0. This clone is on v9.1.0, newer than the install.\nTo update this host, run: sudo ./setup.sh --update --native'
expect_lib "$declined_newer" "declining with the clone ahead" mdm_native_declined 9.0.0 9.1.0
for c in "9.1.0 9.1.0" "9.1.0 9.0.0" "'' 9.1.0" "9.0.0 ''" "'' ''"; do
  eval "set -- $c"
  expect_lib "Aborted — nothing on this host was changed." "declining with install '$1', clone '$2'" mdm_native_declined "$1" "$2"
done
# The supervisor.env fallback is read once: over an install without the record, the installer writes what it read
# (mdm_installed_version) as the record before its first change, so a run that then fails after rewriting CURRENT_VERSION
# to the new release still leaves the old one as the release to update from. (The installer's own part is checked by
# tests/native's killed scenario.)
printf '%s\n' "$(lib mdm_installed_version "$E")" > "$E/installed-version"   # as the installer writes it
printf 'CURRENT_VERSION=9.1.0\n' > "$E/supervisor.env"                     # the failed run's rewrite
expect_lib 9.0.0 "the release to update from after a failed first run" mdm_installed_version "$E"
rm -f -- "$E/installed-version"
printf 'SUPERVISOR_PORT=9000\nCURRENT_VERSION=9.0.0\nAPPLY_SUPPORTED=0\n' > "$E/supervisor.env"
# expect_installed WANT WHAT: mdm_installed_version of $E is WANT.
expect_installed() { local got; got=$(installed "$E"); [ "$got" = "$1" ] || fail "mdm_installed_version: $2 gives '$got', want '$1'"; ok; }
# The record a completed run writes wins over supervisor.env (written before a run ends), even when it is empty.
printf '9.0.1\n' > "$E/installed-version"; expect_installed 9.0.1 "installed-version beside an older CURRENT_VERSION"
: > "$E/installed-version"; expect_installed '?' "an empty installed-version (installed from an untagged clone)"
printf '9.0.1' > "$E/installed-version"; expect_installed 9.0.1 "installed-version without a newline"
printf '9.0.1\r\n' > "$E/installed-version"; expect_installed 9.0.1 "installed-version with a CRLF"
printf 'v9.0.1\n' > "$E/installed-version"; expect_installed '?' "installed-version that is not release-shaped"
rm -f -- "$E/installed-version"; mkdir "$E/installed-version"; expect_installed '?' "installed-version that is a directory"
rmdir -- "$E/installed-version"; ln -s "$E/supervisor.env" "$E/installed-version"
expect_installed '?' "installed-version that is a symbolic link"
rm -f -- "$E/installed-version"
# Without the record (installs from before it): CURRENT_VERSION, read as systemd reads the file.
for c in "CURRENT_VERSION=9.0.0:9.0.0" "  CURRENT_VERSION = \"9.0.0-rc.1\" :9.0.0-rc.1" "CURRENT_VERSION='9.0.0'\r:9.0.0" \
         "CURRENT_VERSION=0.0.0:?" "CURRENT_VERSION=:?" "#CURRENT_VERSION=9.0.0:?" "CURRENT_VERSION=9.0.0 junk:?" \
         "CURRENT_VERSION=9.0.0\nCURRENT_VERSION=9.0.2:9.0.2" "X=\"open\nCURRENT_VERSION=9.0.0\nclose\":?" \
         "X=a\\\\\nCURRENT_VERSION=9.0.0:?"; do
  # shellcheck disable=SC2059  # the case's \n, \r and \\ are printf escapes
  printf "${c%:*}\n" > "$E/supervisor.env"; expect_installed "${c##*:}" "supervisor.env '${c%:*}'"
done
printf 'X=1\nCURRENT_VERSION=9.0.3' > "$E/supervisor.env"; expect_installed 9.0.3 "supervisor.env whose last line has no newline"
rm -f -- "$E/supervisor.env"; expect_installed '?' "no record at all (a fresh host, or supervisor.env from before v0.3.2)"
clone fresh2
upd fresh2 ok
{ head_is fresh2 "$c910" && grep -q '^PATH=' "$WORK/env.log" && ! grep -q '^MDMESH_UPDAT' "$WORK/env.log"; } \
  || { fail "--update of a fresh clone tells its setup.sh it is an update"; show; }
ok
# Already on the newest release: says so and runs setup.sh.
upd detached ok
{ head_is detached "$c910" && grep -q 'already on v9.1.0' "$WORK/out" && grep -q 'Docker Compose v2 is required' "$WORK/out"; } \
  || { fail "--update on the newest release does not say so and run setup.sh"; show; }
ok
# main ahead of the release, with an install: going back needs --allow-downgrade.
clone ahead; : > "$WORK/ahead/.env"
expect_refused ahead ok "going back from main over an install" "would run older code"
upd ahead ok -- --allow-downgrade
head_is ahead "$c910" || { fail "--allow-downgrade does not go back to v9.1.0"; show; }
ok
# A clone on a release branch before its squash merge, with an install: v9.0.0 plus commits of its own, which the
# release v9.1.0 holds under other hashes, so they are not its ancestors. Its version is older than v9.1.0: an upgrade.
clone squash v9.0.0; : > "$WORK/squash/.env"; "${G[@]}" -C "$WORK/squash" commit -q --allow-empty -m pre-squash
upd squash ok
{ head_is squash "$c910" && grep -q 'Checked out v9.1.0' "$WORK/out"; } \
  || { fail "a clone on a release branch before its squash merge is refused as going back"; show; }
ok
# v9.1.0 plus a commit of its own, with an install: the release's own version, with code the release doesn't have.
clone same v9.1.0; : > "$WORK/same/.env"; "${G[@]}" -C "$WORK/same" commit -q --allow-empty -m local
expect_refused same ok "going back from v9.1.0 plus a commit over an install" "would run older code"
# The same with stale local tags (v9.1.0 not fetched yet, so the clone's own tags say v9.0.0): its version is read once
# the release's tag is fetched, and it is refused all the same.
clone stale v9.1.0; : > "$WORK/stale/.env"; "${G[@]}" -C "$WORK/stale" commit -q --allow-empty -m local
"${G[@]}" -C "$WORK/stale" tag -d v9.1.0 > "$WORK/tagd.out"
expect_refused stale ok "going back from v9.1.0 plus a commit, with stale local tags, over an install" "would run older code"
# Local changes: stop, change nothing.
clone dirty; echo x >> "$WORK/dirty/note"
expect_refused dirty ok "a clone with local changes" "commit or stash them"
# Releases that don't verify.
for c in nosig:"has a published, signed manifest" otherkey:"is not signed by the release key" \
         tampered:"is not signed by the release key" replay:"is not that release's" \
         wrongcommit:"its signed manifest names commit" nobind:"records no commit"; do
  clone "bad-${c%%:*}"
  expect_refused "bad-${c%%:*}" "${c%%:*}" "a release with ${c%%:*}" "${c#*:}"
done

# Behind your own proxy (0.6.0 on), never to a release before 0.6.0, --allow-downgrade or not, dry run or not: that
# release's setup.sh and edge don't know the mode. An origin whose only release is v0.5.9; the same move without the
# proxy overlay goes on (with --allow-downgrade), so the refusal is the mode's.
"${G[@]}" clone -q "$O" "$WORK/origin4"
for t in $("${G[@]}" -C "$WORK/origin4" tag -l 'v9*'); do "${G[@]}" -C "$WORK/origin4" tag -d "$t" > /dev/null; done
"${G[@]}" -C "$WORK/origin4" tag -a -m old v0.5.9 "$c900"
manifest "$R/old" v0.5.9 0.5.9 "$c900" "$WORK/k.key"
for a in '' --allow-downgrade dry; do
  clone "proxy$a" '' "$WORK/origin4"
  "${G[@]}" -C "$WORK/proxy$a" tag -d v0.5.9 > "$WORK/tagd.out"   # so a fetch of the tag would show
  printf '%s\n' 'COMPOSE_FILE=docker-compose.yml:docker-compose.proxy.yml' 'CURRENT_VERSION=0.6.0' > "$WORK/proxy$a/.env"
  case "$a" in
    dry) upd "proxy$a" old MDMESH_DRY_RUN=1 -- ;;
    *) upd "proxy$a" old -- $a ;;
  esac
  { [ "$RC" != 0 ] && grep -qF "runs behind your own proxy (docker-compose.proxy.yml), which came in 0.6.0, so it can't go to 0.5.9" "$WORK/out" \
    && head_is "proxy$a" "$cmain" && [ -z "$("${G[@]}" -C "$WORK/proxy$a" tag -l v0.5.9)" ]; } \
    || { fail "--update ${a:-} to v0.5.9 behind your own proxy is not refused before fetching the tag"; show; }
  ok
done
# Moved out of the proxy mode with TRUSTED_PROXIES="host" left: the same rule as the updater's, --allow-downgrade too.
clone host059 '' "$WORK/origin4"
printf '%s\n' 'COMPOSE_FILE=docker-compose.yml:docker-compose.domain.yml' 'TRUSTED_PROXIES="host"' 'CURRENT_VERSION=0.6.0' > "$WORK/host059/.env"
upd host059 old -- --allow-downgrade
{ [ "$RC" != 0 ] && grep -qF 'TRUSTED_PROXIES in .env still says host' "$WORK/out" && head_is host059 "$cmain"; } \
  || { fail "--update --allow-downgrade to v0.5.9 with TRUSTED_PROXIES=host is not refused"; show; }
ok
clone domain059 '' "$WORK/origin4"
printf '%s\n' 'COMPOSE_FILE=docker-compose.yml:docker-compose.domain.yml' 'CURRENT_VERSION=0.6.0' > "$WORK/domain059/.env"
upd domain059 old -- --allow-downgrade
{ head_is domain059 "$c900" && grep -q 'Checked out v0.5.9' "$WORK/out"; } || { fail "--update --allow-downgrade to v0.5.9 in domain mode is refused"; show; }
ok

# On your own domain, with a COMPOSE_FILE that leaves the domain overlay out: the release's setup.sh stops before any
# container is touched (the clone has moved; install/lib/ports.sh). With the line it shows in .env, --update at the same
# tag runs through to `up`, which gives the supervisor the new list. docker: a stub whose edge binds 443.
mkdir -p "$WORK/stubs-ports"
cat > "$WORK/stubs-ports/docker" <<'EOF'
#!/bin/sh
case "$*" in
  "compose ps -a -q caddy") echo edge1 ;;
  inspect*PortBindings*) echo '{"443/tcp":[{"HostIp":"","HostPort":"443"}]}' ;;
  compose*" up "*) echo "up $* COMPOSE_FILE=$COMPOSE_FILE" >> "$WORK_ENV_LOG"; exit 99 ;;
esac
exit 0
EOF
chmod +x "$WORK/stubs-ports/docker"
clone ports v9.0.0
printf '%s\n' DB_PASSWORD=x HASH_SECRET=y BASE_URL=https://mdm.example.com IMAGE_OWNER=local \
  COMPOSE_FILE=docker-compose.yml:mine.yml > "$WORK/ports/.env"
upd ports ok PATH="$WORK/stubs-ports:$WORK/stubs:$PATH" --
{ [ "$RC" = 1 ] && head_is ports "$c910" && grep -qF 'port 443 would close' "$WORK/out" && ! grep -q '^up ' "$WORK/env.log" \
  && grep -qF "COMPOSE_FILE='docker-compose.yml:mine.yml:docker-compose.domain.yml'" "$WORK/out"; } \
  || { fail "--update with a list that closes the edge's 443 does not stop, showing the line"; show; }
ok
grep -o "COMPOSE_FILE='.*'" "$WORK/out" >> "$WORK/ports/.env" || true
upd ports ok PATH="$WORK/stubs-ports:$WORK/stubs:$PATH" --
{ head_is ports "$c910" && grep -q '^up .* up -d --build COMPOSE_FILE=docker-compose.yml:mine.yml:docker-compose.domain.yml$' "$WORK/env.log"; } \
  || { fail "--update at the same tag, with the line it showed in .env, does not go on with it"; show; }
ok

# Which tree ran, and a floor on how many checks did (a few depend on the host), so a section that silently ran
# nothing can't pass.
TREE=$(git -c safe.directory='*' -C "$REPO" describe --always --dirty 2>&1) || TREE='?'
TREE="$REPO at $TREE"
if [ "$FAILS" != 0 ]; then echo "setup --update: $FAILS failure(s) ($TREE)" >&2; exit 1; fi
if [ "$CHECKS" -lt 80 ]; then echo "setup --update: only $CHECKS checks ran, expected at least 80 ($TREE)" >&2; exit 1; fi
echo "setup --update: all $CHECKS checks passed ($TREE)"
