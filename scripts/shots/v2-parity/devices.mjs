// Parity walk-through of /devices against the shot fixtures (plus a few route overrides).
//   node scripts/shots/v2-parity/devices.mjs [--dist <dir>]
import { chromium } from 'playwright';
import { fx, newContext, startServer } from '../harness.mjs';
import { dist } from './lib.mjs';
const { server, base } = await startServer(dist);
const browser = await chromium.launch();
const results = [];
const ok = (name, cond, extra = '') => {
  results.push(`${cond ? 'PASS' : 'FAIL'} ${name}${extra ? ' — ' + extra : ''}`);
};

async function ctx({ devices, viewport, search } = {}) {
  const context = await newContext(browser, fx.user, { theme: 'light', viewport: viewport ?? { width: 1440, height: 1000 }, deviceScaleFactor: 1, blockGoogleFonts: true });
  const calls = [];
  // Search: answer like the server (filter by value), optionally with a bigger fleet.
  await context.route('**/rest/private/devices/search', async (route) => {
    const body = JSON.parse(route.request().postData() || '{}');
    const all = devices ?? fx.devices;
    const v = (body.value || '').toLowerCase();
    let items = v ? all.filter((d) => `${d.number} ${d.description ?? ''}`.toLowerCase().includes(v)) : all;
    calls.push({ m: 'POST', p: '/private/devices/search', body });
    // A test's own answer for some values ({ items?, delay? }): out-of-order responses, server-only matches.
    const custom = search?.(v);
    if (custom?.items) items = custom.items;
    if (custom?.delay) await new Promise((r) => setTimeout(r, custom.delay));
    await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ status: 'OK', data: { configurations: fx.configurations, devices: { items, totalItemsCount: items.length } } }) });
  });
  const page = await context.newPage();
  const errors = [];
  page.on('console', (m) => { if (m.type() === 'error' && !/fonts\.g|ERR_FAILED/.test(m.text() + (m.location()?.url ?? ''))) errors.push(m.text() + ' @ ' + (m.location()?.url ?? '')); });
  page.on('pageerror', (e) => errors.push(String(e)));
  page.on('request', (r) => {
    const u = new URL(r.url());
    if (u.pathname.startsWith('/rest/') && r.method() !== 'GET' && !u.pathname.endsWith('/devices/search'))
      calls.push({ m: r.method(), p: u.pathname.replace(/^\/rest/, ''), body: r.postData() });
  });
  return { context, page, errors, calls };
}

const last = (calls, re) => [...calls].reverse().find((c) => re.test(c.p));

// ---------- table view ----------
{
  const { context, page, errors, calls } = await ctx();
  await page.goto(`${base}/devices`);
  await page.waitForSelector('table[aria-label="Devices"] tbody tr');
  await page.waitForTimeout(600);
  const rows = () => page.locator('table[aria-label="Devices"] tbody tr').count();
  ok('table renders 8 rows', (await rows()) === 8);
  const sub = await page.locator('.v2-pagehead__sub').innerText();
  ok('subtitle counts', /3 of 8 online now, 2 need attention, 3 haven’t checked in\./.test(sub), sub);
  ok('attention strip chips', (await page.getByRole('button', { name: '1 config failed' }).count()) === 1 && (await page.getByRole('button', { name: '1 agent too old' }).count()) === 1);

  // status filter
  await page.getByRole('radio', { name: /Attention/ }).click(); await page.waitForTimeout(150);
  ok('Attention filter → 2', (await rows()) === 2);
  const attnText = await page.locator('table[aria-label="Devices"] tbody').innerText();
  ok('attention reasons in the shared wording', attnText.includes('Configuration failed to apply') && attnText.includes('Agent too old for configuration sync'), attnText.replace(/\s+/g, ' ').slice(0, 200));
  await page.getByRole('radio', { name: /Offline/ }).click(); await page.waitForTimeout(150);
  ok('Offline filter → 3', (await rows()) === 3);
  await page.getByRole('radio', { name: /Online/ }).click(); await page.waitForTimeout(150);
  ok('Online filter → 3', (await rows()) === 3);
  await page.getByRole('radio', { name: /^All/ }).click(); await page.waitForTimeout(150);
  await page.getByRole('button', { name: '1 config failed' }).click();
  ok('chip filter → 1 (KIOSK-CAFE)', (await rows()) === 1 && (await page.locator('tbody').innerText()).includes('KIOSK-CAFE'));
  await page.getByRole('button', { name: '1 config failed' }).click();
  await page.getByRole('radio', { name: /^All/ }).click(); await page.waitForTimeout(150);

  // selects
  await page.getByLabel('Configuration').selectOption('2');
  await page.waitForTimeout(200);
  ok('config filter by id + URL', (await rows()) === 3 && page.url().includes('config=2'), `${await rows()} ${page.url()}`);
  await page.getByLabel('Configuration').selectOption('all');
  ok('config filter cleared from URL', !page.url().includes('config='));
  await page.getByLabel('Android').selectOption('14');
  ok('android filter', (await rows()) === 2);
  await page.getByLabel('Android').selectOption('all');
  await page.getByLabel('Type').selectOption('kiosk');
  ok('type filter (kiosk from kioskMode)', (await rows()) === 3);
  await page.getByLabel('Type').selectOption('all');
  ok('toolbar selects are named by their label alone', (await page.getByRole('combobox', { name: 'Type', exact: true }).count()) === 1 && (await page.getByRole('combobox', { name: 'Android', exact: true }).count()) === 1);

  // search: instant client filter, then server
  await page.getByLabel('Search devices').fill('scan');
  await page.waitForTimeout(800);
  ok('search → 3 scanners', (await rows()) === 3);
  ok('server search sent value', !!calls.find((c) => c.p.endsWith('/devices/search') && c.body?.value === 'scan'));
  await page.getByLabel('Search devices').fill('zzz');
  await page.waitForTimeout(800);
  ok('search empty state', (await page.getByText('No devices match this search').count()) === 1);
  await page.getByRole('button', { name: 'Clear search' }).click();
  await page.waitForTimeout(800);
  ok('clear search restores', (await rows()) === 8);

  // sort
  const seenTh = page.locator('th', { hasText: 'Last seen' });
  ok('default sort Last seen desc', (await seenTh.getAttribute('aria-sort')) === 'descending');
  await page.getByRole('button', { name: 'Device', exact: true }).click();
  const firstName = await page.locator('tbody tr').first().locator('.v2-devid__name').innerText();
  ok('sort by name asc', firstName === 'Café menu board', firstName);
  await page.getByRole('button', { name: /Last seen/ }).click();

  // row click → detail
  await page.locator('tbody tr').first().locator('td').nth(4).click();
  ok('row click opens /devices/:number', /\/devices\/KIOSK-FRONT$/.test(page.url()), page.url());
  await page.goBack();
  await page.waitForSelector('table[aria-label="Devices"] tbody tr');

  // per-row sync + menu
  await page.getByRole('button', { name: 'Sync KIOSK-FRONT now' }).click();
  await page.waitForTimeout(300);
  ok('row Sync → POST sync', !!last(calls, /\/devices\/KIOSK-FRONT\/sync$/));
  await page.getByRole('button', { name: 'More actions for KIOSK-FRONT' }).click();
  await page.getByRole('menuitem', { name: 'Lock device' }).click();
  await page.waitForTimeout(300);
  const lock = last(calls, /\/devices\/KIOSK-FRONT\/commands$/);
  ok('menu Lock → device.lock', !!lock && lock.body.includes('device.lock'));
  await page.getByRole('button', { name: 'More actions for KIOSK-FRONT' }).click();
  await page.getByRole('menuitem', { name: 'Ring device' }).click();
  await page.waitForTimeout(300);
  ok('menu Ring → device.ring', last(calls, /\/devices\/KIOSK-FRONT\/commands$/)?.body.includes('device.ring'));
  await page.getByRole('button', { name: 'More actions for KIOSK-FRONT' }).click();
  await page.getByRole('menuitem', { name: 'Delete…' }).click();
  ok('menu Delete opens confirm', (await page.getByRole('alertdialog').count()) === 1);
  await page.getByRole('button', { name: 'Delete KIOSK-FRONT' }).click();
  await page.waitForTimeout(300);
  const del1 = last(calls, /deleteBulk$/);
  ok('single delete → deleteBulk [102]', del1 && JSON.parse(del1.body).ids.join() === '102', del1?.body);

  // selection
  await page.getByLabel('Select KIOSK-CAFE').check();
  ok('bulk bar shows', (await page.getByText('1 selected').count()) === 1);
  // row click while selecting toggles
  await page.locator('tr', { hasText: 'WH-SCAN-07' }).locator('td').nth(4).click();
  ok('row click toggles while selecting', (await page.getByText('2 selected').count()) === 1 && page.url().endsWith('/devices'));
  await page.getByRole('button', { name: 'Select all 8' }).click();
  ok('Select all N', (await page.getByText('8 selected').count()) === 1);
  await page.getByRole('button', { name: 'Clear', exact: true }).click();
  ok('Clear', (await page.locator('.v2-bulkbar').count()) === 0);
  await page.getByLabel('Select all on this page').check();
  ok('header checkbox selects page', (await page.getByText('8 selected').count()) === 1);

  // Run action: immediate (Ring), confirm (Reboot), params (Send alert)
  await page.getByRole('button', { name: 'Run action' }).click();
  ok('run modal title', (await page.getByRole('dialog', { name: 'Run action on 8 devices' }).count()) === 1);
  ok('destructive actions excluded', (await page.getByRole('button', { name: 'Factory reset (wipe)' }).count()) === 0 && (await page.getByRole('button', { name: 'Reset passcode' }).count()) === 0);
  await page.getByRole('button', { name: 'Reboot device' }).click();
  ok('Reboot asks to confirm', (await page.getByRole('button', { name: 'Run on 8' }).count()) === 1);
  await page.getByRole('button', { name: 'Run on 8' }).click();
  await page.waitForTimeout(300);
  let bulk = last(calls, /\/bulk\/commands$/);
  ok('reboot bulk queued', bulk && JSON.parse(bulk.body).command.type === 'device.reboot' && JSON.parse(bulk.body).deviceIds.length === 8);
  ok('selection cleared after run', (await page.locator('.v2-bulkbar').count()) === 0);

  await page.getByLabel('Select all on this page').check();
  await page.getByRole('button', { name: 'Run action' }).click();
  await page.getByRole('button', { name: 'Send alert' }).click();
  const run = page.getByRole('button', { name: 'Run on 8' });
  ok('alert: Run disabled until Message', await run.isDisabled());
  await page.getByLabel('Message', { exact: true }).fill('Hello');
  await run.click();
  await page.waitForTimeout(300);
  bulk = last(calls, /\/bulk\/commands$/);
  ok('alert queued with body', bulk && JSON.parse(JSON.parse(bulk.body).command.payload).body === 'Hello');

  await page.getByLabel('Select all on this page').check();
  await page.getByRole('button', { name: 'Run action' }).click();
  await page.getByRole('button', { name: 'Install app…' }).click();
  await page.getByRole('button', { name: /^Chrome/ }).click();
  await page.waitForTimeout(300);
  bulk = last(calls, /\/bulk\/commands$/);
  ok('install queued', bulk && JSON.parse(bulk.body).command.type === 'app.install');

  await page.getByLabel('Select all on this page').check();
  await page.getByRole('button', { name: 'Run action' }).click();
  const customBtn = page.getByRole('button', { name: 'Custom kiosk…' });
  const returnBtn = page.getByRole('button', { name: 'Return to configured kiosk…' });
  ok('kiosk group: Return to configured kiosk is primary', /v2-btn--primary/.test((await returnBtn.getAttribute('class')) ?? ''));
  ok('kiosk group: Custom kiosk is secondary, not red', /v2-btn--secondary/.test((await customBtn.getAttribute('class')) ?? ''), await customBtn.getAttribute('class'));
  await customBtn.click();
  ok('custom kiosk: one-time, not the configurations’', (await page.getByRole('dialog', { name: 'Custom kiosk on 8 devices' }).textContent()).includes('A one-time kiosk with apps you pick now. The devices’ configurations don’t define it.'));
  const enter = page.getByRole('button', { name: 'Enter custom kiosk on 8' });
  ok('kiosk: disabled until an app', await enter.isDisabled());
  ok('kiosk: empty exit PIN means no exit (0.3.6+)', await page.getByPlaceholder('Empty: no exit on the device (agent 0.3.6+)').isVisible());
  await page.getByRole('radio', { name: 'Pin a single app' }).click();
  await page.getByRole('button', { name: 'Chrome' }).click();
  await page.getByRole('button', { name: 'Field Ops' }).click();
  ok('single mode keeps one app', (await page.locator('[aria-pressed="true"]', { hasText: /Chrome|Field Ops/ }).count()) === 1);
  await page.getByLabel('Exit mode').selectOption('remote');
  await enter.click();
  await page.waitForTimeout(500);
  const kiosk = calls.filter((c) => /\/bulk\/commands$/.test(c.p) && c.body.includes('kiosk.enter'));
  ok('kiosk: one command per configuration', kiosk.length === 3, String(kiosk.length));
  ok('kiosk payload', kiosk[0] && JSON.parse(JSON.parse(kiosk[0].body).command.payload).exitMode === 'remote');

  // Return to configured kiosk: only Retail Kiosk (devices 102, 104, 107) has kiosk mode on; it pins Signage Player
  // (mainAppId 2710 is its usedVersionId), Chrome is hidden from kiosk and Teams isn't set to Install.
  await page.getByLabel('Select all on this page').check();
  await page.getByRole('button', { name: 'Run action' }).click();
  await page.getByRole('button', { name: 'Return to configured kiosk…' }).click();
  const rd = page.getByRole('dialog', { name: 'Return to configured kiosk' });
  await rd.getByRole('list', { name: 'By configuration' }).waitFor({ timeout: 5000 }).catch(() => {});
  const rtext = (await rd.textContent()).replace(/\s+/g, ' ');
  ok('return: says what it does', rtext.includes('Puts each device back into the kiosk its configuration defines'));
  ok('return: Retail Kiosk pinned to Signage Player', rtext.includes('Retail Kiosk · 3 devices: Pinned to Signage Player'), rtext.slice(0, 400));
  ok('return: kiosk-off configurations skipped with the reason', rtext.includes('Default · 3 devices: skipped, kiosk mode is off in this configuration') && rtext.includes('Field Ops · 2 devices: skipped, kiosk mode is off in this configuration'));
  const before = calls.length;
  await rd.getByRole('button', { name: 'Return 3 devices to configured kiosk' }).click();
  await page.waitForTimeout(500);
  const ret = calls.slice(before).filter((c) => /\/bulk\/commands$/.test(c.p));
  ok('return: one kiosk.enter, for the kiosk configuration’s devices only', ret.length === 1 && JSON.parse(ret[0].body).deviceIds.slice().sort().join() === '102,104,107', ret.map((c) => c.body).join(' | '));
  const rp = ret[0] && JSON.parse(JSON.parse(ret[0].body).command.payload);
  const want = {
    mode: 'single', allowedPackages: ['com.acme.signage'], pinPackage: 'com.acme.signage',
    features: { recents: false, systemInfo: true, lockButtons: true }, exitMode: 'gesture', password: '12345678',
    theme: { backgroundColor: '#15181D', textColor: '#FFFFFF', iconSize: 'LARGE' },
  };
  const canon = (v) => JSON.stringify(v, (_k, x) => (x && typeof x === 'object' && !Array.isArray(x) ? Object.fromEntries(Object.entries(x).sort(([a], [b]) => (a < b ? -1 : 1))) : x));
  ok('return: payload is the configuration’s kiosk', rp && canon(rp) === canon(want), rp && canon(rp));
  ok('return: toast', (await page.getByText('Return to configured kiosk queued').count()) > 0);
  ok('return: selection cleared', (await page.locator('.v2-bulkbar').count()) === 0);

  // A selection with no kiosk configuration: nothing to return, the button stays disabled.
  await page.getByLabel('Select WH-SCAN-07').check();
  await page.getByRole('button', { name: 'Run action' }).click();
  await page.getByRole('button', { name: 'Return to configured kiosk…' }).click();
  const rd2 = page.getByRole('dialog', { name: 'Return to configured kiosk' });
  await rd2.getByRole('list', { name: 'By configuration' }).waitFor({ timeout: 5000 }).catch(() => {});
  ok('return: nothing to return → disabled', await rd2.getByRole('button', { name: 'Return 0 devices to configured kiosk' }).isDisabled());
  await rd2.getByRole('button', { name: 'Back' }).click();
  ok('return: Back to the catalogue', (await page.getByRole('button', { name: 'Custom kiosk…' }).count()) === 1);
  await page.keyboard.press('Escape');
  await page.getByRole('button', { name: 'Clear', exact: true }).click().catch(() => {});

  // Escape closes the run modal
  await page.getByLabel('Select all on this page').check();
  await page.getByRole('button', { name: 'Run action' }).click();
  await page.keyboard.press('Escape');
  ok('Escape closes modal', (await page.getByRole('dialog').count()) === 0);

  // change configuration
  await page.getByRole('button', { name: 'Change configuration' }).click();
  const move = page.getByRole('button', { name: 'Move' });
  ok('Move disabled until chosen', await move.isDisabled());
  await page.getByRole('dialog').getByLabel('Configuration').selectOption('3');
  await move.click();
  await page.waitForTimeout(400);
  const put = last(calls, /^\/private\/devices$/);
  ok('PUT devices {ids, configurationId}', put && put.m === 'PUT' && JSON.parse(put.body).configurationId === 3 && JSON.parse(put.body).ids.length === 8);
  ok('selection cleared after move', (await page.locator('.v2-bulkbar').count()) === 0);

  // bulk delete
  await page.getByLabel('Select WH-SCAN-11').check();
  await page.getByRole('button', { name: 'Delete', exact: true }).click();
  await page.getByRole('alertdialog').getByRole('button', { name: 'Delete 1' }).click();
  await page.waitForTimeout(300);
  ok('bulk delete', JSON.parse(last(calls, /deleteBulk$/).body).ids.join() === '108');

  // deep links: ?status=…&config=…, then Clear filters
  await page.goto(`${base}/devices?status=offline&config=1`);
  await page.waitForSelector('table[aria-label="Devices"] tbody tr');
  await page.waitForTimeout(600);
  ok('?status=offline&config=1 → 1', (await rows()) === 1 && (await page.getByRole('radio', { name: /Offline/ }).getAttribute('aria-checked')) === 'true');
  await page.goto(`${base}/devices?status=attention&config=1`);
  await page.waitForTimeout(900);
  ok('?status=attention&config=1 → empty', (await page.getByText('No devices match these filters').count()) === 1);
  await page.getByRole('button', { name: 'Clear filters' }).click();
  await page.waitForTimeout(200);
  ok('Clear filters resets URL', (await rows()) === 8 && !page.url().includes('?'), page.url());

  // view switch
  await page.getByRole('radio', { name: 'Cards' }).click();
  await page.waitForTimeout(300);
  ok('cards view + URL', page.url().includes('view=cards') && (await page.locator('.v2-devices-card').count()) === 8, `${await page.locator('.v2-devices-card').count()} ${page.url()}`);
  ok('view remembered', (await page.evaluate(() => localStorage.getItem('mdmesh-v2-devices-view'))) === 'cards');
  await page.locator('.v2-devices-card', { hasText: 'KIOSK-CAFE' }).locator('.v2-devices-card__facts').click();
  ok('card click opens detail', /\/devices\/KIOSK-CAFE$/.test(page.url()));
  ok('no console errors (fleet 8)', errors.length === 0, errors.join(' | '));
  await context.close();
}

// ---------- selection follows the filters ----------
{
  const { context, page, errors } = await ctx();
  await page.goto(`${base}/devices`);
  await page.waitForSelector('table[aria-label="Devices"] tbody tr');
  await page.waitForTimeout(600);
  const bar = () => page.locator('.v2-bulkbar').count();
  const offlineRows = async () => {
    await page.getByRole('radio', { name: /Offline/ }).click();
    await page.waitForTimeout(200);
    const n = await page.locator('table[aria-label="Devices"] tbody tr .v2-devid__num').allInnerTexts();
    await page.getByRole('radio', { name: /^All/ }).click();
    await page.waitForTimeout(200);
    return n;
  };
  const offline = await offlineRows();
  const online = (await page.locator('table[aria-label="Devices"] tbody tr .v2-devid__num').allInnerTexts()).find((n) => !offline.includes(n));
  ok('fixture: 3 offline rows', offline.length === 3, offline.join());

  // A selected row the filter hides is dropped, not kept out of sight.
  await page.getByLabel(`Select ${online}`).check();
  await page.getByRole('radio', { name: /Offline/ }).click();
  await page.waitForTimeout(200);
  ok('filter hides the only selected row → selection cleared', (await bar()) === 0);
  await page.getByRole('radio', { name: /^All/ }).click();
  await page.waitForTimeout(200);
  ok('…and it stays cleared when the filter is lifted', (await bar()) === 0);

  // One visible + one hidden selected: the bar counts the visible one and still offers "Select all 3".
  await page.getByLabel(`Select ${online}`).check();
  await page.getByLabel(`Select ${offline[0]}`).check();
  ok('2 selected under All', (await page.getByText('2 selected').count()) === 1);
  await page.getByRole('radio', { name: /Offline/ }).click();
  await page.waitForTimeout(200);
  ok('Offline filter: 1 selected, "Select all 3" offered', (await page.getByText('1 selected').count()) === 1 && (await page.getByRole('button', { name: 'Select all 3' }).count()) === 1);
  await page.getByRole('button', { name: 'Run action' }).click();
  ok('bulk dialog acts on the visible selection only', (await page.getByRole('dialog', { name: 'Run action on 1 device' }).count()) === 1);
  await page.keyboard.press('Escape');
  await page.getByRole('button', { name: 'Select all 3' }).click();
  ok('Select all 3 → 3 selected, offer gone', (await page.getByText('3 selected').count()) === 1 && (await page.getByRole('button', { name: /^Select all \d/ }).count()) === 0);
  await page.getByRole('radio', { name: /^All/ }).click();
  await page.waitForTimeout(200);
  ok('back to All keeps the 3', (await page.getByText('3 selected').count()) === 1 && (await page.getByRole('button', { name: 'Select all 8' }).count()) === 1);
  await page.getByRole('button', { name: 'Clear', exact: true }).click();

  // A search reload drops rows it no longer returns (bulk kiosk then never meets an id it can't place in a configuration).
  await page.getByLabel('Select KIOSK-CAFE').check();
  await page.getByLabel('Search devices').fill('scan');
  await page.waitForTimeout(900);
  ok('search reload drops the selected row it no longer lists', (await bar()) === 0);
  await page.getByLabel('Select all on this page').check();
  ok('scanners selectable after the search', (await page.getByText('3 selected').count()) === 1);
  ok('no console errors (selection)', errors.length === 0, errors.join(' | '));
  await context.close();
}

// ---------- reloads don't wipe the selection while the sync picture rebuilds; the server's answer prunes it ----------
{
  const front = fx.devices.find((d) => d.number === 'KIOSK-FRONT');
  const { context, page, errors } = await ctx({ search: (v) => (v === 'kiosk' ? { items: [front], delay: 600 } : null) });
  // Attention filter + a search reload: attention rows drop out only while the sync check is pending.
  await page.goto(`${base}/devices?status=attention`);
  await page.getByLabel('Select KIOSK-CAFE').waitFor();
  await page.waitForTimeout(600);
  await page.getByLabel('Select KIOSK-CAFE').check();
  await page.getByLabel('Search devices').fill('cafe');
  await page.waitForTimeout(1500);
  ok('attention filter: a reload keeps the selected row', (await page.getByText('1 selected').count()) === 1 && (await page.getByLabel('Select KIOSK-CAFE').isChecked()));
  await page.getByRole('button', { name: 'Clear', exact: true }).click({ timeout: 2000 }).catch(() => {});
  // Server-only prune: "kiosk" still matches KIOSK-CAFE on the client, but the server's answer leaves it out.
  await page.goto(`${base}/devices`);
  await page.getByLabel('Select KIOSK-CAFE').waitFor();
  await page.getByLabel('Select KIOSK-CAFE').check();
  await page.getByLabel('Search devices').fill('kiosk');
  await page.waitForTimeout(300);
  ok('before the server answers, the row is still shown and selected', (await page.getByText('1 selected').count()) === 1);
  await page.waitForTimeout(1200);
  ok('the server answer without the row drops it from the selection', (await page.locator('.v2-bulkbar').count()) === 0 && (await page.getByLabel('Select KIOSK-CAFE').count()) === 0);
  ok('no console errors (reload selection)', errors.length === 0, errors.join(' | '));
  await context.close();
}

// ---------- out-of-order search answers ----------
{
  // "wh" answers late (after "wh-x" has); "wh-x" is matched by the server on a field the list doesn't hold, so only
  // the newer answer shows SERIAL-HIT (the client filter alone would show no row at all).
  const hit = { ...fx.devices[0], id: 990, number: 'SERIAL-HIT', description: 'Matched on serial' };
  const { context, page, errors, calls } = await ctx({
    search: (v) => (v === 'wh' ? { delay: 1500 } : v === 'wh-x' ? { items: [hit] } : null),
  });
  await page.goto(`${base}/devices`);
  await page.waitForSelector('table[aria-label="Devices"] tbody tr');
  await page.waitForTimeout(500);
  const box = page.getByLabel('Search devices');
  await box.fill('wh');
  await page.waitForTimeout(450); // debounce fired: "wh" is in flight
  await box.fill('wh-x');
  await page.waitForTimeout(2300); // "wh-x" answered, then "wh" (late)
  const order = calls.filter((c) => c.p.endsWith('/devices/search')).map((c) => c.body.value);
  ok('both searches were sent, older first', order.indexOf('wh') >= 0 && order.indexOf('wh') < order.indexOf('wh-x'), order.join());
  const nums = await page.locator('table[aria-label="Devices"] tbody tr .v2-devid__num').allInnerTexts();
  ok('the newer answer wins', nums.length === 1 && nums[0] === 'SERIAL-HIT', nums.join());
  ok('search box keeps the new value', (await box.inputValue()) === 'wh-x');
  ok('no console errors (search race)', errors.length === 0, errors.join(' | '));
  await context.close();
}

// ---------- long attention reasons wrap on cards ----------
for (const width of [1440, 390]) {
  const { context, page } = await ctx({ viewport: { width, height: 900 } });
  await page.goto(`${base}/devices?view=cards&status=attention`);
  await page.waitForSelector('.v2-devices-card .v2-presence__reason');
  await page.waitForTimeout(600);
  const reasons = await page.locator('.v2-devices-card .v2-presence__reason').evaluateAll((els) =>
    els.map((el) => {
      const cs = getComputedStyle(el);
      const lh = parseFloat(cs.lineHeight) || parseFloat(cs.fontSize) * 1.4;
      return {
        text: el.textContent, title: el.getAttribute('title'), ws: cs.whiteSpace, to: cs.textOverflow,
        lines: Math.round(el.getBoundingClientRect().height / lh), clipped: el.scrollHeight > el.clientHeight + 1,
      };
    }),
  );
  const long = reasons.find((r) => r.text === 'Agent too old for configuration sync');
  ok(`${width}: full long reason in the card`, !!long, JSON.stringify(reasons.map((r) => r.text)));
  ok(`${width}: reasons wrap (no nowrap/ellipsis), ≤ 2 lines, nothing clipped, no tooltip`,
    reasons.length > 0 && reasons.every((r) => r.ws !== 'nowrap' && r.to !== 'ellipsis' && r.lines <= 2 && !r.clipped && r.title === null),
    JSON.stringify(reasons));
  await context.close();
}

// ---------- "Roll out an agent update" goes to the rollout section ----------
{
  const { context, page } = await ctx();
  await page.goto(`${base}/devices`);
  const link = page.getByRole('link', { name: 'Roll out an agent update' });
  await link.waitFor({ timeout: 8000 }).catch(() => {});
  const href = await link.getAttribute('href', { timeout: 1000 }).catch(() => null);
  ok('rollout link → /settings#agent-rollout', href === '/settings#agent-rollout', String(href));
  await context.close();
}

// ---------- bigger fleet: paging, duplicates, unassigned, ?config=none ----------
{
  const now = Date.now();
  const many = Array.from({ length: 25 }, (_, i) => ({
    id: 500 + i, number: `TAB-${String(i).padStart(4, '0')}`, description: `Tablet ${i}`, configurationId: i % 5 === 0 ? undefined : 1,
    lastUpdate: now - i * 60_000, androidVersion: '13', hardwareId: i < 2 ? 'same-hw' : `hw-${i}`,
  }));
  const { context, page, errors } = await ctx({ devices: many });
  await page.goto(`${base}/devices?config=none`);
  await page.waitForSelector('table[aria-label="Devices"] tbody tr');
  const rows = () => page.locator('table[aria-label="Devices"] tbody tr').count();
  ok('?config=none → unassigned only', (await rows()) === 5);
  await page.getByLabel('Configuration').selectOption('all');
  await page.waitForTimeout(200);
  const pc = await page.locator('.v2-pager__count').innerText();
  ok('page size 10', (await rows()) === 10 && pc.includes('1–10 of 25'), `${await rows()} ${JSON.stringify(pc)}`);
  await page.getByRole('button', { name: 'Next' }).click();
  ok('next page', (await page.locator('.v2-pager__count').innerText()).includes('11–20'));
  await page.getByLabel('Rows per page').selectOption('50');
  ok('rows per page 50', (await rows()) === 25);
  await page.getByRole('button', { name: /Duplicates/ }).click();
  ok('duplicates toggle', (await rows()) === 2 && (await page.getByText('2× hardware ID').count()) === 2);
  await page.goto(`${base}/devices?view=cards`);
  await page.waitForSelector('.v2-devices-card');
  ok('cards page of 12', (await page.locator('.v2-devices-card').count()) === 12);
  ok('no console errors (fleet 25)', errors.length === 0, errors.join(' | '));
  await context.close();
}

// ---------- narrow ----------
{
  const { context, page } = await ctx({ viewport: { width: 390, height: 800 } });
  await page.goto(`${base}/devices`);
  await page.waitForSelector('.v2-devices-card');
  ok('390: cards forced, no switch', (await page.getByRole('radiogroup', { name: 'View' }).count()) === 0);
  const sw = await page.evaluate(() => document.documentElement.scrollWidth);
  ok('390: no horizontal scroll', sw <= 390, String(sw));
  await context.close();
}

await browser.close();
server.close();
console.log(results.join('\n'));
console.log(results.filter((r) => r.startsWith('FAIL')).length ? 'SOME FAILED' : 'ALL PASS');
process.exit(results.some((r) => r.startsWith('FAIL')) ? 1 : 0);
