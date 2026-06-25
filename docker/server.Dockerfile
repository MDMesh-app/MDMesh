# Control-plane image: build the WAR (Maven, Java 8 source), drop it into Tomcat 9.
# Runtime config (DB, base.url, secrets) is generated from environment by entrypoint.sh, so the
# same image serves any deployment. NOTE: not built in the authoring sandbox (no Docker there) —
# first build happens on a Docker host via docker-compose.yml / setup.sh.

FROM maven:3.9-eclipse-temurin-8 AS build
WORKDIR /src
COPY pom.xml ./
COPY common ./common
COPY jwt ./jwt
COPY notification ./notification
COPY plugins ./plugins
COPY server ./server
COPY swagger ./swagger
COPY install ./install
RUN cp server/build.properties.example server/build.properties || true
RUN mvn -q -B -DskipTests package

FROM tomcat:9.0-jdk8-temurin
RUN rm -rf /usr/local/tomcat/webapps/*
COPY --from=build /src/server/target/launcher.war /usr/local/tomcat/webapps/ROOT.war
# App base directory (data, plugins, logging config, email templates). /opt/hmdm should be a volume
# so uploaded files + the hosted agent APK survive container recreation.
RUN mkdir -p /opt/hmdm/files /opt/hmdm/plugins
COPY install/log4j_template.xml /opt/hmdm/log4j-hmdm.xml
COPY install/emails /opt/hmdm/emails
COPY docker/entrypoint.sh /entrypoint.sh
RUN chmod +x /entrypoint.sh
# NOTE: full uploaded-APK metadata parsing uses `aapt`; install android build-tools aapt into the
# image for the richest parse (the Java apk-parser fallback covers basic package/version otherwise).
EXPOSE 8080
ENTRYPOINT ["/entrypoint.sh"]
