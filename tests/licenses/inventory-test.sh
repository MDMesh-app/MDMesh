#!/usr/bin/env bash
# scripts/licenses/inventory.py against small made-up artifacts: a WAR, a console dist/ and an APK.
#
# Usage: tests/licenses/inventory-test.sh
#
# Checks that it passes artifacts that match the policy, and fails: a jar the WAR's list leaves out (but not a module
# of this repository, nor a classifier jar its list names), a listed jar the WAR doesn't hold, a missing NOTICE, a
# component under a licence the allowlist doesn't allow, and one offered under several licences with no recorded
# choice (a new version of an elected jar, say); that a recorded choice is used; and that a failed --write writes
# nothing. Needs: bash, python3.
set -euo pipefail
HERE=$(cd -P -- "$(dirname -- "$0")" && pwd)
REPO=$(cd -P -- "$HERE/../.." && pwd)
WORK=$(mktemp -d)
cleanup() { rm -r -- "$WORK"; }
trap cleanup EXIT
FAILS=0
fail() { FAILS=$((FAILS + 1)); printf 'FAIL: %s\n' "$*" >&2; }

# A copy of the script and the policy files it reads, in a repository of its own (it writes the inventory there).
mkdir -p "$WORK/repo/scripts/licenses" "$WORK/repo/docs/licenses/inventory"
cp "$REPO/scripts/licenses/inventory.py" "$WORK/repo/scripts/licenses/"
printf 'Apache-2.0\nMIT\nCDDL-1.1\n' > "$WORK/repo/docs/licenses/allowed.txt"
printf '# fixture\nweb\tspark-md5\t(WTFPL OR MIT)\tMIT\n' > "$WORK/repo/docs/licenses/elections.tsv"
INV="$WORK/repo/scripts/licenses/inventory.py"

# make_war LINES...: a WAR whose THIRD-PARTY.txt holds LINES; jars named by env JARS (name=group, group "-" = none).
make_war() {
  python3 - "$WORK/a.war" "$@" <<'EOF'
import io, os, sys, zipfile
with zipfile.ZipFile(sys.argv[1], "w") as z:
    for f in os.environ.get("META", "LICENSE NOTICE").split():
        z.writestr(f"META-INF/{f}", "x")
    z.writestr("META-INF/THIRD-PARTY.txt", "header\n\n" + "".join(f"    {l}\n" for l in sys.argv[2:]))
    for spec in os.environ["JARS"].split():
        name, group = spec.split("=")
        buf = io.BytesIO()
        with zipfile.ZipFile(buf, "w") as j:
            j.writestr(f"META-INF/maven/{group}/x/pom.xml" if group != "-" else "a.class", "x")
        z.writestr(f"WEB-INF/lib/{name}", buf.getvalue())
EOF
}
# run WANT_RC PATTERN NAME ARGS...: inventory.py ARGS exits WANT_RC, its output matches PATTERN.
run() {
  local want=$1 pattern=$2 name=$3 out rc=0
  shift 3
  out=$("$INV" "$@" 2>&1) || rc=$?
  [ "$rc" = "$want" ] || fail "$name: exited $rc, wanted $want: $out"
  grep -qE -- "$pattern" <<< "$out" || fail "$name: said: $out"
}

GOOD=('(Apache-2.0) Guava (com.google.guava:guava:33.0 - u)' '(CDDL-1.1) Jersey (org.glassfish:jersey:2.25.1 - u)'
      '(Apache-2.0) Netty (io.netty:netty-epoll:4.1 - u)')
JARS="guava-33.0.jar=- jersey-2.25.1.jar=- netty-epoll-4.1-linux-x86_64.jar=- common-0.1.0.jar=com.hmdm \
fork-1.0.jar=com.mdmesh.plugin" make_war "${GOOD[@]}"
"$INV" --write --war "$WORK/a.war" > "$WORK/out" || fail "write: $(cat "$WORK/out")"
run 0 '^$' 'matching WAR' --war "$WORK/a.war"
grep -qx 'org.glassfish:jersey	CDDL-1.1' "$WORK/repo/docs/licenses/inventory/server.tsv" || fail 'inventory row'

JARS="guava-33.0.jar=- jersey-2.25.1.jar=- netty-epoll-4.1-linux-x86_64.jar=- extra-1.0.jar=org.example" \
  make_war "${GOOD[@]}"
run 1 'extra-1.0.jar is not in META-INF/THIRD-PARTY.txt' 'unlisted jar' --war "$WORK/a.war"

JARS="guava-33.0.jar=- netty-epoll-4.1-linux-x86_64.jar=-" make_war "${GOOD[@]}"
run 1 "lists jersey-2.25.1.jar, which WEB-INF/lib doesn't hold" 'listed jar missing' --war "$WORK/a.war"

META=LICENSE JARS="guava-33.0.jar=- jersey-2.25.1.jar=- netty-epoll-4.1-linux-x86_64.jar=-" make_war "${GOOD[@]}"
run 1 'has no META-INF/NOTICE' 'no NOTICE' --war "$WORK/a.war"

# A bumped jar whose election (override file) no longer matches: the POM's two licences come back.
JARS="guava-33.0.jar=- jersey-2.26.jar=-" make_war '(Apache-2.0) Guava (com.google.guava:guava:33.0 - u)' \
  '(CDDL-1.1) (GPL2 w/ CPE) Jersey (org.glassfish:jersey:2.26 - u)'
run 1 'org.glassfish:jersey is offered under CDDL-1.1 or GPL2 w/ CPE.*override-THIRD-PARTY' 'no election' \
  --war "$WORK/a.war"

JARS="lgpl-1.0.jar=-" make_war '(LGPL-2.1-only) Lib (org.example:lgpl:1.0 - u)'
run 1 "org.example:lgpl is under LGPL-2.1-only, which docs/licenses/allowed.txt doesn't allow" 'not allowed' \
  --war "$WORK/a.war"

# The console: a recorded choice is used; an unrecorded one fails.
mkdir -p "$WORK/dist/licenses"
touch "$WORK/dist/licenses/LICENSE.txt" "$WORK/dist/licenses/NOTICE.txt"
printf '[%s,%s]' '{"name":"spark-md5","version":"3","identifier":"(WTFPL OR MIT)"}' \
  '{"name":"react","version":"19","identifier":"MIT"}' > "$WORK/dist/licenses/THIRD-PARTY.json"
"$INV" --write --web "$WORK/dist" > "$WORK/out" || fail "web write: $(cat "$WORK/out")"
grep -qx 'spark-md5	MIT' "$WORK/repo/docs/licenses/inventory/web.tsv" || fail 'web: the recorded choice is not used'
printf '[{"name":"dual","version":"1","identifier":"(MIT OR GPL-2.0-only)"}]' > "$WORK/dist/licenses/THIRD-PARTY.json"
run 1 'web: dual is offered under MIT or GPL-2.0-only.*elections.tsv' 'web: no election' --web "$WORK/dist"

# A failed check leaves the inventory alone.
run 1 'web: dual is offered' 'web: failed write' --write --web "$WORK/dist"
grep -qx 'spark-md5	MIT' "$WORK/repo/docs/licenses/inventory/web.tsv" || fail 'web: a failed --write changed the inventory'

# An expression with an AND fails whole, even with a choice recorded for it.
EXPR='(MIT OR Apache-2.0) AND (GPL-2.0-only OR GPL-3.0-only)'
for expr in "$EXPR" 'MIT AND Apache-2.0'; do
  printf 'web\tboth\t%s\tMIT\n' "$expr" > "$WORK/repo/docs/licenses/elections.tsv"
  printf '[{"name":"both","version":"1","identifier":"%s"}]' "$expr" > "$WORK/dist/licenses/THIRD-PARTY.json"
  run 1 'web: both is under .* AND .*allow' "web: $expr" --web "$WORK/dist"
done

# A recorded choice must be one of the licences offered.
printf '# fixture\nweb\tdual\t(GPL-2.0-only OR GPL-3.0-only)\tMIT\n' > "$WORK/repo/docs/licenses/elections.tsv"
printf '[{"name":"dual","version":"1","identifier":"(GPL-2.0-only OR GPL-3.0-only)"}]' \
  > "$WORK/dist/licenses/THIRD-PARTY.json"
run 1 "chooses MIT for dual, which isn't one of the licences it is offered under" 'web: election not offered' \
  --web "$WORK/dist"

if [ "$FAILS" -ne 0 ]; then echo "$FAILS check(s) failed" >&2; exit 1; fi
echo "inventory.py: all checks passed"
