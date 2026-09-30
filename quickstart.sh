#!/usr/bin/env bash
# MDMesh quick start — deploy from PUBLISHED images, no clone and no build. Needs only Docker.
# Run from anywhere (the `bash <(...)` form keeps the prompts interactive):
#
#   bash <(curl -fsSL https://raw.githubusercontent.com/MDMesh-app/MDMesh/main/quickstart.sh)
#
# It creates ./mdmesh, downloads the pull-only compose + seed, generates secrets, brings the stack
# up, and prints the console URL + a temporary admin password (you set your own on first login).
set -euo pipefail
# An exported CDPATH makes cd (here and in every child, e.g. `bash -c 'cd web && …'`) resolve a relative path against
# CDPATH's directories, not the current one — silently building or reading from a same-named directory elsewhere. Unset
# it for this script and its children.
unset CDPATH

REPO="MDMesh-app/MDMesh"
BRANCH="main"   # where the compose + seed come from only when the release can't be resolved (see below)
IMAGE_OWNER_DEFAULT="mdmesh-app"

say()  { printf '\033[1;36m%s\033[0m\n' "$*"; }
warn() { printf '\033[1;33m%s\033[0m\n' "$*"; }
err()  { printf '\033[1;31m%s\033[0m\n' "$*" >&2; }
rand() { openssl rand -hex 24; }
# The latest published (non-prerelease, non-draft) release of owner/repo $1, without the "v" — the version this
# install pins. Release tags are always vX.Y.Z[-pre] (release.yml triggers on v*), and the caller downloads from
# the v<version> ref, so anything else counts as unresolved. Prints nothing when GitHub is unreachable,
# rate-limited or has no release.
latest_release() {
  local tag
  tag=$(curl -fsSL -m 20 "https://api.github.com/repos/$1/releases/latest" 2>/dev/null \
        | grep -o '"tag_name"[[:space:]]*:[[:space:]]*"[^"]*"' | head -1 | sed 's/.*"\([^"]*\)"$/\1/') || true
  if [[ "$tag" =~ ^v[0-9]+\.[0-9]+\.[0-9]+([-+][0-9A-Za-z.+-]+)?$ ]]; then printf '%s\n' "${tag#v}"; fi
}

# The BASE_URL rule of install/lib/url.sh (see there for what it accepts and why it is an allowlist). A verbatim copy
# of the functions between the markers, not a download: this script runs from main but fetches install/lib from the
# release it installs, which may predate url.sh (see db.sh below). CI (t0-fast, edge entry) fails if the copies differ.
# >>> url.sh functions (quickstart.sh keeps a verbatim copy) >>>
# The character sets, spelled out: a range such as [A-Za-z0-9] follows the locale's collation, and under en_US.UTF-8
# bash matches thousands of non-ASCII letters with it (https://ｅxample.com would pass).
_MDM_ALNUM=ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789
_MDM_HEX=0123456789ABCDEFabcdef
_MDM_DIGITS=0123456789
# _mdm_hostport_problem HOSTPORT: prints why HOSTPORT is not host[:port] as described above; prints nothing when it is.
_mdm_hostport_problem() {
  local hp=$1 host='' port='' v6=''
  case "$hp" in
    \[*)
      v6=${hp#\[}
      case "$v6" in *\]*) ;; *) echo "\"$hp\" has no closing ]"; return ;; esac
      port=${v6#*\]}; v6=${v6%%\]*}
      case "$v6" in
        ''|*[!${_MDM_HEX}:.]*|*:::*|*::*::*|*:*:*:*:*:*:*:*:*) echo "\"[$v6]\" is not an IPv6 address"; return ;;
        *:*) ;;
        *) echo "\"[$v6]\" is not an IPv6 address"; return ;;
      esac
      case "$port" in '') return ;; :*) port=${port#:}; [ -n "$port" ] || port=- ;; *) echo "\"$hp\": only :port may follow ]"; return ;; esac ;;
    *)
      host=${hp%%:*}
      case "$hp" in *:*) port=${hp#*:}; [ -n "$port" ] || port=- ;; esac
      case "$host" in
        '') echo 'it has no host (expected e.g. mdm.example.com)'; return ;;
        *[!${_MDM_ALNUM}.-]*|.*|*.|-*|*-|*..*) echo "\"$host\" is not a host name or IPv4 address"; return ;;
      esac ;;
  esac
  case "$port" in
    '') ;;                                                                     # no port
    *[!${_MDM_DIGITS}]*) echo "\"$hp\" does not end in a port number after the colon"; return ;;
    0*) echo "\"$hp\" has an invalid port (a port is 1-65535, with no leading zero)"; return ;;
  esac
  # Length first, so the numeric compare never sees a value too big for the shell's integer.
  [ -z "$port" ] || { [ "${#port}" -le 5 ] && [ "$port" -ge 1 ] && [ "$port" -le 65535 ]; } \
    || echo "\"$hp\" has a port outside 1-65535"
}

# mdm_check_base_url VAR: checks the URL held in the variable named VAR. When it passes, VAR's scheme is rewritten in
# lowercase (HTTPS://… becomes https://…; nothing else changes) and it succeeds. Otherwise it prints why to stderr,
# leaves VAR alone and fails. It takes a variable name, not the value, so the caller's variable is normalised in place.
mdm_check_base_url() {
  local url=${!1-} rest='' bad='' why=''
  case "$url" in
    '')                             why='it is empty' ;;
    *@*)                            why='it contains "@": a base URL takes no user name or password (user@host)' ;;
    *\?*|*#*)                       why='it contains "?" or "#": a base URL takes no query or fragment' ;;
    *%*)                            why='it contains "%": percent-escapes are not accepted in a base URL' ;;
    *[!${_MDM_ALNUM}._:/+=,\[\]-]*)
      bad=${url//[${_MDM_ALNUM}._:\/+=,\[\]-]/}; bad=${bad:0:1}
      case "$bad" in \') bad="\"'\"" ;; [[:print:]]) bad="'$bad'" ;; *) bad=$(printf '%q' "$bad") ;; esac
      why="it contains $bad, which is not allowed (only letters, digits and . _ : / + = , [ ] -)" ;;
    [Hh][Tt][Tt][Pp]://*)           rest=${url#*://}; url="http://$rest" ;;
    [Hh][Tt][Tt][Pp][Ss]://*)       rest=${url#*://}; url="https://$rest" ;;
    *)                              why='it must start with http:// or https:// (e.g. https://mdm.example.com)' ;;
  esac
  case "$rest" in
    [Hh][Tt][Tt][Pp]://*|[Hh][Tt][Tt][Pp][Ss]://*) why='it has the scheme twice (where a hostname is asked for, enter the name only)' ;;
  esac
  [ -n "$why" ] || why=$(_mdm_hostport_problem "${rest%%/*}")
  if [ -z "$why" ]; then printf -v "$1" '%s' "$url"; return 0; fi
  url=${!1-}
  case "$url" in *[![:print:]]*) url=$(printf '%q' "$url") ;; esac   # shown as typed, unless that would garble the terminal
  printf 'Invalid public base URL %s: %s.\n' "${url:-(empty)}" "$why" >&2
  return 1
}
# mdm_check_host VAR: checks that the variable named VAR holds a bare host[:port] as described above, with no scheme,
# path, user@, query or fragment: what the hostname prompts ask for (it becomes BASE_URL and Caddy's site address).
# Otherwise it prints why to stderr and fails. The value is never changed.
mdm_check_host() {
  local h=${!1-} bad='' why=''
  case "$h" in
    '')                 why='it is empty' ;;
    *://*)              why='enter the name only, without http:// or https://' ;;
    */*|*\?*|*#*|*@*)   why='enter the name only: no /path, ?query, #fragment or user@' ;;
    *[!${_MDM_ALNUM}.:\[\]-]*)
      bad=${h//[${_MDM_ALNUM}.:\[\]-]/}; bad=${bad:0:1}
      case "$bad" in \') bad="\"'\"" ;; [[:print:]]) bad="'$bad'" ;; *) bad=$(printf '%q' "$bad") ;; esac
      why="it contains $bad, which is not allowed (only letters, digits, . and -, or an [IPv6] address, then an optional :port)" ;;
  esac
  [ -n "$why" ] || why=$(_mdm_hostport_problem "$h")
  [ -z "$why" ] && return 0
  case "$h" in *[![:print:]]*) h=$(printf '%q' "$h") ;; esac
  printf 'Invalid hostname %s: %s.\n' "${h:-(empty)}" "$why" >&2
  return 1
}
# <<< url.sh functions <<<

command -v docker >/dev/null || { err "Docker is required."; exit 1; }
docker compose version >/dev/null 2>&1 || { err "Docker Compose v2 is required ('docker compose')."; exit 1; }
command -v curl    >/dev/null || { err "curl is required."; exit 1; }
command -v openssl >/dev/null || { err "openssl is required."; exit 1; }

DIR="${MDMESH_DIR:-mdmesh}"
# Two statements, not `mkdir && cd`: under set -e a failure before an && is not fatal, so a failed mkdir would fall
# through and the rest would run in the wrong directory.
mkdir -p -- "$DIR"
cd -P -- "$DIR"
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

# Pin the release being installed: server + web images and CURRENT_VERSION name the same version, so the console
# doesn't report the running release as an update (it would with CURRENT_VERSION=0.0.0), a rollback has a real tag to
# return to, and a `:latest` tag that moves mid-release can't hand us a mismatched server/web pair. The supervisor
# stays on `:latest`: apply never bumps it, so `docker compose pull` is how it gets its own fixes. If the release
# can't be resolved, fall back to `:latest` + CURRENT_VERSION=0.0.0 (the old behaviour): the stack still comes up, the
# console shows "Update available" until the first apply pins the versions. The compose file + seed come from the same
# release's tag in the repo it was resolved from, so they match the pinned images; `main` only in the fallback.
RELEASE=$(latest_release "$GH_REPO")
if [ -n "$RELEASE" ]; then
  IMAGE_TAG="$RELEASE"; CURRENT_VERSION="$RELEASE"
  RAW="https://raw.githubusercontent.com/${GH_REPO}/v${RELEASE}"
  say "Installing release v${RELEASE} of ${GH_REPO}."
else
  IMAGE_TAG="latest"; CURRENT_VERSION="0.0.0"
  RAW="https://raw.githubusercontent.com/${REPO}/${BRANCH}"
  warn "Could not resolve the latest release of ${GH_REPO} (GitHub API unreachable or rate-limited?) — using the :latest"
  warn "images and the ${BRANCH} compose. The console will show \"Update available\" until the first update pins the"
  warn "version (see DEPLOY.md)."
fi

DB_PASSWORD=$(rand); HASH_SECRET=$(rand); ADMIN_PASSWORD=$(rand); RESET_TOKEN=$(openssl rand -hex 16)

if [ "$MODE" = "1" ]; then
  read -rp "Public hostname devices will use (e.g. mdm.example.com): " HOST
  mdm_check_host HOST || { err "Enter the hostname only, e.g. mdm.example.com, then re-run."; exit 1; }
  read -rp "Cloudflare Tunnel token (Zero Trust → Tunnels → your tunnel): " TUNNEL_TOKEN
  BASE_URL="https://${HOST}"; SITE_ADDRESS=":80"; ACME_EMAIL=""
  COMPOSE_FILE="docker-compose.yml"; COMPOSE_PROFILES="cloudflare"
  EXTRA_NOTE="In Cloudflare, route the tunnel's public hostname ($HOST) to http://caddy:80."
else
  read -rp "Your domain (DNS already pointing here, e.g. mdm.example.com): " HOST
  mdm_check_host HOST || { err "Enter the hostname only, e.g. mdm.example.com, then re-run."; exit 1; }
  read -rp "Email for Let's Encrypt: " ACME_EMAIL
  BASE_URL="https://${HOST}"; SITE_ADDRESS="${HOST}"; TUNNEL_TOKEN=""
  COMPOSE_FILE="docker-compose.yml:docker-compose.domain.yml"; COMPOSE_PROFILES=""
  EXTRA_NOTE="Make sure ${HOST} resolves to this server and ports 80/443 are open."
fi
mdm_check_base_url BASE_URL || { err "Check the hostname you entered (the name only, e.g. mdm.example.com), then re-run."; exit 1; }

say "Downloading the pull-only compose + seed…"
curl -fsSL "${RAW}/docker-compose.release.yml" -o docker-compose.yml
curl -fsSL "${RAW}/docker-compose.domain.yml"  -o docker-compose.domain.yml
mkdir -p install/sql
curl -fsSL "${RAW}/install/sql/hmdm_init.en.sql" -o install/sql/hmdm_init.en.sql
curl -fsSL "${RAW}/install/sql/post_seed.sql"    -o install/sql/post_seed.sql
mkdir -p install/lib
curl -fsSL "${RAW}/install/lib/db.sh"             -o install/lib/db.sh
# Shared seed rules with setup.sh / the native installer (seed gate, verified seed, post-seed repairs).
# Version coupling: this script runs from main, but db.sh (like the compose file and the seed) comes from the release
# tag being installed, so it matches the images (from ${BRANCH} only when the release can't be resolved; see RAW).
# Call only db.sh functions, with only the arguments and output, that the latest published release already has; a
# db.sh change becomes usable here once a release ships it. (install/lib/db.sh states the same rule for its side.)
# shellcheck source=install/lib/db.sh
. ./install/lib/db.sh

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
SERVER_VERSION=${IMAGE_TAG}
WEB_VERSION=${IMAGE_TAG}
SUPERVISOR_VERSION=latest
GITHUB_REPO=${GH_REPO}
UPDATE_CHANNEL=stable
POLL_INTERVAL_HOURS=6
CURRENT_VERSION=${CURRENT_VERSION}
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
docker compose pull || { err "Could not pull the :${IMAGE_TAG} images from ghcr.io/${IMAGE_OWNER}. Has a release been published? (cut one with: git tag v0.1.0 && git push --tags)"; exit 1; }
# The init marker the wait below reads is on the server's data volume, and the server writes it only when it is absent.
# The entrypoint removes the previous start's marker only in images after v0.3.1, and this script (served from main) may
# be installing v0.3.1 or older, so a re-run over an existing volume would read the last run's result. Remove it here:
# the old server stopped first (so it cannot write it again in between), then a one-off container of the server image
# that runs rm as the server's own user (never as root on the volume; rm -f removes a planted link, not its target).
# On a fresh install there is no container to stop and no marker to remove, and the one-off only creates the volume
# that `up` would create anyway.
say "Starting the stack…"
docker compose stop server >/dev/null 2>&1 || true
docker compose run --rm --no-deps -T -u mdmesh --entrypoint rm server -f /opt/mdmesh/initialized.txt \
  || { err "Could not remove the previous start's init marker (/opt/mdmesh/initialized.txt) from the server's volume."; exit 1; }
docker compose up -d

say "Waiting for the server to finish first-boot (Liquibase)…"
BOOTED=0
for _ in $(seq 1 60); do
  sleep 5   # first: `up -d` can return before the entrypoint has removed the previous start's marker
  if docker compose exec -T server test -s /opt/mdmesh/initialized.txt 2>/dev/null; then BOOTED=1; break; fi
done
if [ "$BOOTED" != 1 ]; then
  err "Server did not finish first-boot within ~5 minutes. Last server logs:"
  docker compose logs --tail 40 server 2>&1 || true
  err "Fix the issue above and re-run the quick start from this directory ($(pwd))."; exit 1
fi
# The marker holds "OK" or the server's initialization error (docker/entrypoint.sh removes the previous start's marker,
# so it is this boot's). It is read as the server's own user: the volume is that account's, and the container's root
# would follow a link planted there.
INIT_RESULT=$(docker compose exec -T -u mdmesh server cat /opt/mdmesh/initialized.txt 2>/dev/null || true)
if ! grep -q '^OK' <<< "$INIT_RESULT"; then
  err "The server reported an initialization error:"
  printf '%s\n' "${INIT_RESULT:0:2000}" | tr -d '\000-\010\013-\037\177' | sed 's/^/    /'   # the server's text: no control chars
  err "Fix the issue above and re-run the quick start from this directory ($(pwd))."; exit 1
fi

# Same rules as setup.sh (shared install/lib/db.sh): seed only a fresh database, verify the seed, then
# the always-run repairs that switch on QR/token enrollment (this step used to be missing here).
# shellcheck disable=SC2034  # PSQL is consumed by install/lib/db.sh
PSQL=(docker compose exec -T postgres psql -U mdmesh -d mdmesh)
STATE=$(mdm_db_state)
case "$STATE" in
  fresh)  ;;
  seeded) err "This database is already seeded — the quick start is for new installs only. To upgrade, use ./setup.sh in a clone."; exit 1 ;;
  *)      err "Could not confirm a fresh database (state: ${STATE}). Aborting before touching data."; exit 1 ;;
esac
say "Seeding settings + admin…"
if ! mdm_seed "admin@${HOST}" install/sql/hmdm_init.en.sql "$ADMIN_PASSWORD" "$RESET_TOKEN"; then
  err "Seeding failed — the install is NOT usable yet. Fix the error above and re-run."; exit 1
fi
if ! mdm_post_seed install/sql/post_seed.sql; then
  err "Post-seed repairs failed — device enrollment would not work. Fix the error above and re-run."; exit 1
fi

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
