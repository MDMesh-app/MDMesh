// Parity run for /settings against the harness fixtures.
//   node scripts/shots/v2-parity/settings.mjs [--dist <dir>]
import { chromium } from 'playwright';
import { fx, newContext, startServer } from '../harness.mjs';
import { dist } from './lib.mjs';
const { server, base } = await startServer(dist);
const browser = await chromium.launch();
const results = [];
const errors = [];
const check = (name, ok, extra = '') => {
  results.push(`${ok ? 'PASS' : 'FAIL'}  ${name}${extra ? ` (${extra})` : ''}`);
};

async function page(opts = {}) {
  const ctx = await newContext(browser, fx.user, { theme: 'light', viewport: { width: 1440, height: 900 }, deviceScaleFactor: 1, blockGoogleFonts: true, ...opts });
  const p = await ctx.newPage();
  p.on('console', (m) => {
    if (m.type() === 'error') errors.push(`${m.text()}`);
  });
  p.on('pageerror', (e) => errors.push(`pageerror: ${e.message}`));
  p.on('requestfailed', (r) => errors.push(`  ↳ failed request: ${r.url()}`));
  p.on('response', (r) => { if (r.status() >= 400) errors.push(`  ↳ ${r.status()} ${r.url()}`); });
  return { ctx, p };
}
const json = (route, body, status = 200) =>
  route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(body) });
const env = (data, status = 'OK', message = null) => ({ status, message, data });

try {
  // ---- deep links ----
  for (const id of ['agent-rollout', 'updates']) {
    const { ctx, p } = await page();
    await p.goto(`${base}/settings#${id}`);
    await p.waitForSelector(`#${id}`);
    await p.waitForTimeout(800);
    const top = await p.$eval(`#${id}`, (el) => el.getBoundingClientRect().top);
    const atEnd = await p.evaluate(() => window.innerHeight + window.scrollY >= document.documentElement.scrollHeight - 2);
    check(`#${id} deep link scrolls the section to the top (or the page end)`, top >= 0 && (top < 60 || atEnd), `top=${Math.round(top)} atEnd=${atEnd}`);
    const cur = await p.$eval('nav[aria-label="Settings sections"] [aria-current="location"]', (a) => a.getAttribute('href')).catch(() => null);
    check(`#${id} deep link marks the nav item current`, cur === `#${id}`, `current=${cur}`);
    await ctx.close();
  }

  // ---- sidebar "Review update" while already on the page ----
  {
    const { ctx, p } = await page();
    await p.goto(`${base}/settings`);
    await p.waitForSelector('#updates');
    await p.click('text=Review update');
    await p.waitForTimeout(600);
    const top = await p.$eval('#updates', (el) => el.getBoundingClientRect().top);
    check('sidebar Review update scrolls to #updates on the same page', top >= 0 && top < 60, `top=${Math.round(top)}`);
    await ctx.close();
  }

  // ---- account, change password ----
  {
    const { ctx, p } = await page();
    let putBody = null;
    let wrong = true;
    await ctx.route('**/rest/private/users/current', (route) => {
      if (route.request().method() !== 'PUT') return route.fallback();
      putBody = JSON.parse(route.request().postData() || '{}');
      return json(route, wrong ? env(null, 'ERROR', 'error.password.wrong') : env({}));
    });
    await p.goto(`${base}/settings`);
    await p.waitForSelector('#account');
    const kv = await p.$$eval('#account dd', (d) => d.map((x) => x.textContent));
    check('account rows show name, login, email, role', kv.join('|') === 'Operations|admin|ops@acme.example|Super admin', kv.join('|'));
    await p.click('button:has-text("Change password")');
    const dlg = p.locator('dialog[open]');
    await dlg.waitFor();
    check('change password opens a dialog', await dlg.isVisible());
    const submit = dlg.locator('button[type=submit]');
    check('each show-password button names its field', (await dlg.getByRole('button', { name: 'Show current password' }).count()) === 1 && (await dlg.getByRole('button', { name: 'Show new password' }).count()) === 1);
    await dlg.getByLabel('Current password', { exact: true }).fill('oldpass1');
    await dlg.getByLabel('New password', { exact: true }).fill('short');
    check('submit disabled for a short password', await submit.isDisabled());
    await dlg.getByLabel('New password', { exact: true }).fill('newpass123');
    await dlg.getByLabel('Type it again', { exact: true }).fill('newpass12');
    check('submit disabled when the two differ', await submit.isDisabled());
    await dlg.getByLabel('Type it again', { exact: true }).fill('newpass123');
    check('submit enabled when valid', await submit.isEnabled());
    await submit.click();
    await dlg.locator('text=That isn’t your current password.').waitFor();
    check('wrong current password shows a field error', true);
    check('PUT body hashes both passwords (MD5, upper hex) and sends the user id',
      putBody && putBody.id === 1 && /^[0-9A-F]{32}$/.test(putBody.oldPassword) && /^[0-9A-F]{32}$/.test(putBody.newPassword) && putBody.oldPassword !== putBody.newPassword,
      JSON.stringify(putBody));
    wrong = false;
    await dlg.getByLabel('Current password', { exact: true }).fill('oldpass2');
    await submit.click();
    await p.waitForSelector('dialog[open]', { state: 'detached', timeout: 3000 }).catch(() => {});
    const open = await p.locator('dialog[open]').count();
    const toast = await p.locator('text=Password changed').count();
    check('success closes the dialog and toasts', open === 0 && toast > 0, `open=${open} toast=${toast}`);
    // Escape closes, fields reset
    await p.click('button:has-text("Change password")');
    await dlg.waitFor();
    const cur = await dlg.getByLabel('Current password', { exact: true }).inputValue();
    check('reopened dialog starts empty', cur === '');
    await p.keyboard.press('Escape');
    await p.waitForTimeout(200);
    check('Escape closes the dialog', (await p.locator('dialog[open]').count()) === 0);
    // sign out
    let loggedOut = false;
    await ctx.route('**/rest/public/auth/logout', (r) => { loggedOut = true; return json(r, env(null)); });
    await p.click('#account button:has-text("Sign out")');
    await p.waitForTimeout(500);
    check('Sign out calls logout and leaves the page', loggedOut && !p.url().includes('/settings'), p.url());
    await ctx.close();
  }

  // ---- server, appearance, enrollment ----
  {
    const { ctx, p } = await page();
    await p.goto(`${base}/settings`);
    await p.waitForSelector('#server');
    await p.waitForTimeout(500);
    const conn = await p.$eval('#server dd', (d) => d.textContent);
    check('connection shows Connected with latency', /^Connected · \d+ ms$/.test(conn.trim()), conn);
    await p.click('#appearance [role=radio]:has-text("Dark")');
    await p.waitForTimeout(300);
    check('Theme Dark applies data-theme=dark', (await p.getAttribute('html', 'data-theme')) === 'dark');
    await p.click('#appearance [role=radio]:has-text("Match system")');
    await p.waitForTimeout(300);
    const pref = await p.evaluate(() => localStorage.getItem('mdmesh-theme'));
    check('Match system stores "system"', pref === 'system', pref);
    await p.focus('#appearance [role=radio][aria-checked=true]');
    const focused = await p.evaluate(() => document.activeElement?.textContent);
    await p.keyboard.press('ArrowLeft');
    await p.waitForTimeout(600);
    console.log('focused before arrow:', focused, 'after:', await p.evaluate(() => document.activeElement?.textContent), await p.evaluate(() => localStorage.getItem('mdmesh-theme')));
    check('arrow keys move the theme choice', (await p.evaluate(() => localStorage.getItem('mdmesh-theme'))) === 'dark');
    const opts = await p.$$eval('#enrollment select option', (o) => o.map((x) => x.textContent));
    check('default configuration lists No default + sorted configurations', opts.join('|') === 'No default|Default|Field Ops|Retail Kiosk', opts.join('|'));
    await p.selectOption('#enrollment select', { label: 'Retail Kiosk' });
    check('choosing a default stores it per browser', (await p.evaluate(() => localStorage.getItem('mdmesh-default-config'))) === '2');
    await p.selectOption('#enrollment select', { label: 'No default' });
    check('No default clears it', (await p.evaluate(() => localStorage.getItem('mdmesh-default-config'))) === null);
    await p.click('#enrollment a:has-text("Enroll a device")');
    await p.waitForTimeout(300);
    check('Enroll a device goes to /enroll', p.url().endsWith('/enroll'), p.url());
    await ctx.close();
  }

  // ---- updates: check, auto, apply → progress → failure ----
  {
    const { ctx, p } = await page();
    let status = { ...fx.updateStatus };
    let checkCalls = 0;
    let autoBody = null;
    let applyCalls = 0;
    await ctx.route('**/update/**', (route) => {
      const u = route.request().url();
      if (u.includes('/update/status')) return json(route, status);
      if (u.includes('/update/check')) { checkCalls++; status = { ...status, checkedAt: Date.now() }; return json(route, status); }
      if (u.includes('/update/auto')) { autoBody = JSON.parse(route.request().postData() || '{}'); return json(route, { auto: autoBody.auto }); }
      if (u.includes('/update/apply')) {
        applyCalls++;
        status = { ...status, apply: { phase: 'backup', fromVersion: '0.1.14', toVersion: '0.1.15', trigger: 'manual', startedAt: Date.now(), finishedAt: null, error: null } };
        return json(route, { started: true }, 202);
      }
      return json(route, {});
    });
    await p.goto(`${base}/settings`);
    await p.waitForSelector('text=is ready');
    check('ready card shows version, verified, channel, date', (await p.locator('#updates >> text=Signature verified').count()) === 1 && (await p.locator('#updates >> text=/Stable channel · released/').count()) === 1);
    check('release notes render as headings and bullets', (await p.locator('#updates li').count()) === 3 && (await p.locator('#updates .v2-settings-notes__h').count()) === 2);
    check('notes drop the repeated version title', (await p.locator('#updates .v2-settings-notes >> text=v0.1.15').count()) === 0);
    check('Full release notes link opens the release URL', (await p.getAttribute('#updates a:has-text("Full release notes")', 'href')) === fx.updateStatus.release.url);
    await p.click('#updates button:has-text("Check now")');
    await p.waitForTimeout(400);
    check('Check now posts /update/check and shows the new time', checkCalls === 1 && (await p.locator('#updates >> text=Checked just now').count()) === 1);
    await p.click('#updates [role=switch]');
    await p.waitForTimeout(300);
    check('auto toggle posts {auto:true} and shows On', autoBody?.auto === true && (await p.getAttribute('#updates [role=switch]', 'aria-checked')) === 'true');
    await p.click('#updates button:has-text("Update server and console")');
    const dlg = p.locator('dialog[open]');
    await dlg.waitFor();
    check('apply asks for confirmation first', applyCalls === 0 && (await dlg.locator('text=Update to MDMesh 0.1.15?').count()) === 1);
    await dlg.locator('button:has-text("Cancel")').click();
    await p.waitForTimeout(200);
    check('cancelling the confirmation does not apply', applyCalls === 0);
    await p.click('#updates button:has-text("Update server and console")');
    await dlg.locator('button:has-text("Update now")').click();
    await p.waitForSelector('text=Updating to MDMesh');
    check('apply shows live progress (stepper at Back up)', applyCalls === 1 && (await p.locator('#updates [aria-current=step]').textContent()).includes('Back up'));
    check('nav shows Updating', (await p.locator('nav[aria-label="Settings sections"] >> text=Updating').count()) === 1);
    status = { ...status, apply: { ...status.apply, phase: 'healthcheck' } };
    await p.waitForTimeout(3500);
    check('progress polls (stepper at Health check)', (await p.locator('#updates [aria-current=step]').textContent()).includes('Health check'));
    status = { ...status, apply: { ...status.apply, phase: 'rolled_back', finishedAt: Date.now(), error: 'healthcheck timed out' } };
    await p.waitForTimeout(3500);
    check('rolled back shows the alert with the error', (await p.locator('#updates >> text=was rolled back').count()) === 1 && (await p.locator('#updates >> text=healthcheck timed out').count()) === 1);
    check('recovery link goes to /recovery', (await p.getAttribute('#updates a:has-text("Open recovery")', 'href')) === '/recovery');
    check('nav shows Failed', (await p.locator('nav[aria-label="Settings sections"] >> text=Failed').count()) === 1);
    await ctx.close();
  }

  // ---- updates: auto skip notice, unverified, up to date, done ----
  {
    const { ctx, p } = await page();
    let status = { ...fx.updateStatus, auto: true, autoSkipped: '0.1.15' };
    await ctx.route('**/update/status', (r) => json(r, status));
    await p.goto(`${base}/settings`);
    await p.waitForSelector('text=is ready');
    check('auto.json skipVersion shows the won’t-install-again note', (await p.locator('#updates >> text=/won’t install/').count()) === 1);
    await ctx.close();
  }
  {
    const { ctx, p } = await page();
    await ctx.route('**/update/status', (r) => json(r, { ...fx.updateStatus, verified: false }));
    await p.goto(`${base}/settings`);
    await p.waitForSelector('text=is ready');
    check('unverified release: badge + apply disabled', (await p.locator('#updates >> text=Signature not verified').count()) === 1 && await p.isDisabled('#updates button:has-text("Update server and console")'));
    await ctx.close();
  }
  {
    const { ctx, p } = await page();
    await ctx.route('**/update/status', (r) => json(r, { ...fx.updateStatus, current: '0.1.15', updateAvailable: false, apply: { phase: 'done', fromVersion: '0.1.14', toVersion: '0.1.15', trigger: 'manual', startedAt: Date.now() - 300000, finishedAt: Date.now() - 120000, error: null } }));
    await p.goto(`${base}/settings`);
    await p.waitForSelector('text=is the latest release');
    check('up to date + just-updated notice with Reload', (await p.locator('#updates button:has-text("Reload")').count()) === 1);
    check('nav has no Updates meta when up to date', (await p.locator('nav[aria-label="Settings sections"] >> text=1 ready').count()) === 0);
    await ctx.close();
  }

  // ---- APPLY_SUPPORTED=0 ----
  {
    const { ctx, p } = await page();
    await ctx.route('**/update/status', (r) => json(r, { ...fx.updateStatus, applySupported: false }));
    await p.goto(`${base}/settings`);
    await p.waitForSelector('text=is ready');
    check('source install: manual commands, no Update button, no auto toggle',
      (await p.locator('#updates input[value="git pull && sudo ./setup.sh --native"]').count()) === 1 &&
      (await p.locator('#updates input[value="git pull && ./setup.sh"]').count()) === 1 &&
      (await p.locator('#updates button:has-text("Update server and console")').count()) === 0 &&
      (await p.locator('#updates [role=switch]').count()) === 0);
    check('Installed with reflects a source install', (await p.locator('#server >> text=Source or native installer').count()) === 1);
    await ctx.close();
  }

  // ---- supervisor unreachable ----
  {
    const { ctx, p } = await page();
    await ctx.route('**/update/status', (r) => r.fulfill({ status: 502, body: 'bad gateway' }));
    await p.goto(`${base}/settings`);
    await p.waitForSelector('text=The updater isn’t reachable');
    check('unreachable updater: message + Try again', (await p.locator('#updates button:has-text("Try again")').count()) === 1);
    check('rollout section still renders (anchor target exists)', (await p.locator('#agent-rollout').count()) === 1);
    await ctx.close();
  }

  // ---- rollout: cancel → pick canary → start → promote → finish ----
  {
    const { ctx, p } = await page();
    let active = fx.activeRollout;
    let cancelCalls = 0;
    let createBody = null;
    let promoteCalls = 0;
    await ctx.route('**/rest/private/agent/v1/rollout**', (route) => {
      const u = new URL(route.request().url());
      const m = route.request().method();
      if (u.pathname.endsWith('/rollout/active')) return json(route, env(active));
      if (/\/rollout\/\d+\/cancel$/.test(u.pathname)) { cancelCalls++; active = null; return json(route, env(null)); }
      if (/\/rollout\/\d+\/promote$/.test(u.pathname)) {
        promoteCalls++;
        active = { ...active, stage: 'fleet', progress: { ...active.progress, stage: 'fleet', fleet: { total: 4, updated: 1, pending: 2, outstanding: 1, ineligible: 0 } } };
        return json(route, env(active));
      }
      if (m === 'POST' && u.pathname.endsWith('/rollout')) {
        createBody = JSON.parse(route.request().postData() || '{}');
        active = { ...fx.activeRollout, progress: { ...fx.activeRollout.progress, canary: { total: 2, updated: 2, pending: 0, outstanding: 0, ineligible: 0 } } };
        return json(route, env(active));
      }
      return route.fallback();
    });
    await p.goto(`${base}/settings#agent-rollout`);
    await p.waitForSelector('#agent-rollout >> text=Canary');
    check('rollout shows canary counts and note', (await p.locator('#agent-rollout >> text=2 updated · 1 installing · 1 waiting to check in').count()) === 1);
    check('Promote disabled while canary devices are still updating', await p.isDisabled('#agent-rollout button:has-text("Promote to everyone else")'));
    check('nav shows Running', (await p.locator('nav[aria-label="Settings sections"] >> text=Running').count()) === 1);
    await p.click('#agent-rollout button:has-text("Cancel rollout")');
    const dlg = p.locator('dialog[open]');
    await dlg.waitFor();
    check('cancel asks for confirmation', cancelCalls === 0);
    await dlg.locator('button:has-text("Cancel rollout")').click();
    await p.waitForSelector('text=is ready to roll out');
    check('cancel posts and returns to the start state', cancelCalls === 1);
    await p.click('#agent-rollout button:has-text("Roll out agent")');
    await dlg.waitFor();
    await dlg.locator('text=KIOSK-FRONT').waitFor();
    const start = dlg.locator('button:has-text("Start canary")');
    check('picker lists devices; Start disabled with none chosen', (await dlg.locator('input[type=checkbox]').count()) === fx.devices.length && await start.isDisabled());
    await dlg.locator('label:has-text("KIOSK-FRONT")').click();
    await dlg.locator('label:has-text("WH-SCAN-07")').click();
    check('Start canary counts the selection', (await start.textContent()).includes('(2)'));
    await start.click();
    await p.waitForSelector('dialog[open]', { state: 'detached', timeout: 3000 }).catch(() => {});
    check('create posts the apk fields and chosen numbers',
      createBody && createBody.targetVersion === '0.1.15' && createBody.apkVersionCode === 16 && createBody.canaryDeviceNumbers.sort().join(',') === 'KIOSK-FRONT,WH-SCAN-07',
      JSON.stringify(createBody));
    await p.waitForSelector('#agent-rollout button:has-text("Promote to everyone else"):not([disabled])');
    check('Promote enabled once every canary device updated', true);
    await p.click('#agent-rollout button:has-text("Promote to everyone else")');
    await dlg.waitFor();
    await dlg.locator('button:has-text("Promote")').last().click();
    await p.waitForSelector('#agent-rollout button:has-text("Finish rollout")');
    check('promote posts and shows the fleet step + Finish', promoteCalls === 1 && (await p.locator('#agent-rollout >> text=1 updated · 2 installing · 1 waiting to check in').count()) === 1);
    await p.click('#agent-rollout button:has-text("Finish rollout")');
    await dlg.waitFor();
    await dlg.locator('button:has-text("Finish rollout")').click();
    await p.waitForSelector('text=is ready to roll out');
    check('finish posts cancel and clears the rollout', cancelCalls === 2);
    await ctx.close();
  }

  // ---- one supervisor status: Check now in Settings updates the sidebar card at once ----
  {
    const { ctx, p } = await page();
    let status = { ...fx.updateStatus, latest: '0.1.14', updateAvailable: false };
    await ctx.route('**/update/**', (route) => {
      const u = route.request().url();
      if (u.includes('/update/check')) status = { ...fx.updateStatus, checkedAt: Date.now() };
      return json(route, status);
    });
    await p.goto(`${base}/settings`);
    await p.waitForSelector('#updates button:has-text("Check now")');
    await p.waitForTimeout(300);
    check('sidebar has no update card while up to date', (await p.locator('.v2-sidebar .v2-updcard').count()) === 0);
    await p.click('#updates button:has-text("Check now")');
    await p.waitForSelector('.v2-sidebar .v2-updcard', { timeout: 3000 }).catch(() => {});
    check('Check now shows the new release in the sidebar card too', (await p.locator('.v2-sidebar .v2-updcard >> text=Server update 0.1.15').count()) === 1);
    await ctx.close();
  }

  // ---- rollout poll race: a poll answered after Cancel / Promote can't bring the old state back ----
  for (const action of ['cancel', 'promote']) {
    const { ctx, p } = await page();
    await ctx.clock.install();
    const canary = { ...fx.activeRollout, progress: { ...fx.activeRollout.progress, canary: { total: 2, updated: 2, pending: 0, outstanding: 0, ineligible: 0 } } };
    let active = canary;
    let hold = false;
    let held = null; // { route, body }: the poll held back, answered with the state when it was sent
    await ctx.route('**/rest/private/agent/v1/rollout**', (route) => {
      const u = new URL(route.request().url());
      if (u.pathname.endsWith('/rollout/active')) {
        if (hold && !held) { held = { route, body: env(active) }; return undefined; }
        return json(route, env(active));
      }
      if (/\/cancel$/.test(u.pathname)) { active = null; return json(route, env(null)); }
      if (/\/promote$/.test(u.pathname)) {
        active = { ...canary, stage: 'fleet', progress: { ...canary.progress, stage: 'fleet', fleet: { total: 4, updated: 1, pending: 2, outstanding: 1, ineligible: 0 } } };
        return json(route, env(active));
      }
      return route.fallback();
    });
    await p.goto(`${base}/settings#agent-rollout`);
    await p.waitForSelector('#agent-rollout button:has-text("Promote to everyone else"):not([disabled])');
    hold = true;
    await p.clock.runFor(10_500); // the 10 s poll goes out and is held
    for (let i = 0; i < 20 && !held; i++) await p.waitForTimeout(100);
    const label = action === 'cancel' ? 'Cancel rollout' : 'Promote to everyone else';
    await p.click(`#agent-rollout button:has-text("${label}")`);
    const dlg = p.locator('dialog[open]');
    await dlg.waitFor();
    await dlg.locator(`button:has-text("${action === 'cancel' ? 'Cancel rollout' : 'Promote'}")`).last().click();
    await p.waitForSelector(action === 'cancel' ? 'text=is ready to roll out' : '#agent-rollout button:has-text("Finish rollout")');
    if (held) await json(held.route, held.body); // the old canary state arrives late
    await p.waitForTimeout(500);
    const after = action === 'cancel'
      ? (await p.locator('text=is ready to roll out').count()) === 1 && (await p.locator('#agent-rollout button:has-text("Cancel rollout")').count()) === 0
      : (await p.locator('#agent-rollout button:has-text("Finish rollout")').count()) === 1;
    check(`a late poll after ${action} doesn't revert the rollout`, !!held && after, held ? '' : 'no poll was held');
    await ctx.close();
  }

  // ---- Density is gone: no control, nothing stored ----
  {
    const { ctx, p } = await page();
    // A value an older console stored is ignored: no attribute, no control.
    await ctx.addInitScript(() => localStorage.setItem('mdmesh-density', 'compact'));
    await p.goto(`${base}/settings#appearance`);
    await p.waitForSelector('#appearance');
    check('no Density control', (await p.locator('#v2-main >> text=Density').count()) === 0);
    check('a stored compact density is ignored', await p.evaluate(() => !document.documentElement.hasAttribute('data-density')));
    await ctx.close();
  }

  // ---- rollout error mapping ----
  {
    const { ctx, p } = await page();
    await ctx.route('**/rest/private/agent/v1/rollout/active', (r) => json(r, env(null)));
    await ctx.route('**/rest/private/agent/v1/rollout', (r) => json(r, env(null, 'ERROR', 'error.rollout.active')));
    await p.goto(`${base}/settings`);
    await p.click('#agent-rollout button:has-text("Roll out agent")');
    const dlg = p.locator('dialog[open]');
    await dlg.locator('label:has-text("KIOSK-FRONT")').click();
    await dlg.locator('button:has-text("Start canary")').click();
    await dlg.locator('text=Another rollout is already running').waitFor({ timeout: 3000 }).then(() => check('server rollout errors are worded and kept in the picker', true), () => check('server rollout errors are worded and kept in the picker', false));
    await ctx.close();
  }

  // ---- What's new (web/src/whatsnew): from Settings, and once by itself after an upgrade ----
  {
    // A server on a version this build has notes for (CHANGELOG.md's v0.3.7), already seen in this browser.
    const at = (ctx) => ctx.route('**/update/status', (r) => json(r, { ...fx.updateStatus, current: '0.3.7', updateAvailable: false }));
    // 390×560: a window too short for the notes, so they open in a body that scrolls.
    const short = '390×560 dark';
    for (const [label, opts] of [['1440 light', {}], ['390 dark', { theme: 'dark', viewport: { width: 390, height: 844 } }], [short, { theme: 'dark', viewport: { width: 390, height: 560 } }]]) {
      const { ctx, p } = await page({ whatsNewSeen: ['0.3.7', 'unreleased'], ...opts });
      const before = errors.length;
      await at(ctx);
      await p.goto(`${base}/settings`);
      await p.waitForSelector('#server');
      check(`${label}: What's new stays closed on a version seen before`, (await p.locator('dialog[open]').count()) === 0);
      const link = p.locator('#server button:has-text("What’s new")');
      await link.click();
      const dlg = p.getByRole('dialog', { name: 'What’s new in 0.3.7' });
      await dlg.waitFor({ timeout: 3000 }).catch(() => {});
      check(`${label}: What's new opens from Settings beside the version`, await dlg.isVisible());
      // The notes scroll in the dialog's body, under the title, its date line and the close button, above the
      // footer: all of them in view at the top of the notes and at their end.
      const layout = await p.evaluate(() => {
        const d = document.querySelector('dialog[open]');
        const body = d.querySelector('.v2-modal__body');
        const box = d.getBoundingClientRect();
        const shown = () =>
          ['.v2-modal__title', '.v2-modal__desc', 'button[aria-label="Close"]', '.v2-modal__foot'].every((sel) => {
            const r = d.querySelector(sel).getBoundingClientRect();
            return r.height > 0 && r.top >= Math.max(box.top, 0) && r.bottom <= Math.min(box.bottom, window.innerHeight);
          });
        const atTitle = d.scrollTop === 0 && body.scrollTop === 0 && shown();
        const tall = body.scrollHeight > body.clientHeight;
        body.scrollTop = body.scrollHeight;
        const atEnd = d.scrollTop === 0 && (!tall || body.scrollTop > 0) && shown();
        body.scrollTop = 0;
        return { atTitle, tall, atEnd };
      });
      check(`${label}: it opens at its title, not scrolled down`, layout.atTitle, JSON.stringify(layout));
      check(`${label}: its header and footer stay in view at the end of the notes${layout.tall ? ' (they scroll)' : ''}`, layout.atEnd && (layout.tall || label !== short), JSON.stringify(layout));
      if (label === short) {
        // 0.3.7's notes have no link: the scrolling body is a Tab stop of its own, after the close button, and the
        // keyboard scrolls it.
        await dlg.getByRole('button', { name: 'Close' }).focus();
        await p.keyboard.press('Tab');
        const stop = await p.evaluate(() => {
          const body = document.querySelector('dialog[open] .v2-modal__body');
          return document.activeElement === body && body.getAttribute('tabindex') === '0';
        });
        await p.keyboard.press('PageDown');
        await p.waitForTimeout(400);
        const scrolled = await p.evaluate(() => document.querySelector('dialog[open] .v2-modal__body').scrollTop > 0);
        check(`${label}: Tab reaches the notes after the close button, and PageDown scrolls them`, stop && scrolled, JSON.stringify({ stop, scrolled }));
        await p.evaluate(() => (document.querySelector('dialog[open] .v2-modal__body').scrollTop = 0));
      }
      check(`${label}: it renders the version's CHANGELOG section`, (await dlg.getByRole('heading', { name: 'Fixes', level: 3 }).count()) === 1 && (await dlg.locator('text=Exit unavailable right now').count()) === 1);
      check(`${label}: it links the GitHub release`, (await dlg.getByRole('link', { name: 'Full release notes on GitHub ↗' }).getAttribute('href')) === 'https://github.com/MDMesh-app/MDMesh/releases/tag/v0.3.7');
      const fits = await p.evaluate(() => {
        const d = document.querySelector('dialog[open]').getBoundingClientRect();
        return d.left >= 0 && d.right <= window.innerWidth && document.documentElement.scrollWidth <= window.innerWidth;
      });
      check(`${label}: the dialog fits the window`, fits);
      await p.keyboard.press('Escape');
      await p.waitForTimeout(200);
      check(`${label}: Escape closes it and focus goes back to the link`, (await p.locator('dialog[open]').count()) === 0 && (await link.evaluate((el) => el === document.activeElement)));
      check(`${label}: no page errors`, errors.length === before, errors.slice(before).join(' | '));
      await ctx.close();
    }
    // An upgrade: this browser used the console before (the stored sign-in), and hasn't seen 0.3.7.
    const { ctx, p } = await page({ whatsNewSeen: [] });
    await at(ctx);
    await p.goto(`${base}/`);
    const dlg = p.getByRole('dialog', { name: 'What’s new in 0.3.7' });
    await dlg.waitFor({ timeout: 3000 }).catch(() => {});
    check("What's new opens by itself once after an upgrade", await dlg.isVisible());
    await dlg.getByRole('button', { name: 'Done' }).click();
    await p.reload();
    await p.waitForSelector('.v2-shell');
    await p.waitForTimeout(800);
    check("…and not on the next page load", (await p.locator('dialog[open]').count()) === 0);
    await ctx.close();
  }
} catch (e) {
  results.push(`FAIL  exception: ${e.stack || e}`);
} finally {
  await browser.close();
  server.close();
}
console.log(results.join('\n'));
console.log(`\nconsole errors: ${errors.length}`);
for (const e of errors) console.log('  ' + e);
process.exit(results.some((r) => r.startsWith('FAIL')) ? 1 : 0);
