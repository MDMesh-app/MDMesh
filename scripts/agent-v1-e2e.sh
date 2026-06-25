#!/usr/bin/env bash
#
# End-to-end smoke test of the Agent v1 protocol against a running control plane.
# `curl` plays the device, so this verifies the server half of the loop without an
# emulator: enroll -> mint token -> queue command -> authenticated capability-gated
# check-in -> ack.
#
# Prerequisites (a fresh Liquibase-only DB is NOT enough — these are normally set via
# the admin UI on first run):
#   1. Seed base data:   psql ... -f install/sql/hmdm_init.en.sql   (set _ADMIN_EMAIL_)
#   2. Clear the forced password reset:  UPDATE users SET passwordreset=false WHERE id=1;
#   3. Enable on-demand device creation: UPDATE settings SET createnewdevices=true WHERE id=1;
#   4. Set a default new-device config:  UPDATE settings SET newdeviceconfigurationid=1 WHERE id=1;
# (See docs/DEV.md "End-to-end agent loop".)
#
# Usage: scripts/agent-v1-e2e.sh [BASE_URL]      (default http://localhost:8080)
set -euo pipefail
BASE="${1:-${BASE_URL:-http://localhost:8080}}"
CJ="$(mktemp)"; trap 'rm -f "$CJ"' EXIT
PASS=0; FAIL=0
chk(){ if [ "$2" = "$3" ]; then echo "  PASS: $1"; PASS=$((PASS+1)); else echo "  FAIL: $1 (got '$2' want '$3')"; FAIL=$((FAIL+1)); fi; }
# Extract a field from a JSON response on stdin. First arg is python code operating on `d`.
# (Not eval — fixed expressions passed by this script only.)
field(){ python3 -c "import sys,json; d=json.load(sys.stdin); print($1)"; }

MD5=$(printf '%s' "${ADMIN_PW:-admin}" | md5sum | awk '{print toupper($1)}')

echo "== login =="
chk "login OK" "$(curl -s -c "$CJ" -H 'Content-Type: application/json' \
  -d "{\"login\":\"admin\",\"password\":\"$MD5\"}" "$BASE/rest/public/auth/login" | field "d['status']")" "OK"

echo "== mint enrollment token =="
TOK=$(curl -s -b "$CJ" -X POST "$BASE/rest/private/agent/v1/token" | field "d['data']['token']")
[ -n "$TOK" ] || { echo "  FAIL: no token"; exit 1; }

echo "== enroll =="
ENR=$(curl -s -X POST -H 'Content-Type: application/json' -d "{\"enrollToken\":\"$TOK\",\"agent\":{\"version\":\"0.1.0\",\"package\":\"com.mdmesh.agent\"},\"device\":{\"androidSdkInt\":34,\"isDeviceOwner\":true},\"capabilities\":{\"policy\":[\"wifi\"],\"appManagement\":[],\"remoteControl\":{\"tier\":\"none\"},\"oem\":{\"vendor\":\"samsung\",\"knox\":false}}}" "$BASE/rest/public/agent/v1/enroll")
chk "enroll OK" "$(echo "$ENR" | field "d['status']")" "OK"
DID=$(echo "$ENR" | field "d['data']['deviceId']"); SEC=$(echo "$ENR" | field "d['data']['deviceSecret']")

echo "== queue wifi command (requires policy.wifi) =="
DCMD_Q=$(curl -s -b "$CJ" -X POST -H 'Content-Type: application/json' \
  -d '{"type":"policy.apply","requiresCapability":"policy.wifi","payload":"{\"policy\":\"wifi\",\"value\":false}"}' \
  "$BASE/rest/private/agent/v1/devices/$DID/commands" | field "d['data']['id']")

echo "== authenticated check-in delivers the command =="
C1=$(curl -s -X POST -H "Authorization: Bearer $SEC" -H 'Content-Type: application/json' \
  -d "{\"deviceId\":\"$DID\",\"capabilities\":{\"policy\":[\"wifi\"]}}" "$BASE/rest/public/agent/v1/checkin")
chk "wifi command delivered" \
  "$(echo "$C1" | field "(lambda cs: f\"{len(cs)}:{cs[0]['type']}:{cs[0]['requiresCapability']}\" if cs else '')(d['data']['commands'])")" \
  "1:policy.apply:policy.wifi"
DCMD=$(echo "$C1" | field "d['data']['commands'][0]['commandId']")

echo "== capability gate =="
curl -s -b "$CJ" -X POST -H 'Content-Type: application/json' \
  -d '{"type":"policy.apply","requiresCapability":"policy.camera","payload":"{\"policy\":\"camera\",\"value\":true}"}' \
  "$BASE/rest/private/agent/v1/devices/$DID/commands" >/dev/null
chk "camera withheld when not advertised" \
  "$(curl -s -X POST -H "Authorization: Bearer $SEC" -H 'Content-Type: application/json' -d "{\"deviceId\":\"$DID\",\"capabilities\":{\"policy\":[\"wifi\"]}}" "$BASE/rest/public/agent/v1/checkin" | field "'policy.camera' in [c.get('requiresCapability') for c in d['data']['commands']]")" \
  "False"
chk "camera delivered once advertised" \
  "$(curl -s -X POST -H "Authorization: Bearer $SEC" -H 'Content-Type: application/json' -d "{\"deviceId\":\"$DID\",\"capabilities\":{\"policy\":[\"wifi\",\"camera\"]}}" "$BASE/rest/public/agent/v1/checkin" | field "'policy.camera' in [c.get('requiresCapability') for c in d['data']['commands']]")" \
  "True"

echo "== auth rejection =="
chk "bad bearer rejected" \
  "$(curl -s -X POST -H 'Authorization: Bearer WRONG' -H 'Content-Type: application/json' -d "{\"deviceId\":\"$DID\",\"capabilities\":{\"policy\":[\"wifi\"]}}" "$BASE/rest/public/agent/v1/checkin" | field "d['status']+':'+str(d.get('message'))")" \
  "ERROR:error.agent.unauthorized"
chk "missing bearer rejected" \
  "$(curl -s -X POST -H 'Content-Type: application/json' -d "{\"deviceId\":\"$DID\",\"capabilities\":{\"policy\":[\"wifi\"]}}" "$BASE/rest/public/agent/v1/checkin" | field "d['status']+':'+str(d.get('message'))")" \
  "ERROR:error.agent.unauthorized"

echo "== ack command =="
chk "check-in accepts result ack" \
  "$(curl -s -X POST -H "Authorization: Bearer $SEC" -H 'Content-Type: application/json' -d "{\"deviceId\":\"$DID\",\"capabilities\":{\"policy\":[\"wifi\"]},\"results\":[{\"commandId\":\"$DCMD\",\"status\":\"done\",\"completedAt\":\"2026-01-01T00:00:00Z\"}]}" "$BASE/rest/public/agent/v1/checkin" | field "d['status']")" \
  "OK"

echo "== check-in with state snapshot =="
chk "state checkin OK" \
  "$(curl -s -X POST -H "Authorization: Bearer $SEC" -H 'Content-Type: application/json' \
     -d "{\"deviceId\":\"$DID\",\"capabilities\":{\"policy\":[\"wifi\"]},\"state\":{\"battery\":77,\"charging\":true,\"locked\":false,\"kioskActive\":false,\"androidRelease\":\"14\",\"lastBootAt\":1000}}" \
     "$BASE/rest/public/agent/v1/checkin" | field "d['status']")" "OK"

echo "== check-in with telemetry =="
chk "telemetry checkin OK" \
  "$(curl -s -X POST -H "Authorization: Bearer $SEC" -H 'Content-Type: application/json' \
     -d "{\"deviceId\":\"$DID\",\"capabilities\":{\"policy\":[\"wifi\"]},\"state\":{\"battery\":77,\"charging\":true,\"locked\":false,\"kioskActive\":false,\"androidRelease\":\"14\",\"lastBootAt\":1000},\"telemetry\":{\"dynamic\":{\"batteryPct\":77},\"hardware\":{\"model\":\"Pixel\"}}}" \
     "$BASE/rest/public/agent/v1/checkin" | field "d['status']")" "OK"
echo "== read telemetry =="
chk "telemetry hardware.model" \
  "$(curl -s -b "$CJ" "$BASE/rest/private/agent/v1/devices/$DID/telemetry" | field "d['data']['hardware']['model']")" "Pixel"

echo "== check-in with events =="
chk "events checkin OK" \
  "$(curl -s -X POST -H "Authorization: Bearer $SEC" -H 'Content-Type: application/json' \
     -d "{\"deviceId\":\"$DID\",\"capabilities\":{\"policy\":[\"wifi\"]},\"state\":{\"battery\":77,\"charging\":true,\"locked\":false,\"kioskActive\":false,\"androidRelease\":\"14\",\"lastBootAt\":1000},\"events\":[{\"type\":\"boot\",\"ts\":1},{\"type\":\"appInstalled\",\"ts\":2,\"detail\":\"com.x\"}]}" \
     "$BASE/rest/public/agent/v1/checkin" | field "d['status']")" "OK"
echo "== read events =="
chk "events has boot" \
  "$(curl -s -b "$CJ" "$BASE/rest/private/agent/v1/devices/$DID/events?since=0" | field "'boot' in [e['type'] for e in d['data']]")" "True"

echo "== read device state =="
chk "state battery=77" \
  "$(curl -s -b "$CJ" "$BASE/rest/private/agent/v1/devices/$DID/state" | field "d['data']['battery']")" "77"
chk "state androidRelease=14" \
  "$(curl -s -b "$CJ" "$BASE/rest/private/agent/v1/devices/$DID/state" | field "d['data']['androidRelease']")" "14"

echo "== command history =="
chk "history has completedAt" \
  "$(curl -s -b "$CJ" "$BASE/rest/private/agent/v1/devices/$DID/commands?since=0" | field "any(c.get('completedAt') for c in d['data'])")" "True"

echo "== force sync =="
chk "force sync OK" \
  "$(curl -s -b "$CJ" -X POST "$BASE/rest/private/agent/v1/devices/$DID/sync" | field "d['status']")" "OK"

echo "===== RESULT: PASS=$PASS FAIL=$FAIL ====="
[ "$FAIL" -eq 0 ]
