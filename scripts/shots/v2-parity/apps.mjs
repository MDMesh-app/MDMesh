// Parity walk-through of /apps against the shots fixtures.
//   node scripts/shots/v2-parity/apps.mjs [--dist <dir>]
import { chromium } from 'playwright';
import { fx, newContext, startServer } from '../harness.mjs';
import { dist } from './lib.mjs';

const { server, base } = await startServer(dist);
const browser = await chromium.launch();
const ctx = await newContext(browser, fx.user, { theme: 'light', viewport: { width: 1440, height: 1000 }, deviceScaleFactor: 1, blockGoogleFonts: true });
const page = await ctx.newPage();
const errors = [];
page.on('console', (m) => m.type() === 'error' && !/fonts\.g/.test(m.location()?.url ?? '') && errors.push(m.text()));
page.on('pageerror', (e) => errors.push(String(e)));
const reqs = [];
page.on('request', (r) => {
  if (r.url().includes('/rest/') && r.method() !== 'GET') reqs.push({ m: r.method(), u: new URL(r.url()).pathname, b: r.postData() });
});
const ok = (data) => ({ status: 200, contentType: 'application/json', body: JSON.stringify({ status: 'OK', message: null, data }) });
const err = (message) => ({ status: 200, contentType: 'application/json', body: JSON.stringify({ status: 'ERROR', message, data: null }) });

let pass = 0;
let fail = 0;
function check(name, cond, extra = '') {
  if (cond) pass++;
  else fail++;
  console.log(`${cond ? 'PASS' : 'FAIL'} ${name}${extra ? ` (${extra})` : ''}`);
}
const lastToast = async () => (await page.locator('.toast').last().innerText().catch(() => '')).replace(/\n/g, ' | ');
const waitToast = async (text, timeout = 12000) => {
  try {
    await page.locator('.toast', { hasText: text }).last().waitFor({ timeout });
    return true;
  } catch {
    return false;
  }
};
const clearReqs = () => (reqs.length = 0);

// ---------------- Library ----------------
await page.goto(`${base}/apps`);
await page.locator('table[aria-label="Library"] tbody tr').first().waitFor();
check('library rows', (await page.locator('table[aria-label="Library"] tbody tr').count()) === 8);
check('tab count', (await page.locator('nav[aria-label="App source"] .v2-tabs__count').innerText()) === '8');
check('play store tab dropped', (await page.getByText('Play Store').count()) === 0);
await page.waitForTimeout(400);
check('not used yet x2', (await page.getByText('Not used yet').count()) === 2);
check('used in names', (await page.getByText('Retail Kiosk, Field Ops').count()) === 1);
check('missing notice', (await page.locator('section[aria-label="Missing checksum"]').innerText()).includes('Microsoft Teams'));
check('source split bundle', (await page.getByText('3 parts').count()) === 1);

await page.getByRole('searchbox', { name: 'Search the library' }).fill('chrome');
check('search filters', (await page.locator('table[aria-label="Library"] tbody tr').count()) === 1);
await page.getByRole('searchbox', { name: 'Search the library' }).fill('');
await page.getByRole('combobox', { name: 'Source', exact: true }).selectOption('fdroid');
check('source filter', (await page.locator('table[aria-label="Library"] tbody tr').count()) === 2);
await page.getByRole('combobox', { name: 'Source', exact: true }).selectOption('all');
await page.getByRole('searchbox', { name: 'Search the library' }).fill('zzz');
check('no matches', (await page.getByText('No apps match your search.').count()) === 1);
await page.getByRole('searchbox', { name: 'Search the library' }).fill('');

// SHA-256 via the notice
await page.getByRole('button', { name: 'Add checksum' }).click();
const dlg = page.getByRole('dialog');
await dlg.waitFor();
check('sha dialog url', (await dlg.innerText()).includes('teams-1.0.0.apk'));
const shaInput = dlg.getByLabel('SHA-256 (64 hex characters)');
await shaInput.fill('xyz');
check('sha invalid -> save disabled', await dlg.getByRole('button', { name: 'Save' }).isDisabled());
check('sha invalid error', (await dlg.innerText()).includes('64 hex characters (0-9'));
clearReqs();
await shaInput.fill('AB'.repeat(32));
await dlg.getByRole('button', { name: 'Save' }).click();
check('sha saved toast', await waitToast('SHA-256 saved'));
const shaReq = reqs.find((r) => r.u.endsWith('/applications/versions/40/sha256'));
check('sha PUT lower-cased', !!shaReq && shaReq.b.includes('ab'.repeat(32)), shaReq?.b);
check('notice gone', (await page.locator('section[aria-label="Missing checksum"]').count()) === 0);

// Remove blocked (used)
await page.getByRole('button', { name: 'More for MDMesh Agent' }).click();
await page.getByRole('menuitem', { name: /Remove from library/ }).click();
check('remove blocked toast', await waitToast('App is still in use'));
// Remove allowed
await page.getByRole('button', { name: 'More for Binary Eye' }).click();
await page.getByRole('menuitem', { name: /Remove from library/ }).click();
const conf = page.getByRole('alertdialog');
await conf.waitFor();
check('confirm text', (await conf.innerText()).includes('does not uninstall the app'));
clearReqs();
await conf.getByRole('button', { name: 'Remove from library' }).click();
check('removed toast', await waitToast('Removed from Library'));
check('DELETE sent', reqs.some((r) => r.m === 'DELETE' && r.u.endsWith('/applications/8')));
check('row gone', (await page.locator('table[aria-label="Library"] tbody tr').count()) === 7);
check('count updated', (await page.locator('nav[aria-label="App source"] .v2-tabs__count').innerText()) === '7');
// Remove error mapping
await page.route('**/rest/private/applications/7', (r) => (r.request().method() === 'DELETE' ? r.fulfill(err('error.config.reference.exists')) : r.fallback()));
await page.route('**/rest/private/applications/configurations/7', (r) => r.fulfill(ok([])));
await page.getByRole('button', { name: 'More for Organic Maps' }).click();
await page.getByRole('menuitem', { name: /Remove from library/ }).click();
await page.getByRole('alertdialog').getByRole('button', { name: 'Remove from library' }).click();
check('reference.exists mapped', await waitToast('A configuration still uses this app'));

// Deploy a bundle to devices (bulk)
await page.getByRole('button', { name: 'Deploy Zebra DataWedge' }).click();
const dep = page.getByRole('dialog');
await dep.waitFor();
check('deploy desc parts', (await dep.innerText()).includes('3 parts'));
await dep.locator('.v2-deploy__row').first().waitFor();
check('devices listed', (await dep.locator('.v2-deploy__row').count()) === 8);
await dep.getByRole('searchbox', { name: 'Filter devices' }).fill('KIOSK');
check('device filter', (await dep.locator('.v2-deploy__row').count()) === 2);
await dep.getByLabel(/Select all 2 shown/).check();
await dep.getByRole('searchbox', { name: 'Filter devices' }).fill('');
await dep.locator('.v2-deploy__row').first().click();
await dep.getByLabel('Launch the app after install').check();
clearReqs();
await dep.getByRole('button', { name: 'Deploy to 3 devices' }).click();
check('deploy toast', await waitToast('3 devices queued'));
const installs = reqs.filter((r) => r.m === 'POST' && /commands/.test(r.u));
check('3 install commands with parts + runAfter', installs.length === 3 && installs.every((r) => r.b.includes('parts') && r.b.includes('runAfterInstall\\":true')), installs[0]?.b?.slice(0, 200));

// Deploy to a configuration
await page.getByRole('button', { name: 'Deploy Chrome' }).click();
const dep2 = page.getByRole('dialog');
await dep2.waitFor();
await dep2.getByRole('tab', { name: 'Add to a configuration' }).click();
await dep2.getByLabel('Configuration', { exact: true }).selectOption({ label: 'Default' });
clearReqs();
await dep2.getByRole('button', { name: 'Add to configuration' }).click();
check('assign toast', await waitToast('Assigned to Default'));
const link = reqs.find((r) => r.u.endsWith('/applications/configurations'));
check('assign link body', !!link && /"configurationId":1,[^}]*"action":1/.test(link.b), link?.b?.slice(0, 300));

// Deploy error when nothing to install
await page.route('**/rest/private/applications/2/versions', (r) => r.fulfill(ok([])));
// ---------------- Custom APK ----------------
await page.goto(`${base}/apps?source=custom`);
await page.getByText('Add your own app').waitFor();
check('stepper at File', (await page.locator('.v2-stepper [aria-current="step"]').innerText()).includes('File'));
await page.route('**/rest/private/web-ui-files', (r) =>
  r.fulfill(ok({ serverPath: '/tmp/up.apk', fileDetails: { pkg: 'com.acme.new', name: 'New App', version: '5.2.0', versionCode: 5020 } })),
);
await page.route('**/rest/private/web-ui-files/update', (r) => r.fulfill(ok({ url: 'https://mdm.acme.example/files/new.apk' })));
let putBody = null;
await page.route('**/rest/private/applications/android', (r) => {
  putBody = r.request().postData();
  return r.fulfill(ok({ id: 901, name: 'New App', pkg: 'com.acme.new' }));
});
await page.locator('input[type=file]').first().setInputFiles({ name: 'new-app.apk', mimeType: 'application/vnd.android.package-archive', buffer: Buffer.alloc(2_000_000) });
check('upload toast', await waitToast('APK ready'));
check('read panel', (await page.locator('.v2-filepanel').innerText()).includes('Read successfully · APK · 1.9 MB'));
check('auto-added with version text', !!putBody && putBody.includes('"version":"5.2.0"') && putBody.includes('"type":"app"'), putBody);
check('version field', (await page.getByLabel('Version', { exact: true }).inputValue()) === '5.2.0 (code 5020)');
check('pkg read-only', await page.getByLabel('Package name').evaluate((e) => e.readOnly));
check('in library disabled', await page.getByRole('button', { name: 'In library' }).isDisabled());
check('stepper all done', (await page.locator('.v2-stepper [aria-current="step"]').count()) === 0);
await page.getByRole('button', { name: 'Deploy…' }).click();
const dep3 = page.getByRole('dialog');
await dep3.waitFor();
check('custom deploy can assign', !(await dep3.getByRole('tab', { name: 'Add to a configuration' }).isDisabled()));
await dep3.getByRole('button', { name: 'Cancel' }).click();

// not an APK
await page.locator('input[type=file]').first().setInputFiles({ name: 'notes.pdf', mimeType: 'application/pdf', buffer: Buffer.alloc(10) });
check('not an apk toast', await waitToast('Not an APK'));
check('alert panel', (await page.locator('.v2-filepanel.is-alert').count()) === 1);

// bundle
await page.route('**/rest/private/web-ui-files/bundle', (r) =>
  r.fulfill(ok({ name: 'Route', packageName: 'com.acme.route', version: '', versionCode: 77, parts: [1, 2, 3].map((i) => ({ url: `https://mdm.acme.example/files/b/${i}.apk`, sha256: 'a'.repeat(64), name: `p${i}.apk` })) })),
);
putBody = null;
await page.locator('input[type=file]').first().setInputFiles({ name: 'route.apks', mimeType: 'application/zip', buffer: Buffer.alloc(43_000_000) });
// 43 MB through setInputFiles: slow on a loaded machine.
check('bundle toast', await waitToast('Bundle ready', 30000), await lastToast());
check('bundle panel', (await page.locator('.v2-filepanel').innerText()).includes('split bundle with 3 parts · 41.0 MB'));
check('bundle saved with parts + version fallback', !!putBody && putBody.includes('"parts"') && putBody.includes('"version":"77"'), putBody?.slice(0, 160));

// upload failure
await page.route('**/rest/private/web-ui-files', (r) => r.fulfill(err('Signature check failed')));
await page.locator('input[type=file]').first().setInputFiles({ name: 'bad.apk', mimeType: 'application/vnd.android.package-archive', buffer: Buffer.alloc(10) });
check('upload failed toast', await waitToast('Upload failed'));

// link mode
await page.getByRole('radio', { name: 'Use a link' }).click();
await page.getByLabel('Link to the APK').fill('https://cdn.example.com/thing.apk');
await page.getByLabel('Package name').fill('com.example.thing');
check('stepper check details', (await page.locator('.v2-stepper [aria-current="step"]').innerText()).includes('Check details'));
await page.getByRole('button', { name: 'Add to library' }).click();
check('sha required toast', await waitToast('SHA-256 required'));
await page.getByLabel('SHA-256 checksum').fill('123');
await page.getByRole('button', { name: 'Add to library' }).click();
check('sha invalid toast', await waitToast('Invalid SHA-256'));
putBody = null;
await page.getByLabel('SHA-256 checksum').fill('CD'.repeat(32));
check('stepper add', (await page.locator('.v2-stepper [aria-current="step"]').innerText()).includes('Add'));
await page.getByRole('button', { name: 'Add to library' }).click();
check('added toast', await waitToast('Added to Library'));
check('link save body', !!putBody && putBody.includes('cd'.repeat(32)) && putBody.includes('cdn.example.com'), putBody);
check('no second add', await page.getByRole('button', { name: 'In library' }).isDisabled());
// duplicate → reuse existing entry
await page.getByLabel('Link to the APK').fill('https://cdn.example.com/chrome.apk');
await page.getByLabel('Package name').fill('com.android.chrome');
await page.route('**/rest/private/applications/android', (r) => r.fulfill(err('error.duplicate.application')));
clearReqs();
await page.getByRole('button', { name: 'Add and deploy…' }).click();
check('duplicate reuses existing', await waitToast('Already in Library'));
const dep4 = page.getByRole('dialog');
await dep4.waitFor();
check('add and deploy opens with id', !(await dep4.getByRole('tab', { name: 'Add to a configuration' }).isDisabled()));
check('only one save attempt', reqs.filter((r) => r.u.endsWith('/applications/android')).length === 1);
await dep4.getByRole('button', { name: 'Cancel' }).click();

// ---------------- F-Droid ----------------
await page.goto(`${base}/apps?source=fdroid`);
await page.locator('.v2-apps-fd__card').first().waitFor();
check('12 cards', (await page.locator('.v2-apps-fd__card').count()) === 12);
await page.getByRole('button', { name: 'Show more' }).click();
check('show more -> 14', (await page.locator('.v2-apps-fd__card').count()) === 14);
check('no more button', (await page.getByRole('button', { name: 'Show more' }).count()) === 0);
check('in library x2', (await page.locator('.v2-apps-fd__inlib').count()) === 2);
check('blocked', (await page.locator('.v2-apps-fd__card', { hasText: 'Kiosk Notes' }).innerText()).includes('can’t deploy'));
check('blocked has no deploy', (await page.locator('.v2-apps-fd__card', { hasText: 'Kiosk Notes' }).getByRole('button').count()) === 0);
putBody = null;
await page.route('**/rest/private/applications/android', (r) => {
  putBody = r.request().postData();
  return r.fulfill(ok({ id: 950, name: 'AntennaPod', pkg: 'de.danoeh.antennapod' }));
});
await page.getByRole('button', { name: 'Add AntennaPod to library' }).click();
check('fdroid add toast', await waitToast('Added to Library'));
check('fdroid add body', !!putBody && putBody.includes('f-droid.org/repo/de.danoeh.antennapod') && putBody.includes('sha256'), putBody);
check('now in library', (await page.locator('.v2-apps-fd__card', { hasText: 'AntennaPod' }).locator('.v2-apps-fd__inlib').count()) === 1);
await page.getByRole('button', { name: 'Deploy Binary Eye' }).click();
let d = page.getByRole('dialog');
await d.waitFor();
check('in-library same version can assign', !(await d.getByRole('tab', { name: 'Add to a configuration' }).isDisabled()));
await d.getByRole('button', { name: 'Cancel' }).click();
await page.getByRole('button', { name: 'Deploy Catima' }).click();
d = page.getByRole('dialog');
await d.waitFor();
check('catalogue-only cannot assign', await d.getByRole('tab', { name: 'Add to a configuration' }).isDisabled());
await d.locator('.v2-deploy__row').first().click();
clearReqs();
await d.getByRole('button', { name: 'Deploy to 1 device' }).click();
await waitToast('1 device queued');
const fdInstall = reqs.find((r) => /commands/.test(r.u));
check('fdroid install sha', !!fdInstall && fdInstall.b.includes('sha256') && fdInstall.b.includes('f-droid.org'), fdInstall?.b?.slice(0, 200));
// search
const searchReq = page.waitForRequest((r) => r.url().includes('/fdroid/search') && r.url().includes('q=termux'));
await page.getByRole('searchbox', { name: 'Search F-Droid' }).fill('termux');
check('search request', !!(await searchReq.catch(() => null)));
// error + retry
await page.route('**/rest/private/fdroid/search*', (r) => r.fulfill(err('error.fdroid.fetch')));
await page.getByRole('searchbox', { name: 'Search F-Droid' }).fill('x');
check('fdroid error', await page.getByText('Could not reach the F-Droid catalogue.').waitFor({ timeout: 3000 }).then(() => true, () => false));

// Escape closes a dialog, focus returns
await page.goto(`${base}/apps`);
const btn = page.getByRole('button', { name: 'Deploy Chrome' });
await btn.click();
await page.getByRole('dialog').waitFor();
await page.keyboard.press('Escape');
await page.waitForTimeout(200);
check('escape closes', (await page.getByRole('dialog').count()) === 0);
check('focus returns', await btn.evaluate((e) => e === document.activeElement));

// ---------------- Review follow-ups (fresh contexts) ----------------
/** A fresh signed-in page with its own error capture; `quietNet` ignores the failed loads a case provokes. */
async function freshPage({ viewport = { width: 1440, height: 1000 }, quietNet = false } = {}) {
  const c = await newContext(browser, fx.user, { theme: 'light', viewport, deviceScaleFactor: 1, blockGoogleFonts: true });
  const p = await c.newPage();
  p.on('console', (m) => {
    if (m.type() !== 'error' || /fonts\.g/.test(m.location()?.url ?? '')) return;
    if (quietNet && m.text().startsWith('Failed to load resource')) return;
    errors.push(m.text());
  });
  p.on('pageerror', (e) => errors.push(String(e)));
  return { c, p };
}
const linkProbe = /\/rest\/private\/applications\/configurations\/(\d+)$/;

// "Used in" is asked once per used app for the life of the page: not again on a tab switch, and after an assign
// only for the app that changed.
{
  const { c, p } = await freshPage();
  const probes = [];
  p.on('request', (r) => {
    const m = new URL(r.url()).pathname.match(linkProbe);
    if (m && r.method() === 'GET') probes.push(Number(m[1]));
  });
  await p.goto(`${base}/apps`);
  await p.locator('table[aria-label="Library"] tbody tr').first().waitFor();
  await p.waitForTimeout(600);
  const first = probes.length;
  check('used-in: each used app asked once', first > 0 && new Set(probes).size === first, JSON.stringify(probes));
  await p.getByRole('link', { name: /Custom APK/ }).click();
  await p.getByText('Add your own app').waitFor();
  await p.getByRole('link', { name: /Library/ }).click();
  await p.locator('table[aria-label="Library"] tbody tr').first().waitFor();
  check('used-in: names shown at once after a tab switch', (await p.getByText('Retail Kiosk, Field Ops').count()) === 1);
  await p.waitForTimeout(600);
  check('used-in: no new requests after a tab switch', probes.length === first, `${probes.length - first} more`);
  let assignedId = null;
  p.on('request', (r) => {
    if (r.method() === 'PUT' || r.method() === 'POST') {
      if (new URL(r.url()).pathname.endsWith('/applications/configurations')) assignedId = JSON.parse(r.postData() || '{}').applicationId ?? null;
    }
  });
  await p.getByRole('button', { name: 'Deploy Chrome' }).click();
  const dd = p.getByRole('dialog');
  await dd.waitFor();
  await dd.getByRole('tab', { name: 'Add to a configuration' }).click();
  await dd.getByLabel('Configuration', { exact: true }).selectOption({ label: 'Default' });
  const beforeAssign = probes.length;
  await dd.getByRole('button', { name: 'Add to configuration' }).click();
  await p.locator('.toast', { hasText: 'Assigned to Default' }).waitFor({ timeout: 8000 }).catch(() => {});
  await p.waitForTimeout(800);
  const after = probes.slice(beforeAssign);
  const again = after.filter((id) => id !== assignedId);
  check('used-in: after an assign that app is asked again, and only that app', assignedId != null && after.includes(assignedId) && again.length === 0, `assigned ${assignedId}, re-asked ${JSON.stringify(after)}`);
  await c.close();
}

// A Deploy still resolving when the user switches tab doesn't open its dialog over the other tab.
{
  const { c, p } = await freshPage();
  await c.route(/\/rest\/private\/applications\/\d+\/versions$/, async (r) => {
    await new Promise((res) => setTimeout(res, 1500));
    await r.fulfill(ok([])).catch(() => {});
  });
  await p.goto(`${base}/apps`);
  await p.locator('table[aria-label="Library"] tbody tr').first().waitFor();
  await p.getByRole('button', { name: 'Deploy Chrome' }).click();
  check('slow deploy: button says Opening…', (await p.getByRole('button', { name: 'Deploy Chrome' }).innerText()).includes('Opening'));
  await p.getByRole('link', { name: /Custom APK/ }).click();
  await p.waitForTimeout(2000);
  check('slow deploy: no dialog after switching tab', (await p.getByRole('dialog').count()) === 0);
  if (await p.getByRole('dialog').count()) await p.keyboard.press('Escape'); // (the failure above) carry on
  await p.getByRole('link', { name: /Library/ }).click();
  await p.locator('table[aria-label="Library"] tbody tr').first().waitFor();
  check('slow deploy: Deploy is usable again', (await p.getByRole('button', { name: 'Deploy Chrome' }).innerText()).trim() === 'Deploy');
  await c.close();
}

// Library load error, then Retry.
{
  const { c, p } = await freshPage({ quietNet: true });
  await c.route('**/rest/private/applications/search', (r) => r.fulfill({ status: 500, body: '' }));
  await p.goto(`${base}/apps`);
  const alert = p.getByText('Could not load the app library.');
  check('library error shown', await alert.waitFor({ timeout: 5000 }).then(() => true, () => false));
  check('library error: no rows, no empty-library message', (await p.locator('table[aria-label="Library"] tbody tr').count()) === 0 && (await p.getByText('No apps in the library yet').count()) === 0);
  await c.unroute('**/rest/private/applications/search');
  await p.getByRole('button', { name: 'Retry' }).click();
  check('library Retry loads it', await p.locator('table[aria-label="Library"] tbody tr').first().waitFor({ timeout: 5000 }).then(() => true, () => false) && (await p.getByText('Could not load the app library.').count()) === 0);
  await c.close();
}

// Phone width: the library is a list of cards, nothing scrolls sideways.
{
  const { c, p } = await freshPage({ viewport: { width: 390, height: 844 } });
  await p.goto(`${base}/apps`);
  await p.locator('.v2-apps-libcard').first().waitFor();
  await p.waitForTimeout(500);
  check('390: library cards, no table', (await p.locator('.v2-apps-libcard').count()) === 8 && (await p.locator('table[aria-label="Library"]').count()) === 0);
  check('390: Used in on the cards', (await p.locator('.v2-apps-libcard', { hasText: 'Retail Kiosk, Field Ops' }).count()) === 1);
  const over = await p.evaluate(() => {
    const W = document.documentElement.clientWidth;
    const out = [...document.querySelectorAll('.v2-apps-libcard, .v2-apps-libcard *')].filter((e) => e.getBoundingClientRect().right > W + 1).length;
    return { sw: document.documentElement.scrollWidth - W, out };
  });
  check('390: no horizontal scroll, nothing past the edge', over.sw <= 0 && over.out === 0, JSON.stringify(over));
  for (const src of ['custom', 'fdroid']) {
    await p.goto(`${base}/apps?source=${src}`);
    await p.waitForTimeout(800);
    const sw = await p.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth);
    check(`390: ${src} has no horizontal scroll`, sw <= 0, `overflow ${sw}px`);
  }
  await c.close();
}

// Keyboard: Tab to the file picker, choose a file; focus stays on the zone while it reads, then lands on the
// result's "Choose another file" (never on <body>), and the reading state is announced.
{
  const { c, p } = await freshPage();
  await c.route('**/rest/private/web-ui-files', async (r) => {
    await new Promise((res) => setTimeout(res, 800));
    await r.fulfill(ok({ serverPath: '/tmp/up.apk', fileDetails: { pkg: 'com.acme.kb', name: 'KB App', version: '1.0', versionCode: 1 } })).catch(() => {});
  });
  await c.route('**/rest/private/web-ui-files/update', (r) => r.fulfill(ok({ url: 'https://mdm.acme.example/files/kb.apk' })));
  await c.route('**/rest/private/applications/android', (r) => r.fulfill(ok({ id: 902, name: 'KB App', pkg: 'com.acme.kb' })));
  await p.goto(`${base}/apps?source=custom`);
  await p.getByText('Add your own app').waitFor();
  let reached = false;
  for (let i = 0; i < 40 && !reached; i++) {
    await p.keyboard.press('Tab');
    reached = await p.evaluate(() => document.activeElement?.textContent?.trim() === 'Choose a file');
  }
  check('keyboard: Tab reaches "Choose a file"', reached);
  await p.locator('input[type=file]').first().setInputFiles({ name: 'kb-app.apk', mimeType: 'application/vnd.android.package-archive', buffer: Buffer.alloc(1000) });
  await p.waitForTimeout(250);
  const during = await p.evaluate(() => ({
    tag: document.activeElement?.tagName,
    text: document.activeElement?.textContent?.trim().slice(0, 40),
    live: [...document.querySelectorAll('.v2-drop [aria-live]')].map((e) => e.textContent).join(''),
  }));
  check('keyboard: focus kept on the zone while reading', during.tag === 'BUTTON' && during.text === 'Choose a file', JSON.stringify(during));
  check('keyboard: reading announced in a live region', /Reading kb-app\.apk/.test(during.live), during.live);
  await p.locator('.toast', { hasText: 'APK ready' }).waitFor({ timeout: 8000 }).catch(() => {});
  await p.waitForTimeout(300);
  const afterRead = await p.evaluate(() => ({ tag: document.activeElement?.tagName, text: document.activeElement?.textContent?.trim().slice(0, 40) }));
  check('keyboard: focus on "Choose another file" once read', afterRead.tag === 'BUTTON' && afterRead.text === 'Choose another file', JSON.stringify(afterRead));
  // Again from the result panel's own button.
  await p.locator('input[type=file]').first().setInputFiles({ name: 'kb-two.apk', mimeType: 'application/vnd.android.package-archive', buffer: Buffer.alloc(1000) });
  await p.waitForTimeout(250);
  const during2 = await p.evaluate(() => document.activeElement?.tagName);
  check('keyboard: second file keeps focus off <body>', during2 === 'BUTTON', during2);
  await p.locator('.toast', { hasText: 'APK ready' }).last().waitFor({ timeout: 8000 }).catch(() => {});
  await p.waitForTimeout(900);
  const after2 = await p.evaluate(() => document.activeElement?.textContent?.trim().slice(0, 40));
  check('keyboard: second read lands on "Choose another file"', after2 === 'Choose another file', after2);
  await c.close();
}

console.log(`\n${pass} passed, ${fail} failed`);
console.log('console errors:', errors.length ? errors : 'none');
await browser.close();
server.close();
process.exit(fail || errors.length ? 1 : 0);
