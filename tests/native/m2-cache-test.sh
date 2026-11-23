#!/usr/bin/env bash
# The T2 rig's Maven repository seed and save (m2_seed, m2_save_or_warn in tests/native/lib/host.sh), against a docker
# stub: the "container" is a directory, `docker exec CTR mkdir|rm` and `docker cp` act on it, and switches make a copy
# fail halfway. Each case runs the real host.sh functions in a bash of its own under `set -euo pipefail`, as run.sh
# does, then checks the cache directory, its marker (<dir>.t2-saved, which CI's upload waits for), the scratch
# siblings and the log.
#
# Usage: tests/native/m2-cache-test.sh
#   M2_TEST_HOST_SH=<file>  test another copy of host.sh (to check that the test catches a broken one)
#
# Needs bash and coreutils/findutils; no docker, no network, no root. Everything happens in a temporary directory.
# As root, the read-only case drops the capabilities that let root write anywhere (setpriv), or is skipped.
set -euo pipefail
unset CDPATH
HERE=$(cd -P -- "$(dirname -- "$0")" && pwd)
HOST_SH=${M2_TEST_HOST_SH:-$HERE/lib/host.sh}
MIN_CHECKS=55   # fewer means a case stopped checking: fail rather than pass on less (63 run; 59 with a skip)
export LC_ALL=C

WORK=$(mktemp -d)
cleanup() { chmod -R u+rwX "$WORK" || true; rm -rf -- "$WORK"; }
trap cleanup EXIT
FAILS=0; CHECKS=0; SKIPS=0
fail() { FAILS=$((FAILS + 1)); printf 'FAIL [%s]: %s\n' "$CASE" "$*" >&2; }
ok() { CHECKS=$((CHECKS + 1)); }
expect() {   # expect WHAT GOT WANT
  if [ "$2" = "$3" ]; then ok; else fail "$1: got '$2', want '$3'"; fi
}

# The docker stub. Container paths (ctr:/p, or /p after `exec ctr`) live under $STUB_GUEST. Every call is logged.
# STUB_CP_FAIL=in|out: a copy into / out of the container writes one truncated file, then fails (a disk filling up).
# STUB_RM_FAIL=1: `docker exec ctr rm` fails.
mkdir -p "$WORK/bin"
cat > "$WORK/bin/docker" <<'EOF'
#!/usr/bin/env bash
set -u
printf '%s\n' "$*" >> "$STUB_CALLS"
case "$1" in
  exec)
    case "$3" in
      mkdir) mkdir -p "$STUB_GUEST$5" ;;
      rm) [ "${STUB_RM_FAIL:-0}" != 1 ] || exit 1; rm -rf "$STUB_GUEST$5" ;;
      *) echo "docker stub: unexpected exec $*" >&2; exit 1 ;;
    esac ;;
  cp)
    src=$2 dst=$3
    case "$src" in ctr:*) dir=out; src=$STUB_GUEST${src#ctr:} ;; *) dir=in ;; esac
    case "$dst" in ctr:*) dst=$STUB_GUEST${dst#ctr:} ;; esac
    if [ "${STUB_CP_FAIL:-}" = "$dir" ]; then echo TRUNC > "$dst/truncated.jar"; echo "no space left" >&2; exit 1; fi
    cp -a "$src" "$dst" ;;
  *) echo "docker stub: unexpected $*" >&2; exit 1 ;;
esac
EOF
# find: the real one, unless STUB_FIND_FAIL=1 makes its -delete (the cleanup) fail.
REAL_FIND=$(command -v find)
cat > "$WORK/bin/find" <<EOF
#!/usr/bin/env bash
if [ "\${STUB_FIND_FAIL:-0}" = 1 ]; then
  for a in "\$@"; do [ "\$a" != -delete ] || { echo "find: I/O error" >&2; exit 1; }; done
fi
exec "$REAL_FIND" "\$@"
EOF
chmod +x "$WORK/bin/docker" "$WORK/bin/find"

# One case's bash: the rig's globals, the real host.sh, then the calls run.sh and start_container make. The guest's
# build leaves y.jar and the junk a resolver leaves behind, in a read-only directory; GUEST_BUILD=none leaves nothing.
cat > "$WORK/case.sh" <<'EOF'
set -euo pipefail
OUT=$CASE_DIR/out; LOG_TAG=$CASE; CTR=ctr
mkdir -p "$OUT"
# shellcheck disable=SC1090  # the host.sh under test
. "$HOST_SH"
m2_seed
find "$STUB_GUEST" -type f | sed "s#^$STUB_GUEST##" | sort > "$CASE_DIR/guest-after-seed"
if [ "${GUEST_BUILD:-jar}" = jar ]; then
  r=$STUB_GUEST/root/.m2/repository/org/b/1.0
  mkdir -p "$r"; echo built > "$r/b-1.0.jar"
  touch "$r/b-1.0.pom.lastUpdated" "$r/b-1.0.jar.part" "$r/b-1.0.jar.part.lock" "$r/b-1.0.jar.8812345678901234567.tmp" \
        "$r/resolver-status.properties"
  chmod 555 "$r"
fi
m2_save_or_warn
echo "REACHED_END"
EOF

# run_case NAME [VAR=value...]: a fresh cache dir holding org/a/1.0/a-1.0.jar (unless SEED=none or SEED=old-only, the
# latter leaving it only as <dir>.t2-old), the case's bash with the variables, and its rc/log/cache/siblings in globals.
run_case() {
  CASE=$1; shift
  CASE_DIR=$WORK/$CASE; CACHE=$CASE_DIR/cache
  mkdir -p "$CASE_DIR/guest"
  local seed=present v
  for v in "$@"; do case "$v" in SEED=*) seed=${v#SEED=} ;; esac; done
  case "$seed" in
    present)  mkdir -p "$CACHE/org/a/1.0"; echo good > "$CACHE/org/a/1.0/a-1.0.jar" ;;
    old-only) mkdir -p "$CACHE.t2-old/org/a/1.0"; echo good > "$CACHE.t2-old/org/a/1.0/a-1.0.jar" ;;
  esac
  : > "$CASE_DIR/calls"
  RC=0
  # shellcheck disable=SC2086  # CASE_PREFIX is a command and its words
  env PATH="$WORK/bin:$PATH" HOST_SH="$HOST_SH" CASE="$CASE" CASE_DIR="$CASE_DIR" STUB_GUEST="$CASE_DIR/guest" \
      STUB_CALLS="$CASE_DIR/calls" T2_M2_REPO="$CACHE" "$@" ${CASE_PREFIX:-} \
      bash "$WORK/case.sh" > "$CASE_DIR/log" 2>&1 \
    || RC=$?
  chmod -R u+rwX "$CASE_DIR/guest"
  LOG=$(cat "$CASE_DIR/log")
}
# The cache's files with their contents, the marker, the scratch siblings.
cache_files() {
  [ -d "$CACHE" ] || { echo ABSENT; return; }
  (cd "$CACHE" && find . -type f -exec sh -c 'printf "%s=%s\n" "$1" "$(cat "$1")"' _ {} \; | sort | tr '\n' ' ')
}
marker() { [ -e "$CACHE.t2-saved" ] && echo yes || echo no; }
siblings() { (cd "$CASE_DIR" && for s in cache.t2-new cache.t2-old; do [ ! -e "$s" ] || printf '%s ' "$s"; done); }
logs() { case "$LOG" in *"$1"*) echo yes ;; *) echo no ;; esac; }
BOTH="./org/a/1.0/a-1.0.jar=good ./org/b/1.0/b-1.0.jar=built "
OLD="./org/a/1.0/a-1.0.jar=good "
show_log() { [ "$FAILS" = "$1" ] || sed 's/^/    | /' "$CASE_DIR/log" >&2; }

# 1. Seed, build, save: the guest gets the cache; the cache becomes the guest's repository, without the junk (records
#    of failed lookups, Maven 3.8's *.part / *.part.lock, Maven 3.9's <file>.<n>.tmp), out of a read-only directory.
f0=$FAILS; run_case good
expect "rc" "$RC" 0
expect "the guest was seeded" "$(cat "$CASE_DIR/guest-after-seed")" /root/.m2/repository/org/a/1.0/a-1.0.jar
expect "the cache is the guest's repository, junk dropped" "$(cache_files)" "$BOTH"
expect "marker" "$(marker)" yes
expect "no scratch siblings" "$(siblings)" ""
expect "logs the save" "$(logs 'Maven repository saved to')" yes
expect "the run goes on" "$(logs REACHED_END)" yes
show_log "$f0"

# 2. The seed's copy fails halfway: the guest's half-copied repository is cleared, the run goes on cold, and saves.
f0=$FAILS; run_case seed-fails STUB_CP_FAIL=in
expect "rc" "$RC" 0
expect "the half-copied guest repository is cleared" "$(cat "$CASE_DIR/guest-after-seed")" ""
expect "warns" "$(logs 'WARN could not seed the Maven repository')" yes
expect "the cache is the cold build's repository" "$(cache_files)" "./org/b/1.0/b-1.0.jar=built "
expect "marker" "$(marker)" yes
expect "no scratch siblings" "$(siblings)" ""
expect "the run goes on" "$(logs REACHED_END)" yes
show_log "$f0"

# 3. The seed fails and the half-copied repository cannot be cleared: the run stops (it must not build on it).
f0=$FAILS; run_case seed-and-clear-fail STUB_CP_FAIL=in STUB_RM_FAIL=1
expect "rc is not 0" "$([ "$RC" != 0 ] && echo yes || echo no)" yes
expect "says why" "$(logs 'nor clear the partial copy')" yes
expect "the cache is untouched" "$(cache_files)" "$OLD"
expect "marker" "$(marker)" no
show_log "$f0"

# 4. The copy-out fails halfway: the cache stays as it was (no truncated file), no marker, a WARN, the run goes on.
f0=$FAILS; run_case copy-out-fails STUB_CP_FAIL=out
expect "rc" "$RC" 0
expect "the cache is untouched" "$(cache_files)" "$OLD"
expect "marker" "$(marker)" no
expect "no scratch siblings" "$(siblings)" ""
expect "warns" "$(logs 'WARN the Maven repository was not saved')" yes
expect "does not claim a save" "$(logs 'Maven repository saved to')" no
expect "the run goes on" "$(logs REACHED_END)" yes
show_log "$f0"

# 5. The cleanup fails: the same.
f0=$FAILS; run_case cleanup-fails STUB_FIND_FAIL=1
expect "rc" "$RC" 0
expect "the cache is untouched" "$(cache_files)" "$OLD"
expect "marker" "$(marker)" no
expect "no scratch siblings" "$(siblings)" ""
expect "warns" "$(logs 'WARN the Maven repository was not saved')" yes
expect "does not claim a save" "$(logs 'Maven repository saved to')" no
expect "the run goes on" "$(logs REACHED_END)" yes
show_log "$f0"

# 6. A cache miss, and the build left no jars in /root/.m2 (Maven used another repository): nothing is saved.
f0=$FAILS; run_case no-jars SEED=none GUEST_BUILD=none
expect "rc" "$RC" 0
expect "the cache stays empty" "$(cache_files)" ""
expect "marker" "$(marker)" no
expect "no scratch siblings" "$(siblings)" ""
expect "warns" "$(logs 'holds no jars')" yes
expect "the run goes on" "$(logs REACHED_END)" yes
show_log "$f0"

# 7. An earlier save was interrupted between its renames (only <dir>.t2-old is left): the seed takes it back.
f0=$FAILS; run_case interrupted-swap SEED=old-only
expect "rc" "$RC" 0
expect "the guest was seeded from the recovered repository" "$(cat "$CASE_DIR/guest-after-seed")" \
  /root/.m2/repository/org/a/1.0/a-1.0.jar
expect "the cache is the guest's repository" "$(cache_files)" "$BOTH"
expect "marker" "$(marker)" yes
expect "no scratch siblings" "$(siblings)" ""
show_log "$f0"

# 8. T2_M2_SAVE=0 (CI: an exact cache hit): seeded, never saved, and a stale marker is removed.
f0=$FAILS; mkdir -p "$WORK/save-off"; touch "$WORK/save-off/cache.t2-saved"
run_case save-off T2_M2_SAVE=0
expect "rc" "$RC" 0
expect "the guest was seeded" "$(cat "$CASE_DIR/guest-after-seed")" /root/.m2/repository/org/a/1.0/a-1.0.jar
expect "the cache is untouched" "$(cache_files)" "$OLD"
expect "marker (a stale one is removed)" "$(marker)" no
expect "nothing copied out" "$(grep -c '^cp ctr:' "$CASE_DIR/calls" || true)" 0
expect "the run goes on" "$(logs REACHED_END)" yes
show_log "$f0"

# 9. No cache directory yet (a cache miss): an empty seed, then a save.
f0=$FAILS; run_case cache-miss SEED=none
expect "rc" "$RC" 0
expect "the guest got nothing" "$(cat "$CASE_DIR/guest-after-seed")" ""
expect "the cache is the build's repository" "$(cache_files)" "./org/b/1.0/b-1.0.jar=built "
expect "marker" "$(marker)" yes
show_log "$f0"

# 10. T2_M2_REPO unset or empty: nothing at all happens.
for v in unset empty; do
  f0=$FAILS; CASE=repo-$v; CASE_DIR=$WORK/$CASE; mkdir -p "$CASE_DIR/guest"; : > "$CASE_DIR/calls"; RC=0
  if [ "$v" = unset ]; then set -- -u T2_M2_REPO; else set -- T2_M2_REPO=; fi
  env "$@" PATH="$WORK/bin:$PATH" HOST_SH="$HOST_SH" CASE="$CASE" CASE_DIR="$CASE_DIR" STUB_GUEST="$CASE_DIR/guest" \
      STUB_CALLS="$CASE_DIR/calls" bash "$WORK/case.sh" > "$CASE_DIR/log" 2>&1 || RC=$?
  chmod -R u+rwX "$CASE_DIR/guest"; LOG=$(cat "$CASE_DIR/log")
  expect "rc" "$RC" 0
  expect "no docker calls" "$(wc -l < "$CASE_DIR/calls" | tr -d ' ')" 0
  expect "the run goes on" "$(logs REACHED_END)" yes
  show_log "$f0"
done

# 11. A read-only scratch copy left by an earlier run (<dir>.t2-new with a 0555 directory) does not block the save.
#     Only meaningful without root's power to write anywhere: as root, drop it with setpriv, or skip.
CASE=read-only-leftover; CASE_PREFIX=""
if [ "$(id -u)" = 0 ]; then
  if command -v setpriv > "$WORK/setpriv.path" 2>&1; then
    CASE_PREFIX="setpriv --bounding-set=-dac_override,-dac_read_search,-fowner --"
  else
    CASE_PREFIX=skip
  fi
fi
if [ "$CASE_PREFIX" = skip ]; then
  SKIPS=$((SKIPS + 1)); echo "SKIP [$CASE]: running as root without setpriv" >&2
else
  f0=$FAILS; ro=$WORK/$CASE/cache.t2-new/ro
  mkdir -p "$ro"; touch "$ro/f"; chmod 555 "$ro"
  run_case "$CASE"
  expect "rc" "$RC" 0
  expect "the cache is the guest's repository" "$(cache_files)" "$BOTH"
  expect "marker" "$(marker)" yes
  expect "no scratch siblings" "$(siblings)" ""
  show_log "$f0"
fi
CASE_PREFIX=""

CASE=total
if [ "$FAILS" != 0 ]; then echo "T2 Maven cache: $FAILS failure(s)" >&2; exit 1; fi
[ "$CHECKS" -ge "$MIN_CHECKS" ] \
  || { echo "T2 Maven cache: only $CHECKS checks ran (want at least $MIN_CHECKS)" >&2; exit 1; }
echo "T2 Maven cache: all $CHECKS checks passed$([ "$SKIPS" = 0 ] || echo " ($SKIPS case(s) skipped)")"
