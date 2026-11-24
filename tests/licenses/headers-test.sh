#!/usr/bin/env bash
# scripts/licenses/headers.py against a fixture repository: a few "upstream" files listed in a manifest, changed and
# unchanged, with and without the upstream header, and an MDMesh file.
#
# Usage: tests/licenses/headers-test.sh
#
# Checks that `check` passes a clean tree and fails a changed file without the notice, a file that lost the upstream
# copyright line, a listed file that is gone, and an unlisted file carrying the upstream header; and that `mark` adds
# the notice under the copyright line, or as a comment at the top of a file that never had the header (after an XML
# declaration), and leaves a marked file alone. Needs: bash, git, python3.
set -euo pipefail
HERE=$(cd -P -- "$(dirname -- "$0")" && pwd)
REPO=$(cd -P -- "$HERE/../.." && pwd)
WORK=$(mktemp -d)
cleanup() { rm -r -- "$WORK"; }
trap cleanup EXIT
FAILS=0
fail() { FAILS=$((FAILS + 1)); printf 'FAIL: %s\n' "$*" >&2; }

cd "$WORK"
git init -q .
mkdir -p scripts/licenses docs/licenses src
cp "$REPO/scripts/licenses/headers.py" scripts/licenses/
ANCHOR='Copyright (C) 2019 Headwind Solutions LLC (http://h-sms.com)'
printf '/*\n * Headwind MDM\n *\n * %s\n *\n * Apache-2.0\n */\npackage a;\n' "$ANCHOR" > src/A.java
cp src/A.java src/B.java
printf 'angular.module("x");\n' > src/c.js
printf '<?xml version="1.0"?>\n<Context>\n</Context>\n' > src/context.xml
printf '{"a": 1}\n' > src/en.json
printf '#!/bin/bash\necho hi\n' > src/run.sh
{
  printf '# fixture\n'
  for f in src/A.java src/B.java; do printf '%s\t%s\tyes\t\n' "$f" "$(git hash-object "$f")"; done
  for f in src/c.js src/context.xml src/run.sh; do printf '%s\t%s\tno\t\n' "$f" "$(git hash-object "$f")"; done
  printf '%s\t%s\tno\tJSON has no comments\n' src/en.json "$(git hash-object src/en.json)"
} > docs/licenses/upstream-files.tsv
printf '/*\n * Copyright (C) 2026 the MDMesh contributors.\n */\npackage m;\n' > src/Mine.java
git add -A
git -c user.name=t -c user.email=t@example.com commit -qm fixture

H=scripts/licenses/headers.py
# expect_check WANT_RC PATTERN NAME: `check` exits WANT_RC and its output matches PATTERN.
expect_check() {
  local out rc=0
  out=$("$H" check 2>&1) || rc=$?
  [ "$rc" = "$1" ] || fail "$3: check exited $rc, wanted $1: $out"
  grep -qE -- "$2" <<< "$out" || fail "$3: check said: $out"
}

expect_check 0 'carries the notices it needs' 'clean tree'

printf '// changed\n' >> src/A.java
printf 'x;\n' >> src/c.js
printf '<!-- changed -->\n' >> src/context.xml
printf 'echo bye\n' >> src/run.sh
printf '{"a": 2}\n' > src/en.json
expect_check 1 'src/A.java: differs from upstream' 'changed headered file'
expect_check 1 'src/c.js: differs from upstream' 'changed headerless file'
if "$H" check 2>&1 | grep -q 'src/en.json'; then fail 'an exempt file was reported'; fi

"$H" mark src/A.java src/c.js src/context.xml src/run.sh > "$WORK/mark.out"
grep -qx ' \* Modified by the MDMesh contributors\.' src/A.java || fail "mark: no notice in A.java: $(cat src/A.java)"
[ "$(grep -n 'Modified by' src/A.java | cut -d: -f1)" = 5 ] || fail 'mark: the notice is not under the copyright line'
DERIVED='Derived from Headwind MDM (Apache-2.0); modified by the MDMesh contributors.'
[ "$(head -n 1 src/c.js)" = "// $DERIVED" ] || fail "mark: c.js starts with $(head -n 1 src/c.js)"
[ "$(sed -n 2p src/context.xml)" = "<!-- $DERIVED -->" ] || fail "mark: context.xml: $(cat src/context.xml)"
[ "$(sed -n 2p src/run.sh)" = "# $DERIVED" ] || fail "mark: run.sh: $(cat src/run.sh)"
expect_check 0 'carries the notices it needs' 'after mark'
"$H" mark src/A.java | grep -q 'already says' || fail 'mark: a marked file was marked again'
[ "$(grep -c 'Modified by' src/A.java)" = 1 ] || fail 'mark: the notice was added twice'

sed -i '/Headwind Solutions LLC/d' src/B.java
expect_check 1 'src/B.java: lost the upstream copyright line' 'header removed'
git checkout -q -- src/B.java

cp src/B.java src/Copied.java
expect_check 1 "src/Copied.java: carries the upstream copyright header but isn't a file kept" 'header copied'
rm src/Copied.java

rm src/c.js
expect_check 1 'lists src/c.js, which is gone' 'listed file deleted'

if [ "$FAILS" -ne 0 ]; then echo "$FAILS check(s) failed" >&2; exit 1; fi
echo "headers.py: all checks passed"
