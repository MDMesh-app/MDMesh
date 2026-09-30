#!/usr/bin/env bash
# Lean native (non-Docker) installer for MDMesh — Debian/Ubuntu. Stands up Postgres + Tomcat 9 + the
# server on the host and assumes you terminate TLS yourself (your own reverse proxy / cert, or Caddy in
# front). For the turnkey experience use ./setup.sh (Docker). Flags: -y/--yes (skip confirm), -v/--verbose
# (stream all output instead of hiding it in the log). Best-effort + idempotent; review before prod use.
# shellcheck source-path=SCRIPTDIR  # lets shellcheck -x follow lib/*.sh from any working directory
set -euo pipefail
# An exported CDPATH makes cd (here and in every child, e.g. `bash -c 'cd web && …'`) resolve a relative path against
# CDPATH's directories, not the current one — silently building or reading from a same-named directory elsewhere. Unset
# it for this script and its children.
unset CDPATH
# Secrets hygiene: files this script writes (the install log, ROOT.xml, temp downloads) can carry
# the DB password / hash secret, so create everything owner-only by default. Tomcat and the server
# run as root here, so 0600/0700 artifacts stay readable by the things that need them.
umask 077
export PATH="/usr/sbin:/sbin:$PATH"   # useradd/userdel/pg tools live here; not every root shell has it
# -P resolves symbolic links so REPO is the real checkout.
cd -P -- "$(dirname -- "$0")/.."
REPO="$PWD"   # repo root — used for absolute paths inside subshells (e.g. exploding the WAR)
# Shared DB provisioning rules (seed gate, verified seed, post-seed repairs) — same file setup.sh uses.
# shellcheck source=lib/db.sh
. "$REPO/install/lib/db.sh"
# shellcheck source=lib/version.sh
. "$REPO/install/lib/version.sh"
# shellcheck source=lib/url.sh
. "$REPO/install/lib/url.sh"
# as_svc_user / as_postgres / as_mdmesh_role: the service account, postgres (superuser-only statements, in the postgres
# database), or the mdmesh role (anything reading the mdmesh database), isolated from root's environment and terminal.
# shellcheck source=lib/runas.sh
. "$REPO/install/lib/runas.sh"

[ "$(id -u)" = "0" ] || { echo "Run as root (sudo)."; exit 1; }
command -v apt-get >/dev/null || { echo "This script targets Debian/Ubuntu."; exit 1; }

ASSUME_YES="${ASSUME_YES:-0}"; VERBOSE="${VERBOSE:-0}"
for a in "$@"; do case "$a" in -y|--yes) ASSUME_YES=1 ;; -v|--verbose) VERBOSE=1 ;; esac; done

# ------------------------------------------------------------------------------------------------------
# Lightweight UI (zero deps): colored status lines, a spinner for long steps, and verbose command output
# tucked into a logfile that is auto-expanded only when something fails. Run with -v to stream it inline.
# Colour/spinner auto-disable when stdout isn't a TTY or NO_COLOR is set, so piped runs stay clean.
# ------------------------------------------------------------------------------------------------------
LOGFILE="${LOGFILE:-/var/log/mdmesh-install.log}"
: > "$LOGFILE" 2>/dev/null || LOGFILE="/tmp/mdmesh-install.log"; : > "$LOGFILE" 2>/dev/null || true
# The log can capture echoed SQL (including the DB password — see the Database step), so keep it
# owner-only even if it pre-existed with looser modes (umask only covers newly created files).
chmod 600 "$LOGFILE" 2>/dev/null || true
if [ -t 1 ] && [ -z "${NO_COLOR:-}" ]; then
  c_reset=$'\033[0m'; c_dim=$'\033[2m'; c_grn=$'\033[32m'; c_red=$'\033[31m'
  c_yel=$'\033[33m'; c_cyn=$'\033[36m'; c_bold=$'\033[1m'; TTY=1
else
  c_reset=; c_dim=; c_grn=; c_red=; c_yel=; c_cyn=; c_bold=; TTY=0
fi
hr()   { printf '  %s%s%s\n' "$c_dim" '────────────────────────────────────────────────' "$c_reset"; }
step() { printf '\n%s▸%s %s%s%s\n' "$c_cyn" "$c_reset" "$c_bold" "$1" "$c_reset"; }
ok()   { printf '  %s✓%s %s\n' "$c_grn" "$c_reset" "$1"; }
info() { printf '  %s·%s %s%s%s\n' "$c_dim" "$c_reset" "$c_dim" "$1" "$c_reset"; }

_spin_pid=
_spin_start() {
  [ "$TTY" = 1 ] || { printf '  %s·%s %s…\n' "$c_dim" "$c_reset" "$1"; return; }
  local label="$1" frames='⣾⣽⣻⢿⡿⣟⣯⣷' i=0
  ( while :; do i=$(( (i+1) % ${#frames} )); printf '\r  %s%s%s %s… ' "$c_cyn" "${frames:$i:1}" "$c_reset" "$label"; sleep 0.1; done ) &
  _spin_pid=$!
}
_spin_stop() {
  [ -n "$_spin_pid" ] && { kill "$_spin_pid" 2>/dev/null || true; wait "$_spin_pid" 2>/dev/null || true; _spin_pid=; }
  [ "$TTY" = 1 ] && printf '\r\033[K'
  return 0
}
_show_log() { hr; tail -n 30 "$LOGFILE" 2>/dev/null | sed "s/^/    ${c_dim}/;s/$/${c_reset}/"; hr; printf '  full log: %s\n' "$LOGFILE"; }
_fail()     { _spin_stop; printf '  %s✗ %s%s\n' "$c_red" "$1" "$c_reset"; printf '\n  %sIt failed — last lines of the log:%s\n' "$c_yel" "$c_reset"; _show_log; exit 1; }
trap '_spin_stop; printf "\n  %s✗ install aborted (line %s)%s\n" "$c_red" "$LINENO" "$c_reset"; _show_log' ERR

# run "Label" cmd...  — run a command with a ✓/✗ summary. -v streams everything inline; on a TTY the
# default shows the command's latest output line live next to a spinner (so you can watch progress) and
# collapses to a single ✓ when it succeeds; on failure the log tail is shown automatically. Non-TTY runs
# just print a start/✓ line. Full output always goes to $LOGFILE (tail -f it, or re-run with -v).
run() {
  local label="$1"; shift
  printf '\n=== %s ===\n' "$label" >> "$LOGFILE"
  if [ "$VERBOSE" = 1 ]; then
    printf '  %s▸%s %s\n' "$c_cyn" "$c_reset" "$label"
    "$@" 2>&1 | tee -a "$LOGFILE"; [ "${PIPESTATUS[0]}" -eq 0 ] || _fail "$label"
    ok "$label"; return 0
  fi
  if [ "$TTY" != 1 ]; then
    printf '  %s·%s %s…\n' "$c_dim" "$c_reset" "$label"
    "$@" >> "$LOGFILE" 2>&1 || _fail "$label"
    ok "$label"; return 0
  fi
  # TTY: live single-line tail of the command's output, collapsing to ✓ on success.
  local frames='⣾⣽⣻⢿⡿⣟⣯⣷' fi=0 cols width rc_file rc line clip
  cols=$(tput cols 2>/dev/null || echo 100); [ "$cols" -ge 20 ] 2>/dev/null || cols=100
  width=$(( cols - ${#label} - 8 )); [ "$width" -ge 12 ] || width=12
  rc_file=$(mktemp)
  printf '\r\033[K  %s%s%s %s…' "$c_cyn" "${frames:0:1}" "$c_reset" "$label"
  # set +e is local to this pipe subshell: without it, set -e would kill the subshell at a failing
  # command before `echo $?` records the code, and the run would abort raw instead of showing ✗ + log.
  { set +e; "$@" 2>&1; echo $? > "$rc_file"; } | while IFS= read -r line; do
    printf '%s\n' "$line" >> "$LOGFILE"
    line=${line//$'\r'/}; clip=${line:0:$width}
    fi=$(( (fi + 1) % 8 ))
    printf '\r\033[K  %s%s%s %s %s%s%s' "$c_cyn" "${frames:$fi:1}" "$c_reset" "$label" "$c_dim" "$clip" "$c_reset"
  done
  rc=$(cat "$rc_file" 2>/dev/null || echo 1); rm -f "$rc_file"
  printf '\r\033[K'
  [ "$rc" -eq 0 ] || _fail "$label"
  ok "$label"
}

# ------------------------------------------------------------------------------------------------------
printf '\n  %sMDMesh · native install%s\n' "$c_bold" "$c_reset"
cat <<WARN

  ${c_yel}⚠  This will modify THIS host:${c_reset}
    • apt-get install openjdk-17-jdk, postgresql, maven, nodejs, npm, curl, python3, aapt
    • create or alter a PostgreSQL role and database "mdmesh" (resets that role's password)
    • download and unpack Apache Tomcat 9 into /opt/mdmesh-tc (clears its webapps/)
    • write config and uploaded files under /opt/mdmesh (the server logs to the systemd journal)
    • write the updater's settings to /etc/mdmesh/supervisor.env
    • start Tomcat, run database migrations, and seed the admin account

  Intended for a dedicated server you control. This script does not undo these changes.
  ${c_dim}Details are hidden — re-run with -v to stream them, or: tail -f ${LOGFILE}${c_reset}

WARN
if [ "$ASSUME_YES" != "1" ]; then
  printf '  Type "yes" to proceed: '
  read -r _confirm
  [ "$_confirm" = "yes" ] || { echo "  Aborted — no changes made."; exit 1; }
fi

rand() { mdm_rand; }

printf '\n'
# Public base URL. Override non-interactively with BASE_URL=https://mdm.example.com (required with -y).
BASE_URL="${BASE_URL:-}"
if [ -z "$BASE_URL" ]; then
  [ "$ASSUME_YES" = "1" ] && { echo "  BASE_URL must be set when running with -y (e.g. BASE_URL=https://mdm.example.com)."; exit 1; }
  read -rp "  Public base URL (e.g. https://mdm.example.com): " BASE_URL
fi
mdm_check_base_url BASE_URL || exit 1   # install/lib/url.sh: http(s)://host[:port]; lower-cases the scheme
# HTTP port Tomcat listens on. Override non-interactively with HTTP_PORT=9090; default 8080.
HTTP_PORT="${HTTP_PORT:-}"
if [ -z "$HTTP_PORT" ]; then read -rp "  HTTP port [8080]: " _p; HTTP_PORT="${_p:-8080}"; fi
case "$HTTP_PORT" in ''|*[!0-9]*) echo "  Port must be a number."; exit 1 ;; esac
{ [ "$HTTP_PORT" -ge 1 ] && [ "$HTTP_PORT" -le 65535 ]; } || { echo "  Port must be 1-65535."; exit 1; }
DB_PASSWORD=$(rand); HASH_SECRET=$(rand); ADMIN_PASSWORD=$(rand); RESET_TOKEN=$(openssl rand -hex 16)
JWT_SECRET=$(openssl rand -hex 64)   # jwt.secretkey: hex only (see the reuse rule below)
BASE_DIR=/opt/mdmesh
CATALINA=/opt/mdmesh-tc
TOMCAT_VER=9.0.89
# Tomcat lifecycle helpers. CATALINA_PID lets `catalina.sh stop -force` actually kill a JVM that ignores
# the shutdown command (the server keeps scheduler threads alive after context stop), and the pgrep
# fallback covers instances started by older versions of this script without a PID file.
export CATALINA_PID="$CATALINA/tomcat.pid"
SVC_USER=mdmesh            # unprivileged account Tomcat runs as (mirrors the Docker image)
SVC_UNIT=mdmesh-server     # systemd unit that owns Tomcat
SUP_UNIT=mdmesh-supervisor # systemd unit that owns the updater supervisor (also runs as $SVC_USER)
# The supervisor's settings (GITHUB_TOKEN included). They live outside $BASE_DIR on purpose: systemd reads an
# EnvironmentFile as root and follows links, so one inside the service user's tree would let that user point it at any
# root-only KEY=VALUE file and receive its contents in the supervisor's environment.
SUP_ENV_DIR=/etc/mdmesh
# Root writes into $CATALINA and $BASE_DIR, which this script chowns to $SVC_USER (on this run and on every earlier one),
# so a link planted there must never be followed. guard_under ROOT REL refuses (fails the install) when ROOT or any
# component of ROOT/REL is a symbolic link. write_under ROOT REL CMD... guards REL, runs CMD with its stdout going to a
# fresh mode-600 file (mktemp) in REL's directory, and renames that over REL in one step (mv -fT never follows a link or
# descends into a directory there). The temp file is named .NAME.mdmesh-tmp.XXXXXX, and the ones a killed run left
# behind (ROOT.xml's hold its secrets) are removed first, by that pattern only: a plain NAME.?????? glob would also
# delete an admin's NAME.backup. (Earlier versions used NAME.XXXXXX; such leftovers are left alone.) tc_guard/tc_write REL and base_guard/base_write REL are these for $CATALINA and $BASE_DIR. Before these run,
# Tomcat and the supervisor (both run as $SVC_USER) are stopped, every other $SVC_USER process is killed (kill_svc_user),
# and the install stops if the account has a crontab or at jobs that could start a new one (refuse_svc_user_jobs); the
# two units are started again after the last write. That leaves only a process that some other root service starts as
# $SVC_USER in between, which none does unless an admin set one up. So these guards stay, and root reads the files in
# these trees as $SVC_USER (svc_cat) rather than trusting that nothing can race it.
guard_under() {
  local root="$1" p="$1" part
  local -a parts
  IFS=/ read -r -a parts <<< "$2"
  if [ -L "$p" ]; then _fail "Refusing to write under $root: it is a symbolic link. Remove it and re-run."; fi
  for part in "${parts[@]}"; do
    p="$p/$part"
    if [ -L "$p" ]; then _fail "Refusing to write $root/$2: $p is a symbolic link (the service user owns this tree). Remove it and re-run."; fi
  done
}
write_under() {
  local root="$1" rel="$2" dir name tmp
  shift 2
  guard_under "$root" "$rel"
  dir=$(dirname "$root/$rel") name=$(basename "$rel")
  mkdir -p "$dir"
  rm -f "$dir/.$name".mdmesh-tmp.??????
  tmp=$(mktemp "$dir/.$name.mdmesh-tmp.XXXXXX")
  if "$@" > "$tmp" && chmod 600 "$tmp" && mv -fT "$tmp" "$root/$rel"; then return 0; fi
  rm -f "$tmp"
  _fail "Could not write $root/$rel"
}
tc_guard()   { guard_under "$CATALINA" "$1"; }
tc_write()   { local rel="$1"; shift; write_under "$CATALINA" "$rel" "$@"; }
base_guard() { guard_under "$BASE_DIR" "$1"; }
base_write() { local rel="$1"; shift; write_under "$BASE_DIR" "$rel" "$@"; }
have_systemd() { command -v systemctl >/dev/null 2>&1 && [ -d /run/systemd/system ]; }
# unit_installed NAME: NAME.service is installed. Not `systemctl list-unit-files | grep -q`: grep exits at the first
# match, systemctl then gets SIGPIPE writing its footer, and under pipefail the pipeline fails, so the unit reads as absent.
unit_installed() { have_systemd && [ -n "$(systemctl list-unit-files --no-legend "$1.service" 2>/dev/null)" ]; }
# Stops the supervisor unit if it exists (older installs ran it as root; either way it must not run during root writes).
# A unit that does not run as $SVC_USER is also disabled, before the first root write: the deploy step's chown -R hands
# its code to $SVC_USER, and if this run stopped before the unit is rewritten, the next boot would run that code as root.
# The supervisor step re-enables the rewritten unit; one that already runs as $SVC_USER is left enabled.
stop_supervisor() {
  if unit_installed "$SUP_UNIT"; then
    systemctl stop "$SUP_UNIT" >/dev/null 2>&1 || true
    if [ "$(systemctl show -p User --value "$SUP_UNIT" 2>/dev/null)" != "$SVC_USER" ]; then
      systemctl disable "$SUP_UNIT" >> "$LOGFILE" 2>&1 || _fail "Could not disable the old ${SUP_UNIT} unit (it runs as root)"
    fi
  fi
}
# svc_user_pids: the pids of $SVC_USER's processes, one per line, leaving out container processes that merely run as the
# same numeric uid (postgres, redis and our own server image run as uid 999, which useradd --system often hands out;
# `pkill -u` would kill them too). Those are in another PID namespace but still in the host's user namespace, which only
# a privileged container runtime sets up. A process the account starts is either in the host's PID namespace or, to get
# a PID namespace of its own without privileges, in a new user namespace too, so it is always listed. (That is why this
# is not `pgrep --ns 1 --nslist pid`: it would miss such a process.) uninstall-native.sh has the same function.
svc_user_pids() {
  local host_pid host_user p
  host_pid=$(readlink /proc/1/ns/pid) host_user=$(readlink /proc/1/ns/user)
  for p in $(pgrep -u "$SVC_USER" || true); do
    [ "$(readlink "/proc/$p/ns/pid")" != "$host_pid" ] && [ "$(readlink "/proc/$p/ns/user")" = "$host_user" ] && continue
    echo "$p"
  done
}
# Kills every process still running as $SVC_USER (svc_user_pids) and waits until none is left. Stopping the two units
# does not end a process the account started some other way (a cron or at job, or anything on a host without systemd),
# and one could race the root writes below. Before the account exists (a fresh install) there is nothing to kill.
kill_svc_user() {
  id -u "$SVC_USER" >/dev/null 2>&1 || return 0
  local _ pids
  for _ in $(seq 1 50); do
    pids=$(svc_user_pids)
    [ -n "$pids" ] || return 0
    # shellcheck disable=SC2086  # one pid per word
    kill -KILL $pids 2>/dev/null || true
    sleep 0.2
  done
  _fail "Could not stop every $SVC_USER process (still running: $(svc_user_pids | tr '\n' ' ')). Stop them and re-run."
}
# Stops the install if $SVC_USER has a crontab or pending at jobs: cron or atd could start one as $SVC_USER at any moment,
# racing the root writes, and kill_svc_user cannot stop a process that does not exist yet. The account is a system
# account this script created, and it never legitimately has either. Called twice: first before anything is changed
# (right after the port preflight), so the usual refusal leaves the running install untouched; then again after
# kill_svc_user (argument "stopped"), because a process of the account could have added a job in between, and after the
# kill none is left to add another before the writes. That second refusal comes after Tomcat and the supervisor were
# stopped, so it says so and that only a re-run restores them. Comment-only crontab lines run nothing and are ignored. The jobs are
# not printed: the account wrote them, and they are not for root's terminal.
refuse_svc_user_jobs() {
  id -u "$SVC_USER" >/dev/null 2>&1 || return 0
  local cron=0 at=0
  if command -v crontab >/dev/null 2>&1; then
    cron=$(crontab -l -u "$SVC_USER" 2>/dev/null | grep -cvE '^[[:space:]]*(#|$)' || true)
  fi
  if command -v atq >/dev/null 2>&1; then
    at=$(atq 2>/dev/null | awk -v u="$SVC_USER" '$NF == u' | grep -c . || true)
  fi
  { [ "${cron:-0}" -gt 0 ] || [ "${at:-0}" -gt 0 ]; } || return 0
  printf '\n  %s✗ the %s account has scheduled jobs%s: cron or at would run them as %s while this installer writes to\n' "$c_red" "$SVC_USER" "$c_reset" "$SVC_USER"
  printf '    its files. It is a system account this installer created, and it never has jobs of its own.\n'
  [ "${cron:-0}" -gt 0 ] && printf '    • a crontab with %s job line(s). Inspect: crontab -l -u %s   Remove: crontab -r -u %s\n' "$cron" "$SVC_USER" "$SVC_USER"
  [ "${at:-0}" -gt 0 ] && printf '    • %s at job(s). Inspect: atq, then at -c <id>   Remove: atrm <id>\n' "$at"
  printf '  Find out how they got there (it can mean the server was compromised), remove them, then re-run.\n'
  if [ "${1:-}" = stopped ]; then
    printf '  %sThe server and the updater supervisor are stopped now%s: this run stopped them before it found the jobs.\n' "$c_yel" "$c_reset"
    printf '    Remove the jobs and re-run this installer: that finishes the upgrade and starts both. Do not start the old\n'
    printf '    units by hand: the database role already has a new password the old server config lacks'
    [ "${REPLACE_DATA:-}" = yes ] && printf ' (and the database\n    was already recreated empty)'
    printf ',\n    and an updater unit from before v0.4 runs as root code that %s can change.\n' "$SVC_USER"
  fi
  exit 1
}
# svc_cat FILE: FILE's contents, read as $SVC_USER. For files in the trees that account owns: root would follow a link
# planted there and read any root-only file. Before the account exists (a fresh install, or an upgrade from a version
# whose Tomcat ran as root) nothing unprivileged owns those trees, so root reads them.
# It reads a REGULAR file only ([ -f ] rejects a FIFO, which cat would block on forever, and a symlink to a device such
# as /dev/zero, which would read without end) and at most 64 KiB (these config files are far smaller). Fails (empty, rc 1)
# when the path is missing or not a regular file, which callers already treat as "cannot read".
svc_cat() {
  if id -u "$SVC_USER" >/dev/null 2>&1; then
    # shellcheck disable=SC2016  # $1 is the inner sh's positional (the path), not a variable to expand here
    as_svc_user sh -c '[ -f "$1" ] && head -c 65536 -- "$1"' _ "$1"
  else
    [ -f "$1" ] && head -c 65536 -- "$1"
  fi
}
# as_svc_user (Tomcat's scripts as $SVC_USER), as_postgres and as_mdmesh_role (database clients) are in lib/runas.sh.
port_holder() {
  if command -v ss >/dev/null 2>&1; then ss -ltnp 2>/dev/null | awk -v p=":$HTTP_PORT$" '$4 ~ p {print; exit}'
  elif command -v lsof >/dev/null 2>&1; then lsof -iTCP:"$HTTP_PORT" -sTCP:LISTEN -nP 2>/dev/null | awk 'NR==2{print; exit}'; fi
}
# Classifies whatever listens on $HTTP_PORT: "free", "unit" (our own systemd unit), "legacy" (a Tomcat started
# from $CATALINA by an older version of this script, before the unit existed) or "foreign" (anything else —
# typically a leftover Headwind/hmdm Tomcat or another web server). Only the first three may be stopped by us.
port_owner() {
  local holder hpid
  holder=$(port_holder); [ -n "$holder" ] || { echo free; return; }
  hpid=$(printf '%s' "$holder" | grep -oE 'pid=[0-9]+' | head -n 1 | cut -d= -f2)
  if have_systemd && [ -n "$hpid" ] && [ "$(systemctl show -p MainPID --value "$SVC_UNIT" 2>/dev/null)" = "$hpid" ]; then
    echo unit
  elif [ -n "$hpid" ] && tr '\0' ' ' < "/proc/${hpid}/cmdline" 2>/dev/null | grep -q "catalina.base=${CATALINA}"; then
    echo legacy
  else
    echo foreign
  fi
}
refuse_foreign_port() {
  printf '  %s✗ port %s is already in use%s by another server:\n' "$c_red" "$HTTP_PORT" "$c_reset"
  printf '    %s%s%s\n' "$c_dim" "$(port_holder)" "$c_reset"
  printf '  Not an MDMesh Tomcat, so this installer will not stop it. Stop it yourself, or pick another port\n'
  printf '  (HTTP_PORT=9090), then re-run.  %s(fuser -k %s/tcp kills whatever holds the port)%s\n' "$c_dim" "$HTTP_PORT" "$c_reset"
  exit 1
}
stop_tomcat() {
  # Preferred: the systemd unit (cgroup-tracked, kills stragglers itself), and then catalina.sh is not run at all. The
  # catalina.sh / pgrep paths below only matter without a unit (no systemd, or a Tomcat an older version of this script
  # started). catalina.sh runs as $SVC_USER (as_svc_user), never as root; before that account exists (an install from
  # before v0.2.9, whose Tomcat ran as root) the signals below stop Tomcat on their own.
  local unit=0 p i
  if unit_installed "$SVC_UNIT"; then
    unit=1; systemctl stop "$SVC_UNIT" >/dev/null 2>&1 || true
  fi
  [ -x "$CATALINA/bin/catalina.sh" ] || return 0
  if [ "$unit" = 0 ] && id -u "$SVC_USER" >/dev/null 2>&1; then
    as_svc_user "$CATALINA/bin/catalina.sh" stop 30 -force >/dev/null 2>&1 || true
  fi
  for p in $(pgrep -f "^[^ ]*/java .*catalina.base=$CATALINA" || true); do kill "$p" 2>/dev/null || true; done
  for i in $(seq 1 30); do pgrep -f "^[^ ]*/java .*catalina.base=$CATALINA" >/dev/null || break; sleep 1; done
  for p in $(pgrep -f "^[^ ]*/java .*catalina.base=$CATALINA" || true); do kill -9 "$p" 2>/dev/null || true; done
  for i in $(seq 1 15); do [ -z "$(port_holder)" ] && break; sleep 1; done
  rm -f "$CATALINA_PID"
}
# Fail fast on a port conflict, before packages are installed, the build runs or the running server is
# stopped — losing the bind later would leave our Tomcat dead while the other server answers with 404s.
[ "$(port_owner)" = foreign ] && refuse_foreign_port
# Likewise refuse on scheduled jobs of the service account now, while the running install is untouched (it is checked
# again after the stop below, which closes the gap; see refuse_svc_user_jobs).
refuse_svc_user_jobs

# Upgrades re-run this script. hash.secret signs enrollment/sync requests and download URLs, so rotating
# it would silently break every already-enrolled device; reuse the value from the existing ROOT.xml.
# (DB_PASSWORD is different: it is re-applied to the role via ALTER USER below, so a fresh one is fine.)
_old_root="$CATALINA/conf/Catalina/localhost/ROOT.xml"
# It is read as $SVC_USER (svc_cat), whose tree $CATALINA is. If that account cannot read it (a link to a root-only file,
# or a root-owned leftover), stop: carrying on would silently rotate the secrets enrolled devices depend on.
_old_xml=
if [ -f "$_old_root" ]; then
  _old_xml=$(svc_cat "$_old_root" 2>>"$LOGFILE") || _fail "Could not read $_old_root as $SVC_USER. It holds hash.secret, which enrolled devices depend on: if it is a symbolic link, remove it; if root owns it, chown it to $SVC_USER. Then re-run."
fi
# Value of <Parameter name="$1" value="…"/> in the existing ROOT.xml; empty when there is none.
old_root_param() { printf '%s\n' "$_old_xml" | sed -n "s/.*name=\"$1\"[[:space:]]*value=\"\([^\"]*\)\".*/\1/p" | head -n 1; }
_old_secret=$(old_root_param hash.secret)
if [ -n "$_old_secret" ]; then HASH_SECRET="$_old_secret"; info "Reusing hash.secret from the existing install (enrolled devices keep working)"; fi
# jwt.secretkey signs REST API clients' JWTs (/rest/public/jwt/login), so it is kept the same way: those tokens then
# survive restarts and upgrades, and an install from before it existed gets the key generated above. JJWT 0.9.1
# base64-decodes the key and silently drops characters outside the base64 alphabet and a trailing partial 4-character
# group, so only hex, a multiple of 4 characters and at least 128 long (what we generate) is reused; anything else
# (a hand edit) is replaced. Whitespace is not part of the key (the XML parser turns a tab or newline in the value into
# a space, which the JWT library drops), so a hand edit that added some is not a reason to rotate it. docker/entrypoint.sh
# applies the same rules to JWT_SECRET and its key file.
jwt_key_ok() { case "$1" in ''|*[!0-9a-fA-F]*) return 1 ;; esac; [ "${#1}" -ge 128 ] && [ $(( ${#1} % 4 )) -eq 0 ]; }
_old_jwt=$(old_root_param jwt.secretkey | tr -d '[:space:]')
if jwt_key_ok "$_old_jwt"; then JWT_SECRET="$_old_jwt"; info "Reusing jwt.secretkey from the existing install (API clients stay signed in)"
elif [ -n "$_old_jwt" ]; then info "Replacing the existing jwt.secretkey: it is not hex, a multiple of 4 and at least 128 characters (the JWT library would drop characters)"; fi

step "Installing dependencies"
# HERMETIC BUILD: pin JDK 17 and never fall back to the host default JDK. JDK 17 is the one supported server
# JDK (CI, the Docker build + tomcat:9.0-jdk17 runtime, and this Tomcat all use it), so a host whose default is
# 21/25/… still builds and runs exactly what CI tested. (JDK 23+ would also need annotation processing enabled
# explicitly for Lombok.)
select_jdk17() {
  local c
  for c in "${JAVA17_HOME:-}" \
           /usr/lib/jvm/java-17-openjdk* /usr/lib/jvm/*temurin-17* /usr/lib/jvm/*zulu*17* \
           /usr/lib/jvm/*corretto*17* /usr/lib/jvm/*-17-* /usr/lib/jvm/*17* /opt/*jdk-17* /opt/*jdk17*; do
    [ -n "$c" ] && [ -x "$c/bin/javac" ] || continue
    case "$("$c/bin/javac" -version 2>&1)" in *' 17.'*) printf '%s' "$c"; return 0 ;; esac
  done
  return 1
}
# Install only what is missing. Asking apt for packages the host already provides another way (Node
# from nodesource, a JDK under /opt, Postgres from PGDG) is how "held broken packages" conflicts happen
# on otherwise healthy boxes — and openjdk-17-jdk is not packaged on every release (Debian 13 has 21/25).
PKGS=()
select_jdk17 >/dev/null || PKGS+=(openjdk-17-jdk)
command -v psql    >/dev/null && command -v pg_ctlcluster >/dev/null || PKGS+=(postgresql)
command -v mvn     >/dev/null || PKGS+=(maven)
command -v node    >/dev/null || PKGS+=(nodejs)
command -v npm     >/dev/null || PKGS+=(npm)
command -v curl    >/dev/null || PKGS+=(curl)
command -v python3 >/dev/null || PKGS+=(python3)
command -v aapt    >/dev/null || PKGS+=(aapt)
if [ ${#PKGS[@]} -eq 0 ]; then
  ok "all build/runtime dependencies already present — nothing to install"
else
  # Tolerate an unrelated broken third-party APT source (e.g. a Docker repo on a codename Docker doesn't
  # publish for → "does not have a Release file") — the native install only needs base Debian packages.
  run "$(IFS=,; echo "${PKGS[*]}" | sed 's/,/, /g')" bash -c \
    "apt-get update -y || echo '(some apt sources failed to refresh — continuing)'; DEBIAN_FRONTEND=noninteractive apt-get install -y ${PKGS[*]}"
fi
# minisign verifies release-manifest signatures for the updater supervisor. Best-effort: without it
# the supervisor still runs but reports releases as unverified (and never mirrors an APK).
DEBIAN_FRONTEND=noninteractive apt-get install -y minisign >> "$LOGFILE" 2>&1 || info "minisign unavailable — updater will report releases as unverified"

step "Selecting the Java 17 toolchain"
JAVA_HOME=$(select_jdk17) || {
  _spin_stop
  echo "  ${c_red}✗ no JDK 17 found${c_reset} — the server is built and run on JDK 17 (the supported server JDK)." >&2
  echo "    Install it (apt-get install -y openjdk-17-jdk) or set JAVA17_HOME to a JDK 17 home, then re-run." >&2
  exit 1
}
export JAVA_HOME
export PATH="$JAVA_HOME/bin:$PATH"
ok "$(javac -version 2>&1) — $JAVA_HOME"

step "Database"
# Idempotent: every run generates a fresh DB_PASSWORD, so ALWAYS set the role's password to match — ALTER
# if the role already exists from a previous run, else CREATE — so ROOT.xml + seeding always authenticate.
# The password reaches psql on stdin as a psql variable (:'pw' quotes it as an SQL literal), never on its command line,
# which every local user can read (ps, /proc/<pid>/cmdline). A psql error here can still echo the
# statement (password included) into $LOGFILE, which is owner-only (above).
role_password_sql() { printf '%s\n' "\\set pw $(_mdm_psql_arg "$DB_PASSWORD")" "$1 USER mdmesh WITH PASSWORD :'pw';"; }
{
  if as_postgres psql -X -tAc "SELECT 1 FROM pg_roles WHERE rolname='mdmesh'" | grep -q 1; then
    role_password_sql ALTER | as_postgres psql -X -v ON_ERROR_STOP=1
  else
    role_password_sql CREATE | as_postgres psql -X -v ON_ERROR_STOP=1
  fi
  as_postgres psql -X -tAc "SELECT 1 FROM pg_database WHERE datname='mdmesh'" | grep -q 1 || \
    as_postgres psql -X -c "CREATE DATABASE mdmesh OWNER mdmesh;"
} >> "$LOGFILE" 2>&1
ok "PostgreSQL role + database 'mdmesh' ready"

# Data safety: the seed (hmdm_init.en.sql) is FRESH-DB-ONLY — it DELETEs configurations and re-inserts a
# demo device. So decide now whether to seed. If the DB already holds data (an existing install), default
# to KEEPING it: we only deploy new code + run Liquibase migrations (non-destructive). Replacing is opt-in
# and drops the DB for a clean slate. Override non-interactively with REPLACE_DATA=yes|no.
q() { PGPASSWORD="$DB_PASSWORD" psql -h 127.0.0.1 -U mdmesh -d mdmesh -tAc "$1" 2>/dev/null | tr -d '[:space:]'; }
# A function, not `env PGPASSWORD=... psql`: env would carry the password on its command line.
mdmesh_psql() { PGPASSWORD="$DB_PASSWORD" psql -h 127.0.0.1 -U mdmesh -d mdmesh "$@"; }
# shellcheck disable=SC2034  # PSQL is consumed by install/lib/db.sh
PSQL=(mdmesh_psql)
SEED=yes
DB_STATE=$(mdm_db_state)   # fresh | seeded | inconsistent | unavailable (no schema yet on a new box)
# "unavailable" on a box that already HAS the schema means we could not read the settings table, not that
# the install is new. Seeding would DELETE configurations, so stop instead of guessing (a brand-new box has
# no users table yet and falls through to SEED=yes as before).
if [ "$DB_STATE" = unavailable ] && [ "$(q "SELECT to_regclass('public.users')")" = "users" ]; then
  printf '  %s✗ the schema exists but the settings table could not be read — not seeding over an existing install.%s\n' "$c_red" "$c_reset"
  printf '  %sCheck Postgres connectivity / the settings table, then re-run.%s\n' "$c_yel" "$c_reset"; exit 1
fi
if [ "$DB_STATE" = inconsistent ]; then
  printf '  %s✗ the database has devices but no settings row — refusing to seed (it would delete configurations).%s\n' "$c_red" "$c_reset"
  printf '  %sRestore a backup or repair the settings table by hand, then re-run.%s\n' "$c_yel" "$c_reset"; exit 1
fi
if [ "$DB_STATE" = seeded ]; then
  # count(*) is a bigint; keep only a plain integer (else "?") so nothing the mdmesh role owns can put control bytes
  # into the prompt printed to the terminal.
  n_or_q() { case "$1" in ''|*[!0-9]*) printf '?' ;; *) printf '%s' "$1" ;; esac; }
  uc=$(n_or_q "$(q "SELECT count(*) FROM users")"); dc=$(n_or_q "$(q "SELECT count(*) FROM devices")")
  REPLACE_DATA="${REPLACE_DATA:-}"
  if [ -z "$REPLACE_DATA" ]; then
    if [ "$ASSUME_YES" = 1 ]; then
      REPLACE_DATA=no   # never destroy data unprompted
    else
      printf '\n  %s%s⚠  Existing MDMesh data found: %s device(s), %s user(s).%s\n' "$c_red" "$c_bold" "$dc" "$uc" "$c_reset"
      printf '  What should the installer do with it?\n'
      printf '    %s[K]eep%s  — deploy new code + run migrations; devices, users and configs untouched %s(default)%s\n' "$c_bold" "$c_reset" "$c_bold" "$c_reset"
      printf '    %s[E]rase%s — drop the database and start from an empty seed. %sThis cannot be undone.%s\n' "$c_bold" "$c_reset" "$c_red" "$c_reset"
      printf '  Choice %s[K/e]%s: ' "$c_bold" "$c_reset"
      read -r _r
      case "$_r" in
        e|E|erase|ERASE)
          # Destructive path needs a second, typed confirmation (same convention as `gh repo delete`).
          printf '  %sType ERASE to confirm dropping %s device(s) and %s user(s):%s ' "$c_red" "$dc" "$uc" "$c_reset"
          read -r _c
          if [ "$_c" = "ERASE" ]; then REPLACE_DATA=yes; else info "Not confirmed — keeping existing data."; REPLACE_DATA=no; fi ;;
        *) REPLACE_DATA=no ;;
      esac
    fi
  fi
  if [ "$REPLACE_DATA" = yes ]; then
    info "Replacing the database — dropping $dc device(s), $uc user(s)"
    stop_tomcat   # release DB connections first
    {
      as_postgres psql -X -c "SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE datname='mdmesh' AND pid<>pg_backend_pid();"
      as_postgres psql -X -c "DROP DATABASE mdmesh;"
      as_postgres psql -X -c "CREATE DATABASE mdmesh OWNER mdmesh;"
    } >> "$LOGFILE" 2>&1
    SEED=yes
  else
    SEED=no
    ok "Keeping existing data — $dc device(s), $uc user(s) preserved (code + migrations only)"
  fi
fi

step "Building the server"
run "Maven package (JDK 17, ~1-2 min)" bash -c \
  'cp server/build.properties.example server/build.properties 2>/dev/null || true; mvn -q -B -DskipTests -pl server -am package'

step "Fetching the agent APK from GitHub Releases"
# The agent APK is a release artifact, not a repo file. Pull the latest release's signed APK (+ manifest)
# and host it at /files/agent.apk, and bake its signing checksum + package into the console build so the
# provisioning QR matches the hosted APK. Anonymous once the repo is public; honours GITHUB_TOKEN if set.
# Graceful: if there's no release yet (or it's still private/unreachable), the install continues with
# debug defaults and you host an APK manually — enrollment just needs a matching APK at /files/agent.apk.
# The APK is trusted only through the release's signed manifest, as the supervisor does: manifest.json must verify with
# minisign against release/minisign.pub before its checksum and sha256 are read, and the APK must match that sha256.
# Anything less (no signature, a bad one, no minisign, a failed download, a mismatch) is refused, with its own message.
GITHUB_REPO="${GITHUB_REPO:-$(git remote get-url origin 2>/dev/null | sed -E 's#(git@|https?://)[^/:]+[/:]##; s#\.git$##')}"
AGENT_APK=""
# The manifest, its signature and the APK are fetched into one private directory (mktemp -d). The deploy step removes it
# once the APK is hosted; this EXIT trap removes it on every other way the run ends (_fail, an error under set -e, a
# signal). It adds an EXIT trap only: the ERR trap above is unchanged and still reports an abort first.
AGENT_FETCH_DIR=""
agent_fetch_cleanup() { if [ -n "$AGENT_FETCH_DIR" ]; then rm -rf -- "$AGENT_FETCH_DIR"; AGENT_FETCH_DIR=""; fi; }
trap agent_fetch_cleanup EXIT
if [ -n "$GITHUB_REPO" ]; then
  # gh_curl ARGS...: curl, sending GITHUB_TOKEN (when set) as an Authorization header read from stdin (-H @-, curl 7.55+),
  # never on curl's command line, which every local user can read (ps, /proc/<pid>/cmdline).
  gh_curl() {
    if [ -n "${GITHUB_TOKEN:-}" ]; then printf 'Authorization: Bearer %s\n' "$GITHUB_TOKEN" | curl -H @- "$@"
    else curl "$@"; fi
  }
  # jget NAME: the download URL of release asset NAME in the release JSON on stdin; empty (and success) when there is
  # none or the reply is not JSON (no release yet, an empty body, a rate-limit page): under set -euo pipefail a failing
  # jget would end the install at the assignment instead of taking the "no published release" branch.
  jget() { python3 -c 'import sys,json;
d=json.load(sys.stdin)
def asset(n): return next((a["browser_download_url"] for a in d.get("assets",[]) if a["name"]==n),"")
print({"apk":asset("mdmesh-agent.apk"),"manifest":asset("manifest.json"),
       "signature":asset("manifest.json.minisig"),"tag":str(d.get("tag_name") or "")}.get(sys.argv[1],""))' "$1" 2>/dev/null || true; }
  # manifest_apk FIELD FILE: components.apk.FIELD of the manifest FILE; empty when it has none.
  manifest_apk() { python3 -c 'import sys,json;print(json.load(open(sys.argv[2]))["components"]["apk"][sys.argv[1]])' "$1" "$2" 2>/dev/null || true; }
  # manifest_version FILE: the manifest's top-level "version" (release/build-manifest.sh: the tag without its "v").
  manifest_version() { python3 -c 'import sys,json;v=json.load(open(sys.argv[1])).get("version");print(v if isinstance(v,str) else "")' "$1" 2>/dev/null || true; }
  # fetch_agent_apk: sets AGENT_APK (and AGENT_CK) when the latest release's APK verifies as described above; otherwise
  # says why in one line and leaves AGENT_APK empty. Always returns 0, so set -e still stops the run on anything else.
  fetch_agent_apk() {
    local rel apk_url man_url sig_url tag mver d want_sha soft=" — console uses debug defaults; host /files/agent.apk manually"
    AGENT_CK=""
    rel=$(gh_curl -fsSL "https://api.github.com/repos/${GITHUB_REPO}/releases/latest" 2>>"$LOGFILE" || true)
    apk_url=$(printf '%s' "$rel" | jget apk); man_url=$(printf '%s' "$rel" | jget manifest)
    sig_url=$(printf '%s' "$rel" | jget signature)
    if [ -z "$apk_url" ] || [ -z "$man_url" ]; then info "No published release found for ${GITHUB_REPO}${soft}"; return; fi
    if [ -z "$sig_url" ]; then
      info "The latest release of ${GITHUB_REPO} has no manifest signature (manifest.json.minisig), so its APK is not trusted${soft}"; return
    fi
    if ! command -v minisign >/dev/null 2>&1; then
      info "minisign is unavailable, so the release manifest cannot be verified and its APK is not trusted${soft}"; return
    fi
    if [ ! -r "$REPO/release/minisign.pub" ]; then
      info "The release signing key release/minisign.pub is missing from this checkout, so the manifest cannot be verified${soft}"; return
    fi
    # The ERR trap does not fire inside a function (no set -E), so a failure here must be caught here, or the install
    # would end without a word.
    AGENT_FETCH_DIR=$(mktemp -d) || { AGENT_FETCH_DIR=""; info "Could not create a temporary directory for the release files${soft}"; return 0; }
    d=$AGENT_FETCH_DIR
    if ! gh_curl -fsSL "$man_url" -o "$d/manifest.json" 2>>"$LOGFILE" \
       || ! gh_curl -fsSL "$sig_url" -o "$d/manifest.json.minisig" 2>>"$LOGFILE"; then
      info "Could not download the release manifest or its signature (details in $LOGFILE)${soft}"; return
    fi
    if ! minisign -V -p "$REPO/release/minisign.pub" -m "$d/manifest.json" >> "$LOGFILE" 2>&1; then
      info "The release manifest's signature is invalid (it does not verify with release/minisign.pub), so its APK is not trusted${soft}"; return
    fi
    # Bind the signed manifest to this release: an older release's manifest is signed too, and served in its place (a
    # replay) it would verify. Its version must be the release tag without the "v".
    tag=$(printf '%s' "$rel" | jget tag); mver=$(manifest_version "$d/manifest.json")
    if [ -z "$mver" ] || [ "$mver" != "${tag#v}" ]; then
      info "The signed manifest is for version ${mver:-(none)}, not for the latest release ${tag:-(untagged)}, so its APK is not trusted${soft}"; return
    fi
    AGENT_CK=$(manifest_apk signatureChecksum "$d/manifest.json"); want_sha=$(manifest_apk sha256 "$d/manifest.json")
    if [ -z "$AGENT_CK" ] || [ -z "$want_sha" ]; then
      AGENT_CK=""; info "The signed release manifest names no agent APK checksum or sha256${soft}"; return
    fi
    if ! gh_curl -fsSL "$apk_url" -o "$d/agent.apk" 2>>"$LOGFILE"; then
      AGENT_CK=""; info "Could not download the release APK (details in $LOGFILE)${soft}"; return
    fi
    if [ "$(sha256sum "$d/agent.apk" | awk '{print $1}')" != "$want_sha" ]; then
      AGENT_CK=""; info "The downloaded APK does not match the sha256 in the signed manifest, so it is not trusted${soft}"; return
    fi
    AGENT_APK="$d/agent.apk"
  }
  fetch_agent_apk
  if [ -n "$AGENT_APK" ]; then
    export VITE_AGENT_PACKAGE="com.mdmesh.agent" VITE_AGENT_CHECKSUM="$AGENT_CK" VITE_AGENT_APK_URL="/files/agent.apk"
    ok "release agent APK fetched: manifest signature verified, sha256 matches (checksum ${AGENT_CK})"
  else
    agent_fetch_cleanup
  fi
else
  info "No GitHub repo detected — skipping release fetch; host /files/agent.apk manually"
fi

step "Building the admin console"
# The React console (web/) calls the API at the same origin (/rest), so once it's served from the same
# Tomcat as the server there's no proxy to configure. Build it to web/dist here; deploy overlays it below.
# VITE_AGENT_* (exported above from the release, if any) bake the QR's package/checksum/APK URL.
run "npm ci + vite build (web/)" bash -c 'cd web && npm ci --no-audit --no-fund && npm run build'

step "Tomcat 9 + app deploy"
# Stop the previous instance first: dropping a new ROOT.war into a running Tomcat triggers a hot redeploy
# against the old context parameters (and the DB password we just rotated). The supervisor is stopped too, and then any
# other $SVC_USER process is killed: from here until the supervisor is started again below, root writes into $CATALINA
# and $BASE_DIR, which that account owns.
stop_tomcat
stop_supervisor
kill_svc_user
refuse_svc_user_jobs stopped
# Install Tomcat if it's missing OR a previous run left it partial/corrupt. Check for the actual launcher
# script, not just the directory, so a broken /opt/mdmesh-tc self-heals instead of failing at startup.
# archive.apache.org keeps every release permanently, so the pinned version URL never rots.
TC_FRESH=0   # 1 when root unpacks Tomcat below (see the server.xml read)
if [ ! -x "$CATALINA/bin/catalina.sh" ]; then
  # A fresh private temp file (not a fixed /tmp name) and Apache's published SHA-512; extracted with root's own
  # ownership and umask, never the archive's.
  TC_URL="https://archive.apache.org/dist/tomcat/tomcat-9/v${TOMCAT_VER}/bin/apache-tomcat-${TOMCAT_VER}.tar.gz"
  TC_TGZ=$(mktemp)
  run "Downloading Apache Tomcat ${TOMCAT_VER}" curl -fsSL --retry 3 "$TC_URL" -o "$TC_TGZ"
  TC_SHA=$(curl -fsSL --retry 3 "$TC_URL.sha512" | awk 'NR == 1 {print $1}') || TC_SHA=   # no early exit: SIGPIPE under pipefail
  if [ -z "$TC_SHA" ] || [ "$(sha512sum "$TC_TGZ" | awk '{print $1}')" != "$TC_SHA" ]; then
    rm -f "$TC_TGZ"; _fail "Tomcat download does not match Apache's published SHA-512"
  fi
  rm -rf "$CATALINA"; mkdir -p "$CATALINA"
  tar xzf "$TC_TGZ" -C "$CATALINA" --strip-components=1 --no-same-owner --no-same-permissions
  rm -f "$TC_TGZ"
  [ -x "$CATALINA/bin/catalina.sh" ] || _fail "Tomcat extract (catalina.sh missing after unpack)"
  TC_FRESH=1
  ok "Apache Tomcat ${TOMCAT_VER} installed at $CATALINA"
else
  info "Apache Tomcat already present at $CATALINA"
fi
# Point Tomcat's HTTP connector at the chosen port. Idempotent across re-runs: rewrite whatever numeric
# port currently sits on the HTTP/1.1 connector (leaves the shutdown/AJP ports untouched).
# The file is read first, as $SVC_USER (svc_cat: root never opens a file in that account's tree), unless root unpacked
# this Tomcat just above (then the file is root's own, mode 600, and no $SVC_USER process has run since). tc_write then
# refuses a link there and replaces the file without following one.
if [ "$TC_FRESH" = 1 ]; then _server_xml=$(cat -- "$CATALINA/conf/server.xml")
else _server_xml=$(svc_cat "$CATALINA/conf/server.xml" 2>>"$LOGFILE"); fi \
  || _fail "Could not read $CATALINA/conf/server.xml as $SVC_USER: if it is a symbolic link, remove it; if root owns it (a run that stopped before handing the tree over), run chown -R $SVC_USER:$SVC_USER $CATALINA. Then re-run."
tc_write conf/server.xml sed -E "s#(<Connector port=\")[0-9]+(\" protocol=\"HTTP/1.1\")#\1${HTTP_PORT}\2#" <<< "$_server_xml"
info "HTTP port set to ${HTTP_PORT}"
tc_guard webapps   # the glob below would otherwise empty a linked directory's target as root
rm -rf "$CATALINA"/webapps/*
# Deploy the server as an EXPLODED webapp (not ROOT.war) and overlay the built SPA into it, so a single
# Tomcat serves the console at / and the API at /rest on one origin. Exploding ourselves (no ROOT.war
# left behind) means Tomcat won't re-expand on restart and wipe the overlaid SPA files.
mkdir -p "$CATALINA/webapps/ROOT"
( cd "$CATALINA/webapps/ROOT" && "$JAVA_HOME/bin/jar" -xf "$REPO/server/target/launcher.war" )
cp -a "$REPO"/web/dist/. "$CATALINA/webapps/ROOT/"   # index.html + assets at / (server maps /rest,/files,/agent)
# SPA fallback (verified on Tomcat 9.0.89): !-f serves real files (assets) as-is; the negative lookahead
# leaves the API paths (/rest,/files,/agent,/update) alone; everything else → index.html so client-side routes
# survive a reload. /healthz and anything below it is left alone too, so it 404s here instead of returning the
# console's 200: native has no health route (that's Docker's edge), and a monitor pointed at it must see a failure.
# Paired with the RewriteValve declared in ROOT.xml above.
tc_write webapps/ROOT/WEB-INF/rewrite.config \
  printf 'RewriteCond %%{REQUEST_URI} !-f\nRewriteRule ^/(?!rest|files|agent|update|healthz(?:/|$))(.*)$ /index.html\n'
# $BASE_DIR is the service user's tree too: every root write there goes through base_guard/base_write (see tc_guard).
base_guard files; base_guard plugins
mkdir -p "$BASE_DIR/files" "$BASE_DIR/plugins"   # tc_write creates conf/Catalina/localhost after checking for links
# The email templates, one guarded write per file: a link planted anywhere under emails/ stops the install.
while IFS= read -r -d '' _email; do
  base_write "${_email#"$REPO/install/"}" cat "$_email"
done < <(find "$REPO/install/emails" -type f -print0)
# Host the release agent APK the QR points at (/files/agent.apk), if we fetched one above.
[ -n "$AGENT_APK" ] && { base_write files/agent.apk cat "$AGENT_APK"; ok "agent APK hosted at /files/agent.apk"; }
agent_fetch_cleanup   # the fetched release files are no longer needed (the EXIT trap covers every other path)
# ROOT.xml carries the DB password, hash.secret and jwt.secretkey: tc_write makes it mode 600.
tc_write conf/Catalina/localhost/ROOT.xml cat <<XML
<?xml version="1.0" encoding="UTF-8"?>
<Context>
    <!-- SPA fallback: serve index.html for client-side routes so a reload on /devices etc. works.
         Rules live in webapps/ROOT/WEB-INF/rewrite.config (written below). -->
    <Valve className="org.apache.catalina.valves.rewrite.RewriteValve"/>
    <Parameter name="JDBC.driver"   value="org.postgresql.Driver"/>
    <Parameter name="JDBC.url"      value="jdbc:postgresql://127.0.0.1:5432/mdmesh"/>
    <Parameter name="JDBC.username" value="mdmesh"/>
    <Parameter name="JDBC.password" value="${DB_PASSWORD}"/>
    <Parameter name="base.directory"  value="${BASE_DIR}"/>
    <Parameter name="files.directory" value="${BASE_DIR}/files"/>
    <Parameter name="base.url"        value="${BASE_URL}"/>
    <Parameter name="usage.scenario"    value="private"/>
    <Parameter name="secure.enrollment" value="0"/>
    <Parameter name="hash.secret"       value="${HASH_SECRET}"/>
    <Parameter name="jwt.secretkey"     value="${JWT_SECRET}"/>
    <Parameter name="plugins.files.directory" value="${BASE_DIR}/plugins"/>
    <Parameter name="plugin.devicelog.persistence.config.class" value="com.hmdm.plugins.devicelog.persistence.postgres.DeviceLogPostgresPersistenceConfiguration"/>
    <Parameter name="role.orgadmin.id" value="2"/>
    <Parameter name="swagger.base.path" value="/rest"/>
    <Parameter name="initialization.completion.signal.file" value="${BASE_DIR}/initialized.txt"/>
    <Parameter name="aapt.command" value="aapt"/>
    <Parameter name="mqtt.server.uri" value=""/>
    <Parameter name="mqtt.auth" value="0"/>
    <Parameter name="device.fast.search.chars" value="5"/>
    <!-- Loopback updater supervisor; /update/* is passed through by UpdateProxyServlet so the
         console's Updates + staged-rollout views are same-origin (no proxy config needed). -->
    <Parameter name="supervisor.base" value="http://127.0.0.1:9000"/>
    <!-- TODO(parity): the Docker stack wires SMTP via env (smtp.host/port/ssl/starttls/username/
         password/from — see docker/entrypoint.sh); this installer writes no smtp.* Parameters yet,
         so password-reset emails stay disabled on native installs. Add them when SMTP is needed. -->
</Context>
XML
# Tomcat runs unprivileged (like the Docker image). Create the service account and hand it the trees it
# must write: the whole Tomcat base (logs/work/temp/conf/webapps) and the app dir (uploads, plugins, marker).
if ! id -u "$SVC_USER" >/dev/null 2>&1; then
  useradd --system --home-dir "$BASE_DIR" --shell /usr/sbin/nologin "$SVC_USER"
  info "created service user $SVC_USER"
fi
# chown -R follows no links, but it would chown a hard link's target: relies on fs.protected_hardlinks=1 (the default).
chown -R "$SVC_USER:$SVC_USER" "$CATALINA" "$BASE_DIR"
ok "server + console deployed (console at /, API at /rest); ROOT.xml written; owned by $SVC_USER"

if [ "$SEED" = no ]; then
  step "Backing up the database before upgrading"
  # Liquibase migrations run against live data on the next start; keep a restorable dump first.
  BK_DIR="$BASE_DIR/backups"; base_guard backups; mkdir -p "$BK_DIR"; chmod 700 "$BK_DIR"
  BK="$BK_DIR/mdmesh-pre-upgrade-$(date +%Y%m%d-%H%M%S).dump"
  # Dumped into a fresh mktemp file that is renamed over $BK (mv -fT replaces a link planted at that name instead of
  # writing through it; mktemp already made it mode 600). Temp dumps a killed run left behind are removed first, by
  # their own .mdmesh-tmp. names only (as in write_under).
  rm -f "$BK_DIR"/.mdmesh-pre-upgrade-*.dump.mdmesh-tmp.??????
  _bk_tmp=$(mktemp "$BK_DIR/.${BK##*/}.mdmesh-tmp.XXXXXX")
  # Dumped as the mdmesh role, not as the postgres superuser (as_mdmesh_role in lib/runas.sh says why); root opens the
  # output file (a root-owned mktemp file).
  if as_mdmesh_role "$DB_PASSWORD" pg_dump -Fc > "$_bk_tmp" 2>>"$LOGFILE" && mv -fT "$_bk_tmp" "$BK"; then
    ok "pg_dump written: $BK"
    printf '    '; mdm_restore_hint "$BK"
  else
    rm -f "$_bk_tmp"
    printf '  %s✗ pg_dump failed — not upgrading without a backup. See %s%s\n' "$c_red" "$LOGFILE" "$c_reset"; exit 1
  fi
fi

step "Updater supervisor (release polling + verified agent-APK mirror)"
# The same supervisor the Docker stack runs, as a systemd unit on loopback :9000. It polls GitHub
# Releases, minisign-verifies the manifest, keeps /files/agent.apk fresh (verified releases only)
# and powers Settings→Updates + staged agent rollouts in the console (via the /update/* passthrough
# servlet). APPLY_SUPPORTED=0: native installs update server/console by re-running this installer,
# so the self-apply/rollback routes are disabled — the console shows the manual steps instead.
# It runs as $SVC_USER, like Tomcat: it has no job that needs root here, and its code and state live in $SVC_USER's
# tree. So $SUP_DIR is handed to $SVC_USER after the writes below (on a fresh install base_write creates it as root;
# on an upgrade it may hold root-owned apk/, auto.json or recovery.token from when the supervisor ran as root).
SUP_DIR="$BASE_DIR/supervisor"
for _f in server.js lib.js recovery.html; do base_write "supervisor/$_f" cat "$REPO/supervisor/$_f"; done
base_write supervisor/minisign.pub cat "$REPO/release/minisign.pub"
# chown -R follows no links, but it would chown a hard link's target: relies on fs.protected_hardlinks=1 (the default).
chown -R "$SVC_USER:$SVC_USER" "$SUP_DIR"
# The running version: the checkout's latest release tag (source installs track the repo). The
# supervisor compares it against GitHub's latest to decide "update available". Same rule as setup.sh
# (install/lib/version.sh).
CURRENT_VERSION=$(mdm_repo_version "$REPO")
# Settings go to $SUP_ENV_DIR (root-owned; see its definition). Earlier versions kept them in $BASE_DIR/supervisor.env.
rm -f "$BASE_DIR/supervisor.env"
write_under "$SUP_ENV_DIR" supervisor.env cat <<ENV
SUPERVISOR_PORT=9000
SUPERVISOR_BIND=127.0.0.1
GITHUB_REPO=${GITHUB_REPO}
GITHUB_TOKEN=${GITHUB_TOKEN:-}
UPDATE_CHANNEL=stable
POLL_INTERVAL_HOURS=6
CURRENT_VERSION=${CURRENT_VERSION:-0.0.0}
MANIFEST_PUBKEY=${SUP_DIR}/minisign.pub
APK_CACHE_DIR=${SUP_DIR}/apk
AUTO_FILE=${SUP_DIR}/auto.json
RECOVERY_TOKEN_FILE=${SUP_DIR}/recovery.token
PUBLISH_APK_TO=${BASE_DIR}/files/agent.apk
SERVER_BASE=http://127.0.0.1:${HTTP_PORT}
APPLY_SUPPORTED=0
ENV
NODE_BIN=$(command -v node)
# Node installed under a private home (nvm in /root, say) is not executable by $SVC_USER, and the unit would only
# restart-loop quietly (systemctl restart still reports success for Type=simple), so say so here.
( cd / && setpriv --reuid="$SVC_USER" --regid="$SVC_USER" --init-groups "$NODE_BIN" -e '' ) >> "$LOGFILE" 2>&1 \
  || info "the $SVC_USER user cannot run $NODE_BIN, so the supervisor will not start: install Node system-wide and re-run"
if command -v systemctl >/dev/null 2>&1 && [ -d /run/systemd/system ]; then
  cat > "/etc/systemd/system/${SUP_UNIT}.service" <<UNIT
[Unit]
Description=MDMesh updater supervisor (release polling + verified agent-APK mirror)
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
User=${SVC_USER}
Group=${SVC_USER}
EnvironmentFile=${SUP_ENV_DIR}/supervisor.env
ExecStart=${NODE_BIN} ${SUP_DIR}/server.js
Restart=always
RestartSec=5
NoNewPrivileges=true
ProtectSystem=full
ReadWritePaths=${BASE_DIR}

[Install]
WantedBy=multi-user.target
UNIT
  systemctl daemon-reload >> "$LOGFILE" 2>&1
  # enable + RESTART (not enable --now, which is a no-op on an already-running unit): re-runs
  # rewrite supervisor.env — notably CURRENT_VERSION — and a stale process would keep reporting
  # the pre-upgrade version, leaving the console's "update available" banner stuck forever.
  systemctl enable "$SUP_UNIT" >> "$LOGFILE" 2>&1
  systemctl restart "$SUP_UNIT" >> "$LOGFILE" 2>&1 \
    && ok "supervisor running v${CURRENT_VERSION:-0.0.0} as $SVC_USER (systemd unit ${SUP_UNIT}, loopback :9000)" \
    || info "supervisor unit failed to start — check: journalctl -u ${SUP_UNIT}"
else
  info "no systemd — start the supervisor manually, as $SVC_USER (never as root):"
  info "  env -i PATH=/usr/local/bin:/usr/bin:/bin sh -c 'cd /; set -a; . ${SUP_ENV_DIR}/supervisor.env; setsid setpriv --reuid=$SVC_USER --regid=$SVC_USER --init-groups --no-new-privs $NODE_BIN ${SUP_DIR}/server.js </dev/null >>/var/log/mdmesh-supervisor.log 2>&1 &'"
fi

step "Starting the server"
# Runs on the same pinned JDK 17 (JAVA_HOME exported above), matching the Docker tomcat:9.0-jdk17 image.
export CATALINA_OPTS="--add-opens java.base/java.lang=ALL-UNNAMED --add-opens java.base/java.lang.reflect=ALL-UNNAMED --add-opens java.base/java.util=ALL-UNNAMED --add-opens java.base/java.text=ALL-UNNAMED --add-opens java.desktop/java.awt.font=ALL-UNNAMED"
# Port check (the preflight above already rejected foreign holders; this catches anything that bound since).
case "$(port_owner)" in
  unit)   info "port ${HTTP_PORT} is held by our own ${SVC_UNIT} unit — restarting it"; stop_tomcat ;;
  legacy) info "port ${HTTP_PORT} is held by a Tomcat from a previous install run — stopping it"; stop_tomcat ;;
esac
[ "$(port_owner)" = free ] || refuse_foreign_port
rm -f "$BASE_DIR/initialized.txt"   # Initializer only writes the completion marker when it is absent
if have_systemd; then
  cat > "/etc/systemd/system/${SVC_UNIT}.service" <<UNIT
[Unit]
Description=MDMesh server (Tomcat 9)
After=network-online.target postgresql.service
Wants=network-online.target

[Service]
Type=simple
User=${SVC_USER}
Group=${SVC_USER}
Environment=JAVA_HOME=${JAVA_HOME}
Environment=CATALINA_HOME=${CATALINA}
Environment=CATALINA_BASE=${CATALINA}
Environment=CATALINA_PID=${CATALINA}/tomcat.pid
Environment="CATALINA_OPTS=${CATALINA_OPTS}"
ExecStart=${CATALINA}/bin/catalina.sh run
SuccessExitStatus=143
TimeoutStopSec=45
KillMode=mixed
Restart=on-failure
RestartSec=5
NoNewPrivileges=true
ProtectSystem=full
ReadWritePaths=${CATALINA} ${BASE_DIR}

[Install]
WantedBy=multi-user.target
UNIT
  systemctl daemon-reload >> "$LOGFILE" 2>&1
  systemctl enable "$SVC_UNIT" >> "$LOGFILE" 2>&1
  systemctl restart "$SVC_UNIT" >> "$LOGFILE" 2>&1 || _fail "systemctl restart ${SVC_UNIT} (see: journalctl -u ${SVC_UNIT})"
  ok "Tomcat started as $SVC_USER (systemd unit ${SVC_UNIT}; enabled at boot)"
else
  # No systemd (container/chroot): fall back to catalina.sh under the service user.
  as_svc_user "$CATALINA/bin/catalina.sh" start >> "$LOGFILE" 2>&1
  ok "Tomcat started as $SVC_USER (no systemd — not supervised)"
fi

# Readiness = the server's own completion signal. Initializer writes $BASE_DIR/initialized.txt only after
# the Guice injector — every Liquibase module, plugins included — has finished, and only if the file is
# absent, so we delete the previous run's marker before starting Tomcat and wait for a fresh one. Waiting
# on the `users` table alone was a race: the main change log creates it early while plugin change logs
# (e.g. the `plugins` table the seed updates) are still running. The users check stays as a sanity bound.
# Under the systemd unit Tomcat's stdout goes to the journal (there is no catalina.out); read the latest line
# from whichever exists so the live status line below has something to show.
last_log_line() {
  if have_systemd && systemctl is-active --quiet "$SVC_UNIT" 2>/dev/null; then journalctl -u "$SVC_UNIT" -n 1 -o cat --no-pager 2>/dev/null
  else as_svc_user tail -n 1 -- "$CATALINA/logs/catalina.out" 2>/dev/null; fi   # $SVC_USER's tree: never read as root
}
INIT_MARKER="$BASE_DIR/initialized.txt"
schema_ready() {
  [ -f "$INIT_MARKER" ] || return 1
  PGPASSWORD="$DB_PASSWORD" psql -h 127.0.0.1 -U mdmesh -d mdmesh -tAc "SELECT to_regclass('public.users')" 2>/dev/null | grep -q '^users$'
}
# Wait up to ~5 min for first boot (Liquibase) to complete. On a TTY, show the latest Tomcat log line live.
_migrate_wait() {
  local frames='⣾⣽⣻⢿⡿⣟⣯⣷' fi=0 cols width last clip i
  if [ "$TTY" != 1 ]; then
    printf '  %s·%s Running first-boot database migration…\n' "$c_dim" "$c_reset"
    for i in $(seq 1 150); do schema_ready && return 0; sleep 2; done
    return 1
  fi
  cols=$(tput cols 2>/dev/null || echo 100); [ "$cols" -ge 20 ] 2>/dev/null || cols=100
  width=$(( cols - 30 )); [ "$width" -ge 12 ] || width=12
  for i in $(seq 1 150); do
    schema_ready && { printf '\r\033[K'; return 0; }
    last=$(last_log_line | tr -d '\r'); clip=${last:0:$width}
    fi=$(( (fi + 1) % 8 ))
    printf '\r\033[K  %s%s%s migrating database %s%s%s' "$c_cyn" "${frames:$fi:1}" "$c_reset" "$c_dim" "$clip" "$c_reset"
    sleep 2
  done
  printf '\r\033[K'; return 1
}
if ! _migrate_wait; then
  printf '  %s✗ the server did not finish initializing within 5 minutes%s\n' "$c_red" "$c_reset"
  printf '  %sLiquibase or server startup likely failed — last Tomcat log lines:%s\n' "$c_yel" "$c_reset"
  hr
  if unit_installed "$SVC_UNIT"; then journalctl -u "$SVC_UNIT" -n 30 -o cat --no-pager 2>/dev/null
  else as_svc_user tail -n 30 -- "$CATALINA/logs/catalina.out" 2>/dev/null; fi | sed "s/^/    ${c_dim}/;s/$/${c_reset}/"
  printf '    %s(full logs: journalctl -u %s  /  %s/logs/)%s\n' "$c_dim" "$SVC_UNIT" "$CATALINA" "$c_reset"; hr
  exit 1
fi
# The marker carries "OK" or the initialization error text — refuse to seed on an errored boot. It is read as $SVC_USER
# (svc_cat): $BASE_DIR is that account's tree, and root would print any root-only file a link planted there points at.
if ! grep -q '^OK' < <(svc_cat "$INIT_MARKER" 2>/dev/null); then
  printf '  %s✗ the server reported an initialization error:%s\n' "$c_red" "$c_reset"
  hr; head -c 2000 < <(svc_cat "$INIT_MARKER" 2>/dev/null) | sed "s/^/    ${c_dim}/;s/$/${c_reset}/"; echo; hr; exit 1
fi
ok "database schema ready"

if [ "$SEED" = yes ]; then
  step "Seeding settings + admin account"
  HOST=$(printf '%s' "$BASE_URL" | sed -E 's#https?://##; s#/.*##')
  # mdm_seed is strict and checks its own postcondition; a half-seed must never look like success.
  if mdm_seed "admin@${HOST}" install/sql/hmdm_init.en.sql "$ADMIN_PASSWORD" "$RESET_TOKEN" 2>>"$LOGFILE"; then
    ok "admin account seeded"
  else
    printf '  %s✗ seeding failed — the install is NOT usable yet. Details: %s%s\n' "$c_red" "$LOGFILE" "$c_reset"
    tail -n 15 "$LOGFILE" | sed "s/^/    ${c_dim}/;s/$/${c_reset}/"; exit 1
  fi
else
  step "Preserving existing data"
  info "Skipped seeding — your configurations, devices and admin login are untouched"
fi

# Shared post-seed repairs (install/sql/post_seed.sql, same file setup.sh pipes in Docker) — run on
# EVERY install/upgrade, deliberately OUTSIDE the seed gate so upgrades of older installs get them
# too: the enrollment-settings fix (createnewdevices + a default configuration, without which every
# /agent/v1/enroll fails) and the aux-Headwind-app scrub. Idempotent; no-op on an empty database.
if mdm_post_seed install/sql/post_seed.sql 2>>"$LOGFILE"; then
  ok "post-seed repairs applied (enrollment settings + aux-app scrub)"
else
  printf '  %s✗ post-seed repairs failed — device enrollment would not work. Details: %s%s\n' "$c_red" "$LOGFILE" "$c_reset"
  tail -n 15 "$LOGFILE" | sed "s/^/    ${c_dim}/;s/$/${c_reset}/"; exit 1
fi

trap - ERR
printf '\n  %s%s✓ MDMesh installed (native)%s\n\n' "$c_grn" "$c_bold" "$c_reset"
printf '  %sConsole%s        %s\n' "$c_dim" "$c_reset" "${BASE_URL}"
printf '  %sREST API%s       %s/rest\n' "$c_dim" "$c_reset" "${BASE_URL}"
if [ "$SEED" = yes ]; then
  printf '  %sLogin%s          %sadmin%s / %s%s%s   %s(temporary — set your own on first login)%s\n' \
    "$c_dim" "$c_reset" "$c_bold" "$c_reset" "$c_bold" "${ADMIN_PASSWORD}" "$c_reset" "$c_dim" "$c_reset"
else
  printf '  %sLogin%s          %syour existing admin credentials (unchanged)%s\n' "$c_dim" "$c_reset" "$c_dim" "$c_reset"
fi
printf '  %sTomcat%s         %s (serving on :%s — front it with your TLS reverse proxy)\n' "$c_dim" "$c_reset" "$CATALINA" "$HTTP_PORT"
printf '  %sLocal URL%s      http://localhost:%s/\n' "$c_dim" "$c_reset" "$HTTP_PORT"
printf '  %sUpdater%s        Settings -> Updates in the console (supervisor on loopback :9000; recovery page: curl 127.0.0.1:9000)\n' "$c_dim" "$c_reset"
printf '  %sWebSockets%s     your TLS proxy MUST forward WebSocket upgrades for /agent/ws (instant commands; otherwise ~10 min polling)\n' "$c_dim" "$c_reset"
printf '\n  %sService: systemctl status %s   ·   logs: journalctl -u %s -f  /  %s/logs/%s\n' "$c_dim" "$SVC_UNIT" "$SVC_UNIT" "$CATALINA" "$c_reset"
printf '  %sInstall log: %s%s\n\n' "$c_dim" "$LOGFILE" "$c_reset"
