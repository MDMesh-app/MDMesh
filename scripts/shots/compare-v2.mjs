// Compare a v2 page against its design board.
//
// Renders a route of the REAL built SPA against the same fixtures as capture.mjs (harness.mjs), screenshots it
// at 1440 × (board height) and at 390 wide, in light and dark, and writes the shots next to the board PNG, plus a
// side-by-side composite and a difference overlay you can read spacing errors from.
//
// USAGE (from the repo root; the first run needs `npm ci` in scripts/shots)
//   node scripts/shots/compare-v2.mjs <page> [options]
//   node scripts/shots/compare-v2.mjs --list                 # pages, routes and boards
//
//   node scripts/shots/compare-v2.mjs overview --build       # rebuild web/dist first (with the gallery), then shoot
//   node scripts/shots/compare-v2.mjs devices-cards --themes light --widths 1440
//   node scripts/shots/compare-v2.mjs device --route /devices/KIOSK-FRONT
//   node scripts/shots/compare-v2.mjs gallery --full          # every component, full page, light + dark
//   node scripts/shots/compare-v2.mjs gallery --route '/_gallery?open=modal' --widths 1440
//   node scripts/shots/compare-v2.mjs settings --url http://localhost:5173   # against `npm run dev` instead of dist
//
// OPTIONS
//   --build            build web/ first into a fresh directory for this run ($TMPDIR/mdmesh-dist-v2-XXXXXX, never
//                      web/dist), with VITE_GALLERY=1 so /_gallery exists; removed afterwards unless
//                      --keep-build (parallel runs never share a build)
//   --keep-build       keep the --build directory and print its path (reuse it with --dist)
//   --url <base>       use a running server (e.g. the Vite dev server) instead of serving a build
//   --dist <dir>       serve another build directory (default: this run's --build directory, else
//                      $TMPDIR/mdmesh-dist-v2 if it exists, else web/dist)
//   --route <path>     override the page's route (keep the query string quoted)
//   --themes a,b       light,dark (default both)
//   --widths a,b       1440,390 (default both). 1440 uses the board height; 390 is always full page
//   --full             full-page screenshot at 1440 too (default: just the board-height viewport, like the board)
//   --scale <n>        device scale factor (default 1, same as the board PNGs)
//   --wait <ms>        extra settle time after load (default 900)
//   --out <dir>        output root (default $TMPDIR/mdmesh-compare; shots go to <out>/<page>/, so give
//                      parallel runs of the same page different roots)
//   --boards <dir>     design board PNGs, named as in PAGES below (env MDMESH_BOARDS; default scripts/shots/boards).
//                      The boards are not in the repo: without them only the v2 shots are written
//
// OUTPUT  <out>/<page>/
//   board.png                     copy of the design board (light only; the boards have no dark mode)
//   v2-1440-light.png, v2-1440-dark.png, v2-390-light.png, v2-390-dark.png
//   side-1440-<theme>.png         board | v2, labelled, for eyeballing
//   diff-1440-light.png           |board − v2| per pixel: black = identical, bright edges = misplaced or resized
//
// Fixture data (scripts/shots/fixtures.mjs) is not the board's demo data, so compare structure, sizes, spacing,
// type and colour, not text. Unknown GETs return [] (pages render empty states); add fixtures for your page there.
// Uncaught page errors and console.error are printed as warnings.
import { chromium } from 'playwright';
import sharp from 'sharp';
import fs from 'node:fs';
import path from 'node:path';
import os from 'node:os';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { DIST, fx, newContext, startServer } from './harness.mjs';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const WEB = path.resolve(__dirname, '../../web');
/** A shared build some workflows leave here (built by hand or by an older --build); used when no --dist is given. */
const SHARED_DIST = path.join(process.env.TMPDIR || os.tmpdir(), 'mdmesh-dist-v2');

// page → route, board file, board viewport (the size each board was drawn at), auth state.
const PAGES = {
  overview: { route: '/', board: '01-overview', w: 1440, h: 1320 },
  devices: { route: '/devices', board: '02-devices-table', w: 1440, h: 1120 },
  'devices-cards': { route: '/devices?view=cards', board: '03-devices-cards', w: 1440, h: 1120 },
  device: { route: '/devices/WH-SCAN-07', board: '05-device-detail-panel', w: 1440, h: 1280 },
  'device-header': { route: '/devices/WH-SCAN-07', board: '04-device-detail', w: 1440, h: 1400 },
  configurations: { route: '/configurations', board: '06-configurations', w: 1440, h: 1060 },
  'config-editor': { route: '/configurations/2', board: '07-config-editor', w: 1440, h: 2400 },
  apps: { route: '/apps', board: '08-apps-library', w: 1440, h: 1060 },
  'apps-custom': { route: '/apps?source=custom', board: '09-apps-custom', w: 1440, h: 1060 },
  'apps-fdroid': { route: '/apps?source=fdroid', board: '10-apps-fdroid', w: 1440, h: 1060 },
  enroll: { route: '/enroll', board: '11-enroll', w: 1440, h: 1120 },
  settings: { route: '/settings', board: '12-settings', w: 1440, h: 1800 },
  login: { route: '/login', board: '13-signin', w: 1440, h: 860, auth: 'none' },
  'set-password': { route: '/set-password', board: '14-set-password', w: 1440, h: 860, auth: 'reset' },
  // The sidebar board is the left column alone (240 × 900): shot as an element of the overview page.
  sidebar: { route: '/', board: '15-sidebar', w: 1440, h: 900, element: '.v2-shell__side' },
  gallery: { route: '/_gallery', board: null, w: 1440, h: 900 },
};

// ---- args ----
const argv = process.argv.slice(2);
const flag = (name) => argv.includes(`--${name}`);
const opt = (name, dflt) => {
  const i = argv.indexOf(`--${name}`);
  return i >= 0 && argv[i + 1] && !argv[i + 1].startsWith('--') ? argv[i + 1] : dflt;
};

if (flag('list') || argv.length === 0 || flag('help')) {
  console.log('pages:');
  for (const [name, p] of Object.entries(PAGES)) {
    console.log(`  ${name.padEnd(15)} ${p.route.padEnd(26)} ${p.board ? `${p.board}.png (${p.w}×${p.h})` : '(no board)'}`);
  }
  console.log('\nsee the header of scripts/shots/compare-v2.mjs for options');
  process.exit(argv.length === 0 && !flag('list') ? 1 : 0);
}

const pageName = argv[0];
const page = PAGES[pageName];
if (!page) {
  console.error(`unknown page "${pageName}". Try --list.`);
  process.exit(1);
}
const route = opt('route', page.route);
const themes = opt('themes', 'light,dark').split(',').map((s) => s.trim()).filter(Boolean);
const widths = opt('widths', '1440,390').split(',').map((s) => Number(s.trim())).filter(Boolean);
const scale = Number(opt('scale', '1'));
const wait = Number(opt('wait', '900'));
const full = flag('full');
const outRoot = opt('out', path.join(process.env.TMPDIR || os.tmpdir(), 'mdmesh-compare'));
const boardsDir = [opt('boards', ''), process.env.MDMESH_BOARDS || '', path.resolve(__dirname, 'boards')].find(
  (d) => d && fs.existsSync(d),
);
const outDir = path.join(outRoot, pageName);
fs.mkdirSync(outDir, { recursive: true });

// ---- optional build (into a directory of this run's own) ----
let builtDir = null;
if (flag('build')) {
  builtDir = fs.mkdtempSync(path.join(process.env.TMPDIR || os.tmpdir(), 'mdmesh-dist-v2-'));
  console.log(`building web/ (VITE_GALLERY=1) into ${builtDir}…`);
  const r = spawnSync('npm', ['run', 'build', '--', '--outDir', builtDir, '--emptyOutDir'], {
    cwd: WEB,
    stdio: 'inherit',
    env: { ...process.env, VITE_GALLERY: '1' },
  });
  if (r.status !== 0) {
    fs.rmSync(builtDir, { recursive: true, force: true });
    process.exit(r.status ?? 1);
  }
}
const dropBuild = () => {
  if (!builtDir) return;
  if (flag('keep-build')) console.log(`build kept: ${builtDir} (pass --dist ${builtDir} to reuse it)`);
  else fs.rmSync(builtDir, { recursive: true, force: true });
};

const dist = path.resolve(
  opt('dist', builtDir ?? (fs.existsSync(path.join(SHARED_DIST, 'index.html')) ? SHARED_DIST : DIST)),
);
let server = null;
let base = opt('url', '');
if (!base) {
  if (!fs.existsSync(path.join(dist, 'index.html'))) {
    console.error(`${dist}/index.html not found: run with --build.`);
    process.exit(1);
  }
  ({ server, base } = await startServer(dist));
}
base = base.replace(/\/$/, '');

const user =
  page.auth === 'none' ? null : page.auth === 'reset' ? { ...fx.user, passwordReset: true, passwordResetToken: 'compare-token' } : fx.user;

// ---- shoot ----
const browser = await chromium.launch();
const shots = {};
const warnings = [];
try {
  for (const theme of themes) {
    for (const width of widths) {
      const desktop = width >= 1000;
      const viewport = { width, height: desktop ? page.h : 844 };
      const context = await newContext(browser, user, { theme, viewport, deviceScaleFactor: scale, blockGoogleFonts: true });
      const tab = await context.newPage();
      tab.on('pageerror', (e) => warnings.push(`[${theme} ${width}] pageerror: ${e.message}`));
      tab.on('console', (m) => {
        // Google Fonts are blocked on purpose (v2 self-hosts its fonts); ignore those failed loads.
        if (/fonts\.(googleapis|gstatic)\.com/.test(m.location()?.url ?? '')) return;
        if (m.type() === 'error') warnings.push(`[${theme} ${width}] console.error: ${m.text()}`);
      });
      await tab.goto(base + route, { waitUntil: 'domcontentloaded' });
      await tab.waitForSelector('#root > *', { timeout: 15000 }).catch(() => warnings.push(`[${theme} ${width}] the app never rendered`));
      await tab.waitForLoadState('networkidle', { timeout: 10000 }).catch(() => {});
      await tab.evaluate(() => document.fonts.ready);
      await tab.waitForTimeout(wait);
      const finalPath = new URL(tab.url()).pathname;
      const wanted = new URL(base + route).pathname;
      if (finalPath !== wanted) {
        warnings.push(
          `[${theme} ${width}] ended on ${finalPath}, expected ${wanted}` +
            (pageName === 'gallery' ? ' (the gallery needs a VITE_GALLERY=1 build: use --build)' : ''),
        );
      }
      const file = path.join(outDir, `v2-${width}-${theme}.png`);
      if (page.element && desktop) {
        await tab.locator(page.element).first().screenshot({ path: file });
      } else {
        await tab.screenshot({ path: file, fullPage: !desktop || full });
      }
      shots[`${width}-${theme}`] = file;
      console.log('shot', path.relative(outRoot, file));
      await context.close();
    }
  }
} finally {
  await browser.close();
  if (server) server.close();
  dropBuild();
}

// ---- board, composites, diff ----
const label = (text, w) =>
  Buffer.from(
    `<svg xmlns="http://www.w3.org/2000/svg" width="${w}" height="32"><rect width="100%" height="100%" fill="#15181d"/>` +
      `<text x="12" y="21" font-family="IBM Plex Sans, Arial, sans-serif" font-size="14" fill="#ffffff">${text}</text></svg>`,
  );

async function sideBySide(left, leftLabel, right, rightLabel, outFile) {
  const a = await sharp(left).metadata();
  const b = await sharp(right).metadata();
  const gap = 24;
  const width = a.width + gap + b.width;
  const height = Math.max(a.height, b.height) + 32;
  await sharp({ create: { width, height, channels: 4, background: '#8a8f99' } })
    .composite([
      { input: label(leftLabel, a.width), left: 0, top: 0 },
      { input: left, left: 0, top: 32 },
      { input: label(rightLabel, b.width), left: a.width + gap, top: 0 },
      { input: right, left: a.width + gap, top: 32 },
    ])
    .png()
    .toFile(outFile);
  console.log('side', path.relative(outRoot, outFile));
}

async function difference(boardFile, shotFile, outFile) {
  const a = await sharp(boardFile).metadata();
  const s = await sharp(shotFile).metadata();
  if (s.width !== a.width) return; // element shots (sidebar) differ in width; the side-by-side covers them
  // Same width: crop or pad the shot to the board height, never scale (scaling would hide spacing errors).
  let img = sharp(shotFile);
  if (s.height > a.height) img = img.extract({ left: 0, top: 0, width: a.width, height: a.height });
  else if (s.height < a.height) img = img.extend({ bottom: a.height - s.height, background: '#ffffff' });
  const shot = await img.png().toBuffer();
  await sharp(boardFile)
    .composite([{ input: shot, blend: 'difference' }])
    .png()
    .toFile(outFile);
  console.log('diff', path.relative(outRoot, outFile));
}

if (page.board && boardsDir) {
  const boardSrc = path.join(boardsDir, `${page.board}.png`);
  if (fs.existsSync(boardSrc)) {
    const boardFile = path.join(outDir, 'board.png');
    fs.copyFileSync(boardSrc, boardFile);
    for (const theme of themes) {
      const shot = shots[`1440-${theme}`];
      if (!shot) continue;
      await sideBySide(boardFile, `board ${page.board}`, shot, `v2 ${route} · ${theme}`, path.join(outDir, `side-1440-${theme}.png`));
      if (theme === 'light' && scale === 1) await difference(boardFile, shot, path.join(outDir, 'diff-1440-light.png'));
    }
  } else {
    console.warn(`board not found: ${boardSrc}`);
  }
} else if (page.board) {
  console.warn('no board directory (pass --boards <dir> or set MDMESH_BOARDS): wrote the v2 shots only');
}

if (warnings.length) {
  console.warn('\nwarnings:\n  ' + warnings.join('\n  '));
}
console.log(`\ndone → ${outDir}`);
