// Paths an extension page may not take. The registry checks every `route.page` against these and against the core
// pages' first segments, so a clash fails the guard test in CI instead of shadowing something in production.
import { paths } from '../routes';

/**
 * Sent to another backend before the console sees them, so a console page there would never load. Matched as a
 * plain prefix (`/updates` is taken too), because the native install's Tomcat rewrite is:
 *   RewriteRule ^/(?!rest|files|agent|update|healthz(?:/|$))(.*)$ /index.html   (install/install-native.sh)
 * which serves no console at `/restore`, `/agents` or `/updates` either. The others:
 *   /rest, /files, /agent/ws  -> the server        (docker/Caddyfile @backend; vite.config.ts proxy)
 *   /update, /recovery*       -> the supervisor    (docker/Caddyfile; server web.xml maps /update/* too)
 *   /healthz                  -> health probes     (docker/Caddyfile)
 *   /api                      -> the server's API  (server MainRestModule filters /api/*)
 */
export const EDGE_PREFIXES: readonly string[] = ['/rest', '/files', '/agent', '/update', '/recovery', '/healthz', '/api'];

/**
 * First segments the console itself answers outside the extension routes: signed-out pages, the component gallery,
 * old bookmarks that redirect (routes.ts legacyRedirect), and static files of the build (`/assets`, and `/licenses`
 * from web/public).
 */
export const CONSOLE_SEGMENTS: readonly string[] = [
  paths.login,
  paths.setPassword,
  paths.gallery,
  '/v2',
  '/dashboard',
  '/configs',
  '/overview',
  '/assets',
  '/licenses',
];
