#!/bin/sh
# Generates the Tomcat ROOT context (server config) from environment at start, then runs Tomcat.
# Keeping it env-driven means the same image serves any deployment — the setup wizard supplies
# values via .env. Secrets are generated alphanumeric (no XML-special chars), so plain interpolation
# is safe. MQTT is intentionally off (our agent wakes over WebSocket; see ROOT.xml mqtt.server.uri="").
set -e

: "${DB_HOST:=postgres}"
: "${DB_PORT:=5432}"
: "${DB_NAME:=mdmesh}"
: "${DB_USER:=mdmesh}"
: "${BASE_URL:?BASE_URL is required}"
: "${HASH_SECRET:?HASH_SECRET is required}"
: "${DB_PASSWORD:?DB_PASSWORD is required}"
: "${SECURE_ENROLLMENT:=0}"
: "${SMTP_HOST:=}"
: "${SMTP_PORT:=25}"
: "${SMTP_FROM:=mdm@localhost}"

CONF_DIR=/usr/local/tomcat/conf/Catalina/localhost
mkdir -p "$CONF_DIR" /opt/mdmesh/files /opt/mdmesh/plugins

cat > "$CONF_DIR/ROOT.xml" <<EOF
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
    <Parameter name="hash.secret"       value="${HASH_SECRET}"/>

    <Parameter name="plugins.files.directory" value="/opt/mdmesh/plugins"/>
    <Parameter name="plugin.devicelog.persistence.config.class"
               value="com.hmdm.plugins.devicelog.persistence.postgres.DeviceLogPostgresPersistenceConfiguration"/>
    <Parameter name="role.orgadmin.id" value="2"/>

    <Parameter name="swagger.host"      value=""/>
    <Parameter name="swagger.base.path" value="/rest"/>

    <Parameter name="initialization.completion.signal.file" value="/opt/mdmesh/initialized.txt"/>
    <Parameter name="log4j.config" value="file:///opt/mdmesh/log4j-mdmesh.xml"/>
    <Parameter name="aapt.command" value="aapt"/>

    <!-- MQTT broker disabled: the agent wakes over the WebSocket, not MQTT. -->
    <Parameter name="mqtt.server.uri" value=""/>
    <Parameter name="mqtt.auth" value="0"/>

    <Parameter name="device.fast.search.chars" value="5"/>

    <Parameter name="smtp.host" value="${SMTP_HOST}"/>
    <Parameter name="smtp.port" value="${SMTP_PORT}"/>
    <Parameter name="smtp.ssl" value="false"/>
    <Parameter name="smtp.starttls" value="false"/>
    <Parameter name="smtp.username" value="${SMTP_USERNAME:-}"/>
    <Parameter name="smtp.password" value="${SMTP_PASSWORD:-}"/>
    <Parameter name="smtp.from" value="${SMTP_FROM}"/>

    <Parameter name="email.recovery.subj" value="/opt/mdmesh/emails/_LANGUAGE_/recovery_subj.txt"/>
    <Parameter name="email.recovery.body" value="/opt/mdmesh/emails/_LANGUAGE_/recovery_body.txt"/>
</Context>
EOF

exec catalina.sh run
