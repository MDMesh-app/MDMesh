#!/usr/bin/env bash
# Lean native (non-Docker) installer for MDMesh — Debian/Ubuntu. Stands up Postgres + Tomcat 9 + the
# server on the host and assumes you terminate TLS yourself (your own reverse proxy / cert, or Caddy in
# front). For the turnkey experience use ./setup.sh (Docker). Flags: -y/--yes (skip confirm), -v/--verbose
# (stream all output instead of hiding it in the log). Best-effort + idempotent; review before prod use.
set -euo pipefail
cd "$(dirname "$0")/.."
REPO="$PWD"   # repo root — used for absolute paths inside subshells (e.g. exploding the WAR)

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
    • apt-get install openjdk-17-jdk, postgresql, maven, nodejs, npm, curl, python3
    • create or alter a PostgreSQL role and database "mdmesh" (resets that role's password)
    • download and unpack Apache Tomcat 9 into /opt/mdmesh-tc (clears its webapps/)
    • write config, logs and uploaded files under /opt/mdmesh
    • start Tomcat, run database migrations, and seed the admin account

  Intended for a dedicated server you control. This script does not undo these changes.
  ${c_dim}Details are hidden — re-run with -v to stream them, or: tail -f ${LOGFILE}${c_reset}

WARN
if [ "$ASSUME_YES" != "1" ]; then
  printf '  Type "yes" to proceed: '
  read -r _confirm
  [ "$_confirm" = "yes" ] || { echo "  Aborted — no changes made."; exit 1; }
fi

SALT='5YdSYHyg2U'
rand() { openssl rand -hex 24; }
pwhash() { local m; m=$(printf '%s' "$1" | md5sum | awk '{print toupper($1)}'); printf '%s' "${m}${SALT}" | sha1sum | awk '{print $1}'; }

printf '\n'
read -rp "  Public base URL (e.g. https://mdm.example.com): " BASE_URL
# HTTP port Tomcat listens on. Override non-interactively with HTTP_PORT=9090; default 8080.
HTTP_PORT="${HTTP_PORT:-}"
if [ -z "$HTTP_PORT" ]; then read -rp "  HTTP port [8080]: " _p; HTTP_PORT="${_p:-8080}"; fi
case "$HTTP_PORT" in ''|*[!0-9]*) echo "  Port must be a number."; exit 1 ;; esac
{ [ "$HTTP_PORT" -ge 1 ] && [ "$HTTP_PORT" -le 65535 ]; } || { echo "  Port must be 1-65535."; exit 1; }
DB_PASSWORD=$(rand); HASH_SECRET=$(rand); ADMIN_PASSWORD=$(rand); RESET_TOKEN=$(openssl rand -hex 16)
BASE_DIR=/opt/mdmesh
CATALINA=/opt/mdmesh-tc
TOMCAT_VER=9.0.89

step "Installing dependencies"
# Tolerate an unrelated broken third-party APT source (e.g. a Docker repo on a codename Docker doesn't
# publish for → "does not have a Release file") — the native install only needs base Debian packages.
run "openjdk-17-jdk, postgresql, maven, nodejs, npm, curl" bash -c \
  'apt-get update -y || echo "(some apt sources failed to refresh — continuing)"; DEBIAN_FRONTEND=noninteractive apt-get install -y openjdk-17-jdk postgresql maven nodejs npm curl python3'

step "Selecting the Java 17 toolchain"
# HERMETIC BUILD: pin JDK 17 and never fall back to the host default JDK. The server uses Lombok 1.18.20,
# whose annotation processor only runs on JDK <=17; on a newer default JDK (21/25/…) it generates nothing
# and the build dies with hundreds of "cannot find symbol". This keeps the build identical on any host.
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
JAVA_HOME=$(select_jdk17) || {
  _spin_stop
  echo "  ${c_red}✗ no JDK 17 found${c_reset} — the server build REQUIRES JDK 17 (Lombok 1.18.20 breaks on JDK 21+)." >&2
  echo "    Install it (apt-get install -y openjdk-17-jdk) or set JAVA17_HOME to a JDK 17 home, then re-run." >&2
  exit 1
}
export JAVA_HOME
export PATH="$JAVA_HOME/bin:$PATH"
ok "$(javac -version 2>&1) — $JAVA_HOME"

step "Database"
# Idempotent: every run generates a fresh DB_PASSWORD, so ALWAYS set the role's password to match — ALTER
# if the role already exists from a previous run, else CREATE — so ROOT.xml + seeding always authenticate.
{
  if sudo -u postgres psql -tAc "SELECT 1 FROM pg_roles WHERE rolname='mdmesh'" | grep -q 1; then
    sudo -u postgres psql -c "ALTER USER mdmesh WITH PASSWORD '${DB_PASSWORD}';"
  else
    sudo -u postgres psql -c "CREATE USER mdmesh WITH PASSWORD '${DB_PASSWORD}';"
  fi
  sudo -u postgres psql -tAc "SELECT 1 FROM pg_database WHERE datname='mdmesh'" | grep -q 1 || \
    sudo -u postgres psql -c "CREATE DATABASE mdmesh OWNER mdmesh;"
} >> "$LOGFILE" 2>&1
ok "PostgreSQL role + database 'mdmesh' ready"

# Data safety: the seed (hmdm_init.en.sql) is FRESH-DB-ONLY — it DELETEs configurations and re-inserts a
# demo device. So decide now whether to seed. If the DB already holds data (an existing install), default
# to KEEPING it: we only deploy new code + run Liquibase migrations (non-destructive). Replacing is opt-in
# and drops the DB for a clean slate. Override non-interactively with REPLACE_DATA=yes|no.
q() { PGPASSWORD="$DB_PASSWORD" psql -h 127.0.0.1 -U mdmesh -d mdmesh -tAc "$1" 2>/dev/null | tr -d '[:space:]'; }
SEED=yes
if [ "$(q "SELECT to_regclass('public.users')")" = "users" ] && [ "$(q "SELECT count(*) FROM users")" != "0" ]; then
  uc=$(q "SELECT count(*) FROM users"); dc=$(q "SELECT count(*) FROM devices"); dc=${dc:-0}
  REPLACE_DATA="${REPLACE_DATA:-}"
  if [ -z "$REPLACE_DATA" ]; then
    if [ "$ASSUME_YES" = 1 ]; then
      REPLACE_DATA=no   # never destroy data unprompted
    else
      printf '\n  %s%s⚠  Existing MDMesh data found: %s device(s), %s user(s).%s\n' "$c_red" "$c_bold" "$dc" "$uc" "$c_reset"
      printf '  %sKeep your existing data? Answering "no" ERASES all of it.%s %s[Y/n]%s: ' "$c_red" "$c_reset" "$c_bold" "$c_reset"
      # Default (Enter) keeps data. Only an explicit no/n erases it.
      read -r _r; case "$_r" in n|N|no|NO) REPLACE_DATA=yes ;; *) REPLACE_DATA=no ;; esac
    fi
  fi
  if [ "$REPLACE_DATA" = yes ]; then
    info "Replacing the database — dropping $dc device(s), $uc user(s)"
    "$CATALINA/bin/catalina.sh" stop 15 -force >/dev/null 2>&1 || true   # release DB connections first
    {
      sudo -u postgres psql -c "SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE datname='mdmesh' AND pid<>pg_backend_pid();"
      sudo -u postgres psql -c "DROP DATABASE mdmesh;"
      sudo -u postgres psql -c "CREATE DATABASE mdmesh OWNER mdmesh;"
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
GITHUB_REPO="${GITHUB_REPO:-$(git remote get-url origin 2>/dev/null | sed -E 's#(git@|https?://)[^/:]+[/:]##; s#\.git$##')}"
AGENT_APK=""
if [ -n "$GITHUB_REPO" ]; then
  AUTH=(); [ -n "${GITHUB_TOKEN:-}" ] && AUTH=(-H "Authorization: Bearer ${GITHUB_TOKEN}")
  jget() { python3 -c 'import sys,json;
d=json.load(sys.stdin)
def asset(n): return next((a["browser_download_url"] for a in d.get("assets",[]) if a["name"]==n),"")
print({"apk":asset("mdmesh-agent.apk"),"manifest":asset("manifest.json")}.get(sys.argv[1],""))' "$1" 2>/dev/null; }
  REL=$(curl -fsSL "${AUTH[@]}" "https://api.github.com/repos/${GITHUB_REPO}/releases/latest" 2>>"$LOGFILE" || true)
  APK_URL=$(printf '%s' "$REL" | jget apk); MAN_URL=$(printf '%s' "$REL" | jget manifest)
  if [ -n "$APK_URL" ] && [ -n "$MAN_URL" ]; then
    MAN=$(curl -fsSL "${AUTH[@]}" "$MAN_URL" 2>>"$LOGFILE" || true)
    AGENT_CK=$(printf '%s' "$MAN" | python3 -c 'import sys,json;print(json.load(sys.stdin)["components"]["apk"]["signatureChecksum"])' 2>/dev/null || true)
    WANT_SHA=$(printf '%s' "$MAN" | python3 -c 'import sys,json;print(json.load(sys.stdin)["components"]["apk"]["sha256"])' 2>/dev/null || true)
    TMP_APK=$(mktemp)
    if curl -fsSL "${AUTH[@]}" "$APK_URL" -o "$TMP_APK" 2>>"$LOGFILE" && [ -n "$AGENT_CK" ] \
       && [ "$(sha256sum "$TMP_APK" | awk '{print $1}')" = "$WANT_SHA" ]; then
      AGENT_APK="$TMP_APK"
      export VITE_AGENT_PACKAGE="com.mdmesh.agent" VITE_AGENT_CHECKSUM="$AGENT_CK" VITE_AGENT_APK_URL="/files/agent.apk"
      ok "release agent APK fetched + sha256-verified (checksum ${AGENT_CK})"
    else
      info "Could not fetch/verify the release APK — continuing; host one at /files/agent.apk manually"
    fi
  else
    info "No published release found for ${GITHUB_REPO} — console uses debug defaults; host /files/agent.apk manually"
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
# Install Tomcat if it's missing OR a previous run left it partial/corrupt. Check for the actual launcher
# script, not just the directory, so a broken /opt/mdmesh-tc self-heals instead of failing at startup.
# archive.apache.org keeps every release permanently, so the pinned version URL never rots.
if [ ! -x "$CATALINA/bin/catalina.sh" ]; then
  run "Downloading Apache Tomcat ${TOMCAT_VER}" \
    curl -fsSL --retry 3 "https://archive.apache.org/dist/tomcat/tomcat-9/v${TOMCAT_VER}/bin/apache-tomcat-${TOMCAT_VER}.tar.gz" -o /tmp/tc.tgz
  rm -rf "$CATALINA"; mkdir -p "$CATALINA"
  tar xzf /tmp/tc.tgz -C "$CATALINA" --strip-components=1
  [ -x "$CATALINA/bin/catalina.sh" ] || _fail "Tomcat extract (catalina.sh missing after unpack)"
  ok "Apache Tomcat ${TOMCAT_VER} installed at $CATALINA"
else
  info "Apache Tomcat already present at $CATALINA"
fi
# Point Tomcat's HTTP connector at the chosen port. Idempotent across re-runs: rewrite whatever numeric
# port currently sits on the HTTP/1.1 connector (leaves the shutdown/AJP ports untouched).
sed -i -E "s#(<Connector port=\")[0-9]+(\" protocol=\"HTTP/1.1\")#\1${HTTP_PORT}\2#" "$CATALINA/conf/server.xml"
info "HTTP port set to ${HTTP_PORT}"
rm -rf "$CATALINA"/webapps/*
# Deploy the server as an EXPLODED webapp (not ROOT.war) and overlay the built SPA into it, so a single
# Tomcat serves the console at / and the API at /rest on one origin. Exploding ourselves (no ROOT.war
# left behind) means Tomcat won't re-expand on restart and wipe the overlaid SPA files.
mkdir -p "$CATALINA/webapps/ROOT"
( cd "$CATALINA/webapps/ROOT" && "$JAVA_HOME/bin/jar" -xf "$REPO/server/target/launcher.war" )
cp -a "$REPO"/web/dist/. "$CATALINA/webapps/ROOT/"   # index.html + assets at / (server maps /rest,/files,/agent)
# SPA fallback (verified on Tomcat 9.0.89): !-f serves real files (assets) as-is; the negative lookahead
# leaves the API paths (/rest,/files,/agent) alone; everything else → index.html so client-side routes
# survive a reload. Paired with the RewriteValve declared in ROOT.xml above.
printf 'RewriteCond %%{REQUEST_URI} !-f\nRewriteRule ^/(?!rest|files|agent)(.*)$ /index.html\n' \
  > "$CATALINA/webapps/ROOT/WEB-INF/rewrite.config"
mkdir -p "$BASE_DIR/files" "$BASE_DIR/plugins" "$CATALINA/conf/Catalina/localhost"
cp install/log4j_template.xml "$BASE_DIR/log4j-mdmesh.xml"
cp -r install/emails "$BASE_DIR/" 2>/dev/null || true
# Host the release agent APK the QR points at (/files/agent.apk), if we fetched one above.
[ -n "$AGENT_APK" ] && { cp "$AGENT_APK" "$BASE_DIR/files/agent.apk"; ok "agent APK hosted at /files/agent.apk"; }
cat > "$CATALINA/conf/Catalina/localhost/ROOT.xml" <<XML
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
    <Parameter name="plugins.files.directory" value="${BASE_DIR}/plugins"/>
    <Parameter name="plugin.devicelog.persistence.config.class" value="com.hmdm.plugins.devicelog.persistence.postgres.DeviceLogPostgresPersistenceConfiguration"/>
    <Parameter name="role.orgadmin.id" value="2"/>
    <Parameter name="swagger.base.path" value="/rest"/>
    <Parameter name="initialization.completion.signal.file" value="${BASE_DIR}/initialized.txt"/>
    <Parameter name="log4j.config" value="file://${BASE_DIR}/log4j-mdmesh.xml"/>
    <Parameter name="aapt.command" value="aapt"/>
    <Parameter name="mqtt.server.uri" value=""/>
    <Parameter name="mqtt.auth" value="0"/>
    <Parameter name="device.fast.search.chars" value="5"/>
</Context>
XML
ok "server + console deployed (console at /, API at /rest); ROOT.xml written"

step "Starting the server"
# Runs on the same pinned JDK 17 (JAVA_HOME exported above), matching the Docker tomcat:9.0-jdk17 image.
export CATALINA_OPTS="--add-opens java.base/java.lang=ALL-UNNAMED --add-opens java.base/java.lang.reflect=ALL-UNNAMED --add-opens java.base/java.util=ALL-UNNAMED --add-opens java.base/java.text=ALL-UNNAMED --add-opens java.desktop/java.awt.font=ALL-UNNAMED"
# Stop any instance left from a previous run so the freshly written ROOT.xml (new DB password) is loaded.
"$CATALINA/bin/catalina.sh" stop 15 -force >/dev/null 2>&1 || true
sleep 1  # let our just-stopped instance release the listen socket
# Preflight: nothing else may hold the chosen port. We stopped our OWN Tomcat above, so any listener now
# is foreign (commonly a leftover Headwind/hmdm Tomcat). If we don't catch it, our Tomcat loses the bind,
# dies quietly, and the old server answers every request with a confusing 404 — fail clearly instead.
port_holder() {
  if command -v ss >/dev/null 2>&1; then ss -ltnp 2>/dev/null | awk -v p=":$HTTP_PORT$" '$4 ~ p {print; exit}'
  elif command -v lsof >/dev/null 2>&1; then lsof -iTCP:"$HTTP_PORT" -sTCP:LISTEN -nP 2>/dev/null | awk 'NR==2{print; exit}'; fi
}
holder=$(port_holder)
if [ -n "$holder" ]; then
  printf '  %s✗ port %s is already in use%s by another server:\n' "$c_red" "$HTTP_PORT" "$c_reset"
  printf '    %s%s%s\n' "$c_dim" "$holder" "$c_reset"
  printf '  Likely a leftover Tomcat from a previous install. Stop it (or pick another port), then re-run:\n'
  printf '    %ssudo fuser -k %s/tcp%s   (or kill the pid shown above)\n' "$c_dim" "$HTTP_PORT" "$c_reset"
  exit 1
fi
"$CATALINA/bin/catalina.sh" start >> "$LOGFILE" 2>&1
ok "Tomcat started"

# Gate on the actual schema, not a marker file: the seed needs the `users` table, which Liquibase creates
# on first boot. Polling for it means we never seed an empty DB and we surface the log if it never appears.
CATALINA_LOG="$CATALINA/logs/catalina.out"
schema_ready() {
  PGPASSWORD="$DB_PASSWORD" psql -h 127.0.0.1 -U mdmesh -d mdmesh -tAc "SELECT to_regclass('public.users')" 2>/dev/null | grep -q '^users$'
}
# Wait up to ~5 min for Liquibase to build the schema. On a TTY, show the latest catalina.out line live
# so you can watch migrations apply; poll the DB for the users table (authoritative, not a marker file).
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
    last=$(tail -n 1 "$CATALINA_LOG" 2>/dev/null | tr -d '\r'); clip=${last:0:$width}
    fi=$(( (fi + 1) % 8 ))
    printf '\r\033[K  %s%s%s migrating database %s%s%s' "$c_cyn" "${frames:$fi:1}" "$c_reset" "$c_dim" "$clip" "$c_reset"
    sleep 2
  done
  printf '\r\033[K'; return 1
}
if ! _migrate_wait; then
  printf '  %s✗ database schema was not built within 5 minutes (users table missing)%s\n' "$c_red" "$c_reset"
  printf '  %sLiquibase or server startup likely failed — last lines of %s:%s\n' "$c_yel" "$CATALINA_LOG" "$c_reset"
  hr; tail -n 30 "$CATALINA_LOG" 2>/dev/null | sed "s/^/    ${c_dim}/;s/$/${c_reset}/" || echo "    (no log at $CATALINA_LOG)"; hr
  exit 1
fi
ok "database schema ready"

if [ "$SEED" = yes ]; then
  step "Seeding settings + admin account"
  HOST=$(printf '%s' "$BASE_URL" | sed -E 's#https?://##; s#/.*##')
  {
    PGPASSWORD="$DB_PASSWORD" sed "s/_ADMIN_EMAIL_/admin@${HOST}/g" install/sql/hmdm_init.en.sql \
      | PGPASSWORD="$DB_PASSWORD" psql -h 127.0.0.1 -U mdmesh -d mdmesh || echo "(seed warnings ok if already seeded)"
    PGPASSWORD="$DB_PASSWORD" psql -h 127.0.0.1 -U mdmesh -d mdmesh -c \
      "UPDATE users SET password='$(pwhash "$ADMIN_PASSWORD")', passwordreset=true, passwordresettoken='${RESET_TOKEN}' WHERE login='admin';"
    # QR/token enrollment creates the device row on demand — that needs the settings flag ON and a
    # default configuration (devices.configurationid is NOT NULL; the init seed sets neither, and
    # without them every /agent/v1/enroll fails). COALESCE keeps a previously chosen default config.
    PGPASSWORD="$DB_PASSWORD" psql -h 127.0.0.1 -U mdmesh -d mdmesh -c \
      "UPDATE settings SET createnewdevices=true, newdeviceconfigurationid=COALESCE(newdeviceconfigurationid, (SELECT MIN(id) FROM configurations));"
  } >> "$LOGFILE" 2>&1
  ok "admin account seeded"
else
  step "Preserving existing data"
  info "Skipped seeding — your configurations, devices and admin login are untouched"
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
printf '\n  %sNotes: install '\''aapt'\'' for full APK parsing; add a systemd unit to keep Tomcat running.%s\n' "$c_dim" "$c_reset"
printf '  %sInstall log: %s%s\n\n' "$c_dim" "$LOGFILE" "$c_reset"
