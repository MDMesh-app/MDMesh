#!/bin/sh
# Generates the Tomcat ROOT context (server config) from environment at start, then runs Tomcat.
# Keeping it env-driven means the same image serves any deployment — the setup wizard supplies
# values via .env. Secrets are generated alphanumeric (no XML-special chars), so plain interpolation
# is safe; operator-typed SMTP values go through xml_attr. MQTT is intentionally off (our agent wakes over WebSocket; see ROOT.xml mqtt.server.uri="").
set -e

# The server writes /opt/mdmesh/initialized.txt when its initialization is over ("OK", or the error), and only if the
# file is absent. It is on the persistent volume, so remove the previous start's first: setup.sh, quickstart.sh and
# scripts/dev-seed.sh wait for it and read it as this start's result. First thing, so a start that fails below does not
# leave the last one's "OK" behind. rm -f removes a link planted there, never its target.
rm -f /opt/mdmesh/initialized.txt

: "${DB_HOST:=postgres}"
: "${DB_PORT:=5432}"
: "${DB_NAME:=mdmesh}"
: "${DB_USER:=mdmesh}"
: "${BASE_URL:?BASE_URL is required}"
: "${HASH_SECRET:?HASH_SECRET is required}"
: "${DB_PASSWORD:?DB_PASSWORD is required}"
: "${SECURE_ENROLLMENT:=0}"
: "${LEGACY_DEVICE_API:=0}"
: "${SMTP_HOST:=}"
: "${SMTP_PORT:=25}"
: "${SMTP_FROM:=mdm@localhost}"
: "${SMTP_SSL:=false}"
: "${SMTP_STARTTLS:=false}"
: "${JWT_SECRET:=}"

# xml_attr RAW: RAW escaped for an XML attribute value (an SMTP password with & or " must not break ROOT.xml).
xml_attr() { printf '%s' "$1" | sed -e 's/&/\&amp;/g' -e 's/</\&lt;/g' -e 's/>/\&gt;/g' -e 's/"/\&quot;/g' -e "s/'/\&apos;/g"; }

CONF_DIR=/usr/local/tomcat/conf/Catalina/localhost
# The chown -R below hands conf/Catalina to the server user, and root writes ROOT.xml into CONF_DIR on the next start:
# never follow a link planted there (a linked CONF_DIR is removed and recreated as a directory).
[ -L "$CONF_DIR" ] && rm -f "$CONF_DIR"
mkdir -p "$CONF_DIR" /opt/mdmesh/files /opt/mdmesh/plugins

# jwt.secretkey signs the JWTs of REST API clients (/rest/public/jwt/login; the console uses its session cookie). Left
# empty, the server picks a random key at every start and signs those clients out on each restart. So: an explicit
# JWT_SECRET wins; otherwise the key is generated once into the persistent /opt/mdmesh volume and reused on every
# start. That covers every Docker install with no manual step, including quick-start ones whose compose never changes.
# JJWT 0.9.1 base64-decodes the key and silently DROPS characters outside the base64 alphabet and a trailing partial
# 4-character group, so accept only hex, a multiple of 4 characters, at least 128 (512 bits, the HS512 minimum).
JWT_SECRET_FILE=/opt/mdmesh/jwt.secret
jwt_secret_ok() {
  case "$1" in '' | *[!0-9a-fA-F]*) return 1 ;; esac
  [ "${#1}" -ge 128 ] && [ $(( ${#1} % 4 )) -eq 0 ]
}
# Temp key files (mktemp below) of a start that was killed before publishing its key hold a key: remove them first.
# They have a name of their own (.jwt.secret.mdmesh-tmp.XXXXXX), so an admin's jwt.secret.backup is never matched.
# (Earlier images used jwt.secret.XXXXXX; such leftovers are left alone.)
rm -f /opt/mdmesh/.jwt.secret.mdmesh-tmp.??????
if [ -n "$JWT_SECRET" ]; then
  if ! jwt_secret_ok "$JWT_SECRET"; then
    echo "JWT_SECRET (SERVER_JWT_SECRET in .env) must be hex, a multiple of 4 characters and at least 128 long (the JWT library would silently drop anything else). Generate one with: openssl rand -hex 64 (or unset it to use the key kept in $JWT_SECRET_FILE)" >&2
    exit 1
  fi
else
  # This runs as root in a directory the server user owns: never follow a link there (chmod/read would act on its target).
  [ -L "$JWT_SECRET_FILE" ] && rm -f "$JWT_SECRET_FILE"
  # Whitespace is not part of a key (a hand-pinned file saved with CRLF or a stray tab must not be rotated).
  [ -f "$JWT_SECRET_FILE" ] && JWT_SECRET=$(tr -d '[:space:]' < "$JWT_SECRET_FILE")
  if ! jwt_secret_ok "$JWT_SECRET"; then
    if [ -e "$JWT_SECRET_FILE" ]; then
      echo "WARNING: $JWT_SECRET_FILE does not hold a valid key; replacing it (REST API clients sign in again once)." >&2
      rm -f "$JWT_SECRET_FILE"
    fi
    _jwt_new=$(od -An -v -tx1 -N64 /dev/urandom | tr -d ' \n')
    jwt_secret_ok "$_jwt_new" || { echo "Could not generate a JWT signing key from /dev/urandom." >&2; exit 1; }
    # mktemp: a fresh, unique mode-600 file (O_EXCL, so a planted name or link is never written through). ln publishes
    # it only if no key exists yet, so concurrent first starts converge on the first writer's key; mv is the fallback
    # for a volume without hard links. The chown -R below hands the file to the server user. The trap removes the temp
    # file if this start fails before it is published.
    _jwt_tmp=$(mktemp /opt/mdmesh/.jwt.secret.mdmesh-tmp.XXXXXX)
    trap 'rm -f "$_jwt_tmp"' EXIT
    printf '%s\n' "$_jwt_new" > "$_jwt_tmp"
    ln "$_jwt_tmp" "$JWT_SECRET_FILE" 2>/dev/null || [ -e "$JWT_SECRET_FILE" ] || mv -f "$_jwt_tmp" "$JWT_SECRET_FILE"
    rm -f "$_jwt_tmp"
    trap - EXIT
    # Use what the file holds (possibly another start's key), so this process always matches the file.
    JWT_SECRET=$(tr -d '[:space:]' < "$JWT_SECRET_FILE")
    jwt_secret_ok "$JWT_SECRET" || { echo "$JWT_SECRET_FILE does not hold a valid key after writing it." >&2; exit 1; }
  fi
  chmod 600 "$JWT_SECRET_FILE"
fi

# Written to a fresh mktemp file (O_EXCL, mode 600: it holds the DB password) that is renamed over ROOT.xml in one step
# (mv -fT replaces a link planted there instead of following it; a planted directory stops the start). The temp file
# holds the secrets too: the trap removes it if this start fails before the rename, and leftovers of a start that was
# killed outright are removed first, by the temp file's own name only (an admin's ROOT.xml.backup is kept).
rm -f "$CONF_DIR"/.ROOT.xml.mdmesh-tmp.??????
_root_xml_tmp=$(mktemp "$CONF_DIR/.ROOT.xml.mdmesh-tmp.XXXXXX")
trap 'rm -f "$_root_xml_tmp"' EXIT
cat > "$_root_xml_tmp" <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<Context>
    <Parameter name="JDBC.driver"   value="org.postgresql.Driver"/>
    <Parameter name="JDBC.url"      value="jdbc:postgresql://${DB_HOST}:${DB_PORT}/${DB_NAME}"/>
    <Parameter name="JDBC.username" value="${DB_USER}"/>
    <Parameter name="JDBC.password" value="${DB_PASSWORD}"/>

    <Parameter name="base.directory"  value="/opt/mdmesh"/>
    <Parameter name="files.directory" value="/opt/mdmesh/files"/>
    <Parameter name="base.url"        value="${BASE_URL}"/>

    <Parameter name="usage.scenario"    value="private"/>
    <Parameter name="secure.enrollment" value="${SECURE_ENROLLMENT}"/>
    <!-- Headwind launcher device routes: off unless 1. The MDMesh agent never uses them. -->
    <Parameter name="legacy.device.api" value="$(xml_attr "${LEGACY_DEVICE_API}")"/>
    <Parameter name="hash.secret"       value="${HASH_SECRET}"/>
    <Parameter name="jwt.secretkey"     value="${JWT_SECRET}"/>

    <Parameter name="plugins.files.directory" value="/opt/mdmesh/plugins"/>
    <Parameter name="plugin.devicelog.persistence.config.class"
               value="com.hmdm.plugins.devicelog.persistence.postgres.DeviceLogPostgresPersistenceConfiguration"/>
    <Parameter name="role.orgadmin.id" value="2"/>

    <Parameter name="swagger.host"      value=""/>
    <Parameter name="swagger.base.path" value="/rest"/>

    <Parameter name="initialization.completion.signal.file" value="/opt/mdmesh/initialized.txt"/>
    <Parameter name="aapt.command" value="aapt"/>

    <!-- MQTT broker disabled: the agent wakes over the WebSocket, not MQTT. -->
    <Parameter name="mqtt.server.uri" value=""/>
    <Parameter name="mqtt.auth" value="0"/>

    <Parameter name="device.fast.search.chars" value="5"/>

    <Parameter name="smtp.host" value="$(xml_attr "${SMTP_HOST}")"/>
    <Parameter name="smtp.port" value="$(xml_attr "${SMTP_PORT}")"/>
    <Parameter name="smtp.ssl" value="$(xml_attr "${SMTP_SSL}")"/>
    <Parameter name="smtp.starttls" value="$(xml_attr "${SMTP_STARTTLS}")"/>
    <Parameter name="smtp.username" value="$(xml_attr "${SMTP_USERNAME:-}")"/>
    <Parameter name="smtp.password" value="$(xml_attr "${SMTP_PASSWORD:-}")"/>
    <Parameter name="smtp.from" value="$(xml_attr "${SMTP_FROM}")"/>

    <Parameter name="email.recovery.subj" value="/opt/mdmesh/emails/_LANGUAGE_/recovery_subj.txt"/>
    <Parameter name="email.recovery.body" value="/opt/mdmesh/emails/_LANGUAGE_/recovery_body.txt"/>
</Context>
EOF
mv -fT "$_root_xml_tmp" "$CONF_DIR/ROOT.xml"
trap - EXIT

# Volumes from older deployments are root-owned; make them writable for the unprivileged user, then drop
# root for good. setpriv ships with util-linux on the Debian-based tomcat image (no gosu needed).
chown -R mdmesh:mdmesh /opt/mdmesh /usr/local/tomcat/conf/Catalina /usr/local/tomcat/logs /usr/local/tomcat/work /usr/local/tomcat/temp /usr/local/tomcat/webapps
# The secrets are in ROOT.xml now, and nothing reads them from the environment at runtime (the server has no
# System.getenv), so keep them out of the environment Tomcat inherits (/proc/<pid>/environ). JAVA_OPTS and CATALINA_OPTS
# stay: catalina.sh reads them.
unset DB_PASSWORD HASH_SECRET JWT_SECRET SMTP_PASSWORD
exec setpriv --reuid=mdmesh --regid=mdmesh --init-groups catalina.sh run
