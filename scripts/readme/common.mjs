// Shared plumbing for the README asset generator: paths, fonts, design tokens, and a Playwright page renderer.
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { chromium } from 'playwright';

const here = path.dirname(fileURLToPath(import.meta.url));
export const ROOT = path.resolve(here, '../..');
export const CACHE = path.join(here, '.cache');
export const SHOTS = path.join(CACHE, 'shots'); // 2x console captures (capture.mjs)
export const PARTS = path.join(CACHE, 'parts'); // raster parts for the SVGs (compose.mjs)
export const OUT = path.join(ROOT, 'docs/readme');
export const THEMES = ['light', 'dark'];
for (const d of [CACHE, SHOTS, PARTS, OUT, path.join(CACHE, 'html')]) fs.mkdirSync(d, { recursive: true });

// ---- fonts: IBM Plex from the console's own packages, Roboto / Noto Sans Mono for the agent's Android screen ----
const FS_WEB = path.join(ROOT, 'web/node_modules/@fontsource');
const FS_OWN = path.join(here, 'node_modules/@fontsource');
function face(family, file, weight) {
  if (!fs.existsSync(file)) throw new Error(`font missing: ${file} (run npm install in web/ and scripts/readme/)`);
  return `@font-face{font-family:'${family}';font-weight:${weight};font-style:normal;font-display:block;src:url('file://${file}') format('woff2')}\n`;
}
export function fontCSS() {
  let css = '';
  for (const w of [300, 400, 500, 600, 700]) css += face('IBM Plex Sans', `${FS_WEB}/ibm-plex-sans/files/ibm-plex-sans-latin-${w}-normal.woff2`, w);
  for (const w of [400, 500]) css += face('IBM Plex Mono', `${FS_WEB}/ibm-plex-mono/files/ibm-plex-mono-latin-${w}-normal.woff2`, w);
  for (const w of [400, 500, 700]) css += face('Roboto', `${FS_OWN}/roboto/files/roboto-latin-${w}-normal.woff2`, w);
  css += face('Noto Sans Mono', `${FS_OWN}/noto-sans-mono/files/noto-sans-mono-latin-400-normal.woff2`, 400);
  return css;
}

// ---- console tokens (web/src/tokens.css) plus scene colours ----
export const T = {
  light: {
    app: '#f6f7f9', surface: '#ffffff', border: '#e7e9ee', ink: '#15181d', text2: '#384152', secondary: '#586073',
    muted: '#6b7686', faint: '#9aa3b0', online: '#1aa766', warn: '#d98a2b',
    okInk: '#0e7a47', okSoft: '#e6f6ee', okLine: '#bfe8d2',
    grid: 'rgba(21,24,29,.055)', shadow: '20,30,55', shadowFloat: '0 8px 24px rgba(20,30,55,.12)',
  },
  dark: {
    app: '#0f1115', surface: '#161922', border: '#272c37', ink: '#f2f4f8', text2: '#c4ccd7', secondary: '#9aa3b0',
    muted: '#8b95a3', faint: '#6c7480', online: '#2bb979', warn: '#e0973a',
    okInk: '#4fd194', okSoft: '#13261d', okLine: '#1f4a35',
    grid: 'rgba(242,244,248,.045)', shadow: '0,0,0', shadowFloat: '0 8px 24px rgba(0,0,0,.5)',
  },
};

let browser = null;
export async function launch() {
  browser ??= await chromium.launch({ args: ['--allow-file-access-from-files', '--force-color-profile=srgb', '--font-render-hinting=none'] });
  return browser;
}
export async function closeBrowser() {
  if (browser) await browser.close();
  browser = null;
}

/** Writes `html` to .cache/html/<name>.html and opens it once fonts and images are ready. */
export async function openPage(name, html, { w, h, scale = 1 }) {
  const file = path.join(CACHE, 'html', `${name}.html`);
  fs.writeFileSync(file, html);
  const b = await launch();
  const page = await b.newPage({ viewport: { width: w, height: h }, deviceScaleFactor: scale });
  await page.goto('file://' + file, { waitUntil: 'load' });
  await page.evaluate(() => document.fonts.ready);
  await page.evaluate(() => Promise.all([...document.images].map((i) => i.decode().catch(() => {}))));
  await page.waitForTimeout(300);
  return page;
}

export const shot = (name, theme) => `file://${SHOTS}/${name}-${theme}.png`;
export const readJSON = (file) => JSON.parse(fs.readFileSync(file, 'utf8'));
