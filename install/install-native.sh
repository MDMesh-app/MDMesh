#!/usr/bin/env bash
# Lean native (non-Docker) installer for MDMesh — Debian/Ubuntu. The leaner path: it stands up
# Postgres + Tomcat 9 + the server on the host and assumes you terminate TLS yourself (your own
# reverse proxy / cert, or put Caddy in front). For the turnkey experience use ./setup.sh (Docker).
# Best-effort + idempotent-ish; review before running on a production host.
set -euo pipefail
cd "$(dirname "$0")/.."

[ "$(id -u)" = "0" ] || { echo "Run as root (sudo)."; exit 1; }
command -v apt-get >/dev/null || { echo "This script targets Debian/Ubuntu."; exit 1; }

SALT='5YdSYHyg2U'
rand() { openssl rand -hex 24; }
pwhash() { local m; m=$(printf '%s' "$1" | md5sum | awk '{print toupper($1)}'); printf '%s' "${m}${SALT}" | sha1sum | awk '{print $1}'; }

read -rp "Public base URL (e.g. https://mdm.example.com): " BASE_URL
DB_PASSWORD=$(rand); HASH_SECRET=$(rand); ADMIN_PASSWORD=$(rand); RESET_TOKEN=$(openssl rand -hex 16)
BASE_DIR=/opt/hmdm
CATALINA=/opt/mdmesh-tc
TOMCAT_VER=9.0.89

echo "== Installing dependencies =="
apt-get update -y
apt-get install -y openjdk-17-jdk postgresql maven curl

echo "== Database =="
sudo -u postgres psql -tc "SELECT 1 FROM pg_roles WHERE rolname='hmdm'" | grep -q 1 || \
  sudo -u postgres psql -c "CREATE USER hmdm WITH PASSWORD '${DB_PASSWORD}';"
sudo -u postgres psql -tc "SELECT 1 FROM pg_database WHERE datname='hmdm'" | grep -q 1 || \
  sudo -u postgres psql -c "CREATE DATABASE hmdm OWNER hmdm;"

echo "== Building the server WAR =="
cp server/build.properties.example server/build.properties 2>/dev/null || true
mvn -q -B -DskipTests -pl server -am package

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
JAVA_HOME=$(dirname "$(dirname "$(readlink -f "$(command -v javac)")")")
export JAVA_HOME
export CATALINA_OPTS="--add-opens java.base/java.lang=ALL-UNNAMED --add-opens java.base/java.lang.reflect=ALL-UNNAMED --add-opens java.base/java.util=ALL-UNNAMED --add-opens java.base/java.text=ALL-UNNAMED --add-opens java.desktop/java.awt.font=ALL-UNNAMED"
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
