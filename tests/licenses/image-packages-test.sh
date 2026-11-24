#!/usr/bin/env bash
# scripts/licenses/image-packages.sh against a stub docker that prints what an image's package manager would.
#
# Usage: tests/licenses/image-packages-test.sh
#
# Checks that Alpine's `apk list -I` lines become five columns, including a licence with parentheses of its own, that
# dpkg-query's lines and the runtime comments pass through, and that a line that is neither fails the list. Needs:
# bash, awk, sed. No Docker.
set -euo pipefail
HERE=$(cd -P -- "$(dirname -- "$0")" && pwd)
REPO=$(cd -P -- "$HERE/../.." && pwd)
WORK=$(mktemp -d)
cleanup() { rm -r -- "$WORK"; }
trap cleanup EXIT
FAILS=0
fail() { FAILS=$((FAILS + 1)); printf 'FAIL: %s\n' "$*" >&2; }

# The stub prints $STUB_OUT instead of running anything.
mkdir -p "$WORK/bin"
# shellcheck disable=SC2016  # expanded by the stub
printf '#!/bin/sh\ncat "$STUB_OUT"\n' > "$WORK/bin/docker"
chmod +x "$WORK/bin/docker"
SCRIPT="$REPO/scripts/licenses/image-packages.sh"

T=$'\t'
cat > "$WORK/apk.txt" <<EOF
busybox-1.37.0-r31 x86_64 {busybox} (GPL-2.0-only) [installed]
libcap2-2.78-r0 aarch64 {libcap} (BSD-3-Clause OR GPL-2.0-only) [installed]
nested-1.0-r2 x86_64 {nested} (MIT AND (GPL-2.0-or-later OR LGPL-3.0-only)) [installed]
bsdutils${T}1:2.41.3-3ubuntu2.2${T}util-linux${T}2.41.3-3ubuntu2.2${T}-
#
# runtime: Node.js v24.21.0
EOF
out=$(STUB_OUT="$WORK/apk.txt" PATH="$WORK/bin:$PATH" "$SCRIPT" example/image@sha256:abc) || fail "exited $?: $out"
grep -qx "busybox${T}1.37.0-r31${T}busybox${T}1.37.0-r31${T}GPL-2.0-only" <<< "$out" || fail "busybox: $out"
grep -qx "libcap2${T}2.78-r0${T}libcap${T}2.78-r0${T}BSD-3-Clause OR GPL-2.0-only" <<< "$out" || fail "libcap2: $out"
grep -qx "nested${T}1.0-r2${T}nested${T}1.0-r2${T}MIT AND (GPL-2.0-or-later OR LGPL-3.0-only)" <<< "$out" ||
  fail "nested licence: $out"
grep -qx "bsdutils${T}1:2.41.3-3ubuntu2.2${T}util-linux${T}2.41.3-3ubuntu2.2${T}-" <<< "$out" || fail "dpkg: $out"
grep -qx '# runtime: Node.js v24.21.0' <<< "$out" || fail "runtime: $out"

printf 'WARNING: something the converter does not know\n' > "$WORK/bad.txt"
if STUB_OUT="$WORK/bad.txt" PATH="$WORK/bin:$PATH" "$SCRIPT" example/image@sha256:abc > "$WORK/out" 2> "$WORK/err"; then
  fail 'a line that is not a package passed'
fi
grep -q 'not a package line: WARNING' "$WORK/err" || fail "no message: $(cat "$WORK/err")"

if [ "$FAILS" -ne 0 ]; then echo "$FAILS check(s) failed" >&2; exit 1; fi
echo "image-packages.sh: all checks passed"
