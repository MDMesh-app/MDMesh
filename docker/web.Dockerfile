# Edge image: builds the React SPA, then serves it from Caddy (which also reverse-proxies the API).
# The SPA calls the API at the same origin (apiClient base "/rest"), so it's deployment-agnostic —
# no server URL is baked into the web bundle.

FROM node:20-alpine AS web
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
COPY --from=web /web/dist /srv
COPY docker/Caddyfile /etc/caddy/Caddyfile
EXPOSE 80 443
