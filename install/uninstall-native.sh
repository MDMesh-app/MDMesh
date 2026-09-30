#!/usr/bin/env bash
# Remove a native (non-Docker) MDMesh install made by install/install-native.sh.
#
#   sudo ./install/uninstall-native.sh              # interactive: shows what goes, asks you to type UNINSTALL
#   sudo ./install/uninstall-native.sh --keep-data  # remove code/services but keep the database + uploaded files
#   sudo ./install/uninstall-native.sh -y           # unattended (still takes a final pg_dump unless --no-backup)
#
# Removes: Tomcat (/opt/mdmesh-tc), the app dir (/opt/mdmesh), the mdmesh-supervisor systemd unit and its settings
# (/etc/mdmesh), the install log, and — unless --keep-data — the PostgreSQL database + role "mdmesh". A final dump is
# written first.
# Leaves alone: apt packages (postgresql, maven, node, …), your reverse proxy/TLS, and the git checkout.
# shellcheck source-path=SCRIPTDIR  # lets shellcheck -x follow lib/*.sh from any working directory
set -euo pipefail
# An exported CDPATH makes cd (here and in every child, e.g. `bash -c 'cd web && …'`) resolve a relative path against
# CDPATH's directories, not the current one — silently building or reading from a same-named directory elsewhere. Unset
# it for this script and its children.
unset CDPATH
umask 077
export PATH="/usr/sbin:/sbin:$PATH"   # useradd/userdel/pg tools live here; not every root shell has it
[ "$(id -u)" = "0" ] || { echo "Run as root (sudo)."; exit 1; }
# as_svc_user / as_postgres / as_mdmesh_role, shared with install-native.sh: the service account, postgres (superuser-only
# statements, in the postgres database), or the mdmesh role (anything reading the mdmesh database), isolated from
# root's environment and terminal.
# shellcheck source=lib/runas.sh
. "$(cd -P -- "$(dirname -- "$0")" && pwd -P)/lib/runas.sh"

BASE_DIR=/opt/mdmesh
CATALINA=/opt/mdmesh-tc
UNIT=/etc/systemd/system/mdmesh-supervisor.service
SUP_ENV_DIR=/etc/mdmesh   # the supervisor's settings (install-native.sh)
SERVER_UNIT=/etc/systemd/system/mdmesh-server.service
SVC_USER=mdmesh
CATALINA_PID="$CATALINA/tomcat.pid"   # as_svc_user passes it to catalina.sh (the installer's value)
INSTALL_LOG=/var/log/mdmesh-install.log
KEEP_DATA=0; YES=0; BACKUP=1
for a in "$@"; do
  case "$a" in
    --keep-data) KEEP_DATA=1 ;;
    -y|--yes)    YES=1 ;;
    --no-backup) BACKUP=0 ;;
    -h|--help)   sed -n '2,11p' "$0"; exit 0 ;;
    *) echo "Unknown flag: $a (see --help)"; exit 1 ;;
  esac
done

# svc_user_pids: the pids of $SVC_USER's processes, one per line, leaving out container processes that merely run as the
# same numeric uid (another PID namespace, but the host's user namespace: only a privileged container runtime sets that
# up). Same function as in install-native.sh; see the reasoning there.
svc_user_pids() {
  local host_pid host_user p
  host_pid=$(readlink /proc/1/ns/pid) host_user=$(readlink /proc/1/ns/user)
  for p in $(pgrep -u "$SVC_USER" || true); do
    [ "$(readlink "/proc/$p/ns/pid")" != "$host_pid" ] && [ "$(readlink "/proc/$p/ns/user")" = "$host_user" ] && continue
    echo "$p"
  done
}
db_exists() { as_postgres psql -X -tAc "SELECT 1 FROM pg_database WHERE datname='mdmesh'" 2>/dev/null | grep -q 1; }
# The mdmesh role's password, from the installed ROOT.xml, read as $SVC_USER (the file is in that account's tree; see
# svc_cat in install-native.sh); empty when it cannot be read. Everything below that reads the mdmesh database connects
# as that role (as_mdmesh_role), never as the postgres superuser: see lib/runas.sh for why.
# Read as a REGULAR file only and bounded to 64 KiB (see svc_cat in install-native.sh): a FIFO here would hang the
# uninstaller, a symlink to /dev/zero would read forever.
# shellcheck disable=SC2016  # $1 is the inner sh's positional (the ROOT.xml path), not a variable to expand here
DB_PW=$(as_svc_user sh -c '[ -f "$1" ] && head -c 65536 -- "$1"' _ "$CATALINA/conf/Catalina/localhost/ROOT.xml" 2>/dev/null \
        | sed -n 's/.*name="JDBC.password"[[:space:]]*value="\([^"]*\)".*/\1/p' | head -n 1) || DB_PW=""
# n_or_q RESULT: RESULT when it is a plain non-negative integer, else "?". The counts print to root's terminal, and the
# mdmesh role owns these relations (and its search_path), so it must not slip control bytes into that summary; count(*)
# per relation is a bigint, and this rejects anything else. Each count is its own query — no SQL-side "||", whose
# operator the role could shadow to return arbitrary text.
n_or_q() { case "$1" in ''|*[!0-9]*) printf '?' ;; *) printf '%s' "$1" ;; esac; }
counts=""
if db_exists; then
  if [ -z "$DB_PW" ]; then
    counts="unreadable (no database password in ROOT.xml)"
  else
    _cd=$(as_mdmesh_role "$DB_PW" psql -X -tAc 'SELECT count(*) FROM devices' 2>/dev/null) || _cd=""
    _cc=$(as_mdmesh_role "$DB_PW" psql -X -tAc 'SELECT count(*) FROM configurations' 2>/dev/null) || _cc=""
    _cu=$(as_mdmesh_role "$DB_PW" psql -X -tAc 'SELECT count(*) FROM users' 2>/dev/null) || _cu=""
    counts="$(n_or_q "$_cd") device(s), $(n_or_q "$_cc") configuration(s), $(n_or_q "$_cu") user(s)"
  fi
fi

echo
echo "  MDMesh native uninstall — this host will lose:"
[ -d "$CATALINA" ] && echo "    • Tomcat + deployed server:   $CATALINA"
[ -f "$SERVER_UNIT" ] && echo "    • server service:             mdmesh-server (systemd unit removed)"
id -u "$SVC_USER" >/dev/null 2>&1 && [ "$KEEP_DATA" != 1 ] && echo "    • service user:               $SVC_USER"
[ -f "$UNIT" ]     && echo "    • updater service:            mdmesh-supervisor (systemd unit removed)"
[ -d "$SUP_ENV_DIR" ] && echo "    • updater settings:           $SUP_ENV_DIR"
if [ "$KEEP_DATA" = 1 ]; then
  [ -d "$BASE_DIR" ] && echo "    • app dir (KEEPING files/ and backups/): $BASE_DIR"
  [ -n "$counts" ]   && echo "    • database:                   KEPT ($counts)"
else
  [ -d "$BASE_DIR" ] && echo "    • app dir, uploads, backups:  $BASE_DIR"
  [ -n "$counts" ]   && echo "    • database + role 'mdmesh':   DROPPED — $counts"
fi
[ -f "$INSTALL_LOG" ] && echo "    • install log:                $INSTALL_LOG"
echo "  Not touched: apt packages, your TLS proxy, this git checkout."
[ "$BACKUP" = 1 ] && [ -n "$counts" ] && echo "  A final database dump is written to /root before anything is removed."
echo
if [ "$YES" != 1 ]; then
  printf '  Type UNINSTALL to proceed: '
  read -r _c
  [ "$_c" = "UNINSTALL" ] || { echo "  Aborted — nothing changed."; exit 1; }
fi

# 1. Final backup — cheap insurance even when --keep-data (the dump is the portable copy).
if [ "$BACKUP" = 1 ] && db_exists; then
  DUMP="/root/mdmesh-final-$(date +%Y%m%d-%H%M%S).dump"
  # A plain `pg_dump > "$DUMP" && …` list would not stop the script: set -e ignores a failure anywhere but the last
  # command of an && list, so a failed dump went on to drop the database. (umask 077 above: the file is created 600.)
  if [ -z "$DB_PW" ]; then
    echo "  ✗ final dump failed: the database password could not be read from ROOT.xml — nothing removed (re-run with"
    echo "    --no-backup to uninstall without a dump)"
    exit 1
  fi
  if ! as_mdmesh_role "$DB_PW" pg_dump -Fc > "$DUMP"; then
    rm -f "$DUMP"
    echo "  ✗ final dump failed — nothing removed (fix it, or re-run with --no-backup to uninstall without a dump)"
    exit 1
  fi
  chmod 600 "$DUMP"
  echo "  ✓ final dump: $DUMP"
  echo -n "    "; mdm_restore_hint "$DUMP"
fi

# 2. Stop Tomcat for good: the systemd unit first (cgroup-tracked), then legacy fallbacks for Tomcats
#    started by older versions of the installer without a unit. $CATALINA is $SVC_USER's tree, so root never runs its
#    bin/catalina.sh (nor the bin/setenv.sh it sources): with the unit, systemd stops Tomcat; without one, catalina.sh
#    runs as $SVC_USER (no controlling terminal, none of root's environment; see as_svc_user in lib/runas.sh), and
#    before that account existed (Tomcat ran as root) the signals below stop it.
if [ -f "$SERVER_UNIT" ] || [ -n "$(systemctl list-unit-files --no-legend mdmesh-server.service 2>/dev/null)" ]; then
  systemctl disable --now mdmesh-server >/dev/null 2>&1 || true
  rm -f "$SERVER_UNIT"; systemctl daemon-reload 2>/dev/null || true
  echo "  ✓ mdmesh-server service removed"
elif [ -x "$CATALINA/bin/catalina.sh" ] && id -u "$SVC_USER" >/dev/null 2>&1; then
  as_svc_user "$CATALINA/bin/catalina.sh" stop 20 -force >/dev/null 2>&1 || true
fi
for p in $(pgrep -f "^[^ ]*/java .*catalina.base=$CATALINA" || true); do kill "$p" 2>/dev/null || true; done
for _ in $(seq 1 20); do pgrep -f "^[^ ]*/java .*catalina.base=$CATALINA" >/dev/null || break; sleep 1; done
for p in $(pgrep -f "^[^ ]*/java .*catalina.base=$CATALINA" || true); do kill -9 "$p" 2>/dev/null || true; done
echo "  ✓ Tomcat stopped"

# 3. Updater service.
if [ -f "$UNIT" ] || [ -n "$(systemctl list-unit-files --no-legend mdmesh-supervisor.service 2>/dev/null)" ]; then
  systemctl disable --now mdmesh-supervisor >/dev/null 2>&1 || true
  rm -f "$UNIT"; systemctl daemon-reload 2>/dev/null || true
  echo "  ✓ mdmesh-supervisor service removed"
fi
if [ -d "$SUP_ENV_DIR" ]; then
  # With the temp file a killed install run may have left (install-native.sh write_under's .NAME.mdmesh-tmp.XXXXXX).
  rm -f "$SUP_ENV_DIR/supervisor.env" "$SUP_ENV_DIR"/.supervisor.env.mdmesh-tmp.??????
  if rmdir "$SUP_ENV_DIR" 2>/dev/null; then echo "  ✓ removed $SUP_ENV_DIR/supervisor.env and $SUP_ENV_DIR"
  else echo "  ✓ removed $SUP_ENV_DIR/supervisor.env (kept $SUP_ENV_DIR: it holds other files)"; fi
fi

# 4. Database.
if [ "$KEEP_DATA" != 1 ] && db_exists; then
  as_postgres psql -X -qc "SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE datname='mdmesh' AND pid<>pg_backend_pid();" >/dev/null 2>&1 || true
  as_postgres psql -X -qc 'DROP DATABASE mdmesh;'
  as_postgres psql -X -qc 'DROP ROLE IF EXISTS mdmesh;'
  echo "  ✓ database + role dropped"
fi

# 5. Files.
rm -rf "$CATALINA"
if [ "$KEEP_DATA" = 1 ]; then
  for d in emails plugins supervisor; do rm -rf "${BASE_DIR:?}/$d"; done
  rm -f "$BASE_DIR"/initialized.txt "$BASE_DIR"/log4j-mdmesh.xml "$BASE_DIR"/supervisor.env   # log4j-mdmesh.xml: written by v0.2.1–v0.3.x
  echo "  ✓ removed $CATALINA and app code; kept $BASE_DIR/files and $BASE_DIR/backups"
else
  rm -rf "$BASE_DIR"
  echo "  ✓ removed $CATALINA and $BASE_DIR"
fi
rm -f "$INSTALL_LOG"
# 6. Service account — only when its files are gone too (a kept files/ dir stays owned by it).
if [ "$KEEP_DATA" != 1 ] && id -u "$SVC_USER" >/dev/null 2>&1; then
  # userdel refuses while the account has processes (it ignores those in another root, such as a container's).
  for _ in $(seq 1 20); do
    pids=$(svc_user_pids); [ -n "$pids" ] || break
    # shellcheck disable=SC2086  # one pid per word
    kill -KILL $pids 2>/dev/null || true; sleep 0.2
  done
  if userdel "$SVC_USER" 2>/dev/null; then echo "  ✓ service user $SVC_USER removed"
  else echo "  ! could not remove the service user $SVC_USER (run: userdel $SVC_USER)"; fi
fi
echo
echo "  MDMesh removed. Devices still enrolled will keep polling this server's URL until factory-reset or re-provisioned."
