#!/usr/bin/env bash
# Lean native (non-Docker) installer for MDMesh — Debian/Ubuntu. The leaner path: it stands up
# Postgres + Tomcat 9 + the server on the host and assumes you terminate TLS yourself (your own
# reverse proxy / cert, or put Caddy in front). For the turnkey experience use ./setup.sh (Docker).
# Best-effort + idempotent-ish; review before running on a production host.
set -euo pipefail
cd "$(dirname "$0")/.."

[ "$(id -u)" = "0" ] || { echo "Run as root (sudo)."; exit 1; }
command -v apt-get >/dev/null || { echo "This script targets Debian/Ubuntu."; exit 1; }

# --- Confirmation gate: this installer makes real, host-wide changes. Skip with ASSUME_YES=1 or -y. ---
ASSUME_YES="${ASSUME_YES:-0}"
case "${1:-}" in -y|--yes) ASSUME_YES=1 ;; esac
cat <<'WARN'

  ⚠  WARNING — MDMesh native installer

  This action will modify THIS host:
    • apt-get install openjdk-17-jdk, postgresql, maven, curl
    • create or alter a PostgreSQL role and database "hmdm" (resets that role's password)
    • download and unpack Apache Tomcat 9 into /opt/mdmesh-tc (clears its webapps/)
    • write config, logs and uploaded files under /opt/hmdm
    • start Tomcat, run database migrations, and seed the admin account

  Intended for a dedicated server you control. This script does not undo these changes.

WARN
if [ "$ASSUME_YES" != "1" ]; then
  printf 'Type "yes" to proceed: '
  read -r _confirm
  [ "$_confirm" = "yes" ] || { echo "Aborted — no changes made."; exit 1; }
fi

SALT='5YdSYHyg2U'
rand() { openssl rand -hex 24; }
pwhash() { local m; m=$(printf '%s' "$1" | md5sum | awk '{print toupper($1)}'); printf '%s' "${m}${SALT}" | sha1sum | awk '{print $1}'; }

read -rp "Public base URL (e.g. https://mdm.example.com): " BASE_URL
DB_PASSWORD=$(rand); HASH_SECRET=$(rand); ADMIN_PASSWORD=$(rand); RESET_TOKEN=$(openssl rand -hex 16)
BASE_DIR=/opt/hmdm
CATALINA=/opt/mdmesh-tc
TOMCAT_VER=9.0.89

echo "== Installing dependencies =="
# Don't let an unrelated broken third-party APT source abort the install. A common case: a Docker repo
# pinned to a codename Docker doesn't publish for (e.g. "resolute") returns "does not have a Release
# file", which makes `apt-get update` exit non-zero. The native install only needs base Debian packages,
# so tolerate source-refresh errors here — the `apt-get install` below still fails loudly if a required
# package is genuinely unavailable.
apt-get update -y || echo "   (some APT sources failed to refresh; continuing — only base Debian packages are required)"
apt-get install -y openjdk-17-jdk postgresql maven curl

echo "== Selecting the Java 17 toolchain =="
# HERMETIC BUILD: pin JDK 17 and never fall back to the host's default JDK. The server depends on
# Lombok 1.18.20, whose annotation processor only runs on JDK <=17; on a newer default JDK (21/25/…)
# it generates nothing and the build dies with hundreds of "cannot find symbol". Owning the toolchain
# here keeps this installer building identically no matter what the host's default JDK is, now or years
# from now — no version guessing. (The Docker path is already hermetic via the temurin-17 image.)
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
  echo "ERROR: no JDK 17 found. The server build REQUIRES JDK 17 (Lombok 1.18.20 breaks on JDK 21+)." >&2
  echo "       Install it (Debian/Ubuntu: apt-get install -y openjdk-17-jdk) or set JAVA17_HOME to a" >&2
  echo "       JDK 17 home, then re-run. Refusing to build on the host default JDK to avoid a silent fail." >&2
  exit 1
}
export JAVA_HOME
export PATH="$JAVA_HOME/bin:$PATH"
echo "   JDK: $(javac -version 2>&1)  (JAVA_HOME=$JAVA_HOME)"

echo "== Database =="
# Idempotent + timeless: every run generates a fresh DB_PASSWORD, so ALWAYS set the role's password to
# match — ALTER if the role already exists from a previous run, else CREATE. The old "create only if
# missing" left a re-run's role on its previous password while ROOT.xml + seeding used the new one →
# "password authentication failed for user hmdm".
if sudo -u postgres psql -tAc "SELECT 1 FROM pg_roles WHERE rolname='hmdm'" | grep -q 1; then
  sudo -u postgres psql -c "ALTER USER hmdm WITH PASSWORD '${DB_PASSWORD}';"
else
  sudo -u postgres psql -c "CREATE USER hmdm WITH PASSWORD '${DB_PASSWORD}';"
fi
sudo -u postgres psql -tAc "SELECT 1 FROM pg_database WHERE datname='hmdm'" | grep -q 1 || \
  sudo -u postgres psql -c "CREATE DATABASE hmdm OWNER hmdm;"

echo "== Building the server WAR =="
cp server/build.properties.example server/build.properties 2>/dev/null || true
mvn -q -B -DskipTests -pl server -am package  # uses the pinned JDK 17 selected above

echo "== Tomcat 9 =="
if [ ! -d "$CATALINA" ]; then
  curl -fsSL "https://archive.apache.org/dist/tomcat/tomcat-9/v${TOMCAT_VER}/bin/apache-tomcat-${TOMCAT_VER}.tar.gz" -o /tmp/tc.tgz
  mkdir -p "$CATALINA"; tar xzf /tmp/tc.tgz -C "$CATALINA" --strip-components=1
fi
rm -rf "$CATALINA"/webapps/*
cp server/target/launcher.war "$CATALINA"/webapps/ROOT.war
mkdir -p "$BASE_DIR/files" "$BASE_DIR/plugins" "$CATALINA/conf/Catalina/localhost"
cp install/log4j_template.xml "$BASE_DIR/log4j-hmdm.xml"
cp -r install/emails "$BASE_DIR/" 2>/dev/null || true

cat > "$CATALINA/conf/Catalina/localhost/ROOT.xml" <<XML
<?xml version="1.0" encoding="UTF-8"?>
<Context>
    <Parameter name="JDBC.driver"   value="org.postgresql.Driver"/>
    <Parameter name="JDBC.url"      value="jdbc:postgresql://127.0.0.1:5432/hmdm"/>
    <Parameter name="JDBC.username" value="hmdm"/>
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
    <Parameter name="log4j.config" value="file://${BASE_DIR}/log4j-hmdm.xml"/>
    <Parameter name="aapt.command" value="aapt"/>
    <Parameter name="mqtt.server.uri" value=""/>
    <Parameter name="mqtt.auth" value="0"/>
    <Parameter name="device.fast.search.chars" value="5"/>
</Context>
XML

echo "== Starting Tomcat (first boot runs Liquibase) =="
# Runs on the same pinned JDK 17 (JAVA_HOME exported above), matching the Docker tomcat:9.0-jdk17 image.
export CATALINA_OPTS="--add-opens java.base/java.lang=ALL-UNNAMED --add-opens java.base/java.lang.reflect=ALL-UNNAMED --add-opens java.base/java.util=ALL-UNNAMED --add-opens java.base/java.text=ALL-UNNAMED --add-opens java.desktop/java.awt.font=ALL-UNNAMED"
# Stop any instance left over from a previous run so the freshly written ROOT.xml (new DB password) is
# actually loaded — otherwise `start` no-ops against the already-running server and keeps stale config.
"$CATALINA/bin/catalina.sh" stop 15 -force >/dev/null 2>&1 || true
"$CATALINA/bin/catalina.sh" start
for i in $(seq 1 60); do [ -f "$BASE_DIR/initialized.txt" ] && break; sleep 5; done

echo "== Seeding settings + admin =="
HOST=$(printf '%s' "$BASE_URL" | sed -E 's#https?://##; s#/.*##')
PGPASSWORD="$DB_PASSWORD" sed "s/_ADMIN_EMAIL_/admin@${HOST}/g" install/sql/hmdm_init.en.sql \
  | PGPASSWORD="$DB_PASSWORD" psql -h 127.0.0.1 -U hmdm -d hmdm >/dev/null 2>&1 || echo "(seed warnings ok if already seeded)"
PGPASSWORD="$DB_PASSWORD" psql -h 127.0.0.1 -U hmdm -d hmdm -c \
  "UPDATE users SET password='$(pwhash "$ADMIN_PASSWORD")', passwordreset=true, passwordresettoken='${RESET_TOKEN}' WHERE login='admin';" >/dev/null

echo
echo "== MDMesh installed (native) =="
echo "  Tomcat:        $CATALINA (serving on :8080 — front it with your TLS reverse proxy at ${BASE_URL})"
echo "  Console:       ${BASE_URL}"
echo "  REST API base: ${BASE_URL}/rest"
echo "  Login:         admin / ${ADMIN_PASSWORD}   (temporary — you'll set your own on first login)"
echo "  Note: install 'aapt' for full uploaded-APK parsing; set up a systemd unit to keep Tomcat running."
