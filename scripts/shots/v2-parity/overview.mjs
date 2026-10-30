// Parity walk-through of the Overview at / against the shots fixtures.
//   node scripts/shots/v2-parity/overview.mjs [--dist <dir>]
import { chromium } from 'playwright';
import { fx, newContext, startServer, restData } from '../harness.mjs';
import { dist } from './lib.mjs';

const { server, base } = await startServer(dist);
const browser = await chromium.launch();
let failures = 0;
const check = (name, cond, extra = '') => { console.log(`${cond ? 'PASS' : 'FAIL'} ${name} ${extra}`); if (!cond) failures++; };
const ok = (data) => JSON.stringify({ status: 'OK', message: null, data });

async function page(opts = {}, setup) {
  const ctx = await newContext(browser, fx.user, { theme: 'light', deviceScaleFactor: 1, blockGoogleFonts: true, viewport: opts.viewport ?? { width: 1440, height: 1300 } });
  if (setup) await setup(ctx);
  const p = await ctx.newPage();
  const errors = [];
  p.on('pageerror', (e) => errors.push(String(e)));
  p.on('console', (m) => m.type() === 'error' && !/fonts\.g/.test(m.location()?.url ?? '') && errors.push(m.text()));
  await p.goto(base + '/');
  await p.waitForTimeout(1200);
  return { ctx, p, errors };
}

// 1. Main render
{
  const { ctx, p, errors } = await page({}, async (ctx) => {
    // Make KIOSK-FRONT out of sync so a "Sync now" row exists; record sync posts.
    await ctx.route('**/rest/private/agent/v1/devices/KIOSK-FRONT/configStatus', (r) => r.fulfill({ contentType: 'application/json', body: ok({ configurationId: 2, currentRevision: 'b', appliedRevision: 'a', inSync: false, supported: true, lastCommand: { id: 5, status: 'done' } }) }));
  });
  const text = await p.locator('#v2-main').innerText();
  check('title', (await p.title()).startsWith('Overview'));
  check('subtitle', /3 devices need attention, and 3 are offline\./.test(text), text.split('\n')[1]);
  check('updated', /Updated \d+s ago|Updated just now/.test(text));
  check('fleet total', /FLEET\s*8\s*devices enrolled/i.test(text));
  check('legend', /Online\s*2\s*25%/.test(text) && /Need attention\s*3\s*38%/.test(text) && /Offline\s*3\s*38%/.test(text));
  check('legend links', (await p.locator('a.v2-fleet__item').evaluateAll((as) => as.map((a) => a.getAttribute('href')))).join() === '/devices?status=online,/devices?status=attention,/devices?status=offline');
  check('sync stat', /Config in sync\s*4\/8\s*2 out of sync\s*1 agent too old/.test(text));
  check('rollout stat', /Agent 0\.1\.15 rollout\s*2\/4\s*Canary devices first\s*1 installing/.test(text));
  const rows = p.locator('table.v2-attn tr');
  check('attention rows', (await rows.count()) === 6, String(await rows.count()));
  check('showing meta', /Showing 6 of 6/.test(text));
  check('see all', (await p.getByRole('link', { name: 'See all' }).getAttribute('href')) === '/devices');
  check('failed reason first', (await rows.first().innerText()).includes('Configuration failed to apply'));
  check('update agent href', (await p.getByRole('link', { name: /Update agent: Lobby check-in/ }).getAttribute('href')) === '/settings#agent-rollout');
  check('offline rows', (await p.locator('td.v2-attn__reason', { hasText: 'Offline' }).count()) === 3);
  check('device link', (await p.getByRole('link', { name: /Café menu board/ }).first().getAttribute('href')) === '/devices/KIOSK-CAFE');
  check('by config', /Retail Kiosk\s*3\s*1 in sync · 1 out of sync · 1 agent too old/.test(text) && /Default\s*3\s*2 in sync · 1 not reported/.test(text));
  check('config link', (await p.getByRole('link', { name: 'Retail Kiosk' }).getAttribute('href')) === '/configurations/2');
  check('manage', (await p.getByRole('link', { name: 'Manage' }).getAttribute('href')) === '/configurations');
  check('server', /Running 0\.1\.14/.test(text) && /0\.1\.15 is available/.test(text) && /Signature verified · stable channel/.test(text));
  check('review update', (await p.locator('#v2-main').getByRole('link', { name: 'Review update' }).getAttribute('href')) === '/settings#updates');
  check('enroll', (await p.getByRole('link', { name: 'Enroll device' }).getAttribute('href')) === '/enroll');

  // Activity filter
  const feed = p.locator('ol.v2-activity li');
  check('activity 8', (await feed.count()) === 8);
  await p.getByRole('radio', { name: 'Commands' }).click();
  const cmdTexts = await feed.allInnerTexts();
  check('commands filter', cmdTexts.length > 0 && cmdTexts.every((t) => t.includes('ran a command')), String(cmdTexts.length));
  await p.keyboard.press('ArrowRight');
  check('arrow key selects Device events', (await p.getByRole('radio', { name: 'Device events' }).getAttribute('aria-checked')) === 'true');
  const evTexts = await feed.allInnerTexts();
  check('events filter', evTexts.length === 8 && evTexts.every((t) => !t.includes('ran a command')));
  await p.getByRole('radio', { name: 'All' }).click();
  check('activity link', (await feed.first().getByRole('link').getAttribute('href'))?.startsWith('/devices/'));

  // Sync now → POST /sync + toast
  const req = p.waitForRequest((r) => r.url().includes('/devices/KIOSK-FRONT/sync') && r.method() === 'POST');
  await p.getByRole('button', { name: /Sync now: Front-desk kiosk/ }).click();
  await req;
  await p.waitForTimeout(300);
  check('sync toast', /Sync requested/.test(await p.locator('body').innerText()));

  // Sync failure → toast
  await ctx.route('**/devices/KIOSK-FRONT/sync', (r) => r.fulfill({ status: 500, contentType: 'application/json', body: '{}' }));
  await p.getByRole('button', { name: /Sync now: Front-desk kiosk/ }).click();
  await p.waitForTimeout(400);
  check('sync fail toast', /Sync failed/.test(await p.locator('body').innerText()));

  // Open device navigates
  await p.getByRole('link', { name: 'Open device: Café menu board' }).click();
  await p.waitForTimeout(300);
  check('open device navigates', p.url().endsWith('/devices/KIOSK-CAFE'));

  // Keyboard reachability: tab through, focus visible on See all
  await p.goto(base + '/'); await p.waitForTimeout(1000);
  let reached = false;
  for (let i = 0; i < 60 && !reached; i++) { await p.keyboard.press('Tab'); reached = await p.evaluate(() => document.activeElement?.textContent === 'See all'); }
  check('keyboard reaches See all', reached);
  check('no console errors', errors.filter((e) => !/status of 500/.test(e)).length === 0, errors.join(' | '));
  await ctx.close();
}

// 2. Load error + retry
{
  let fail = true;
  const { ctx, p } = await page({}, async (ctx) => {
    await ctx.route('**/rest/private/devices/search', (r) => fail
      ? r.fulfill({ status: 500, contentType: 'application/json', body: '{}' })
      : r.fulfill({ contentType: 'application/json', body: ok({ configurations: fx.configurations, devices: { items: fx.devices, totalItemsCount: fx.devices.length } }) }));
  });
  check('error banner', /Failed to load devices\./.test(await p.locator('[role=alert]').innerText()));
  fail = false;
  await p.getByRole('button', { name: 'Retry' }).click();
  await p.waitForTimeout(800);
  check('retry recovers', (await p.locator('[role=alert]').count()) === 0 && /FLEET\s*8/i.test(await p.locator('#v2-main').innerText()));
  await ctx.close();
}

// 3. Empty fleet, no supervisor
{
  const { ctx, p, errors } = await page({}, async (ctx) => {
    await ctx.route('**/rest/private/devices/search', (r) => r.fulfill({ contentType: 'application/json', body: ok({ configurations: {}, devices: { items: [], totalItemsCount: 0 } }) }));
    await ctx.route('**/rest/private/agent/v1/rollout/active', (r) => r.fulfill({ contentType: 'application/json', body: ok(null) }));
    await ctx.route('**/update/**', (r) => r.fulfill({ status: 502, body: '' }));
  });
  const t = await p.locator('#v2-main').innerText();
  check('empty subtitle', /No devices enrolled yet/.test(t));
  check('empty states', (t.match(/No devices yet/g) ?? []).length === 2 && /No recent events/.test(t));
  check('server card hidden', !/Running/.test(t));
  await ctx.close();
}

// 4. Healthy fleet: all online + in sync
{
  const now = Date.now();
  const devs = fx.devices.map((d) => ({ ...d, lastUpdate: now - 60_000 }));
  const { ctx, p } = await page({}, async (ctx) => {
    await ctx.route('**/rest/private/devices/search', (r) => r.fulfill({ contentType: 'application/json', body: ok({ configurations: fx.configurations, devices: { items: devs, totalItemsCount: devs.length } }) }));
    await ctx.route('**/configurations/syncSummary', (r) => r.fulfill({ contentType: 'application/json', body: ok([{ configurationId: 1, total: 3, inSync: 3, outOfSync: 0, unsupported: 0, neverSeen: 0 }, { configurationId: 2, total: 3, inSync: 3, outOfSync: 0, unsupported: 0, neverSeen: 0 }, { configurationId: 3, total: 2, inSync: 2, outOfSync: 0, unsupported: 0, neverSeen: 0 }]) }));
  });
  const t = await p.locator('#v2-main').innerText();
  check('healthy subtitle', /Every device is online and in sync\./.test(t));
  check('all clear', /All clear/.test(t) && /All in sync/.test(t));
  await ctx.close();
}

// 5. Mobile: no horizontal page scroll
{
  const { ctx, p } = await page({ viewport: { width: 390, height: 844 } });
  const sw = await p.evaluate(() => document.documentElement.scrollWidth);
  check('390 no horizontal scroll', sw <= 390, String(sw));
  await ctx.close();
}

// 6. Update status: the supervisor's 'not polled yet' (just restarted) is not a failed check
for (const [name, error, failedShown] of [['not polled yet', 'not polled yet', false], ['real error', 'GitHub API rate limited', true]]) {
  const status = { ...fx.updateStatus, latest: '0.1.14', updateAvailable: false, error, checkedAt: null };
  const { ctx, p } = await page({}, async (ctx) => {
    await ctx.route('**/update/**', (r) => r.fulfill({ contentType: 'application/json', body: JSON.stringify(status) }));
  });
  const t = await p.locator('.v2-ov-side').innerText();
  check(`server card, ${name}`, /The last update check failed\./.test(t) === failedShown && /Up to date/.test(t) === !failedShown, t.replace(/\n/g, ' | '));
  await ctx.close();
}

// 7. Refresh load: each minute only the list, summary and rollout (plus probes for new suspects);
//    the activity events and every probe again only on the 5-minute full refresh.
{
  const counts = { list: 0, summary: 0, events: 0, probes: 0, update: 0 };
  const ctx = await newContext(browser, fx.user, { theme: 'light', deviceScaleFactor: 1, blockGoogleFonts: true, viewport: { width: 1440, height: 1300 } });
  await ctx.clock.install();
  ctx.on('request', (r) => {
    const u = r.url();
    if (u.includes('/devices/search')) counts.list++;
    else if (u.includes('/configurations/syncSummary')) counts.summary++;
    else if (/\/devices\/[^/]+\/events/.test(u)) counts.events++;
    else if (/\/configStatus$/.test(u)) counts.probes++;
    else if (u.includes('/update/status')) counts.update++;
  });
  const p = await ctx.newPage();
  await p.goto(base + '/');
  await p.waitForTimeout(1500);
  const first = { ...counts };
  check('first load fetches events + probes', first.events > 0 && first.probes > 0, JSON.stringify(first));
  await p.clock.runFor(61_000);
  await p.waitForTimeout(800);
  const light = Object.fromEntries(Object.keys(counts).map((k) => [k, counts[k] - first[k]]));
  check('60 s refresh: list + summary only', light.list >= 1 && light.summary >= 1 && light.events === 0 && light.probes === 0, JSON.stringify(light));
  const before = { ...counts };
  await p.clock.runFor(5 * 60_000);
  await p.waitForTimeout(800);
  const deep = Object.fromEntries(Object.keys(counts).map((k) => [k, counts[k] - before[k]]));
  check('5 min: events and probes again', deep.events > 0 && deep.probes > 0, JSON.stringify(deep));
  await ctx.close();
}

// Kiosk events (agent 0.3.6/0.3.7): labelled, with the padlock / warning icon.
{
  const { ctx, p, errors } = await page({}, async (ctx) => {
    const now = Date.now();
    // One device reports a crash loop (the newest event), every other one a PIN lockout.
    await ctx.route('**/rest/private/agent/v1/devices/*/events**', (r) => r.fulfill({ contentType: 'application/json', body: ok(
      r.request().url().includes('/devices/KIOSK-FRONT/')
        ? [{ id: 1, type: 'kioskCrashLoop', ts: now - 30_000, detail: null }]
        : [{ id: 2, type: 'kioskPinLockout', ts: now - 60_000, detail: null }],
    ) }));
  });
  const feed = await p.locator('ol.v2-activity').innerText();
  check('kiosk events: PIN lockout', feed.includes('locked the exit PIN'));
  check('kiosk events: crash loop', feed.includes('reported the kiosk app keeps closing'));
  check('kiosk events: no raw type', !/kioskPinLockout|kioskCrashLoop/.test(feed));
  check('kiosk events: no page errors', errors.length === 0, errors.join(' | '));
  await ctx.close();
}

await browser.close();
server.close();
console.log(failures ? `${failures} FAILED` : 'ALL PASS');
process.exit(failures ? 1 : 0);
