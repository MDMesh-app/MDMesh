// Shared plumbing for the screenshot scripts (capture.mjs, compare-v2.mjs, v2-parity/): a static server for the built SPA and
// a Playwright browser context whose REST and /update calls are answered from fixtures.mjs.
import http from 'node:http';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { buildFixtures, buildOverviewFixtures } from './fixtures.mjs';
// --- v2 configurations fixtures ---
import { configurationsFixtures } from './fixtures.mjs';
// --- end v2 configurations fixtures ---

const __dirname = path.dirname(fileURLToPath(import.meta.url));
export const DIST = path.resolve(__dirname, '../../web/dist');
export const fx = buildFixtures();
const fxOverview = buildOverviewFixtures(); // v2 overview fixtures
// --- v2 configurations fixtures ---
const cfx = configurationsFixtures(fx);
// --- end v2 configurations fixtures ---
// --- v2 enroll fixtures ---
import { buildEnrollFixtures } from './fixtures.mjs';
const efx = buildEnrollFixtures(fx);
// --- end v2 enroll fixtures ---
// One /configurations/search answer for every page: the configurations page's full rows with Enroll's saved Wi-Fi
// merged in where a row has none (Field Ops).
const configRows = cfx.configs.map((c) => (c.wifiSSID ? c : { ...c, ...efx.wifi[c.id] }));

// ---- tiny static server for the SPA (with history fallback to index.html) ----
const MIME = {
  '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css', '.json': 'application/json',
  '.png': 'image/png', '.svg': 'image/svg+xml', '.ico': 'image/x-icon', '.woff2': 'font/woff2',
  '.woff': 'font/woff', '.map': 'application/json', '.webmanifest': 'application/manifest+json',
};
export function serve(dist = DIST) {
  return http.createServer((req, res) => {
    const urlPath = decodeURIComponent(req.url.split('?')[0]);
    let file = path.join(dist, urlPath);
    if (!fs.existsSync(file) || fs.statSync(file).isDirectory()) {
      const ext = path.extname(urlPath);
      file = ext && ext !== '.html' ? file : path.join(dist, 'index.html'); // SPA fallback
    }
    if (!fs.existsSync(file)) { res.writeHead(404).end('not found'); return; }
    res.writeHead(200, { 'content-type': MIME[path.extname(file)] || 'application/octet-stream' });
    fs.createReadStream(file).pipe(res);
  });
}

/** Starts the static server on a free port; resolves to { server, base }. */
export async function startServer(dist = DIST) {
  const server = serve(dist);
  await new Promise((r) => server.listen(0, '127.0.0.1', r));
  return { server, base: `http://127.0.0.1:${server.address().port}` };
}

// ---- map an intercepted request to its fixture (envelope-wrapped for /rest) ----
const ok = (data) => ({ status: 'OK', message: null, data });
/** restData's answer for a call the server refuses: sent as an ERROR envelope (error.permission.denied). */
const DENIED = Symbol('permission denied');
const envelopeFor = (data) => (data === DENIED ? { status: 'ERROR', message: 'error.permission.denied', data: null } : ok(data));
const deviceListView = { configurations: fx.configurations, devices: { items: fx.devices, totalItemsCount: fx.devices.length } };

/**
 * The fixture answer for one REST call. `page` is the pathname of the page that made it (from the request's frame):
 * page blocks that override shared answers with page-specific data apply only on their own page, so every page sees
 * the same data its compare shots and parity tests were built against.
 */
export function restData(method, p, page = '') {
  // --- v2 device-detail fixtures --- (its telemetry, configStatus and command history are for the detail page only)
  if (/^\/devices\/[^/]+$/.test(page)) { const r = deviceDetailRest(method, p, deviceDetailFx); if (r !== undefined) return r; }
  // --- end v2 device-detail fixtures ---
  // --- v2 configurations + enroll fixtures --- (full configuration rows: the editor's fields plus Enroll's Wi-Fi)
  if (method === 'GET' && p.endsWith('/configurations/search')) return configRows;
  // The same configurations' ids and names (LookupItem), as the server's /list answers: Deploy's picker.
  if (method === 'GET' && p.endsWith('/configurations/list')) return configRows.map(({ id, name }) => ({ id, name }));
  if (method === 'GET' && /\/configurations\/applications\/\d+$/.test(p)) return cfx.appsFor(Number(p.split('/').pop()));
  if (method === 'GET' && p.endsWith('/agent/v1/configurations/syncSummary') && page.startsWith('/configurations')) return cfx.syncSummary;
  if (method === 'GET' && /\/private\/configurations\/\d+$/.test(p) && page.startsWith('/configurations')) {
    // The full configuration (editor); an unknown id is "permission denied" on the server (see envelopeFor).
    const id = Number(p.split('/').pop());
    const row = configRows.find((c) => c.id === id);
    return row ? { ...row, ...cfx.fullExtras(id) } : DENIED;
  }
  if (method === 'POST' && p.endsWith('/agent/v1/token')) return efx.token();
  // --- end v2 configurations + enroll fixtures ---
  // --- v2 apps fixtures ---
  { const r = appsRest(method, p, appsFx); if (r !== undefined) return r; }
  // --- end v2 apps fixtures ---
  if (p === '/public/auth/options') return fx.authOptions;
  if (p === '/public/auth/login') return fx.user;
  // --- v2 device-detail exact lookup --- (GET /private/devices/number/{number}: exact match or null, like the server)
  { const m = p.match(/^\/private\/devices\/number\/([^/]+)$/); if (method === 'GET' && m) return fx.devices.find((d) => d.number === decodeURIComponent(m[1])) ?? null; }
  // --- end v2 device-detail exact lookup ---
  if (p.endsWith('/devices/search')) return deviceListView;
  if (p.endsWith('/configurations/search')) return fx.configList;
  if (p.endsWith('/applications/search') || /\/applications\/search\//.test(p)) return fx.applications;
  if (/\/devices\/[^/]+\/state$/.test(p)) return fx.state;
  if (/\/devices\/[^/]+\/telemetry$/.test(p)) return fx.telemetry;
  if (/\/devices\/[^/]+\/events/.test(p)) return fx.events;
  if (/\/devices\/[^/]+\/locations/.test(p)) return fx.locations;
  if (p.endsWith('/rollout/active')) return fx.activeRollout;
  if (/\/applications\/\d+\/versions/.test(p)) return [];
  // --- v2 overview fixtures ---
  if (p.endsWith('/configurations/syncSummary')) return fxOverview.syncSummary;
  { const m = p.match(/\/devices\/([^/]+)\/configStatus$/); if (m) return fxOverview.configStatus[decodeURIComponent(m[1])] ?? null; }
  // --- end v2 overview fixtures ---
  if (method === 'POST') return {};           // bulk ops etc.
  return [];                                   // unknown GET → empty list (never errors the UI)
}

/**
 * The What's new dialog (web/src/whatsnew) opens once per version after an upgrade, and the stored sign-in below
 * looks like one: by default each context has seen every version in CHANGELOG.md, the fixtures' and a dev build's
 * Unreleased, so no page opens with it, whatever VITE_APP_VERSION the build has. `whatsNewSeen: []` leaves it to the
 * console (v2-parity/settings.mjs).
 */
const WHATS_NEW_SEEN = [
  ...fs.readFileSync(path.resolve(__dirname, '../../CHANGELOG.md'), 'utf8').matchAll(/^## v(\d+\.\d+\.\d+)/gm),
].map((m) => m[1]).concat(fx.updateStatus.current, 'unreleased');

/**
 * Browser context with the theme (+ auth when given) seeded before any app script runs and the SPA's API calls
 * answered from fixtures. Options: theme 'light' | 'dark' (default dark), viewport, deviceScaleFactor,
 * blockGoogleFonts (the console self-hosts IBM Plex: a broken bundled file must not hide behind Google's copy), and
 * whatsNewSeen (the What's new versions marked seen, above).
 */
export async function newContext(
  browser,
  user,
  {
    theme = 'dark',
    viewport = { width: 1440, height: 900 },
    deviceScaleFactor = 2,
    blockGoogleFonts = false,
    whatsNewSeen = WHATS_NEW_SEEN,
  } = {},
) {
  const context = await browser.newContext({ viewport, deviceScaleFactor });
  if (blockGoogleFonts) {
    await context.route(/^https:\/\/fonts\.(googleapis|gstatic)\.com\//, (route) => route.abort());
  }
  await context.addInitScript(({ u, t, seen }) => {
    if (u) localStorage.setItem('hmdm.admin.user', JSON.stringify(u));
    localStorage.setItem('mdmesh-theme', t);
    for (const v of seen) localStorage.setItem(`mdmesh-whatsnew:${v}`, 'seen');
  }, { u: user, t: theme, seen: whatsNewSeen });

  await context.route('**/rest/**', async (route) => {
    const url = new URL(route.request().url());
    const p = url.pathname.replace(/^\/rest/, '');
    let page = '';
    try { page = new URL(route.request().frame().url()).pathname; } catch { /* no frame (e.g. a worker): shared data */ }
    await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(envelopeFor(restData(route.request().method(), p, page))) });
  });
  // One handler for /update/* (Playwright applies the LAST matching route, so keep it single).
  await context.route('**/update/**', (route) => {
    // /update/status and /update/check both answer with the supervisor status (check returns it fresh).
    const u = route.request().url();
    const body = u.includes('/update/status') ? fx.updateStatus
      : u.includes('/update/check') ? { ...fx.updateStatus, checkedAt: Date.now() } : { ok: true };
    return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(body) });
  });
  return context;
}

// --- v2 device-detail fixtures ---
import { buildDeviceDetailFixtures, deviceDetailRest } from './fixtures.mjs';
const deviceDetailFx = buildDeviceDetailFixtures(fx);
// --- end v2 device-detail fixtures ---
// --- v2 apps fixtures ---
import { buildAppsFixtures, appsRest } from './fixtures.mjs';
const appsFx = buildAppsFixtures(fx);
// The server's base.url rides on every configuration row (getServerBaseUrl): hosted-file detection needs it.
for (const c of fx.configList) c.baseUrl ??= appsFx.baseUrl;
// --- end v2 apps fixtures ---
