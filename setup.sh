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
#        ./setup.sh --update   # move this clone to the newest signed release (fetches the tags, verifies the
#                              # release's signed manifest, checks out its tag), then run as usual; with
#                              # --native, the native installer. Combine with the other options.
#
# A fresh setup's questions can be answered in advance with environment variables (MDMESH_MODE, MDMESH_HOST,
# MDMESH_TUNNEL_TOKEN or MDMESH_ACME_EMAIL) or an answers file (MDMESH_ANSWERS); MDMESH_NONINTERACTIVE=1 never asks,
# MDMESH_DRY_RUN=1 only checks the answers and prints the plan. See install/options.json and DEPLOY.md.
set -euo pipefail
# An exported CDPATH makes cd (here and in every child, e.g. `bash -c 'cd web && …'`) resolve a relative path against
# CDPATH's directories, not the current one — silently building or reading from a same-named directory elsewhere. Unset
# it for this script and its children.
unset CDPATH
# The script's directory without running dirname: no program may start before the answers are loaded (the tunnel token
# would be in its environment).
case "$0" in */*) cd -P -- "${0%/*}" ;; esac

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
# shellcheck source=install/lib/release.sh
. ./install/lib/release.sh
# shellcheck source=install/lib/answers.sh
. ./install/lib/answers.sh
# mdm_load_env: reads .env as sourcing it would, without running it (install/lib/dotenv.sh).
# shellcheck source=install/lib/dotenv.sh
. ./install/lib/dotenv.sh
# mdm_apt: apt-get, non-interactive, waiting for another apt run's lock (install/lib/apt.sh).
# shellcheck source=install/lib/apt.sh
. ./install/lib/apt.sh
rand() { mdm_rand; }
# Update KEY in .env in place (or append it) — persists values discovered after .env was written
# (GITHUB_REPO autodetection, the release QR build args) so compose substitution + the supervisor
# container keep seeing them on later runs.
# The value is escaped for the sed replacement (\ # &), so any value is written as given.
setenv() {
  local v=${2//\\/\\\\}; v=${v//#/\\#}; v=${v//&/\\&}
  if grep -q "^$1=" .env 2>/dev/null; then sed -i "s#^$1=.*#$1=$v#" .env; else printf '%s=%s\n' "$1" "$2" >> .env; fi
}

RESET=0; ALLOW_DOWNGRADE=0; UPDATE=0
NATIVE=0; NGINX_FLAG=0; NATIVE_ARGS=(); PASS_ARGS=()
for a in "$@"; do
  [ "$a" = --update ] || PASS_ARGS+=("$a")
  case "$a" in
    --update) UPDATE=1 ;;
    --native) NATIVE=1 ;;
    --nginx)  NGINX_FLAG=1 ;;
    --reset)  RESET=1 ;;
    --allow-downgrade) ALLOW_DOWNGRADE=1 ;;   # Docker path only: the native installer has no apply to protect
    *)        NATIVE_ARGS+=("$a") ;;   # forwarded to the native installer (-y, -v)
  esac
done
# Answers given in advance (install/lib/answers.sh, install/options.json): the file is read and checked here, before
# --update or --native hand over, so MDMESH_DRY_RUN from it counts for them too, and the tunnel token is out of the
# environment before any other program runs.
mdm_answers_load

# --update: move this clone to the newest signed release, then run the release's own setup.sh with the other options.
# The release tags (vX.Y.Z; pre-releases are skipped) are listed with git ls-remote, newest first. A release is trusted
# only through its signed manifest, checked as the quick start checks it (quickstart.sh's verification functions, with
# this clone's release/minisign.pub): the signature, the version (the tag), and the commit, which must be the one the tag
# points to; the clone is then checked out at that commit (a detached HEAD), so the code that runs is the signed one.
# Manifests from before 0.5.0 record no commit and are refused. A newest tag whose release has no published manifest yet
# (HTTP 404: still publishing, or failed) is skipped, saying so; with an install, the walk stops below the release this
# clone is on (going back is the downgrade check's business); it always stops after the 5 newest tags. Any other download failure (a timeout, DNS, TLS, a 5xx; each retried twice) stops the update:
# that is GitHub out of reach, not a release missing. A manifest that is there but does not verify stops it too.
# MDMESH_DRY_RUN=1 does all the checking, then stops before anything is fetched or checked out. Updates come from
# --update, never from `git pull`.
_upd_dir=''; _upd_installed=''
_upd_clean() { [ -z "$_upd_dir" ] || { rm -f -- "$_upd_dir/manifest.json" "$_upd_dir/manifest.json.minisig"; rmdir -- "$_upd_dir" 2>/dev/null || true; }; }
_upd_stop() {
  _upd_clean; err "$@"
  if [ -n "$_upd_installed" ]; then err "Nothing else was changed (it installed${_upd_installed} above)."; else err "Nothing was changed."; fi
  exit 1
}
# _upd_get URL FILE: downloads URL to FILE. 0 when done, 4 on HTTP 404 (not published), 1 on anything else (a timeout,
# DNS, TLS, a 5xx: retried twice, with short timeouts), with the reason in _upd_why.
# The HTTP code decides first: a curl set to fail on HTTP errors (`fail` in a .curlrc) exits 22 on a 404 too. (No -q:
# a proxy set in .curlrc must still be used.)
_upd_get() {
  local code rc n
  for n in 1 2 3; do
    rc=0; code=$(curl -sSL --connect-timeout 10 -m 30 -o "$2" -w '%{http_code}' "$1" 2>/dev/null) || rc=$?
    case "$rc" in
      126|127) _upd_stop "--update could not run curl (exit $rc). Reinstall it (Debian/Ubuntu: apt-get install curl), then re-run." ;;
      # A TLS set-up problem on this host, not GitHub out of reach: not retried.
      77) _upd_stop "curl has no CA certificates to check GitHub's (curl exit 77). Install them (Debian/Ubuntu: apt-get install ca-certificates), then re-run." ;;
      60) _upd_stop "curl does not trust GitHub's TLS certificate (curl exit 60): this host's CA certificates are missing or out of date (Debian/Ubuntu: apt-get install ca-certificates), or a proxy intercepts TLS." ;;
    esac
    case "$code" in
      200) [ "$rc" != 0 ] || return 0; _upd_why="curl error $rc after HTTP 200" ;;
      404) return 4 ;;
      408|429|5??) _upd_why="HTTP $code" ;;
      000|'')
        case "$rc" in
          6) _upd_why="DNS lookup failed" ;;
          7) _upd_why="connection refused" ;;
          28) _upd_why="timed out" ;;
          35) _upd_why="TLS handshake failed" ;;
          *) _upd_why="curl error $rc" ;;
        esac ;;
      *) _upd_why="HTTP $code"; return 1 ;;
    esac
    [ "$n" = 3 ] || sleep "$n"
  done
  return 1
}
# _upd_has_install: this host runs an install from this clone: a Docker .env, or a native install, with or without
# --native. /opt/mdmesh-tc itself is visible to any user (its contents are not: mode 700). (tests/setup-update checks
# the path below is exactly this.)
_upd_has_install() { [ -f .env ] || [ -d /opt/mdmesh-tc ]; }
# update_tools [installed]: what --update runs, checked before it does anything: git, curl, awk, sort, and openssl 3.0+ or minisign
# to check the release signature (with quickstart.sh's functions, loaded here). As root with --native on an apt system,
# missing ones are installed, as the native installer installs its packages anyway; otherwise it stops, naming them and
# how to install them.
update_tools() {
  local missing='' pkgs='' c n=0 sudo=''
  for c in git curl awk sort; do
    command -v "$c" >/dev/null 2>&1 && continue
    missing="$missing, $c"; n=$((n + 1))
    case "$c" in
      curl) pkgs="$pkgs curl ca-certificates" ;;   # without recommends curl comes without CA certificates (exit 77)
      awk) pkgs="$pkgs mawk" ;;
      sort) pkgs="$pkgs coreutils" ;;
      *) pkgs="$pkgs $c" ;;
    esac
  done
  # quickstart.sh's own verification functions (scripts/release-manifest-check.sh runs them the same way).
  # shellcheck source=/dev/null
  . <(sed -n '/^# >>> release verification >>>$/,/^# <<< release verification <<<$/p' quickstart.sh)
  command -v mdm_verify_manifest >/dev/null || _upd_stop "quickstart.sh in this clone has no release verification functions."
  if ! mdm_openssl_can_verify 2>/dev/null && ! command -v minisign >/dev/null 2>&1; then
    missing="$missing, openssl 3.0+ or minisign"; n=$((n + 1))
    # An openssl that is there but can't check minisign signatures (1.1.1, or no BLAKE2b): installing it again would
    # change nothing, minisign does the job.
    if command -v openssl >/dev/null 2>&1; then pkgs="$pkgs minisign"; else pkgs="$pkgs openssl"; fi
  fi
  [ -n "$missing" ] || return 0
  if [ "${1:-}" != installed ] && [ "$NATIVE" = 1 ] && [ "$EUID" = 0 ] && [ "${MDMESH_DRY_RUN:-0}" != 1 ] \
     && command -v apt-get >/dev/null 2>&1; then
    say "Installing what --update needs:${pkgs}."
    # A source that fails to refresh (a broken third-party repository) is no reason to stop; apt still locked is.
    mdm_apt update -q || { [ $? != "$MDM_APT_LOCKED" ] || _upd_stop "Could not refresh the package lists."; warn "(some apt sources failed to refresh — continuing)"; }
    # shellcheck disable=SC2086  # one package per word
    mdm_apt install -y -q $pkgs || _upd_stop "Could not install${pkgs} (apt-get above)."
    _upd_installed=$pkgs
    update_tools installed; return 0
  fi
  [ "$EUID" = 0 ] || sudo='sudo '
  if [ "$n" = 1 ]; then n=it; else n=them; fi
  _upd_stop "--update needs ${missing#, }, which this host lacks. Install $n first (Debian/Ubuntu: ${sudo}apt-get install${pkgs}), then re-run."
}
update_to_release() {
  local repo gerr refs list ver t sha tag='' tgt='' skipped='' cur curver rc mcommit dry=${MDMESH_DRY_RUN:-0} have=0 walked=0 f
  # --native changes this host's install, which needs root: stop before anything is looked up or checked out, rather
  # than move the clone and leave the install behind it.
  if [ "$NATIVE" = 1 ] && [ "$EUID" != 0 ] && [ "$dry" != 1 ]; then
    _upd_stop "--update --native updates this host's native install, which needs root: run sudo ./setup.sh --update --native"
  fi
  update_tools
  gerr=$(git rev-parse --is-inside-work-tree 2>&1) || _upd_stop "--update needs a git clone of MDMesh, and git refuses this directory: ${gerr}"
  if [ -n "$(git status --porcelain --untracked-files=no)" ]; then
    _upd_stop "This clone has local changes (see git status). --update checks out a release tag, which would mix them in or fail: commit or stash them (git stash), then re-run."
  fi
  repo=${GITHUB_REPO:-$(git remote get-url origin 2>/dev/null | sed -E 's#(git@|https?://)[^/:]+[/:]##; s#\.git$##' || true)}
  case "$repo" in
    */*/*|*[!${_MDM_ALNUM}._/-]*|''|/*|*/) _upd_stop "--update can't tell the GitHub repo of this clone (origin: $(git remote get-url origin 2>/dev/null || echo none)): set GITHUB_REPO=owner/name." ;;
    */*) ;;
    *) _upd_stop "--update can't tell the GitHub repo of this clone: set GITHUB_REPO=owner/name." ;;
  esac
  say "Looking up the release tags of origin…"
  refs=$(git ls-remote --tags origin 2>&1) || _upd_stop "Could not list origin's tags (git ls-remote --tags origin): ${refs}"
  # "X.Y.Z vX.Y.Z <commit>" per release tag (an annotated tag's ^{} line names its commit), newest first. POSIX awk
  # only: mawk, Debian 12's and Ubuntu's default, has no {n} intervals.
  list=$(printf '%s\n' "$refs" | awk -F'\t' '
    $2 ~ /^refs\/tags\/v[0-9]+\.[0-9]+\.[0-9]+(\^\{\})?$/ && length($1) == 40 && $1 ~ /^[0-9a-f]+$/ {
      t = $2; sub(/^refs\/tags\//, "", t); p = sub(/\^\{\}$/, "", t); if (p || !(t in c)) c[t] = $1 }
    END { for (t in c) print substr(t, 2), t, c[t] }' | sort -t. -k1,1nr -k2,2nr -k3,3nr)
  [ -n "$list" ] || _upd_stop "origin has no release tag (vX.Y.Z)."
  # With an install, the release this clone is on (or the nearest one before it); a fresh clone has nothing to protect.
  curver=''; if _upd_has_install; then curver=$(mdm_repo_version .); fi
  _upd_dir=$(mktemp -d) || _upd_stop "Could not make a temporary directory."
  while read -r ver t sha; do
    if [ -n "$skipped" ]; then
      [ "$walked" -lt 5 ] || _upd_stop "None of the 5 newest release tags has a published release yet (skipped:${skipped}). Try again later."
      if [ -n "$curver" ] && mdm_version_gt "$curver" "$ver"; then
        _upd_stop "No release newer than this clone's v${curver} is published yet (skipped:${skipped}). Try again later."
      fi
    fi
    walked=$((walked + 1))
    rm -f -- "$_upd_dir/manifest.json" "$_upd_dir/manifest.json.minisig"
    for f in manifest.json manifest.json.minisig; do
      rc=0; _upd_get "https://github.com/${repo}/releases/download/${t}/${f}" "$_upd_dir/$f" || rc=$?
      [ "$rc" != 1 ] || _upd_stop "Could not reach GitHub for the ${f} of ${t} (${_upd_why}). Check this host's network and re-run."
      [ "$rc" = 0 ] || break
    done
    if [ "$rc" = 4 ]; then
      warn "${t}: no published release yet (HTTP 404 for its ${f}; it may still be publishing, or it failed). Trying the next."
      skipped="$skipped $t"; continue
    fi
    say "Verifying release ${t} of ${repo}…"
    rc=0; mdm_verify_manifest "$(sed -n 2p release/minisign.pub)" "$_upd_dir/manifest.json" "$_upd_dir/manifest.json.minisig" || rc=$?
    [ "$rc" != 2 ] || _upd_stop "Checking the release signature needs OpenSSL 3.0+ or minisign. Install minisign and re-run."
    [ "$rc" = 0 ] || _upd_stop "The manifest of ${t} is not signed by the release key in release/minisign.pub (reason above)."
    [ "$(mdm_manifest_field "$_upd_dir/manifest.json" version '[0-9A-Za-z.+-]+' || true)" = "$ver" ] \
      || _upd_stop "The signed manifest at ${t} is not that release's (its version differs)."
    mcommit=$(mdm_manifest_field "$_upd_dir/manifest.json" commit '[0123456789abcdef]{40}' || true)
    [ -n "$mcommit" ] || _upd_stop "The signed manifest of ${t} records no commit (releases before 0.5.0 don't), so --update can't tie the tag to signed code. --update needs a release from 0.5.0 on: wait for one, or check out a release tag yourself (git checkout vX.Y.Z) and run ./setup.sh."
    [ "$mcommit" = "$sha" ] || _upd_stop "${t} points to ${sha}, but its signed manifest names commit ${mcommit}."
    tag=$t; tgt=$sha
    break
  done <<< "$list"
  [ -n "$tag" ] || _upd_stop "No release tag of origin has a published, signed manifest (skipped:${skipped}). A release may still be publishing: try again later."
  [ -z "$skipped" ] || warn "Using ${tag}, the newest release that is published."
  _upd_clean
  cur=$(git rev-parse HEAD)
  git cat-file -e "${tgt}^{commit}" 2>/dev/null && have=1
  if [ "$dry" = 1 ]; then
    if [ "$cur" = "$tgt" ]; then say "Dry run (MDMESH_DRY_RUN=1): this clone is already on ${tag}, which verifies. A run would run its ./setup.sh ${PASS_ARGS[*]}"
    else
      [ "$have" = 0 ] || update_downgrade_check "$cur" "$tgt" "$tag"
      say "Dry run (MDMESH_DRY_RUN=1): ${tag} verifies. A run would fetch it, check it out and run its ./setup.sh ${PASS_ARGS[*]}"
      [ "$have" = 1 ] || echo "(Whether that goes back from this checkout is checked once the tag is fetched.)"
    fi
    exit 0
  fi
  if [ "$cur" = "$tgt" ]; then say "This clone is already on ${tag}, the newest signed release."; return 0; fi
  git fetch -q --no-tags origin "refs/tags/${tag}:refs/tags/${tag}" \
    || _upd_stop "Could not fetch ${tag} from origin (above). If this clone has a different ${tag}, remove it (git tag -d ${tag}) and re-run."
  [ "$(git rev-parse --verify -q "${tag}^{commit}")" = "$tgt" ] || _upd_stop "${tag} in this clone is not the commit origin and the signed manifest name."
  update_downgrade_check "$cur" "$tgt" "$tag"
  git -c advice.detachedHead=false checkout -q --detach "$tgt" || _upd_stop "git checkout ${tag} failed (above)."
  say "Checked out ${tag} (signed release, verified)."
}
# update_downgrade_check CUR TGT TAG: stop when CUR has commits TAG doesn't (main ahead of the release, or a newer local
# tag) and this clone runs an install: checking out TAG would run older code over it. --allow-downgrade allows it.
update_downgrade_check() {
  git merge-base --is-ancestor "$1" "$2" && return 0
  [ "$ALLOW_DOWNGRADE" != 1 ] || return 0
  _upd_has_install || return 0
  _upd_stop "This clone is at $(git describe --tags --always 2>/dev/null || echo "$1"), which has commits the newest release $3 doesn't (for example main, ahead of the release). Checking out $3 would run older code over this install. Wait for the next release and run --update then, or re-run with --allow-downgrade to go to $3 now."
}
# Docker mode takes no other flag: a typo (--alow-downgrade) must not be silently ignored, nor, with --update, found
# only after the clone has moved. (With --native the native installer checks the rest.)
if [ "$NATIVE" != 1 ] && [ "${#NATIVE_ARGS[@]}" -gt 0 ]; then
  err "Unknown option: ${NATIVE_ARGS[0]}"
  sed -n '/^# Usage:/,/^set -euo pipefail/p' "$(basename "$0")" | sed '$d; s/^# \{0,1\}//' >&2
  exit 2
fi
if [ "$UPDATE" = 1 ]; then
  update_to_release
  # The release's own setup.sh, read afresh from the checked-out tag. The tunnel token, kept out of the environment of
  # everything --update ran, goes to it alone (it takes it out again at once).
  if [ -n "${MDMESH_TUNNEL_TOKEN+x}" ]; then export MDMESH_TUNNEL_TOKEN; fi
  exec ./setup.sh "${PASS_ARGS[@]}"
fi

# Hand off to the native (non-Docker) installer, passing the remaining flags through so
# `./setup.sh --native -y` really is unattended.
# The native installer has no tunnel: the token is not passed on.
if [ "$NATIVE" = 1 ]; then unset MDMESH_TUNNEL_TOKEN; exec ./install/install-native.sh "${NATIVE_ARGS[@]}"; fi

# Answers given in advance (install/lib/answers.sh, install/options.json), all checked here, before anything else.
# Only a fresh .env asks questions: a re-run reuses its .env, and then they are not used. (The file was read above.)
mdm_answer mode MODE mode MDMESH_MODE
mdm_answer host HOST hostname MDMESH_HOST
mdm_answer tunnel-token TUNNEL_TOKEN tunnel-token MDMESH_TUNNEL_TOKEN
mdm_answer acme-email ACME_EMAIL email MDMESH_ACME_EMAIL
if [ "$NGINX_FLAG" = 1 ]; then
  if mdm_given mode && [ "$MODE" != 3 ]; then warn "--nginx selects the host Nginx mode: ignoring MDMESH_MODE."; fi
  MODE=3
fi
if mdm_given mode || [ "$NGINX_FLAG" = 1 ]; then
  case "$MODE" in
    1) mdm_unused acme-email "in tunnel mode" ;;
    2) mdm_unused tunnel-token "in domain mode" ;;
    3) mdm_unused acme-email "in host-Nginx mode"; mdm_unused tunnel-token "in host-Nginx mode" ;;
  esac
fi
REUSE_ENV=0
if [ -f .env ] && [ "$RESET" != 1 ]; then REUSE_ENV=1; fi
if [ "$REUSE_ENV" = 1 ] && mdm_any_answered; then
  warn "The MDMESH_* answers are not used: an .env exists here, and a re-run reuses it (--reset starts over)."
fi
if [ "$REUSE_ENV" = 0 ]; then
  [ "$NGINX_FLAG" = 1 ] || mdm_need mode
  mdm_need host
  if mdm_given mode && [ "$MODE" = 1 ]; then mdm_need tunnel-token; fi
fi
if [ "${MDMESH_DRY_RUN:-0}" = 1 ]; then
  say "== MDMesh setup: dry run (MDMESH_DRY_RUN=1) =="
  echo "The answers check out. Nothing on this host was checked or changed. What a run would do:"
  if [ "$REUSE_ENV" = 1 ]; then
    echo "Reuse the .env here (no questions), then build the images from this checkout and start them with Docker."
    exit 0
  fi
  if [ "$NGINX_FLAG" = 1 ]; then mdm_plan_row mode 3 --nginx; else mdm_plan_line mode ''; fi
  mdm_plan_line host ''
  if ! mdm_given mode && [ "$NGINX_FLAG" != 1 ]; then mdm_plan_line tunnel-token ''; mdm_plan_line acme-email ''
  elif [ "$MODE" = 1 ]; then mdm_plan_line tunnel-token ''; mdm_plan_row acme-email - unused
  elif [ "$MODE" = 2 ]; then mdm_plan_row tunnel-token - unused; mdm_plan_line acme-email ''
  else mdm_plan_row tunnel-token - unused; mdm_plan_row acme-email - unused; fi
  echo "Then: write a new .env here$(if mdm_given host; then echo " with the base URL https://${HOST}"; fi), build the images from this checkout and start them with Docker."
  exit 0
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
    err "Refusing to build older code over this stack: running $1, checkout is ${REPO_VERSION} — run ./setup.sh --update, or re-run with --allow-downgrade."
    err "(--allow-downgrade builds and runs this checkout's code against the current database.)"
    exit 1
  fi
}

if [ "$REUSE_ENV" = 1 ]; then
  # RE-RUN: reuse the existing .env verbatim — never regenerate secrets over a live deployment.
  # The pgdata volume keeps the ORIGINAL DB password (Postgres only reads POSTGRES_PASSWORD on
  # first init), so a fresh DB_PASSWORD would brick the stack; a fresh HASH_SECRET would likewise
  # invalidate every enrolled device's token. --reset opts out (see the header for when that's safe).
  say "Existing .env found — reusing it (secrets + hosting mode kept; use --reset to start over)."
  # It holds the secrets: owner-only, also when an older version or a hand edit left it looser. (Not ours to change when
  # another user owns it: say so instead.)
  if [ -O .env ]; then chmod go= .env
  elif [ -n "$(find -L .env -prune \( -perm -040 -o -perm -004 \) -print 2>/dev/null)" ]; then   # the file, not a link
    warn ".env is readable by other users, and owned by another one: run chmod 600 .env as its owner."
  fi
  if [ ! -r .env ]; then
    _envmode=$(stat -L -c %a .env 2>/dev/null || echo '?'); case "$_envmode" in *[!0-9]*) ;; *) _envmode=$(printf '%03d' "$_envmode") ;; esac
    if [ -O .env ]; then
      err "This user ($(id -un)) can't read .env (mode ${_envmode}), its own file: make it readable to you only with chmod 600 .env, then re-run."
    else
      err "This user ($(id -un)) can't read .env: it belongs to $(stat -L -c %U .env 2>/dev/null || echo another user), mode ${_envmode}."
      err "Run ./setup.sh as that user (or with sudo if root owns it)."
    fi
    exit 1
  fi
  mdm_load_env .env
  version_preflight "${CURRENT_VERSION:-${SERVER_VERSION:-}}"
  _env_base_url=${BASE_URL:-}
  mdm_check_base_url BASE_URL || { err "Fix BASE_URL in .env, then re-run."; exit 1; }
  [ "$BASE_URL" = "$_env_base_url" ] || setenv BASE_URL "$BASE_URL"   # the scheme was lower-cased: keep .env canonical
  HOST=${BASE_URL#*://}; HOST=${HOST%%/*}
  if [ "${COMPOSE_PROFILES:-}" = "cloudflare" ]; then
    MODE=1
    COMPOSE_ARGS="--profile cloudflare"
    EXTRA_NOTE="In Cloudflare, route the tunnel's public hostname ($HOST) to http://caddy:80."
  elif [ "${HOST_NGINX:-0}" = "1" ] || [ "${COMPOSE_FILE:-}" = "docker-compose.yml:docker-compose.nginx.yml" ]; then
    MODE=3
    COMPOSE_ARGS="-f docker-compose.yml -f docker-compose.nginx.yml"
    EXTRA_NOTE="Nginx on host proxies requests to Docker server/supervisor."
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
  if [ "$NGINX_FLAG" = 1 ]; then
    MODE=3
  elif ! mdm_answered mode; then
    echo "Hosting mode:"
    echo "  1) Cloudflare Tunnel   (no open ports; Cloudflare manages TLS — needs a domain in Cloudflare)"
    echo "  2) Your own domain     (open 80/443; Caddy auto-provisions a Let's Encrypt cert)"
    echo "  3) Host Nginx          (use existing Nginx on host; reverse-proxies to Docker backend)"
    MODE=""
    while [ "$MODE" != "1" ] && [ "$MODE" != "2" ] && [ "$MODE" != "3" ]; do
      read -rp "Choose [1/2/3]: " MODE || { err "No selection (non-interactive run?). Aborting."; exit 1; }   # answer: mode
      case "$MODE" in 1|2|3) ;; *) warn "Please enter 1, 2 or 3." ;; esac
    done
  fi

  if [ "$MODE" = "3" ]; then
    command -v nginx >/dev/null || { err "Nginx command not found on host! Aborting installation for Host Nginx mode."; exit 1; }
  fi

  DB_PASSWORD=$(rand)
  HASH_SECRET=$(rand)
  if [ -n "$REPO_VERSION" ]; then CURRENT_VERSION=$REPO_VERSION; fi   # else the heredoc's ${CURRENT_VERSION:-0.0.0}

  SERVER_PORT="8080"
  SUPERVISOR_PORT="9000"
  HOST_NGINX=0

  if [ "$MODE" = "1" ]; then
    mdm_answered host || read -rp "Public hostname devices will use (e.g. mdm.example.com): " HOST   # answer: host
    mdm_check_host HOST || { err "Enter the hostname only, e.g. mdm.example.com, then re-run."; exit 1; }
    while :; do
      mdm_answered tunnel-token || read -rp "Cloudflare Tunnel token (Zero Trust → Tunnels → your tunnel): " TUNNEL_TOKEN   # answer: tunnel-token
      mdm_prompt_ok tunnel-token TUNNEL_TOKEN && break
    done
    BASE_URL="https://${HOST}"
    SITE_ADDRESS=":80"
    ACME_EMAIL=""
    COMPOSE_ARGS="--profile cloudflare"
    COMPOSE_FILE="docker-compose.yml"
    COMPOSE_PROFILES="cloudflare"
    EXTRA_NOTE="In Cloudflare, route the tunnel's public hostname ($HOST) to http://caddy:80."
  elif [ "$MODE" = "2" ]; then
    mdm_answered host || read -rp "Your domain (DNS already pointing here, e.g. mdm.example.com): " HOST   # answer: host
    mdm_check_host HOST || { err "Enter the hostname only, e.g. mdm.example.com, then re-run."; exit 1; }
    while :; do
      mdm_answered acme-email || read -rp "Email for Let's Encrypt: " ACME_EMAIL   # answer: acme-email
      mdm_prompt_ok acme-email ACME_EMAIL && break
    done
    BASE_URL="https://${HOST}"
    SITE_ADDRESS="${HOST}"
    TUNNEL_TOKEN=""
    COMPOSE_ARGS="-f docker-compose.yml -f docker-compose.domain.yml"
    COMPOSE_FILE="docker-compose.yml:docker-compose.domain.yml"
    COMPOSE_PROFILES=""
    EXTRA_NOTE="Make sure ${HOST} resolves to this server and ports 80/443 are open."
  else
    mdm_answered host || read -rp "Your domain (DNS already pointing here, e.g. mdm.example.com): " HOST   # answer: host
    mdm_check_host HOST || { err "Enter the hostname only, e.g. mdm.example.com, then re-run."; exit 1; }
    read -rp "Backend Server port [8080]: " INP_SERVER_PORT
    read -rp "Backend Supervisor port [9000]: " INP_SUPERVISOR_PORT
    SERVER_PORT="${INP_SERVER_PORT:-8080}"
    SUPERVISOR_PORT="${INP_SUPERVISOR_PORT:-9000}"
    mdm_check_port SERVER_PORT || { err "The backend server port must be a number from 1 to 65535."; exit 1; }
    mdm_check_port SUPERVISOR_PORT || { err "The backend supervisor port must be a number from 1 to 65535."; exit 1; }
    [ "$SERVER_PORT" != "$SUPERVISOR_PORT" ] || { err "The backend server and supervisor ports must differ."; exit 1; }
    HOST_NGINX=1
    BASE_URL="https://${HOST}"
    SITE_ADDRESS="${HOST}"
    ACME_EMAIL=""
    TUNNEL_TOKEN=""
    COMPOSE_ARGS="-f docker-compose.yml -f docker-compose.nginx.yml"
    COMPOSE_FILE="docker-compose.yml:docker-compose.nginx.yml"
    COMPOSE_PROFILES="none"
    EXTRA_NOTE="Nginx configured at /etc/nginx/sites-available/mdmesh.conf."
  fi
  # Checked before .env is written (install/lib/url.sh), so a mistyped hostname leaves nothing behind.
  mdm_check_base_url BASE_URL || { err "Check the hostname you entered (the name only, e.g. mdm.example.com), then re-run."; exit 1; }

  # Owner-only from the start: written to a new mode-600 file (mktemp), then renamed over .env, so no other user can
  # read the secrets at any moment, and a --reset over a looser old .env does not keep its mode.
  # A failed write or an interrupt removes it (it holds the new secrets).
  _envtmp=''
  trap '[ -z "$_envtmp" ] || rm -f -- "$_envtmp"' EXIT; trap 'exit 130' INT; trap 'exit 143' TERM
  _envtmp=$(mktemp .env.new.XXXXXX)
  cat > "$_envtmp" <<EOF
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
SERVER_PORT=${SERVER_PORT}
SUPERVISOR_PORT=${SUPERVISOR_PORT}
HOST_NGINX=${HOST_NGINX}
SMTP_HOST=
SMTP_PORT=25
SMTP_FROM=mdm@${HOST}
EOF
  # --reset as root (sudo) over someone's .env keeps it theirs, so their later re-runs can still read it.
  if [ "$EUID" = 0 ] && [ -f .env ]; then chown --reference=.env -- "$_envtmp"; fi
  mv -fT -- "$_envtmp" .env
  _envtmp=''; trap - INT TERM
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
# (updates = ./setup.sh --update). A registry owner (IMAGE_OWNER=<ghcr owner>) keeps it on. version_preflight above
# derived APPLY_SUPPORTED from the IMAGE_OWNER compose sees: the loaded .env on a re-run (quotes stripped, key missing →
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
# run, whoever owns the images. Without this an update (./setup.sh --update) builds new code into images named after an
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
  # A one-click update pins the images by digest (SERVER_VERSION=<version>@sha256:…, supervisor/apply.sh). `up --build`
  # tags what it builds with these values, and a digest can't be a build tag, so the kept values lose it.
  for _v in SERVER_VERSION WEB_VERSION SUPERVISOR_VERSION; do
    case "${!_v:-}" in *@*) printf -v "$_v" '%s' "${!_v%%@*}"; setenv "$_v" "${!_v}"; export "${_v?}" ;; esac
  done
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
# The native installer's release check (install/lib/release.sh): the latest release's manifest.json must verify with
# minisign against release/minisign.pub and belong to that release, and its APK must match the manifest's sha256; only
# then are the agent package/signing checksum + the canonical /files/agent.apk URL baked into the web build, so the
# enrollment QR matches a real, verified APK. The fetched file itself is NOT hosted here — in Docker, Caddy serves
# /files/agent.apk straight from the supervisor's verified release mirror, so the download below is verification only.
# Anonymous once the repo is public; honours GITHUB_TOKEN if set. Graceful, like native: no release, unreachable, no
# signature or minisign, or no python3/curl on the host → warn and keep the SPA's debug defaults.
VITE_AGENT_PACKAGE=""; VITE_AGENT_CHECKSUM=""; VITE_AGENT_APK_URL=""
if [ -n "${GITHUB_REPO:-}" ] && command -v python3 >/dev/null && command -v curl >/dev/null; then
  # agent_ck_ok CK: CK looks like an APK signing-certificate checksum (unpadded base64url of a SHA-256: 43 characters of
  # A-Z a-z 0-9 _ -). It is written to .env, which this script reads as root on every re-run, so nothing else from a
  # download may go there.
  # (The letters are spelled out: a range like A-Z follows the locale and matches non-ASCII letters under en_US.UTF-8.)
  agent_ck_ok() { case "$1" in *[!ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789_-]*) return 1 ;; esac; [ "${#1}" -eq 43 ]; }
  # minisign does the verifying. As root on a Debian/Ubuntu host, install it quietly like the native installer does;
  # otherwise the warning below says how.
  if ! command -v minisign >/dev/null 2>&1 && [ "$(id -u)" = 0 ] && command -v apt-get >/dev/null 2>&1; then
    say "Installing minisign (verifies the release manifest)…"
    # apt's output is hidden (it goes to stdout); mdm_apt's own lines, saying it waits for another apt run, are not.
    # (Not again after another apt run held its lock to the end: that would wait twice more.)
    { mdm_apt install -y -qq minisign || { [ $? != "$MDM_APT_LOCKED" ] && mdm_apt update -qq && mdm_apt install -y -qq minisign; }; } >/dev/null || true
  fi
  # Still no minisign on the host: verify with the one the supervisor image ships (docker/supervisor.Dockerfile), building
  # that image now (the `up --build` below reuses it). The container gets no network and only the key and the release
  # directory, read-only.
  if ! command -v minisign >/dev/null 2>&1; then
    SUPERVISOR_IMAGE="ghcr.io/${IMAGE_OWNER:-local}/mdmesh-supervisor:${SUPERVISOR_VERSION:-${CURRENT_VERSION:-latest}}"
    say "minisign is not on this host — using the one in the supervisor image (${SUPERVISOR_IMAGE})…"
    # mdm_container_minisign -V -p PUBKEY -m FILE: minisign inside the supervisor image (only this form is supported).
    mdm_container_minisign() {
      local pub="" msg=""
      while [ $# -gt 0 ]; do
        case "$1" in
          -V) shift ;;
          -p) pub=${2:-}; shift 2 ;;
          -m) msg=${2:-}; shift 2 ;;
          *) return 2 ;;
        esac
      done
      [ -n "$pub" ] && [ -n "$msg" ] && [ -r "$pub" ] && [ -r "$msg" ] || return 2
      local pub_abs msg_dir
      pub_abs="$(cd "$(dirname -- "$pub")" && pwd)/$(basename -- "$pub")"
      msg_dir="$(cd "$(dirname -- "$msg")" && pwd)"
      docker run --rm --pull never --network none --entrypoint minisign \
        -v "${pub_abs}:/verify/minisign.pub:ro" -v "${msg_dir}:/verify/release:ro" \
        "$SUPERVISOR_IMAGE" -V -p /verify/minisign.pub -m "/verify/release/$(basename -- "$msg")"
    }
    # shellcheck disable=SC2086
    if { docker image inspect "$SUPERVISOR_IMAGE" >/dev/null 2>&1 || docker compose $COMPOSE_ARGS build supervisor >/dev/null 2>&1; } \
       && docker run --rm --pull never --network none --entrypoint minisign "$SUPERVISOR_IMAGE" -v >/dev/null 2>&1; then
      MDM_MINISIGN=mdm_container_minisign
    fi
  fi
  REL_DIR=$(mktemp -d 2>/dev/null || true)
  if [ -z "$REL_DIR" ]; then
    warn "Could not create a temporary directory for the release files — the console keeps its debug enrollment defaults."
  elif mdm_fetch_release_apk "$GITHUB_REPO" ./release/minisign.pub "$REL_DIR" /dev/null; then
    if agent_ck_ok "$MDM_AGENT_CK"; then
      VITE_AGENT_PACKAGE="com.mdmesh.agent"; VITE_AGENT_CHECKSUM="$MDM_AGENT_CK"; VITE_AGENT_APK_URL="/files/agent.apk"
      say "Release APK verified (manifest signature + sha256; signing checksum ${MDM_AGENT_CK}) — the QR will point at /files/agent.apk."
    else
      warn "The release manifest's APK signing checksum is malformed (not 43 base64url characters) — the console keeps its debug enrollment defaults."
    fi
  else
    if command -v "${MDM_MINISIGN:-minisign}" >/dev/null 2>&1; then
      warn "${MDM_RELEASE_PROBLEM} — the console keeps its debug enrollment defaults."
    else
      warn "minisign is unavailable (not on this host, and the supervisor image's could not be used), so the release manifest cannot be verified — install it (e.g. apt-get install minisign) and re-run ./setup.sh; until then the console keeps its debug enrollment defaults."
    fi
  fi
  [ -z "${MDM_RELEASE_NOTE:-}" ] || warn "${MDM_RELEASE_NOTE}. Set a new GITHUB_TOKEN in .env, or remove it."
  [ -z "$REL_DIR" ] || rm -rf -- "$REL_DIR"
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

# Configure host Nginx if in Host Nginx mode
if [ "${HOST_NGINX:-0}" = "1" ]; then
  say "Setting up Host Nginx web assets and configuration…"
  # The console must match the checkout this run deploys, so build the caddy service's web image explicitly
  # (`COMPOSE_PROFILES=none` makes `up --build` skip it) and copy /srv out of the image this run just built. A
  # release's mdmesh-web.zip would be the latest release's console, which can differ from a source checkout.
  WEB_DIR="$(pwd)/web_dist"
  STAGE_DIR="$(pwd)/.web_dist.new"
  say "Building the console image for this checkout…"
  docker compose --profile caddy build caddy || { err "Could not build the console image (caddy / docker/web.Dockerfile)."; exit 1; }
  # Ask compose for the image it just built (the environment, .env and the image: line all take part), rather than
  # rebuilding the tag here from a shell variable that may be unset.
  WEB_IMAGE="$(docker compose --profile caddy config --images caddy 2>/dev/null | head -1 || true)"
  [ -n "$WEB_IMAGE" ] || { err "Could not read the console image name from docker compose."; exit 1; }
  CID=$(docker create "$WEB_IMAGE" 2>/dev/null || true)
  [ -n "$CID" ] || { err "Could not create a container from ${WEB_IMAGE} after building it."; exit 1; }
  rm -rf -- "$STAGE_DIR"
  mkdir -p -- "$STAGE_DIR"
  if ! docker cp "$CID:/srv/." "$STAGE_DIR" || [ ! -s "$STAGE_DIR/index.html" ]; then
    docker rm -f "$CID" >/dev/null 2>&1 || true
    rm -rf -- "$STAGE_DIR"
    err "Could not copy the console files out of ${WEB_IMAGE}."
    exit 1
  fi
  docker rm "$CID" >/dev/null 2>&1 || true
  # Nginx workers are unprivileged: the served tree must be readable and traversable whatever the caller's umask.
  chmod -R a+rX "$STAGE_DIR" || { rm -rf -- "$STAGE_DIR"; err "Could not set the console file permissions (${STAGE_DIR})."; exit 1; }
  # Swap the fully prepared directory in; on failure put the previous console back.
  OLD_WEB_DIR="$(pwd)/.web_dist.old"
  rm -rf -- "$OLD_WEB_DIR"
  if [ -d "$WEB_DIR" ] && ! mv -- "$WEB_DIR" "$OLD_WEB_DIR"; then
    rm -rf -- "$STAGE_DIR"
    err "Could not move the current console files aside (${WEB_DIR})."
    exit 1
  fi
  if ! mv -- "$STAGE_DIR" "$WEB_DIR"; then
    [ -d "$OLD_WEB_DIR" ] && mv -- "$OLD_WEB_DIR" "$WEB_DIR"
    rm -rf -- "$STAGE_DIR"
    err "Could not put the console files in place (${WEB_DIR})."
    exit 1
  fi
  rm -rf -- "$OLD_WEB_DIR"

  # Certbot check
  if command -v certbot >/dev/null; then
    say "Running certbot to configure SSL/TLS..."
    certbot -d "$HOST" || warn "Certbot failed or was cancelled. Please configure HTTPS manually."
  else
    warn "certbot was not found. Please configure SSL/TLS certificates for ${HOST} in Nginx manually."
  fi

  # Generate Nginx configuration
  NGINX_CONF_AVAILABLE="/etc/nginx/sites-available/mdmesh.conf"
  NGINX_CONF_ENABLED="/etc/nginx/sites-enabled/mdmesh.conf"

  TMP_NGINX_CONF=$(mktemp)
  sed -e "s#__HOST__#${HOST}#g" \
      -e "s#__WEB_ROOT__#${WEB_DIR}#g" \
      -e "s#__SERVER_PORT__#${SERVER_PORT:-8080}#g" \
      -e "s#__SUPERVISOR_PORT__#${SUPERVISOR_PORT:-9000}#g" \
      docker/nginx.conf.template > "$TMP_NGINX_CONF"

  if [ -w "/etc/nginx/sites-available" ] || [ "$EUID" -eq 0 ]; then
    mkdir -p /etc/nginx/sites-available /etc/nginx/sites-enabled
    cp "$TMP_NGINX_CONF" "$NGINX_CONF_AVAILABLE"
    ln -sf "$NGINX_CONF_AVAILABLE" "$NGINX_CONF_ENABLED"
    rm -f "$TMP_NGINX_CONF"
    if command -v nginx >/dev/null; then
      nginx -t && (systemctl reload nginx 2>/dev/null || nginx -s reload 2>/dev/null || true)
    fi
  else
    say "Root permissions required to write /etc/nginx/sites-available/mdmesh.conf."
    say "Writing generated configuration to ./mdmesh.nginx.conf..."
    cp "$TMP_NGINX_CONF" ./mdmesh.nginx.conf
    rm -f "$TMP_NGINX_CONF"
    warn "Please copy ./mdmesh.nginx.conf to /etc/nginx/sites-available/mdmesh.conf, link to sites-enabled, and reload Nginx."
  fi
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
