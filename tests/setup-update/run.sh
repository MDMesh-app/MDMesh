#!/usr/bin/env bash
# `./setup.sh --update` against a fixture: a local "origin" repository built from this checkout's installer files, with
# release tags, and release manifests signed by a throwaway minisign key served by a curl stub.
#
# Usage: tests/setup-update/run.sh
#
# Checks that --update checks out the newest signed stable release tag (a detached HEAD) and then runs that release's
# setup.sh; refuses an unsigned release, a bad or foreign signature, a replayed manifest, a manifest naming another
# commit (or, without a commit, other files); refuses local changes, and going back from a newer checkout over an
# install unless --allow-downgrade; moves on from a detached release checkout; and that MDMESH_DRY_RUN=1 changes nothing.
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
out=''; url=''
while [ $# -gt 0 ]; do case "$1" in -o) out=$2; shift ;; https://*) url=$1 ;; esac; shift; done
case "$url" in https://github.com/test/repo/releases/download/*) ;; *) exit 22 ;; esac
f="$REL_DIR/${url#https://github.com/test/repo/releases/download/}"
[ -f "$f" ] && [ -n "$out" ] || exit 22
cp "$f" "$out"
EOF
for c in docker apt-get systemctl mvn; do printf '#!/bin/sh\nexit 99\n' > "$WORK/stubs/$c"; done
chmod +x "$WORK/stubs"/*

# The fixture origin: v9.0.0, v9.1.0, a pre-release v9.2.0-rc1, then main ahead of them all.
minisign -G -W -p "$WORK/k.pub" -s "$WORK/k.key" >/dev/null
minisign -G -W -p "$WORK/x.pub" -s "$WORK/x.key" >/dev/null
O="$WORK/origin"
mkdir -p "$O/release"
cp -R -- "$REPO/setup.sh" "$REPO/quickstart.sh" "$REPO/install" "$REPO/docker-compose.release.yml" \
  "$REPO/docker-compose.domain.yml" "$O/"
cp -- "$WORK/k.pub" "$O/release/minisign.pub"
( cd "$O" && "${G[@]}" init -q && "${G[@]}" add -A && "${G[@]}" commit -qm r1 && "${G[@]}" tag v9.0.0 \
  && echo 1 > note && "${G[@]}" add note && "${G[@]}" commit -qm r2 && "${G[@]}" tag v9.1.0 \
  && echo 2 > note && "${G[@]}" commit -qam rc && "${G[@]}" tag v9.2.0-rc1 \
  && echo 3 > note && "${G[@]}" commit -qam ahead )
c900=$(git -C "$O" rev-parse v9.0.0); c910=$(git -C "$O" rev-parse v9.1.0); cmain=$(git -C "$O" rev-parse main)

# manifest DIR TAG VERSION COMMIT KEY [files|badfiles]: a signed manifest for TAG in DIR/TAG/.
manifest() {
  local dir=$1/$2; mkdir -p "$dir"
  python3 - "$O" "$3" "$4" "${6:-}" "$2" > "$dir/manifest.json" <<'PY'
import sys, json, hashlib, subprocess
o, ver, commit, mode, tag = sys.argv[1:6]
m = {"version": ver, "channel": "stable"}
if commit: m["commit"] = commit
if mode:
    fs = ["docker-compose.release.yml", "docker-compose.domain.yml", "install/sql/hmdm_init.en.sql",
          "install/sql/post_seed.sql", "install/lib/db.sh"]
    fs.append("quickstart.sh")
    m["files"] = {f: hashlib.sha256(subprocess.run(["git", "-C", o, "show", tag + ":" + f], capture_output=True).stdout).hexdigest() for f in fs}
    if mode == "badfiles": m["files"]["install/lib/db.sh"] = "0" * 64
    if mode == "badqs": m["files"]["quickstart.sh"] = "0" * 64
print(json.dumps(m, indent=2))
PY
  minisign -S -s "$5" -m "$dir/manifest.json" >/dev/null
}
R="$WORK/rel"
manifest "$R/ok" v9.1.0 9.1.0 "$c910" "$WORK/k.key"
manifest "$R/ok" v9.0.0 9.0.0 "$c900" "$WORK/k.key"
manifest "$R/files" v9.1.0 9.1.0 '' "$WORK/k.key" files
manifest "$R/badfiles" v9.1.0 9.1.0 '' "$WORK/k.key" badfiles
manifest "$R/badqs" v9.1.0 9.1.0 '' "$WORK/k.key" badqs
manifest "$R/nobind" v9.1.0 9.1.0 '' "$WORK/k.key"
manifest "$R/wrongcommit" v9.1.0 9.1.0 "$c900" "$WORK/k.key"
manifest "$R/otherkey" v9.1.0 9.1.0 "$c910" "$WORK/x.key"
mkdir -p "$R/nosig/v9.1.0" "$R/replay/v9.1.0" "$R/tampered/v9.1.0"
cp "$R/ok/v9.1.0/manifest.json" "$R/nosig/v9.1.0/"
cp "$R/ok/v9.0.0/manifest.json" "$R/ok/v9.0.0/manifest.json.minisig" "$R/replay/v9.1.0/"
cp "$R/ok/v9.1.0/manifest.json" "$R/ok/v9.1.0/manifest.json.minisig" "$R/tampered/v9.1.0/"
sed -i 's/"channel": "stable"/"channel": "beta"/' "$R/tampered/v9.1.0/manifest.json"

# clone NAME [REF]: a fresh clone of the fixture origin, on main or detached at REF.
clone() {
  "${G[@]}" clone -q "$O" "$WORK/$1"
  [ -z "${2:-}" ] || "${G[@]}" -C "$WORK/$1" checkout -q --detach "$2"
}
# upd NAME RELEASES [NAME=VALUE...] -- [ARGS...]: ./setup.sh --update ARGS in clone NAME, the releases served from
# $R/RELEASES. Sets RC; output in $WORK/out.
upd() {
  local name=$1 rel=$2; shift 2
  local envs=(); while [ "$#" -gt 0 ] && [ "$1" != -- ]; do envs+=("$1"); shift; done; [ "$#" = 0 ] || shift
  RC=0
  (cd "$WORK/$name" && env -i PATH="$WORK/stubs:$PATH" HOME="$WORK" GITHUB_REPO=test/repo REL_DIR="$R/$rel" \
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
# Dry run: verifies and says what it would do; the clone doesn't move.
clone dry
upd dry ok MDMESH_DRY_RUN=1 --
{ [ "$RC" = 0 ] && head_is dry "$cmain" && grep -q 'v9.1.0 verifies' "$WORK/out"; } || { fail "MDMESH_DRY_RUN=1 --update changed the clone or failed"; show; }
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
# A manifest without its commit is checked by the files it lists.
clone files
upd files files
head_is files "$c910" || { fail "a manifest whose files match the tag is refused"; show; }
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
for c in nosig:"Could not download the signed manifest" otherkey:"is not signed by the release key" \
         tampered:"is not signed by the release key" replay:"not the one of v9.1.0" \
         wrongcommit:"its signed manifest names commit" badfiles:"does not match the signed manifest" \
         badqs:"quickstart.sh at v9.1.0 does not match" \
         nobind:"records neither its commit nor its files"; do
  clone "bad-${c%%:*}"
  expect_refused "bad-${c%%:*}" "${c%%:*}" "a release with ${c%%:*}" "${c#*:}"
done

if [ "$FAILS" != 0 ]; then echo "setup --update: $FAILS failure(s)" >&2; exit 1; fi
echo "setup --update: all $CHECKS checks passed"
