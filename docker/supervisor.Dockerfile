# Updater/recovery supervisor — decoupled from the server so it survives a broken update.
# Node 20 (zero npm deps) + the minisign binary for manifest verification; the release public key is
# baked in so only properly-signed releases are ever trusted.
FROM node:20-alpine
# Deliberately root: the supervisor drives the host Docker daemon through /var/run/docker.sock (see
# docker-compose.yml), which is host-root-equivalent regardless of the in-container uid.
# minisign verifies the signed manifest; docker-cli + the compose plugin let the supervisor drive
# `docker compose pull/up` against the host daemon (socket mounted in compose) to apply updates.
# postgresql-client gives apply.sh pg_dump/psql for the pre-update backup + rollback restore.
RUN apk add --no-cache minisign docker-cli docker-cli-compose postgresql-client bash curl
WORKDIR /app
COPY supervisor/ /app/
COPY release/minisign.pub /app/minisign.pub
RUN chmod +x /app/apply.sh /app/rollback.sh
EXPOSE 9000
# Absolute on purpose (#27): compose files set working_dir /project, and a relative path would
# resolve against it. The entrypoint must not depend on the container's working directory.
CMD ["node", "/app/server.js"]
