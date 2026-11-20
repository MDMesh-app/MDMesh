# MDMesh-managed: the native installer rewrites this file. Remove this line to keep your own.
# Tomcat's bin/setenv.sh, in the Docker image (docker/server.Dockerfile) and in a native install's Tomcat
# (install-native.sh rewrites it on every run while the line above is there). catalina.sh sources it at every start, after reading JAVA_OPTS and
# CATALINA_OPTS from the environment, so the flags added here stay whatever those set (SERVER_JAVA_OPTS reaches the
# Docker server as JAVA_OPTS; the dev stack and the native unit set CATALINA_OPTS). catalina.sh puts CATALINA_OPTS after
# JAVA_OPTS, so they also win. A flag the native unit sets too is simply given twice.
#
# -XX:-UseBASE64Intrinsics: CVE-2026-114938 (OpenJDK), the AVX-512 Base64 decoder of JDK 17.0.6 to 17.0.21 on x86_64.
# A product flag on every JDK build; CI checks it is in effect (scripts/server-jvm-check.sh, tests/native).
CATALINA_OPTS="${CATALINA_OPTS:+$CATALINA_OPTS }-XX:-UseBASE64Intrinsics"
