// Capture MDMesh docs screenshots from the REAL built SPA, answering its REST calls with sample
// fixtures (Playwright request interception), in dark mode, then round the corners (sharp).
//
//   node capture.mjs            # build is expected to exist at ../../web/dist
//
// Output: ../../docs/screenshots/{overview,devices,device-detail,apps,rollout}.png
import { chromium } from 'playwright';
import http from 'node:http';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { buildFixtures } from './fixtures.mjs';
import { roundCorners } from './round.mjs';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const DIST = path.resolve(__dirname, '../../web/dist');
const OUT = path.resolve(__dirname, '../../docs/screenshots');
const fx = buildFixtures();

// ---- tiny static server for the SPA (with history fallback to index.html) ----
const MIME = {
  '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css', '.json': 'application/json',
  '.png': 'image/png', '.svg': 'image/svg+xml', '.ico': 'image/x-icon', '.woff2': 'font/woff2',
  '.woff': 'font/woff', '.map': 'application/json', '.webmanifest': 'application/manifest+json',
};
function serve() {
  return http.createServer((req, res) => {
    const urlPath = decodeURIComponent(req.url.split('?')[0]);
    let file = path.join(DIST, urlPath);
    if (!fs.existsSync(file) || fs.statSync(file).isDirectory()) {
      const ext = path.extname(urlPath);
      file = ext && ext !== '.html' ? file : path.join(DIST, 'index.html'); // SPA fallback
    }
    if (!fs.existsSync(file)) { res.writeHead(404).end('not found'); return; }
    res.writeHead(200, { 'content-type': MIME[path.extname(file)] || 'application/octet-stream' });
    fs.createReadStream(file).pipe(res);
  });
}

// ---- map an intercepted request to its fixture (envelope-wrapped for /rest) ----
const ok = (data) => ({ status: 'OK', message: null, data });
const deviceListView = { configurations: fx.configurations, devices: { items: fx.devices, totalItemsCount: fx.devices.length } };

function restData(method, p) {
  if (p === '/public/auth/options') return fx.authOptions;
  if (p === '/public/auth/login') return fx.user;
  if (p.endsWith('/devices/search')) return deviceListView;
  if (p.endsWith('/configurations/search')) return fx.configList;
  if (p.endsWith('/applications/search') || /\/applications\/search\//.test(p)) return fx.applications;
  if (/\/devices\/[^/]+\/state$/.test(p)) return fx.state;
  if (/\/devices\/[^/]+\/telemetry$/.test(p)) return fx.telemetry;
  if (/\/devices\/[^/]+\/events/.test(p)) return fx.events;
  if (/\/devices\/[^/]+\/locations/.test(p)) return fx.locations;
  if (p.endsWith('/rollout/active')) return fx.activeRollout;
  if (/\/applications\/\d+\/versions/.test(p)) return [];
  if (method === 'POST') return {};           // bulk ops etc.
  return [];                                   // unknown GET → empty list (never errors the UI)
}

async function main() {
  if (!fs.existsSync(path.join(DIST, 'index.html'))) {
    console.error('web/dist not found — run `npm run build` in web/ first.');
    process.exit(1);
  }
  fs.mkdirSync(OUT, { recursive: true });
  const server = serve();
  await new Promise((r) => server.listen(0, r));
  const base = `http://127.0.0.1:${server.address().port}`;

  const browser = await chromium.launch();
  const context = await browser.newContext({ viewport: { width: 1440, height: 900 }, deviceScaleFactor: 2 });

  // Seed auth + dark theme before any app script runs.
  await context.addInitScript((u) => {
    localStorage.setItem('hmdm.admin.user', JSON.stringify(u));
    localStorage.setItem('mdmesh-theme', 'dark');
    localStorage.setItem('mdmesh-density', 'comfortable');
  }, fx.user);

  // Answer the SPA's API calls from fixtures.
  await context.route('**/rest/**', async (route) => {
    const url = new URL(route.request().url());
    const p = url.pathname.replace(/^\/rest/, '');
    await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(ok(restData(route.request().method(), p))) });
  });
  // One handler for /update/* (Playwright applies the LAST matching route, so keep it single).
  await context.route('**/update/**', (route) => {
    const body = route.request().url().includes('/update/status') ? fx.updateStatus : { ok: true };
    return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(body) });
  });

  const page = await context.newPage();
  const shots = [
    { name: 'overview', route: '/dashboard' },
    { name: 'devices', route: '/devices' },
    { name: 'device-detail', route: '/devices/101' },
    { name: 'apps', route: '/apps' },
    // The staged-rollout panel lives down the Settings page — capture it as a focused card.
    { name: 'rollout', route: '/settings', element: 'section.panel:has(h2:has-text("Agent rollout"))' },
  ];

  for (const s of shots) {
    await page.goto(base + s.route, { waitUntil: 'domcontentloaded' });
    await page.waitForSelector('.wordmark', { timeout: 10000 }).catch(() => {});
    await page.waitForTimeout(1400); // let intercepted data render + transitions settle
    let raw;
    if (s.element) {
      const el = page.locator(s.element).first();
      await el.scrollIntoViewIfNeeded().catch(() => {});
      await page.waitForTimeout(300);
      raw = await el.screenshot();
    } else {
      raw = await page.screenshot({ fullPage: false });
    }
    const final = await roundCorners(raw, { radius: 28, pad: 48, blur: 34 });
    fs.writeFileSync(path.join(OUT, `${s.name}.png`), final);
    console.log('captured', s.name, '→', s.route);
  }

  await browser.close();
  server.close();
  console.log('done →', OUT);
}

main().catch((e) => { console.error(e); process.exit(1); });
