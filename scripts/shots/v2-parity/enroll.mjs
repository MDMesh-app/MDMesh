// Checks the v2 Enroll page against the fixtures.
//   node scripts/shots/v2-parity/enroll.mjs [--dist <dir>] [--shots <dir>]
// Serves a built SPA (default $TMPDIR/mdmesh-dist-v2, as compare-v2.mjs --build leaves it), drives /enroll with
// Playwright and prints one line per check. Exit code 1 when a check fails or the page logs an error.
import { chromium } from 'playwright';
import path from 'node:path';
import fs from 'node:fs';
import { fx, newContext, restData, startServer } from '../harness.mjs';
import { dist as DIST } from './lib.mjs';

const arg = (k, d) => {
  const i = process.argv.indexOf(k);
  return i > 0 ? process.argv[i + 1] : d;
};
const SHOTS = arg('--shots', null);

const results = [];
const check = (name, ok, extra = '') => {
  results.push({ name, ok: !!ok });
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}${extra ? `  (${extra})` : ''}`);
};

const { server, base } = await startServer(DIST);
const browser = await chromium.launch();
const errors = [];

/** New page with an optional stored default configuration and request overrides. */
async function open({ defaultCfg = null, viewport = { width: 1440, height: 1120 }, theme = 'light', setup, quietNet = false } = {}) {
  const ctx = await newContext(browser, fx.user, { theme, viewport, deviceScaleFactor: 1, blockGoogleFonts: true });
  await ctx.grantPermissions(['clipboard-read', 'clipboard-write'], { origin: base });
  if (defaultCfg !== null) await ctx.addInitScript((v) => localStorage.setItem('mdmesh-default-config', v), defaultCfg);
  const mints = [];
  ctx.on('request', (r) => {
    if (r.method() === 'POST' && r.url().includes('/agent/v1/token')) mints.push(JSON.parse(r.postData() || '{}'));
  });
  if (setup) await setup(ctx);
  const page = await ctx.newPage();
  page.on('pageerror', (e) => errors.push(`pageerror: ${e.message}`));
  page.on('console', (m) => {
    if (m.type() !== 'error') return;
    if (/fonts\.(googleapis|gstatic)\.com/.test(m.location()?.url ?? '')) return; // blocked on purpose (v2 self-hosts)
    if (quietNet && m.text().startsWith('Failed to load resource')) return; // the failure this case provokes
    errors.push(`console: ${m.text()} ${m.location()?.url ?? ""}`);
  });
  await page.goto(`${base}/enroll`);
  await page.getByRole('heading', { name: 'Enroll a device' }).waitFor();
  await page.waitForTimeout(400);
  return { ctx, page, mints };
}
const tokenRoute = (body) => async (ctx) =>
  ctx.route('**/rest/private/agent/v1/token', (route) =>
    route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(body) }),
  );

try {
  // 1. Stored default with saved Wi-Fi (the board's state).
  {
    const { ctx, page, mints } = await open({ defaultCfg: '3' });
    if (SHOTS) {
      fs.mkdirSync(SHOTS, { recursive: true });
      await page.screenshot({ path: path.join(SHOTS, 'enroll-default-1440-light.png') });
    }
    const select = page.getByLabel('Configuration', { exact: true });
    check('default configuration selected and marked', (await select.inputValue()) === '3' && (await select.locator('option:checked').textContent()).includes('Field Ops (default)'));
    check('server-setting option present', (await select.locator('option[value=""]').textContent()) === 'Server setting for new devices');
    check('first mint bound to the default configuration', mints[0]?.configurationId === 3, JSON.stringify(mints));
    const sw = page.getByRole('switch', { name: 'Join Wi-Fi during setup' });
    check('Wi-Fi switch on from the configuration', (await sw.getAttribute('aria-checked')) === 'true');
    check('SSID filled from the configuration', (await page.getByLabel('Network name').inputValue()) === 'Field-Setup');
    check('password filled', (await page.getByLabel('Wi-Fi password').inputValue()) === 'field-setup-2026');
    check('security filled', (await page.getByLabel('Security').inputValue()) === 'WPA');
    check('"Filled in from" note', await page.getByText('Filled in from Field Ops. Changes here only affect this code.').isVisible());
    check('password-in-QR warning', await page.getByText('The Wi-Fi password is written into the QR code.', { exact: false }).isVisible());
    const facts = page.locator('.v2-kv--facts');
    check('summary Gives / Joins Wi-Fi / Use / Expires', /Gives\s*Field Ops/.test(await facts.innerText()) && (await facts.innerText()).includes('Field-Setup') && (await facts.innerText()).includes('One device, then it stops working') && /in 24 hours/.test(await facts.innerText()));
    check('QR rendered with an accessible name', await page.getByRole('img', { name: 'Enrollment QR code for Field Ops' }).locator('.v2-qr__code').count() === 1);
    check('four steps, no "connect to Wi-Fi" while the code carries it', (await page.locator('.v2-numsteps li').count()) === 4 && !(await page.locator('.v2-numsteps').innerText()).includes('Connect to Wi-Fi'));
    check('footer server + agent URLs', (await page.locator('.v2-enroll__foot').innerText()).includes(`${base}/files/agent.apk`) && (await page.locator('.v2-enroll__foot').innerText()).includes(base));
    check('debug-defaults warning (build has no release checksum)', await page.getByText('No verified agent release was found', { exact: false }).isVisible());

    // New code: mints again for the SAME configuration.
    const before = mints.length;
    await page.getByRole('button', { name: 'New code' }).click();
    await page.waitForTimeout(300);
    check('New code mints for the selected configuration', mints.length === before + 1 && mints.at(-1).configurationId === 3, JSON.stringify(mints.at(-1)));
    check('New code keeps focus on the button', await page.evaluate(() => document.activeElement?.textContent?.includes('New code')));

    // Full screen.
    await page.getByRole('button', { name: 'Full screen' }).click();
    const dlg = page.getByRole('dialog');
    await dlg.waitFor();
    check('full screen shows the code', (await dlg.locator('.v2-qr__code').count()) === 1 && (await dlg.innerText()).includes('Scan to enroll: Field Ops'));
    // The code sits in the middle of the body; in a window too short for it (a landscape phone) the body scrolls from
    // the code's top edge, none of it out of reach above.
    const qrPlace = () =>
      page.evaluate(() => {
        const body = document.querySelector('dialog[open] .v2-modal__body');
        const frame = body.querySelector('.v2-qr__frame');
        const last = body.lastElementChild;
        body.scrollTop = 0;
        const b = body.getBoundingClientRect();
        const f = frame.getBoundingClientRect();
        const top = f.top - b.top;
        const centred = Math.abs(f.top + f.height / 2 - (b.top + b.height / 2)) < 40;
        body.scrollTop = body.scrollHeight;
        const bottom = b.bottom - last.getBoundingClientRect().bottom;
        body.scrollTop = 0;
        return { top: Math.round(top), bottom: Math.round(bottom), centred, scrolls: body.scrollHeight > body.clientHeight };
      });
    const roomy = await qrPlace();
    check('full screen centres the code', roomy.centred && !roomy.scrolls, JSON.stringify(roomy));
    await page.keyboard.press('Escape');
    await page.waitForTimeout(200);
    check('Escape closes full screen', !(await dlg.isVisible()));
    // Again in a short window (after Escape: a modal dialog open across a viewport resize no longer gets Escape in
    // headless Chromium, with or without this check), closed with its button.
    const size = page.viewportSize();
    await page.setViewportSize({ width: 844, height: 390 });
    await page.getByRole('button', { name: 'Full screen' }).click();
    await dlg.waitFor();
    await page.waitForTimeout(200);
    const cramped = await qrPlace();
    check('full screen in a short window scrolls from the code’s top edge', cramped.scrolls && cramped.top >= 0 && cramped.bottom >= 0, JSON.stringify(cramped));
    await dlg.getByRole('button', { name: 'Close' }).click();
    await page.waitForTimeout(200);
    await page.setViewportSize(size);
    check('…and its close button closes it', !(await dlg.isVisible()));

    // Security Open hides the password and the warning.
    await page.getByLabel('Security').selectOption('NONE');
    check('Open hides the password field', (await page.getByLabel('Wi-Fi password').count()) === 0);
    check('Open hides the password warning', (await page.getByText('The Wi-Fi password is written', { exact: false }).count()) === 0);
    await page.getByLabel('Security').selectOption('WPA');

    // Editing the SSID updates the summary.
    await page.getByLabel('Network name').fill('Lobby-Staff');
    check('typed SSID shows in the summary', (await facts.innerText()).includes('Lobby-Staff'));

    // Wi-Fi off.
    await sw.click();
    check('switch off hides the Wi-Fi fields', (await page.getByLabel('Network name').count()) === 0);
    check('switch off drops "Joins Wi-Fi" from the summary', !(await facts.innerText()).includes('Joins Wi-Fi'));
    check('step 3 asks to connect to Wi-Fi when the code carries none', (await page.locator('.v2-numsteps').innerText()).includes('Connect to Wi-Fi if the device asks'));
    await sw.click();
    check('switch on again restores the typed values', (await page.getByLabel('Network name').inputValue()) === 'Lobby-Staff');

    // Server setting: token without a configuration id, Wi-Fi left as typed.
    const n0 = mints.length;
    await select.selectOption('');
    await page.waitForTimeout(700); // the choice settles (300 ms) before a token is minted for it
    check('server setting mints with no configuration id', mints.length === n0 + 1 && mints.at(-1).configurationId === undefined, JSON.stringify(mints.at(-1)));
    check('server setting leaves the Wi-Fi fields', (await page.getByLabel('Network name').inputValue()) === 'Lobby-Staff');
    check('Gives says the server setting', (await facts.innerText()).includes('Server setting for new devices'));

    // A configuration without saved Wi-Fi clears the fields (as in the v0.3 console). Default has none (Retail Kiosk has one).
    await select.selectOption('1');
    await page.waitForTimeout(700);
    check('configuration without Wi-Fi turns the switch off', (await sw.getAttribute('aria-checked')) === 'false');
    check('…and mints for it', mints.at(-1).configurationId === 1);

    // Token tab with arrow keys.
    await page.getByRole('tab', { name: 'QR code' }).focus();
    await page.keyboard.press('ArrowRight');
    const tokenTab = page.getByRole('tab', { name: 'Token' });
    check('arrow key selects the Token tab', (await tokenTab.getAttribute('aria-selected')) === 'true');
    const tokenField = page.getByLabel('Enrollment token');
    const tokenValue = await tokenField.inputValue();
    check('token shown in a read-only field', /^5f0c9a2e-/.test(tokenValue) && (await tokenField.getAttribute('readonly')) !== null);
    check('headless note names the extras', (await page.locator('.v2-enroll__note').innerText()).includes('com.mdmesh.ENROLL_TOKEN') && (await page.locator('.v2-enroll__note').innerText()).includes('com.mdmesh.SERVER_URL'));
    await page.getByRole('button', { name: 'Copy' }).click();
    await page.waitForTimeout(200);
    const clip = await page.evaluate(() => navigator.clipboard.readText());
    check('Copy puts the token on the clipboard', clip === tokenValue);
    check('Copy toast "Token copied"', await page.getByText('Token copied', { exact: true }).isVisible());
    const n1 = mints.length;
    await page.getByRole('button', { name: 'New token' }).click();
    await page.waitForTimeout(300);
    check('New token mints for the selected configuration', mints.length === n1 + 1 && mints.at(-1).configurationId === 1);
    check('new token value differs', (await page.getByLabel('Enrollment token').inputValue()) !== tokenValue);
    await ctx.close();
  }

  // 2. Stale stored default falls back to the server setting.
  {
    const { ctx, page, mints } = await open({ defaultCfg: '99' });
    await page.waitForTimeout(700);
    check('stale default falls back to the server setting', (await page.getByLabel('Configuration', { exact: true }).inputValue()) === '' && mints.at(-1)?.configurationId === undefined, JSON.stringify(mints));
    check('stale default: never minted for, and forgotten', mints.length === 1 && mints.every((m) => m.configurationId === undefined) && (await page.evaluate(() => localStorage.getItem('mdmesh-default-config'))) === null, JSON.stringify(mints));
    await ctx.close();
  }

  // 3. Token errors.
  {
    const { ctx, page } = await open({
      setup: tokenRoute({ status: 'ERROR', message: 'error.permission.denied', data: null }),
    });
    const alert = page.getByRole('alert').filter({ hasText: 'permission' });
    check('server error shown as a sentence', await alert.isVisible());
    check('no QR while there is no token', (await page.locator('.v2-enroll .v2-qr__code').count()) === 0 && (await page.getByText('No code yet.').isVisible()));
    await ctx.unroute('**/rest/private/agent/v1/token');
    await alert.getByRole('button', { name: 'Try again' }).click();
    await page.waitForTimeout(400);
    check('Try again recovers', (await page.getByRole('alert').count()) === 0 && (await page.locator('.v2-enroll .v2-qr__code').count()) === 1);
    await ctx.close();
  }
  {
    const { ctx, page } = await open({ setup: tokenRoute({ status: 'OK', message: null, data: {} }) });
    check('missing token message', await page.getByText('The server did not return a token.').isVisible());
    await ctx.close();
  }
  {
    const { ctx, page } = await open({
      quietNet: true,
      setup: async (c) => c.route('**/rest/private/agent/v1/token', (r) => r.abort()),
    });
    check('unreachable server message', await page.getByText('Cannot reach the server.').isVisible());
    await ctx.close();
  }

  // 4. Expired token.
  {
    const { ctx, page } = await open({
      setup: tokenRoute({ status: 'OK', message: null, data: { token: 'expired-token', expiresAt: Date.now() - 1000 } }),
    });
    check('expired code withdrawn with a prompt', (await page.getByText('This code has expired. Make a new one', { exact: false }).isVisible()) && (await page.locator('.v2-enroll .v2-qr__code').count()) === 0);
    check('expired summary', (await page.locator('.v2-kv--facts').innerText()).includes('Expired'));
    await ctx.close();
  }

  // 5. Configurations fail to load.
  {
    const { ctx, page } = await open({
      defaultCfg: '3',
      quietNet: true,
      setup: async (c) => c.route('**/rest/private/configurations/search', (r) => r.fulfill({ status: 500, body: '' })),
    });
    check('configurations load failure explained', await page.getByText('Couldn’t load the configurations', { exact: false }).isVisible());
    check('stored default kept by id when the list failed', (await page.getByLabel('Configuration', { exact: true }).inputValue()) === '3');
    await ctx.unroute('**/rest/private/configurations/search');
    await page.getByRole('button', { name: 'Retry' }).click();
    await page.waitForTimeout(400);
    check('Retry loads them', (await page.getByLabel('Configuration', { exact: true }).locator('option:checked').textContent()).includes('Field Ops (default)'));
    await ctx.close();
  }

  // 7. A slow first mint: "Making a code…" until it lands; a later choice's code wins over its late answer.
  {
    let calls = 0;
    const { ctx, page, mints } = await open({
      defaultCfg: '3',
      setup: async (c) =>
        c.route('**/rest/private/agent/v1/token', async (route) => {
          const n = ++calls;
          if (n === 1) await new Promise((r) => setTimeout(r, 1800));
          const body = { status: 'OK', message: null, data: { token: n === 1 ? 'first-slow-token' : `later-token-${n}`, expiresAt: Date.now() + 86_400_000 } };
          await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(body) }).catch(() => {});
        }),
    });
    check('slow first mint: no code yet, "Making a code…"', (await page.locator('.v2-enroll .v2-qr__code').count()) === 0 && (await page.locator('.v2-enroll__qrwait').innerText()).includes('Making a code'));
    await page.locator('.v2-enroll .v2-qr__code').waitFor({ timeout: 5000 }).catch(() => {});
    check('slow first mint: the QR appears once it lands', (await page.locator('.v2-enroll .v2-qr__code').count()) === 1 && mints.length === 1);
    // Again, but change the configuration while the first answer is still on its way.
    calls = 0;
    await page.getByRole('button', { name: 'New code' }).click(); // call 1: slow, for Field Ops
    await page.waitForTimeout(100);
    await page.getByLabel('Configuration', { exact: true }).selectOption('1');
    await page.waitForTimeout(800); // call 2 (Default) settles and lands first
    await page.getByRole('tab', { name: 'Token' }).click();
    const later = await page.getByLabel('Enrollment token').inputValue().catch(() => '');
    await page.waitForTimeout(1500); // the slow answer for Field Ops lands now
    const after = await page.getByLabel('Enrollment token').inputValue().catch(() => '');
    check('a late answer for the previous configuration is dropped', later === 'later-token-2' && after === 'later-token-2', `${later} → ${after}`);
    check('…and the code is for the chosen configuration', mints.at(-1)?.configurationId === 1, JSON.stringify(mints));
    await ctx.close();
  }

  // 8. Arrowing through the configuration select mints one token, for the option it settles on.
  {
    const { ctx, page, mints } = await open({ defaultCfg: '3' });
    const select = page.getByLabel('Configuration', { exact: true });
    const values = await select.locator('option').evaluateAll((os) => os.map((o) => o.value));
    const n0 = mints.length;
    await select.focus();
    for (let i = 0; i < 4; i++) {
      await page.keyboard.press(i % 2 ? 'ArrowUp' : 'ArrowDown');
      await page.waitForTimeout(60);
    }
    for (const v of values.slice(0, 3)) {
      await select.selectOption(v); // as fast as the user can arrow
      await page.waitForTimeout(60);
    }
    const last = values[2];
    await page.waitForTimeout(900);
    check('arrowing through the select mints once after it settles', mints.length === n0 + 1, `${mints.length - n0} mints`);
    check('…for the option it settled on', (mints.at(-1)?.configurationId ?? '') === (last === '' ? '' : Number(last)), `${JSON.stringify(mints.at(-1))} vs ${last}`);
    check('…and its QR is up', (await page.locator('.v2-enroll .v2-qr__code').count()) === 1);
    await ctx.close();
  }

  // 9. EAP Wi-Fi saved on a configuration shows as EAP, not WPA.
  {
    const rows = restData('GET', '/private/configurations/search').map((c) => (c.id === 3 ? { ...c, wifiSecurityType: 'EAP' } : c));
    const { ctx, page } = await open({
      defaultCfg: '3',
      setup: async (c) =>
        c.route('**/rest/private/configurations/search', (r) =>
          r.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ status: 'OK', message: null, data: rows }) }),
        ),
    });
    const sec = page.getByLabel('Security');
    check('EAP from the configuration selected', (await sec.inputValue()) === 'EAP' && (await sec.locator('option:checked').textContent()).includes('EAP (from the configuration)'));
    check('EAP keeps the password field', (await page.getByLabel('Wi-Fi password').count()) === 1);
    check('Wi-Fi password is not autofilled from saved logins', (await page.getByLabel('Wi-Fi password').getAttribute('autocomplete')) === 'new-password');
    // The server setting no longer claims the fields came from a configuration.
    await page.getByLabel('Configuration', { exact: true }).selectOption('');
    await page.waitForTimeout(200);
    check('server setting drops "Filled in from"', (await page.getByText('Filled in from', { exact: false }).count()) === 0 && (await page.getByText('Changes here only affect this code.').isVisible()));
    await ctx.close();
  }

  // 6. Phone width: Enroll card first, no sideways scroll; dark keeps a white QR frame.
  {
    const { ctx, page } = await open({ defaultCfg: '3', viewport: { width: 390, height: 844 }, theme: 'dark' });
    const order = await page.locator('.v2-enroll > section h2').allTextContents();
    check('phone: Enroll the device comes first', order[0] === 'Enroll the device', order.join(' | '));
    const sw = await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth);
    check('phone: no horizontal scroll', sw <= 0, `overflow ${sw}px`);
    const bg = await page.locator('.v2-qr__frame').first().evaluate((el) => getComputedStyle(el).backgroundColor);
    check('dark: QR frame stays white', bg === 'rgb(255, 255, 255)', bg);
    if (SHOTS) await page.screenshot({ path: path.join(SHOTS, 'enroll-default-390-dark.png'), fullPage: true });
    await ctx.close();
  }
} finally {
  await browser.close();
  server.close();
}

for (const e of errors) console.log(`ERROR  ${e}`);
const failed = results.filter((r) => !r.ok).length;
console.log(`\n${results.length - failed}/${results.length} checks pass, ${errors.length} page errors`);
process.exit(failed || errors.length ? 1 : 0);
