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

echo "== desired-state: config.apply on drift =="
CFG_ID=$(curl -s -b "$CJ" "$BASE/rest/private/agent/v1/devices/$DID/configStatus" | field "d['data']['configurationId']")
CUR_REV=$(curl -s -b "$CJ" "$BASE/rest/private/agent/v1/devices/$DID/configStatus" | field "d['data']['currentRevision']")
chk "current revision is 64 hex" "$(printf '%s' "$CUR_REV" | grep -cE '^[0-9a-f]{64}$')" "1"
# capable agent, stale revision -> command delivered in the same response
C_DS=$(curl -s -X POST -H "Authorization: Bearer $SEC" -H 'Content-Type: application/json' \
  -d "{\"deviceId\":\"$DID\",\"capabilities\":{\"policy\":[\"wifi\"],\"device\":[\"configApply\"]},\"state\":{\"battery\":77,\"charging\":true,\"locked\":false,\"kioskActive\":false,\"androidRelease\":\"14\",\"lastBootAt\":1000,\"appliedConfigRevision\":\"0000\"}}" \
  "$BASE/rest/public/agent/v1/checkin")
chk "config.apply delivered on drift" "$(echo "$C_DS" | field "[c['type'] for c in d['data']['commands']].count('config.apply')")" "1"
chk "config.apply carries current revision" "$(echo "$C_DS" | field "[c['payload']['revision'] for c in d['data']['commands'] if c['type']=='config.apply'][0]")" "$CUR_REV"
DS_CMD=$(echo "$C_DS" | field "[c['commandId'] for c in d['data']['commands'] if c['type']=='config.apply'][0]")
# not re-issued while open
chk "not re-issued while delivered" "$(curl -s -X POST -H "Authorization: Bearer $SEC" -H 'Content-Type: application/json' -d "{\"deviceId\":\"$DID\",\"capabilities\":{\"policy\":[\"wifi\"],\"device\":[\"configApply\"]}}" "$BASE/rest/public/agent/v1/checkin" | field "[c['type'] for c in d['data']['commands']].count('config.apply')")" "0"
# ack done with the revision applied -> in sync
curl -s -X POST -H "Authorization: Bearer $SEC" -H 'Content-Type: application/json' \
  -d "{\"deviceId\":\"$DID\",\"capabilities\":{\"policy\":[\"wifi\"],\"device\":[\"configApply\"]},\"results\":[{\"commandId\":\"$DS_CMD\",\"status\":\"done\",\"detail\":\"{\\\"revision\\\":\\\"$CUR_REV\\\",\\\"outcomes\\\":{\\\"policies.wifi\\\":\\\"applied\\\"}}\",\"completedAt\":\"2026-01-01T00:00:00Z\"}],\"state\":{\"battery\":77,\"charging\":true,\"locked\":false,\"kioskActive\":false,\"androidRelease\":\"14\",\"lastBootAt\":1000,\"appliedConfigRevision\":\"$CUR_REV\"}}" \
  "$BASE/rest/public/agent/v1/checkin" >/dev/null
chk "configStatus inSync after ack" "$(curl -s -b "$CJ" "$BASE/rest/private/agent/v1/devices/$DID/configStatus" | field "d['data']['inSync']")" "True"
chk "syncSummary counts this device in sync" "$(curl -s -b "$CJ" "$BASE/rest/private/agent/v1/configurations/syncSummary" | field "[s['inSync'] for s in d['data'] if s['configurationId']==$CFG_ID][0] >= 1")" "True"
# old agent (no configApply key) never gets the command
chk "old agent not sent config.apply" "$(curl -s -X POST -H "Authorization: Bearer $SEC" -H 'Content-Type: application/json' -d "{\"deviceId\":\"$DID\",\"capabilities\":{\"policy\":[\"wifi\"]},\"state\":{\"battery\":1,\"charging\":false,\"locked\":false,\"kioskActive\":false,\"androidRelease\":\"14\",\"lastBootAt\":1,\"appliedConfigRevision\":\"stale\"}}" "$BASE/rest/public/agent/v1/checkin" | field "[c['type'] for c in d['data']['commands']].count('config.apply')")" "0"

echo "== desired-state: kiosk (mainAppId is an application VERSION id) =="
# Runs on a throwaway configuration so no real device on the shared configuration is ever
# kiosked. The main app is picked from the device configuration's install apps; its
# applications.id and applicationVersions.id differ, so matching by the wrong id fails here.
KCFG_SRC=$(curl -s -b "$CJ" "$BASE/rest/private/configurations/$CFG_ID")
KAPPS=$(curl -s -b "$CJ" "$BASE/rest/private/configurations/applications/$CFG_ID")
KPICK=$(echo "$KAPPS" | field "' '.join(str(x) for x in next((a['id'],a['usedVersionId'],a['pkg']) for a in d['data'] if a.get('selected') and a.get('action')==1 and a.get('usedVersionId') and a.get('pkg') and a['id']!=a['usedVersionId']))")
read -r KAPP_ID KVID KPKG <<<"$KPICK"
chk "picked install app has a distinct version id" "$([ -n "$KVID" ] && [ "$KAPP_ID" != "$KVID" ] && echo yes)" "yes"
KNAME="e2e-kiosk-$(date +%s)-$$"
# Body = the source configuration minus identity, with ONE install app and kiosk off.
kcfg_body(){ # $1 = id or "" ; $2 = kioskMode (true|false)
  KCFG_SRC="$KCFG_SRC" KID="$1" KMODE="$2" KNAME="$KNAME" KAPP_ID="$KAPP_ID" KVID="$KVID" python3 -c '
import json,os
c=json.loads(os.environ["KCFG_SRC"])["data"]
for k in ("id","qrCodeKey","selected"): c.pop(k,None)
if os.environ["KID"]: c["id"]=int(os.environ["KID"])
c["name"]=os.environ["KNAME"]; c["description"]="agent-v1-e2e kiosk scenario (temporary)"
c["applications"]=[{"id":int(os.environ["KAPP_ID"]),"usedVersionId":int(os.environ["KVID"]),"action":1,"showIcon":True,"remove":False}]
c["kioskMode"]=os.environ["KMODE"]=="true"; c["mainAppId"]=int(os.environ["KVID"])
print(json.dumps(c))'; }
KNEW=$(curl -s -b "$CJ" -X PUT -H 'Content-Type: application/json' -d "$(kcfg_body "" false)" "$BASE/rest/private/configurations")
KCFG=$(echo "$KNEW" | field "d['data']['id']")
chk "kiosk scenario configuration created" "$(echo "$KNEW" | field "d['status']")" "OK"
KDEV=$(curl -s -b "$CJ" -X POST -H 'Content-Type: application/json' -d "{\"value\":\"$DID\",\"pageSize\":5,\"pageNum\":1}" "$BASE/rest/private/devices/search" | field "[x['id'] for x in d['data']['devices']['items'] if x['number']=='$DID'][0]")
chk "device moved to kiosk scenario configuration" "$(curl -s -b "$CJ" -X PUT -H 'Content-Type: application/json' -d "{\"ids\":[$KDEV],\"configurationId\":$KCFG}" "$BASE/rest/private/devices" | field "d['status']")" "OK"
chk "PUT configuration kioskMode=true + mainAppId=version id" \
  "$(curl -s -b "$CJ" -X PUT -H 'Content-Type: application/json' -d "$(kcfg_body "$KCFG" true)" "$BASE/rest/private/configurations" | field "str(d['status'])+':'+str(d['data']['kioskMode'])+':'+str(d['data']['mainAppId'])")" "OK:True:$KVID"
# Capable agent, stale revision (it last applied the shared configuration's revision).
C_K=$(curl -s -X POST -H "Authorization: Bearer $SEC" -H 'Content-Type: application/json' \
  -d "{\"deviceId\":\"$DID\",\"capabilities\":{\"policy\":[\"wifi\"],\"device\":[\"configApply\"]},\"state\":{\"battery\":77,\"charging\":true,\"locked\":false,\"kioskActive\":false,\"androidRelease\":\"14\",\"lastBootAt\":1000,\"appliedConfigRevision\":\"$CUR_REV\"}}" \
  "$BASE/rest/public/agent/v1/checkin")
KPAY="[c['payload'] for c in d['data']['commands'] if c['type']=='config.apply'][0]"
chk "kiosk config.apply delivered" "$(echo "$C_K" | field "[c['type'] for c in d['data']['commands']].count('config.apply')")" "1"
chk "kiosk.mode == single" "$(echo "$C_K" | field "$KPAY['kiosk']['mode']")" "single"
chk "kiosk.pinPackage == main app pkg" "$(echo "$C_K" | field "$KPAY['kiosk']['pinPackage']")" "$KPKG"
# Restore: kiosk off, device back on its configuration, drop the temporary configuration.
chk "restore kioskMode=false" "$(curl -s -b "$CJ" -X PUT -H 'Content-Type: application/json' -d "$(kcfg_body "$KCFG" false)" "$BASE/rest/private/configurations" | field "str(d['status'])+':'+str(d['data']['kioskMode'])")" "OK:False"
chk "device restored to its configuration" "$(curl -s -b "$CJ" -X PUT -H 'Content-Type: application/json' -d "{\"ids\":[$KDEV],\"configurationId\":$CFG_ID}" "$BASE/rest/private/devices" | field "d['status']")" "OK"
chk "kiosk scenario configuration deleted" "$(curl -s -b "$CJ" -X DELETE "$BASE/rest/private/configurations/$KCFG" | field "d['status']")" "OK"

echo "== command history (payload-free, 6.6) =="
HIST=$(curl -s -b "$CJ" "$BASE/rest/private/agent/v1/devices/$DID/commands?since=0")
chk "history has completedAt" "$(echo "$HIST" | field "any(c.get('completedAt') for c in d['data'])")" "True"
chk "history rows carry no payload" "$(echo "$HIST" | field "sum(1 for c in d['data'] if 'payload' in c)")" "0"
chk "history rows carry no deviceNumber" "$(echo "$HIST" | field "sum(1 for c in d['data'] if 'deviceNumber' in c)")" "0"
chk "history keeps the config.apply result detail" "$(echo "$HIST" | field "[c.get('detail') or '' for c in d['data'] if str(c['id'])=='$DS_CMD'][0].startswith('{')")" "True"

echo "== force sync =="
chk "force sync OK" \
  "$(curl -s -b "$CJ" -X POST "$BASE/rest/private/agent/v1/devices/$DID/sync" | field "d['status']")" "OK"

echo "===== RESULT: PASS=$PASS FAIL=$FAIL ====="
[ "$FAIL" -eq 0 ]
