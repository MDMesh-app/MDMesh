// Capture MDMesh docs screenshots from the REAL built SPA, answering its REST calls with sample
// fixtures (Playwright request interception), in the light theme (the design's; `--theme dark` for dark),
// then round the corners (sharp).
//
//   node capture.mjs            # build is expected to exist at ../../web/dist
//   node capture.mjs --check    # runtime smoke for dependency bumps instead; writes nothing
//
// --check visits, against the same fixtures:
//   * signed in: / /devices /devices/WH-SCAN-07 /configurations /configurations/1 /apps /enroll /settings — each
//     must render the app shell (.v2-shell .v2-sidebar .v2-wordmark) and end on the requested path; /devices/101
//     (an old id link) must move to the device's number, /devices/WH-SCAN-07; /no-such-page (catch-all) and the old
//     bookmarks /dashboard, /configs and /v2/devices must land on /, /configurations and /devices;
//   * signed out: /login — the sign-in form must render and the page must stay on /login;
//   * flagged first-login session (passwordReset + token in storage, as an older console stored it; the console
//     itself keeps the token in memory, which v2-parity/auth.mjs covers): /set-password — the set-password form
//     must render and the page must stay on /set-password.
// A route is FAIL if its selector never appears, its final path differs, or it raised any uncaught page
// error or console.error. Prints `ok|FAIL <route> -> <final path>` per route, then `check: PASS` (exit 0)
// or the problems on stderr (exit 1).
//
// Output: ../../docs/screenshots/{overview,devices,device-detail,apps,rollout}.png
import { chromium } from 'playwright';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { roundCorners } from './round.mjs';
import { DIST, fx, serve, newContext } from './harness.mjs';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const OUT = path.resolve(__dirname, '../../docs/screenshots');
const CHECK = process.argv.includes('--check');
const themeArg = process.argv.indexOf('--theme');
const THEME = themeArg > 0 ? process.argv[themeArg + 1] : 'light';
if (THEME !== 'light' && THEME !== 'dark') {
  console.error('--theme must be light or dark');
  process.exit(2);
}

// Static server, fixtures and the mocked browser context live in harness.mjs (shared with compare-v2.mjs).

// ---- --check: runtime smoke over every route (see header) ----
const SHELL = '.v2-shell .v2-sidebar .v2-wordmark';
const SIGNED_IN = ['/', '/devices', '/devices/WH-SCAN-07', '/configurations', '/configurations/1', '/apps', '/enroll', '/settings'];
const CHECK_GROUPS = [
  {
    user: fx.user,
    routes: [
      ...SIGNED_IN.map((route) => ({ route, expect: route, selector: SHELL })),
      // An old link by database id (no device has the number 101): the device page finds it by id and moves to
      // the device's number.
      { route: '/devices/101', expect: '/devices/WH-SCAN-07', selector: SHELL },
      { route: '/no-such-page', expect: '/', selector: SHELL },
      // Old bookmarks: the v0.3 console's paths and the redesign preview's /v2/*.
      { route: '/dashboard', expect: '/', selector: SHELL },
      { route: '/configs', expect: '/configurations', selector: SHELL },
      { route: '/v2/devices', expect: '/devices', selector: SHELL },
    ],
  },
  {
    user: null,
    routes: [{ route: '/login', expect: '/login', selector: '.v2-auth__card input[autocomplete="username"]' }],
  },
  {
    user: { ...fx.user, passwordReset: true, passwordResetToken: 'check-token' },
    routes: [{ route: '/set-password', expect: '/set-password', selector: '.v2-auth__card input[autocomplete="new-password"]' }],
  },
];

async function runCheck(browser, base) {
  const problems = [];
  for (const group of CHECK_GROUPS) {
    const context = await newContext(browser, group.user, { theme: THEME });
    const page = await context.newPage();
    page.on('pageerror', (e) => problems.push(`pageerror on ${page.url()}: ${e.message}`));
    page.on('console', (m) => { if (m.type() === 'error') problems.push(`console.error on ${page.url()}: ${m.text()}`); });
    for (const { route, expect, selector } of group.routes) {
      const before = problems.length;
      await page.goto(base + route, { waitUntil: 'domcontentloaded' });
      const rendered = await page.waitForSelector(selector, { timeout: 10000 }).then(() => true, () => false);
      await page.waitForLoadState('networkidle', { timeout: 10000 }).catch(() => {});
      await page.waitForTimeout(800);
      const final = new URL(page.url()).pathname;
      if (!rendered) problems.push(`${route}: ${selector} never rendered`);
      if (final !== expect) problems.push(`${route}: ended on ${final}, expected ${expect}`);
      console.log(`${problems.length === before ? 'ok  ' : 'FAIL'} ${route} -> ${final}`);
    }
    await context.close();
  }
  if (problems.length) { console.error(problems.join('\n')); return false; }
  console.log('check: PASS');
  return true;
}

async function main() {
  if (!fs.existsSync(path.join(DIST, 'index.html'))) {
    console.error('web/dist not found — run `npm run build` in web/ first.');
    process.exit(1);
  }
  if (!CHECK) fs.mkdirSync(OUT, { recursive: true });
  const server = serve();
  await new Promise((r) => server.listen(0, r));
  const base = `http://127.0.0.1:${server.address().port}`;

  const browser = await chromium.launch();

  if (CHECK) {
    const passed = await runCheck(browser, base);
    await browser.close();
    server.close();
    process.exit(passed ? 0 : 1);
  }

  const context = await newContext(browser, fx.user, { theme: THEME });
  const page = await context.newPage();
  const shots = [
    { name: 'overview', route: '/' },
    { name: 'devices', route: '/devices' },
    { name: 'device-detail', route: '/devices/WH-SCAN-07' },
    { name: 'apps', route: '/apps' },
    // The staged-rollout card lives down the Settings page — capture it as a focused card.
    { name: 'rollout', route: '/settings', element: '#agent-rollout' },
  ];

  for (const s of shots) {
    await page.goto(base + s.route, { waitUntil: 'domcontentloaded' });
    await page.waitForSelector(SHELL, { timeout: 10000 }).catch(() => {});
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
    // The frame's hairline: light on dark shots, a soft dark line on light ones (white on white would vanish).
    const border = THEME === 'light' ? 'rgba(21,24,29,0.10)' : undefined;
    const final = await roundCorners(raw, { radius: 28, pad: 48, blur: 34, border });
    fs.writeFileSync(path.join(OUT, `${s.name}.png`), final);
    console.log('captured', s.name, '→', s.route);
  }

  await browser.close();
  server.close();
  console.log('done →', OUT);
}

main().catch((e) => { console.error(e); process.exit(1); });
