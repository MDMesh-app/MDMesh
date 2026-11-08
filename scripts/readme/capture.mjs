// Step 1: capture the console crops the README images are built from, at 2x, in light and dark, from the REAL
// built SPA (web/dist) answered by the screenshot harness's fixtures (scripts/shots/harness.mjs).
//
// Output (.cache/shots/): screen-overview-<theme>.png (1440x900 viewport) and one PNG per card, clipped to its
// DOM box: attention, sync, enroll, remote, rollout, cfg (Security card after three real edits), cfgbar (the
// sticky "3 unsaved changes · Save and apply" bar). Card sizes in CSS px go to .cache/shots/cards.json.
// The sample versions are overridden for the README (versions.mjs); a page that still shows an old one fails.
import fs from 'node:fs';
import path from 'node:path';
import { fx, newContext, startServer, DIST } from '../shots/harness.mjs';
import { SHOTS, THEMES, launch, closeBrowser } from './common.mjs';
import { applyReadmeVersions, STALE } from './versions.mjs';

applyReadmeVersions(fx); // README-only sample versions (see versions.mjs)

if (!fs.existsSync(path.join(DIST, 'index.html'))) {
  console.error('web/dist not found: run `npm run build` in web/ first (or ./generate.sh --build).');
  process.exit(1);
}

const cards = {};

async function open(browser, theme, route, height) {
  const ctx = await newContext(browser, fx.user, { theme, viewport: { width: 1440, height }, deviceScaleFactor: 2, blockGoogleFonts: true });
  const page = await ctx.newPage();
  const errors = [];
  page.on('pageerror', (e) => errors.push(e.message));
  await page.goto(base + route, { waitUntil: 'domcontentloaded' });
  await page.waitForSelector('.v2-shell', { timeout: 15000 });
  await page.waitForLoadState('networkidle', { timeout: 10000 }).catch(() => {});
  await page.evaluate(() => document.fonts.ready);
  await page.waitForTimeout(1000);
  if (errors.length) throw new Error(`${route}: ${errors.join('; ')}`);
  const stale = (await page.evaluate(() => document.body.innerText)).match(STALE);
  if (stale) throw new Error(`${route}: shows the fixtures' own version ${stale[0]} (extend versions.mjs)`);
  return { ctx, page };
}

/** Page-coordinate box of the card (rounded, bordered ancestor) that holds the heading with exactly `title`. */
function cardBox(page, title) {
  return page.evaluate((title) => {
    const heading = [...document.querySelectorAll('h1,h2,h3,h4')].find((e) => e.textContent.trim() === title);
    let el = heading;
    while (el && el !== document.body) {
      const cs = getComputedStyle(el);
      const r = el.getBoundingClientRect();
      if (parseFloat(cs.borderTopLeftRadius) >= 8 && r.width > 300 && cs.borderTopWidth !== '0px') {
        return { x: r.x + scrollX, y: r.y + scrollY, width: r.width, height: r.height };
      }
      el = el.parentElement;
    }
    return null;
  }, title);
}

async function clip(page, name, theme, box) {
  if (!box) throw new Error(`${name}: card not found`);
  await page.screenshot({ path: path.join(SHOTS, `${name}-${theme}.png`), clip: box });
  cards[name] = { w: box.width, h: box.height };
}

/** Click the segmented option `option` in the setting row titled `title` (a real UI edit). */
function pick(page, title, option) {
  return page.evaluate(([title, option]) => {
    const label = [...document.querySelectorAll('body *')].find((e) => e.children.length === 0 && e.textContent.trim() === title);
    let row = label;
    for (let i = 0; i < 8 && row; i++) {
      row = row.parentElement;
      const b = [...row.querySelectorAll('button,[role=radio],label')].find((x) => x.textContent.trim() === option);
      if (b) { b.click(); return true; }
    }
    return false;
  }, [title, option]);
}

const { server, base } = await startServer();
const browser = await launch();
try {
  for (const theme of THEMES) {
    {
      const { ctx, page } = await open(browser, theme, '/', 900);
      await page.screenshot({ path: path.join(SHOTS, `screen-overview-${theme}.png`) });
      await ctx.close();
    }
    {
      const { ctx, page } = await open(browser, theme, '/', 1320);
      await clip(page, 'attention', theme, await cardBox(page, 'Needs attention'));
      await clip(page, 'sync', theme, await cardBox(page, 'By configuration'));
      await ctx.close();
    }
    {
      const { ctx, page } = await open(browser, theme, '/devices/WH-SCAN-07', 1300);
      await clip(page, 'remote', theme, await cardBox(page, 'Remote actions'));
      await ctx.close();
    }
    {
      // The enroll card down to its QR buttons and steps (leaving out the debug-build notice and the server URLs).
      const { ctx, page } = await open(browser, theme, '/enroll', 1120);
      const card = await cardBox(page, 'Enroll the device');
      const bottom = await page.evaluate(() => {
        const btn = [...document.querySelectorAll('button')].find((b) => b.textContent.trim() === 'Full screen');
        const steps = [...document.querySelectorAll('ol,ul')].find((l) => /Wait a few minutes/.test(l.textContent));
        return Math.max(...[btn, steps].filter(Boolean).map((e) => e.getBoundingClientRect().bottom + scrollY));
      });
      await clip(page, 'enroll', theme, { ...card, height: bottom - card.y + 12 });
      await ctx.close();
    }
    {
      const { ctx, page } = await open(browser, theme, '/settings', 1800);
      const box = await page.evaluate(() => {
        const r = document.querySelector('#agent-rollout').getBoundingClientRect();
        return { x: r.x + scrollX, y: r.y + scrollY, width: r.width, height: r.height };
      });
      await clip(page, 'rollout', theme, box);
      await ctx.close();
    }
    {
      // Default's Security card after three real edits, and the sticky unsaved-changes bar they bring up.
      const { ctx, page } = await open(browser, theme, '/configurations/1', 1100);
      for (const [title, option] of [['Screenshots', 'Block'], ['Factory reset', 'Block'], ['Safe Mode', 'Block']]) {
        if (!(await pick(page, title, option))) throw new Error(`config editor: no ${option} for ${title}`);
        await page.waitForTimeout(120);
      }
      await page.waitForTimeout(400);
      const sec = await cardBox(page, 'Security and restrictions');
      await page.evaluate((y) => window.scrollTo(0, y), sec.y + sec.height - 980);
      await page.waitForTimeout(500);
      const geo = await page.evaluate(() => {
        const box = (el) => { const r = el.getBoundingClientRect(); return { x: r.x, y: r.y, width: r.width, height: r.height }; };
        const up = (el, ok) => { while (el && !ok(el)) el = el.parentElement; return el; };
        const rounded = (min) => (el) => parseFloat(getComputedStyle(el).borderTopLeftRadius) >= 8 && el.getBoundingClientRect().width > min;
        const h = [...document.querySelectorAll('h2,h3')].find((e) => e.textContent.trim() === 'Security and restrictions');
        const save = [...document.querySelectorAll('button')].find((b) => b.textContent.trim() === 'Save and apply');
        return { card: box(up(h, rounded(300))), bar: box(up(save, rounded(500))), barText: up(save, rounded(500)).innerText };
      });
      if (!/3 unsaved changes/.test(geo.barText)) throw new Error(`config editor: unexpected bar "${geo.barText}"`);
      await clip(page, 'cfg', theme, geo.card);
      await clip(page, 'cfgbar', theme, geo.bar);
      await ctx.close();
    }
    console.log('captured', theme);
  }
} finally {
  await closeBrowser();
  server.close();
}
fs.writeFileSync(path.join(SHOTS, 'cards.json'), JSON.stringify(cards, null, 1));
console.log('cards', JSON.stringify(cards));
