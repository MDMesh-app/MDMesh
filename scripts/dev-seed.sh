#!/usr/bin/env bash
#
# Seed the dev stack's database the way the installers do (install/lib/db.sh), then set the admin password through
# the real first-login flow (the console's Set Password call). Re-runnable: on a seeded database it only re-applies
# the idempotent post-seed repairs.
#
# Usage: scripts/dev-seed.sh          (after: docker compose --env-file docker/dev.env up -d --build)
#   DEV_ADMIN_PASSWORD   admin password to set on a fresh database (default: admin, scripts/agent-v1-e2e.sh's default)
set -euo pipefail
cd "$(CDPATH='' cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# shellcheck source=install/lib/db.sh
. install/lib/db.sh
DC=(docker compose --env-file docker/dev.env)
# shellcheck disable=SC2034  # PSQL is used by install/lib/db.sh
PSQL=("${DC[@]}" exec -T postgres psql -U mdmesh -d mdmesh)
PW="${DEV_ADMIN_PASSWORD:-admin}"
md5u() { printf '%s' "$1" | md5sum | awk '{print toupper($1)}'; }
# POST a JSON body to $API$1 and print the response's "data" as JSON. The body goes on stdin, so password hashes and
# tokens never appear in the process list. Any failure is one line on stderr naming the call.
api_post() {
  local resp
  resp=$(printf '%s' "$2" | curl -sS -m 30 -H 'Content-Type: application/json' --data-binary @- "$API$1" 2>&1) \
    || { echo "POST $1 failed: $(printf '%s' "$resp" | head -n 1)" >&2; return 1; }
  printf '%s' "$resp" | python3 -c '
import sys, json
raw = sys.stdin.read()
try:
    r = json.loads(raw)
except ValueError:
    sys.exit("POST %s failed: the response is not JSON: %.100r" % (sys.argv[1], raw))
if not isinstance(r, dict) or r.get("status") != "OK":
    sys.exit("POST %s failed: %.200s" % (sys.argv[1], json.dumps(r)))
json.dump(r.get("data"), sys.stdout)' "$1"
}
# Set the admin password with a reset token: the call the console's Set Password page makes.
reset_pw() {
  api_post /rest/public/passwordReset/reset \
    "{\"passwordResetToken\":\"$1\",\"newPassword\":\"$(md5u "$PW")\"}" >/dev/null
}

# Act only on a dev stack. docker/dev.env names the project mdmesh-dev, but an exported COMPOSE_PROJECT_NAME (how a
# second, isolated dev stack runs) or an edited dev.env can point these commands at another project, such as a
# production install's (setup.sh names it mdmesh). Compose labels each container with the files it was created from,
# so require the dev overlay on every container this script uses; a production stack's never carry it.
[ -f docker/dev.env ] || { echo "docker/dev.env is missing; this script only seeds the dev stack" >&2; exit 1; }
cfg=$("${DC[@]}" config --format json) || exit 1
project=$(printf '%s' "$cfg" | python3 -c 'import sys,json; print(json.load(sys.stdin)["name"])')
L=com.docker.compose.project
for svc in postgres server; do
  ids=$("${DC[@]}" ps -q --status running "$svc")
  [ -n "$ids" ] || { echo "the dev stack's $svc is not running; start it:" \
                         "docker compose --env-file docker/dev.env up -d --build" >&2; exit 1; }
  for id in $ids; do
    meta=$(docker inspect --format "{{index .Config.Labels \"$L\"}}|{{index .Config.Labels \"$L.config_files\"}}" "$id")
    case ",${meta#*|}," in
      *[/,]docker-compose.dev.yml,*) ;;
      *) echo "refusing: project '${meta%%|*}' was not started with docker-compose.dev.yml, so it is not a dev stack" \
              "(its $svc was created from: ${meta#*|})" >&2; exit 1 ;;
    esac
  done
done
API="http://$("${DC[@]}" port server 8080)"   # the published host port, wherever DEV_API_PORT put it

# docker/entrypoint.sh removes initialized.txt at every start, and the server writes it when its initialization is over
# (with "OK" or the error), so also wait for the API to answer, which it does only after a boot that worked. Both
# probes are silent: while Tomcat boots, refused or reset connections are expected, not errors worth printing.
ready() { "${DC[@]}" exec -T server test -f /opt/mdmesh/initialized.txt >/dev/null 2>&1 \
          && curl -fs -o /dev/null -m 5 "$API/rest/public/auth/options" 2>/dev/null; }
printf 'dev stack %s: waiting for the server (the first boot runs Liquibase)' "$project"
up=
for _ in $(seq 1 60); do ready && { up=1; break; }; printf '.'; sleep 5; done
if [ -n "$up" ]; then echo " ready"; else
  echo " timed out"
  echo "the server did not answer $API/rest/public/auth/options within 5 minutes;" \
       "see its log: docker compose --env-file docker/dev.env logs server" >&2
  exit 1
fi

state=$(mdm_db_state)
case "$state" in
  fresh)
    tmp=$(mdm_rand)
    mdm_seed admin@localhost install/sql/hmdm_init.en.sql "$tmp" "$(openssl rand -hex 16)"
    mdm_post_seed install/sql/post_seed.sql
    # First login with the temporary password returns a reset token; setting the password with it is what the
    # console's Set Password page does. If a step fails from here on, the database is seeded but admin still has the
    # random password; a re-run finishes the reset (seeded branch).
    resume() { echo "the database is seeded; re-run scripts/dev-seed.sh to finish setting the admin password" >&2
               exit 1; }
    user=$(api_post /rest/public/auth/login "{\"login\":\"admin\",\"password\":\"$(md5u "$tmp")\"}") || resume
    token=$(printf '%s' "$user" | python3 -c 'import sys,json; d=json.load(sys.stdin) or {}
print(d.get("passwordResetToken") or "" if d.get("passwordReset") else "")')
    [ -n "$token" ] || { echo "the first admin login did not ask for a password change" >&2; resume; }
    reset_pw "$token" || resume
    echo "seeded; console login: admin / $PW" ;;
  seeded)
    mdm_post_seed install/sql/post_seed.sql
    # A run that seeded but failed before the password reset left admin behind a random password it never printed.
    # mdm_seed stored the reset token; finish the reset with it. The server takes a reset token for 24 hours from
    # its issue time, which the seed does not set, so it is stamped now.
    token=$(mdm_psql -c "UPDATE users SET passwordresettokentime = (EXTRACT(EPOCH FROM NOW()) * 1000)::BIGINT
                         WHERE login='admin' AND passwordreset AND passwordresettoken IS NOT NULL
                         RETURNING passwordresettoken")
    if [ -n "$token" ]; then
      reset_pw "$token" || exit 1
      echo "seeded (admin password reset resumed): admin / $PW"
    else
      echo "already seeded; post-seed repairs applied (admin password unchanged)"
    fi ;;
  unavailable)
    echo "could not query the database; check postgres: docker compose --env-file docker/dev.env logs postgres" >&2
    exit 1 ;;
  *) echo "database state is '$state'; refusing to seed" \
          "(docker compose --env-file docker/dev.env down -v resets it)" >&2
     exit 1 ;;
esac
