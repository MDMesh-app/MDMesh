#!/usr/bin/env bash
# T2 native rig, guest side: runs INSIDE a tests/native container (as root, via `docker exec`), never on a real host.
# run.sh copies it to /t2/guest.sh. Subcommands:
#
#   clone <origin-url>             clone /t2/repo.bundle as t2user into ~t2user/MDMesh, origin set to <origin-url>
#   checkout <sha>                 as t2user: the checkout's main branch moves to <sha> (what `git pull` leaves behind)
#   install <out> [kill-point]     the documented command, as t2user: `sudo BASE_URL=… HTTP_PORT=… ./setup.sh --native -y`
#                                  (T2_BASE_URL, T2_HTTP_PORT from the environment), output to <out>. With a kill point,
#                                  SIGKILLs the installer's process group when the point is reached (see KILL POINTS).
#   kill-points                    list the kill points, one per line
#   admin-temp-password <out>      the temporary admin password a fresh install printed in <out>
#   secrets                        a short fingerprint (sha256) of hash.secret / jwt.secretkey in ROOT.xml
#   counts                         configurations / devices / users counts (psql as postgres)
#   deployed                       "match" when Tomcat serves this checkout's build (server WAR + console)
#   initialized                    the server's initialized.txt
#   units                          systemctl is-active of the MDMesh units that are installed
#   state                          a diagnostic snapshot (units, ports, owners of the files a re-run must read)
#   collect <dir>                  copy logs and journals into <dir> (inside the container)
set -euo pipefail
unset CDPATH

CHECKOUT=/home/t2user/MDMesh
CATALINA=/opt/mdmesh-tc
BASE_DIR=/opt/mdmesh
ROOT_XML=$CATALINA/conf/Catalina/localhost/ROOT.xml

as_user() { runuser -u t2user -- "$@"; }

cmd_clone() {
  as_user git clone -q /t2/repo.bundle "$CHECKOUT"
  as_user git -C "$CHECKOUT" remote set-url origin "$1"
}

cmd_checkout() {
  as_user git -C "$CHECKOUT" checkout -q -B main "$1"
  as_user git -C "$CHECKOUT" log -1 --format='checked out %h %s'
}

# ---------------------------------------------------------------------------------------------------------------------
# KILL POINTS. Each one names a moment of an upgrade run of install/install-native.sh, recognised by a line the installer
# prints (non-TTY output: step = "▸ Label", run/info = "  · Label…", ok = "  ✓ Label") plus, for some, a condition or a
# delay. When it is reached, the installer's whole process group gets SIGKILL: the harshest interruption (OOM killer,
# a hard reboot of the shell, kill -9), and unlike a closed terminal nothing gets a chance to clean up. The systemd units
# the installer started are not in that group and keep their state, as they would on a real host.
#
# A marker that never appears fails the run loudly: the installer's wording changed, update the table.
# Format: name|marker|condition (a function below, polled; empty = none)|delay seconds after both
KILL_POINTS=(
  "db-role|PostgreSQL role + database 'mdmesh' ready||0"          # role password rotated; old server still running on the old one
  "mvn|· Maven package||20"                                        # mid Maven build (old server still running)
  "npm|· npm ci + vite build||8"                                   # mid console build
  "server-stopped|▸ Tomcat 9 + app deploy|server_stopped|0"   # server just stopped, nothing deployed yet
  "mid-deploy|· HTTP port set to||0"                               # server.xml rewritten, webapps about to be replaced
  "deployed|server + console deployed||0"                          # new code + ROOT.xml in place, no pre-upgrade dump yet
  "backup|▸ Backing up the database before upgrading||0"           # during/just before the pre-upgrade pg_dump
  "supervisor|▸ Updater supervisor||0"                             # supervisor files/unit being rewritten
  "migrating|▸ Starting the server|server_booting|3"   # new server booting (Liquibase)
  "post-seed|▸ Preserving existing data||0"                        # server up, post-seed repairs not yet applied
)

server_stopped() { case "$(systemctl is-active mdmesh-server 2>/dev/null)" in inactive|failed) return 0 ;; esac; return 1; }
server_booting() { systemctl is-active --quiet mdmesh-server && [ ! -e "$BASE_DIR/initialized.txt" ]; }

cmd_kill_points() { local p; for p in "${KILL_POINTS[@]}"; do printf '%s\n' "${p%%|*}"; done; }

# The pgid of the installer (the oldest process running install-native.sh), or nothing.
installer_pgid() {
  local p
  p=$(pgrep -o -f 'install/install-native\.sh' || true)
  [ -n "$p" ] || return 0
  ps -o pgid= -p "$p" | tr -d ' '
}

cmd_install() {
  local out=$1 point=${2:-} spec="" marker cond delay rc=0 pid pg i
  : "${T2_BASE_URL:?}" "${T2_HTTP_PORT:?}"
  if [ -n "$point" ]; then
    for spec in "${KILL_POINTS[@]}"; do [ "${spec%%|*}" = "$point" ] && break; spec=""; done
    [ -n "$spec" ] || { echo "unknown kill point: $point (known: $(cmd_kill_points | tr '\n' ' '))" >&2; return 2; }
    IFS='|' read -r _ marker cond delay <<< "$spec"
  fi
  : > "$out"
  # The user's shell: a login shell of the sudo user in the checkout, running the documented command. setsid gives the
  # run a session (and process group) of its own, as a terminal would.
  setsid runuser -l t2user -c "cd MDMesh && exec sudo BASE_URL='$T2_BASE_URL' HTTP_PORT='$T2_HTTP_PORT' ./setup.sh --native -y" \
    < /dev/null > "$out" 2>&1 &
  pid=$!
  if [ -z "$point" ]; then
    wait "$pid" || rc=$?
    echo "T2 install rc=$rc"
    return "$rc"
  fi
  # Wait for the marker (and the condition) while the installer is still running.
  for i in $(seq 1 18000); do   # 60 minutes at 0.2 s
    if grep -qF -- "$marker" "$out" && { [ -z "$cond" ] || "$cond"; }; then break; fi
    if ! kill -0 "$pid" 2>/dev/null; then
      wait "$pid" || rc=$?
      echo "T2 kill point $point NOT REACHED: the installer exited (rc=$rc) before it (marker: $marker)"
      return 3
    fi
    sleep 0.2
  done
  [ "$i" -lt 18000 ] || { echo "T2 kill point $point: timed out waiting for it"; return 3; }
  sleep "${delay:-0}"
  pg=$(installer_pgid)
  if [ -z "$pg" ]; then
    wait "$pid" || rc=$?
    echo "T2 kill point $point NOT REACHED: the installer had already exited (rc=$rc)"
    return 3
  fi
  echo "T2 kill point $point: SIGKILL to process group $pg; last installer line: $(grep -v '^[[:space:]]*$' "$out" | tail -n 1)"
  kill -KILL -- "-$pg" 2>/dev/null || true
  wait "$pid" 2>/dev/null || true
  sleep 1
  # Anything of the run that survived (another process group of the same session, e.g. if sudo had made one).
  local left
  left=$(ps -eo pid=,pgid=,sid=,args= | awk -v s="$pid" -v g="$pg" '$2 == g || $3 == s' || true)
  if [ -n "$left" ]; then echo "T2 kill point $point: still running after the kill:"; printf '%s\n' "$left" | sed 's/^/    /'; fi
  if pgrep -f 'install/install-native\.sh' >/dev/null; then echo "T2 kill point $point: an installer process survived"; fi
  echo "T2 install killed at $point"
  return 0
}

cmd_admin_temp_password() {
  sed -n 's/.*admin \/ \([0-9a-f]\{16,\}\).*/\1/p' "$1" | head -n 1
}

fp() { if [ -n "$1" ]; then printf '%s' "$1" | sha256sum | cut -c1-16; else echo none; fi; }
xml_param() { sed -n "s/.*name=\"$1\"[[:space:]]*value=\"\([^\"]*\)\".*/\1/p" "$ROOT_XML" 2>/dev/null | head -n 1; }

cmd_secrets() {
  [ -f "$ROOT_XML" ] || { echo "ROOT.xml=missing"; return 0; }
  echo "hash.secret=$(fp "$(xml_param hash.secret)")"
  echo "jwt.secretkey=$(fp "$(xml_param jwt.secretkey)")"
}

psql_pg() { (cd / && runuser -u postgres -- psql -X -d mdmesh -tAc "$1"); }

cmd_counts() {
  psql_pg "SELECT 'configurations='||(SELECT count(*) FROM configurations)||' devices='||(SELECT count(*) FROM devices)||' users='||(SELECT count(*) FROM users)"
}

# What Tomcat serves is this checkout's build: the exploded webapp holds the checkout's server/target/launcher.war (files
# the console build overlays excepted) and the checkout's console (web/dist). Prints "match" or the differences.
cmd_deployed() {
  local war=$CHECKOUT/server/target/launcher.war dist=$CHECKOUT/web/dist root=$CATALINA/webapps/ROOT tmp jar f bad=""
  jar=$(systemctl show -p Environment --value mdmesh-server 2>/dev/null | tr ' ' '\n' | sed -n 's/^JAVA_HOME=//p')/bin/jar
  if [ ! -f "$war" ] || [ ! -d "$dist" ] || [ ! -x "$jar" ]; then
    echo "nothing to compare (WAR, console build or the unit's jar tool missing)"; return 0
  fi
  tmp=$(mktemp -d)
  (cd "$tmp" && "$jar" -xf "$war")
  while IFS= read -r f; do
    f=${f#"$tmp"/}
    [ -e "$dist/$f" ] && continue
    cmp -s "$tmp/$f" "$root/$f" || bad="$bad WAR:$f"
  done < <(find "$tmp" -type f)
  while IFS= read -r f; do
    f=${f#"$dist"/}
    cmp -s "$dist/$f" "$root/$f" || bad="$bad console:$f"
  done < <(find "$dist" -type f)
  rm -rf "$tmp"
  if [ -z "$bad" ]; then echo match; else echo "differs:$(printf '%s' "$bad" | cut -c1-300)"; fi
}

cmd_initialized() { cat "$BASE_DIR/initialized.txt" 2>/dev/null || echo "(no $BASE_DIR/initialized.txt)"; }

cmd_units() {
  local u
  for u in mdmesh-server mdmesh-supervisor; do
    if [ -n "$(systemctl list-unit-files --no-legend "$u.service" 2>/dev/null)" ]; then
      echo "$u=$(systemctl is-active "$u" 2>/dev/null || true)"
    fi
  done
}

cmd_state() {
  echo "--- units"; cmd_units; systemctl is-active postgresql 2>/dev/null | sed 's/^/postgresql=/' || true
  echo "--- listening"; ss -ltnp 2>/dev/null | sed -n '1!p' || true
  echo "--- initialized.txt"; cmd_initialized | head -c 400; echo
  echo "--- owners of what a re-run reads or writes"
  ls -la "$CATALINA/conf" "$CATALINA/conf/Catalina/localhost" 2>&1 || true
  ls -la "$CATALINA/webapps" "$BASE_DIR" "$BASE_DIR/supervisor" /etc/mdmesh 2>&1 || true
  find "$CATALINA" "$BASE_DIR" -maxdepth 4 \( -name '.*.mdmesh-tmp.*' -o ! -user mdmesh \) -printf '%u %m %p\n' 2>/dev/null | head -n 40 || true
  echo "--- installer processes"; pgrep -af 'install-native|mvn|npm|vite' || true
}

cmd_collect() {
  local d=$1 u
  mkdir -p "$d"
  cp -f /var/log/mdmesh-install.log "$d/" 2>/dev/null || true
  cp -f /t2/out/*.out "$d/" 2>/dev/null || true
  for u in mdmesh-server mdmesh-supervisor postgresql; do
    journalctl -u "$u" --no-pager -o short-iso > "$d/journal-$u.log" 2>&1 || true
  done
  journalctl -b --no-pager -o short-iso | tail -n 3000 > "$d/journal-boot.log" 2>&1 || true
  cmd_state > "$d/state.txt" 2>&1 || true
  cp -rf "$CATALINA/logs" "$d/tomcat-logs" 2>/dev/null || true
}

cmd=${1:?usage: guest.sh <subcommand> ...}; shift
case "$cmd" in
  clone|checkout|install|admin-temp-password|secrets|counts|deployed|initialized|units|state|collect) "cmd_${cmd//-/_}" "$@" ;;
  kill-points) cmd_kill_points ;;
  *) echo "unknown subcommand: $cmd" >&2; exit 2 ;;
esac
