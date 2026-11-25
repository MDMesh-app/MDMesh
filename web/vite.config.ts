/// <reference types="vitest/config" />
import { fileURLToPath } from 'node:url';
import { defineConfig, loadEnv } from 'vite';
import react from '@vitejs/plugin-react';
import { changelogNotes } from './vite.changelog.ts';
import { THIRD_PARTY, licences } from './vite.licences.ts';

// Dev server. Every same-origin path the console calls is proxied to the dev stack's edge (Caddy on :8088, see
// docker-compose.dev.yml), which routes it exactly as production does: /rest, /files and /agent/ws to the server,
// /update and /recovery to the supervisor. Override the target with VITE_DEV_PROXY_TARGET.
export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), 'VITE_');
  const target = env.VITE_DEV_PROXY_TARGET || 'http://localhost:8088';
  // The server is session-cookie based (JSESSIONID), so cookies must be forwarded. /agent/ws is a WebSocket.
  const route = (ws = false) => ({ target, changeOrigin: true, cookieDomainRewrite: '', ws });

  return {
    plugins: [react(), changelogNotes(env.VITE_APP_VERSION), licences()],
    resolve: {
      // The public surface extensions import (web/src/sdk), and what their tests import; tsconfig.app.json maps the
      // same names for the compiler. Exact matches, so `@mdmesh/console/<anything else>` resolves to nothing.
      alias: [
        { find: /^@mdmesh\/console$/, replacement: fileURLToPath(new URL('./src/sdk/index.ts', import.meta.url)) },
        { find: /^@mdmesh\/console\/testing$/, replacement: fileURLToPath(new URL('./src/sdk/testing.tsx', import.meta.url)) },
      ],
    },
    test: {
      environment: 'jsdom',
      setupFiles: ['./src/test/setup.ts'],
      // Several cold waits in one test (asyncUtilTimeout, src/test/setup.ts) have to fit in it, on a busy machine too.
      testTimeout: 30_000,
      // Vitest 5's on-disk transform cache keeps an import.meta.glob expanded as it was, so a newly added
      // extensions/<id>/ folder would skip the manifest guard (ext/load.test.ts) until the cache is cleared.
      fsModuleCache: false,
    },
    build: {
      // The bundled packages and their licences, served at /licenses/THIRD-PARTY.json; licences() checks them.
      license: { fileName: THIRD_PARTY },
      rolldownOptions: {
        output: {
          // Third-party code in its own chunks: the app chunk stays under Vite's 500 kB warning, and a console
          // upgrade that only changes app code leaves the browser's cached vendor chunks valid.
          codeSplitting: {
            // A group takes only the modules its test names; what they import goes to its own group (vendor).
            includeDependenciesRecursively: false,
            groups: [
              { name: 'react', test: /node_modules[\\/](react|react-dom|react-router|scheduler|cookie|set-cookie-parser)[\\/]/, priority: 2 },
              { name: 'leaflet', test: /node_modules[\\/]leaflet[\\/]/, priority: 2 },
              // The QR encoder is imported on demand (ui/QrCode): its own chunk keeps it out of the
              // vendor chunk every page loads, so only Enroll downloads it.
              { name: 'qrcode', test: /node_modules[\\/](qrcode|dijkstrajs)[\\/]/, priority: 2 },
              // Radix's Select and Popover are for forms and filters, not the shell: their own chunks load with the
              // first page that shows one, so the shell (menus, dialogs, tooltips) doesn't carry them.
              { name: 'radix-select', test: /node_modules[\\/]@radix-ui[\\/]react-select[\\/]/, priority: 2 },
              { name: 'radix-popover', test: /node_modules[\\/]@radix-ui[\\/]react-popover[\\/]/, priority: 2 },
              { name: 'vendor', test: /node_modules[\\/]/, priority: 1 },
            ],
          },
        },
      },
    },
    server: {
      host: true,
      port: 5173,
      allowedHosts: true,
      proxy: {
        '/rest': route(),
        '/files': route(),
        '/agent/ws': route(true),
        '/update': route(),
        '/recovery': route(),
      },
    },
  };
});
