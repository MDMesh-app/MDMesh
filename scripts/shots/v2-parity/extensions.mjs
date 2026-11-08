// The console extension slots in the real built SPA: one check per slot, against a build that has the test fixture
// extension (web/src/ext/__fixtures__/demo, one contribution of every slot) as a fork's folder. Build it with
// scripts/shots/fixture-build.mjs, or let `run.mjs --with-fixture` build it; a normal build has no fixture, so
// this test fails on one (that is the point of the first check).
//   node scripts/shots/v2-parity/extensions.mjs --dist <fixture build>
import { chromium } from 'playwright';
import { fx, newContext, restData, startServer } from '../harness.mjs';
import { dist, outDir } from './lib.mjs';

const { server, base } = await startServer(dist);
const browser = await chromium.launch();
const results = [];
const errors = [];
const ok = (name, cond, extra = '') => results.push(`${cond ? 'PASS' : 'FAIL'} ${name}${extra ? ` — ${extra}` : ''}`);
const json = (route, data) => route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ status: 'OK', message: null, data }) });

async function open(path, { setup } = {}) {
  const context = await newContext(browser, fx.user, { theme: 'light', viewport: { width: 1440, height: 1000 }, deviceScaleFactor: 1, blockGoogleFonts: true });
  if (setup) await setup(context);
  const page = await context.newPage();
  page.on('console', (m) => { if (m.type() === 'error') errors.push(`${path}: ${m.text()}`); });
  page.on('pageerror', (e) => errors.push(`${path}: pageerror ${e.message}`));
  await page.goto(base + path);
  await page.waitForSelector('.v2-shell .v2-sidebar', { timeout: 10000 });
  return { context, page };
}
const shown = (locator, timeout = 5000) => locator.first().waitFor({ timeout }).then(() => true, () => false);

try {
  // nav.item: the sidebar link, between Devices and Configurations (ORDER.navItem.devices + 50).
  {
    const { context, page } = await open('/');
    const labels = (await page.locator('.v2-sidebar a.v2-navitem').allInnerTexts()).map((t) => t.replace(/\d+/g, '').trim());
    const at = labels.indexOf('Demo');
    ok('nav.item: "Demo" in the sidebar, after Devices and before Configurations',
      at > 0 && labels[at - 1] === 'Devices' && labels[at + 1] === 'Configurations', labels.join(' | '));
    // overview.card: an aside card in the rows, and a stat tile in the fleet summary.
    ok('overview.card (aside): the "Demo card" on the Overview', await shown(page.getByRole('region', { name: 'Demo card' }).or(page.locator('[aria-label="Demo card"]'))));
    ok('overview.card (stat): the "Demo stat" tile with its value', await shown(page.locator('.v2-shell').getByText('Demo stat')) && (await page.getByText('42', { exact: true }).count()) > 0);
    await page.screenshot({ path: `${outDir}/overview.png` });
    await context.close();
  }

  // route.page: the extension's page inside the shell, reached from its nav item.
  {
    const { context, page } = await open('/');
    await page.locator('.v2-sidebar a.v2-navitem', { hasText: 'Demo' }).click();
    ok('route.page: /demo renders the extension page in the shell', await shown(page.getByText('Demo page')) && new URL(page.url()).pathname === '/demo');
    ok('route.page: the page reads the signed-in user (useCurrentUser)', await shown(page.getByText(`Signed in as ${fx.user.login}`)));
    await context.close();
  }

  // device.tab and device.railGroup: the tab among core's (between Telemetry and Activity), ?tab= deep link, useDevice().
  {
    const { context, page } = await open('/devices/WH-SCAN-07?tab=demo');
    await page.waitForSelector('[role="tab"]');
    const tabs = (await page.getByRole('tab').allInnerTexts()).map((t) => t.replace(/\d+/g, '').trim());
    const at = tabs.indexOf('Demo');
    ok('device.tab: "Demo" between Telemetry and Activity', at > 0 && tabs[at - 1] === 'Telemetry' && tabs[at + 1] === 'Activity', tabs.join(' | '));
    ok('device.tab: ?tab=demo opens it', (await page.getByRole('tab', { name: 'Demo', exact: true }).getAttribute('aria-selected')) === 'true');
    ok('device.tab: the body reads the device (useDevice)', await shown(page.getByText('Device WH-SCAN-07')));
    ok('device.railGroup: the "Demo agent" row in the left rail', await shown(page.locator('.v2-device-rail').getByText('Demo agent')));
    await page.screenshot({ path: `${outDir}/device-tab.png` });
    await context.close();
  }

  // config.section: its card and nav entry among core's, and its change in the save bar and the one PUT.
  {
    const { context, page } = await open('/configurations/1', {
      setup: async (c) => {
        await c.route('**/rest/private/configurations/1', (route) =>
          route.request().method() === 'GET'
            ? json(route, { ...restData('GET', '/private/configurations/1', '/configurations/1'), extensions: { demo: { label: 'Front', on: false }, other: { kept: true } } })
            : route.fallback());
        await c.route('**/rest/private/configurations', (route) => {
          if (route.request().method() !== 'PUT') return route.fallback();
          return json(route, JSON.parse(route.request().postData() || '{}'));
        });
      },
    });
    const card = page.locator('section#x-demo-demo');
    ok('config.section: the "Demo" card in the editor', await shown(card.getByRole('heading', { name: 'Demo' })));
    const nav = (await page.getByRole('navigation', { name: 'Sections' }).getByRole('link').allInnerTexts()).map((t) => t.replace(/\d.*$/s, '').trim());
    const at = nav.indexOf('Demo');
    ok('config.section: its nav entry between Network and Security', at > 0 && nav[at - 1] === 'Network' && nav[at + 1] === 'Security', nav.join(' | '));
    const label = card.getByLabel('Demo label');
    ok('config.section: the body gets the stored value', (await label.inputValue()) === 'Front');
    await label.fill('Back');
    const bar = page.getByRole('region', { name: 'Unsaved changes' });
    ok('config.section: an edit joins the save bar', await shown(bar.getByText('1 unsaved change')));
    const putSent = page
      .waitForRequest((r) => r.method() === 'PUT' && new URL(r.url()).pathname.endsWith('/rest/private/configurations'), { timeout: 5000 })
      .catch(() => null);
    await bar.getByRole('button', { name: 'Save and apply' }).click();
    const sentRequest = await putSent;
    const sent = sentRequest ? JSON.parse(sentRequest.postData() || '{}') : null;
    ok('config.section: the save sends the namespace, the others as stored',
      JSON.stringify(sent?.extensions) === JSON.stringify({ demo: { label: 'Back', on: false }, other: { kept: true } }), JSON.stringify(sent?.extensions));
    await context.close();
  }

  // settings.section: its card at #x-demo-demo and its nav entry.
  {
    const { context, page } = await open('/settings#x-demo-demo');
    ok('settings.section: the "Demo settings" card at #x-demo-demo', await shown(page.locator('#x-demo-demo').getByText('Demo settings')));
    ok('settings.section: its nav entry links to #x-demo-demo',
      (await page.getByRole('navigation', { name: 'Settings sections' }).locator('a[href="#x-demo-demo"]').count()) === 1);
    await context.close();
  }

  // list.column, list.rowAction, list.bulkAction on Devices; list.column on Apps.
  {
    const { context, page } = await open('/devices');
    await page.waitForSelector('table[aria-label="Devices"] tbody tr');
    const headers = (await page.locator('table[aria-label="Devices"] thead th').allInnerTexts()).map((t) => t.trim());
    ok('list.column (devices): the "Demo id" header after Android', headers.indexOf('Demo id') === headers.findIndex((h) => /^Android/.test(h)) + 1, headers.join(' | '));
    const device = fx.devices.find((d) => d.number === 'KIOSK-FRONT');
    ok('list.column (devices): its cells', (await page.locator('table[aria-label="Devices"] tbody').getByText(`#${device.id}`, { exact: true }).count()) === 1);
    await page.getByRole('button', { name: 'More actions for KIOSK-FRONT' }).click();
    ok('list.rowAction (devices): "Demo ping" and "Demo note" in the row menu',
      await shown(page.getByRole('menuitem', { name: 'Demo ping' })) && await shown(page.getByRole('menuitem', { name: 'Demo note' })));
    await page.keyboard.press('Escape');
    await page.getByLabel('Select KIOSK-FRONT').check();
    ok('list.bulkAction (devices): "Demo export" in the bulk bar', await shown(page.locator('.v2-bulkbar').getByRole('button', { name: 'Demo export' })));
    await page.screenshot({ path: `${outDir}/devices.png` });
    await context.close();
  }
  {
    const { context, page } = await open('/apps');
    ok('list.column (apps): the "Pkg" header in the library', await shown(page.locator('thead th abbr[title="Package length"]')));
    await context.close();
  }
} catch (e) {
  results.push(`FAIL exception: ${e.stack || e}`);
} finally {
  await browser.close();
  server.close();
}
ok('no console errors or page errors', errors.length === 0, errors.slice(0, 5).join(' / '));
console.log(results.join('\n'));
process.exit(results.some((r) => r.startsWith('FAIL')) ? 1 : 0);
