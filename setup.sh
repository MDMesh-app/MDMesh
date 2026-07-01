#!/usr/bin/env bash
# MDMesh setup wizard. Generates secrets, writes .env, brings up the Docker stack, and seeds a
# functional admin with a generated password. Re-runnable (keeps an existing .env unless --reset).
#
# Usage: ./setup.sh            # interactive Docker setup
#        ./setup.sh --native   # hand off to the native (non-Docker) installer
set -euo pipefail
cd "$(dirname "$0")"

SALT='5YdSYHyg2U'   # PasswordUtil.PASS_SALT — must match the server.
say()  { printf '\033[1;36m%s\033[0m\n' "$*"; }
warn() { printf '\033[1;33m%s\033[0m\n' "$*"; }
err()  { printf '\033[1;31m%s\033[0m\n' "$*" >&2; }
rand() { openssl rand -hex 24; }
# users.password = SHA1( UPPER(MD5(raw)) + SALT ) — see PasswordUtil / CryptoUtil.
pwhash() {
  local md5; md5=$(printf '%s' "$1" | md5sum | awk '{print toupper($1)}')
  printf '%s' "${md5}${SALT}" | sha1sum | awk '{print $1}'
}

if [ "${1:-}" = "--native" ]; then
  exec ./install/install-native.sh
fi

command -v docker >/dev/null || { err "Docker is required (or run ./setup.sh --native)."; exit 1; }
if ! docker compose version >/dev/null 2>&1; then
  if command -v docker-compose >/dev/null; then
    err "Found legacy 'docker-compose' (v1). MDMesh needs the Docker Compose v2 plugin ('docker compose')."
    err "Install the 'docker-compose-plugin' package, or run ./setup.sh --native."
  else
    err "Docker Compose v2 is required ('docker compose'). Install the 'docker-compose-plugin' package,"
    err "or run ./setup.sh --native."
  fi
  exit 1
fi
command -v openssl >/dev/null || { err "openssl is required."; exit 1; }

say "== MDMesh setup =="
echo
echo "Hosting mode:"
echo "  1) Cloudflare Tunnel   (no open ports; Cloudflare manages TLS — needs a domain in Cloudflare)"
echo "  2) Your own domain     (open 80/443; Caddy auto-provisions a Let's Encrypt cert)"
MODE=""
while [ "$MODE" != "1" ] && [ "$MODE" != "2" ]; do
  read -rp "Choose [1/2]: " MODE || { err "No selection (non-interactive run?). Aborting."; exit 1; }
  case "$MODE" in 1|2) ;; *) warn "Please enter 1 or 2." ;; esac
done

DB_PASSWORD=$(rand)
HASH_SECRET=$(rand)
ADMIN_PASSWORD=$(rand)
RESET_TOKEN=$(openssl rand -hex 16)   # ≤40 chars (passwordresettoken column); forces a first-login change

if [ "$MODE" = "1" ]; then
  read -rp "Public hostname devices will use (e.g. mdm.example.com): " HOST
  read -rp "Cloudflare Tunnel token (Zero Trust → Tunnels → your tunnel): " TUNNEL_TOKEN
  BASE_URL="https://${HOST}"
  SITE_ADDRESS=":80"
  ACME_EMAIL=""
  COMPOSE_ARGS="--profile cloudflare"
  COMPOSE_FILE="docker-compose.yml"
  COMPOSE_PROFILES="cloudflare"
  EXTRA_NOTE="In Cloudflare, route the tunnel's public hostname ($HOST) to http://caddy:80."
else
  read -rp "Your domain (DNS already pointing here, e.g. mdm.example.com): " HOST
  read -rp "Email for Let's Encrypt: " ACME_EMAIL
  BASE_URL="https://${HOST}"
  SITE_ADDRESS="${HOST}"
  TUNNEL_TOKEN=""
  COMPOSE_ARGS="-f docker-compose.yml -f docker-compose.domain.yml"
  COMPOSE_FILE="docker-compose.yml:docker-compose.domain.yml"
  COMPOSE_PROFILES=""
  EXTRA_NOTE="Make sure ${HOST} resolves to this server and ports 80/443 are open."
fi

cat > .env <<EOF
DB_NAME=hmdm
DB_USER=hmdm
DB_PASSWORD=${DB_PASSWORD}
BASE_URL=${BASE_URL}
HASH_SECRET=${HASH_SECRET}
SECURE_ENROLLMENT=0
SITE_ADDRESS=${SITE_ADDRESS}
ACME_EMAIL=${ACME_EMAIL}
TUNNEL_TOKEN=${TUNNEL_TOKEN}
GITHUB_REPO=${GITHUB_REPO:-}
UPDATE_CHANNEL=stable
POLL_INTERVAL_HOURS=6
CURRENT_VERSION=${CURRENT_VERSION:-0.0.0}
GITHUB_TOKEN=
# Pull-based image coordinates (used when the supervisor applies an update). IMAGE_OWNER is the GHCR
# owner (lowercase); SERVER_VERSION/WEB_VERSION track the running release and are bumped by apply.sh.
IMAGE_OWNER=${IMAGE_OWNER:-local}
SERVER_VERSION=${CURRENT_VERSION:-0.0.0}
WEB_VERSION=${CURRENT_VERSION:-0.0.0}
SUPERVISOR_VERSION=${CURRENT_VERSION:-0.0.0}
AUTO_UPDATE=0
# Pin the compose identity so the supervisor drives the SAME stack the host launched.
COMPOSE_PROJECT_NAME=mdmesh
COMPOSE_FILE=${COMPOSE_FILE}
COMPOSE_PROFILES=${COMPOSE_PROFILES}
SMTP_HOST=
SMTP_PORT=25
SMTP_FROM=mdm@${HOST}
EOF
chmod 600 .env
say "Wrote .env (secrets generated)."

say "Building + starting the stack…"
# shellcheck disable=SC2086
docker compose $COMPOSE_ARGS up -d --build

say "Waiting for the server to finish first-boot (Liquibase)…"
for i in $(seq 1 60); do
  if docker compose exec -T server test -f /opt/hmdm/initialized.txt 2>/dev/null; then break; fi
  sleep 5
done

say "Seeding settings + admin…"
# Settings/configs/system-apps (avoids first-use NPEs); harmless if already present.
sed "s/_ADMIN_EMAIL_/admin@${HOST}/g" install/sql/hmdm_init.en.sql \
  | docker compose exec -T postgres psql -U hmdm -d hmdm >/dev/null 2>&1 || \
  warn "Seed step reported issues (often fine if already seeded)."
# Set the admin password to the generated one and FORCE a change on first login (the console routes
# a flagged login to a "set your password" screen, which clears the flag via the reset token).
docker compose exec -T postgres psql -U hmdm -d hmdm -c \
  "UPDATE users SET password='$(pwhash "$ADMIN_PASSWORD")', passwordreset=true, passwordresettoken='${RESET_TOKEN}' WHERE login='admin';" >/dev/null
# Remove the auxiliary Headwind seed apps (not used by our agent). The launcher (com.hmdm.launcher)
# is left in place — the default configurations reference it as their main app.
docker compose exec -T postgres psql -U hmdm -d hmdm >/dev/null 2>&1 <<'SQL' || true
DELETE FROM configurationapplications WHERE applicationid IN (SELECT id FROM applications WHERE pkg IN ('com.hmdm.pager','com.hmdm.phoneproxy','com.hmdm.emuilauncherrestarter'));
DELETE FROM applicationversions      WHERE applicationid IN (SELECT id FROM applications WHERE pkg IN ('com.hmdm.pager','com.hmdm.phoneproxy','com.hmdm.emuilauncherrestarter'));
DELETE FROM applications             WHERE pkg IN ('com.hmdm.pager','com.hmdm.phoneproxy','com.hmdm.emuilauncherrestarter');
SQL

echo
say "== MDMesh is up =="
echo "  Console:        ${BASE_URL}"
echo "  REST API base:  ${BASE_URL}/rest"
echo "  Recovery page:  ${BASE_URL}/recovery"
echo "  Login:          admin"
echo "  Password:       ${ADMIN_PASSWORD}   (temporary)"
echo
echo "  Access is via the URL above only — Postgres and the server publish no host ports;"
echo "  the edge (Caddy) is the single entry point ($([ "$MODE" = "1" ] && echo "Cloudflare Tunnel" || echo "ports 80/443"))."
echo
warn "Sign in with the temporary password — you'll be required to set your own on first login."
echo
echo "Next: $EXTRA_NOTE"
echo "Host the agent APK and enroll devices from the console's Enroll page (it builds the QR with ${BASE_URL})."
