#!/usr/bin/env bash
# `./setup.sh --update` against a fixture: a local "origin" repository built from this checkout's installer files, with
# release tags, and release manifests signed by a throwaway minisign key served by a curl stub.
#
# Usage: tests/setup-update/run.sh
#
# Checks that --update checks out the newest signed stable release tag (a detached HEAD) and then runs that release's
# setup.sh; skips a newest tag whose release has no published manifest, saying so; refuses a bad or foreign signature,
# a replayed manifest, a manifest naming another commit or none; refuses local changes, and going back from a newer
# checkout over an install unless --allow-downgrade; moves on from a detached release checkout (an annotated tag);
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

# Stubs: curl serves https://github.com/test/repo/releases/download/<tag>/<name> from $REL_DIR/<tag>/<name>; docker
# (and anything else that could install) fails.
mkdir -p "$WORK/stubs"
cat > "$WORK/stubs/curl" <<'EOF'
#!/bin/sh
env >> "$WORK_ENV_LOG"
out=''; url=''
while [ $# -gt 0 ]; do case "$1" in -o) out=$2; shift ;; https://*) url=$1 ;; esac; shift; done
case "$url" in https://github.com/test/repo/releases/download/*) ;; *) exit 22 ;; esac
f="$REL_DIR/${url#https://github.com/test/repo/releases/download/}"
[ -f "$f" ] && [ -n "$out" ] || exit 22
cp "$f" "$out"
EOF
# shellcheck disable=SC2016  # the stub's own variable
for c in docker apt-get systemctl mvn; do printf '#!/bin/sh\nenv >> "$WORK_ENV_LOG"\nexit 99\n' > "$WORK/stubs/$c"; done
chmod +x "$WORK/stubs"/*

# The fixture origin: v9.0.0, v9.1.0, a pre-release v9.2.0-rc1, then main ahead of them all.
minisign -G -W -p "$WORK/k.pub" -s "$WORK/k.key" >/dev/null
minisign -G -W -p "$WORK/x.pub" -s "$WORK/x.key" >/dev/null
O="$WORK/origin"
mkdir -p "$O/release"
cp -R -- "$REPO/setup.sh" "$REPO/quickstart.sh" "$REPO/install" "$REPO/docker-compose.release.yml" \
  "$REPO/docker-compose.domain.yml" "$O/"
cp -- "$WORK/k.pub" "$O/release/minisign.pub"
( cd "$O" && "${G[@]}" init -q && "${G[@]}" add -A && "${G[@]}" commit -qm r1 && "${G[@]}" tag -a -m r1 v9.0.0 \
  && echo 1 > note && "${G[@]}" add note && "${G[@]}" commit -qm r2 && "${G[@]}" tag v9.1.0 \
  && echo 2 > note && "${G[@]}" commit -qam rc && "${G[@]}" tag v9.2.0-rc1 \
  && echo 3 > note && "${G[@]}" commit -qam ahead )
c900=$(git -C "$O" rev-parse 'v9.0.0^{commit}'); c910=$(git -C "$O" rev-parse v9.1.0); cmain=$(git -C "$O" rev-parse main)
# A second origin whose newest tag, v9.1.1, has no published release yet.
"${G[@]}" clone -q "$O" "$WORK/origin2"; "${G[@]}" -C "$WORK/origin2" tag v9.1.1 "$cmain"

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
  RC=0; : > "$WORK/env.log"
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
# A newest tag whose release isn't published yet: skipped, saying so, for the newest that is.
clone fallback '' "$WORK/origin2"
upd fallback ok
{ head_is fallback "$c910" && grep -q 'v9.1.1 has no published signed manifest yet' "$WORK/out"; } \
  || { fail "a newest tag without a published release is not skipped for the newest signed one"; show; }
ok
# From a detached release checkout with an install: forward to the newest, no git pull involved.
clone detached v9.0.0; : > "$WORK/detached/.env"
upd detached ok
head_is detached "$c910" || { fail "--update from v9.0.0 (detached) did not move to v9.1.0"; show; }
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

if [ "$FAILS" != 0 ]; then echo "setup --update: $FAILS failure(s)" >&2; exit 1; fi
echo "setup --update: all $CHECKS checks passed"
