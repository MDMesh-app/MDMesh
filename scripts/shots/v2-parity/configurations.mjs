// Parity checks for the v2 Configurations list and editor.
// Drives the built SPA against the harness fixtures, with per-test REST overrides, and asserts the classic behaviour.
//
//   node scripts/shots/v2-parity/configurations.mjs [--dist <dir>] [--out <dir>]
import { chromium } from 'playwright';
import assert from 'node:assert/strict';
import { fx, newContext, startServer } from '../harness.mjs';
import { configurationsFixtures, manyAppsFixture } from '../fixtures.mjs';
import { dist, outDir } from './lib.mjs';

const { server, base } = await startServer(dist);
const browser = await chromium.launch();
const results = [];
let failed = 0;

const envelope = (data) => JSON.stringify({ status: 'OK', message: null, data });
const seedConfigs = () =>
  fx.configList.map((c) => ({
    id: c.id,
    name: ['Managed Launcher', 'Background (Agent) Mode', 'MIUI (Xiaomi Redmi)'][c.id - 1],
    description: 'Built-in template',
    kioskMode: c.id === 1,
    password: c.id === 1 ? '12345678' : '',
    defaultKioskPin: c.id === 1,
    mainAppId: null,
    requestUpdates: 'DONOTTRACK',
  }));

const denied = JSON.stringify({ status: 'ERROR', message: 'error.permission.denied', data: null });
const idOf = (r) => Number(new URL(r.request().url()).pathname.split('/').pop());
/** The built-in templates as the list (/search) and as full configurations (GET /configurations/{id}). */
async function seedRoutes(context) {
  const rows = seedConfigs();
  await context.route('**/rest/private/configurations/search', (r) => r.fulfill({ contentType: 'application/json', body: envelope(rows) }));
  await context.route(/\/rest\/private\/configurations\/\d+$/, (r) => {
    if (r.request().method() !== 'GET') return r.fallback();
    const row = rows.find((c) => c.id === idOf(r));
    return r.fulfill({ contentType: 'application/json', body: row ? envelope({ ...row, applications: [], files: [] }) : denied });
  });
}

/**
 * One test in a fresh context. `routes(context, log)` may add REST overrides (they win over the harness: Playwright
 * runs the last registered handler first; call route.fallback() to pass through). `log` records every /rest call.
 */
async function test(name, fn, routes) {
  const context = await newContext(browser, fx.user, { theme: 'light', viewport: { width: 1440, height: 900 }, deviceScaleFactor: 1, blockGoogleFonts: true });
  const log = [];
  const errors = [];
  await context.route('**/rest/**', async (route) => {
    const r = route.request();
    log.push({ method: r.method(), path: new URL(r.url()).pathname.replace(/^\/rest/, ''), body: r.postData() });
    await route.fallback();
  });
  if (routes) await routes(context, log);
  const page = await context.newPage();
  page.on('pageerror', (e) => errors.push(`pageerror: ${e.message}`));
  page.on('console', (m) => {
    if (m.type() === 'error' && !/Failed to load resource/.test(m.text())) errors.push(`console: ${m.text()}`);
  });
  page.on('dialog', (d) => {
    errors.push(`unexpected native dialog: ${d.message()}`);
    void d.dismiss();
  });
  try {
    await fn(page, log);
    assert.deepEqual(errors, [], 'no console errors');
    results.push(`ok   ${name}`);
  } catch (e) {
    failed++;
    results.push(`FAIL ${name}\n     ${String(e.message).split('\n').slice(0, 6).join('\n     ')}`);
    await page.screenshot({ path: `${outDir}/cfg-parity-${name.replace(/\W+/g, '-')}.png`, fullPage: true }).catch(() => {});
  } finally {
    await context.close();
  }
}

const go = (page, p) => page.goto(`${base}${p}`, { waitUntil: 'networkidle' });
const bar = (page) => page.getByRole('region', { name: 'Unsaved changes' });
const group = (page, name) => page.getByRole('radiogroup', { name, exact: true });
const stats = { now: 0, max: 0, done: 0 };
const puts = (log, suffix) => log.filter((l) => l.method === 'PUT' && l.path.endsWith(suffix));

// ---------------------------------------------------------------- list

await test('list: cards, counts, sync line, badges', async (page) => {
  await go(page, '/configurations');
  const cards = page.locator('.v2-configs-card');
  await assert.equal(await cards.count(), 3);
  const kiosk = cards.filter({ hasText: 'Retail Kiosk' });
  assert.match(await kiosk.innerText(), /Kiosk/);
  assert.match(await kiosk.innerText(), /Default PIN/);
  assert.match(await kiosk.innerText(), /Signage Player 2\.7\.1/);
  assert.match(await kiosk.innerText(), /All 3 in sync/);
  const def = cards.filter({ hasText: 'Warehouse scanners' });
  assert.match(await def.innerText(), /Apps\s*2/);
  assert.match(await def.innerText(), /2 of 3 in sync · 1 agent too old/);
  assert.match(await cards.filter({ hasText: 'Field Ops' }).innerText(), /1 of 2 in sync · 1 not reported yet/);
});

await test('list: search, show filter, empty match', async (page) => {
  await go(page, '/configurations');
  await page.getByRole('searchbox', { name: 'Search configurations' }).fill('café');
  assert.equal(await page.locator('.v2-configs-card').count(), 1);
  await page.getByRole('searchbox', { name: 'Search configurations' }).fill('');
  await page.getByRole('radio', { name: /Built-in/ }).click();
  await page.getByText('No configurations match').waitFor();
  await page.getByRole('button', { name: 'Show all configurations' }).click();
  assert.equal(await page.locator('.v2-configs-card').count(), 3);
});

await test('list: built-in cards (seed id + name) are view-only', async (page) => {
  await go(page, '/configurations');
  const card = page.locator('.v2-configs-card').first();
  assert.match(await card.innerText(), /Built-in/);
  assert.match(await card.innerText(), /Ships with MDMesh/);
  await card.getByRole('link', { name: 'View' }).waitFor();
  await card.getByRole('button', { name: 'Duplicate to edit' }).waitFor();
  await card.getByRole('button', { name: /More for/ }).click();
  assert.equal(await page.getByRole('menuitem', { name: /Delete/ }).count(), 0);
  await page.keyboard.press('Escape');
}, seedRoutes);

await test('list: unassigned devices link', async (page) => {
  await go(page, '/configurations');
  const link = page.getByRole('link', { name: /1 device has no configuration · Assign/ });
  assert.equal(await link.getAttribute('href'), '/devices?config=none');
}, async (context) => {
  await context.route('**/rest/private/devices/search', (r) =>
    r.fulfill({ contentType: 'application/json', body: envelope({ configurations: fx.configurations, devices: { items: [...fx.devices, { id: 999, number: 'NEW-1' }], totalItemsCount: 9 } }) }));
});

await test('list: duplicate → copy → opens the copy', async (page, log) => {
  await go(page, '/configurations');
  await page.locator('.v2-configs-card').filter({ hasText: 'Field Ops' }).getByRole('button', { name: 'Duplicate' }).click();
  const dlg = page.getByRole('dialog', { name: 'Duplicate configuration' });
  assert.equal(await dlg.getByRole('textbox', { name: 'New name' }).inputValue(), 'Field Ops copy');
  await dlg.getByRole('textbox', { name: 'New name' }).fill('Field Ops');
  await dlg.getByRole('button', { name: 'Duplicate' }).click();
  await page.waitForURL('**/configurations/3');
  const body = JSON.parse(puts(log, '/configurations/copy')[0].body);
  assert.equal(body.id, 3);
  assert.equal(body.name, 'Field Ops');
});

await test('list: delete confirmation, refusal message', async (page, log) => {
  await go(page, '/configurations');
  await page.getByRole('button', { name: 'More for Field Ops' }).click();
  await page.getByRole('menuitem', { name: /Delete/ }).click();
  const dlg = page.getByRole('alertdialog', { name: 'Delete configuration?' });
  assert.match(await dlg.innerText(), /2 devices still use this configuration/);
  await dlg.getByRole('button', { name: 'Delete' }).click();
  await page.getByText('Devices still use this configuration.').waitFor();
  assert.ok(log.some((l) => l.method === 'DELETE' && l.path.endsWith('/configurations/3')));
}, async (context, log) => {
  await context.route('**/rest/private/configurations/3', (r) =>
    r.request().method() === 'DELETE' && log.push({ method: 'DELETE', path: '/private/configurations/3' })
      ? r.fulfill({ contentType: 'application/json', body: JSON.stringify({ status: 'ERROR', message: 'error.configuration.device.reference.exists', data: null }) })
      : r.fallback());
});

await test('list: new configuration chooser', async (page) => {
  await go(page, '/configurations');
  await page.getByRole('button', { name: 'New configuration' }).first().click();
  const dlg = page.getByRole('dialog', { name: 'New configuration' });
  await dlg.getByRole('button', { name: /Based on “Managed Launcher”/ }).click();
  await page.waitForURL('**/configurations/new?from=1');
  assert.equal(await page.getByRole('textbox', { name: /^Name/ }).inputValue(), 'Managed Launcher copy');
  // the template's assigned apps came along
  await page.locator('.v2-cfged-apps__table tbody tr').nth(1).waitFor();
  assert.equal(await page.locator('.v2-cfged-apps__table tbody tr').count(), 2);
}, seedRoutes);

// ---------------------------------------------------------------- editor

await test('editor: flag Allow/Block, null restored, Changed chip, nav count, discard', async (page) => {
  await go(page, '/configurations/2');
  await page.locator('.v2-cfged-apps__table tbody tr').nth(2).waitFor();
  assert.equal(await bar(page).count(), 0);
  const nav = page.getByRole('navigation', { name: 'Sections' });
  assert.match(await nav.innerText(), /Kiosk\s*Default PIN/);
  // The colour field announces its value with its name.
  assert.equal(await page.getByRole('button', { name: 'Background color: #15181D' }).count(), 1);
  // kioskHome stored null: Allow then Block must restore null (no change)
  await group(page, 'Home button').getByRole('radio', { name: 'Allow' }).click();
  await bar(page).getByText('1 unsaved change').waitFor();
  assert.match(await nav.innerText(), /Kiosk\s*1 changed/);
  await page.getByText('Changed', { exact: true }).first().waitFor();
  await group(page, 'Home button').getByRole('radio', { name: 'Block' }).click();
  await bar(page).waitFor({ state: 'detached' });
  // discard
  await group(page, 'Bluetooth').getByRole('radio', { name: 'Not managed' }).click();
  await bar(page).getByRole('button', { name: 'Discard' }).click();
  await bar(page).waitFor({ state: 'detached' });
  assert.equal(await group(page, 'Bluetooth').getByRole('radio', { name: 'Block' }).getAttribute('aria-checked'), 'true');
});

await test('editor: kiosk change asks, then saves the whole config and the list polls', async (page, log) => {
  await go(page, '/configurations/2');
  await page.locator('.v2-cfged-apps__table tbody tr').nth(2).waitFor();
  await group(page, 'Recents button').getByRole('radio', { name: 'Allow' }).click();
  await group(page, 'Location reporting').getByRole('radio', { name: 'GPS (fresh fix)' }).click();
  assert.match(await bar(page).innerText(), /2 unsaved changes/);
  assert.match(await bar(page).innerText(), /Kiosk settings change on 3 devices/);
  await bar(page).getByRole('button', { name: 'Save and apply' }).click();
  const dlg = page.getByRole('dialog', { name: 'Change kiosk on 3 devices?' });
  assert.match(await dlg.innerText(), /Recents button/);
  await dlg.getByRole('button', { name: 'Save and apply' }).click();
  await page.waitForURL(/\/configurations$/);
  const body = JSON.parse(puts(log, '/private/configurations').at(-1).body);
  assert.equal(body.id, 2);
  assert.equal(body.kioskRecents, true);
  assert.equal(body.kioskHome, null);
  assert.equal(body.requestUpdates, 'GPS');
  assert.equal(body.applications.length, 3);
  assert.equal(body.wifiSSID, 'acme-setup');
  const before = log.filter((l) => l.path.endsWith('/syncSummary')).length;
  const polled = () => log.filter((l) => l.path.endsWith('/syncSummary')).length > before;
  for (let t = 0; t < 30 && !polled(); t++) await page.waitForTimeout(500);
  assert.ok(polled(), 'sync summary polled after save');
});

await test('editor: name required and duplicate name', async (page) => {
  await go(page, '/configurations/3');
  await page.locator('.v2-cfged-apps__table tbody tr').nth(2).waitFor();
  await page.getByRole('textbox', { name: /^Name/ }).fill('');
  await bar(page).getByRole('button', { name: 'Save and apply' }).click();
  await page.locator('.v2-field__error', { hasText: 'Give the configuration a name.' }).waitFor();
  await page.getByRole('textbox', { name: /^Name/ }).fill('Default');
  await bar(page).getByRole('button', { name: 'Save and apply' }).click();
  await page.locator('.v2-field__error', { hasText: 'A configuration with that name exists.' }).waitFor();
}, async (context) => {
  await context.route('**/rest/private/configurations', (r) =>
    r.request().method() === 'PUT'
      ? r.fulfill({ contentType: 'application/json', body: JSON.stringify({ status: 'ERROR', message: 'error.duplicate.configuration', data: null }) })
      : r.fallback());
});

await test('editor: default PIN warning from the server flag, PIN show/hide', async (page) => {
  await go(page, '/configurations/2');
  await page.getByText('This is still a well-known default PIN.').waitFor();
  const pin = page.getByRole('textbox', { name: 'Kiosk exit PIN' }).or(page.locator('input[aria-labelledby$="-password"]'));
  assert.equal(await pin.first().getAttribute('type'), 'password');
  await page.getByRole('button', { name: 'Show' }).first().click();
  assert.equal(await pin.first().getAttribute('type'), 'text');
  await pin.first().fill('86420');
  assert.equal(await page.getByText('This is still a well-known default PIN.').count(), 0);
  await pin.first().fill('');
  await page.getByText('There is no exit PIN').waitFor();
});

await test('editor: apps — add, action, show in kiosk clears main app, kiosk off hides column', async (page) => {
  await go(page, '/configurations/2');
  const rows = page.locator('.v2-cfged-apps__table tbody tr');
  await rows.nth(2).waitFor();
  const main = page.getByRole('combobox', { name: 'Main app' });
  assert.equal(await main.inputValue(), '2710');
  // Chrome is hidden from kiosk, so it is not a main-app choice
  assert.equal(await main.locator('option', { hasText: 'Chrome' }).count(), 0);
  await page.getByRole('checkbox', { name: /in kiosk: Signage Player/ }).uncheck();
  assert.equal(await main.inputValue(), '');
  // action Remove disables Show in kiosk
  await page.getByRole('combobox', { name: 'What happens to Chrome' }).selectOption('2');
  assert.ok(await page.getByRole('checkbox', { name: /in kiosk: Chrome/ }).isDisabled());
  // add an app
  await page.getByRole('button', { name: 'Add apps' }).click();
  const dlg = page.getByRole('dialog', { name: 'Add apps' });
  await dlg.getByRole('checkbox', { name: /Field Ops/ }).check();
  await dlg.getByRole('button', { name: 'Add 1' }).click();
  assert.equal(await rows.count(), 4);
  // remove it again
  await page.getByRole('button', { name: 'Remove Field Ops' }).click();
  assert.equal(await rows.count(), 3);
  // kiosk off: column hidden, kiosk-only rows disabled
  await page.getByRole('switch', { name: 'Kiosk mode' }).click();
  assert.equal(await page.getByRole('columnheader', { name: 'Show in kiosk' }).count(), 0);
  assert.ok(await group(page, 'Home button').getByRole('radio', { name: 'Allow' }).isDisabled());
  assert.ok(await main.isDisabled());
});

await test('editor: Headwind-only section', async (page) => {
  await go(page, '/configurations/1');
  const btn = page.getByRole('button', { name: 'Show settings' });
  assert.equal(await btn.getAttribute('aria-expanded'), 'false');
  await btn.click();
  assert.equal(await page.locator('.v2-cfged-hw__body .v2-fieldrow').count(), 42);
  await group(page, 'Keep screen on').getByRole('radio', { name: 'On' }).click();
  assert.match(await page.getByRole('navigation', { name: 'Sections' }).innerText(), /Headwind only\s*1 changed/);
});

await test('editor: leave guard on links and Back', async (page) => {
  await go(page, '/configurations');
  await page.locator('.v2-configs-card').filter({ hasText: 'Field Ops' }).getByRole('link', { name: 'Edit' }).click();
  await page.waitForURL('**/configurations/3');
  await page.locator('.v2-cfged-apps__table tbody tr').nth(2).waitFor();
  await group(page, 'Bluetooth').getByRole('radio', { name: 'Block' }).click();
  await page.getByRole('link', { name: 'Devices', exact: false }).first().click();
  const dlg = page.getByRole('alertdialog', { name: 'Leave without saving?' });
  await dlg.getByRole('button', { name: 'Keep editing' }).click();
  assert.match(page.url(), /\/configurations\/3$/);
  await page.goBack();
  await dlg.waitFor();
  await dlg.getByRole('button', { name: 'Keep editing' }).click();
  assert.match(page.url(), /\/configurations\/3$/);
  assert.match(await bar(page).innerText(), /1 unsaved change/);
  await page.goBack();
  await dlg.getByRole('button', { name: 'Leave without saving' }).click();
  await page.waitForURL(/\/configurations$/);
});

await test('editor: guard — undo by hand, skip link, first entry, save then Back', async (page) => {
  // edits undone by hand: one Back leaves
  await go(page, '/configurations');
  await page.locator('.v2-configs-card').filter({ hasText: 'Field Ops' }).getByRole('link', { name: 'Edit' }).click();
  await page.locator('.v2-cfged-apps__table tbody tr').nth(2).waitFor();
  await group(page, 'Bluetooth').getByRole('radio', { name: 'Block' }).click();
  await bar(page).waitFor();
  await group(page, 'Bluetooth').getByRole('radio', { name: 'Not managed' }).click();
  await bar(page).waitFor({ state: 'detached' });
  await page.waitForTimeout(300);
  await page.goBack();
  await page.waitForURL(/\/configurations$/);
  // skip link does not read as Back
  await page.locator('.v2-configs-card').filter({ hasText: 'Field Ops' }).getByRole('link', { name: 'Edit' }).click();
  await page.locator('.v2-cfged-apps__table tbody tr').nth(2).waitFor();
  await group(page, 'Bluetooth').getByRole('radio', { name: 'Block' }).click();
  await page.locator('a.v2-skip').focus();
  await page.keyboard.press('Enter');
  await page.waitForTimeout(300);
  assert.equal(await page.getByRole('alertdialog').count(), 0);
  await page.goBack();
  await page.getByRole('alertdialog', { name: 'Leave without saving?' }).waitFor();
  await page.getByRole('button', { name: 'Keep editing' }).click();
  // save, then Back from the list does not reopen the editor
  await bar(page).getByRole('button', { name: 'Save and apply' }).click();
  await page.waitForURL(/\/configurations$/);
  await page.goBack();
  await page.waitForTimeout(300);
  assert.match(page.url(), /\/configurations$/);
});

await test('editor: guard — Back on the tab\'s first entry gets the browser\'s leave prompt', async (page) => {
  // Back from the tab's first entry leaves the console, which only the browser can hold (beforeunload).
  await go(page, '/configurations/3');
  await page.locator('.v2-cfged-apps__table tbody tr').nth(2).waitFor();
  await group(page, 'Bluetooth').getByRole('radio', { name: 'Block' }).click();
  await page.waitForTimeout(200);
  page.removeAllListeners('dialog');
  let asked = null;
  page.once('dialog', (d) => {
    asked = d.type();
    void d.dismiss();
  });
  await page.goBack({ timeout: 3000 }).catch(() => undefined);
  assert.equal(asked, 'beforeunload');
  assert.match(page.url(), /\/configurations\/3$/);
  assert.match(await bar(page).innerText(), /1 unsaved change/);
});

await test('editor: built-in template is read-only, Duplicate to edit is the server copy', async (page, log) => {
  await go(page, '/configurations/1');
  await page.getByText('This is a built-in template, so it is view only.').waitFor();
  assert.ok(await page.getByRole('textbox', { name: /^Name/ }).isDisabled());
  assert.ok(await group(page, 'Home button').getByRole('radio', { name: 'Allow' }).isDisabled());
  assert.equal(await page.getByRole('button', { name: 'Add apps' }).count(), 0);
  assert.equal(await bar(page).count(), 0);
  await page.getByText('Devices on this template use a well-known default PIN').waitFor();
  await page.getByRole('button', { name: 'Duplicate to edit' }).click();
  const dlg = page.getByRole('dialog', { name: 'Duplicate configuration' });
  assert.equal(await dlg.getByRole('textbox', { name: 'New name' }).inputValue(), 'Managed Launcher copy');
  await dlg.getByRole('button', { name: 'Duplicate' }).click();
  await page.waitForURL('**/configurations/7');
  const body = JSON.parse(puts(log, '/configurations/copy')[0].body);
  assert.equal(body.id, 1);
  assert.equal(body.name, 'Managed Launcher copy');
  // the copy is made by the server (it copies files and app settings too), never re-created in the browser
  assert.equal(puts(log, '/private/configurations').length, 0);
}, async (context, log) => {
  await seedRoutes(context);
  let copied = false;
  await context.route('**/rest/private/configurations/copy', (r) => {
    log.push({ method: 'PUT', path: '/private/configurations/copy', body: r.request().postData() });
    copied = true;
    return r.fulfill({ contentType: 'application/json', body: envelope(null) });
  });
  await context.route('**/rest/private/configurations/search', (r) =>
    r.fulfill({ contentType: 'application/json', body: envelope([...seedConfigs(), ...(copied ? [{ id: 7, name: 'Managed Launcher copy' }] : [])]) }));
});

await test('editor: blank new configuration', async (page, log) => {
  await go(page, '/configurations/new');
  await page.getByRole('button', { name: 'Create configuration' }).first().click();
  await page.locator('.v2-field__error', { hasText: 'Give the configuration a name.' }).waitFor();
  await page.getByRole('textbox', { name: /^Name/ }).fill('Lobby');
  await bar(page).getByRole('button', { name: 'Create configuration' }).click();
  await page.waitForURL(/\/configurations$/);
  const body = JSON.parse(puts(log, '/private/configurations').at(-1).body);
  assert.equal(body.name, 'Lobby');
  assert.equal(body.pushOptions, 'mqttWorker');
  assert.deepEqual(body.applications, []);
});

await test('editor: assigned apps fail to load → save blocked, Retry', async (page) => {
  await go(page, '/configurations/2');
  await page.getByText('Could not load this configuration’s assigned apps.').waitFor();
  await group(page, 'Bluetooth').getByRole('radio', { name: 'Allow' }).click();
  assert.ok(await bar(page).getByRole('button', { name: 'Save and apply' }).isDisabled());
  assert.match(await bar(page).innerText(), /Saving is off until the assigned apps load/);
}, async (context) => {
  await context.route('**/rest/private/configurations/applications/2', (r) => r.fulfill({ status: 500, body: '' }));
});

await test('editor: unknown id', async (page) => {
  await go(page, '/configurations/999');
  await page.getByText('Configuration not found').waitFor();
});

await test('editor: 390px has no horizontal page scroll', async (page) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await go(page, '/configurations/2');
  await page.locator('.v2-cfged-apps__table tbody tr').nth(2).waitFor();
  const w = await page.evaluate(() => document.documentElement.scrollWidth);
  assert.ok(w <= 390, `scrollWidth ${w}`);
  await go(page, '/configurations');
  assert.ok((await page.evaluate(() => document.documentElement.scrollWidth)) <= 390);
});

// ---------------------------------------------------------------- edge cases

/** The assigned apps as the editor keeps them (api/configurations getConfigurationApps), from the server's rows. */
// The assigned rows exactly as the server sent them, minus the matrix's `selected` flag (every other column,
// the launcher layout included, must go back unchanged).
const assignedFrom = (rows) =>
  rows.filter((a) => a.selected).map(({ selected, ...rest }) => JSON.parse(JSON.stringify(rest)));

await test('editor: save sends back the full configuration plus the edits (files, app settings, unknown key)', async (page, log) => {
  const full = page.waitForResponse((r) => /\/rest\/private\/configurations\/3$/.test(r.url()) && r.request().method() === 'GET');
  const appRows = page.waitForResponse((r) => /\/rest\/private\/configurations\/applications\/3$/.test(r.url()));
  await go(page, '/configurations/3');
  const loaded = (await (await full).json()).data;
  const assigned = assignedFrom((await (await appRows).json()).data);
  // the fixture really carries what /search lacks
  assert.equal(loaded.files.length, 1);
  assert.equal(loaded.applicationSettings.length, 1);
  assert.equal(loaded.applicationUsageParameters.length, 1);
  assert.ok(loaded.futureServerSetting);
  assert.ok(assigned.some((a) => a.keyCode === 131 && a.screenOrder === 3) && assigned.some((a) => a.bottom === true), 'app rows carry the launcher layout');
  await page.locator('.v2-cfged-apps__table tbody tr').nth(2).waitFor();
  // edits: name (saved trimmed), a policy, a Headwind-only field, an app's action
  await page.getByRole('textbox', { name: /^Name/ }).fill('  Field Ops North  ');
  await group(page, 'Bluetooth').getByRole('radio', { name: 'Block' }).click();
  await page.getByRole('button', { name: 'Show settings' }).click();
  await group(page, 'Keep screen on').getByRole('radio', { name: 'On' }).click();
  await page.getByRole('combobox', { name: 'What happens to Chrome' }).selectOption('0');
  assert.match(await bar(page).innerText(), /4 unsaved changes/);
  await bar(page).getByRole('button', { name: 'Save and apply' }).click();
  await page.waitForURL(/\/configurations$/);
  const body = JSON.parse(puts(log, '/private/configurations').at(-1).body);
  const expected = {
    ...loaded,
    name: 'Field Ops North',
    bluetooth: false,
    kioskScreenOn: true,
    applications: assigned.map((a) => (a.name === 'Chrome' ? { ...a, action: 0 } : a)),
  };
  assert.deepEqual(body, expected);
});

await test('editor: Duplicate is the server copy and opens it (unsaved edits not copied, guard asks)', async (page, log) => {
  await go(page, '/configurations/3');
  await page.locator('.v2-cfged-apps__table tbody tr').nth(2).waitFor();
  await group(page, 'Bluetooth').getByRole('radio', { name: 'Block' }).click();
  await page.getByRole('button', { name: 'Duplicate', exact: true }).click();
  const dlg = page.getByRole('dialog', { name: 'Duplicate configuration' });
  await dlg.getByText('Unsaved changes on this page are not copied.').waitFor();
  await dlg.getByRole('textbox', { name: 'New name' }).fill('Field Ops South');
  await dlg.getByRole('button', { name: 'Duplicate' }).click();
  // Escape while the copy runs does not close the dialog
  await dlg.getByRole('button', { name: 'Copying…' }).waitFor();
  await page.keyboard.press('Escape');
  assert.equal(await dlg.isVisible(), true);
  const leave = page.getByRole('alertdialog', { name: 'Leave without saving?' });
  await leave.getByRole('button', { name: 'Leave without saving' }).click();
  await page.waitForURL('**/configurations/8');
  const copies = log.filter((l) => l.path === '/private/configurations/copy');
  assert.equal(copies.length, 1);
  assert.deepEqual(JSON.parse(copies[0].body), { id: 3, name: 'Field Ops South', description: 'Field tablets on the road. GPS location reporting, screenshots blocked.' });
  assert.equal(puts(log, '/private/configurations').length, 0);
}, async (context, log) => {
  let copied = false;
  await context.route('**/rest/private/configurations/copy', async (r) => {
    log.push({ method: 'PUT', path: '/private/configurations/copy', body: r.request().postData() });
    await new Promise((res) => setTimeout(res, 800));
    copied = true;
    return r.fulfill({ contentType: 'application/json', body: envelope(null) });
  });
  // after the copy, the list has it (the lookup by name is all that reads this answer)
  await context.route('**/rest/private/configurations/search', (r) =>
    copied ? r.fulfill({ contentType: 'application/json', body: envelope([{ id: 8, name: 'Field Ops South' }]) }) : r.fallback());
});

await test('editor: a removed or uninstalled main app is cleared', async (page) => {
  await go(page, '/configurations/2');
  await page.locator('.v2-cfged-apps__table tbody tr').nth(2).waitFor();
  const main = page.getByRole('combobox', { name: 'Main app' });
  assert.equal(await main.inputValue(), '2710');
  await page.getByRole('combobox', { name: 'What happens to Signage Player' }).selectOption('0');
  assert.equal(await main.inputValue(), '');
  await bar(page).getByRole('button', { name: 'Discard' }).click();
  assert.equal(await main.inputValue(), '2710');
  await page.getByRole('button', { name: 'Remove Signage Player' }).click();
  assert.equal(await main.inputValue(), '');
});

await test('editor: app library fails → error with Retry, Add apps off; Retry recovers', async (page) => {
  await go(page, '/configurations/3');
  await page.locator('.v2-cfged-apps__table tbody tr').nth(2).waitFor();
  const callout = page.getByText('Could not load the app library');
  await callout.waitFor();
  assert.ok(await page.getByRole('button', { name: 'Add apps' }).isDisabled());
  await page.unroute('**/rest/private/applications/search');
  await page.locator('.v2-cfged-libnote').getByRole('button', { name: 'Retry' }).click();
  await callout.waitFor({ state: 'detached' });
  assert.equal(await page.getByRole('button', { name: 'Add apps' }).isDisabled(), false);
}, async (context) => {
  // page-level route so the test can lift it (context routes stay)
  context.on('page', (p) => void p.route('**/rest/private/applications/search', (r) => r.fulfill({ status: 500, body: '' })));
});

await test('list: app library fails → error with Retry', async (page) => {
  await go(page, '/configurations');
  await page.getByText('Could not load the app library').waitFor();
}, async (context) => {
  await context.route('**/rest/private/applications/search', (r) => r.fulfill({ status: 500, body: '' }));
});

await test('list: per-configuration app requests are capped at 4 in flight; delete keeps the fetched ones', async (page, log) => {
  await go(page, '/configurations');
  await page.locator('.v2-configs-card').nth(11).waitFor();
  for (let t = 0; t < 40 && stats.done < 12; t++) await page.waitForTimeout(250);
  assert.equal(stats.done, 12);
  assert.ok(stats.max <= 4, `max in flight ${stats.max}`);
  assert.ok(stats.max >= 2, `requests ran in parallel (max ${stats.max})`);
  const before = stats.done;
  await page.getByRole('button', { name: 'More for Lobby 5' }).click();
  await page.getByRole('menuitem', { name: /Delete/ }).click();
  await page.getByRole('alertdialog', { name: 'Delete configuration?' }).getByRole('button', { name: 'Delete' }).click();
  await page.getByText('Configuration deleted').waitFor();
  await page.waitForTimeout(600);
  assert.equal(stats.done, before, 'no app list refetched after a delete');
}, async (context) => {
  const rows = Array.from({ length: 12 }, (_, i) => ({ id: 20 + i, name: `Lobby ${i}`, mainAppId: null }));
  let deleted = false;
  await context.route('**/rest/private/configurations/search', (r) =>
    r.fulfill({ contentType: 'application/json', body: envelope(deleted ? rows.filter((c) => c.id !== 25) : rows) }));
  await context.route(/\/rest\/private\/configurations\/25$/, (r) => {
    if (r.request().method() !== 'DELETE') return r.fallback();
    deleted = true;
    return r.fulfill({ contentType: 'application/json', body: envelope(null) });
  });
  await context.route(/\/rest\/private\/configurations\/applications\/\d+$/, async (r) => {
    stats.now++;
    stats.max = Math.max(stats.max, stats.now);
    await new Promise((res) => setTimeout(res, 150));
    stats.now--;
    stats.done++;
    return r.fulfill({ contentType: 'application/json', body: envelope([]) });
  });
});

await test('editor: save bar when agents are too old for configuration sync', async (page) => {
  // configuration 1: 1 of 3 devices has an agent too old
  await go(page, '/configurations/1');
  await page.locator('.v2-cfged-apps__table tbody tr').nth(1).waitFor();
  await group(page, 'Bluetooth').getByRole('radio', { name: 'Block' }).click();
  assert.match(await bar(page).innerText(), /pick up the changes on their next sync, except 1 device that has an agent too old/);
  await bar(page).getByRole('button', { name: 'Discard' }).click();
  await bar(page).waitFor({ state: 'detached' });
  // configuration 3: every device too old (overridden)
  await go(page, '/configurations/3');
  await page.locator('.v2-cfged-apps__table tbody tr').nth(2).waitFor();
  await group(page, 'Bluetooth').getByRole('radio', { name: 'Block' }).click();
  assert.match(await bar(page).innerText(), /All 2 devices using this configuration have agents too old for configuration sync, so they do not pick up changes/);
}, async (context) => {
  await context.route('**/rest/private/agent/v1/configurations/syncSummary', (r) => r.fulfill({
    contentType: 'application/json',
    body: envelope([
      { configurationId: 1, total: 3, inSync: 2, outOfSync: 0, unsupported: 1, neverSeen: 0, deferred: 0 },
      { configurationId: 3, total: 2, inSync: 0, outOfSync: 0, unsupported: 2, neverSeen: 0, deferred: 0 },
    ]),
  }));
});

await test('editor: ids like 1e1 or 0x2 are not found', async (page, log) => {
  await go(page, '/configurations/0x2');
  await page.getByText('Configuration not found').waitFor();
  await go(page, '/configurations/1e1');
  await page.getByText('Configuration not found').waitFor();
  assert.equal(log.filter((l) => /\/configurations\/\d+$/.test(l.path)).length, 0);
});

await test('editor: 390px stacks the app rows (select and Remove on screen)', async (page) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await go(page, '/configurations/2');
  await page.locator('.v2-cfged-apps__table tbody tr').nth(2).waitFor();
  const box = page.locator('.v2-cfged-apps');
  const [sw, cw] = await box.evaluate((el) => [el.scrollWidth, el.clientWidth]);
  assert.ok(sw <= cw, `apps table scrolls inside itself (${sw} > ${cw})`);
  for (const name of ['What happens to Chrome', 'Remove Chrome']) {
    const el = name.startsWith('Remove') ? page.getByRole('button', { name }) : page.getByRole('combobox', { name });
    const b = await el.boundingBox();
    assert.ok(b && b.x >= 0 && b.x + b.width <= 390, `${name} on screen (${JSON.stringify(b)})`);
  }
  await page.getByRole('checkbox', { name: /in kiosk: Chrome/ }).waitFor();
  await page.screenshot({ path: `${outDir}/cfg-parity-editor-390-apps.png`, fullPage: true });
});

// ---------------------------------------------------------------- a long app list: search, filter, sort

const many = manyAppsFixture(configurationsFixtures(fx));
/** Configuration 4, with 14 assigned apps. */
async function manyRoutes(context) {
  await context.route(/\/rest\/private\/configurations\/4$/, (r) =>
    r.request().method() === 'GET' ? r.fulfill({ contentType: 'application/json', body: envelope(many.config) }) : r.fallback());
  await context.route(/\/rest\/private\/configurations\/applications\/4$/, (r) =>
    r.fulfill({ contentType: 'application/json', body: envelope(many.apps) }));
}
const appNames = (page) => page.locator('.v2-cfged-apps__table .v2-cfged-app__name').evaluateAll((els) => els.map((e) => e.firstChild.textContent));
const appSearch = (page) => page.getByRole('searchbox', { name: 'Search assigned apps' });
const onDevices = (page) => page.getByRole('radiogroup', { name: 'On devices', exact: true });

await test('editor: a short app list has no search, filter or sort', async (page) => {
  await go(page, '/configurations/2');
  await page.locator('.v2-cfged-apps__table tbody tr').nth(2).waitFor();
  assert.equal(await appSearch(page).count(), 0);
  assert.equal(await onDevices(page).count(), 0);
  assert.equal(await page.getByRole('combobox', { name: 'Sort' }).count(), 0);
});

await test('editor: a long app list — search, On devices filter with counts, sort, no match', async (page) => {
  await go(page, '/configurations/4');
  await appSearch(page).waitFor();
  assert.deepEqual(await onDevices(page).getByRole('radio').allInnerTexts(), ['All 14', 'Install 8', 'Do not install 3', 'Remove 3']);
  const names = await appNames(page);
  assert.equal(names.length, 14);
  assert.deepEqual(names, [...names].sort((a, b) => a.localeCompare(b)), 'sorted by name');
  await appSearch(page).fill('  ACME.C ');
  assert.deepEqual(await appNames(page), ['Camera']);
  await page.getByText('Showing 1 of 14 apps').waitFor({ state: 'attached' });
  await appSearch(page).fill('');
  await onDevices(page).getByRole('radio', { name: /^Remove/ }).click();
  assert.deepEqual(await appNames(page), ['Barcode Reader', 'Old Launcher', 'Wallet']);
  await page.getByRole('combobox', { name: 'Sort' }).selectOption('pkg');
  assert.deepEqual(await appNames(page), ['Old Launcher', 'Wallet', 'Barcode Reader']);
  await appSearch(page).fill('chrome');
  await page.getByText('No apps match').waitFor();
  await page.getByRole('button', { name: 'Clear', exact: true }).click();
  assert.equal((await appNames(page)).length, 14);
  assert.equal(await appSearch(page).inputValue(), '');
}, manyRoutes);

await test('editor: a long app list — an edit under a filter stays visible and saves every app', async (page, log) => {
  await go(page, '/configurations/4');
  await appSearch(page).waitFor();
  await onDevices(page).getByRole('radio', { name: /^Install/ }).click();
  await page.getByRole('combobox', { name: 'What happens to Chrome' }).selectOption('2');
  assert.ok((await appNames(page)).includes('Chrome'), 'the edited row stays');
  assert.deepEqual(await onDevices(page).getByRole('radio').allInnerTexts(), ['All 14', 'Install 7', 'Do not install 3', 'Remove 4']);
  await bar(page).getByText('1 unsaved change').waitFor();
  await onDevices(page).getByRole('radio', { name: /^Do not install/ }).click();
  assert.deepEqual(await appNames(page), ['Camera', 'Microsoft Teams', 'Time Clock']);
  await bar(page).getByRole('button', { name: 'Save and apply' }).click();
  await page.waitForURL(/\/configurations$/);
  const body = JSON.parse(puts(log, '/private/configurations').at(-1).body);
  assert.deepEqual(body.applications, assignedFrom(many.apps).map((a) => (a.name === 'Chrome' ? { ...a, action: 2 } : a)));
}, manyRoutes);

await test('editor: a long app list at 390px — controls on screen, no horizontal page scroll', async (page) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await go(page, '/configurations/4');
  await appSearch(page).waitFor();
  for (const el of [appSearch(page), onDevices(page), page.getByRole('combobox', { name: 'Sort' })]) {
    const b = await el.boundingBox();
    assert.ok(b && b.x >= 0 && b.x + b.width <= 390, `on screen (${JSON.stringify(b)})`);
  }
  assert.ok((await page.evaluate(() => document.documentElement.scrollWidth)) <= 390);
  await page.screenshot({ path: `${outDir}/cfg-parity-editor-390-many-apps.png`, fullPage: true });
}, manyRoutes);

await browser.close();
server.close();
console.log(results.join('\n'));
console.log(failed ? `\n${failed} failed` : `\nall ${results.length} passed`);
process.exit(failed ? 1 : 0);
