# Tomcat's bin/setenv.sh: catalina.sh sources it at every start, after reading JAVA_OPTS and CATALINA_OPTS from the
# environment, so the flags added here stay whatever a deployment sets (SERVER_JAVA_OPTS reaches the server as
# JAVA_OPTS; the dev stack sets CATALINA_OPTS). catalina.sh puts CATALINA_OPTS after JAVA_OPTS, so they also win.
#
# -XX:-UseBASE64Intrinsics: CVE-2026-114938 (OpenJDK), the AVX-512 Base64 decoder of JDK 17.0.6 to 17.0.21 on x86_64.
# A product flag on every JDK build; release.yml checks it is in effect (scripts/server-jvm-check.sh).
CATALINA_OPTS="${CATALINA_OPTS:+$CATALINA_OPTS }-XX:-UseBASE64Intrinsics"
