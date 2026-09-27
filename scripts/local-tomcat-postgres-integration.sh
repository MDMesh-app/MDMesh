#!/usr/bin/env bash
# Run the Java control plane against an isolated PostgreSQL cluster and a stock
# Tomcat 10.1 distribution. This is the non-Docker deployed-WAR harness; Compose remains
# a required deployment verification path.
set -euo pipefail

repo_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
tomcat_version="10.1.60"
tomcat_sha512="aa06508300ca137a023b74b8600f2c1b3248412eb85d4fc5e2f337c6c4d3776f4491e272f79856ac541cfab0fc35537111ae4f3cfcd0bbe702c0a3610a61bd04"
tomcat_name="apache-tomcat-${tomcat_version}"
tomcat_url="https://archive.apache.org/dist/tomcat/tomcat-10/v${tomcat_version}/bin/${tomcat_name}.tar.gz"
if [[ -n "${MDMESH_JAVA_HOME:-}" ]]; then
    java_home="$MDMESH_JAVA_HOME"
elif [[ -n "${JAVA_HOME:-}" ]]; then
    java_home="$JAVA_HOME"
elif command -v javac >/dev/null; then
    java_home="$(cd "$(dirname "$(readlink -f "$(command -v javac)")")/.." && pwd)"
else
    java_home=""
fi
pg_bin="${PG_BINDIR:-$(pg_config --bindir)}"
db_user="$(id -un)"
work_dir="$(mktemp -d "${TMPDIR:-/tmp}/mdmesh-integration.XXXXXX")"
keep_workdir="${KEEP_WORKDIR:-0}"
pg_port="$(python3 - <<'PY'
import socket
s = socket.socket()
s.bind(('127.0.0.1', 0))
print(s.getsockname()[1])
s.close()
PY
)"
http_port="$(python3 - <<'PY'
import socket
s = socket.socket()
s.bind(('127.0.0.1', 0))
print(s.getsockname()[1])
s.close()
PY
)"
shutdown_port="$(python3 - <<'PY'
import socket
s = socket.socket()
s.bind(('127.0.0.1', 0))
print(s.getsockname()[1])
s.close()
PY
)"
pg_data="$work_dir/postgres"
pg_socket_dir="$work_dir/postgres-socket"
app_dir="$work_dir/app"
tomcat_dir="$work_dir/tomcat"
tomcat_log="$work_dir/tomcat.log"
pg_log="$work_dir/postgres.log"
cookie_jar="$work_dir/cookies.txt"

fail() {
    echo "ERROR: $*" >&2
    exit 1
}

cleanup() {
    local exit_code=$?
    if [[ -f "$pg_data/postmaster.pid" ]]; then
        "$pg_bin/pg_ctl" -D "$pg_data" -m fast -w stop >/dev/null 2>&1 || true
    fi
    if [[ -x "$tomcat_dir/bin/catalina.sh" ]]; then
        CATALINA_BASE="$tomcat_dir" CATALINA_HOME="$tomcat_dir" JAVA_HOME="$java_home" \
            "$tomcat_dir/bin/catalina.sh" stop >/dev/null 2>&1 || true
    fi
    if [[ "$keep_workdir" == "1" || $exit_code -ne 0 ]]; then
        echo "Integration work directory retained: $work_dir" >&2
        echo "  PostgreSQL log: $pg_log" >&2
        echo "  Tomcat log:     $tomcat_log" >&2
    else
        rm -rf -- "$work_dir"
    fi
    exit "$exit_code"
}
trap cleanup EXIT

for command in curl python3 readlink sha512sum tar pg_config; do
    command -v "$command" >/dev/null || fail "Required command not found: $command"
done
[[ -x "$java_home/bin/java" && -x "$java_home/bin/javac" ]] || fail "JDK 21 not found; set MDMESH_JAVA_HOME (or JAVA_HOME) to a JDK 21 installation"
[[ "$("$java_home/bin/java" -version 2>&1 | head -n1)" == *'version "21.'* || "$("$java_home/bin/java" -version 2>&1 | head -n1)" == *'version "25.'* ]] || fail "The current baseline requires JDK 21 or 25; set MDMESH_JAVA_HOME accordingly"
[[ -x "$pg_bin/initdb" && -x "$pg_bin/pg_ctl" ]] || fail "PostgreSQL server tools not found under $pg_bin; set PG_BINDIR"

echo "== build WAR with JDK $("$java_home/bin/java" -version 2>&1 | head -n1) =="
(
    cd "$repo_dir"
    JAVA_HOME="$java_home" PATH="$java_home/bin:$PATH" ./mvnw -B -ntp verify
)
war="$repo_dir/server/target/launcher.war"
[[ -f "$war" ]] || fail "Expected WAR was not produced: $war"

echo "== start isolated PostgreSQL =="
"$pg_bin/initdb" -D "$pg_data" --auth-local=trust --auth-host=trust --no-instructions >/dev/null
mkdir -p "$pg_socket_dir"
"$pg_bin/pg_ctl" -D "$pg_data" -l "$pg_log" -o "-h 127.0.0.1 -p $pg_port -k $pg_socket_dir" -w start >/dev/null
"$pg_bin/createdb" -h 127.0.0.1 -p "$pg_port" -U "$db_user" mdmesh

echo "== download stock Tomcat $tomcat_version =="
archive="$work_dir/$tomcat_name.tar.gz"
curl --fail --location --silent --show-error "$tomcat_url" -o "$archive"
echo "$tomcat_sha512  $archive" | sha512sum --check --status || fail "Tomcat archive checksum mismatch"
tar -xzf "$archive" -C "$work_dir"
mv "$work_dir/$tomcat_name" "$tomcat_dir"

mkdir -p "$tomcat_dir/conf/Catalina/localhost" "$app_dir/files/ADMIN" "$app_dir/plugins"
printf 'integration-file-smoke\n' > "$app_dir/files/ADMIN/integration.txt"
cp "$repo_dir/install/log4j_template.xml" "$app_dir/log4j-mdmesh.xml"
cp -R "$repo_dir/install/emails" "$app_dir/emails"
mkdir -p "$tomcat_dir/webapps/ROOT"
(cd "$tomcat_dir/webapps/ROOT" && "$java_home/bin/jar" -xf "$war")
sed -i "0,/port=\"8080\"/s//port=\"${http_port}\"/" "$tomcat_dir/conf/server.xml"
# Stock Tomcat reserves 8005 for its shutdown listener. Give the disposable instance an isolated
# listener too, otherwise it cannot coexist with a native MDMesh Tomcat on the same host.
sed -i -E "s#(<Server port=\")[0-9]+(\" shutdown=)#\1${shutdown_port}\2#" "$tomcat_dir/conf/server.xml"

cat > "$tomcat_dir/conf/Catalina/localhost/ROOT.xml" <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<Context>
    <Parameter name="JDBC.driver" value="org.postgresql.Driver"/>
    <Parameter name="JDBC.url" value="jdbc:postgresql://127.0.0.1:${pg_port}/mdmesh"/>
    <Parameter name="JDBC.username" value="${db_user}"/>
    <Parameter name="JDBC.password" value=""/>
    <Parameter name="base.directory" value="${app_dir}"/>
    <Parameter name="files.directory" value="${app_dir}/files"/>
    <Parameter name="plugins.files.directory" value="${app_dir}/plugins"/>
    <Parameter name="base.url" value="http://127.0.0.1:${http_port}"/>
    <Parameter name="usage.scenario" value="private"/>
    <Parameter name="secure.enrollment" value="0"/>
    <Parameter name="hash.secret" value="integration-test-secret"/>
    <Parameter name="role.orgadmin.id" value="2"/>
    <Parameter name="plugin.devicelog.persistence.config.class" value="com.hmdm.plugins.devicelog.persistence.postgres.DeviceLogPostgresPersistenceConfiguration"/>
    <Parameter name="initialization.completion.signal.file" value="${app_dir}/initialized.txt"/>
    <Parameter name="log4j.config" value="file://${app_dir}/log4j-mdmesh.xml"/>
    <Parameter name="aapt.command" value="aapt"/>
    <Parameter name="mqtt.server.uri" value=""/>
    <Parameter name="mqtt.auth" value="0"/>
    <Parameter name="device.fast.search.chars" value="5"/>
</Context>
EOF

echo "== deploy WAR to Tomcat =="
CATALINA_BASE="$tomcat_dir" CATALINA_HOME="$tomcat_dir" JAVA_HOME="$java_home" \
    CATALINA_OUT="$tomcat_log" CATALINA_OPTS="-Djava.awt.headless=true" "$tomcat_dir/bin/catalina.sh" start

echo "== wait for Liquibase initialization =="
for _ in $(seq 1 120); do
    if [[ -f "$app_dir/initialized.txt" ]]; then
        [[ "$(cat "$app_dir/initialized.txt")" == "OK" ]] || fail "Application initialization failed; see $tomcat_log"
        break
    fi
    sleep 1
done
[[ -f "$app_dir/initialized.txt" ]] || fail "Timed out waiting for application initialization; see $tomcat_log"

echo "== seed deterministic test data =="
sed 's/_ADMIN_EMAIL_/integration@example.invalid/g' "$repo_dir/install/sql/hmdm_init.en.sql" | \
    "$pg_bin/psql" -h 127.0.0.1 -p "$pg_port" -U "$db_user" -d mdmesh -v ON_ERROR_STOP=1 >/dev/null
"$pg_bin/psql" -h 127.0.0.1 -p "$pg_port" -U "$db_user" -d mdmesh -v ON_ERROR_STOP=1 \
    -c "UPDATE users SET passwordreset = false WHERE login = 'admin';" >/dev/null

base_url="http://127.0.0.1:$http_port"
echo "== verify deployed application =="
for _ in $(seq 1 30); do
    if curl --fail --silent --show-error "$base_url/files/ADMIN/integration.txt" | grep -qx 'integration-file-smoke'; then
        break
    fi
    sleep 1
done
curl --fail --silent --show-error "$base_url/files/ADMIN/integration.txt" | grep -qx 'integration-file-smoke' || fail "File-storage smoke test failed"

login_response="$(curl --fail --silent --show-error -c "$cookie_jar" -H 'Content-Type: application/json' \
    --data '{"login":"admin","password":"21232F297A57A5A743894A0E4A801FC3"}' "$base_url/rest/public/auth/login")"
python3 -c 'import json,sys; assert json.load(sys.stdin)["status"] == "OK"' <<<"$login_response" || fail "Admin login failed"
authenticated_response="$(curl --fail --silent --show-error -b "$cookie_jar" "$base_url/rest/private/summary/devices")"
python3 -c 'import json,sys; assert json.load(sys.stdin)["status"] == "OK"' <<<"$authenticated_response" || fail "Authenticated REST smoke test failed"

if [[ "${MDMESH_AGENT_V1_E2E:-0}" == "1" ]]; then
    echo "== verify Agent v1 HTTP and WebSocket contract =="
    "$pg_bin/psql" -h 127.0.0.1 -p "$pg_port" -U "$db_user" -d mdmesh -v ON_ERROR_STOP=1 \
        -c "UPDATE settings SET createnewdevices = true, newdeviceconfigurationid = 1;" >/dev/null
    "$repo_dir/scripts/agent-v1-e2e.sh" "$base_url"
fi

echo "PASS: WAR deployment, Liquibase, login/authenticated REST, and file storage"
