#!/usr/bin/env bash
#
# Checks the JVM of a server built from docker/server.Dockerfile, as it runs (jcmd against the live process): its JDK is
# one without the AVX-512 Base64 decoder bug of CVE-2026-114938 (OpenJDK), or -XX:-UseBASE64Intrinsics is in effect,
# set on its command line. Prints the JDK version and the flag either way.
#
# Usage: scripts/server-jvm-check.sh --image IMAGE       start IMAGE through its own entrypoint, with JAVA_OPTS and
#                                                         CATALINA_OPTS set as a deployment's SERVER_JAVA_OPTS (and the
#                                                         dev stack) set them; no database (the JVM runs regardless).
#                                                         The container is removed afterwards.
#        scripts/server-jvm-check.sh --container NAME   a running server container (the dev stack's, say)
#
# Needs only a Docker daemon: no ports are published, and jcmd runs inside the container, as the server's user.
set -euo pipefail
HERE=$(cd -P -- "$(dirname -- "$0")" && pwd)
# shellcheck source=SCRIPTDIR/../install/lib/jdk.sh
. "$HERE/../install/lib/jdk.sh"

usage() { sed -n '/^# Usage:/,/^# Needs/p' "$0" | sed 's/^# \{0,1\}//' >&2; exit 2; }
[ $# -eq 2 ] || usage
NAME=""; STARTED=""
cleanup() { [ -z "$STARTED" ] || docker rm -f "$STARTED" > /dev/null 2>&1 || true; }
trap cleanup EXIT
case "$1" in
  --image)
    # Unique per run, so runs sharing one daemon never remove each other's container.
    NAME="mdmesh-server-jvm-check-${GITHUB_RUN_ID:-local}-$$-$(od -An -N4 -tx4 /dev/urandom | tr -d ' ')"
    STARTED=$NAME
    docker run -d --name "$NAME" --network none \
      -e BASE_URL=http://mdm.example.com -e HASH_SECRET=jvmcheck -e DB_PASSWORD=jvmcheck \
      -e JAVA_OPTS=-Xmx512m -e CATALINA_OPTS=-Dmdmesh.jvmcheck=1 "$2" > /dev/null ;;
  --container) NAME=$2 ;;
  *) usage ;;
esac

# jcmd finds the server by its main class (whatever its PID), as the user the entrypoint runs it as.
jc() { docker exec -u mdmesh "$NAME" jcmd org.apache.catalina.startup.Bootstrap "$@"; }
for _ in $(seq 1 60); do
  if out=$(jc VM.flags -all 2>&1) && grep -q 'UseBASE64Intrinsics' <<< "$out"; then break; fi
  [ "$(docker inspect -f '{{.State.Running}}' "$NAME" 2>/dev/null)" = true ] || break
  out=""; sleep 1
done
if [ -z "$out" ]; then
  echo "FAIL: could not reach the server's JVM in $NAME with jcmd" >&2
  docker logs --tail 30 "$NAME" >&2 2>&1 || true
  exit 1
fi
flag=$(grep -E '^[[:space:]]*bool[[:space:]]+UseBASE64Intrinsics[[:space:]]' <<< "$out" | head -n 1 | tr -s ' ')
version=$(jc VM.system_properties 2>/dev/null | sed -n 's/^java\.version=//p' | head -n 1)
echo "JDK ${version:-unknown}; flag:${flag:- missing}"

if [[ "$flag" =~ UseBASE64Intrinsics\ =\ false\ .*\{command\ line\} ]]; then
  echo "ok: -XX:-UseBASE64Intrinsics is in effect"
elif mdm_jdk17_base64_fixed "$version"; then
  echo "ok: JDK $version does not have the CVE-2026-114938 (OpenJDK) Base64 bug"
else
  echo "FAIL: JDK ${version:-unknown} has the CVE-2026-114938 (OpenJDK) Base64 bug and -XX:-UseBASE64Intrinsics is not set" >&2
  exit 1
fi
