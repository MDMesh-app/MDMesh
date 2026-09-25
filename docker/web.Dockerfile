# Edge image: builds the React SPA, then serves it from Caddy (which also reverse-proxies the API).
# The SPA calls the API at the same origin (apiClient base "/rest"), so it's deployment-agnostic —
# no server URL is baked into the web bundle.

FROM node:26-alpine AS web
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
RUN npm run build

FROM caddy:2-alpine
# Run Caddy unprivileged. It still needs to bind :80/:443 in own-domain mode, so grant just that capability
# to the binary; /data (certs) and /config are mounted volumes that older deployments created root-owned,
# so a tiny root entrypoint fixes their ownership and then su-execs to "caddy".
RUN apk add --no-cache libcap su-exec \
 && addgroup -S caddy && adduser -S -G caddy -h /data caddy \
 && setcap cap_net_bind_service=+ep /usr/bin/caddy
COPY --from=web /web/dist /srv
COPY docker/Caddyfile /etc/caddy/Caddyfile
COPY docker/web-entrypoint.sh /web-entrypoint.sh
RUN chmod +x /web-entrypoint.sh && chown -R caddy:caddy /srv /etc/caddy /data /config
EXPOSE 80 443
ENTRYPOINT ["/web-entrypoint.sh"]
CMD ["caddy", "run", "--config", "/etc/caddy/Caddyfile", "--adapter", "caddyfile"]
