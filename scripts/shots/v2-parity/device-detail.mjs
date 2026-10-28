// Parity walk-through of /devices/:id against the shots fixtures.
//   node scripts/shots/v2-parity/device-detail.mjs [--dist <dir>] [--out <dir>]
import fs from 'node:fs';
import { chromium } from 'playwright';
import { fx, newContext, startServer } from '../harness.mjs';
import { dist, outDir } from './lib.mjs';
const { server, base } = await startServer(dist);
const browser = await chromium.launch();
const errors = [];
/** The error-state test fails requests on purpose: their "Failed to load resource" console lines are expected. */
let allowNetErrors = false;
let fails = 0;
const ok = (c, m) => { console.log(`${c ? 'PASS' : 'FAIL'} ${m}`); if (!c) fails++; };
async function page(opts = {}) {
  const ctx = await newContext(browser, fx.user, { theme: 'light', deviceScaleFactor: 1, blockGoogleFonts: true, ...opts });
  const p = await ctx.newPage();
  p.on('console', (m) => { if (m.type() === 'error' && !/tile\.openstreetmap|fonts\.g/.test(m.text() + (m.location()?.url ?? '')) && !(allowNetErrors && /Failed to load resource/.test(m.text()))) errors.push(m.text()); });
  p.on('pageerror', (e) => errors.push(String(e)));
  p.on('requestfailed', (r) => { if (!/fonts\.g/.test(r.url()) && !allowNetErrors) console.log('requestfailed', r.url().slice(0, 100)); });
  const posts = [];
  p.on('request', (r) => { if (r.method() !== 'GET' && r.url().includes('/rest/')) posts.push({ url: r.url(), body: r.postData() }); });
  p.posts = posts;
  return p;
}
const toast = async (p, text) => { try { await p.getByText(text).first().waitFor({ timeout: 4000 }); return true; } catch { return false; } };

const p = await page();
await p.goto(`${base}/devices/WH-SCAN-07`);
await p.getByRole('heading', { level: 1 }).waitFor();
ok((await p.getByRole('heading', { level: 1 }).textContent()) === 'Warehouse scanner', 'name heading');
ok(await p.getByText('18.2 / 32 GB').isVisible(), 'storage from real telemetry keys');
ok(await p.getByText('10.20.4.51').first().isVisible(), 'local IP from dynamic.ipAddress');
ok(await p.getByText('Configuration didn’t fully apply').waitFor({ timeout: 6000 }).then(() => true, () => false), 'config panel');
ok(await p.locator('.v2-device-cfg__oval', { hasText: 'Permission denied' }).isVisible(), 'failure reason');
// rename
await p.getByRole('button', { name: 'Rename device' }).click();
await p.getByLabel('Device name').fill('Scanner 7');
await p.keyboard.press('Escape');
ok((await p.getByRole('heading', { level: 1 }).textContent()) === 'Warehouse scanner', 'rename Escape reverts');
ok(await p.getByRole('button', { name: 'Rename device' }).evaluate((e) => e === document.activeElement), 'focus back on pencil');
await p.getByRole('button', { name: 'Rename device' }).click();
await p.getByLabel('Device name').fill('Scanner 7');
await p.keyboard.press('Enter');
ok(await toast(p, 'Name saved'), 'rename toast');
ok((await p.getByRole('heading', { level: 1 }).textContent()) === 'Scanner 7', 'renamed heading');
ok(p.posts.some((x) => x.url.endsWith('/description') && x.body === 'Scanner 7'), 'rename POST body');
// sync, lock
await p.getByRole('button', { name: 'Sync now' }).click();
ok(await toast(p, 'Sync requested'), 'sync toast');
await p.getByRole('toolbar', { name: 'Quick actions' }).getByRole('button', { name: 'Lock' }).click();
ok(await toast(p, 'Lock device queued'), 'lock toast');
ok(await toast(p, 'Command 413'), 'command id in toast');
ok(p.posts.some((x) => x.url.includes('/commands') && JSON.parse(x.body || '{}').type === 'device.lock'), 'lock POST');
// alert dialog
await p.getByRole('button', { name: 'Send alert…' }).click();
const dlg = p.getByRole('dialog', { name: 'Send alert' });
await dlg.waitFor();
ok(await dlg.getByRole('button', { name: 'Send' }).isDisabled(), 'alert Send disabled until message');
await dlg.getByLabel('Message').fill('Please return to desk');
await dlg.getByRole('button', { name: 'Send' }).click();
ok(await toast(p, 'Send alert queued'), 'alert toast');
const alertPost = p.posts.find((x) => x.body && x.body.includes('device.alert'));
ok(alertPost && JSON.parse(JSON.parse(alertPost.body).payload).body === 'Please return to desk', 'alert payload');
// factory reset
await p.getByRole('button', { name: 'Factory reset…' }).click();
const wipe = p.getByRole('alertdialog', { name: 'Factory reset (wipe)' });
await wipe.waitFor();
const confirm = wipe.getByRole('button', { name: 'Confirm' });
ok(await confirm.isDisabled(), 'wipe confirm disabled');
await wipe.getByLabel('Type WIPE to confirm').fill('wipe');
ok(await confirm.isDisabled(), 'wipe needs exact WIPE');
await wipe.getByLabel('Type WIPE to confirm').fill('WIPE');
ok(await confirm.isEnabled(), 'wipe confirm enabled on WIPE');
await p.keyboard.press('Escape');
await p.waitForTimeout(200);
ok(!(await wipe.isVisible()), 'wipe dialog closes on Escape');
ok(!p.posts.some((x) => x.body && x.body.includes('device.wipe')), 'no wipe sent');
// passcode
await p.getByRole('button', { name: 'Reset passcode…' }).click();
const pc = p.getByRole('alertdialog', { name: 'Reset passcode' });
await pc.waitFor();
ok((await pc.getByLabel('New passcode (blank to clear)').getAttribute('type')) === 'password', 'passcode field is password');
await pc.getByRole('button', { name: 'Cancel' }).click();
// reboot
await p.getByRole('button', { name: 'Reboot…' }).click();
const rb = p.getByRole('alertdialog', { name: 'Reboot device' });
await rb.getByRole('button', { name: 'Confirm' }).click();
ok(await toast(p, 'Reboot device queued'), 'reboot toast');
// power
await p.getByRole('radio', { name: 'Always-on' }).click();
ok(await toast(p, 'Connectivity: Always-on queued'), 'power toast');
ok((await p.getByRole('radio', { name: 'Always-on' }).getAttribute('aria-checked')) === 'true', 'always-on selected');
ok(await p.getByText('Waiting for the device').isVisible(), 'power pending note');
ok((await p.getByRole('radiogroup', { name: 'Location' }).getByRole('radio', { name: 'Battery-saver' }).getAttribute('aria-checked')) === 'true', 'location from config WIFI');
await p.getByRole('radio', { name: 'Accurate' }).click();
ok(await toast(p, 'Location: Accurate queued'), 'location toast');
// install config apps
await p.getByRole('button', { name: 'Install config apps' }).click();
ok(await toast(p, '2 install commands queued.'), 'config apps toast');
// menu
await p.getByRole('button', { name: 'More actions' }).click();
await p.getByRole('menuitem', { name: 'Ring' }).click();
ok(await toast(p, 'Ring device queued'), 'menu ring');
// kiosk: the configuration (Default) has kiosk mode off, so only the custom kiosk is offered, with a hint
ok(await p.getByText('Default doesn’t use kiosk mode, so there is no configured kiosk to return to.').isVisible(), 'kiosk off: hint says there is no configured kiosk');
ok((await p.getByRole('button', { name: 'Return to configured kiosk…' }).count()) === 0, 'kiosk off: no Return to configured kiosk');
const customCls = await p.getByRole('button', { name: 'Custom kiosk…' }).getAttribute('class');
ok(!/v2-btn--(danger|primary)/.test(customCls), `Custom kiosk is a quiet button, not red (${customCls})`);
await p.getByRole('button', { name: 'Custom kiosk…' }).click();
const kd = p.getByRole('dialog', { name: 'Custom kiosk' });
await kd.waitFor();
ok((await kd.textContent()).includes('Device WH-SCAN-07 · Scanner 7'), 'custom kiosk names the device (as renamed)');
ok(await kd.getByPlaceholder('Empty: no exit on the device (agent 0.3.6+)').isVisible(), 'custom kiosk: empty exit PIN means no exit (0.3.6+)');
ok((await kd.textContent()).includes('A one-time kiosk with apps you pick now') && (await kd.textContent()).includes('Default doesn’t define it.'), 'custom kiosk says it is one-time and not the configuration’s');
await kd.getByRole('button', { name: /Field Ops/ }).click();
await kd.getByRole('radio', { name: 'Pin a single app' }).click();
ok((await kd.getByRole('button', { name: /^Enter custom kiosk \(\d\)$/ }).textContent()) === 'Enter custom kiosk (1)', 'kiosk count');
await kd.getByRole('searchbox', { name: 'Search apps' }).fill('zzz');
ok(await kd.getByText('No apps match.').isVisible(), 'kiosk no match');
await kd.getByRole('searchbox', { name: 'Search apps' }).fill('');
await kd.getByRole('radio', { name: 'Device apps' }).click();
ok(await kd.getByRole('button', { name: 'Scan device apps' }).isVisible(), 'device scan button');
await kd.getByRole('radio', { name: 'Library' }).click();
await kd.getByRole('button', { name: 'Enter custom kiosk (1)' }).click();
ok(await toast(p, 'Custom kiosk queued'), 'kiosk toast');
const kp = p.posts.find((x) => x.body && x.body.includes('kiosk.enter'));
ok(kp && JSON.parse(JSON.parse(kp.body).payload).pinPackage === 'com.acme.fieldops', 'kiosk payload single pin');
// tabs
await p.getByRole('button', { name: 'All activity' }).click();
ok((await p.getByRole('tab', { name: /Activity/ }).getAttribute('aria-selected')) === 'true', 'All activity opens Activity tab');
ok(await p.getByText('Low battery').isVisible(), 'events listed');
ok(await p.getByRole('heading', { name: 'Commands' }).isVisible(), 'full command history');
await p.getByRole('tab', { name: /Activity/ }).focus();
await p.keyboard.press('ArrowLeft');
ok((await p.getByRole('tab', { name: 'Telemetry' }).getAttribute('aria-selected')) === 'true', 'arrow keys move tabs');
ok(await p.getByText('Storage used').isVisible() && await p.getByText('Build').isVisible(), 'telemetry formatted');
ok(await p.getByText('Other').isVisible(), 'unknown keys listed');
await p.getByRole('tab', { name: 'Location' }).click();
await p.locator('.leaflet-container').waitFor({ timeout: 5000 }).catch(() => {});
ok(await p.locator('.leaflet-container').count() === 1, 'map rendered');
ok(await p.getByText(/4 fixes/).isVisible(), 'fix count');
await p.screenshot({ path: outDir + '/tab-location.png' });
await p.getByRole('tab', { name: 'Telemetry' }).click();
await p.screenshot({ path: outDir + '/tab-telemetry.png', fullPage: true });
await p.getByRole('tab', { name: /Activity/ }).click();
await p.screenshot({ path: outDir + '/tab-activity.png', fullPage: true });
await p.getByRole('tab', { name: 'Control' }).click();
await p.getByRole('button', { name: 'Custom kiosk…' }).click();
await p.waitForTimeout(400);
await p.screenshot({ path: outDir + '/kiosk.png' });
await p.keyboard.press('Escape');

// not found
const q = await page();
await q.goto(`${base}/devices/NOPE`);
ok(await q.getByText('Device not found.').isVisible({ timeout: 4000 }).catch(() => false) || await toast(q, 'Device not found.'), 'not found');

// mobile overflow
const m = await page({ viewport: { width: 390, height: 844 } });
await m.goto(`${base}/devices/WH-SCAN-07`);
await m.getByRole('heading', { level: 1 }).waitFor();
for (const t of ['Control', 'Telemetry', 'Activity', 'Location']) {
  await m.getByRole('tab', { name: new RegExp(t) }).click();
  await m.waitForTimeout(300);
  const sw = await m.evaluate(() => document.documentElement.scrollWidth);
  ok(sw <= 390, `no horizontal scroll at 390 (${t}: ${sw})`);
}
// ---- edge cases: id collisions, polling, navigation races, malformed data ----
const env = (data) => JSON.stringify({ status: 'OK', message: null, data });
const json = (route, data) => route.fulfill({ status: 200, contentType: 'application/json', body: env(data) });
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const now = Date.now();
const dev = (id, number, description, extra = {}) => ({
  id, number, description, configurationId: 1, statusCode: 'green', lastUpdate: now - 60_000, androidVersion: '13',
  kioskMode: false, enrollTime: now - 10 * 86_400_000, mdmMode: true, groups: [], ...extra,
});
/** Devices search + exact lookup answered from `list` (the search returns every row, as a substring search could). */
async function fleet(p, list, { lookup = true } = {}) {
  const ctx = p.context();
  await ctx.route('**/rest/private/devices/search', (r) => json(r, { configurations: fx.configurations, devices: { items: list, totalItemsCount: list.length } }));
  await ctx.route('**/rest/private/devices/number/**', (r) => {
    if (!lookup) return r.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ status: 'ERROR', message: 'error.notfound', data: null }) });
    const n = decodeURIComponent(new URL(r.request().url()).pathname.split('/').pop());
    return json(r, list.find((d) => d.number === n) ?? null);
  });
}
const heading = async (p) => { await p.getByRole('heading', { level: 1 }).waitFor({ timeout: 12000 }); return p.getByRole('heading', { level: 1 }).textContent(); };
const sub = (p) => p.locator('.v2-device-head__sub').textContent();

// Number / id collision: "12" is a device number, 12 is another device's database id, and "120" / "WH-12"
// contain "12". Rows that only contain it come first, as a substring search could return them.
{
  const twelve = dev(501, '12', 'Numbered twelve');
  const idTwelve = dev(12, 'WH-12', 'Id twelve');
  const oneTwenty = dev(502, '120', 'Numbered 120');
  const list = [idTwelve, oneTwenty, twelve];
  const c = await page();
  await fleet(c, list);
  await c.goto(`${base}/devices/12`);
  ok((await heading(c)) === 'Numbered twelve', 'collision: /devices/12 opens the device numbered 12, not id 12');
  ok((await sub(c)).startsWith('12'), 'collision: header shows the device number under the name');
  ok((await c.locator('.v2-breadcrumb, nav[aria-label="Breadcrumb"]').first().textContent()).includes('12'), 'collision: breadcrumb shows the number');
  await c.getByRole('button', { name: 'Factory reset…' }).click();
  const wipe = c.getByRole('alertdialog', { name: 'Factory reset (wipe)' });
  await wipe.waitFor();
  ok((await wipe.textContent()).includes('Device 12 · Numbered twelve'), 'wipe dialog names the device');
  await c.keyboard.press('Escape');
  await c.getByRole('button', { name: 'Reset passcode…' }).click();
  const pc = c.getByRole('alertdialog', { name: 'Reset passcode' });
  await pc.waitFor();
  ok((await pc.textContent()).includes('Device 12 · Numbered twelve'), 'reset passcode dialog names the device');
  await pc.getByRole('button', { name: 'Cancel' }).click();
  await c.getByRole('button', { name: 'Reboot…' }).click();
  const rb = c.getByRole('alertdialog', { name: 'Reboot device' });
  await rb.waitFor();
  ok((await rb.textContent()).includes('Device 12'), 'reboot dialog names the device');
  await rb.getByRole('button', { name: 'Cancel' }).click();

  await c.goto(`${base}/devices/WH-12`);
  ok((await heading(c)) === 'Id twelve', 'exact number WH-12 opens its device');
  await c.goto(`${base}/devices/502`);
  ok((await heading(c)) === 'Numbered 120', 'old id link (no device numbered 502) falls back to the id');
  ok((await sub(c)).startsWith('120'), 'id fallback: header shows the number, not the id');
  ok(new URL(c.url()).pathname === '/devices/120', 'id fallback: the URL moves to the device number');
  await c.goto(`${base}/devices/1`);
  ok(await c.getByText('Device not found.').waitFor({ timeout: 6000 }).then(() => true, () => false), 'a substring of numbers (and no id 1) is not found, not a guess');
  await c.context().close();

  // The exact lookup failing (older server, "/" in the number) still finds the exact row in the search answer.
  const d = await page();
  await fleet(d, list, { lookup: false });
  await d.goto(`${base}/devices/12`);
  ok((await heading(d)) === 'Numbered twelve', 'collision without the exact lookup: search row with the exact number wins');
  await d.context().close();
  // The lookup erroring (a 500) while the search leaves out the exact row: the page must not guess by id.
  allowNetErrors = true; // the induced 500 logs "Failed to load resource"
  const e = await page();
  await e.context().route('**/rest/private/devices/search', (r) => {
    let value = '';
    try { value = JSON.parse(r.request().postData() || '{}').value ?? ''; } catch { /* no body */ }
    // The narrow search for "12" misses the device numbered 12; the 1000-row list has id 12 (WH-12) only.
    return json(r, { configurations: fx.configurations, devices: { items: value ? [oneTwenty] : [idTwelve, oneTwenty], totalItemsCount: 2 } });
  });
  await e.context().route('**/rest/private/devices/number/**', (r) => r.fulfill({ status: 500, body: 'boom' }));
  await e.goto(`${base}/devices/12`);
  const failed = await e.getByText('Failed to load device.').waitFor({ timeout: 6000 }).then(() => true, () => false);
  ok(failed && (await e.getByRole('heading', { name: 'Id twelve' }).count()) === 0, 'lookup error + no exact row: an error, never the device whose id is 12');
  await e.context().close();
  allowNetErrors = false;
}

// Presence follows the polled state report, not only the device row loaded once.
{
  const o = await page();
  await fleet(o, [dev(601, 'LIVE-1', 'Live device', { lastUpdate: now - 20 * 60_000 })]);
  await o.context().route('**/rest/private/agent/v1/devices/*/state', (route) => json(route, { ...fx.state, updatedAt: Date.now() - 5_000 }));
  await o.goto(`${base}/devices/LIVE-1`);
  await heading(o);
  await o.locator('.v2-device-presence', { hasText: 'just now' }).waitFor({ timeout: 6000 }).catch(() => {});
  const presence = await o.locator('.v2-device-presence').textContent();
  ok(!/offline/i.test(presence) && presence.includes('just now'), `presence from the latest state report (${presence.trim()})`);
  await o.context().close();
}

// HTML in the device-reported provider is text, in the rail and in the map popup.
{
  const x = await page();
  const evil = '<img src=x onerror="window.__xss=1">';
  await x.context().route('**/rest/private/agent/v1/devices/*/locations*', (r) =>
    json(r, [
      { lat: 52.52, lon: 13.405, accuracy: 12, provider: evil, capturedAt: now - 120_000 },
      { lat: 52.521, lon: 13.406, accuracy: 20, provider: evil, capturedAt: now - 600_000 },
    ]));
  await x.goto(`${base}/devices/WH-SCAN-07`);
  await heading(x);
  await x.getByText('52.52000, 13.40500').waitFor({ timeout: 6000 }).catch(() => {});
  ok(await x.locator('.v2-device-rail').getByText(evil).count() > 0, 'provider shown as text in the rail');
  await x.getByRole('tab', { name: 'Location' }).click();
  await x.locator('.leaflet-interactive').first().waitFor({ timeout: 6000 }).catch(() => {});
  await x.locator('path.leaflet-interactive').last().click({ force: true }).catch(() => {});
  await x.locator('.leaflet-popup-content').waitFor({ timeout: 4000 }).catch(() => {});
  ok(((await x.locator('.leaflet-popup-content').textContent().catch(() => '')) ?? '').includes(evil), 'popup shows the provider as text');
  ok(await x.evaluate(() => document.querySelectorAll('img[src="x"]').length === 0 && !('__xss' in window)), 'no element injected from the provider');
  await x.context().close();
}

// A → B navigation race: A's answers arrive late (and different); B's page must keep B's device and data.
// (The shell remounts the page per path today, which already drops A's answers; this guards the page itself.)
async function spaGo(p, path) {
  await p.evaluate((to) => { history.pushState({}, '', to); dispatchEvent(new PopStateEvent('popstate')); }, path);
}
for (const slowLookup of [true, false]) {
  const r = await page();
  const ctx = r.context();
  const A = 'WH-SCAN-07';
  const isA = (u) => u.includes(`/${A}/`) || u.endsWith(`/${A}`) || u.includes(`/${A}?`);
  await ctx.route('**/rest/private/devices/number/**', async (route) => {
    const u = route.request().url();
    const n = decodeURIComponent(new URL(u).pathname.split('/').pop());
    if (isA(u) && slowLookup) { await sleep(2500); return json(route, { ...fx.devices[0], description: 'STALE A' }); }
    return json(route, fx.devices.find((d) => d.number === n) ?? null);
  });
  await ctx.route('**/rest/private/devices/search', async (route) => {
    const body = JSON.parse(route.request().postData() || '{}');
    if (body.value === A && slowLookup) {
      await sleep(2500);
      return json(route, { configurations: fx.configurations, devices: { items: [{ ...fx.devices[0], description: 'STALE A' }], totalItemsCount: 1 } });
    }
    return route.fallback();
  });
  await ctx.route('**/rest/private/agent/v1/devices/*/state', async (route) => {
    if (!isA(route.request().url())) return route.fallback();
    if (!slowLookup) await sleep(2500);
    return json(route, { ...fx.state, battery: 11, charging: false });
  });
  await ctx.route('**/rest/private/agent/v1/devices/*/locations*', async (route) => {
    if (!isA(route.request().url())) return route.fallback();
    if (!slowLookup) await sleep(2500);
    return json(route, [{ lat: 1.11111, lon: 2.22222, accuracy: 5, provider: 'gps', capturedAt: now - 60_000 }]);
  });
  await r.goto(`${base}/devices/${A}`);
  if (slowLookup) await r.getByText('Loading device…').waitFor({ timeout: 4000 }).catch(() => {});
  else await heading(r);
  await spaGo(r, '/devices/FIELD-1242');
  ok(await r.getByRole('heading', { level: 1, name: 'Field tablet — North' }).waitFor({ timeout: 12000 }).then(() => true, () => false), `race (${slowLookup ? 'slow lookup' : 'slow data'}): B opens`);
  await sleep(3200);
  const text = await r.locator('#v2-main, main').first().textContent();
  ok((await r.getByRole('heading', { level: 1 }).textContent()) === 'Field tablet — North' && !text.includes('STALE A'), `race (${slowLookup ? 'slow lookup' : 'slow data'}): A's late device doesn't replace B`);
  ok(!text.includes('11%') && !text.includes('1.11111'), `race (${slowLookup ? 'slow lookup' : 'slow data'}): A's late state and location don't show on B`);
  ok((await sub(r)).startsWith('FIELD-1242'), `race (${slowLookup ? 'slow lookup' : 'slow data'}): header number is B's`);
  await ctx.close();
}

// The same race within one page: a slow automatic location load answered after a newer on-demand Refresh is dropped.
{
  const f = await page();
  let calls = 0;
  await f.context().route('**/rest/private/agent/v1/devices/*/locations*', async (route) => {
    const n = ++calls;
    if (n === 1) {
      await sleep(2500);
      return json(route, [{ lat: 1.11111, lon: 2.22222, accuracy: 5, provider: 'gps', capturedAt: now - 600_000 }]);
    }
    return json(route, [{ lat: 48.85837, lon: 2.29448, accuracy: 8, provider: 'gps', capturedAt: now - 60_000 }]);
  });
  await f.goto(`${base}/devices/WH-SCAN-07`);
  await heading(f);
  await f.getByRole('tab', { name: 'Location' }).click();
  await f.locator('.v2-device-loc').getByRole('button', { name: 'Refresh' }).click();
  await f.getByText('48.85837, 2.29448').waitFor({ timeout: 4000 }).catch(() => {});
  await sleep(3000);
  const rail = await f.locator('.v2-device-rail').textContent();
  ok(rail.includes('48.85837, 2.29448') && !rail.includes('1.11111'), 'an older location answer landing late doesn\u2019t replace the newer one');
  await f.context().close();
}

// Error states with Retry.
{
  allowNetErrors = true;
  const e = await page();
  let mode = 'fail';
  await e.context().route('**/rest/private/devices/**', (route) => {
    if (mode === 'fail') return route.fulfill({ status: 500, body: 'boom' });
    if (mode === 'offline') return route.abort('internetdisconnected');
    return route.fallback();
  });
  await e.goto(`${base}/devices/WH-SCAN-07`);
  ok(await e.getByText('Failed to load device.').waitFor({ timeout: 6000 }).then(() => true, () => false), 'server error: message shown');
  mode = 'offline';
  await e.getByRole('button', { name: 'Try again' }).click();
  ok(await e.getByText('Cannot reach the server.').waitFor({ timeout: 6000 }).then(() => true, () => false), 'offline: "Cannot reach the server."');
  mode = 'ok';
  await e.getByRole('button', { name: 'Try again' }).click();
  ok((await heading(e)) === 'Warehouse scanner', 'Try again loads the device');
  await e.context().close();
  allowNetErrors = false;
}

// Do-not-track lock (the configuration's requestUpdates unset or DONOTTRACK).
{
  const t = await page();
  await t.context().route('**/rest/private/configurations/1', (route) =>
    json(route, { id: 1, name: 'Default', requestUpdates: 'DONOTTRACK', kioskMode: false, applications: [] }));
  await t.context().route('**/rest/private/agent/v1/devices/*/locations*', (route) => json(route, []));
  await t.context().route('**/rest/private/agent/v1/devices/*/telemetry', (route) =>
    json(route, { hardware: { model: 'TC52' }, dynamic: {}, security: {}, identity: {} }));
  await t.goto(`${base}/devices/WH-SCAN-07`);
  await heading(t);
  const loc = t.getByRole('radiogroup', { name: 'Location' });
  await t.waitForFunction(() => document.querySelector('[role="radiogroup"][aria-disabled="true"]'), null, { timeout: 6000 }).catch(() => {});
  ok((await loc.getAttribute('aria-disabled')) === 'true', 'DNT: location mode locked');
  ok((await loc.getByRole('radio', { checked: true }).count()) === 0, 'DNT: no location mode selected');
  ok(await t.getByText('Not tracked').isVisible(), 'DNT: rail says Not tracked');
  await t.getByRole('tab', { name: 'Location' }).click();
  ok(await t.locator('.v2-device-loc').getByText(/Do not track|not track/i).first().isVisible(), 'DNT: location tab explains');
  await t.context().close();
}

// Malformed telemetry, locations, state and scans render "—" (never NaN, "[object Object]" or a crash).
{
  const b = await page();
  const ctx = b.context();
  await ctx.route('**/rest/private/agent/v1/devices/*/telemetry', (route) =>
    json(route, {
      hardware: 'oops',
      dynamic: {
        location: { lat: 'north', lon: 5, provider: 7, capturedAt: 'yesterday' },
        ipAddress: { v4: '10.0.0.1' }, networkType: 7, batteryTempC: 'hot', uptimeMs: 'long', foregroundApp: [{ pkg: 1 }],
        freeStorageBytes: 'lots',
      },
      security: null,
      identity: { imei: [{ x: 1 }], serial: { nested: true } },
      publicIp: ['198.51.100.1'],
    }));
  await ctx.route('**/rest/private/agent/v1/devices/*/locations*', (route) =>
    json(route, [
      { lat: 'bad', lon: 1, capturedAt: now },
      { lat: 10.5, lon: 20.5, accuracy: 'wide', provider: { name: 'gps' }, capturedAt: now - 60_000 },
      { lat: 11, lon: 21, accuracy: 3, provider: 'gps', capturedAt: 1e20 },
    ]));
  await ctx.route('**/rest/private/agent/v1/devices/*/state', (route) => json(route, { ...fx.state, updatedAt: 'soon' }));
  await ctx.route('**/rest/private/agent/v1/devices/*/configStatus', (route) =>
    json(route, { configurationId: 1, currentRevision: 'aaaaaaaa1', appliedRevision: 'bbbbbbbb2', appliedAt: 1e20, inSync: false, supported: true }));
  await ctx.route('**/rest/private/agent/v1/devices/*/commands*', (route) => {
    if (route.request().method() !== 'GET') return route.fallback();
    return json(route, [
      { id: 900, type: 'apps.scan', status: 'done', createdAt: 1e20, completedAt: now - 60_000,
        detail: JSON.stringify({ apps: [null, { pkg: 'com.acme.ok', label: 'Okay app' }, { pkg: 'com.acme.nolabel' }, { pkg: 'com.acme.numlabel', label: 5 }, { label: 'no package' }] }) },
    ]);
  });
  await b.goto(`${base}/devices/WH-SCAN-07`);
  await heading(b);
  await b.getByText('10.50000, 20.50000').waitFor({ timeout: 6000 }).catch(() => {});
  const rail = await b.locator('.v2-device-rail').textContent();
  ok(rail.includes('10.50000, 20.50000'), 'bad fixes dropped, the valid one shown');
  ok(!/NaN|\[object Object\]|Invalid Date/.test(rail), 'rail: no NaN / [object Object]');
  ok(await b.locator('.v2-device-rail').getByText('Accuracy').locator('xpath=..').getByText('—').count() > 0, 'non-number accuracy shows —');
  for (const tabName of ['Telemetry', 'Activity', 'Location', 'Control']) {
    await b.getByRole('tab', { name: new RegExp(tabName) }).click();
    await sleep(400);
    const main = await b.locator('#v2-main, main').first().textContent();
    ok(!/NaN|\[object Object\]|Invalid Date/.test(main), `${tabName} tab renders malformed telemetry without NaN / [object Object]`);
  }
  await b.getByRole('button', { name: 'Custom kiosk…' }).click();
  const kd = b.getByRole('dialog', { name: 'Custom kiosk' });
  await kd.waitFor();
  await kd.getByRole('radio', { name: 'Device apps' }).click();
  await kd.getByText('com.acme.nolabel').first().waitFor({ timeout: 4000 }).catch(() => {});
  ok(await kd.getByText('Okay app').isVisible().catch(() => false), 'saved scan listed');
  ok(await kd.getByText('com.acme.nolabel').count() > 0 && await kd.getByText('com.acme.numlabel').count() > 0, 'scan entries without a label fall back to the package');
  ok(await kd.getByText('no package').count() === 0, 'scan entries without a package are dropped');
  await b.keyboard.press('Escape');
  await ctx.close();
}

// ---- Return to configured kiosk (#71): the kiosk the configuration defines, as kiosk.enter ----
/** Sorted-key JSON, so payloads compare by content (the server's canonical JSON sorts keys the same way). */
const canon = (v) => JSON.stringify(v, (_k, x) => (x && typeof x === 'object' && !Array.isArray(x) ? Object.fromEntries(Object.entries(x).sort(([a], [b]) => (a < b ? -1 : 1))) : x));
const kioskPosts = (p) => p.posts.filter((x) => x.url.includes('/commands') && x.body && JSON.parse(x.body).type === 'kiosk.enter');
const lastKioskPayload = (p) => { const l = kioskPosts(p).at(-1); return l ? JSON.parse(JSON.parse(l.body).payload) : null; };
/** Configuration 1 and its app matrix (rows with `selected`, as /configurations/applications/{id} answers). */
async function kioskConfig(p, cfg, apps) {
  await p.context().route('**/rest/private/configurations/1', (r) => json(r, { id: 1, name: 'Default', applications: [], ...cfg }));
  await p.context().route('**/rest/private/configurations/applications/1', (r) => json(r, apps.map((a) => ({ selected: true, action: 1, showIcon: true, ...a }))));
}
const returnBtn = (p) => p.getByRole('button', { name: 'Return to configured kiosk…' });
/** Waits (up to 8 s) for Return to configured kiosk to be offered and enabled (the configuration's apps load first). */
const returnEnabled = (p) => p.waitForFunction(() => {
  const b = [...document.querySelectorAll('button')].find((x) => x.textContent.includes('Return to configured kiosk'));
  return b && !b.disabled;
}, null, { timeout: 8000 }).catch(() => {});
const kioskHint = (p) => p.locator('.v2-device-actions__row', { hasText: 'Kiosk & apps' }).locator('.v2-device-actions__hint');

// A: the server's golden kiosk configuration (common/src/test/resources/contract/v1/desired-config-kiosk.json, built by
// DesiredConfigBuilderTest.kioskConfig() from one Install app): the payload must equal that document's kiosk block.
{
  const golden = JSON.parse(fs.readFileSync(new URL('../../../common/src/test/resources/contract/v1/desired-config-kiosk.json', import.meta.url), 'utf8')).kiosk;
  const k = await page();
  const ctx = k.context();
  await kioskConfig(k,
    { kioskMode: true, mainAppId: 505, kioskExit: true, kioskHome: true, kioskRecents: false, password: 's3cret',
      backgroundColor: '#000000', textColor: '#ffffff', iconSize: 'LARGE', requestUpdates: 'GPS', wifi: true, bluetooth: false, disableScreenshots: true },
    [
      { id: 5, usedVersionId: 505, pkg: 'com.acme.pos', name: 'POS' },
      { id: 9, usedVersionId: 909, pkg: 'com.acme.old', name: 'Old', action: 2 },
    ]);
  // A custom kiosk.enter is still pending (not this console's configured one), and the last scan lacks the POS app.
  let hist = [
    { id: 410, type: 'kiosk.enter', status: 'pending', createdAt: now - 60_000 },
    { id: 300, type: 'apps.scan', status: 'done', completedAt: now - 3_600_000, detail: JSON.stringify({ apps: [{ pkg: 'com.acme.other', label: 'Other' }] }) },
  ];
  let kioskActive = false;
  await ctx.route('**/rest/private/agent/v1/devices/*/commands*', (r) => (r.request().method() === 'GET' ? json(r, hist) : r.fallback()));
  await ctx.route('**/rest/private/agent/v1/devices/*/state', (r) => json(r, { ...fx.state, agentVersion: '0.3.4', kioskActive, updatedAt: Date.now() }));
  await ctx.route('**/rest/private/agent/v1/devices/*/configStatus', (r) => json(r, { configurationId: 1, currentRevision: 'a1', appliedRevision: 'a1', inSync: true, supported: true }));
  await k.goto(`${base}/devices/WH-SCAN-07`);
  await heading(k);
  await returnEnabled(k);
  ok(await returnBtn(k).isEnabled().catch(() => false), 'kiosk on: Return to configured kiosk offered');
  ok(/v2-btn--primary/.test((await returnBtn(k).getAttribute('class')) ?? ''), 'Return to configured kiosk is the primary button');
  ok(!/v2-btn--(danger|primary)/.test((await k.getByRole('button', { name: 'Custom kiosk…' }).getAttribute('class')) ?? ''), 'kiosk on: Custom kiosk stays a quiet secondary button');
  await returnBtn(k).click();
  const rd = k.getByRole('dialog', { name: 'Return to configured kiosk' });
  await rd.waitFor();
  const rtext = await rd.textContent();
  ok(rtext.includes('Puts the device back into the kiosk that Default defines'), 'return dialog says what it does');
  ok(rtext.includes('Pinned to POS') && rtext.includes('Visible exit button') && rtext.includes('The configuration’s PIN'), `return dialog summary (${rtext.slice(0, 260)})`);
  ok(rtext.includes('Device WH-SCAN-07'), 'return dialog names the device');
  ok(rtext.includes('POS wasn’t on the device at its last app scan') && rtext.includes('Install config apps'), 'main app missing from the last scan: warned');
  ok(!rtext.includes('Lock volume'), 'no volume warning when the kiosk does not lock the volume');
  await rd.getByRole('button', { name: 'Return to configured kiosk' }).click();
  ok(await toast(k, 'Return to configured kiosk queued'), 'return toast');
  const sent = lastKioskPayload(k);
  ok(sent && canon(sent) === canon(golden), `payload equals the configuration’s config.apply kiosk block (${sent && canon(sent)})`);
  ok(kioskPosts(k).length === 1 && !JSON.parse(kioskPosts(k)[0].body).requiresCapability, 'one kiosk.enter, ungated like the custom kiosk');
  // The queued command (id 413) is waiting for the device.
  hist = [{ id: 413, type: 'kiosk.enter', status: 'pending', createdAt: Date.now() }, ...hist];
  await kioskHint(k).getByText('Waiting for the device to enter the kiosk that Default defines.').waitFor({ timeout: 8000 }).catch(() => {});
  ok(await returnBtn(k).isDisabled() && (await kioskHint(k).textContent()).includes('Waiting for the device'), 'while it waits: disabled with the reason');
  ok((await returnBtn(k).getAttribute('aria-describedby')) === (await kioskHint(k).getAttribute('id')), 'disabled button points at its reason');
  // Done, and the device reports kiosk on: already in the configured kiosk (also after a reload).
  hist = [{ ...hist[0], status: 'done', completedAt: Date.now() }, ...hist.slice(1)];
  kioskActive = true;
  await kioskHint(k).getByText('The device is already in the kiosk that Default defines.').waitFor({ timeout: 8000 }).catch(() => {});
  ok(await returnBtn(k).isDisabled() && (await kioskHint(k).textContent()).includes('already in the kiosk'), 'in the configured kiosk: disabled with the reason');
  await k.reload();
  await heading(k);
  await kioskHint(k).getByText('already in the kiosk', { exact: false }).waitFor({ timeout: 8000 }).catch(() => {});
  ok(await returnBtn(k).isDisabled().catch(() => false), 'still known after a reload (this browser sent it)');
  // The user leaves the kiosk on the device: offered again.
  kioskActive = false;
  await returnEnabled(k);
  ok(await returnBtn(k).isEnabled(), 'kiosk left on the device: offered again');
  await ctx.close();
}

// B: launcher kiosk. The main app id matches no Install app shown in kiosk (an app whose applications.id equals it is
// not the main app), packages are de-duplicated and trimmed, unset kiosk options and an unset PIN are left out, the
// kiosk locks the volume on an agent older than 0.3.4; the first send fails and says why.
{
  const k = await page();
  const ctx = k.context();
  await kioskConfig(k,
    { kioskMode: true, mainAppId: 777, kioskExit: false, kioskNotifications: true, kioskLockVolume: true, password: null, iconSize: null },
    [
      { id: 1, usedVersionId: 101, pkg: 'com.b', name: 'B' },
      { id: 2, usedVersionId: 202, pkg: ' com.a ', name: 'A' },
      { id: 3, usedVersionId: 303, pkg: 'com.hidden', name: 'Hidden', showIcon: false },
      { id: 4, usedVersionId: 404, pkg: 'com.b', name: 'B again' },
      { id: 6, usedVersionId: 606, pkg: 'com.notinstalled', name: 'Removed', action: 2 },
      { id: 777, usedVersionId: 778, pkg: 'com.decoy', name: 'Decoy' },
    ]);
  await ctx.route('**/rest/private/agent/v1/devices/*/state', (r) => json(r, { ...fx.state, agentVersion: '0.3.2', updatedAt: Date.now() }));
  let failNext = true;
  await ctx.route('**/rest/private/agent/v1/devices/*/commands', (r) => {
    if (r.request().method() !== 'POST' || !failNext) return r.fallback();
    failNext = false;
    return r.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ status: 'ERROR', message: 'Device is offline for maintenance', data: null }) });
  });
  await k.goto(`${base}/devices/WH-SCAN-07`);
  await heading(k);
  await returnEnabled(k);
  await returnBtn(k).click();
  const rd = k.getByRole('dialog', { name: 'Return to configured kiosk' });
  await rd.waitFor();
  const rtext = await rd.textContent();
  ok(rtext.includes('Launcher grid with 3 apps') && rtext.includes('A, B, Decoy'), `launcher summary (${rtext.slice(0, 200)})`);
  ok(rtext.includes('Hidden gesture') && rtext.includes('None: agents before 0.3.6 let anyone leave the kiosk; 0.3.6 and later have no exit on the device'), 'gesture exit, no PIN');
  ok(rtext.includes('The main app set in Default isn’t set to Install and shown in kiosk, so this kiosk leaves it out'), 'main app left out: warned');
  ok(rtext.includes('This device runs agent 0.3.2. Agents before 0.3.4 ignore Lock volume buttons'), 'old agent + volume lock: warned');
  await rd.getByRole('button', { name: 'Return to configured kiosk' }).click();
  const alert = rd.getByRole('alert');
  await alert.waitFor({ timeout: 4000 }).catch(() => {});
  ok((await alert.textContent().catch(() => '')).startsWith('Couldn’t queue the command:'), `queue error shown in the dialog (${await alert.textContent().catch(() => '')})`);
  ok(await rd.isVisible(), 'dialog stays open after an error');
  await rd.getByRole('button', { name: 'Return to configured kiosk' }).click();
  ok(await toast(k, 'Return to configured kiosk queued'), 'second try queued');
  const sent = lastKioskPayload(k);
  const want = { mode: 'launcher', allowedPackages: ['com.a', 'com.b', 'com.decoy'], features: { notifications: true, lockVolume: true }, exitMode: 'gesture', theme: {} };
  ok(sent && canon(sent) === canon(want), `launcher payload follows the server rules (${sent && canon(sent)})`);
  await ctx.close();
}

// C: why the action isn't available.
{
  allowNetErrors = true; // the apps 500 below logs "Failed to load resource"
  const k = await page();
  let fail = true;
  await k.context().route('**/rest/private/configurations/1', (r) => json(r, { id: 1, name: 'Default', kioskMode: true, mainAppId: 505, applications: [] }));
  await k.context().route('**/rest/private/configurations/applications/1', (r) =>
    fail ? r.fulfill({ status: 500, body: 'boom' }) : json(r, [{ id: 5, usedVersionId: 505, pkg: 'com.acme.pos', name: 'POS', selected: true, action: 1, showIcon: true }]));
  await k.goto(`${base}/devices/WH-SCAN-07`);
  await heading(k);
  await kioskHint(k).getByText('Couldn’t load the apps of Default', { exact: false }).waitFor({ timeout: 6000 }).catch(() => {});
  ok(await returnBtn(k).isDisabled().catch(() => false) && (await kioskHint(k).textContent().catch(() => '')).includes('Couldn’t load the apps of Default'), 'apps load error: disabled, error shown');
  fail = false;
  await kioskHint(k).getByRole('button', { name: 'Try again' }).click();
  await returnEnabled(k);
  ok(await returnBtn(k).isEnabled(), 'Try again loads the apps');
  await k.context().close();
  allowNetErrors = false;

  const n = await page();
  await kioskConfig(n, { kioskMode: true, mainAppId: 505 }, [{ id: 5, usedVersionId: 505, pkg: 'com.acme.pos', name: 'POS', action: 0 }]);
  await n.goto(`${base}/devices/WH-SCAN-07`);
  await heading(n);
  await kioskHint(n).getByText('no app is set to Install', { exact: false }).waitFor({ timeout: 6000 }).catch(() => {});
  ok(await returnBtn(n).isDisabled() && (await kioskHint(n).textContent()).includes('Default has kiosk mode on, but no app is set to Install and shown in kiosk'), 'no kiosk app: disabled with the reason');
  await n.context().close();

  // Already in the configured kiosk through config.apply (in sync, its kiosk outcome applied, kiosk running).
  const a = await page();
  await kioskConfig(a, { kioskMode: true, mainAppId: 505 }, [{ id: 5, usedVersionId: 505, pkg: 'com.acme.pos', name: 'POS' }]);
  await a.context().route('**/rest/private/agent/v1/devices/*/state', (r) => json(r, { ...fx.state, kioskActive: true, updatedAt: Date.now() }));
  await a.context().route('**/rest/private/agent/v1/devices/*/configStatus', (r) => json(r, { configurationId: 1, currentRevision: 'a1', appliedRevision: 'a1', inSync: true, supported: true }));
  await a.context().route('**/rest/private/agent/v1/devices/*/commands*', (r) => (r.request().method() === 'GET'
    ? json(r, [
        { id: 420, type: 'config.apply', status: 'done', completedAt: now - 60_000, detail: JSON.stringify({ revision: 'a1', outcomes: { kiosk: 'applied', location: 'applied' } }) },
        { id: 400, type: 'kiosk.exit', status: 'done', completedAt: now - 600_000 },
      ])
    : r.fallback()));
  await a.goto(`${base}/devices/WH-SCAN-07`);
  await heading(a);
  await kioskHint(a).getByText('already in the kiosk', { exact: false }).waitFor({ timeout: 6000 }).catch(() => {});
  ok(await returnBtn(a).isDisabled() && (await kioskHint(a).textContent()).includes('The device is already in the kiosk that Default defines.'), 'config.apply kiosk running: disabled with the reason');
  await a.context().close();

  // No configuration at all.
  const z = await page();
  await fleet(z, [dev(701, 'NOCFG-1', 'Loose device', { configurationId: undefined })]);
  await z.goto(`${base}/devices/NOCFG-1`);
  await heading(z);
  await z.getByText('This device has no configuration, so there is no configured kiosk to return to.').waitFor({ timeout: 6000 }).catch(() => {});
  ok(await z.getByText('This device has no configuration, so there is no configured kiosk to return to.').isVisible() && (await returnBtn(z).count()) === 0, 'no configuration: custom only, with the hint');
  await z.context().close();
}

// Kiosk events (agent 0.3.6/0.3.7) in the Activity tab: labelled, not the raw type.
{
  const k = await page();
  const t = Date.now();
  await k.context().route('**/rest/private/agent/v1/devices/WH-SCAN-07/events**', (r) => json(r, [
    { id: 2, type: 'kioskPinLockout', ts: t - 60_000, detail: null },
    { id: 1, type: 'kioskCrashLoop', ts: t - 120_000, detail: 'kiosk app closed repeatedly; kiosk stays on, app not reopened' },
  ]));
  await k.goto(`${base}/devices/WH-SCAN-07`);
  await heading(k);
  await k.getByRole('tab', { name: /Activity/ }).click();
  await k.getByText('Exit PIN locked').first().waitFor({ timeout: 6000 }).catch(() => {});
  ok(await k.getByText('Exit PIN locked').first().isVisible(), 'kiosk event: Exit PIN locked');
  ok(await k.getByText('Kiosk app keeps closing').first().isVisible(), 'kiosk event: Kiosk app keeps closing');
  ok((await k.getByText(/kioskPinLockout|kioskCrashLoop/).count()) === 0, 'kiosk events: no raw type');
  await k.context().close();
}

console.log('console errors:', errors.length ? errors : 'none');
console.log(fails ? `${fails} FAILED` : 'ALL PASSED');
await browser.close(); server.close();
process.exit(fails || errors.length ? 1 : 0);
