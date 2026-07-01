#!/usr/bin/env bash
# MDMesh quick start — deploy from PUBLISHED images, no clone and no build. Needs only Docker.
# Run from anywhere (the `bash <(...)` form keeps the prompts interactive):
#
#   bash <(curl -fsSL https://raw.githubusercontent.com/MDMesh-app/MDMesh/main/quickstart.sh)
#
# It creates ./mdmesh, downloads the pull-only compose + seed, generates secrets, brings the stack
# up, and prints the console URL + a temporary admin password (you set your own on first login).
set -euo pipefail

REPO="MDMesh-app/MDMesh"
BRANCH="main"
RAW="https://raw.githubusercontent.com/${REPO}/${BRANCH}"
IMAGE_OWNER_DEFAULT="mdmesh-app"
SALT='5YdSYHyg2U'   # PasswordUtil.PASS_SALT — must match the server.

say()  { printf '\033[1;36m%s\033[0m\n' "$*"; }
warn() { printf '\033[1;33m%s\033[0m\n' "$*"; }
err()  { printf '\033[1;31m%s\033[0m\n' "$*" >&2; }
rand() { openssl rand -hex 24; }
pwhash() { local m; m=$(printf '%s' "$1" | md5sum | awk '{print toupper($1)}'); printf '%s' "${m}${SALT}" | sha1sum | awk '{print $1}'; }

command -v docker >/dev/null || { err "Docker is required."; exit 1; }
docker compose version >/dev/null 2>&1 || { err "Docker Compose v2 is required ('docker compose')."; exit 1; }
command -v curl    >/dev/null || { err "curl is required."; exit 1; }
command -v openssl >/dev/null || { err "openssl is required."; exit 1; }

DIR="${MDMESH_DIR:-mdmesh}"
mkdir -p "$DIR" && cd "$DIR"
[ -f .env ] && { err "An .env already exists in $(pwd) — refusing to overwrite. Remove it to re-run."; exit 1; }

say "== MDMesh quick start (published images) =="
echo "Installing into: $(pwd)"
echo
echo "Hosting mode:"
echo "  1) Cloudflare Tunnel   (no open ports; Cloudflare manages TLS — needs a domain in Cloudflare)"
echo "  2) Your own domain     (open 80/443; Caddy auto-provisions a Let's Encrypt cert)"
MODE=""
while [ "$MODE" != "1" ] && [ "$MODE" != "2" ]; do
  read -rp "Choose [1/2]: " MODE || { err "No selection (non-interactive run?). Aborting."; exit 1; }
  case "$MODE" in 1|2) ;; *) warn "Please enter 1 or 2." ;; esac
done

read -rp "Pull releases from GitHub repo [${REPO}]: " GH_REPO;       GH_REPO="${GH_REPO:-$REPO}"
read -rp "Image owner (GHCR, lowercase) [${IMAGE_OWNER_DEFAULT}]: " IMAGE_OWNER; IMAGE_OWNER="${IMAGE_OWNER:-$IMAGE_OWNER_DEFAULT}"

DB_PASSWORD=$(rand); HASH_SECRET=$(rand); ADMIN_PASSWORD=$(rand); RESET_TOKEN=$(openssl rand -hex 16)

if [ "$MODE" = "1" ]; then
  read -rp "Public hostname devices will use (e.g. mdm.example.com): " HOST
  read -rp "Cloudflare Tunnel token (Zero Trust → Tunnels → your tunnel): " TUNNEL_TOKEN
  BASE_URL="https://${HOST}"; SITE_ADDRESS=":80"; ACME_EMAIL=""
  COMPOSE_FILE="docker-compose.yml"; COMPOSE_PROFILES="cloudflare"
  EXTRA_NOTE="In Cloudflare, route the tunnel's public hostname ($HOST) to http://caddy:80."
else
  read -rp "Your domain (DNS already pointing here, e.g. mdm.example.com): " HOST
  read -rp "Email for Let's Encrypt: " ACME_EMAIL
  BASE_URL="https://${HOST}"; SITE_ADDRESS="${HOST}"; TUNNEL_TOKEN=""
  COMPOSE_FILE="docker-compose.yml:docker-compose.domain.yml"; COMPOSE_PROFILES=""
  EXTRA_NOTE="Make sure ${HOST} resolves to this server and ports 80/443 are open."
fi

say "Downloading the pull-only compose + seed…"
curl -fsSL "${RAW}/docker-compose.release.yml" -o docker-compose.yml
curl -fsSL "${RAW}/docker-compose.domain.yml"  -o docker-compose.domain.yml
mkdir -p install/sql
curl -fsSL "${RAW}/install/sql/hmdm_init.en.sql" -o install/sql/hmdm_init.en.sql

cat > .env <<EOF
DB_NAME=mdmesh
DB_USER=mdmesh
DB_PASSWORD=${DB_PASSWORD}
BASE_URL=${BASE_URL}
HASH_SECRET=${HASH_SECRET}
SECURE_ENROLLMENT=0
SITE_ADDRESS=${SITE_ADDRESS}
ACME_EMAIL=${ACME_EMAIL}
TUNNEL_TOKEN=${TUNNEL_TOKEN}
IMAGE_OWNER=${IMAGE_OWNER}
SERVER_VERSION=latest
WEB_VERSION=latest
SUPERVISOR_VERSION=latest
GITHUB_REPO=${GH_REPO}
UPDATE_CHANNEL=stable
POLL_INTERVAL_HOURS=6
CURRENT_VERSION=0.0.0
GITHUB_TOKEN=
AUTO_UPDATE=0
COMPOSE_PROJECT_NAME=mdmesh
COMPOSE_FILE=${COMPOSE_FILE}
COMPOSE_PROFILES=${COMPOSE_PROFILES}
SMTP_HOST=
SMTP_PORT=25
SMTP_FROM=mdm@${HOST}
EOF
chmod 600 .env
say "Wrote .env (secrets generated). docker compose reads COMPOSE_FILE/PROFILES from it."

say "Pulling images…"
docker compose pull || { err "Could not pull images from ghcr.io/${IMAGE_OWNER}. Has a release been published? (cut one with: git tag v0.1.0 && git push --tags)"; exit 1; }
say "Starting the stack…"
docker compose up -d

say "Waiting for the server to finish first-boot (Liquibase)…"
for i in $(seq 1 60); do
  if docker compose exec -T server test -f /opt/mdmesh/initialized.txt 2>/dev/null; then break; fi
  sleep 5
done

say "Seeding settings + admin…"
sed "s/_ADMIN_EMAIL_/admin@${HOST}/g" install/sql/hmdm_init.en.sql \
  | docker compose exec -T postgres psql -U mdmesh -d mdmesh >/dev/null 2>&1 || warn "Seed step reported issues (often fine if already seeded)."
docker compose exec -T postgres psql -U mdmesh -d mdmesh -c \
  "UPDATE users SET password='$(pwhash "$ADMIN_PASSWORD")', passwordreset=true, passwordresettoken='${RESET_TOKEN}' WHERE login='admin';" >/dev/null

echo
say "== MDMesh is up =="
echo "  Console:        ${BASE_URL}"
echo "  REST API base:  ${BASE_URL}/rest"
echo "  Login:          admin"
echo "  Password:       ${ADMIN_PASSWORD}   (temporary — you'll set your own on first login)"
echo "  Directory:      $(pwd)   (run 'docker compose' commands from here)"
echo
warn "Save that password now — it is not stored anywhere in clear text."
echo "Next: $EXTRA_NOTE"
echo "Enroll devices from the console's Enroll page (it builds the QR with ${BASE_URL})."
