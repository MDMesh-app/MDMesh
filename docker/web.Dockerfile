# Edge image: builds the React SPA, then serves it from Caddy (which also reverse-proxies the API).
# The SPA calls the API at the same origin (apiClient base "/rest"), so it's deployment-agnostic —
# no server URL is baked into the web bundle.

FROM node:24-alpine AS web
WORKDIR /web
# Release CI passes the agent's package + signing checksum (+ optional APK URL) so the in-product
# enrollment QR matches the signed release APK. Defaults (in provisioning.ts) cover the debug build.
ARG VITE_AGENT_PACKAGE
ARG VITE_AGENT_CHECKSUM
ARG VITE_AGENT_APK_URL
# Build version, baked so the open console can detect it lags a freshly-deployed one (ReloadPrompt).
ARG VITE_APP_VERSION=dev
ENV VITE_AGENT_PACKAGE=$VITE_AGENT_PACKAGE \
    VITE_AGENT_CHECKSUM=$VITE_AGENT_CHECKSUM \
    VITE_AGENT_APK_URL=$VITE_AGENT_APK_URL \
    VITE_APP_VERSION=$VITE_APP_VERSION
COPY web/package*.json ./
RUN npm ci
COPY web/ ./
# The What's new dialog's notes, read at build time from the repository's CHANGELOG.md (web/vite.changelog.ts); the build fails without it.
COPY CHANGELOG.md /CHANGELOG.md
# LICENSE and NOTICE, served at /licenses/ with the bundle's third-party list, and the allowlist the build checks that
# list against (web/vite.licences.ts).
COPY LICENSE NOTICE /
COPY docs/licenses/allowed.txt /docs/licenses/allowed.txt
RUN npm run build

# An exact Caddy release, not caddy:2-alpine: a Caddy release can change how docker/Caddyfile parses (v0.3.1's
# Cloudflare-mode outage was a Caddyfile that stopped parsing). Dependabot proposes each new release in its weekly
# docker-images PR, and T0's edge check (scripts/edge-check.sh, which reads the image from this line) validates the
# Caddyfile in every hosting mode against it before it can merge. The tag itself is still rebuilt upstream for
# Alpine fixes.
FROM caddy:2.11.9-alpine
# Run Caddy unprivileged. It still needs to bind :80/:443 in own-domain mode, so grant just that capability
# to the binary; /data (certs) and /config are mounted volumes that older deployments created root-owned,
# so a tiny root entrypoint fixes their ownership and then su-execs to "caddy".
RUN apk add --no-cache libcap su-exec \
 && addgroup -S caddy && adduser -S -G caddy -h /data caddy \
 && setcap cap_net_bind_service=+ep /usr/bin/caddy
COPY --from=web /web/dist /srv
COPY docker/Caddyfile /etc/caddy/Caddyfile
COPY docker/web-entrypoint.sh /web-entrypoint.sh
COPY docker/edge-peers.sh /edge-peers.sh
# Every caddy command in the container reads the settings edge-peers.sh computed at start (see the file).
COPY docker/edge-caddy.sh /usr/local/bin/caddy
# /etc/caddy/site.d: where compose mounts your own rules (the install directory's site.d/, read-only); empty in the image.
# Only what caddy writes is its own (/data: certificates and the internal CA; /config: its autosaved config). The SPA,
# the Caddyfile, the scripts and the site.d mount point stay root's, so the running edge can't change its own
# configuration or what the next start runs.
RUN mkdir -p /etc/caddy/site.d && chmod +x /web-entrypoint.sh /usr/local/bin/caddy && chown -R caddy:caddy /data /config
EXPOSE 80 443 8080
ENTRYPOINT ["/web-entrypoint.sh"]
CMD ["caddy", "run", "--config", "/etc/caddy/Caddyfile", "--adapter", "caddyfile"]
