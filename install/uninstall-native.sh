#!/usr/bin/env bash
# Remove a native (non-Docker) MDMesh install made by install/install-native.sh.
#
#   sudo ./install/uninstall-native.sh              # interactive: shows what goes, asks you to type UNINSTALL
#   sudo ./install/uninstall-native.sh --keep-data  # remove code/services but keep the database + uploaded files
#   sudo ./install/uninstall-native.sh -y           # unattended (still takes a final pg_dump unless --no-backup)
#
# Removes: Tomcat (/opt/mdmesh-tc), the app dir (/opt/mdmesh), the mdmesh-supervisor systemd unit, the install
# log, and — unless --keep-data — the PostgreSQL database + role "mdmesh". A final dump is written first.
# Leaves alone: apt packages (postgresql, maven, node, …), your reverse proxy/TLS, and the git checkout.
set -euo pipefail
umask 077
[ "$(id -u)" = "0" ] || { echo "Run as root (sudo)."; exit 1; }

BASE_DIR=/opt/mdmesh
CATALINA=/opt/mdmesh-tc
UNIT=/etc/systemd/system/mdmesh-supervisor.service
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

db_exists() { su -s /bin/sh postgres -c "psql -tAc \"SELECT 1 FROM pg_database WHERE datname='mdmesh'\"" 2>/dev/null | grep -q 1; }
counts=""
if db_exists; then
  counts=$(su -s /bin/sh postgres -c "psql -d mdmesh -tAc \"SELECT (SELECT count(*) FROM devices)||' device(s), '||(SELECT count(*) FROM configurations)||' configuration(s), '||(SELECT count(*) FROM users)||' user(s)'\"" 2>/dev/null || echo "unreadable")
fi

echo
echo "  MDMesh native uninstall — this host will lose:"
[ -d "$CATALINA" ] && echo "    • Tomcat + deployed server:   $CATALINA"
[ -f "$UNIT" ]     && echo "    • updater service:            mdmesh-supervisor (systemd unit removed)"
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
  su -s /bin/sh postgres -c "pg_dump -Fc mdmesh" > "$DUMP" && chmod 600 "$DUMP" && echo "  ✓ final dump: $DUMP  (restore: pg_restore -c -d mdmesh $DUMP)"
fi

# 2. Stop Tomcat for good. The server keeps scheduler threads alive after a normal stop, so kill by pid too.
if [ -x "$CATALINA/bin/catalina.sh" ]; then
  CATALINA_PID="$CATALINA/tomcat.pid" "$CATALINA/bin/catalina.sh" stop 20 -force >/dev/null 2>&1 || true
fi
for p in $(pgrep -f "^[^ ]*/java .*catalina.base=$CATALINA" || true); do kill "$p" 2>/dev/null || true; done
for _ in $(seq 1 20); do pgrep -f "^[^ ]*/java .*catalina.base=$CATALINA" >/dev/null || break; sleep 1; done
for p in $(pgrep -f "^[^ ]*/java .*catalina.base=$CATALINA" || true); do kill -9 "$p" 2>/dev/null || true; done
echo "  ✓ Tomcat stopped"

# 3. Updater service.
if [ -f "$UNIT" ] || systemctl list-unit-files 2>/dev/null | grep -q '^mdmesh-supervisor'; then
  systemctl disable --now mdmesh-supervisor >/dev/null 2>&1 || true
  rm -f "$UNIT"; systemctl daemon-reload 2>/dev/null || true
  echo "  ✓ mdmesh-supervisor service removed"
fi

# 4. Database.
if [ "$KEEP_DATA" != 1 ] && db_exists; then
  su -s /bin/sh postgres -c "psql -qc \"SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE datname='mdmesh' AND pid<>pg_backend_pid();\"" >/dev/null 2>&1 || true
  su -s /bin/sh postgres -c "psql -qc 'DROP DATABASE mdmesh;'"
  su -s /bin/sh postgres -c "psql -qc 'DROP ROLE IF EXISTS mdmesh;'"
  echo "  ✓ database + role dropped"
fi

# 5. Files.
rm -rf "$CATALINA"
if [ "$KEEP_DATA" = 1 ]; then
  for d in emails plugins supervisor; do rm -rf "${BASE_DIR:?}/$d"; done
  rm -f "$BASE_DIR"/initialized.txt "$BASE_DIR"/log4j-mdmesh.xml "$BASE_DIR"/supervisor.env
  echo "  ✓ removed $CATALINA and app code; kept $BASE_DIR/files and $BASE_DIR/backups"
else
  rm -rf "$BASE_DIR"
  echo "  ✓ removed $CATALINA and $BASE_DIR"
fi
rm -f "$INSTALL_LOG"
echo
echo "  MDMesh removed. Devices still enrolled will keep polling this server's URL until factory-reset or re-provisioned."
