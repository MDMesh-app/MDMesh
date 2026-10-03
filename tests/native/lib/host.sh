#!/usr/bin/env bash
# T2 native rig, host side: container lifecycle, the reusable check step and the continuity checks. Sourced by
# tests/native/run.sh; reads its globals (REPO, HERE, OUT, NAME, DISTRO, ...). Every docker command here acts only on
# names this run created (mdmesh-t2-<distro>-<scenario>-<rand>[...]).

T2_HTTP_PORT=${T2_HTTP_PORT:-9090}
# The public URL the installer is given. A name, not the container's IP: the killed scenario starts one container per
# kill point from a snapshot, and each gets a new IP. The rig itself talks to http://<container ip>:<port>.
T2_BASE_URL=${T2_BASE_URL:-http://mdm.t2.test:${T2_HTTP_PORT}}

log()  { printf '[t2 %s %s] %s\n' "$(date +%H:%M:%S)" "${LOG_TAG:-}" "$*" | tee -a "$OUT/t2.log" >&2; }
die()  { log "FATAL: $*"; exit 1; }

# --- results ---------------------------------------------------------------------------------------------------------
# check NAME GOT WANT: one line PASS/FAIL into the log and CHECK_FAILS.
CHECK_FAILS=0
check() {
  if [ "$2" = "$3" ]; then log "  PASS $1"
  else log "  FAIL $1 (got '$2', want '$3')"; CHECK_FAILS=$((CHECK_FAILS + 1)); fi
}
result() {   # result POINT VERDICT DETAIL — one row of $OUT/results.tsv
  printf '%s\t%s\t%s\t%s\t%s\n' "$DISTRO" "$SCENARIO" "$1" "$2" "$3" >> "$OUT/results.tsv"
}

# --- image + containers ----------------------------------------------------------------------------------------------
# Pinned workaround downloads (tests/native/Dockerfile, layer 3). Each is a finding; see docs/DEV.md "T2 native rig".
NODE_URL=https://nodejs.org/dist/v22.23.3/node-v22.23.3-linux-x64.tar.xz
NODE_SHA256=df450af89261115ef9f9e3830c3eeb2cc9213b63c720b1af623cb5dcbe2e02de
JDK17_URL=https://github.com/adoptium/temurin17-binaries/releases/download/jdk-17.0.20.1%2B1/OpenJDK17U-jdk_x64_linux_hotspot_17.0.20.1_1.tar.gz
JDK17_SHA256=3808d1d15e3ec6bd5b84057fb5d84c33d8a1536a258146bcea2e603fc726e08e

# distro_setup DISTRO: BASE_IMAGE, and the workaround build args (none with T2_STRICT=1).
distro_setup() {
  BUILD_ARGS=()
  case "$1" in
    debian-12)    BASE_IMAGE=debian:12 ;;
    debian-13)    BASE_IMAGE=debian:13 ;;
    ubuntu-24.04) BASE_IMAGE=ubuntu:24.04 ;;
    *) die "unknown distro '$1' (debian-12, debian-13, ubuntu-24.04)" ;;
  esac
  [ "${T2_STRICT:-0}" = 1 ] && return 0
  case "$1" in
    debian-12|ubuntu-24.04) BUILD_ARGS+=(--build-arg "T2_NODE_URL=$NODE_URL" --build-arg "T2_NODE_SHA256=$NODE_SHA256") ;;
    debian-13)              BUILD_ARGS+=(--build-arg "T2_JDK17_URL=$JDK17_URL" --build-arg "T2_JDK17_SHA256=$JDK17_SHA256") ;;
  esac
}

build_image() {
  # shellcheck disable=SC2153  # IMAGE is run.sh's
  log "building $IMAGE from $BASE_IMAGE${BUILD_ARGS[*]:+ (with workarounds)}"
  docker build -q -t "$IMAGE" --build-arg "BASE_IMAGE=$BASE_IMAGE" "${BUILD_ARGS[@]}" "$HERE" >> "$OUT/t2.log" 2>&1 \
    || die "image build failed (see $OUT/t2.log)"
}

# Free space guard for the Docker root (each container needs a few GB: JDK, Postgres, Maven and npm caches, the build).
disk_guard() {
  local root free
  root=$(docker info --format '{{.DockerRootDir}}' 2>/dev/null) || return 0
  free=$(df -Pk "$root" 2>/dev/null | awk 'NR==2 {print int($4/1048576)}') || return 0
  [ -z "$free" ] || [ "$free" -ge "${T2_MIN_FREE_GB:-8}" ] \
    || die "only ${free} GB free under $root (T2_MIN_FREE_GB=${T2_MIN_FREE_GB:-8}); not starting a container"
}

# start_container NAME IMAGE: a privileged systemd container with its own network namespace (Docker's default bridge).
# Privileged + a private cgroup namespace is what systemd as PID 1 needs on cgroup v2; no host path is mounted. The
# optional cache volumes (T2_CACHE=1) are named Docker volumes, never host directories.
start_container() {
  local name=$1 image=$2 i
  local -a extra=()
  if [ "${T2_CACHE:-0}" = 1 ]; then
    extra+=(-v mdmesh-t2-cache-m2:/root/.m2 -v mdmesh-t2-cache-npm:/root/.npm)
  fi
  disk_guard
  CONTAINERS+=("$name")
  docker run -d --name "$name" --hostname "t2-${DISTRO}" --label mdmesh-t2=1 \
    --privileged --cgroupns=private --tmpfs /run --tmpfs /run/lock \
    --memory "${T2_MEMORY:-6g}" "${extra[@]}" "$image" > /dev/null
  CTR=$name
  for i in $(seq 1 60); do
    case "$(docker exec "$CTR" systemctl is-system-running 2>/dev/null || true)" in
      running|degraded) break ;;
    esac
    sleep 2
  done
  [ "$i" -lt 60 ] || die "systemd in $CTR did not come up"
  IP=$(docker inspect -f '{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}' "$CTR")
  [ -n "$IP" ] || die "no IP for $CTR"
  API="http://$IP:$T2_HTTP_PORT"
  docker exec "$CTR" mkdir -p /t2/out
  docker cp "$HERE/lib/guest.sh" "$CTR:/t2/guest.sh" > /dev/null
  log "container $CTR up ($IP; $(docker exec "$CTR" sh -c '. /etc/os-release; echo "$PRETTY_NAME"'))"
}

remove_container() {
  local name=$1
  docker rm -f "$name" > /dev/null 2>&1 || true
}

guest() { docker exec -e T2_BASE_URL="$T2_BASE_URL" -e T2_HTTP_PORT="$T2_HTTP_PORT" "$CTR" bash /t2/guest.sh "$@"; }

# collect LABEL: the guest's logs, journals and state into $OUT/<LABEL>/.
collect() {
  local d="$OUT/$1"
  mkdir -p "$d"
  docker exec "$CTR" bash /t2/guest.sh collect "/t2/collect" > /dev/null 2>&1 || true
  docker cp "$CTR:/t2/collect/." "$d/" > /dev/null 2>&1 || true
  docker exec "$CTR" rm -rf /t2/collect > /dev/null 2>&1 || true
  log "logs of $CTR saved to $d"
}

# --- the repo, as a user gets it -------------------------------------------------------------------------------------
# make_bundle FROM_SHA TO_SHA: a git bundle with the two refs under test and the tags reachable from them (so
# `git describe`, which the installers use to read the version, sees what a clone of the real repo sees).
make_bundle() {
  local from=$1 to=$2 src="$WORK/src.git" t
  local -a refs=("$to:refs/heads/main")
  [ -z "$from" ] || refs+=("$from:refs/heads/t2-from")
  while IFS= read -r t; do refs+=("refs/tags/$t:refs/tags/$t"); done < <(
    { git -C "$REPO" tag --merged "$to"; [ -z "$from" ] || git -C "$REPO" tag --merged "$from"; } | sort -u)
  git -c init.defaultBranch=main init -q --bare "$src"
  git -C "$src" symbolic-ref HEAD refs/heads/main
  git -C "$REPO" -c core.hooksPath=/dev/null push -q --no-verify "$src" "${refs[@]}" 2>> "$OUT/t2.log"
  git -C "$src" bundle create -q "$WORK/repo.bundle" --all 2>> "$OUT/t2.log"
  log "bundle: $(du -h "$WORK/repo.bundle" | cut -f1), main=${to:0:12}${from:+, from=${from:0:12}}"
}

prepare_checkout() {   # prepare_checkout SHA
  docker cp "$WORK/repo.bundle" "$CTR:/t2/repo.bundle" > /dev/null
  docker exec "$CTR" chmod 644 /t2/repo.bundle
  guest clone "$T2_ORIGIN_URL"
  guest checkout "$1" | while IFS= read -r l; do log "$l"; done
}

# run_install LABEL [KILL_POINT]: the documented install command in the guest; its output lands in $OUT/LABEL.out.
# Returns the installer's exit code (0 after a kill: the kill point itself succeeded). With OMIT_PORT=1 the command
# leaves HTTP_PORT out, as a re-run or upgrade may: the installer must keep the port the install is on.
run_install() {
  local label=$1 point=${2:-} rc=0 t0 port=$T2_HTTP_PORT
  [ "${OMIT_PORT:-0}" != 1 ] || port=""
  t0=$(date +%s)
  log "install [$label]${point:+ (kill at $point)}: sudo BASE_URL=$T2_BASE_URL ${port:+HTTP_PORT=$port }./setup.sh --native -y"
  # Bounded: a hung install must end in a failure with logs, not run into the CI job timeout.
  timeout "${T2_STEP_TIMEOUT:-3600}" docker exec -e T2_BASE_URL="$T2_BASE_URL" -e T2_HTTP_PORT="$port" "$CTR" \
    bash /t2/guest.sh install "/t2/out/$label.out" ${point:+"$point"} > "$OUT/$label.result" 2>&1 || rc=$?
  [ "$rc" -ne 124 ] || echo "T2 install timed out after ${T2_STEP_TIMEOUT:-3600}s" >> "$OUT/$label.result"
  docker cp "$CTR:/t2/out/$label.out" "$OUT/$label.out" > /dev/null 2>&1 || true
  while IFS= read -r l; do log "  $l"; done < "$OUT/$label.result"
  log "install [$label] rc=$rc in $(( ($(date +%s) - t0) / 60 ))m$(( ($(date +%s) - t0) % 60 ))s"
  return "$rc"
}

# --- API helpers (curl + python3 on the host, like scripts/agent-v1-e2e.sh) ------------------------------------------
md5u() { printf '%s' "$1" | md5sum | awk '{print toupper($1)}'; }
# api METHOD PATH [JSON] [extra curl args...]: the JSON reply (empty on a transport error).
api() {
  local m=$1 p=$2 body=${3:-}
  shift 3 2>/dev/null || shift $#
  if [ -n "$body" ]; then
    printf '%s' "$body" | curl -s -m 30 -X "$m" -H 'Content-Type: application/json' --data-binary @- "$@" "$API$p" || true
  else
    curl -s -m 30 -X "$m" "$@" "$API$p" || true
  fi
}
jq_py() { python3 -c "import sys,json
try: d=json.load(sys.stdin)
except Exception: print('(not json)'); sys.exit(0)
print($1)"; }

http_code() { curl -s -o /dev/null -m 15 -w '%{http_code}' "$API$1" || true; }

wait_api() {   # wait_api SECONDS: until /rest/public/name answers 200
  local i
  for i in $(seq 1 "$1"); do [ "$(http_code /rest/public/name)" = 200 ] && return 0; sleep 1; done
  return 1
}

# first_login TEMP_PW: what the admin does on first sign-in: log in with the temporary password the installer printed,
# then set ADMIN_PW with the reset token the login returns (the console's Set Password call; scripts/dev-seed.sh does
# the same).
first_login() {
  local temp=$1 token
  token=$(api POST /rest/public/auth/login "{\"login\":\"admin\",\"password\":\"$(md5u "$temp")\"}" \
          | jq_py "(d.get('data') or {}).get('passwordResetToken') or '' if d.get('status')=='OK' and (d.get('data') or {}).get('passwordReset') else ''")
  check "first login with the printed temporary password asks for a new one" "$([ -n "$token" ] && echo yes || echo no)" yes
  [ -n "$token" ] || return 1
  check "admin password set through the first-login reset" \
    "$(api POST /rest/public/passwordReset/reset "{\"passwordResetToken\":\"$token\",\"newPassword\":\"$(md5u "$ADMIN_PW")\"}" | jq_py "d.get('status')")" OK
}

admin_login_ok() {
  api POST /rest/public/auth/login "{\"login\":\"admin\",\"password\":\"$(md5u "$ADMIN_PW")\"}" \
    | jq_py "str(d.get('status'))+':'+str((d.get('data') or {}).get('passwordReset'))"
}

# --- the reusable check step -----------------------------------------------------------------------------------------
# check_install LABEL REF: everything an install or upgrade must leave behind, on the version REF.
#   - the server unit (and the supervisor unit, when the version installs one) is active;
#   - what Tomcat serves is the checkout's own build (so a stale deploy cannot pass as the new version);
#   - initialized.txt (the server's own completion marker) says OK; /rest/public/name answers 200;
#   - scripts/agent-v1-e2e.sh OF THAT VERSION passes against the container (FAIL=0).
check_install() {
  local label=$1 ref=$2 u e2e res
  log "checks [$label] on $(git -C "$REPO" describe --tags --always "$ref" 2>/dev/null || echo "$ref")"
  wait_api 120 || true
  while IFS='=' read -r u res; do check "systemctl is-active $u" "$res" active; done < <(guest units)
  check "mdmesh-server unit installed" "$(guest units | grep -c '^mdmesh-server=')" 1
  check "Tomcat on port $T2_HTTP_PORT (server.xml connector, listening)" "$(guest port)" "$T2_HTTP_PORT listening"
  check "Tomcat serves this checkout's build (server WAR + console)" "$(guest deployed)" match
  check "initialized.txt says OK" "$(guest initialized | head -n 1 | cut -c1-2)" OK
  check "GET /rest/public/name" "$(http_code /rest/public/name)" 200
  check "admin can sign in (status:passwordReset)" "$(admin_login_ok)" "OK:False"
  e2e="$WORK/e2e-$label.sh"
  if git -C "$REPO" show "$ref:scripts/agent-v1-e2e.sh" > "$e2e" 2>/dev/null; then
    ADMIN_PW="$ADMIN_PW" bash "$e2e" "$API" > "$OUT/$label.e2e.log" 2>&1 || true
    res=$(sed -n 's/^===== RESULT: \(PASS=[0-9]* FAIL=[0-9]*\) =====$/\1/p' "$OUT/$label.e2e.log")
    log "  e2e (that version's scripts/agent-v1-e2e.sh): ${res:-no RESULT line}"
    check "agent-v1-e2e FAIL=0" "$(printf '%s' "$res" | sed -n 's/.*FAIL=\([0-9]*\).*/\1/p')" 0
    E2E_RESULT="$res"
  else
    log "  SKIP e2e: $ref has no scripts/agent-v1-e2e.sh"
    # shellcheck disable=SC2034  # read by run.sh
    E2E_RESULT="skipped (none in $ref)"
  fi
}

# --- continuity across an upgrade ------------------------------------------------------------------------------------
# continuity_before: enroll a device through /agent/v1 (as scripts/agent-v1-e2e.sh does) and record its credentials,
# the secrets the installer must carry over and the data counts. Written to $OUT/continuity.before.
continuity_before() {
  local cj tok enr
  cj=$(mktemp)
  log "continuity: enrolling a device before the upgrade"
  api POST /rest/public/auth/login "{\"login\":\"admin\",\"password\":\"$(md5u "$ADMIN_PW")\"}" -c "$cj" > /dev/null
  tok=$(api POST /rest/private/agent/v1/token "" -b "$cj" | jq_py "d['data']['token']")
  rm -f "$cj"
  enr=$(api POST /rest/public/agent/v1/enroll "{\"enrollToken\":\"$tok\",\"agent\":{\"version\":\"0.1.0\",\"package\":\"com.mdmesh.agent\"},\"device\":{\"androidSdkInt\":34,\"isDeviceOwner\":true},\"capabilities\":{\"policy\":[\"wifi\"],\"appManagement\":[],\"remoteControl\":{\"tier\":\"none\"},\"oem\":{\"vendor\":\"t2\",\"knox\":false}}}")
  DEV_ID=$(printf '%s' "$enr" | jq_py "d['data']['deviceId']")
  DEV_SECRET=$(printf '%s' "$enr" | jq_py "d['data']['deviceSecret']")
  check "continuity device enrolled" "$(printf '%s' "$enr" | jq_py "d.get('status')")" OK
  check "continuity device checks in (before)" "$(checkin_status)" OK
  {
    echo "DEV_ID=$DEV_ID"; echo "DEV_SECRET=$DEV_SECRET"
    guest secrets; guest counts
  } > "$OUT/continuity.before"
  log "  recorded: $(grep -v '^DEV_SECRET' "$OUT/continuity.before" | tr '\n' ' ')"
}

checkin_status() {
  api POST /rest/public/agent/v1/checkin "{\"deviceId\":\"$DEV_ID\",\"capabilities\":{\"policy\":[\"wifi\"]}}" \
    -H "Authorization: Bearer $DEV_SECRET" | jq_py "str(d.get('status'))+('' if d.get('status')=='OK' else ':'+str(d.get('message')))"
}

# continuity_after LABEL: the same device checks in with its ORIGINAL secret; secrets and counts are unchanged; admin
# still signs in. Must run BEFORE that version's e2e suite (it enrolls a device and creates/deletes a configuration).
continuity_after() {
  local label=$1 k v now
  log "continuity [$label]"
  wait_api 120 || true
  DEV_ID=$(sed -n 's/^DEV_ID=//p' "$OUT/continuity.before"); DEV_SECRET=$(sed -n 's/^DEV_SECRET=//p' "$OUT/continuity.before")
  check "device enrolled before the upgrade checks in with its original secret" "$(checkin_status)" OK
  check "admin still signs in with its password (status:passwordReset)" "$(admin_login_ok)" "OK:False"
  now=$( { guest secrets; guest counts; } )
  printf '%s\n' "$now" > "$OUT/$label.continuity.after"
  for k in hash.secret jwt.secretkey; do
    v=$(sed -n "s/^$k=//p" "$OUT/continuity.before")
    if [ "$v" = none ]; then
      log "  INFO $k: none before (that version did not write one); now $(printf '%s\n' "$now" | sed -n "s/^$k=//p")"
    else
      check "$k unchanged" "$(printf '%s\n' "$now" | sed -n "s/^$k=//p")" "$v"
    fi
  done
  check "configuration/device/user counts unchanged" "$(printf '%s\n' "$now" | grep '^configurations=')" \
    "$(grep '^configurations=' "$OUT/continuity.before")"
}
