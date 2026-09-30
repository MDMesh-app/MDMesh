#!/usr/bin/env bash
# MDMesh setup wizard. Generates secrets, writes .env, brings up the Docker stack, and seeds a
# functional admin with a generated password. Re-runnable: an existing .env is reused as-is (so
# secrets stay stable) and a database that already holds data is never re-seeded — a re-run just
# rebuilds/redeploys code and applies idempotent repairs.
#
# Usage: ./setup.sh            # interactive Docker setup (re-run safe: reuses .env, keeps data)
#        ./setup.sh --reset    # regenerate .env + secrets from scratch. ONLY safe with a fresh
#                              # database: the pgdata volume keeps the ORIGINAL DB password, so a
#                              # new DB_PASSWORD locks the server out of its own data. Run
#                              # `docker compose down -v` first if you really want a clean slate.
#        ./setup.sh --native   # hand off to the native (non-Docker) installer
#        ./setup.sh --allow-downgrade  # registry IMAGE_OWNER only: build and run a checkout older than the running
#                              # release, or one with no readable release tag (both refused by default)
set -euo pipefail
# An exported CDPATH makes cd (here and in every child, e.g. `bash -c 'cd web && …'`) resolve a relative path against
# CDPATH's directories, not the current one — silently building or reading from a same-named directory elsewhere. Unset
# it for this script and its children.
unset CDPATH
cd -P -- "$(dirname -- "$0")"

say()  { printf '\033[1;36m%s\033[0m\n' "$*"; }
warn() { printf '\033[1;33m%s\033[0m\n' "$*"; }
err()  { printf '\033[1;31m%s\033[0m\n' "$*" >&2; }
# Shared DB provisioning (seed gate, seed, post-seed repairs, password hashing) — one copy for all
# three installers. See install/lib/db.sh for the rules and why they are what they are.
# shellcheck source=install/lib/db.sh
. ./install/lib/db.sh
# shellcheck source=install/lib/version.sh
. ./install/lib/version.sh
# shellcheck source=install/lib/url.sh
. ./install/lib/url.sh
rand() { mdm_rand; }
# Update KEY in .env in place (or append it) — persists values discovered after .env was written
# (GITHUB_REPO autodetection, the release QR build args) so compose substitution + the supervisor
# container keep seeing them on later runs.
# The value is escaped for the sed replacement (\ # &), so any value is written as given.
setenv() {
  local v=${2//\\/\\\\}; v=${v//#/\\#}; v=${v//&/\\&}
  if grep -q "^$1=" .env 2>/dev/null; then sed -i "s#^$1=.*#$1=$v#" .env; else printf '%s=%s\n' "$1" "$2" >> .env; fi
}

RESET=0; ALLOW_DOWNGRADE=0
NATIVE=0; NATIVE_ARGS=()
for a in "$@"; do
  case "$a" in
    --native) NATIVE=1 ;;
    --reset)  RESET=1 ;;
    --allow-downgrade) ALLOW_DOWNGRADE=1 ;;   # Docker path only: the native installer has no apply to protect
    *)        NATIVE_ARGS+=("$a") ;;   # forwarded to the native installer (-y, -v)
  esac
done
# Hand off to the native (non-Docker) installer, passing the remaining flags through so
# `./setup.sh --native -y` really is unattended.
if [ "$NATIVE" = 1 ]; then exec ./install/install-native.sh "${NATIVE_ARGS[@]}"; fi
# Docker mode takes no other flag: a typo (--alow-downgrade) must not be silently ignored.
if [ "${#NATIVE_ARGS[@]}" -gt 0 ]; then
  err "Unknown option: ${NATIVE_ARGS[0]}"
  sed -n '/^# Usage:/,/^set -euo pipefail/p' "$(basename "$0")" | sed '$d; s/^# \{0,1\}//' >&2
  exit 2
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

# The checkout's latest release tag (install/lib/version.sh, same rule as the native installer); empty when there is no
# git or no tag. Read up front so a fresh .env already records it (CURRENT_VERSION and the locally built image tags);
# every run then refreshes CURRENT_VERSION from it below.
REPO_VERSION=$(mdm_repo_version .)

# What this run builds is what it reports (see "The running version" below), so on a registry owner, where apply.sh may
# have moved the stack to a newer release than this checkout, refuse to build unless --allow-downgrade:
# - a checkout older than the running release <running> (the .env CURRENT_VERSION, or SERVER_VERSION for an older .env
#   without it; apply.sh writes both; empty on a fresh .env) would roll the code back;
# - a checkout with no readable release tag (source tarball, no git, tags not fetched, or a nearest tag that is not
#   release-shaped, such as v0.3.1+build.5; see mdm_repo_version) is code of unknown version that would run under
#   apply's release tags.
# Called before anything is prompted for or written: right after an existing .env is read, or before a fresh one is
# asked for. Also derives APPLY_SUPPORTED from IMAGE_OWNER (persisted below).
version_preflight() {  # version_preflight <running>
  if [ "${IMAGE_OWNER:-local}" = "local" ]; then APPLY_SUPPORTED=0; else APPLY_SUPPORTED=1; fi
  [ "$APPLY_SUPPORTED" = 1 ] && [ "$ALLOW_DOWNGRADE" != 1 ] || return 0
  if [ -z "$REPO_VERSION" ]; then
    err "Refusing to build: IMAGE_OWNER=${IMAGE_OWNER} gets its updates from apply, and setup.sh can't tell which version this checkout is (no readable release tag vX.Y.Z or vX.Y.Z-pre) — build from a tagged git checkout, or re-run with --allow-downgrade to build it anyway."
    exit 1
  fi
  if mdm_version_gt "$1" "$REPO_VERSION"; then
    err "Refusing to build older code over this stack: running $1, checkout is ${REPO_VERSION} — git pull first, or re-run with --allow-downgrade."
    err "(--allow-downgrade builds and runs this checkout's code against the current database.)"
    exit 1
  fi
}

if [ -f .env ] && [ "$RESET" != 1 ]; then
  # RE-RUN: reuse the existing .env verbatim — never regenerate secrets over a live deployment.
  # The pgdata volume keeps the ORIGINAL DB password (Postgres only reads POSTGRES_PASSWORD on
  # first init), so a fresh DB_PASSWORD would brick the stack; a fresh HASH_SECRET would likewise
  # invalidate every enrolled device's token. --reset opts out (see the header for when that's safe).
  say "Existing .env found — reusing it (secrets + hosting mode kept; use --reset to start over)."
  set -a; . ./.env; set +a
  version_preflight "${CURRENT_VERSION:-${SERVER_VERSION:-}}"
  _env_base_url=${BASE_URL:-}
  mdm_check_base_url BASE_URL || { err "Fix BASE_URL in .env, then re-run."; exit 1; }
  [ "$BASE_URL" = "$_env_base_url" ] || setenv BASE_URL "$BASE_URL"   # the scheme was lower-cased: keep .env canonical
  HOST=${BASE_URL#*://}; HOST=${HOST%%/*}
  if [ "${COMPOSE_PROFILES:-}" = "cloudflare" ]; then
    MODE=1
    COMPOSE_ARGS="--profile cloudflare"
    EXTRA_NOTE="In Cloudflare, route the tunnel's public hostname ($HOST) to http://caddy:80."
  else
    MODE=2
    COMPOSE_ARGS="-f docker-compose.yml -f docker-compose.domain.yml"
    EXTRA_NOTE="Make sure ${HOST} resolves to this server and ports 80/443 are open."
  fi
else
  if [ "$RESET" = 1 ] && [ -f .env ]; then
    warn "--reset: regenerating .env. If the old database still exists it needs the OLD password —"
    warn "run 'docker compose down -v' first for a genuinely clean slate."
  fi
  version_preflight ""
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
  if [ -n "$REPO_VERSION" ]; then CURRENT_VERSION=$REPO_VERSION; fi   # else the heredoc's ${CURRENT_VERSION:-0.0.0}

  if [ "$MODE" = "1" ]; then
    read -rp "Public hostname devices will use (e.g. mdm.example.com): " HOST
    mdm_check_host HOST || { err "Enter the hostname only, e.g. mdm.example.com, then re-run."; exit 1; }
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
    mdm_check_host HOST || { err "Enter the hostname only, e.g. mdm.example.com, then re-run."; exit 1; }
    read -rp "Email for Let's Encrypt: " ACME_EMAIL
    BASE_URL="https://${HOST}"
    SITE_ADDRESS="${HOST}"
    TUNNEL_TOKEN=""
    COMPOSE_ARGS="-f docker-compose.yml -f docker-compose.domain.yml"
    COMPOSE_FILE="docker-compose.yml:docker-compose.domain.yml"
    COMPOSE_PROFILES=""
    EXTRA_NOTE="Make sure ${HOST} resolves to this server and ports 80/443 are open."
  fi
  # Checked before .env is written (install/lib/url.sh), so a mistyped hostname leaves nothing behind.
  mdm_check_base_url BASE_URL || { err "Check the hostname you entered (the name only, e.g. mdm.example.com), then re-run."; exit 1; }

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
fi

# The supervisor polls this repo's GitHub Releases (updater + the verified agent-APK mirror behind
# /files/agent.apk). Detect owner/repo from the git remote when not already configured — exactly
# like the native installer — and persist it so the supervisor container sees it.
if [ -z "${GITHUB_REPO:-}" ]; then
  GITHUB_REPO=$(git remote get-url origin 2>/dev/null | sed -E 's#(git@|https?://)[^/:]+[/:]##; s#\.git$##' || true)
  if [ -n "$GITHUB_REPO" ]; then setenv GITHUB_REPO "$GITHUB_REPO"; fi
fi

# Source builds (IMAGE_OWNER=local) cannot be updated by pulling images — keep the supervisor's one-click apply off
# (updates = git pull && ./setup.sh). A registry owner (IMAGE_OWNER=<ghcr owner>) keeps it on. version_preflight above
# derived APPLY_SUPPORTED from the IMAGE_OWNER compose sees: the sourced .env on a re-run (quotes stripped, key missing →
# unset) or the caller's env on a fresh .env (which the heredoc above wrote as ${IMAGE_OWNER:-local}) — same `:-local`
# default as docker-compose.yml.
# Persist AND export: a re-run has already exported the OLD .env value (set -a above), and compose gives the shell
# environment priority over .env, so `up` below would otherwise recreate the supervisor with the stale setting.
setenv APPLY_SUPPORTED "$APPLY_SUPPORTED"
export APPLY_SUPPORTED

# The running version. What this run builds is what it reports: `up --build` below rebuilds every image from the
# checkout, so CURRENT_VERSION (which the supervisor compares with GitHub's latest release; a stale or placeholder 0.0.0
# shows a false "Update available") and the image tags SERVER/WEB/SUPERVISOR_VERSION (compose tags each `build:` result
# with them) all follow the checkout's latest release tag (install/lib/version.sh, the native installer's rule) on EVERY
# run, whoever owns the images. Without this a `git pull && ./setup.sh` builds new code into images named after an
# older version, and on a registry owner a re-run would build old code under apply.sh's newer release tag.
# version_preflight (above) has already refused an older checkout, or an untagged one, on a registry owner unless
# --allow-downgrade.
# No release tag to read (no git, no tags fetched, or the nearest tag is not release-shaped) → keep the .env values and
# warn (CURRENT_VERSION falls back to a release-shaped SERVER_VERSION, else 0.0.0); on a registry owner this is only
# reached with --allow-downgrade.
# Persisted + exported for the same reason as APPLY_SUPPORTED.
if [ -n "$REPO_VERSION" ]; then
  CURRENT_VERSION=$REPO_VERSION
  SERVER_VERSION=$REPO_VERSION; WEB_VERSION=$REPO_VERSION; SUPERVISOR_VERSION=$REPO_VERSION
  setenv SERVER_VERSION "$SERVER_VERSION"; setenv WEB_VERSION "$WEB_VERSION"; setenv SUPERVISOR_VERSION "$SUPERVISOR_VERSION"
  export SERVER_VERSION WEB_VERSION SUPERVISOR_VERSION
else
  if [ -z "${CURRENT_VERSION:-}" ]; then
    if [ -n "$(mdm_version_core "${SERVER_VERSION:-}")" ]; then CURRENT_VERSION=$SERVER_VERSION; else CURRENT_VERSION=0.0.0; fi
  fi
  if [ "$APPLY_SUPPORTED" = 1 ]; then
    warn "--allow-downgrade: no release tag readable in this checkout — building this checkout's code under the kept tag ${CURRENT_VERSION} (SERVER_VERSION=${SERVER_VERSION:-latest})."
  else
    warn "Could not read a release tag from this checkout (git missing, tags not fetched, or the nearest tag is not a release version vX.Y.Z[-pre]) — keeping CURRENT_VERSION=${CURRENT_VERSION}."
  fi
fi
setenv CURRENT_VERSION "$CURRENT_VERSION"
export CURRENT_VERSION

say "Checking GitHub Releases for the signed agent APK…"
# Mirror of the native installer's release fetch: pull the latest release's manifest + APK, verify
# the APK's sha256 against the manifest, and bake the agent package/signing checksum + the canonical
# /files/agent.apk URL into the web build so the enrollment QR matches a real, verified APK. The
# fetched file itself is NOT hosted here — in Docker, Caddy serves /files/agent.apk straight from
# the supervisor's sha256-verified release mirror, so the download below is verification only.
# Anonymous once the repo is public; honours GITHUB_TOKEN if set. Graceful: no release, unreachable,
# or no python3/curl on the host → warn and keep the SPA's debug defaults, exactly like native.
VITE_AGENT_PACKAGE=""; VITE_AGENT_CHECKSUM=""; VITE_AGENT_APK_URL=""
if [ -n "${GITHUB_REPO:-}" ] && command -v python3 >/dev/null && command -v curl >/dev/null; then
  # gh_curl ARGS...: curl, sending GITHUB_TOKEN (when set) as an Authorization header read from stdin (-H @-, curl 7.55+),
  # never on curl's command line, which every local user can read (ps, /proc/<pid>/cmdline).
  gh_curl() {
    if [ -n "${GITHUB_TOKEN:-}" ]; then printf 'Authorization: Bearer %s\n' "$GITHUB_TOKEN" | curl -H @- "$@"
    else curl "$@"; fi
  }
  # jget NAME: the download URL of release asset NAME in the release JSON on stdin; empty (and success) when there is
  # none or the reply is not JSON (no release yet, an empty body, a rate-limit page): under set -euo pipefail a failing
  # jget would end setup.sh at the assignment instead of taking the "no published release" branch.
  jget() { python3 -c 'import sys,json;
d=json.load(sys.stdin)
def asset(n): return next((a["browser_download_url"] for a in d.get("assets",[]) if a["name"]==n),"")
print({"apk":asset("mdmesh-agent.apk"),"manifest":asset("manifest.json")}.get(sys.argv[1],""))' "$1" 2>/dev/null || true; }
  # agent_ck_ok CK: CK looks like an APK signing-certificate checksum (unpadded base64url of a SHA-256: 43 characters of
  # A-Z a-z 0-9 _ -). It is written to .env, which this script sources as root on every re-run, so nothing else from a
  # download may go there.
  # (The letters are spelled out: a range like A-Z follows the locale and matches non-ASCII letters under en_US.UTF-8.)
  agent_ck_ok() { case "$1" in *[!ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789_-]*) return 1 ;; esac; [ "${#1}" -eq 43 ]; }
  REL=$(gh_curl -fsSL "https://api.github.com/repos/${GITHUB_REPO}/releases/latest" 2>/dev/null || true)
  APK_URL=$(printf '%s' "$REL" | jget apk); MAN_URL=$(printf '%s' "$REL" | jget manifest)
  if [ -n "$APK_URL" ] && [ -n "$MAN_URL" ]; then
    MAN=$(gh_curl -fsSL "$MAN_URL" 2>/dev/null || true)
    AGENT_CK=$(printf '%s' "$MAN" | python3 -c 'import sys,json;print(json.load(sys.stdin)["components"]["apk"]["signatureChecksum"])' 2>/dev/null || true)
    WANT_SHA=$(printf '%s' "$MAN" | python3 -c 'import sys,json;print(json.load(sys.stdin)["components"]["apk"]["sha256"])' 2>/dev/null || true)
    TMP_APK=$(mktemp)
    if [ -n "$AGENT_CK" ] && ! agent_ck_ok "$AGENT_CK"; then
      warn "The release manifest's APK signing checksum is malformed (not 43 base64url characters) — the console keeps its debug enrollment defaults."
    elif gh_curl -fsSL "$APK_URL" -o "$TMP_APK" 2>/dev/null && [ -n "$AGENT_CK" ] \
       && [ "$(sha256sum "$TMP_APK" | awk '{print $1}')" = "$WANT_SHA" ]; then
      VITE_AGENT_PACKAGE="com.mdmesh.agent"; VITE_AGENT_CHECKSUM="$AGENT_CK"; VITE_AGENT_APK_URL="/files/agent.apk"
      say "Release APK verified (signing checksum ${AGENT_CK}) — the QR will point at /files/agent.apk."
    else
      warn "Could not fetch/verify the release APK — the console keeps its debug enrollment defaults."
    fi
    rm -f "$TMP_APK"
  else
    warn "No published release found for ${GITHUB_REPO} — the console keeps its debug enrollment defaults."
  fi
else
  warn "No GitHub repo detected (or python3/curl missing) — skipping the release check; the console keeps its debug enrollment defaults."
fi
# Persist for the web image build: docker-compose.yml wires these as build args (compose reads .env
# for substitution), so both this run and any later rebuild bake the same QR parameters.
setenv VITE_AGENT_PACKAGE "$VITE_AGENT_PACKAGE"
setenv VITE_AGENT_CHECKSUM "$VITE_AGENT_CHECKSUM"
setenv VITE_AGENT_APK_URL "$VITE_AGENT_APK_URL"
export VITE_AGENT_PACKAGE VITE_AGENT_CHECKSUM VITE_AGENT_APK_URL

say "Building + starting the stack…"
# shellcheck disable=SC2086
docker compose $COMPOSE_ARGS up -d --build

say "Waiting for the server to finish first-boot (Liquibase)…"
BOOTED=0
for _ in $(seq 1 60); do
  sleep 5   # first: `up -d` can return before the entrypoint has removed the previous start's marker
  if docker compose exec -T server test -s /opt/mdmesh/initialized.txt 2>/dev/null; then BOOTED=1; break; fi
done
if [ "$BOOTED" != 1 ]; then
  # Hard-fail rather than seed a half-migrated database: everything after this point assumes the
  # schema exists, and continuing silently used to leave a broken install that LOOKED successful.
  err "Server did not finish first-boot within ~5 minutes. Last server logs:"
  docker compose logs --tail 40 server 2>&1 || true
  err "Fix the issue above and re-run ./setup.sh (it reuses your .env; no need to start over)."
  exit 1
fi
# The marker holds "OK" or the server's initialization error (docker/entrypoint.sh removes the previous start's marker,
# so it is this boot's). It is read as the server's own user: the volume is that account's, and the container's root
# would follow a link planted there.
INIT_RESULT=$(docker compose exec -T -u mdmesh server cat /opt/mdmesh/initialized.txt 2>/dev/null || true)
if ! grep -q '^OK' <<< "$INIT_RESULT"; then
  err "The server reported an initialization error:"
  printf '%s\n' "${INIT_RESULT:0:2000}" | tr -d '\000-\010\013-\037\177' | sed 's/^/    /'   # the server's text: no control chars
  err "Fix the issue above and re-run ./setup.sh (it reuses your .env; no need to start over)."; exit 1
fi

# Data safety: hmdm_init.en.sql is FRESH-DB-ONLY — it DELETEs configurations and re-inserts demo
# rows, so it must NEVER run against live data. The gate is the settings row (only the seed creates
# it); counting users does NOT work because Liquibase inserts the admin user on first boot.
# shellcheck disable=SC2034  # PSQL is consumed by install/lib/db.sh
PSQL=(docker compose exec -T postgres psql -U mdmesh -d mdmesh)
case "$(mdm_db_state)" in
  fresh)  SEED=yes ;;
  seeded) SEED=no ;;
  inconsistent)
    err "The database has devices but no settings row. Refusing to seed (that would delete configurations)."
    err "Restore from a backup or fix the settings table by hand, then re-run ./setup.sh."; exit 1 ;;
  *)
    err "Could not read the database state (is the postgres container healthy?). Last postgres logs:"
    docker compose logs --tail 20 postgres 2>&1 || true; exit 1 ;;
esac

if [ "$SEED" = yes ]; then
  say "Seeding settings + admin…"
  ADMIN_PASSWORD=$(rand)
  RESET_TOKEN=$(openssl rand -hex 16)   # ≤40 chars (passwordresettoken column); forces a first-login change
  # Base settings/configs/system apps, then the generated admin password with a forced change on first
  # login. mdm_seed verifies its own postcondition and fails loudly — a silent half-seed used to leave
  # an install that LOOKED successful but could not enroll devices.
  if ! mdm_seed "admin@${HOST}" install/sql/hmdm_init.en.sql "$ADMIN_PASSWORD" "$RESET_TOKEN"; then
    err "Seeding failed — the install is NOT usable yet. Fix the error above and re-run ./setup.sh."; exit 1
  fi
else
  say "Existing data found — skipping the seed; logins and configurations untouched."
  say "  (To start from scratch instead: 'docker compose down -v' — destroys ALL data — then './setup.sh --reset'.)"
fi

# Idempotent repairs that must run on EVERY install/upgrade, fresh or not (shared with the native
# installer): the enrollment settings fix (createnewdevices + a default configuration) and the
# aux-Headwind-app scrub. See install/sql/post_seed.sql for the rationale on each statement.
if ! mdm_post_seed install/sql/post_seed.sql; then
  err "Post-seed repairs failed — device enrollment would not work. Fix the error above and re-run ./setup.sh."; exit 1
fi

echo
say "== MDMesh is up =="
echo "  Console:        ${BASE_URL}"
echo "  REST API base:  ${BASE_URL}/rest"
echo "  Recovery page:  ${BASE_URL}/recovery"
echo "  Login:          admin"
if [ "$SEED" = yes ]; then
  echo "  Password:       ${ADMIN_PASSWORD}   (temporary)"
else
  echo "  Password:       (unchanged — use your existing admin credentials)"
fi
echo
echo "  Access is via the URL above only — Postgres and the server publish no host ports;"
echo "  the edge (Caddy) is the single entry point ($([ "$MODE" = "1" ] && echo "Cloudflare Tunnel" || echo "ports 80/443"))."
echo
if [ "$SEED" = yes ]; then
  warn "Sign in with the temporary password — you'll be required to set your own on first login."
  echo
fi
echo "Next: $EXTRA_NOTE"
if [ -n "${VITE_AGENT_CHECKSUM:-}" ]; then
  echo "Agent APK: ${BASE_URL}/files/agent.apk (served from the supervisor's verified release mirror; the enrollment QR is baked to match)."
else
  echo "Host the agent APK and enroll devices from the console's Enroll page (it builds the QR with ${BASE_URL})."
fi
