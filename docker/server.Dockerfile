# Control-plane image: build the WAR (Maven, JDK 17), drop it into Tomcat 9.
# Runtime config (DB, base.url, secrets) is generated from environment by entrypoint.sh, so the
# same image serves any deployment. JDK 17 is required — dependencies (e.g. activemq-broker) ship
# Java-11+ bytecode, so Java 8 fails to both compile and run.

FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /src
COPY pom.xml ./
COPY common ./common
COPY jwt ./jwt
COPY notification ./notification
COPY plugins ./plugins
COPY server ./server
COPY install ./install
RUN cp server/build.properties.example server/build.properties || true
RUN mvn -q -B -DskipTests package

# Pinned by digest (Dependabot bumps it), so a release ships the base it was tested on. Its amd64 image has JDK 17.0.21.1,
# with the fix for CVE-2026-114938 (OpenJDK); its arm64 image still has 17.0.21, where the bug can't run (it is x86_64-only).
# install/tomcat-setenv.sh turns the affected code off on either, and release.yml checks the built image
# (scripts/server-jvm-check.sh).
FROM tomcat:9.0.124-jdk17-temurin@sha256:3a78549294409e66975d1a02507be2dc42561877d52f8dbe62f0fca196d199d6
RUN rm -rf /usr/local/tomcat/webapps/*
COPY --from=build /src/server/target/launcher.war /usr/local/tomcat/webapps/ROOT.war
# App base directory (data, plugins, email templates). /opt/mdmesh should be a volume
# so uploaded files + the hosted agent APK survive container recreation.
RUN mkdir -p /opt/mdmesh/files /opt/mdmesh/plugins \
 && groupadd -r mdmesh && useradd -r -g mdmesh -d /opt/mdmesh -s /usr/sbin/nologin mdmesh
COPY install/emails /opt/mdmesh/emails
COPY docker/entrypoint.sh /entrypoint.sh
COPY install/tomcat-setenv.sh /usr/local/tomcat/bin/setenv.sh
RUN chmod +x /entrypoint.sh
# NOTE: full uploaded-APK metadata parsing uses `aapt`; install android build-tools aapt into the
# image for the richest parse (the Java apk-parser fallback covers basic package/version otherwise).
EXPOSE 8080
ENTRYPOINT ["/entrypoint.sh"]
