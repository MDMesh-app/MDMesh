# syntax=docker/dockerfile:1.7
# Control-plane image: build the WAR (Maven, JDK 21), drop it into Tomcat 10.1.
# Runtime config (DB, base.url, secrets) is generated from environment by entrypoint.sh, so the
# same image serves any deployment. The Java 21 release target requires JDK 21 to compile and run.

FROM maven:3.9-eclipse-temurin-21 AS build
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
# Some enterprise networks TLS-inspect Maven repositories. An opt-in BuildKit secret can supply
# one locally managed root CA to this build stage; it is never copied into the runtime image.
# The trust entries are removed after Maven completes so they are not retained in the build layer.
RUN --mount=type=secret,id=mdmesh_build_ca,required=false,target=/run/secrets/mdmesh-build-ca.crt \
    --mount=type=cache,target=/root/.m2 \
    if [ -s /run/secrets/mdmesh-build-ca.crt ]; then \
        install -m 0644 /run/secrets/mdmesh-build-ca.crt /usr/local/share/ca-certificates/mdmesh-build-ca.crt && \
        update-ca-certificates && \
        keytool -importcert -noprompt -trustcacerts -cacerts -storepass changeit \
            -alias mdmesh-build-ca -file /run/secrets/mdmesh-build-ca.crt; \
    fi && \
    mvn -B -DskipTests package && \
    if [ -s /run/secrets/mdmesh-build-ca.crt ]; then \
        keytool -delete -cacerts -storepass changeit -alias mdmesh-build-ca && \
        rm -f /usr/local/share/ca-certificates/mdmesh-build-ca.crt && \
        update-ca-certificates --fresh; \
    fi

# Pin the Tomcat patch line so a rebuild cannot silently change the servlet container.
FROM tomcat:10.1.60-jdk21-temurin
RUN rm -rf /usr/local/tomcat/webapps/*
COPY --from=build /src/server/target/launcher.war /usr/local/tomcat/webapps/ROOT.war
# App base directory (data, plugins, logging config, email templates). /opt/mdmesh should be a volume
# so uploaded files + the hosted agent APK survive container recreation.
RUN mkdir -p /opt/mdmesh/files /opt/mdmesh/plugins \
 && groupadd -r mdmesh && useradd -r -g mdmesh -d /opt/mdmesh -s /usr/sbin/nologin mdmesh
COPY install/log4j_template.xml /opt/mdmesh/log4j-mdmesh.xml
COPY install/emails /opt/mdmesh/emails
COPY docker/entrypoint.sh /entrypoint.sh
RUN chmod +x /entrypoint.sh
# NOTE: full uploaded-APK metadata parsing uses `aapt`; install android build-tools aapt into the
# image for the richest parse (the Java apk-parser fallback covers basic package/version otherwise).
EXPOSE 8080
ENTRYPOINT ["/entrypoint.sh"]
